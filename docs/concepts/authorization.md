# Authorization

Nessy grants a tool and answers its authority in the same breath. There is
no way to name a tool the model may call without also stating who decides
whether one particular call may proceed.

## The trust gradient

Five words carry the whole design, from least to most trusted:

- **Claim** — something the model asserts about itself, such as a declared
  intent. Untrusted by construction.
- **Action** — the trusted statement of what one call will actually do,
  rendered by the application from the bound input.
- **Assessment** — a fact an enricher gathers about the action or its
  context: a risk level, a resolved principal, a quota check.
- **Judgment** — the pure act of deciding, from the assembled facts alone;
  its verdict is a `PolicyDecision`: allow, deny, or require approval.
- **Adjudication** — the stage a call enters only when judgment's verdict is
  require approval: a human or an external system decides — `Granted`,
  `Refused`, or its most interesting state, `Suspended`: not decided yet,
  parked in a durable computation.

Each stage only ever adds information; nothing downstream can widen what an
upstream stage already narrowed.

## A grant is the security statement

`ToolGrant` names four things together: which `Tool` an agent may call, the
`ActionContributor` that states what one call will do, the ordered
`Enricher`s that gather facts into the context, and the `UsagePolicy` the
executor consults before it runs.

`ToolGrant` is a **final class with a private constructor** — the `grant`
factories are the only supported way to write one. There is no bare grant,
no derived floor, no re-dressing an existing grant with a different policy:
a grant does not exist until its authority is answered.

```java
// rung 0/1 — any Tool, judged by a policy that reads at most the context
ToolGrant.grant(tool, UsagePolicy policy);

// rung 2 — a typed ActionContributor renders the action, no enrichers
ToolGrant.grant(tool, ActionContributor<? super I, ?> contributor, UsagePolicy policy);

// rung 2/3 — the same contributor, plus an ordered list of enrichers
ToolGrant.grant(tool, contributor, List<Enricher> enrichers, UsagePolicy policy);
```

The application states the action, even for a third-party tool — an MCP
tool, say — whose own `Tool` implementation never speaks for itself.
Authorization never appears in the tool API.

## `ActionContributor` — typed at the source

```java
@FunctionalInterface
public interface ActionContributor<I, A> {
  A actionOf(I input);
}
```

The contributor is welded to the tool's own input type at the grant site,
so the action is produced from a live `I`, not recovered later from an
erased `Object`:

```java
ActionContributor<RestartInput, String> RESTART_ACTION =
    ActionContributor.named("restart-statement", in -> "restart " + in.target());
```

`ActionContributor.named(...)` gives it a display name `AuthorizationReport`
reads; an undecorated lambda reports as `unnamed`. The grant's own default
contributor — used when a caller wires no contributor at all — is
`String.valueOf(input)`, and it always reports as `action(String.valueOf)`.

## The context is the pipeline

`AuthzContext` is one concrete, immutable, typed-key bag over the facts an
authorization decision may need. It is deliberately **not generic** over
the grant's own action type, so an `Enricher` or `UsagePolicy` written
against this interface composes into any grant, regardless of what action
type that grant welded — the whole pipeline is monomorphic.

```java
public interface AuthzContext {
  String agentName();
  ToolCall call();
  <T> Optional<T> get(Key<T> key);
  default <T, S extends T> Optional<S> get(Key<T> key, Class<S> type) { ... }
  <T> AuthzContext with(Key<T> key, T value);
}
```

`agentName()` and `call()` are known before any application code runs.
Everything else starts empty and is filled in by enrichers via `with`,
functionally — each call returns a new context, so an earlier enricher's
view is never mutated out from under it.

A missing key is `Optional.empty()`, never an exception. `get(key, type)`
narrows by class token: a non-instance and an absence both read as empty —
a reader fails closed on its own terms either way, with no way to tell
"nothing was deposited" from "something else was."

The action travels only as `AuthzContext.ACTION_KEY`, deposited by the
grant's own `assemble` step before any enricher runs. Three sugar methods
sit on top of the general typed read for the well-known slots:

```java
context.action(Class<A> type)          // sugar over get(ACTION_KEY, type)
context.principal(Class<P> type)       // sugar over get(PRINCIPAL_KEY, type)
context.declaredIntent(Class<T> type)  // sugar over get(DECLARED_INTENT_KEY, type)
```

An application or enricher library declares its own key exactly the same
way nessy declares its well-known ones — `new Key<>(Foo.class, "foo")` — and
deposits into it with `context.with(key, value)`. `Key` equality is
identity, deliberately: two modules that happen to pick the same name never
collide.

## `assemble` / `decide` — the two-method pipeline

```java
AuthzContext assemble(AuthzContext base, Object input);
PolicyDecision decide(AuthzContext assembled);
```

`assemble` binds the input, renders the action, deposits it under
`ACTION_KEY`, and runs the enrichers in order, returning the enriched
context. `decide` lets the policy judge that context. There is no result
record — the assembled context **is** the carrier, read back by the caller
with `context.action()` and its own typed keys.

A `RuntimeException` escaping the action render or any enricher is caught
and rethrown as an `IllegalStateException` naming the stage that broke —
`"action stage: ..."` or `"enricher stage «name»: ..."` — with the original
as its cause. The chokepoint fails closed on the stage name alone, never on
a bare throw.

## Enrichers — impure, and they gather

```java
@FunctionalInterface
public interface Enricher {
  AuthzContext enrich(AuthzContext context);
}
```

Enrichers **may do I/O** — a principal exchange, a risk service call, a
quota read — because the policy that follows them stays pure, and all of
that impurity has to live somewhere. Each enricher receives the previous
enricher's own context and returns the next one; nothing upstream ever sees
a later enricher's deposit.

A throwing enricher fails the whole call closed, naming its own stage.
`Enrichers.principal(Supplier<?> resolver)` is the shipped principal kit —
nessy imposes no identity shape, since authorization here is never
authentication; `resolver` hands over an already-authenticated identity of
whatever type the deployment prefers.

## Policies — pure judgment

```java
public interface UsagePolicy {
  PolicyDecision evaluate(AuthzContext context);
}
```

`evaluate` must be pure: no I/O, no mutation, nothing beyond a function of
the final assembled context. The executor may call it from any thread, and
treats an escaping `RuntimeException` as a `Deny` naming the policy stage —
a broken policy fails closed rather than becoming an allow.

Three canonical statics:

```java
UsagePolicy.allow()             // every call proceeds, approver never consulted
UsagePolicy.deny(String reason) // every call refused, same reason every time
UsagePolicy.requireApproval()   // every call defers to the approver
```

`allow()` and `deny(...)` implement the sealed marker `UsagePolicy.Static`
— a verdict that never depends on context or action — so the chokepoint
fast-paths them: no action rendered, no context assembled, no enrichers
run. `requireApproval()` deliberately does **not** implement `Static`: the
approver still needs the rendered action and the assembled context, so it
must pay the assembly cost even though its own verdict never varies.

`UsagePolicy.allOf(List<UsagePolicy>)` is the deny-biased conjunction:
evaluates in order, stops at the first `Deny`, and — if none deny but any
requires approval — the composite requires approval; only when every
policy allows does the composite allow.

`IntentPolicies.requireDeclared(vocabulary)` and
`RiskPolicies.threshold(approveAt, denyAt)` are the two shipped
context-reading policies; see [Intent](intent.md) and the risk kit below.

## The chokepoint's flow

`RegistryToolCallExecutor` is the one place every call passes through:

```java
if (grant.policy() instanceof UsagePolicy.Static fixed) {
  decision = fixed.decision();                    // rung 0: nothing assembled
} else {
  AuthzContext assembled = grant.assemble(AuthzContext.of(type.name(), call), input);
  decision = grant.decide(assembled);
}

switch (decision) {
  case Allow _            -> run(tool, input, call, address);
  case Deny(String reason)-> failed(call, reason);
  case RequireApproval _  -> switch (approver.adjudicate(
      new ApprovalRequest(address.approval(), call, address.agentType(), address.agentId(),
                           assembled))) {
      case Granted _              -> run(tool, input, call, address);
      case Refused(String reason) -> failed(call, reason);
      case Suspended(var computation) -> deferred(computation);
    };
}
```

A `Deny` and a `Refused` adjudication both deliver **in-band, narrated**:
the model reads the reason as a tool result and reacts. An `Allow` and a
`Granted` adjudication run the tool. The model has no say in any of it — it
only ever sees the outcome.

## `RequireApproval` → the `Approver` seam

`RequireApproval` routes to the wiring's `Approver`:

```java
@FunctionalInterface
public interface Approver {
  Adjudication adjudicate(ApprovalRequest request);
}

public sealed interface Adjudication {
  record Granted() implements Adjudication {}
  record Refused(String reason) implements Adjudication {}
  record Suspended(ComputationId computation) implements Adjudication {}
}
```

`ApprovalRequest` is `(id, call, agentType, agentId, context)` — the ticket,
the call, a plain-string agent coordinate for display, and the assembled
`AuthzContext`, never less. The rendered action is not a component of its
own; it lives in `context`, read back the same way anywhere else does:
`context.action()`, `context.principal()`, `context.risk()`,
`context.declaredIntent()`. It is a human decision surface, not a routing
packet — a computation-backed approver that needs the committed model
response reads it from the agent's own state at ask time instead.

The default approver (the executor's 5- and 6-arg constructors) refuses
loudly in-band — approval is a capability of the wiring, not a right of
every deployment. A durable wiring's approver instead suspends into a
computation and returns `Suspended`; the desk mechanics — approval
computations, `approve`/`deny`, a granted call dispatching straight through
the delivery pipeline with no re-asked policy — belong to
[Durable Computation](durable-computation.md), not here.

## `AuthorizationReport` — the report is the wiring

`AuthorizationReport.of(grants)` reads each grant's own `tool()`,
`policy()`, `enrichers()`, and `contributor().displayName()` — by
declaration, never by reflection over an erased lambda, and never by
calling `actionOf`, `enrich`, or `evaluate`. It cannot drift from the
wiring it reads, because it never runs anything the wiring does.

```
restart_prod: action(restart-statement) → intent → risk → policy (ThresholdPolicy)
read-balance: allow()
```

A grant whose policy is `UsagePolicy.Static` reports honestly as its own
factory call (`allow()`, `deny("reason")`) with no action and no
enrichers, no matter what the grant's `enrichers()` list happens to
hold — that mirrors the chokepoint's own rung-0 skip exactly.

## The risk kit

`RiskAssessment` is the shipped, opinionated shape a risk-assessing
enricher deposits under `AuthzContext.RISK_KEY`:

```java
public record RiskAssessment(
    Likelihood likelihood, Impact impact, RiskLevel risk, Set<RiskFactor> factors) {

  public static RiskAssessment of(Likelihood likelihood, Impact impact, RiskFactor... factors) {
    // derives risk from the NIST SP 800-30 combination matrix
  }
}
```

`Likelihood`, `Impact`, and `RiskLevel` are each their own five-value enum
— kept separate so a swapped likelihood/impact argument is a compile error,
not a silent severity bug. `RiskAssessment.of` derives `risk` from NIST SP
800-30's qualitative combination matrix; the canonical constructor is the
explicit-override door for an assessor whose own model concludes a level
the matrix wouldn't.

`RiskFactors` seeds an open vocabulary — `DESTRUCTIVE`, `IRREVERSIBLE`,
`EXTERNAL_WORLD`, `READ_ONLY`, `SPENDS_MONEY`, `TOUCHES_PII` — drawn from
MCP tool annotations plus nessy's own additions. An org's own factor is
just another `RiskFactor`; this is a starting vocabulary, not a sealed
grammar.

`RiskPolicies.threshold(approveAt, denyAt)` reads `context.risk()` and
judges by severity: below `approveAt` allows, from `approveAt` up to (but
below) `denyAt` requires approval, `denyAt` or above denies naming the
severity and the threshold. An absent assessment fails closed with a `Deny`
naming the empty slot — wiring no risk-assessing enricher at all is not the
same as wiring a lenient one.

```java
ToolGrant.grant(
    new RestartTool(),
    RESTART_ACTION,
    List.of(new IntentEnricher(intentStore), riskAssessor, Enrichers.principal(() -> "jcarman")),
    RiskPolicies.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH));
```

`GovernedTurnDemo` is the worked example: severity `HIGH` parks for a
human and completes once approved; severity `VERY_HIGH` is denied outright,
in-band, before any approver is ever asked; no risk assessment at all fails
the same door closed with `"no risk assessment deposited under RISK_KEY"`.

## Where next

- [Intent](intent.md) — the claim channel enrichers read, and the
  teaching loop `IntentPolicies.requireDeclared` drives.
- [Tools](tools.md) — what a granted `Tool` actually is, and the sealed
  vocabulary a grant's input can take.
- [Durable Computation](durable-computation.md) — the computation an
  approval suspends into and how a granted call dispatches once it's
  answered.
