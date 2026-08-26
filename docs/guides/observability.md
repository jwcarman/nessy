# Observability

Nessy exports two things: the live story of one turn (`TurnObserver`), and
the numbers a soak needs — spans and counters over the OpenTelemetry GenAI
semantic conventions, recorded through one Micrometer seam
(`HarnessConfig.observationRegistry(ObservationRegistry)`). A third seam,
`AuthorizationReport`, is neither a stream nor a listener; it reads a
harness's grants back as a static report, because the report *is* the
wiring. All three default to silence — a noop observer, `ObservationRegistry
.NOOP`, an empty grant list — so watching costs nothing until something is
plugged in.

## `TurnObserver` — the audience stream

`TurnObserver` sees the live story of one turn: the model speaking and
thinking, homework requested and decided, homework settled, the turn's
close. No observer is handed to `tell`/`drive` directly — an observer
reaches an id's turns by `Agent#subscribe`ing to the harness's internal
per-id fanout, which is worker-inclusive: a subscriber registered before a
park still sees the turn that resumes it, even though the resumption folds
on the harness's own worker thread, not the one that called `tell`. The
harness's own configured `turnObserver` rides the same fanout as one more
subscriber. An unattended scope with no observer wired runs every turn
against `TurnObserver.noop()` and loses nothing it needed.

```java
public sealed interface TurnEvent {
  record TextDelta(String text) implements TurnEvent {}
  record ThinkingDelta(String text) implements TurnEvent {}
  record RedactedThinking(String data) implements TurnEvent {}
  record ToolCallRequested(ToolCall call) implements TurnEvent {}
  record ToolCallDecided(ToolCall call, Approval approval) implements TurnEvent {}
  record ToolCallCompleted(ToolCall call, ToolResult result) implements TurnEvent {}
  record ToolCallProgressed(ToolCall call, String message) implements TurnEvent {}
  record AssistantSaid(Message message) implements TurnEvent {}
  record TurnEnded(String failureReason) implements TurnEvent {}
}
```

`AssistantSaid` is the settled sentence; the delta variants were only its
preview. `TurnEnded.failureReason()` is `null` for a completed turn and
carries the reason when the model call itself failed. A parked call is
never narrated *here* — from the model's turn a park is indistinguishable
from a slow call by design, and the resumption token it would carry is a
capability that handing to every listener would turn into a shadow way to
act on the call. `TurnEvent` narrates the model's turn, never that. The park
does have a channel: `HarnessObserver` sees `ApprovalDeferred`, below.

Three ways to build one:

- A bare lambda, when one concern covers every event: `event -> log.info("{}", event)`.
- `TurnObserver.observe(TurnObserverCustomizer)`, composing per-variant
  consumers — the composition-friendly rung between a lambda and a
  subclass:

  ```java
  TurnObserver observer =
      TurnObserver.observe(
          o ->
              o.onTextDelta(delta -> terminal.print(delta.text()))
                  .onToolCallCompleted(done -> statusBar.flash(done.call().name())));
  ```

  Registering the same variant twice chains rather than replaces, so two
  independent concerns — a journal and a renderer — can both hear the same
  events in registration order.
- `TurnObserver.logging(Logger, prefix)` — the standard narrator: one
  `says:` line per non-blank `AssistantSaid`, a line each for a tool
  requested and completed, and the turn's closing line at `INFO`, with the
  failure reason repeated at `WARN` when the turn failed. The `prefix`
  overload takes a `Supplier<String>` for a tag not yet known when the
  observer is built — a correlation id minted only once a drive returns.

Throw semantics are asymmetric. A throwing observer aborts the call it
narrates on the model path — the observer is the caller's own code, so its
exception is the caller's exception. `ToolCallProgressed` is the exception
to the exception: a misbehaving progress narrator is logged and dropped
rather than propagated, so a bug in a status bar can't kill a tool call that
was otherwise succeeding. Narration is at-least-once, matching the shell's
own retry-on-stale-save discipline: a retried apply can narrate the same
event twice, so an observer materializing per-event UI should dedupe by the
event's natural key.

### `RelayTurnObserver` in the CLI

`Nessy.cli()` builds its own `RelayTurnObserver` internally — an
`AtomicReference<TurnObserver>` that `converse(...)` points at a fresh
`AwaitingReply` for the duration of each call, and drops events with
nowhere to go the rest of the time. `AwaitingReply` itself is a `TurnObserver`
that ignores everything except `AssistantSaid` (buffered as the pending
reply) and `TurnEnded` (which completes or fails the future `converse`
blocks on). There is no public seam today for a `Nessy.cli()` caller to also
attach a streaming renderer alongside it — the relay is the CLI's own
internal wiring, not yet an exposed extension point.

### The harness's `turnObserver` seam

`Nessy.harness(...)` takes a `TurnObserver` directly, wired once inside the
customizer and shared by every scope the harness serves — `HarnessConfig`
carries no per-call relay, since there is no caller thread parked on any one
turn to hand it to:

```java
var harness =
    Nessy.harness(
        h ->
            h.model(claude)
                .systemPrompt(prompt)
                .turnObserver(TurnObserver.logging(logger, "ops")));
```

The default is `TurnObserver.noop()`.

## The roster — OTel GenAI spans and counters

Nessy has one fact stream per harness: both fold sites — `DefaultAgent`'s
synchronous shell and `DeliveryWorker`'s durable one — publish
`(agentId, event, transition)` through it, and everything interested
subscribes. `HarnessConfig.observationRegistry(ObservationRegistry)` is the
one seam: absent, the harness runs against `ObservationRegistry.NOOP` and
the roster below is inert. Supplied, a package-private `Observations`
object subscribes to the stream and turns folds into Micrometer
`Observation`s named per the OpenTelemetry GenAI semantic conventions
(pinned here against the **2025 `gen_ai.*` attribute set** — GenAI semconv
is still *development* status upstream and `gen_ai.system` became
`gen_ai.provider.name` in that revision; the CHANGELOG names the exact
attributes this build implements against). `nessy-agent` depends on
`micrometer-observation` only — exporters, the OTel tracing bridge, and
OTLP live in the application; see `nessy-examples/observed` for the whole
wiring and how to point it at a collector.

### Spans

| Observation (contextual name) | `gen_ai.operation.name` | opens | closes | attributes |
|---|---|---|---|---|
| `invoke_agent {agentType}` | `invoke_agent` | a segment starts | the segment ends | `gen_ai.agent.name`, `gen_ai.provider.name`, `gen_ai.request.model`, `gen_ai.agent.id`, `gen_ai.conversation.id`; `nessy.turn.outcome` = complete / parked / failed |
| `chat {model}` | `chat` | `Model.stream` is called | the stream closes | `gen_ai.provider.name`, `gen_ai.request.model`, `gen_ai.request.stream`=true, `gen_ai.request.max_tokens`, `gen_ai.response.finish_reasons`, `gen_ai.response.time_to_first_chunk`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, `gen_ai.usage.cache_read.input_tokens`, `gen_ai.usage.cache_write.input_tokens`; `error.type` |
| `execute_tool {tool}` | `execute_tool` | `tool.execute` is called | it returns | `gen_ai.tool.name`, `gen_ai.agent.name`, `gen_ai.tool.call.id`, `gen_ai.tool.type`=function; `error.type`; `nessy.tool.deferred` |
| `search_memory` | `search_memory` | `Memory.recall` is called | it returns | `gen_ai.agent.name`, `gen_ai.memory.record.count`; `error.type` |
| `create_memory` | `create_memory` | `Memory.remember` is called | it returns | `gen_ai.agent.name`, `gen_ai.memory.record.count`=1; `error.type` |
| `nessy.approval.wait {tool}` | — | `ApprovalDeferred` applied | `ApprovalAnswered` applied | `gen_ai.tool.name`, `gen_ai.tool.call.id`, `nessy.approval.answer` |
| `nessy.tool.wait {tool}` | — | `ToolDeferred` applied | `ToolFinished` applied | `gen_ai.tool.name`, `gen_ai.tool.call.id`, `nessy.tool.outcome` |

`gen_ai.agent.id`, `gen_ai.conversation.id`, and `gen_ai.tool.call.id` are
**high-cardinality** key-values in Micrometer's terms — they ride the span,
never a meter.

**A span is not a turn.** An `invoke_agent` span runs one *segment* — from
`Observed` (or a delivery that resumes the scope) applied, to the next
`Idle` or the next park (a phase with no call left `Running` or `Pending`).
A turn that waits six hours on a human would otherwise hold an open span
across a crash, a restart, and a redeploy — an OTel span that survives none
of those is a lie. The wait spans carry the dwell between segments instead,
and they are what a dashboard reads for "how long do humans take." In-flight
spans die with the process; nothing reconstructs one after a restart — they
are telemetry, and the phase in the store is the truth.

**The idiom `"none"`.** Every outcome-bearing key (`nessy.turn.outcome`,
`nessy.approval.answer`, `nessy.tool.outcome`, `nessy.tool.deferred`,
`gen_ai.response.finish_reasons`, `error.type`) is set at **start** to the
placeholder `"none"` and overwritten once the outcome is known. Micrometer
requires every observation sharing a name to carry the same set of
low-cardinality keys — a `chat` that only sometimes carried
`error.type` would be a meter with unstable tags, which a strict registry
rejects outright and a real backend corrupts. A reader of these spans must
treat `"none"` as "not yet known / not applicable," including on a span that
finished successfully: `error.type=none` on a healthy `chat` is the
documented shape, not a bug.

**Parent/child.** `chat` and `execute_tool` are children of the scope's open
`invoke_agent` segment; wait spans are children of the segment that parked
them. A tracing backend renders segment → chat → execute_tool → wait as one
trace per segment, and `gen_ai.conversation.id` stitches segments across
parks.

### Metrics

Micrometer's default handlers derive a timer from every span above. Semconv
defines a separate duration histogram per operation boundary, each with its
own attribute set, and each observation is **named for its own metric** —
the span name rides as the `contextualName` instead:

| Observation name (the meter) | Contextual name (the span) |
|---|---|
| `gen_ai.client.operation.duration` | `chat {model}` |
| `gen_ai.invoke_agent.duration` | `invoke_agent {agentType}` |
| `gen_ai.execute_tool.duration` | `execute_tool {tool}` |
| `search_memory` / `create_memory` | same (semconv defines no memory duration metric) |
| `nessy.approval.wait` / `nessy.tool.wait` | `nessy.approval.wait {tool}` / `nessy.tool.wait {tool}` |

**Match on the meter name, not the span name.** An `ObservationHandler`
reads `context.getName()`, which is the left column. A handler written
against `"chat"` matches nothing.

Three names rather than one is what semconv asks for, and it satisfies
Micrometer's rule for free: a meter requires one stable low-cardinality key
set per name, and semconv already partitions the attributes per metric.

`gen_ai.client.token.usage` is application-side: an `ObservationRegistry`
times observations, it cannot record a value histogram. The `chat` span carries
the vendor's own token counts as key-values
(`gen_ai.usage.input_tokens`/`output_tokens`); a ten-line
`ObservationHandler` reads them on `onStop` and records the metric to its
own `MeterRegistry` — `nessy-agent` never sees a `MeterRegistry` at all.
`nessy-examples/observed` ships that handler.

Three counters, spelled as zero-duration observations (an `ObservationRegistry`
has no direct counter API), tagged `gen_ai.agent.name` only:

- `nessy.delivery.dropped` — a genuine delivery (an answered approval, a
  completed tool result) that arrived against a phase that no longer wanted
  it.
- `nessy.state.stale_retries` — one per `StaleStateException`/
  `ConflictException` retry the two fold sites absorb.
- `nessy.effects.refired` — one per effect the recovery arm re-dispatched.

### Telemetry never breaks a turn

An `ObservationHandler` is arbitrary application code, running inline on
whichever thread started or stopped the span, and a turn must never fail
because the thing describing it did. So every `start()`, `stop()` and
`error()` call is contained — the `chat` and `execute_tool` spans at their
own call sites inside the two executors, the three engine counters inside
`Observations`, and the segment and wait spans by the fact stream's own
per-subscriber isolation, since those are opened and closed from a
`HarnessObserver` callback. A failed `start()` yields `Observation.NOOP`, so
the `stop()` that follows is a harmless no-op; a throwing handler is logged
once at `WARN` and dropped. Key-value writes are not wrapped, and need not
be: they only mutate the observation's context and invoke no handler.
This applies all the way down — an application handler that reads
`gen_ai.usage.input_tokens` off a `chat` that failed before the model
reported any usage throws `NullPointerException` inside `onStop`, and the
turn still completes with its real outcome.

### Known bound: a wait parked by one harness and answered by another

Two harnesses sharing a type, a substrate and a Continuum are a supported
shape (see the harness guide), and either may deliver what the other parked.
The wait span, though, is an open `Observation` living in the parking
harness's heap alone — so an answer folded by the *other* harness closes
nothing: the first harness's `nessy.approval.wait` stays open until its
process ends, and the second records a close for a wait it never opened,
which is a no-op.

Nothing is corrupted and no fold is affected; the dwell simply goes
unrecorded, exactly as an in-flight span already dies with a restart. Fixing
it would mean reconstructing spans from durable state, which is the thing
the segment rule above exists to refuse. If you need the dwell of every parked call in a multi-harness
deployment, read it from the approval desk's own records, not from the spans.

## `HarnessObserver` — the fact stream's own subscriber

Where `TurnObserver` narrates the model's turn, `HarnessObserver` narrates
what the shell itself decided — exactly the fact applied and the whole
transition it produced, including the next phase. It is the same stream the
roster above subscribes to; the harness's own narrating adapter — the one
that turns an applied fold into the `AssistantSaid`/`TurnEnded` turn events
the CLI door has always shown — is always its first subscriber, and every
caller-supplied one sits beside it and beside `Observations`, seeing every
fact this harness produces:

```java
public interface HarnessObserver {
  void applied(AgentId id, AgentEvent event, Transition transition);
  void ignored(AgentId id, AgentEvent event);
  void renderFailed(AgentId id, Object observation, RuntimeException error);
  void applyFailed(AgentId id, AgentEvent event, RuntimeException error);
  void reFired(AgentId id, List<Effect> effects);
  void observationRequeued(AgentId id, Object observation);
}
```

Every method leads with the `AgentId` the fact is about — one
`HarnessObserver` instance serves every scope the harness runs, unlike the
retired per-scope `AgentObserver` a factory used to stamp fresh per id.

- **`applied`** — the normal case: one event folded, the resulting
  transition (commits and effects included) handed over for whoever wants
  to build metrics or a trajectory log from it.
- **`ignored`** — a stale or duplicate completion, discarded before
  anything was written.
- **`renderFailed`** — a renderer threw; the observation is discarded and
  the scope stays idle rather than wedging on a bad render.
- **`applyFailed`** — applying a completion threw (a malformed delivery, a
  phase-contract violation); the event is dropped and narrated, and the
  scope's phase is unchanged.
- **`reFired`** — the recovery arm re-dispatched a stalled phase's
  outstanding effects.
- **`observationRequeued`** — an observation lost the idle race and went
  back to the backlog.

**`HarnessConfig#harnessObserver(HarnessObserver)` is additive** — each call
subscribes one more observer, in call order, and never replaces the default
narrator. A harness built with no calls to it at all still narrates: the
default narrating adapter is not something you opt into, it is something you
cannot opt out of. `HarnessObserver.noop()` is for a caller-supplied
observer that genuinely wants to see nothing (a placeholder in a test, say)
— it does not silence the narrator, because nothing does.

Publishes for one agent id are **not** guaranteed to arrive in commit order:
each fold site publishes after its CAS, not under it, so two concurrent folds
on one scope can reach an observer either way round. An observer holding
per-scope state must tolerate a close before its open. Every fact you are
handed did commit; only the order is unguaranteed.

None of these events originate from a live wait. `DeliveryWorker` — a small
scheduled pump pool per harness (`ComputationScheduler`), plus a drain pass
`nudge()` submits to that same pool right after any completion commits — is
what turns a pending Continuum delivery back into an `applied` (or
`ignored`) fact on this same stream; the submitted drain runs asynchronously,
not on the completing caller's own thread. A grant's dispatch and a durable
tool's eventual result both surface here exactly like any other transition,
with no separate "resumed from durable storage" event of its own — and,
since the fact stream reform, a delivered fold now narrates through the same
door a synchronous one always has, where before a worker-driven completion
narrated nothing at all. See
[Durable Computation](../concepts/durable-computation.md) for the pipeline
underneath it.

**Observers narrate; they never influence.** Nothing here can change what
the shell does — no return value feeds back into the transition, and
nothing about authorization runs through this seam either: a grant's
approver, its enrichers, and its rendered action are never broadcast to a
`HarnessObserver`, only their outcome shows up, folded into whichever
`ToolResult` the applied transition already carries. A listener that could
affect the flow would create ordering dependence between listeners and a
shadow decision surface competing with the authorization ladder — every
seam that is actually allowed to change behavior (memory, the state store,
the backlog, both executors, the grants themselves) is its own interface,
not this one.

Every subscriber on the stream — the configured `HarnessObserver` and the
o11y roster alike — is isolated: a throw is logged and dropped, never
propagated into the fold. This is stricter than `TurnObserver`'s own
throw-through contract above, because by the time a fact is published the
fold has already committed; letting a bad subscriber's exception escape
would corrupt an outcome that is already true in the store.

## `AuthorizationReport` — the third leg, and it isn't a stream

`TurnObserver` and `HarnessObserver` narrate what already happened. What
*would* happen — which grants render an action, which enrichers run, which
approver answers — is not a live event at all; it is read back once, statically,
from the harness's own wiring:

```java
AuthorizationReport report = AuthorizationReport.of(grants);
System.out.println(report.render());
```

```
restart_prod: action(restart-statement) → intent → risk → approver (RiskThresholdApprover)
read-balance: allow()
```

`AuthorizationReport.of(...)` reads each grant's `tool()`, `approver()`,
`enrichers()`, and `contributor().displayName()` by declaration — never by
calling `actionOf`, `enrich`, or `approve` — so it cannot drift from the
wiring it describes; there is no way for the report to say one thing while
the executor does another. See [Authorization](../concepts/authorization.md#authorizationreport-the-report-is-the-wiring)
for the full grammar of what a grant's story can say.

## Where next

- [Authorization](../concepts/authorization.md) — the trust gradient
  `ToolCallDecided` and `AuthorizationReport` both describe.
- [The harness guide](harness.md) — `Nessy.harness(...)`'s full
  builder surface, `turnObserver`, `harnessObserver`, and
  `observationRegistry` alongside it.
- [Durable Computation](../concepts/durable-computation.md) — the shell,
  the fold, and the events `HarnessObserver` narrates the outcome of.
