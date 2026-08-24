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
                .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
                .substrate(substrate)
                .approvalNotifier(requests::add));

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
worker, the approval and completion desks, the reaper sweep — runs on
daemon threads and lives exactly as long as the process does.

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
  authority and all; `.tools(Tool<?>...)` is sugar for granting each an
  answered-allow policy.
- **`.substrate(Substrate)`** — the one storage seam (see
  [Storage](../concepts/storage.md)): every scope's state, memory, and
  backlog live as documents in this substrate; default a fresh
  `InMemorySubstrate`, durable only for the process's lifetime. Supply a
  durable `Substrate` — a JDBC or DynamoDB adapter — to persist every
  scope beyond the process. There is no per-id cache: `bind(id)` stamps a
  fresh handle on every call, and the substrate document each recipe reads
  is what makes a scope's history survive from one binding to the next.
- **`.memoryFactory(Function<String, Memory>)`** — overrides the default
  `id -> new SubstrateMemory(substrate, id, mapper)` recipe with a
  caller-supplied `Memory`. **Any override MUST return a view over shared
  state, never freshly-created state** — the same discipline
  `SubstrateMemory` gets for free by reading and writing through the
  shared substrate.
- **`.backend(SubstrateComputations)`** — the shared computation store
  behind both desks; default a fresh `SubstrateComputations` over this
  config's `.substrate(...)`. There is no adapter seam above it — override
  only to share one instance across configs, or to pair it with a
  different `Substrate`/`ObjectMapper`.
- **`.objectMapper(ObjectMapper)`** — the one mapper the harness binds
  JSON with; default a fresh `ObjectMapper`. Nessy pins a copy (lower-camel
  naming, tolerant reads, no default typing — see
  [Storage](../concepts/storage.md#the-one-mapper-story)) and threads that
  one pinned copy through every recipe that binds JSON. User-registered
  modules and serializers survive the copy.
- **`.approvalNotifier(Consumer<ApprovalRequest>)`** — fires once,
  point-to-point, the moment an approval computation is first asked. One
  recipient, never narrated — see
  [Durable Computation](../concepts/durable-computation.md).
- **`.staleness(StalenessPolicy)`** — the judgment call for when a quiet
  phase counts as dead enough to re-fire; default five minutes.
- **`.backlogCapacity(int)`** — the per-scope capacity of the shared
  backlog substrate document; default 1024.
- **`.executor`, `.turnObserver`, `.agentObserver`** — the usual narration
  and threading seams, each defaulting to a sane no-op or an owned
  virtual-thread executor that lives as long as the harness does.

## One harness per agent type per substrate

Two harnesses that share both the same `.type(...)` and the same
`.substrate(...)` would double-drain each other's deliveries: each
harness's worker and reaper sweep every record carrying that type,
regardless of which harness instance produced it. Give two harnesses over
one substrate distinct types, or give them distinct substrates. This is a
contract the caller keeps, not something the builder can check for you.

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
  record Parked(ApprovalRequest ask) implements TurnOutcome {}
  record Failed(String reason) implements TurnOutcome {}
}
```

`Replied` and `Failed` read straight off `AssistantSaid`/`TurnEnded` — the
same two events `subscribe` always delivered. `Parked` resolves
off-channel, through the same approval notifier `.approvalNotifier(...)`
already fires into, since a parked call is never narrated as a `TurnEvent`
at all (see "The approval arc" below) — `ask` registers its own wait for
the next `ApprovalRequest` on that id before ever calling `tell`, so a
turn that parks synchronously, inside the very call that registers it,
still resolves to `Parked` rather than hanging.

## The console

`Nessy.cli()` composes the same kept `Harness` this whole page describes
with a `Console` — the terminal front end, in `nessy-agent`'s host
package:

```java
try (Console console =
    Nessy.cli()
        .model(claude)
        .systemPrompt("You are the ops assistant.")
        .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
        .build()) {
  console.run();
}
```

`console.approver()` is the §5a immediate-decision arm as a face: it
renders the flattened `ApprovalRequest` (`id`, `call`, `agentType`,
`agentId`), reads `y`/`n`(+reason), and answers through
`harness.approvals().approve(id)`/`.deny(id, reason)` — the exact same desk
"The approval arc" below describes, reached by hand instead of read back
off a notifier. `console.run()` is the read-`ask`-print loop: a `Replied`
prints; a `Parked` hands the ticket to `approver()` and waits for the same
turn to settle before printing what it settled on; a `Failed` prints the
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

## The approval arc

`GovernedTurnDemo` and `ApprovalPlayground` (in `nessy-agent`'s test
sources) are the flagship: a model asks to restart production, the grant
requires approval, the call suspends, and the desk resumes it.

```java
var requests = new CopyOnWriteArrayList<ApprovalRequest>();

var harness =
    Nessy.harness(
        h ->
            h.type("ops")
                .model(claude)
                .systemPrompt("You are the ops assistant.")
                .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
                .substrate(substrate)
                .approvalNotifier(requests::add));

harness.bind(AgentId.of("prod-eu")).tell("please restart prod-eu");
// ... turn runs, the tool call parks in the approval/ops kind ...

ApprovalRequest request = requests.getFirst();
// request.id() is the ticket — the approval's own opaque ComputationId
// request.context().action() is "restart prod-eu", from the ActionContributor

harness.approvals().approve(request.id());
// ... any node, any time later; the call dispatches and the turn completes ...
```

The arc: **park** — the gate sees `RequireApproval`, creates the approval
computation (kind `approval/<agentType>`) whose continuation carries the
tool call itself (routing, invocation id, call name and arguments), and
suspends; **notifier** — `approvalNotifier` fires once with the
`ApprovalRequest`, carrying the ticket (`id`), the plain-string
`agentType`/`agentId` for display, and the assembled `AuthzContext` (action,
declared intent, risk, principal — whatever the grant's enrichers
deposited); **desk** — `harness.approvals().approve(...)` or `.deny(...,
reason)` completes the computation with a `Decision`, which is itself the
ownership transfer into one outbox delivery; **dispatch** — the delivery
worker drains that delivery and, because its destination continuation
already carries the call, dispatches it directly — no re-read of the fold,
no re-derivation of the pending computation, and no second run through the
policy or the approver. A denial completes the same computation with
`Decision.Deny`, and the delivery worker folds it as an ordinary failed
tool result: the model reads the refusal in-band and reacts to it.

`harness.approvals()` and `harness.completions()` are the two doors:
`approvals()` answers `approve(id)`/`deny(id, reason)`; `completions()`
answers `complete(id, result)`/`fail(id, reason)` for a durable tool's own
eventual result. Both are the harness's own desks — reachable for as long
as the harness is kept, from any thread, any time.

Nothing here holds a thread open waiting. Whether a park survives a restart
of the process that opened it depends entirely on the `Substrate` behind
`.substrate(...)` — `InMemorySubstrate` does not, a durable implementation
does.

!!! note "Delivery is per-harness, not per-cluster, until the outbox gets a lease"
    Within one harness, the delivery worker's own claim gives one winner per
    delivery. Across processes sharing the same substrate and agent type,
    the same delivery can be drained more than once until an outbox lease
    lands with the first durable substrate adapter — parked, not built. The
    durable record stays single-winner regardless (that's the completion's
    own atomic transfer); only a tool's external side effect can run more
    than once in the meantime, which is why `RetrySemantics` exists at all —
    see [Durable Computation](../concepts/durable-computation.md#honest-limits).

## The governed turn: intent, risk, and threshold together

A single grant can compose more than a yes/no policy. `GovernedTurnDemo`
wires a restart tool where the gate reads three separate facts before it
judges:

```java
ToolGrant.grant(
    new RestartTool(),
    RESTART_STATEMENT,
    List.of(new IntentEnricher(intentStore), riskAssessor, Enrichers.principal(() -> "jcarman")),
    RiskPolicies.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH));
```

`IntentEnricher` reads back whatever the model declared through the
`declare-intent` tool (see [Intent](../concepts/intent.md)); a risk assessor
enricher deposits a `RiskAssessment` under `AuthzContext.RISK_KEY`;
`Enrichers.principal` states who's asking. `RiskPolicies.threshold` reads
the assembled context and judges three ways:

- severity below `MODERATE` → `Allow`, no approval needed.
- severity `MODERATE` up to (not including) `VERY_HIGH` → `RequireApproval`,
  the same park-and-dispatch arc as above; the approval request carries the
  declared intent, the risk assessment, and the principal for a human to
  weigh.
- severity `VERY_HIGH` → `Deny`, in-band, **before any approver is ever
  asked** — the notifier fires zero times, and the model reads the refusal
  directly.

And a threshold policy **fails closed**: if no risk assessor is wired at
all, there's nothing under `RISK_KEY` to judge, and the call is denied with
"no risk assessment deposited under `RISK_KEY`" rather than defaulting to
allow. Composing a gate from enrichers is opt-in per fact; leaving one out
is a denial, not a silent pass.

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

ToolGrant.grant(new IntentTool<>(OpsIntent.class, intentStore), UsagePolicy.allow());
```

Three things fall out of typing the intent:

- **`IntentPolicies.requireDeclared(OpsIntent.class)`** denies an
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
var selection = EnvModelProviders.select();
var harness =
    Nessy.harness(
        h ->
            h.type("playground")
                .model(selection.model())
                .systemPrompt("You are a terse assistant.")
                .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
                .approvalNotifier(pending::add)
                .turnObserver(event -> System.out.println("  [turn] " + event)));
// ... read lines; "approve" / "deny <reason>" answer pending.peek();
//     everything else is harness.bind(AgentId.of("tinker")).tell(line) ...
```

Run it from the IDE with a provider key set — see
[Providers](providers.md) — to watch the park-notify-approve-dispatch arc
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
