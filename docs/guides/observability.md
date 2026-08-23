# Observability

Three seams, three audiences. `TurnObserver` narrates one turn to whoever is
watching it happen — a REPL, an SSE emitter. `AgentObserver` narrates the
shell's own machine-level decisions to whoever operates the host — a metrics
sink, an incident log. `AuthorizationReport` is neither a stream nor a
listener; it reads a harness's grants back as a static report, because the
report *is* the wiring. All three default to silence — a noop observer, an
empty grant list — so watching costs nothing until something is plugged in.

## `TurnObserver` — the audience stream

`TurnObserver` sees the live story of one turn: the model speaking and
thinking, homework requested and decided, homework settled, the turn's
close. It is bound per entry — the observer handed to `tell`/`drive` sees
only the segment that call starts, and nothing after a park. An unattended
scope with no observer wired runs every turn against `TurnObserver.noop()`
and loses nothing it needed.

```java
public sealed interface TurnEvent {
  record TextDelta(String text) implements TurnEvent {}
  record ThinkingDelta(String text) implements TurnEvent {}
  record RedactedThinking(String data) implements TurnEvent {}
  record ToolCallRequested(ToolCall call) implements TurnEvent {}
  record ToolCallDecided(ToolCall call, Decision decision) implements TurnEvent {}
  record ToolCallCompleted(ToolCall call, ToolResult result) implements TurnEvent {}
  record ToolCallProgressed(ToolCall call, String message) implements TurnEvent {}
  record AssistantSaid(Message message) implements TurnEvent {}
  record TurnEnded(String failureReason) implements TurnEvent {}
}
```

`AssistantSaid` is the settled sentence; the delta variants were only its
preview. `TurnEnded.failureReason()` is `null` for a completed turn and
carries the reason when the model call itself failed. A parked call is
never narrated at all — parking is executor bookkeeping, indistinguishable
from a slow call by design, and the resumption token it would carry is a
capability that handing to every listener would turn into a shadow way to
act on the call. `TurnEvent` narrates the model's turn, never that.

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

## `AgentObserver` — the operator stream

Where `TurnObserver` narrates the model's turn, `AgentObserver` narrates
what the shell itself decided — exactly the fact applied and the whole
transition it produced, including the next phase:

```java
public interface AgentObserver {
  void applied(AgentEvent event, Transition transition);
  void ignored(AgentEvent event);
  void renderFailed(Object observation, RuntimeException error);
  void applyFailed(AgentEvent event, RuntimeException error);
  void reFired(List<Effect> effects);
  void observationRequeued(Object observation);
}
```

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

`AgentObserver.noop()` is the default everywhere a harness is built without
one, via `HarnessConfig#agentObserver(AgentObserver)`.

None of these events originate from a live wait. `DeliveryWorker` — one
heartbeat thread per harness, plus an immediate synchronous drain right after
any completion commits — is what turns a pending outbox delivery back into
an `applied` (or `ignored`) fact on this same observer; a grant's dispatch
and a durable tool's eventual result both surface here exactly like any
other transition, with no separate "resumed from durable storage" event of
its own. There is nothing to observe about the worker itself beyond that:
it narrates through the same seam every other transition does. See
[Durable Computation](../concepts/durable-computation.md) for the pipeline
underneath it.

**Observers narrate; they never influence.** Nothing here can change what
the shell does — no return value feeds back into the transition, and
nothing about authorization runs through this seam either: a grant's
policy, its enrichers, and its rendered action are never broadcast to an
`AgentObserver`, only their outcome shows up, folded into whichever
`ToolResult` the applied transition already carries. A listener that could
affect the flow would create ordering dependence between listeners and a
shadow decision surface competing with the authorization ladder — every
seam that is actually allowed to change behavior (memory, the state store,
the backlog, both executors, the grants themselves) is its own interface,
not this one.

## `AuthorizationReport` — the third leg, and it isn't a stream

`TurnObserver` and `AgentObserver` narrate what already happened. What
*would* happen — which grants render an action, which enrichers run, which
policy decides — is not a live event at all; it is read back once, statically,
from the harness's own wiring:

```java
AuthorizationReport report = AuthorizationReport.of(grants);
System.out.println(report.render());
```

```
restart_prod: action(restart-statement) → intent → risk → policy (ThresholdPolicy)
read-balance: allow()
```

`AuthorizationReport.of(...)` reads each grant's `tool()`, `policy()`,
`enrichers()`, and `contributor().displayName()` by declaration — never by
calling `actionOf`, `enrich`, or `evaluate` — so it cannot drift from the
wiring it describes; there is no way for the report to say one thing while
the executor does another. See [Authorization](../concepts/authorization.md#authorizationreport-the-report-is-the-wiring)
for the full grammar of what a grant's story can say.

## Where next

- [Authorization](../concepts/authorization.md) — the trust gradient
  `ToolCallDecided` and `AuthorizationReport` both describe.
- [The harness guide](harness.md) — `Nessy.harness(...)`'s full
  builder surface, `turnObserver` and `agentObserver` alongside it.
- [Durable Computation](../concepts/durable-computation.md) — the shell,
  the fold, and the events `AgentObserver` narrates the outcome of.
