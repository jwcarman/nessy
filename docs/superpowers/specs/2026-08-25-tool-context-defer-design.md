# The context creates the computation — `ToolContext.defer()`

*2026-08-25. Amends `2026-08-25-approval-lifecycle-design.md` (§4 "Tools:
inside runTool", §15's deferred `ToolContext` door) and the durable
deliveries spec's tool-deferral mechanics. Status: awaiting James's review.*

## 0. Thesis

James's ruling, 2026-08-25: **the executor never creates a computation. The
context does — for approvals and for tools alike — because that is what
gives the approver and the tool the computation id.**

Today the two kinds diverge. An approver calls `context.defer()`, which
creates the computation, folds `ApprovalDeferred`, commits, and hands back
the id — so by the time the approver can tell anyone, the scope names the
ask. A tool cannot do any of that: it returns `Awaited.deferred()`, a bare
marker, and *after it has returned* the executor creates the computation
through `DeferredToolCallPolicy.onDeferred` and folds `ToolDeferred`. The
tool body never holds the Continuum id. The only way an external system can
learn it is by reading the agent's phase — which every test does and no real
tool can. A tool that opens a ticket, enqueues a job, or calls a webhook has
nothing to give that system as a callback address.

This spec makes the two doors the same door. It also closes a hole the
approval door already has (§3): `defer()` can today return an id for a park
that was never recorded.

## 1. Vocabulary

### 1.1 `ToolContext` becomes an interface

`ToolContext` is a record today — `(call, events, invocation)`, a value. A
`defer()` that creates, folds and commits needs a collaborator behind it,
which is a handle, not a value. It becomes an interface with the same
accessors and one door, the exact shape of `ApprovalContext`:

```java
package org.jwcarman.nessy.api.tool;

/**
 * What a tool learns about the invocation it is serving, plus what it can do
 * with it — the mirror of ApprovalContext. defer() does the plumbing: it
 * creates the durable computation, records the wait in the scope, waits for
 * that record to commit, and only then hands back the id. By the time a tool
 * can give the id to anyone, the phase already names the wait.
 */
public interface ToolContext {

  /** The call being served. */
  ToolCall call();

  /**
   * This execution's opaque, stable idempotency key — deterministic from the
   * call's coordinates, identical across every redispatch and replay. NOT the
   * computation id: a tool deduplicates an external side effect under it,
   * and hands out the id defer() returns as the callback address.
   */
  ComputationId invocation();

  /** Reports progress from inside a long-running tool. */
  void progress(String message);

  /**
   * "The answer arrives later": creates this call's durable computation with
   * the tool's declared timeout as its deadline, folds ToolDeferred, commits,
   * and returns the computation's id. Idempotent: a second call returns the
   * same id, creating nothing new. Throws if the wait could not be recorded
   * (§3) — in which case nothing was parked and the tool should propagate.
   */
  ComputationId defer();
}
```

`events` leaves the public surface — it was only ever the collaborator behind
`progress`, and a handle owns its collaborators.

The two ids are different things and the javadoc says so plainly, because
they were confused once this week: `invocation()` is the address digest,
`defer()` returns the Continuum-minted id.

### 1.2 `Awaited` — one legal way to defer

`Awaited` keeps its two arms. What changes is the contract on `Deferred`:

- `Awaited.deferred()` is legal **only after `context.defer()`** in the same
  execution. Returning it without having deferred is an in-band failure,
  `"deferring tool never called context.defer()"` — there is nowhere for the
  answer to go, so the executor refuses to pretend otherwise.
- `Awaited.ready(x)` **after** `defer()` is an in-band failure,
  `"tool answered after deferring"` — the door already committed the wait.

`Awaited.Deferred` stays a marker rather than carrying the id: the id already
went through the door, and two copies of one fact disagree eventually. The
`Awaited` javadoc's "the wiring derives the computation's deterministic id"
sentence is deleted; it described the retired mechanism.

### 1.3 `ApprovalRequest.Draft` carries the typed input

James's ruling: the input object rides the draft. `ToolGrant.request(...)`
already binds the call's arguments to `T` to render the action and then
throws the object away before the enrichers run. Now:

```java
Draft draft = ApprovalRequest.draft(agentType, agentId, call, input, pinned);
...
// inside an enricher
RestartInput in = draft.input(RestartInput.class);
draft.deposit(RISK, assess(in.target()));
```

- `input` is **transient**: it is not serialized into the document.
  `call().arguments()` is already the input in the JSON form the model sent,
  and that is the evidence; a second copy is a way to have two truths.
- `<T> T input(Class<T>)` casts the already-bound object against the class
  the caller names; a mismatch throws `ClassCastException` inside the
  enricher that asked, which the grant already turns into a denial. No
  re-parse.
- **No type parameter** on `Draft` or `ApprovalRequest` (ruled with James):
  the request is a document, and the draft is shared by a heterogeneous
  enricher list — `Draft<T>` would force `Enricher<? super T>` onto every
  grant signature for the benefit of the minority of enrichers that read
  the input at all.
- `Enricher.enrich(Draft)` stays one-arg. This closes approval-lifecycle
  §13.10.

### 1.4 No factory — the executor holds the clients

James, 2026-08-25: "why wouldn't we always use Continuum?" We always do.
`HarnessConfig` defaults to an in-memory Continuum and lets a wiring
override it; the only "no-Continuum" path in the tree is the executor's
five-arg test constructor, whose `defer` answer is a canned
`"parking unavailable"` failure — a shape kept alive from when Continuum was
heavier to stand up than it is now.

So there is no seam. `ApprovalContexts` and `DeferredToolCallPolicy` both
retire and **nothing replaces them**. `RegistryToolCallExecutor` takes the
two `ContinuumClient`s — non-null — and builds `ComputationApprovalContext`
and a new `ComputationToolContext` itself, per call. The two twins share one
package-private helper for create → fold → commit → id. `HarnessConfig`
passes the two clients it already holds; the executor's five-arg
constructor, `defaultPolicy`, `defaultApprovalContexts` and
`PARKING_UNAVAILABLE` go. Tests that need an executor build an in-memory
Continuum, as `SharedContinuumTest` and the desk tests already do.

This crosses a line earlier specs drew — "the executor stays ignorant of
computations." The line existed to keep the no-Continuum test shape alive;
with that shape gone it protects nothing, and `defer()` is left with exactly
one failure mode, the real one (§3).

## 2. Ordering — the fold is inside the door

```
tool body (executor thread)
  └─ context.defer()
       ├─ client.create(routing, timeout)        Continuum mints the id
       ├─ fold ToolDeferred(call, id)            load → Running → AwaitingResult(id) → CAS save
       └─ return id                              only now can anyone else hold it
  hand id to the external system
  return Awaited.deferred()                      executor delivers nothing: already folded
```

When `defer()` returns, `AwaitingResult(id)` is on disk. A completion that
arrives a millisecond later meets the status that awaits it. The approval
lifecycle's drop rule ("a delivery whose scope is not in the awaiting status
is a permanent failure — drop with a WARN") never sees a legitimate early
result — not because a race is being won, but because there is no window.
Approval-lifecycle §4's "the window does not exist in practice" becomes "the
window does not exist," by construction, for both kinds.

The tool thread blocks for one substrate write. Milliseconds, and the same
cost the approval door already pays.

After `defer()` the call is `AwaitingResult` while the tool is still
running. The reducer does not care: whatever the tool returns next is
policed by the executor (§1.2), never by a matrix cell. The §3 matrix of the
approval-lifecycle spec is unchanged by this document.

## 3. `defer()` must know the fold committed

`Sink.deliver` is `DefaultAgent::deliver`: load → handle → remember → CAS
save → observers → effects, retrying on `StaleStateException`. On any other
`RuntimeException` — substrate down, a throwing `Memory.remember` — it
narrates `AgentObserver.applyFailed` and **returns normally**. That is right
for the executor's ordinary completions: they have nowhere to throw to.

It is wrong for a door that promises "an id you hold is an id the scope
names." Today `ComputationApprovalContext.defer()` can return `Deferred(id)`
for a park that was never recorded: Continuum holds the computation, the
scope still says `Pending`, and the human's eventual answer is a dropped
mismatch. Nothing hangs — the staleness re-fire re-asks with a fresh
computation — but the approver was told "parked" when it was not, and the
first answer is lost. This is the "returned Deferred but nothing was parked"
case, reachable by the back door.

The fix is a contract change on the one door that exists, not a second
door: **`Sink.deliver` narrates `AgentObserver.applyFailed` and then
rethrows.** `DefaultAgent.deliver`'s `catch (RuntimeException e)` keeps its
narration and loses its `return`. Every other caller of a sink runs inside
an executor task — the model executor's completion, the tool executor's
completions, the delivery worker's redrives — where the narration was
already the only trace of a failure and the task ends either way; nothing
observable changes for them. `ComputationApprovalContext.defer()` and
`ComputationToolContext.defer()` simply let the throw propagate. `Sink`
stays a one-method functional interface; nothing is added.

An ignored event (the phase would not admit it) is not a failure: `deliver`
returns normally, as today. A door that needs to know its event was
*applied* rather than ignored — `defer()` against a call the phase no
longer holds — reads the transition through the observer, or, simpler and
proposed: the door does not check, because the only way its event is
ignored is a re-ask that has already replaced the call, in which case the
orphan computation expires into a dropped mismatch exactly as §6 says.

Consequence for a failed `defer()`: the computation was created but the
scope never named it. Its eventual expiry is a dropped mismatch — the drop
rule's WARN is the whole trace, as for any orphan. The tool (or approver)
sees the exception, propagates, and the executor answers the call in-band
with the failure. Nothing dangles.

Approval-lifecycle §1.3's sentence "a hand-built `Deferred` parks nothing"
stands; this section closes the other route to the same state.

## 4. The executor

`RegistryToolCallExecutor.run` today:

```java
ToolContext context = new ToolContext(call, event -> narrate(call, event), ComputationId.of(address.digest()));
return switch (tool.execute(typed, context)) {
  case Awaited.Ready<ToolResult>(var value) -> ...Immediate(Returned(value));
  case Awaited.Deferred<ToolResult> _      -> deferredToolCallPolicy.onDeferred(call, address, tool.timeout());
};
```

becomes:

```java
ComputationToolContext context =
    new ComputationToolContext(toolClient, routing(call, responseId), tool.timeout(),
                               event -> narrate(call, event), sink);
Awaited<ToolResult> outcome = tool.execute(typed, context);
boolean deferred = context.deferred();   // package-visible on the twin — see §8 open question 2
return switch (outcome) {
  case Awaited.Ready<ToolResult>(var value) when !deferred -> Immediate(Returned(value));
  case Awaited.Ready<ToolResult> _                        -> Immediate(failed("tool answered after deferring"));
  case Awaited.Deferred<ToolResult> _ when deferred        -> AlreadyRecorded;   // deliver nothing
  case Awaited.Deferred<ToolResult> _                      -> Immediate(failed("deferring tool never called context.defer()"));
};
```

`ToolExecution.Deferred(id)` — "the executor should deliver `ToolDeferred`
with this id" — has no producer left and retires; `ToolExecution` becomes
`Immediate | AlreadyRecorded`. `runTool` delivers only on `Immediate`.

`seekApproval` is unchanged in shape; it constructs its
`ComputationApprovalContext` directly instead of through the
`approvalContexts` lambda.

## 5. Wiring

`HarnessConfig` passes `effectiveApprovalClient` and `effectiveToolClient`
to the executor where it built a `ComputationDeferredToolCallPolicy` and an
`ApprovalContexts` lambda. `Harness.of(...)`'s arity does not change (the
executor factory is what it receives). There is no default wiring: both
clients are required.

## 6. Recovery — unchanged

`outstandingEffects` re-fires `Pending → SeekApproval` and `Running →
RunTool`; `AwaitingApproval` and `AwaitingResult` emit nothing. A crash after
`defer()` returned and before the tool returned leaves `AwaitingResult(id)`
on disk and a computation that will complete or expire into it. A crash
before `defer()` committed leaves `Running`, which re-fires the tool; a
computation created but not committed (the §3 failure window) expires into a
dropped mismatch. Both are the approval lifecycle's existing rules.

`ToolContext.invocation()` remains the deterministic address digest, so a
re-fired tool can deduplicate the external side effect it may already have
caused before the crash. That is what the digest is for; it was never the
callback address.

## 7. Retirements

- `DeferredToolCallPolicy` (agent.spi) and `ComputationDeferredToolCallPolicy`.
- `ApprovalContexts` (agent.spi) — nothing replaces it.
- `ToolExecution.Deferred(ComputationId)`; `ToolExecution` becomes
  `Immediate | AlreadyRecorded`.

  **Amendment (task-1-report, implementation).** `ToolExecution` does not
  survive even as that narrowed two-arm type — it retires entirely.
  `RegistryToolCallExecutor.run` (the private helper behind `runTool`)
  returns `Optional<ToolOutcome>` directly: empty means "the door already
  recorded the wait, deliver nothing"; present is what `runTool` wraps in
  `ToolFinished` and delivers. A skeleton type whose two arms were
  "deliver" and "don't" bought nothing once one `Optional` said the same
  thing.
- `RegistryToolCallExecutor`'s five-arg constructor, `defaultPolicy`,
  `defaultApprovalContexts`, `PARKING_UNAVAILABLE` — the no-Continuum
  shape; nothing replaces it.
- `ToolContext`'s record constructor and `events()` accessor; the
  `Awaited` javadoc's derived-id sentence.
- `ToolInvocationId`'s references to the policy (javadoc only).

## 8. Open questions — need James's yes

1. **How the executor learns the tool deferred** — the executor holds the
   `ComputationToolContext` it created, so the cheapest answer is a
   package-visible `deferred()` on it, checked after `execute`. The
   alternative is a public `Optional<ComputationId> deferral()` on
   `ToolContext` itself. Proposed: package-visible; the tool has no reason
   to ask a question it answered.

Settled with James, recorded so the plan can cite them: the executor
creates nothing (§0); no factory seam, executor holds the clients, always
Continuum (§1.4); `Draft.input(Class<T>)`, no type parameter (§1.3).

## 9. Tests

- **A tool hands out its id before returning.** A tool calls `defer()`,
  passes the id to a fake external system that completes it *immediately*
  (same thread, before the tool returns), then returns `deferred()`. The
  call reaches `Finished` with that result; nothing is dropped; the WARN log
  is empty. This is the test that distinguishes "ordered by construction"
  from "usually fast enough" for the tool kind — approval-lifecycle §10's
  early-answer test, mirrored.
- **`defer()` is idempotent** — same id twice, one computation created.
- **`deferred()` without `defer()`** fails in-band with the named message;
  **`ready(x)` after `defer()`** fails in-band with the named message; in
  both cases the transcript shows the failure and the phase reaches
  `Finished`.
- **`defer()` throws when the fold cannot commit** — a `Sink` whose
  `deliver` throws; the tool propagates; the call finishes in-band with the failure;
  the phase never left `Running`. Same test against
  `ComputationApprovalContext` — this is the §3 hole closing, and it must
  fail against today's `deliver`-based implementation.
- **`DefaultAgent.deliver` rethrows after narrating** — a throwing
  `Memory.remember` reaches the caller and `applyFailed` was observed once.
- **Draft carries the input** — an enricher reads `draft.input(T.class)`
  and deposits from it; the frozen document contains the deposit and no
  second copy of the input; `input(Wrong.class)` fails inside that enricher
  and the grant denies.
- **Slow tool runs once, pumps never starve, drop-on-mismatch** — the
  existing proofs are unchanged and stay green; `DeferredToolOnContinuumTest`
  migrates from reading the id out of the phase to receiving it from the
  tool.

## 10. Docs

`docs/concepts/tools.md` ("Deferring" gains the door and loses the policy),
`docs/concepts/durable-computation.md` (the tool-side ordering paragraph
becomes "by construction"), `docs/guides/harness.md` (the wiring seam),
`CHANGELOG.md` (breaking: `ToolContext` is an interface; `Awaited.deferred()`
requires `defer()`; retirements). Approval-lifecycle spec §4 and §15 get a
two-paragraph amendment pointing here.

## 11. Rejected

- **`Awaited.Deferred(ComputationId)`** carrying the id — a second copy of a
  fact the door already recorded.
- **Keeping the implicit path** alongside the door — two ways to defer, one
  of which cannot deliver an answer. James: the executor never creates.
- **`Draft<T>` / `Enricher<T>`** — wildcards on every grant for the minority
  of enrichers that read the input.
- **A `CallContexts` factory** replacing `ApprovalContexts` +
  `DeferredToolCallPolicy` — a factory-of-factories whose only purpose was
  a no-Continuum default that no wiring runs. James: "why wouldn't we
  always use Continuum?"
- **Letting `defer()` return normally on a failed fold** and trusting the
  staleness re-fire — it does self-heal, but the caller was lied to and the
  first answer is lost. The door's whole promise is that the id is real.
