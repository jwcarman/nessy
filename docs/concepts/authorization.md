# Authorization

Nessy grants a tool and answers its authority in the same breath. There is
no way to name a tool the model may call without also stating who decides
whether one particular call may proceed.

## The trust gradient

Four words carry the whole design, from least to most trusted:

- **Claim** — something the model asserts about itself, such as a declared
  intent. Untrusted by construction.
- **Action** — the trusted statement of what one call will actually do,
  rendered by the application from the bound input.
- **Assessment** — a fact an enricher gathers about the action or its
  context: a risk level, a resolved principal, a quota check.
- **Approval** — the answer to "may this call run?", either spoken on the
  spot by the grant's own approver or parked for someone else to answer
  later. Every call gets exactly one, whoever spoke it.

Each stage only ever adds information; nothing downstream can widen what an
upstream stage already narrowed.

## A grant is the security statement

`ToolGrant` names four things together: which `Tool` an agent may call, the
`ActionContributor` that states what one call will do, the ordered
`Enricher`s that gather facts onto the request, and the `Approver` the
executor consults before it runs.

`ToolGrant` is a **final class with a private constructor** — the `grant`
factories are the only supported way to write one. There is no bare grant,
no derived floor, no re-dressing an existing grant with a different
approver: a grant does not exist until its authority is answered.

```java
// rung 0/1 — any Tool, judged by an approver that reads at most the request
ToolGrant.grant(tool, approver);

// rung 2 — a typed ActionContributor renders the action, no enrichers
ToolGrant.grant(tool, contributor, approver);

// rung 2/3 — the same contributor, plus an ordered list of enrichers
ToolGrant.grant(tool, contributor, enrichers, approver);
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

## The request is the pipeline

`ApprovalRequest` is the question an approver answers: this call, on this
agent, with these facts.

```java
public record ApprovalRequest(
    String agentType, String agentId, ToolCall call, String action, Facts facts) {}
```

**A JSON document, by contract.** Every field renders through the harness's
pinned mapper, deterministically, and the rendered document is the record
of what was decided on: read by the approver, parked with the computation
when the approver defers, and shown to the desk later. Rendered **once** —
the `action` line and every fact are fixed at enrichment and never
re-derived at read time, so a later change to a contributor or an enricher
cannot rewrite what a human saw.

`Facts` is the typed fact bag. `deposit(key, value)` (on the mutable
`Draft`) encodes the value through the pinned mapper immediately; `get(key)`
decodes it back to `key.type()`. A value the mapper cannot render fails
*inside the enricher, at the line that deposited it* — not later, on a pump
thread, when a human's answer tries to park the request:

```java
public record Key<T>(Class<T> type, String name) {}
```

`Key` is **value-equal**, deliberately: facts are stored by name in a JSON
document, so two keys with the same name address the same fact wherever
they were constructed — an enricher in one module and a rule in another
agree on `new Key<>(Intent.class, "intent.declared")` by construction. An
application declares its own key exactly the way Nessy declares its
well-known ones (`ApprovalRequest.PRINCIPAL`, `ApprovalRequest.RISK`) —
`new Key<>(Foo.class, "foo")` — and deposits into it with `draft.deposit(key,
value)`.

Enrichment builds the request through a short-lived mutable draft, which
`ToolGrant#request` drives:

```java
ApprovalRequest.Draft draft = ApprovalRequest.draft(agentType, agentId, call, pinned);
draft.action(renderAction.apply(input));            // once
for (Enricher e : enrichers) e.enrich(draft);        // each deposits typed facts
ApprovalRequest request = draft.freeze();            // immutable from here on
```

Nothing outside enrichment ever sees a `Draft`, and a `Draft` freezes once.
A `RuntimeException` escaping the action render or any enricher is caught
and rethrown as an `IllegalStateException` naming the stage that broke —
`"action stage: ..."` or `"enricher stage «name»: ..."` — with the original
as its cause. The chokepoint fails closed on the stage name alone, never on
a bare throw.

## Enrichers — impure, and they gather

```java
@FunctionalInterface
public interface Enricher {
  void enrich(ApprovalRequest.Draft draft);
}
```

Enrichers **may do I/O** — a principal exchange, a risk service call, a
quota read — because the approver that follows them stays free to be pure,
and all of that impurity has to live somewhere. Each enricher deposits onto
the same draft in order; it must not freeze it.

A throwing enricher fails the whole call closed, naming its own stage.
`Enrichers.principal(Supplier<String> resolver)` is the shipped principal
kit — Nessy imposes no identity shape, since authorization here is never
authentication; `resolver` hands over an already-authenticated identity of
whatever type the deployment prefers.

## The approver — one method, a world behind it

```java
public interface Approver {
  ApprovalOutcome approve(ApprovalContext context);
}

public interface ApprovalContext {
  ApprovalRequest request();     // the question, enriched and frozen
  ApprovalOutcome defer();       // "I'll get back to you"
}

public sealed interface ApprovalOutcome {
  record Answered(Approval approval) implements ApprovalOutcome {}
  record Deferred(ComputationId id) implements ApprovalOutcome {}   // minted only by defer()
}

public sealed interface Approval {
  record Approved(Optional<String> reference) implements Approval {}
  record Denied(String reason, Optional<String> reference) implements Approval {}
}
```

`Approver` is a facade in the way `Memory` is: one method, and a world
behind it — a rule ladder, a risk service, a Slack post, an OPA call, a
quorum vote, a person at a terminal — none of it visible to the harness,
and all of it free to be asynchronous through `defer()`. `defer()` does the
plumbing: it parks the question, folds `ApprovalDeferred` into the scope,
waits for that fold to commit, and only then hands back the id. By the time
an approver could tell anyone about a question, the phase already names it.
Telling people is the approver's own business — there is no harness-level
notifier.

`Approvers.allow()`, `Approvers.deny(reason)`, and `Approvers.defer()` are
the three one-liners. `allow()` and `deny()` implement the sealed marker
`Approvers.Static`, which the executor recognises and answers **without
building the request** — no action rendered, no enricher run, for a call
nobody will read the file of. A bare `Tool` in `.tools(...)` still means
`allow()`.

There is no chain on the `Approver` interface. Composition is code inside
an approver, and the toolkit ships the two shapes people reach for:

```java
Approvers.rules(                                  // a ladder: first answer wins; a Defer parks
    IntentRules.requireDeclared(OpsIntent.class),
    RiskRules.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH))

Approvers.allOf(a, b, c)                          // gates: every one must approve; any denial denies
```

A `Rule` has three outcomes — `Answered`, `Undecided`, or `Defer` — and the
toolkit's own vocabulary, never `Approver`'s: "I am unable to decide" is a
rule's word. `RiskRules.threshold(approveAt, denyAt)` and
`IntentRules.requireDeclared(vocabulary)` are the two shipped
context-reading rules; see [Intent](intent.md) and the risk kit below.

`nessy-testing` ships `ScriptedApprover` (answers or defers per a script,
like `ScriptedModel`) and `RecordingApprover`, since approvers are the
thing people most want to unit-test their agents against.

## The chokepoint's flow

`RegistryToolCallExecutor` is the one place every call passes through, and
it is two doors, neither with a conditional inside: `seekApproval` asks,
`runTool` runs. `seekApproval`'s inner logic:

```java
if (grant.approver() instanceof Approvers.Static fixed) {
  return answered(call, fixed.answer());          // rung 0: nothing built
}
ApprovalRequest request = grant.request(type.name(), id.value(), call, input, mapper);
ApprovalContext context = approvalContexts.contextFor(call, responseId, request, sink);
ApprovalOutcome outcome = grant.approver().approve(context);
return switch (outcome) {
  case ApprovalOutcome.Answered(Approval approval) -> answered(call, approval);
  case ApprovalOutcome.Deferred _ -> null;         // defer() already delivered ApprovalDeferred
};
```

A denial and an approval both narrate `ToolCallDecided`; a denial also
narrates `ToolCallCompleted` — the model reads the reason as a tool result
right away, because a denied call is a finished call. An approval instead
folds `Pending → Running`, and `runTool` — a separate door, reached only
once the answer is already a fact in the phase — runs the tool. Neither
door ever holds a tool call inside a delivery lease; see [Durable
Computation](durable-computation.md) for what that buys.

## `AuthorizationReport` — the report is the wiring

`AuthorizationReport.of(grants)` reads each grant's own `tool()`,
`approver()`, `enrichers()`, and `contributor().displayName()` — by
declaration, never by reflection over an erased lambda, and never by
calling `actionOf`, `enrich`, or `approve`. It cannot drift from the wiring
it reads, because it never runs anything the wiring does.

```
restart_prod: action(restart-statement) → intent → risk → approver (RestartApprover)
read-balance: allow()
```

A grant whose approver is `Approvers.Static` reports honestly as its own
factory call (`allow()`, `deny("reason")`) with no action and no
enrichers, no matter what the grant's `enrichers()` list happens to
hold — that mirrors the chokepoint's own rung-0 skip exactly. Any other
approver reports its own `getClass().getSimpleName()` — the one identity
every approver already carries without a new field to declare.

## The risk kit

`RiskAssessment` is the shipped, opinionated shape a risk-assessing
enricher deposits under `ApprovalRequest.RISK`:

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
MCP tool annotations plus Nessy's own additions. An org's own factor is
just another `RiskFactor`; this is a starting vocabulary, not a sealed
grammar.

`RiskRules.threshold(approveAt, denyAt)` reads `request.facts().get(ApprovalRequest.RISK)`
and judges by severity: below `approveAt` answers `Approved`; from
`approveAt` up to (but below) `denyAt` returns `Defer`, so the ladder parks
for a human; `denyAt` or above answers `Denied`, naming the severity. An
absent assessment fails closed with a denial naming the empty slot —
wiring no risk-assessing enricher at all is not the same as wiring a
lenient one.

```java
ToolGrant.grant(
    new RestartTool(),
    RESTART_ACTION,
    List.of(new IntentEnricher<>(intentStore, OpsIntent.class), riskAssessor, Enrichers.principal(() -> "jcarman")),
    Approvers.rules(
        IntentRules.requireDeclared(OpsIntent.class),
        RiskRules.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH)));
```

See [the harness guide's governed-turn
section](../guides/harness.md#the-governed-turn-intent-risk-and-threshold-together)
for this grant worked end to end against `nessy-examples/governed`: a
severity of `HIGH` parks for a human and completes once approved; `VERY_HIGH`
is denied outright, in-band, before any approver is ever asked; no risk
assessment at all fails the same door closed with `"no risk assessment
deposited under 'risk'"`.

## Where next

- [Intent](intent.md) — the claim channel enrichers read, and the
  teaching loop `IntentRules.requireDeclared` drives.
- [Tools](tools.md) — what a granted `Tool` actually is, and the sealed
  vocabulary a grant's input can take.
- [Durable Computation](durable-computation.md) — what `defer()` parks,
  and how a granted call dispatches once it's answered.
