# Autonomous Agents

`Nessy.cli()` blocks a thread waiting for a reply. That's wrong for a host
that keeps running without a human driving each turn — a Slack bot, a queue
consumer, an ops agent that might need to wait hours for a person to approve
something. `Nessy.autonomous()` is the second front door: post an
observation, get nothing back on that thread, and let the durable slot
primitive carry the wait.

## Building a host

```java
try (AutonomousHost host =
    Nessy.autonomous()
        .type("ops")
        .provider(provider)
        .settings(settings)
        .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
        .store(kernel)
        .approvalNotifier(requests::add)
        .build()) {
  host.post("prod-eu", "please restart prod-eu");
}
```

The builder surface, piece by piece:

- **`type(String)`** — the recipe's name, the first coordinate of every
  durable address (`AgentType`). Default `"autonomous"`.
- **`grants(ToolGrant...)`** — the tool grants every scope carries, authority
  and all; `.tools(Tool<?>...)` is sugar for granting each an answered-allow
  policy, same as the CLI door.
- **`store(ScopedStore)`** — the one storage seam (see
  [Storage](../concepts/storage.md)): every scope's state, memory, and
  backlog live as documents in this kernel; default a fresh
  `InMemoryScopedStore`, durable only for the process's lifetime. Supply a
  durable `ScopedStore` — a JDBC or DynamoDB adapter — to persist every
  scope beyond the process. There is no per-id cache behind a host:
  `agentFor(id)` binds a fresh `DefaultAgent` on *every* delivery, and the
  kernel document each recipe reads is what makes a scope's history survive
  from one delivery to the next.
- **`memoryFactory(Function<String, Memory>)`** — overrides the default
  `id -> new StoredMemory(store, id)` recipe with a caller-supplied
  `Memory`. **Any override MUST return a view over shared state, never
  freshly-created state** — the same discipline `StoredMemory` gets for
  free by reading and writing through the shared store.
- **`backend(DurableComputationBackend)`** — the shared durable computation
  backend behind both desks; default `StoredComputations` over this
  builder's `store(...)`. Override only for a genuinely foreign engine
  (Restate, Temporal) — nobody implements this seam to get a database.
- **`approvalNotifier(Consumer<ApprovalRequest>)`** — fires once,
  point-to-point, the moment an approval slot is first asked. One recipient,
  never narrated — see [Durable Computation](../concepts/durable-computation.md).
- **`staleness(StalenessPolicy)`** — the judgment call for when a quiet phase
  counts as dead enough to re-fire; default five minutes.
- **`backlogCapacity(int)`** — the per-scope capacity of the shared backlog
  substrate; default 1024.
- **`executor`, `turnObserver`, `agentObserver`** — the usual narration and
  threading seams, each defaulting to a sane no-op or an owned
  virtual-thread executor the host closes for you.

## Posting and the two desks

`AutonomousHost` exposes three things:

```java
public void post(String agentId, String text);   // enqueue one observation
public ApprovalDesk approvals();                  // approve(id) / deny(id, reason)
public CompletionDesk completions();               // complete(id, result) / fail(id, reason)
```

`post` enqueues a fact for that scope and returns immediately; the scope
drains it on its own. Whatever comes back — text, a tool call, a park — is
observed only through `turnObserver`, never returned from `post`.

## The approval arc

`AutonomousApprovalDemo` is the flagship: a model asks to restart production,
the grant requires approval, the call suspends, and the desk resumes it.

```java
try (var host =
    Nessy.autonomous()
        .type("ops")
        .provider(provider)
        .settings(settings)
        .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
        .memoryFactory(id -> memories.computeIfAbsent(id, ignored -> new VerbatimMemory()))
        .store(kernel)
        .backend(backend)
        .approvalNotifier(requests::add)
        .build()) {

  host.post("prod-eu", "please restart prod-eu");
  // ... turn runs, the tool call parks on approval:ops:prod-eu:c1 ...

  ApprovalRequest request = requests.getFirst();
  // request.address().approval() is the slot id
  // request.context().action() is "restart prod-eu", from the ActionContributor

  host.approvals().approve(request.address().approval());
  // ... any node, any time later; the scope resumes and the turn completes ...
}
```

The arc: **park** — the gate sees `RequireApproval`, creates the
`approval:` slot, registers a `REDRIVE_SCOPE` continuation, and suspends;
**notifier** — `approvalNotifier` fires once with the `ApprovalRequest`,
carrying the slot id and the assembled `AuthzContext` (action, declared
intent, risk, principal — whatever the grant's enrichers deposited);
**desk** — `host.approvals().approve(...)` or `.deny(..., reason)` completes
the slot with a `Decision`; **redrive** — completion fires `REDRIVE_SCOPE`,
which re-dispatches the scope's outstanding tool effects, the gate re-reads
the now-decided slot, and either runs the tool or delivers the denial
in-band. A denial is not an error path outside the model's view — the model
reads "not during business hours" as an ordinary failed tool result and
reacts to it, same as `AutonomousApprovalDemo`'s
`aDenialArrivesInBandAndTheModelReacts` shows.

Nothing here holds a thread open waiting. Whether a park survives a restart
of the process that opened it depends entirely on the `ScopedStore` behind
`.store(...)` — `InMemoryScopedStore` does not, a durable implementation
does.

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
  the same park-and-redrive arc as above; the approval request carries the
  declared intent, the risk assessment, and the principal for a human to
  weigh.
- severity `VERY_HIGH` → `Deny`, in-band, **before any approver is ever
  asked** — `requests` stays empty, and the model reads the refusal
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
  — is rejected by the discriminator binder itself, in-band, before
  `declare-intent` ever runs and before anything is stored. The rejection
  names the legal types (`Restart`, `Diagnose`) so the model can retry
  correctly.

See [Intent](../concepts/intent.md) for the discriminator binding mechanics
this rides on.

## Tinkering: ApprovalPlayground

`ApprovalPlayground` (in `nessy-agent`'s test sources, never run by
surefire) is a runnable console loop against a real provider: type an
observation, watch a restart request park, type `approve` or `deny
<reason>` to answer it.

```java
try (var host =
    Nessy.autonomous()
        .type("playground")
        .provider(selection.provider())
        .settings(settings)
        .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
        .approvalNotifier(pending::add)
        .turnObserver(event -> System.out.println("  [turn] " + event))
        .build()) {
  // ... read lines; "approve" / "deny <reason>" answer pending.peek() ...
}
```

Run it from the IDE with a provider key set — see
[Providers](providers.md) — to watch the park-notify-approve-redrive arc
happen live, narrated turn by turn.

For this same arc as consumer code, runnable with no key at all, see
`nessy-examples/approvals` (`./mvnw -q -pl nessy-examples/approvals -am
compile exec:java -Dexec.args=--scripted`) and `nessy-examples/governed` for
the full declared-intent-plus-risk-threshold gate.

## Where next

- [Durable Computation](../concepts/durable-computation.md) — the slot
  primitive, the two desks, and why a parked call survives its own instance
  dying.
- [Storage](../concepts/storage.md) — the `.store(...)` seam and the
  kernel every recipe on this page shares.
- [Intent](../concepts/intent.md) — the `declare-intent` tool and the
  sealed-input discriminator binding `TypedIntentDemo` rides.
