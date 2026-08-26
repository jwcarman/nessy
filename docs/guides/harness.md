# The Harness

Ask Nessy for a harness; keep it forever; bind any id into a transient
agent; tell it things. That's the whole shape:

```java
var harness =
    Nessy.harness(
        h ->
            h.type("ops")
                .model(anthropic.model("claude-sonnet-5"))
                .systemPrompt("You are the ops assistant.")
                .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, Approvers.defer()))
                .substrate(substrate));

harness.bind(AgentId.of("prod-eu")).tell("please restart prod-eu");
```

`harness.bind(id)` returns a plain `Agent<O>` — thin, transient, holding
nothing. `.tell(observation)` enqueues a fact for that scope and returns
immediately; the reply is narrated, not returned — see
[Observability](observability.md) for `TurnObserver`.

## Kept, not closed

The harness is the thing your application maintains a reference to, for as
long as it runs. It is not `AutoCloseable`, and no example in this guide
opens one in a `try`-with-resources. Its life-support — the delivery
worker's deliver/expire/purge pumps, for both the approval and tool
kinds — runs on a small shared pool of daemon threads and lives exactly as
long as the process does.

One undecorated lifecycle method exists, `shutdown()`, and it is
infrastructure-only:

```java
harness.shutdown();
```

Reach for it from a Spring destroy callback or a test's `@AfterEach` —
quiescing the worker while the harness is still reachable is a container's
job, never application hygiene. Nothing about `shutdown()` invites a
try-with-resources; deliberately, it doesn't implement `AutoCloseable`, so
nothing reaches for it by accident.

## Building a harness

`Nessy.harness(HarnessCustomizer<String>)` opens the `String`-observation
door — the same customizer grammar `Tool.of(type, customizer)` already
teaches: the lambda fills in a live `HarnessConfig`, and Nessy alone turns
it into the finished `Harness` the instant the lambda returns. No public
`build()` survives on the config; there is no half-configured builder
object in application hands.

The builder surface, piece by piece:

- **`.model(Model)`** — required, explicit, no environment fallback: the
  one true dependency stays visible. A `Model` is a bound handle from a
  vendor gateway (see [Providers](providers.md)) — it already knows which
  model it runs, so no model string threads through the harness at all.
- **`.systemPrompt(String)`** — required, first-class harness-level
  configuration. It does not live on `ModelSettings`.
- **`.settings(ModelSettings)`** — the OPTIONAL tuning bag: max-tokens
  (default 8192), requested capabilities, context window. Omit it and the
  harness runs on `ModelSettings.defaults()`.
- **`.type(String)`** — the recipe's name, the first coordinate of every
  durable address (`AgentType`); default `"agent"`.
- **`.grants(ToolGrant...)`** — the tool grants every scope carries,
  authority and all; each grant pairs a `Tool` with the `Approver` the
  executor consults before it runs. `.tools(Tool<?>...)` is sugar for
  granting each `Approvers.allow()`.
- **`.substrate(Substrate)`** — the one storage seam (see
  [Storage](../concepts/storage.md)): every scope's state, memory, and
  backlog live as documents in this substrate; default a fresh
  `InMemorySubstrate`, durable only for the process's lifetime. Supply a
  durable `Substrate` to persist those beyond the process — but that alone
  does not make approvals or deferred tool calls durable; see the next
  bullet. There is no per-id cache: `bind(id)` stamps a fresh handle on
  every call, and the substrate document each recipe reads is what makes a
  scope's history survive from one binding to the next.
- **`.continuum(Continuum)`** — the computation store for approvals and
  deferred tool calls. Omitted, `.finish()` mints a private, in-memory
  Continuum that lives exactly as long as the harness and is visible to no
  other. Supplied, the harness uses yours: a `continuum-jdbc`-backed one
  makes parked calls survive the process, and the *same instance* handed
  to two harnesses lets either deliver what the other parked. Its
  durability must match `.substrate(...)`'s — both in memory or both
  durable — and the harness warns when it can tell they differ (a durable
  substrate with no Continuum supplied). There is no factory or policy
  seam between the two: `.finish()` opens one approval client and one tool
  client off this Continuum and hands the executor both, required, never
  null — a tool's own `context.defer()` (see [Tools](../concepts/tools.md#deferring-the-door))
  is always backed by a real store. See
  [Durable Computation](../concepts/durable-computation.md) for the rule
  and what mismatching them does.
- **`.memoryFactory(Function<String, Memory>)`** — overrides the default
  `id -> new SubstrateMemory(substrate, id, mapper)` recipe with a
  caller-supplied `Memory`. **Any override MUST return a view over shared
  state, never freshly-created state** — the same discipline
  `SubstrateMemory` gets for free by reading and writing through the
  shared substrate.
- **`.objectMapper(ObjectMapper)`** — the one mapper the harness binds
  JSON with; default a fresh `ObjectMapper`. Nessy pins a copy (lower-camel
  naming, tolerant reads, no default typing — see
  [Storage](../concepts/storage.md#the-one-mapper-story)) and threads that
  one pinned copy through every recipe that binds JSON. User-registered
  modules and serializers survive the copy.
- **`.staleness(StalenessPolicy)`** — the judgment call for when a quiet
  phase counts as dead enough to re-fire; default five minutes.
- **`.backlogCapacity(int)`** — the per-scope capacity of the shared
  backlog substrate document; default 1024.
- **`.executor`, `.turnObserver`, `.harnessObserver`** — the usual narration
  and threading seams, each defaulting to a sane no-op (or, for
  `harnessObserver`, the default narrating adapter) or an owned
  virtual-thread executor that lives as long as the harness does.
- **`.observationRegistry(ObservationRegistry)`** — the observability seam
  (agentic-o11y spec §0, §4): where this harness's `invoke_agent`/`chat`/
  `execute_tool` spans, its two `nessy.*` wait spans, and its three engine
  counters are recorded; default `ObservationRegistry.NOOP`, which makes the
  whole roster inert. See [Observability](observability.md#the-roster-otel-genai-spans-and-counters)
  for the roster and `nessy-examples/observed` for a real collector wired
  end to end.

## Two harnesses over one substrate share the Continuum too

Computation state is exactly as shared as you make it. A harness that was
handed no `.continuum(...)` mints a private one, so two such harnesses over
the same `.substrate(...)` never see each other's approvals or deferred
tool calls — and a "restart" modeled as a second harness over the same
substrate finds the scope's history but not its parked computations. Hand
both harnesses the same `Continuum` and they do: either one's pumps can
claim and deliver what the other parked (`SharedContinuumTest`), which is
the shape a second process over the same database gets for free with a
`continuum-jdbc`-backed Continuum on each side (`DurableResumeTest`).

The rule that follows: **harnesses that share `.type(...)` must share
both stores or neither.** Continuum's kinds are `approval/<agentType>` and
`tool/<agentType>`, drained with no substrate discriminator — so two
harnesses sharing type and Continuum but *different* substrates cross-drain:
one claims a delivery for a scope that exists only in the other's
substrate. Share type, substrate and Continuum together; or give the
harnesses distinct types; or give them both a distinct substrate *and* a
distinct Continuum. Sharing exactly one store is the one shape that is
never right. This is a contract the caller keeps, not something the
builder can check for you — but the failure when it's broken is now
**loud, not silent**: the cross-drained answer names a computation the
phase it lands on does not hold, so a delivered fact against any status
other than the one that's waiting for it is dropped with a `WARN` naming
the scope, the call, and the computation — never absorbed quietly into a
scope that reads `Idle`.

## `bind` and `tell`

```java
harness.bind(AgentId.of("prod-eu")).tell("please restart prod-eu");
```

`bind(AgentId)` stamps a fresh, transient `Agent<O>` over the harness's
shared substrate — cheap, thin, never closeable, holding no state of its
own. `tell(O)` enqueues a fact for that scope and returns immediately;
whatever comes back — text, a tool call, a park — is observed only through
`turnObserver`, never returned from `tell`. `drive()` is the other
half of `Agent<O>`: make this scope make progress — drain at idle, re-fire
a stale phase, else do nothing.

There is no per-id wiring cache. Every `bind(id)` call binds a fresh handle
from the shared substrate; two deliveries to the same scope, each through a
brand-new binding, still see each other's history because the substrate
underneath persists it, not any cache in front of it.

## `subscribe` and `ask`

`Agent#subscribe(TurnObserver)` returns a `Subscription` — the one
closeable in the API, because it is the only thing holding a routing entry
open. It routes into a fanout the harness carries per agent id, alongside
the harness's own configured `turnObserver`: both a `subscribe`d observer
and the global one see every event a bound id's turns narrate —
`TextDelta`, `ThinkingDelta`, `RedactedThinking`, the `ToolCall*` trio,
`AssistantSaid`, and `TurnEnded` — exactly once each, whether the turn
settles synchronously inside `tell`/`drive` or a worker-driven delivery
folds it days later. Close the `Subscription` to stop listening; dropping
it unclosed leaks one routing entry, never a thread.

`Agent#ask(O)` is a pattern over exactly that door, not new machinery:
subscribe a private capture, `tell`, block for the turn's own outcome,
close. It resolves a sealed `TurnOutcome`:

```java
sealed interface TurnOutcome {
  record Replied(String text) implements TurnOutcome {}
  record Parked(ComputationId approval, ApprovalRequest request) implements TurnOutcome {}
  record Failed(String reason) implements TurnOutcome {}
}
```

`Replied` and `Failed` read straight off `AssistantSaid`/`TurnEnded` — the
same two events `subscribe` always delivered. `Parked` resolves off-channel:
a parked call is never narrated as a `TurnEvent` at all (see "The approval
arc" below), so `ask` registers its own per-id waiter, keyed off the
scope's own `ApprovalDeferred` fold, before ever calling `tell` — a turn
that parks synchronously, inside the very call that registers it, still
resolves to `Parked` rather than hanging.

## The console

`Nessy.cli()` composes the same kept `Harness` this whole page describes
with a `Console` — the terminal front end, in `nessy-agent`'s host
package:

```java
try (Console console =
    Nessy.cli()
        .model(claude)
        .systemPrompt("You are the ops assistant.")
        .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, Approvers.defer()))
        .build()) {
  console.run();
}
```

`console.approver()` is the immediate-decision arm as a face: it renders
the flattened `ApprovalRequest` (agent coordinates, call name, arguments,
action), reads `y`/`n`(+reason), and answers through
`harness.approvals().approve(id, "console", "")`/`.deny(id, "console",
reason)` — the exact same desk "The approval arc" below describes, reached
by hand instead of by a queued callback. `console.run()` is the
read-`ask`-print loop: a `Replied` prints; a `Parked` hands the ticket to
`approver()` and waits for the same turn to settle before printing what it
settled on; a `Failed` prints the
reason honestly. `Nessy.cli()`'s builder mirrors `HarnessConfig`'s own surface for the
pieces a terminal session needs (`.model`, `.systemPrompt`, `.settings`,
`.grants`/`.tools`, `.objectMapper`) — its substrate is always a fresh
in-memory one, not a setting — plus `.in(InputStream)`/`.out(PrintStream)`
to swap the real terminal for scripted streams in a test or an embedding
app.

## Durability is a property of the substrate

The identical harness is a toy on the in-memory substrate and a durable,
resumable, any-host system on a JDBC one — only `.substrate(...)` differs.
A second harness, built later, knowing nothing about the first, still
inherits the first harness's turn the moment it's pointed at the same
substrate:

```java
var substrate = new InMemorySubstrate(); // or a durable Substrate

var harnessA = Nessy.harness(h -> h.model(claude).systemPrompt(prompt).substrate(substrate));
harnessA.bind(AgentId.of("shared-scope")).tell("message one");

var harnessB = Nessy.harness(h -> h.model(claude).systemPrompt(prompt).substrate(substrate));
harnessB.bind(AgentId.of("shared-scope")).tell("message two");
// harness B's model call carries harness A's turn, read back from the substrate
```

Nothing about the object graph ties these two harnesses together — only
the shared `Substrate` does.

## Writing an approver

A grant carries an `Approver` — one method, and a world behind it: a rule
ladder, a risk service, a Slack post, a policy engine, a person at a
terminal. None of that is visible to the harness, and all of it is free to
be asynchronous through `ApprovalContext.defer()`. An approver either
answers now or says it will get back to us; `defer()` does the plumbing —
it parks the question, folds `ApprovalDeferred` into the scope, waits for
that fold to commit, and only then hands back the id. By the time an
approver could tell anyone about a question, the phase already names it.

**Telling people is the approver's own business.** There is no
harness-level notifier: the thing that decided a human was needed is the
thing that knows which human. A Slack approver, complete:

```java
context -> {
  var deferred = context.defer();                       // parked; the phase says AwaitingApproval(id)
  slack.post("#ops", render(context.request(), deferred.id()));
  return deferred;
}
```

The built-ins are one-liners: `Approvers.allow()` and `Approvers.deny(reason)`
answer without ever building a request — no enricher runs for a call
nobody will read the file of — and `Approvers.defer()` parks every call for
someone else to answer, telling nobody. A bare `Tool` in `.tools(...)`
still means `allow()`.

There is no chain on the `Approver` interface itself; composition is code
inside an approver, and the toolkit ships the two shapes people reach for
— a ladder, where the first answer wins and the last word parks:

```java
Approvers.rules(
    IntentRules.requireDeclared(OpsIntent.class),
    RiskRules.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH))
```

and a gate, where every member must approve and the first denial wins:

```java
Approvers.allOf(a, b, c)
```

`nessy-testing` ships `ScriptedApprover` (answers or defers per a script,
like `ScriptedModel`) and `RecordingApprover` for asserting on what an
approver saw.

## The approval arc

`nessy-examples/approvals` and `nessy-examples/governed` are the flagship,
runnable with no key at all
(`./mvnw -q -pl nessy-examples/approvals -am compile
exec:java -Dexec.args=--scripted`): a model asks to restart production, the
grant's approver parks, and the desk resumes it.

```java
var requests = new LinkedBlockingQueue<ComputationId>();
Approver parking =
    context -> {
      ApprovalOutcome outcome = context.defer();
      ComputationId id = ((ApprovalOutcome.Deferred) outcome).id();
      System.out.println("approval requested: " + id.value() + " action=" + context.request().action());
      requests.add(id);
      return outcome;
    };

var harness =
    Nessy.harness(
        h ->
            h.type("ops")
                .model(claude)
                .systemPrompt("You are the ops assistant.")
                .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, parking))
                .substrate(substrate));

harness.bind(AgentId.of("prod-eu")).tell("please restart prod-eu");
// ... turn runs, the call's status goes Pending, then AwaitingApproval(id)
//     the instant the approver defers ...

ComputationId firstAsk = requests.take();
harness.approvals().approve(firstAsk, "demo", "");
// ... any node, any time later; the answer folds, the tool runs, and the
//     turn completes ...
```

The arc: **park** — the executor builds the `ApprovalRequest`, hands it to
the approver, and `defer()` creates the approval computation (kind
`approval/<agentType>`) whose continuation carries the tool call itself,
folds `ApprovalDeferred(call, id)` into the scope — the call's status is
now `AwaitingApproval(id)` — and only then returns; **tell** — whatever the
approver does after `defer()` hands it an id (a queue, a Slack post, a
ticket) is how a human learns there is a question; **desk** —
`harness.approvals().approve(id, principal, note)` or `.deny(id, principal,
reason)` completes the computation with an `Approval`, the ownership
transfer into one outbox delivery; **fold, then dispatch** — the delivery
worker folds `ApprovalAnswered(call, id, approval)` into the scope; an
`Approved` answer is what emits `RunTool`, dispatched afterward on the
harness's own executor — **never inside the delivery's lease**. A `Denied`
answer folds the call straight to `Finished`, and the model reads the
refusal in-band: the delivery worker never runs a tool, whether the answer
was yes or no.

`harness.approvals()` and `harness.completions()` are the two doors:
`approvals()` answers `approve(id, principal, note)`/`deny(id, principal,
reason)`, plus the by-coordinates pair `approve(agentId, callId, principal,
note)`/`deny(...)` for whoever has only the question, not a ticket;
`completions()` answers `complete(id, result)`/`fail(id, reason)` for a
durable tool's own eventual result. Both are the harness's own desks —
reachable for as long as the harness is kept, from any thread, any time.

Nothing here holds a thread open waiting. Whether a park survives a
restart depends on both stores behind the harness, not just
`.substrate(...)` — see
[Durable Computation](../concepts/durable-computation.md) for the rule.
With the defaults — `InMemorySubstrate` and a minted in-memory Continuum —
a park never survives a restart, full stop. With `JdbcSubstrate` and a
`continuum-jdbc`-backed `.continuum(...)` over the same database, it does:
a fresh harness in a fresh process claims the delivery and finishes the
turn the old one started. And a *second* harness over the same
`.substrate(...)` sees the first's pending approvals and tool computations
exactly when it shares the first's Continuum (see "Two harnesses over one
substrate share the Continuum too" above).

!!! note "Delivery is per-harness, not per-cluster"
    Within one harness, Continuum's own lease gives one winner per
    delivery — claimed, processed, then acknowledged or released back for
    another pump to pick up. Neither the approval nor the tool kind's
    consumer ever runs a tool inline: each only folds one fact and returns,
    so the approval kind's lease is short (30 seconds) — it pays for
    delivering a message, never for doing the work. A tool an approver
    granted still runs on the harness's own executor, outside any lease, so
    a slow tool cannot be reclaimed and run twice the way it once could.
    See [Durable Computation](../concepts/durable-computation.md) for the
    delivery pipeline this replaces.

A durable tool defers the same way: `context.defer()` is the tool kind's
own door, creating and folding its computation before it ever hands back
an id, exactly as `ApprovalContext.defer()` does above. See
[Deferring — the door](../concepts/tools.md#deferring-the-door).

## The governed turn: intent, risk, and threshold together

A single grant can compose more than a yes/no answer. `nessy-examples/governed`
wires a restart tool where a rule ladder reads two separate facts before it
answers:

```java
ToolGrant.grant(
    new RestartTool(),
    RESTART_ACTION,
    List.of(new IntentEnricher<>(intentStore, OpsIntent.class), riskAssessor()),
    Approvers.rules(
        IntentRules.requireDeclared(OpsIntent.class),
        RiskRules.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH)));
```

`IntentEnricher` reads back whatever the model declared through the
`declare-intent` tool (see [Intent](../concepts/intent.md)) and deposits it
under `IntentEnricher.declared(OpsIntent.class)`; a risk-assessing
`Enricher` deposits a `RiskAssessment` under `ApprovalRequest.RISK`. The
ladder judges in order, first answer wins:

- **`IntentRules.requireDeclared(OpsIntent.class)`** denies, in-band,
  before the risk rule is ever reached, when no intent was declared for
  this call — the model reads the refusal and learns to call
  `declare-intent` first.
- **`RiskRules.threshold(MODERATE, VERY_HIGH)`** then reads the deposited
  `RiskAssessment` and judges three ways: severity below `MODERATE`
  approves, no human involved; `MODERATE` up to (not including) `VERY_HIGH`
  defers — the same park-and-answer arc as above, and the approval request
  carries the declared intent and the risk assessment for a human to weigh;
  `VERY_HIGH` or above denies, in-band, **before any human is ever told** —
  telling is the approver's job, and a ladder that denies never reaches a
  step that would.

And `RiskRules.threshold` **fails closed**: if no risk assessor is wired at
all, there's nothing deposited under `ApprovalRequest.RISK` to judge, and
the call is denied with "no risk assessment deposited under `risk`" rather
than defaulting to allow. Composing a gate from enrichers is opt-in per
fact; leaving one out is a denial, not a silent pass.

## Typed intent, in your own vocabulary

The demos above use `Intent`, the freeform declaration. An organization can
ride the same `IntentTool` kit with its own sealed vocabulary instead —
`TypedIntentDemo`'s `OpsIntent`:

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Restart.class, name = "Restart"),
  @JsonSubTypes.Type(value = Diagnose.class, name = "Diagnose")
})
sealed interface OpsIntent permits Restart, Diagnose {}
record Restart(String target, String reason) implements OpsIntent {}
record Diagnose(String target) implements OpsIntent {}

ToolGrant.grant(new IntentTool<>(OpsIntent.class, intentStore), Approvers.allow());
```

Three things fall out of typing the intent:

- **`IntentRules.requireDeclared(OpsIntent.class)`** denies an
  undeclared restart in-band, teaching the model to call `declare-intent`
  first. Once declared, the retried restart proceeds through the risk
  threshold as before.
- An org's own **consistency policy** can compare the declared and attempted
  action — `TypedIntentDemo` denies, naming both, when a declared
  `Restart("prod-eu", ...)` is followed by an attempt against `prod-us`.
- A **declaration outside the sealed vocabulary** — `{"type": "Nuke", ...}`
  — is rejected by Jackson's own polymorphic binding, in-band, before
  `declare-intent` ever runs and before anything is stored. The rejection
  names the legal types (`Restart`, `Diagnose`) so the model can retry
  correctly.

See [Intent](../concepts/intent.md) for the annotated discriminator binding
this rides on.

## Typed observations

`Nessy.harness(HarnessCustomizer<String>)` observes `String` text.
`Nessy.harness(Class<O>, HarnessCustomizer<O>)` is the typed door:
observations are any `O` you name, and `Harness<O>` carries that type all
the way through `bind`/`tell`:

```java
record Note(String text, int priority) {}

var harness =
    Nessy.harness(
        Note.class,
        h ->
            h.model(claude)
                .systemPrompt(prompt)
                .substrate(substrate)
                .renderer(note -> List.of(new TextBlock(note.text()))));

harness.bind(AgentId.of("scope-1")).tell(new Note("check the oven", 3));
```

Two things the typed door asks of you that the `String` door presets for
free:

- **`.renderer(ObservationRenderer<O>)` is required.** The `String` door
  presets a renderer that wraps the text in a `TextBlock`; the typed door
  has no sensible default translation from an arbitrary `O` to inference
  content, so Nessy rejects a customizer that never called `.renderer(...)`,
  naming the missing seam.
- **The backlog codec has no override seam.** A posted `Note` is queued in
  the same `backlog` document every observation rides (see
  [Storage](../concepts/storage.md)), through the config's substrate's own
  `CodecFactory` — derived automatically over `observationType` from the
  same pinned `ObjectMapper` the substrate was built with. There is no
  `.backlogCodec(...)` setter today; a custom stored shape for observations
  is parked, not planned.

A typed observation queued while its scope is busy survives in the backlog
document exactly like a `String` one does — draining, staleness recovery,
and multi-process takeover all work the same, because the backlog recipe
never cared what `O` was.

## Tinkering: ApprovalPlayground

`ApprovalPlayground` (in `nessy-agent`'s test sources, never run by
surefire) is a runnable console loop against a real model: type an
observation, watch a restart request park, type `approve` or `deny
<reason>` to answer it.

```java
var selection = ModelDiscovery.select();
var pending = new LinkedBlockingQueue<ComputationId>();
Approver queueing =
    context -> {
      ApprovalOutcome outcome = context.defer();
      System.out.println("  [parked] " + context.request().action());
      pending.add(((ApprovalOutcome.Deferred) outcome).id());
      return outcome;
    };
var harness =
    Nessy.harness(
        h ->
            h.type("playground")
                .model(selection.model())
                .systemPrompt("You are a terse assistant.")
                .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, queueing))
                .turnObserver(event -> System.out.println("  [turn] " + event)));
// ... read lines; "approve" / "deny <reason>" answer pending.peek() by id;
//     everything else is harness.bind(AgentId.of("tinker")).tell(line) ...
```

Run it from the IDE with a provider key set — see
[Providers](providers.md) — to watch the park-tell-approve-dispatch arc
happen live, narrated turn by turn.

For this same arc as consumer code, runnable with no key at all, see
`nessy-examples/approvals` (`./mvnw -q -pl nessy-examples/approvals -am
compile exec:java -Dexec.args=--scripted`) and `nessy-examples/governed` for
the full declared-intent-plus-risk-threshold gate.

## Where next

- [Durable Computation](../concepts/durable-computation.md) — the
  ownership-transfer pipeline, the two desks, and why a parked call
  survives its own process dying.
- [Storage](../concepts/storage.md) — the `.substrate(...)` seam and the
  substrate every recipe on this page shares.
- [Intent](../concepts/intent.md) — the `declare-intent` tool and the
  annotated sealed-input binding `TypedIntentDemo` rides.
