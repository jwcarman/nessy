# Every call is approved — the approval lifecycle folds into the scope

**Date:** 2026-08-25
**Status:** draft for review
**Amends:** `2026-08-18-agent-as-scope-design.md` §2.2 (the phase grammar) and
§4.3 ("parks are not a state"); `2026-08-20-action-and-tool-vocabulary.md`
(the decision vocabulary); `2026-08-24-continuum-adoption-design.md` §5 (the
dispatch index), §11.2, §11.3 and §11.6 (the lease risks).

Every tool call is approved before it runs. A grant carries an **approver**;
the approver either answers now or says it will get back to us, and the
harness parks the question on Continuum until it does. An approval is a
question with exactly two answers, asked as a JSON document that is the
record of what was decided on, and the scope records, for each call, where
that question stands. That record is the lifecycle: **each tool call has its
own, folded into the state.** Nothing runs inside a delivery lease; the lease
pays for a message.

## 0. The thesis this reverses

The agent-as-scope design ruled (§4.3): *"From a phase's view there is no
difference between a tool that returns in 200ms and one that returns in three
days. Both are a pending call that will produce a `ToolFinished`."* Approval
was deliberately invisible to the reducer: the gate parked a call, a human
answered, and a `ToolFinished` arrived by the same path as a slow HTTP call.
The phase never knew anyone hesitated.

That ruling bought a small grammar, and it cost four things that have now all
come due at once:

1. **The lease.** Because the reducer has no fact to fold when a human says
   yes, the grant delivery has to *do the work* — run the approved tool on the
   pump thread, inside Continuum's lease. A tool slower than the lease is
   re-claimed and run twice (§11.2); two slow tools starve every other pump on
   the harness (§11.6). James's ruling on 2026-08-25: *the lease pays for
   delivering a message, never for doing the work.* Under the old thesis that
   rule cannot be kept, because there is no message to deliver.
2. **The index.** The reducer's ignorance forced call→computation into a side
   store, `DispatchIndex`, which can disagree with Continuum. Its orphan path
   logs `ERROR` and continues; the stale-grant guard exists to reconcile it;
   and the sharing rule — "two harnesses of one type share both stores or
   neither" — is a contract nobody can check, because the contradiction lives
   between two stores the builder cannot see together.
3. **Observability.** "Awaiting a human" is the one state an operator most
   wants to see, and it is unrepresentable. `Console`'s own javadoc records the
   consequence: parking is off-channel, no event is emitted, and a second park
   in one turn hangs the console unrecoverably. Park dwell time — the metric
   unique to this system — has nowhere to live.
4. **Vocabulary.** Three sealed types say "yes/no" three ways: `PolicyDecision
   {Allow, Deny, RequireApproval}`, `Adjudication {Granted, Refused,
   Suspended}`, `Decision {Allow, Deny}`. `Allow` means two different things.
   `Suspended` is not an answer at all — it is the parked state the reducer
   was not allowed to have.

The new thesis: **there is no difference, from a phase's view, between an
approver that answers now and one that answers in three days. Both produce
one `Approval` for the call.** The difference the phase *does* record is
whether that answer has arrived — and that record is what makes the lease
rule keepable, the index unnecessary, the wait observable, and the vocabulary
one word.

## 1. Vocabulary

Every word below appears once, and each names one thing.

### 1.1 The answer — `Approval`

```java
public sealed interface Approval {
  record Approved(Optional<String> reference) implements Approval {}
  record Denied(String reason, Optional<String> reference) implements Approval {}
}
```

One type, wherever the answer travels: spoken by a grant's own approver
in-process, by a person at the desk, or delivered by Continuum days later.
`reference` is an opaque pointer into whatever system produced the answer —
its decision id, a ticket number, a hash of its evidence bundle. Nessy never
interprets it; it is the join between the fold's record ("call `c1` was
approved") and the audit trail that knows *who* and *why* (§7). It replaces
`Decision` (the Continuum result) and the `Granted`/`Refused` arms of
`Adjudication` outright.

### 1.2 The question — `ApprovalRequest`

```java
public record ApprovalRequest(
    String agentType,
    String agentId,
    ToolCall call,
    String action,      // the ActionContributor's line, rendered at enrichment
    Facts facts) {}     // typed facts, serialized on deposit
```

**A JSON document, by contract.** Every field renders through the harness's
pinned mapper, deterministically, and the rendered document is the record of
what was decided on: read by the approver, parked with the computation when
the approver defers, shown to the desk, and pointed at by the answer's
reference. Rendered **once**: the `action` line and every fact are fixed at
enrichment and never re-derived at read time, so a later change to a
contributor or an enricher cannot rewrite what a human saw.

`Facts` is the typed fact bag — the machinery `AuthzContext` provides today
(`Key<T>`, typed `get`, fail-closed reads, the risk aggregation), with one
change that makes the document possible: **`deposit(key, value)` encodes the
value through the pinned mapper immediately** and stores JSON; `get(key)`
decodes to `key.type()`. A value the mapper cannot render fails *inside the
enricher, at the line that deposited it* — not hours later on a pump thread
when a human's answer tries to park the request. There is no way to put an
unrenderable value in, so there is no way for a request to fail to render.

Two consequences, both correct for evidence:

- **Facts, not handles.** An enricher that used to deposit a live object with
  behaviour deposits a description of it. A case file a human reads tomorrow
  on another machine holds facts.
- **Keys declare concrete types.** `Key<Intent>`, `Key<Principal>`,
  `Key<RiskAssessment>` — not `Key<Object>`, which three of today's four
  built-in keys are. A concrete type is what makes the round trip typed on the
  way out; `Object` keys were only ever workable because nothing came out of
  storage. The escape hatch survives — an application declares `Key<MyFact>`
  for anything the mapper handles — but the built-ins name their types.

Enrichment builds the request through a short-lived mutable draft:

```java
ApprovalRequest.Draft draft = ApprovalRequest.draft(agentType, agentId, call, pinned);
draft.action(contributor.render(input));            // once
for (Enricher e : grant.enrichers()) e.enrich(draft, input);   // each deposits typed facts
ApprovalRequest request = draft.freeze();            // immutable from here on
```

Nothing outside enrichment ever sees a `Draft`. `AuthzContext` retires; its
mechanism lives on as `Facts`, its role — the enriched question — is
`ApprovalRequest`.

### 1.3 The approver — `Approver`, `ApprovalContext`, `ApprovalOutcome`

```java
public interface Approver {
  ApprovalOutcome approve(ApprovalContext context);
}

public interface ApprovalContext {
  ApprovalRequest request();     // the question, enriched and frozen
  ApprovalOutcome defer();       // "I'll get back to you": parks the question, returns the outcome to hand back
}

public sealed interface ApprovalOutcome {
  record Answered(Approval approval) implements ApprovalOutcome {}
  record Deferred(ComputationId id) implements ApprovalOutcome {}   // minted only by defer()
}
```

`Approver` is a facade in the way `Memory` is: one method, and a world behind
it — a rule ladder, a risk service, a Slack post, an OPA call, a four-eyes
workflow, a quorum vote, a person at a terminal — none of it visible to the
harness, and all of it free to be asynchronous through `defer()`. It is the
existing `org.jwcarman.nessy.spi.approval.Approver` with this signature.

`ApprovalContext` mirrors `ToolContext`: what you learn about the invocation
you are serving, plus what you can do with it. `request()` is the question.
`defer()` is the door to a later answer: it creates the approval computation
— result type `Approval`, this call's routing as its continuation, and
Continuum's own deadline, the harness-level seven days the adoption spec's
§9 ruled — **folds `ApprovalDeferred(call, id)` into the scope
and waits for that fold to commit, then returns.** By the time the approver
holds an id it could tell anyone, the phase already names it. `defer()` is
idempotent: a second call returns the same outcome.

`Deferred` can be constructed only by `defer()`, so "returned Deferred but
nothing was parked" is unrepresentable. No approver sees Continuum, a kind, a
continuation or a lease; `Awaited` stays with tools, where a *tool* decides
whether its own result comes now or later.

The Slack approver, complete:

```java
context -> {
  var deferred = context.defer();                       // parked; the phase says AwaitingApproval(id)
  slack.post("#ops", render(context.request(), deferred.id()));
  return deferred;
}
```

**Telling people is the approver's business.** Pager, Slack, a ticket, an
email, a queue — whatever the approver does after `defer()`. There is no
`approvalNotifier` on `HarnessConfig` (§8): the fact is in the state, and
the thing that decided a human was needed is the thing that knows which
human.

### 1.4 The built-ins and the toolkit

`Approvers.allow()`, `Approvers.deny(reason)`, `Approvers.defer()` are the
three one-liners — `Answered(Approved)`, `Answered(Denied(reason))`,
`context.defer()`. A bare `Tool` in `.tools(...)` still means `allow()`.

`allow()` and `deny()` remain a sealed `Static` pair the executor recognises
and answers **without building the request** — enrichers do not run for a
call nobody will read the file of. This is the one place the executor knows
something about a specific approver, kept because it is an optimisation on a
known pair, not a second type.

There is no chain on the `Approver` interface. Composition is code inside an
approver — a risk check and then a park is three lines — and the toolkit
ships the two compositions people reach for:

```java
Approvers.rules(                                  // a ladder: first answer wins; the last word parks
    RiskRules.denyAt(RiskLevel.CRITICAL),
    IntentRules.requireDeclared(Ops.class),
    RiskRules.approveBelow(RiskLevel.MEDIUM),
    Rules.defer())

Approvers.allOf(a, b, c)                          // gates: every one must approve; any denial denies
```

A `Rule` has three outcomes — `Answered`, `Undecided`, or defer — and the
ladder is the `Approver`. "I am unable to decide" is a rule's word, inside
the toolkit, never the interface's. `allOf` is over rules that *answer*;
a gate that needs a human goes last in a ladder. Today's
`RiskPolicies.threshold(approveAt, denyAt)` becomes two rules;
`IntentPolicies.requireDeclared(vocabulary)` becomes one; `UsagePolicy.allOf`
becomes `Approvers.allOf`. `UsagePolicy` itself retires — it was the name for
an approver that happened to be pure, and the type no longer needs to know.

`nessy-testing` ships `ScriptedApprover` (answers or defers per a script,
like `ScriptedModel`) and `RecordingApprover`, because approvers are the
thing people most want to unit-test their agents against.

### 1.5 The grant

```java
ToolGrant.grant(tool, approver)
ToolGrant.grant(tool, approver, contributor, enrichers...)
```

The grant keeps the contributor and the enrichers — they build the question,
which two readers see (the approver now, the desk later), so they cannot live
inside either. The one word that changes is `policy` → `approver`.

**There is no deadline in this design.** Continuum requires every computation
to carry one and delivers its expiry through the ordinary path; the harness
stamps the approval kind with the seven-day default the adoption spec's §9
ruled harness-level, and an expired ask resumes the agent with a denial the
model reads. That is the whole mechanism, and it already exists. A per-grant
or per-approver bound was considered and left out: nothing needs it, and the
day something does, `defer(Duration)` is one method, not a concept.

### 1.6 The desk

```java
harness.approvals().approve(ComputationId id, String principal, String note)
harness.approvals().deny(ComputationId id, String principal, String reason)
harness.approvals().approve(String agentType, String agentId, String callId, String principal, String note)
harness.approvals().deny(...)                                   // by coordinates likewise
harness.approvals().withdraw(ComputationId id, String reason)  // folds as a denial; the ask is abandoned
harness.approvals().request(ComputationId id)                  // the parked ApprovalRequest, from storage
```

Two doors to the same fold: by id, for whoever was handed one — a Slack
message, a webhook, a test; by coordinates, for whoever has only the
question — a console, a UI listing what an agent is waiting on. Coordinates
resolve through the scope's phase, which is `AwaitingApproval(id)` for that
call: **the phase is the map.** A caller who answers before the harness has
folded the park finds the call `Pending` and is refused with "not awaiting
approval" — a loud, human-readable retry, not a lost answer.

The desk takes a principal and a note because **it is the one door with no
subsystem behind it**: when a person answers here directly, nobody else is
collecting evidence, so the desk refuses to be the place a yes can enter
anonymously. It folds them into the answer's `reference`.

## 2. The phase — one arm, richer entries

```java
public sealed interface Phase {
  record Idle() implements Phase {}
  record AwaitingModel() implements Phase {}
  record AwaitingTools(
      Message assistantTurn,
      Map<String, CallStatus> calls,     // callId → where its lifecycle stands
      ModelResponseId responseId) implements Phase {}
}

public sealed interface CallStatus {
  record Pending() implements CallStatus {}                            // approval sought, no answer yet
  record AwaitingApproval(ComputationId approval) implements CallStatus {} // the approver deferred; Continuum holds it
  record Running() implements CallStatus {}                            // approved; the tool is executing
  record AwaitingResult(ComputationId tool) implements CallStatus {}   // the tool deferred; Continuum holds it
  record Finished(ToolResultBlock result) implements CallStatus {}     // an outcome, success or failure
}
```

`AwaitingTools` keeps its name. Its `pending` set and `gathered` list merge
into one map whose values say, per call, where that call is. Nothing else in
the grammar changes: the scope enters `AwaitingTools` on `ModelFinished` with
tool calls, and leaves it — for `AwaitingModel` — when every entry is
`Finished`.

**States say what they await; acts say what happened.** `AwaitingApproval`
and `AwaitingResult` are named for what is owed, not for the act that put the
call there. The acts have their own past-tense names in the event grammar
(§3). Two of the five statuses wait on Continuum, and they are one mechanism
used twice: **a call waiting on a computation records that computation's id
in its status, is resolved by that computation's delivery, recognises the
delivery by the id, and is never re-fired.** The dispatch index existed to
remember both of these outside the phase; the phase now remembers them
itself, the same way.

**Every call walks the same machine.** The `ModelFinished` fold emits one
`SeekApproval(call)` per call and marks each `Pending`. Seeking approval
produces an *answer* — from the approver now, or from Continuum later — and
the fold of an `Approved` answer is what emits `RunTool(call)`. No effect both
asks and runs; the answer is always a folded fact between the two, whoever
spoke it:

```
Pending ──ApprovalAnswered(∅, Approved)─────────► Running ──ToolFinished(∅)───► Finished
Pending ──ApprovalAnswered(∅, Denied(r))────────────────────────────────────► Finished(failed r)
Pending ──ApprovalDeferred(id)──► AwaitingApproval(id)
AwaitingApproval(id) ──ApprovalAnswered(id, Approved)──► Running ─────────────► Finished
AwaitingApproval(id) ──ApprovalAnswered(id, Denied(r))────────────────────────► Finished(failed r)
Running ──ToolDeferred(id)──► AwaitingResult(id) ──ToolFinished(id, outcome)──► Finished
```

An immediate answer costs one fold more than today — the approver says
`Approved`, that commits, then the tool runs — one `Substrate.batch` per
call, in exchange for every call's lifecycle being recorded and identical.
A call whose approver answers on the spot — `allow()`, a rule ladder, a
console prompt, a test double — goes `Pending → Running` inside the same
`SeekApproval` effect and is never *in* `AwaitingApproval`. Only `defer()`
puts it there. The reducer cannot tell one in-process approver from another,
and must not: which approver a grant carries is configuration, not a
lifecycle branch.

**Ruled: per call, not as a set.** The moment one call's approval lands, its
tool runs. A turn that asked for `read_config` and `restart_prod` together
does not hold the harmless read for eight hours because the restart needs a
signature; parallel tool calls mean independent tool calls. The scope is not
"awaiting approvals" as a phase it must march through — it is awaiting calls,
and *some of those calls are awaiting approval*. That derived view is what
the console narrates and the metrics count. An operator who wants "nothing in
this turn runs until a human has seen all of it" expresses that with the
approvers on those grants, not as a reducer rule for everyone.

**Ruled: the model never knows.** `assistantTurn` and the `Finished` results
are what reach the model on the next `CallModel`; `AwaitingApproval` and its
dwell are for operators, memory, and metrics. The scope design's stance — the
model never knew anyone hesitated — is unchanged. The phase records it; the
context renderer does not show it.

## 3. Events and effects

Three events join the grammar and one gains an id; one effect is split into
two; nothing leaves:

```java
sealed interface AgentEvent {
  record Observed(...)                                                    // unchanged
  record ModelFinished(...)                                               // unchanged
  record ApprovalDeferred(ToolCall call, ComputationId approval)          // the approver parked the question
  record ApprovalAnswered(ToolCall call, Optional<ComputationId> approval, Approval answer)
  record ToolDeferred(ToolCall call, ComputationId tool)                  // the tool parked its result
  record ToolFinished(ToolCall call, Optional<ComputationId> tool, ToolOutcome outcome)
}

sealed interface Effect {
  record CallModel()                                                      // unchanged
  record SeekApproval(ToolCall call)                                      // ask; yields ApprovalAnswered or ApprovalDeferred
  record RunTool(ToolCall call)                                           // run; yields ToolFinished or ToolDeferred
}
```

The optional id on an answer or a result is present when it was delivered
from a parked computation and absent when it was produced in-process; the
reducer uses it only for the identity check below. `ExecuteTool` — today's
"evaluate, then run or park" — is gone; each of its halves is an effect with
exactly one kind of result.

`AwaitingTools.handle` is the whole reducer change, and it is a matrix:

| status of `call` | event | next status | effects |
|---|---|---|---|
| `Pending` | `ApprovalAnswered(∅, Approved)` | `Running` | `RunTool(call)` |
| `Pending` | `ApprovalAnswered(∅, Denied(r))` | `Finished(failed r)` | `CallModel` if all finished |
| `Pending` | `ApprovalDeferred(id)` | `AwaitingApproval(id)` | — |
| `Pending` | `ApprovalAnswered(id, _)` | unchanged | — (permanent: `defer()` folds before it hands back the id, so this is an orphan or a duplicate; the worker drops it with a WARN — §4) |
| `AwaitingApproval(id)` | `ApprovalAnswered(id, Approved)` | `Running` | `RunTool(call)` |
| `AwaitingApproval(id)` | `ApprovalAnswered(id, Denied(r))` | `Finished(failed r)` | `CallModel` if all finished |
| `AwaitingApproval(id)` | `ApprovalAnswered(other, _)` | unchanged | — (stale: an orphan's answer, ignored) |
| `Running` | `ToolFinished(∅, o)` | `Finished` | `CallModel` if all finished |
| `Running` | `ToolFinished(id, _)` | unchanged | — (mismatch: a `Running` call names no computation, so this is one the scope knows nothing of — §4) |
| `Running` | `ToolDeferred(id)` | `AwaitingResult(id)` | — |
| `AwaitingResult(id)` | `ToolFinished(id, o)` | `Finished` | `CallModel` if all finished |
| `AwaitingResult(id)` | `ToolFinished(other, _)` | unchanged | — (stale: an orphan computation's result, ignored) |
| `Finished` | anything for this call | unchanged | — (stale, ignored) |
| any | event for an unknown call | unchanged | — (stale, ignored) |

The identity check in the `AwaitingApproval` rows is the whole of the §11.3
stale-grant guard, relocated: a delivered answer is honoured iff the phase
names its computation. No index, no second store, no reconciliation.
`Transition.ignore()` for the stale rows is the same dedup that already makes
at-least-once delivery safe (§2.5). `Running` + `ApprovalAnswered` is also
stale — a duplicate delivery of an answer already folded — and ignored.

**`Running` + `ToolFinished(id, _)` is ignored like every other mismatch.** A
`Running` call names no computation — it does not until `ToolDeferred` folds —
so a delivered id is by definition one the scope knows nothing of, and there is
no check that could tell the call's own result from an orphan's. Admitting it
unchecked would let a stale orphan finish a live call. Nothing is lost by
ignoring it, because the window does not open in practice (§4).

**`Pending` means "approval sought, no answer recorded."** A call is `Pending`
from the fold that emitted `SeekApproval` until an `ApprovalAnswered` or
`ApprovalDeferred` folds. The staleness re-fire (§6.1) re-emits
`SeekApproval` for every `Pending` call and `RunTool` for every `Running`
call, and leaves `AwaitingApproval` and `AwaitingResult` alone — Continuum
holds those and will deliver. A tool that deferred yesterday is not run again
today because the phase says it deferred; that is the absorption the dispatch
index used to provide, now a status.

## 4. The executor — two doors, neither with a conditional inside

```java
public interface ToolCallExecutor {
  void seekApproval(ToolCall call, ModelResponseId responseId, Sink sink); // yields ApprovalAnswered or ApprovalDeferred
  void runTool(ToolCall call, ModelResponseId responseId, Sink sink);      // yields ToolFinished or ToolDeferred
}
```

`SeekApproval(call)` reaches `seekApproval`, on the harness executor, never
on the dispatching stack. Inside:

1. Find the grant. Unknown tool → `ApprovalAnswered(∅, Denied("unknown tool"))`.
2. If the grant's approver is `Static` — `allow()` or `deny()` — answer
   without building a request, and stop.
3. Convert the input; build the request: draft, contributor, enrichers,
   freeze (§1.2). A conversion, contributor or enricher failure is
   `ApprovalAnswered(∅, Denied(reason))` — a call whose case file cannot be
   built cannot be approved, and it fails closed here, naming the stage.
4. `approver.approve(context)` where `context` holds the frozen request and
   the `defer()` door:
   - `Answered(a)` → deliver `ApprovalAnswered(∅, a)`.
   - `Deferred(id)` → `defer()` already parked the request, folded
     `ApprovalDeferred(call, id)` and waited for the commit. Nothing more to
     deliver; nothing is narrated to the model.

This door never runs a tool. `RunTool(call)` reaches `runTool`: find the
grant, convert, run. `Awaited.Ready(result)` → deliver `ToolFinished(∅,
outcome)`. `Awaited.Deferred` → the tool has parked its result on the tool
kind's computation, whose id the executor holds; deliver `ToolDeferred(call,
id)`. This door never consults an approver — the answer is already a fact in
the phase. Today's `executeTool` (evaluate-then-maybe-run) and
`executeGrantedToolNow` (run, synchronously, from the delivery) are each
replaced by the half they were doing.

**Ordering, and where it lives.** A parked question could be answered the
instant it exists — a test approver, a webhook, a person already watching a
queue. If that answer could arrive before the park had folded, the phase
would not yet name the id, the mismatch row would discard the answer, and the
call would wait forever on a question already answered. The design closes
that in two places, neither of them a rule the harness enforces around the
approver:

- **Approvals: inside `defer()`.** It folds `ApprovalDeferred` and waits for
  the commit *before returning the id*. Nobody can be told about a question
  the scope has not recorded, because nobody has the id yet. The desk's
  by-coordinates door, which needs no id, refuses a `Pending` call loudly
  (§1.6).
- **Tools: the window does not exist in practice.** `runTool` cannot use the
  `defer()` trick: the tool body never holds the Continuum id at all. What it
  gets in `ToolContext` is the address digest, an idempotency key; the
  computation is minted by the EXECUTOR, on the `Awaited.Deferred` arm, in the
  statement immediately after the tool body returns. But no trick is needed,
  because that leaves nothing to race. The `create` carries a one-day default
  deadline, and the very next statement on that same thread folds
  `ToolDeferred`. For a result or an expiry to arrive first, the reaper and the
  deliver pump would have to beat a single thread hop, against a deadline
  measured in days — and nobody outside can complete the computation anyway,
  since the only handle to its id is the phase the fold is about to write. Nor
  does the crash path open it: a crash between `create` and the fold leaves an
  orphan computation, the re-fired `RunTool` mints a SECOND one, and the
  orphan's eventual expiry meets `AwaitingResult(id2)` — a mismatch, correctly
  dropped, with the live computation untouched. So there is no exception to
  make: `Running` + `ToolFinished(id)` is ignored (§3) and the delivery is
  dropped. Giving `ToolContext` a `defer()`-style door remains the symmetric
  next step (§15), not a correctness fix.

**Everything else is permanent, and is dropped.** With both windows closed, a
delivery whose scope is not in the status that awaits it can never improve:
it is an orphan, a duplicate, or a §6 re-ask's loser. `DeliveryWorker` logs it
at WARN — naming the agent, the call, the computation, and the status it
found — and CONSUMES the delivery. Nothing is released for redelivery; there
is no early-delivery exception and no backoff-and-retry path. Retrying a
permanent failure every 5s until a 7-day deadline is noise, not durability.

## 5. Delivery — the lease pays for a message

`DeliveryWorker`'s approval consumer becomes: read the delivery's `Approval`
and routing, fold `ApprovalAnswered(call, id, approval)` into the scope,
commit, return. If the fold produced `RunTool`, it is dispatched after the
commit on the harness executor, the way every effect is dispatched. The lease
covers one `Substrate.batch`. §11.2 and §11.6 are not mitigated; they are
unrepresentable — no consumer of either kind ever runs a tool.

The tool kind's consumer is unchanged in shape: it folds `ToolFinished(call,
id, outcome)`.

## 6. Recovery — one arm, per call

A crash anywhere is answered by the existing re-fire arm (§6.1): a quiet
`AwaitingTools` re-emits `SeekApproval` for each `Pending` call and `RunTool`
for each `Running` call, and nothing for `AwaitingApproval` or
`AwaitingResult`. That is at-least-once with a re-run on real crash, which is
what the lease used to buy and what "run inside the lease" confused with
slowness. A tool that ran before the crash may run again after it and makes
itself idempotent like every other tool (scope design §6). An approver
re-asked on re-fire is at-least-once the same way: a rule ladder is free, a
risk service is called twice, a console prompt re-asks its human — the
"re-askable rendezvous" the scope design already licensed.

**Re-enrichment makes a new request.** A re-fired `Pending` call builds its
question again. An enricher that deposits "now" or a fresh id produces a
different document; that is correct — it is the new evidence, and the
approver decides on whichever document it was handed.

**Ruled: re-run, not expire.** The alternative — give every approved run a
computation with a deadline and let a crash expire loudly — was considered
and rejected as machinery for a case the re-fire arm already covers; it also
turns a one-day default deadline into a one-day silence for a crashed
five-second tool.

One window is named rather than closed: a crash *inside* `defer()` — after
the computation is created and before `ApprovalDeferred` commits — leaves the
call `Pending`, and the re-fire re-asks: the approver defers again, a second
computation is created, and a human may be asked twice. Only the answer whose
id the phase names is honoured (§3); the other is an orphan, acknowledged and
ignored, exactly the §11.3 resolution today. The request can carry a
"re-asked after recovery" fact so a human who sees two understands why. This
is the same window the create-then-index ordering has now, and it stays
because computation ids are opaque (`computation-ids-stay-opaque`): a
deterministic address per call would close it and was ruled out.

## 7. Audit — what the core owes, and what it does not

Evidence, identity, votes, ledgers and retention belong **behind the veil**:
the approver subsystem saw the request, talked to the humans, ran the policy
engine, and holds the record. Pulling that into the core would make Nessy a
worse audit log than the thing that did the work. What the core owes is
exactly what nothing outside it can produce:

1. **The question, as asked** — the request JSON, built at the moment of the
   call from state only the harness has (§1.2).
2. **A handle whose completion resumes the agent** — `defer()`'s id (§1.3).
3. **The resumption** — the fold that runs the tool when the answer lands.
4. **The clock** — Continuum's deadline on the parked computation, because a
   subsystem cannot change when Nessy gives up (§1.5).

And one join: the answer's `reference` (§1.1), pointing from the fold's
record to the subsystem's. The subsystem's record holds — or hashes — the
request document it received, so "what did the approver see when it said
yes?" is answerable from *its* storage, and the desk shows the *same*
document from Nessy's (§1.6 `request(id)`). The desk is the one door that
must not take an anonymous yes, because nothing stands behind it.

## 8. What retires

- `PolicyDecision`, `Adjudication`, `Decision`, `DecisionCodec` — replaced by
  `Approval` and `ApprovalOutcome`.
- `AuthzContext` — its mechanism becomes `Facts`; its role is
  `ApprovalRequest`. `Key<Object>` built-ins become concrete.
- `UsagePolicy`, `RiskPolicies`, `IntentPolicies` — replaced by `Approver`,
  `Approvers`, `Rule`, `RiskRules`, `IntentRules`.
- `HarnessConfig.approvalNotifier` — telling people is the approver's job.
- `DispatchIndex`, `CallAddress.indexKey()`, `ComputationDeferredToolCallPolicy
  .pendingComputation` absorption, and `DeliveryWorker.isCurrentDispatch` — the
  phase names its computations.
- `ToolCallExecutor.executeTool` and `executeGrantedToolNow` — replaced by
  `seekApproval` and `runTool`, each doing one half.
- The two-step `grant.assemble` then `grant.decide` — the harness builds the
  request once; the approver reads it.
- The sharing rule's Continuum half. Two harnesses sharing a type and a
  substrate still write one set of scopes, so they must share the Continuum
  those scopes name — but the failure when they do not is now *loud*: an
  answer arrives for a computation no phase names and is ignored with a WARN,
  rather than draining into a scope that reads `Idle`.

Measured at `2a9a4b50`: roughly 640 lines of main source leave (the whole of
`DispatchIndex`, `DecisionCodec`, the three decision types, the grant arms of
`DeliveryWorker`, the gate and past-gate paths of `RegistryToolCallExecutor`,
most of `ComputationApprover` and `ComputationDeferredToolCallPolicy`) and
about 600 lines of tests that existed only for them. Roughly 300 come in.

## 9. What this buys, measured

- **Park dwell, per call:** `AwaitingApproval` entered → `ApprovalAnswered`
  folded. The number the o11y generation most wanted, with a home.
- **Approval latency, tagged by approver:** ~0 for a rule ladder, seconds for
  a console, hours for a desk — one metric, not a special case.
- **A narratable wait.** `AgentObserver` sees the phase and can say "awaiting
  approval of `restart_prod` for 3h12m." The console's second-park hang is
  gone because the console can *see* the second park.
- **Failure classes removed, not mitigated:** the lease double-run, pump
  starvation, index/Continuum disagreement, the orphan `ERROR` path, the
  re-ask-every-five-minutes hazard, the console hang. Reduced from silent to
  loud: the sharing-rule violation. Unchanged: re-run on crash.

## 10. Testing

- The reducer matrix in §3, cell by cell, in `PhaseTest` — every stated row,
  every stale row, and the all-finished → `CallModel` transition.
- `HarnessApprovalDemo`, `SharedContinuumTest`, `DurableResumeTest` pass with
  one change: the phase assertion reads `AwaitingTools` with the call
  `AwaitingApproval`, then `Idle`. Their outcome is unchanged; their *middle*
  is now observable.
- **A slow approved tool runs once.** An approval-gated `Ready` tool that
  sleeps past a deliberately tiny approval lease completes exactly once and
  the turn finishes — the §11.2 test that could not be written before.
- **Two slow grants do not starve the harness.** With both pool threads busy
  under the old design, the tool-kind deliver pump could not run; the new
  test parks two slow approvals and asserts a third, unrelated deferred tool's
  result is folded while they run.
- **Re-fire per status.** A `Pending` call is re-asked, a `Running` call is
  re-run, an `AwaitingApproval` or `AwaitingResult` call is left alone — the
  latter is the absorption test `AbsorptionTest` used to be, re-homed on the
  phase.
- **Stale answers.** An `ApprovalAnswered` naming an id the phase does not
  hold is ignored, with the scope unchanged.
- **Early answers.** An approver that defers and whose computation is
  answered in the same breath still resolves the call, on the FIRST drain:
  `defer()` does not return until the park has folded, so a legitimate early
  answer never meets a `Pending` call. The desk's by-coordinates door refuses
  a `Pending` call with a message. These are the tests that distinguish
  "ordered by construction" from "usually fast enough."
- **Mismatched deliveries are dropped, not redelivered.** An answer against a
  `Pending` call, an answer naming a computation an `AwaitingApproval` call
  does not hold, a result naming a computation an `AwaitingResult` call does
  not hold, and a result reaching a call still `Running` are each logged at
  WARN and consumed — the assertion is that a later drain returns zero, i.e.
  nothing came back. The rule has no exception.
- **The request is a document.** `ApprovalRequest` round-trips through the
  pinned mapper byte-for-byte; an enricher depositing an unrenderable value
  fails inside `deposit`, naming the key; the desk's `request(id)` returns
  the same document the approver was handed.
- **`Static` short-circuit.** An `allow()` grant runs no enricher; an enricher
  that throws never fires for it.
- **The desk.** By-id and by-coordinates doors reach the same fold; an
  anonymous answer is refused; `withdraw` folds as a denial; `deny` without
  a reason is refused.

## 11. Documentation

`docs/concepts/durable-computation.md` and `docs/guides/harness.md` are
rewritten where they describe parks as an address book, the dispatch index,
the notifier, and the "one desk answers approvals" wiring;
`docs/guides/providers.md`'s `ApprovalPlayground` walkthrough gains the
narrated wait; a new guide section, *Writing an approver*, carries the Slack
example and the rule ladder verbatim. The CHANGELOG's Unreleased section
records the vocabulary collapse as a breaking change.

## 12. Decisions ruled in conversation (2026-08-25)

1. The lease pays for delivering a message, never for doing the work.
2. Every call is approved. A grant carries an approver; there is no separate
   policy notion, and no chain on the interface — composition is code
   inside an approver, with a rule ladder shipped as a toolkit.
3. `Approver` is a facade like `Memory`: one method, a world behind it,
   asynchronous through `ApprovalContext.defer()`, which does the plumbing.
4. The question is `ApprovalRequest`, enriched by the grant's contributor and
   enrichers before the approver sees it, and it is a JSON document by
   contract — serialized on deposit, rendered once, parked as evidence.
5. Each call has its own lifecycle, folded into the state — per call, not as
   a set; states are named for what they await.
6. Audit is the approver subsystem's; the core owes the question, the handle,
   the resumption, the clock, and a reference.

## 13. Needs sign-off

1. **`CallStatus`** — `Pending | AwaitingApproval(id) | Running |
   AwaitingResult(id) | Finished` — inside `AwaitingTools`. The names are the
   "states say what they await" ruling; `Escalated`/`Deferred` as status
   names were considered and rejected as naming acts, not waits.
2. **Events** `ApprovalDeferred`, `ApprovalAnswered`, `ToolDeferred`, and the
   id on `ToolFinished`; **effects** `SeekApproval`, `RunTool` replacing
   `ExecuteTool`. The event grammar was called a "designed ceiling" in §2.4;
   this raises it by three.
3. **`Approver.approve(ApprovalContext) → ApprovalOutcome`**, with
   `ApprovalContext.request()` and `defer()`.
4. **`ApprovalRequest` and `Facts`** replacing `AuthzContext`; concrete types
   on the built-in keys; `Enricher.enrich(Draft, input)`.
5. **`Approval.Approved(reference)` / `Denied(reason, reference)`**, and the
   desk taking a principal and a note; `withdraw`; `request(id)`.
6. **`ToolGrant.grant(tool, approver, ...)`**;
   `Approvers.allow/deny/defer`, `Approvers.rules`, `Approvers.allOf`,
   `Rule`; `RiskRules`, `IntentRules`; `ScriptedApprover`,
   `RecordingApprover`.
7. **`approvalNotifier` retires.**
8. **Model visibility stays off** (§2) and **crash means re-run** (§6).
9. **A mismatched delivery is dropped with a WARN, never redelivered**
   (James's ruling, 2026-08-25), with NO exception: a result reaching a call
   still `Running` is a mismatch like any other, because that call names no
   computation and the window it would rescue does not open in practice —
   a one-day deadline versus a thread hop, and a crash re-creates rather than
   races (§3, §4). `EarlyDeliveryException` retires.

## 14. Rejected

- **A distinct `AwaitingApprovals` phase the scope marches through.** Holds
  allowed calls hostage to siblings still awaiting approval; adds a second arm
  and a second set of transitions for a property that is better derived (§2).
- **Approved runs as tool computations with deadlines** (an earlier
  proposal). Correct but heavier: a completion door on the policy SPI, a
  computation per approved call, and a crash that goes quiet for a day. The
  re-fire arm already does the job (§6).
- **Bounding the inline run with a stopwatch.** Still does the work inside
  the lease; violates decision 1.
- **A policy/approver split, with a harness-level chain.** Two notions for
  one act, and a chain that forced one shape on every tool. One `Approver`
  per grant, composition inside it.
- **`Awaited<Approval>` as the approver's return.** Put Continuum's shape on
  the approver's interface; `defer()` on the context keeps the approver
  ignorant and does the plumbing.
- **An `approvalNotifier` seam, or telling people via observers.** A single
  dumb pipe cannot route per grant; observers would work but put the
  knowledge of *who* to tell far from the thing that decided a human was
  needed. The approver tells.
- **Deterministic approval addresses** to close the re-ask window (§6).
  Ruled out earlier: computation ids stay opaque.
- **`Escalated` as a word in the design.** Named a ladder of authority the
  core never requires. Composition is inside approvers; nobody escalates.

## 15. Deliberately not done

- No change to how a tool *defers* (`Awaited.Deferred`) or completes
  (`CompletionDesk`); only to how the scope remembers that it did. Giving
  `ToolContext` a `defer()` door of its own — the real computation id in the
  tool's hands, replacing the digest key — is the symmetric next step and is
  noted, not taken.
- No change to what the model sees.
- No metrics, spans or decision ledger — this spec gives them a place to
  attach; the o11y and journal generations attach them.
- No approver-side retention policy; Continuum's result TTL stays as is.
- `nessy-intent` and `nessy-tool-mcp` adapt to the vocabulary rename and the
  rule toolkit only.
