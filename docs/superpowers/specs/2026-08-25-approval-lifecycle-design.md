# Every call is approved — the approval lifecycle folds into the scope

**Date:** 2026-08-25
**Status:** draft for review
**Amends:** `2026-08-18-agent-as-scope-design.md` §2.2 (the phase grammar) and
§4.3 ("parks are not a state"); `2026-08-20-action-and-tool-vocabulary.md`
(the decision vocabulary); `2026-08-24-continuum-adoption-design.md` §5 (the
dispatch index), §11.2, §11.3 and §11.6 (the lease risks).

Every tool call is approved before it runs. The policy is the first approver
and answers immediately — yes, no, or "not mine to say," handing over a
dossier for whoever decides. An approval is a question with exactly two
answers, and the scope records, for each call, where that question stands.
That record is the lifecycle: **each tool call has its own, folded into the
state.** Nothing runs inside a delivery lease; the lease pays for a message.

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

The new thesis: **there is no difference, from a phase's view, between a
policy that answers now and a human who answers in three days. Both produce
one `Approval` for the call.** The difference the phase *does* record is
whether that answer has arrived — and that record is what makes the lease
rule keepable, the index unnecessary, the wait observable, and the vocabulary
one word.

## 1. Vocabulary — three types, one question

**The question** is *may this call run?* **The answer** is:

```java
public sealed interface Approval {
  record Approved() implements Approval {}
  record Denied(String reason) implements Approval {}
}
```

One type, wherever the answer travels: spoken by a policy in-process, by a
human at a console, by a webhook, or delivered by Continuum days later. It
replaces `Decision` (the Continuum result) and the `Granted`/`Refused` arms of
`Adjudication` outright.

**The policy** answers, or hands over the case file:

```java
public sealed interface PolicyOutcome {
  record Answered(Approval approval) implements PolicyOutcome {}
  record Escalated(ApprovalRequest request) implements PolicyOutcome {}
}
```

`Escalated` carries the **dossier** — `ApprovalRequest`, which already
exists: the call, the agent coordinates, and the assembled `AuthzContext` with
the rendered action, risk assessment and every enricher's contribution. Today
the policy says `RequireApproval()` empty-handed and the gate assembles the
context in a second step; here the policy that escalates is the one that
hands over everything a decider needs. `PolicyDecision` retires;
`UsagePolicy.evaluate(AuthzContext)` returns `PolicyOutcome`. The three
statics keep their names and meanings: `allow()` answers `Approved`, `deny(r)`
answers `Denied(r)`, and `requireApproval()` becomes `escalate()`: it cannot
decide on its own and hands the question up with the dossier. What is on the
other side of that hand-off — one human, a chain of approvers, an automated
service that defers to a person — is the approver's business, and the policy
assumes nothing about it. That opacity is deliberate: chains, delegation and
fan-out are approver implementations, never new concepts in the policy.

**The approver** takes a dossier and answers — now, or later:

```java
public interface Approver {
  Awaited<Approval> approve(ApprovalRequest request);
}
```

This is the existing `org.jwcarman.nessy.spi.approval.Approver` with its
signature changed — `adjudicate(request) → Adjudication` becomes
`approve(request) → Awaited<Approval>` — not a second seam beside it.
`Awaited` is the type tools already return, and it means the same thing here:
`Ready(approval)` — answered on the spot (the console's `approve? [y/N]`, a
test approver, an automated risk service); `Deferred` — the answer will arrive
as the result of an approval computation whose result type is `Approval`. A
tool computation's result is a `ToolResult`; an approval computation's result
is an `Approval`; the delivery path, the lease rule and the fold shape are the
same for both. `Adjudication` retires; its `Suspended(computation)` arm was the
phase this spec adds, misfiled as a return value.

The policy never sees `Awaited`, Continuum, or a lease. It answers or it
escalates. How the escalation is carried is the harness's business, and the
policy stays pure and re-evaluable, as §4.2 of the scope design requires.

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
  record Pending() implements CallStatus {}                       // approval sought, no answer yet
  record Escalated(String approvalId) implements CallStatus {} // asked and parked; Continuum holds it
  record Running() implements CallStatus {}                        // approved; the tool is executing
  record Finished(ToolResultBlock result) implements CallStatus {} // an outcome, success or failure
}
```

`AwaitingTools` keeps its name. Its `pending` set and `gathered` list merge
into one map whose values say, per call, where that call is. Nothing else in
the grammar changes: the scope enters `AwaitingTools` on `ModelFinished` with
tool calls, and leaves it — for `AwaitingModel` — when every entry is
`Finished`.

**Every call walks the same machine.** The `ModelFinished` fold emits one
`SeekApproval(call)` per call and marks each `Pending`. Seeking approval
produces an *answer* — from the policy now, from a synchronous approver now,
or from Continuum later — and the fold of an `Approved` answer is what emits
`RunTool(call)`. No effect both asks and runs; the answer is always a folded
fact between the two, whoever spoke it:

```
Pending ──ApprovalAnswered(Approved)──────────► Running ──ToolFinished──► Finished
Pending ──ApprovalAnswered(Denied(r))──────────────────────────────────► Finished(failed r)
Pending ──Escalated(id)──► Escalated(id)
Escalated(id) ──ApprovalAnswered(id, Approved)──► Running ──────► Finished
Escalated(id) ──ApprovalAnswered(id, Denied(r))─────────────────► Finished(failed r)
```

An immediate answer costs one fold more than today — the policy says
`Approved`, that commits, then the tool runs — one `Substrate.batch` per
call, in exchange for every call's lifecycle being recorded and identical.

**`Escalated` is one word at three levels, meaning the same thing at each.**
`PolicyOutcome.Escalated(dossier)` is the policy's act — "I cannot decide;
here is the file." `AgentEvent.Escalated(call, id)` is the fact that folds —
"this call was parked with the approver under this id." `CallStatus.Escalated
(id)` is the call's state — "out of our hands until an answer comes back."
`Deferred` already plays this role across `Awaited` and `ToolExecution`; this
is the same house style. A call whose escalation is answered on the spot — a
console's `approve? [y/N]`, a test approver, an automated service — was
escalated as an act but is never *in* the escalated state: the approver's
`Ready(a)` folds as `ApprovalAnswered(∅, a)` inside the same `SeekApproval`
effect and the call goes `Pending → Running` directly. Only an approver that
returns `Deferred` puts a call into `Escalated(id)`. The reducer cannot tell
a policy's answer from a synchronous approver's, and must not: which approver
is wired is harness configuration, not a lifecycle branch.

**Ruled: per call, not as a set.** The moment one call's approval lands, its
tool runs. A turn that asked for `read_config` and `restart_prod` together
does not hold the harmless read for eight hours because the restart needs a
signature; parallel tool calls mean independent tool calls. The scope is not
"awaiting approvals" as a phase it must march through — it is awaiting calls,
and *some of those calls are awaiting approval*. That derived view is what
the console narrates and the metrics count. An operator who wants "nothing in
this turn runs until a human has seen all of it" expresses that as a policy
that escalates the whole turn, not as a reducer rule for everyone.

**Ruled: the model never knows.** `assistantTurn` and the `Finished` results
are what reach the model on the next `CallModel`; `Escalated` and its
dwell are for operators, memory, and metrics. The scope design's stance — the
model never knew anyone hesitated — is unchanged. The phase records it; the
context renderer does not show it.

## 3. Events and effects

Two events join the grammar; one effect is split into two; nothing leaves:

```java
sealed interface AgentEvent {
  record Observed(...)                                            // unchanged
  record ModelFinished(...)                                       // unchanged
  record Escalated(ToolCall call, String approvalId)      // the ask was parked on Continuum
  record ApprovalAnswered(ToolCall call, Optional<String> approvalId, Approval approval)
  record ToolFinished(ToolCall call, ToolOutcome outcome)         // unchanged
}

sealed interface Effect {
  record CallModel()                                              // unchanged
  record SeekApproval(ToolCall call)                              // ask: policy, then approver; yields an Approval* event
  record RunTool(ToolCall call)                                   // run: past the gate; yields ToolFinished
}
```

`ApprovalAnswered.approvalId` is present when the answer came from a parked
computation and absent when the policy or a synchronous approver spoke; the
reducer uses it only for the identity check below. `ExecuteTool` — today's
"evaluate, then run or park" — is gone; each of its halves is an effect
with exactly one kind of result.

`AwaitingTools.handle` is the whole reducer change, and it is a matrix:

| status of `call` | event | next status | effects |
|---|---|---|---|
| `Pending` | `ApprovalAnswered(∅, Approved)` | `Running` | `RunTool(call)` |
| `Pending` | `ApprovalAnswered(∅, Denied(r))` | `Finished(failed r)` | `CallModel` if all finished |
| `Pending` | `Escalated(id)` | `Escalated(id)` | — |
| `Pending` | `ApprovalAnswered(id, _)` | unchanged | — (early: the request has not folded yet; the worker releases, not acks — §4) |
| `Escalated(id)` | `ApprovalAnswered(id, Approved)` | `Running` | `RunTool(call)` |
| `Escalated(id)` | `ApprovalAnswered(id, Denied(r))` | `Finished(failed r)` | `CallModel` if all finished |
| `Escalated(id)` | `ApprovalAnswered(other, _)` | unchanged | — (stale: an orphan's answer, ignored) |
| `Running` | `ToolFinished` | `Finished` | `CallModel` if all finished |
| `Finished` | anything for this call | unchanged | — (stale, ignored) |
| any | event for an unknown call | unchanged | — (stale, ignored) |

The identity check in the `Escalated` rows is the whole of the §11.3
stale-grant guard, relocated: a parked answer is honoured iff the phase names
its computation. No index, no second store, no reconciliation.
`Transition.ignore()` for the stale rows is the same dedup that already makes
at-least-once delivery safe (§2.5). `Running` + `ApprovalAnswered` is also
stale — a duplicate delivery of an answer already folded — and ignored.

**`Pending` means "approval sought, no answer recorded."** A call is `Pending`
from the fold that emitted `SeekApproval` until an `ApprovalAnswered` or
`Escalated` folds. The staleness re-fire (§6.1) re-emits
`SeekApproval` for every `Pending` call and `RunTool` for every `Running`
call, and leaves `Escalated` alone — Continuum holds those and will
deliver.

## 4. The executor — two doors, neither with a conditional inside

`ToolCallExecutor` gains one door and changes one:

```java
public interface ToolCallExecutor {
  void seekApproval(ToolCall call, ModelResponseId responseId, Sink sink); // yields Escalated or ApprovalAnswered
  void runTool(ToolCall call, ModelResponseId responseId, Sink sink);      // yields ToolFinished
}
```

`SeekApproval(call)` reaches `seekApproval`, on the harness executor, never on
the dispatching stack. Inside:

1. Find the grant. Unknown tool → `ApprovalAnswered(∅, Denied("unknown tool"))`.
2. Convert the input; assemble the `AuthzContext` (rung 0 statics skip this).
   A conversion or assembly failure is `ApprovalAnswered(∅, Denied(reason))` —
   a call that cannot be understood cannot be approved.
3. `grant.policy().evaluate(context)`:
   - `Answered(a)` → deliver `ApprovalAnswered(∅, a)`.
   - `Escalated(dossier)` → `approver.approve(dossier)`:
     - `Ready(a)` → deliver `ApprovalAnswered(∅, a)`.
     - `Deferred` → the approver has created an approval computation whose
       result type is `Approval`, with the call's routing as its
       continuation; deliver `Escalated(call, id)`. Nothing is
       narrated to the model.

This door never runs a tool. `RunTool(call)` reaches `runTool`: find the
grant, convert, run, deliver `ToolFinished`. This door never consults a
policy or an approver — the answer is already a fact in the phase. Today's
`executeTool` (evaluate-then-maybe-run) and `executeGrantedToolNow` (run,
synchronously, from the delivery) are each replaced by the half they were
doing.

**Ordering: the fact commits before anyone is told.** An approver that
defers has created a computation somebody could answer at once — a test
approver, a webhook, a human already looking at a queue. If that answer were
delivered before `Escalated` had folded, the phase would not yet name
the id, §3's stale row would discard the answer, and the call would then wait
forever on a question already answered. So the `Deferred` arm delivers
`Escalated` through the sink and **waits for that fold to commit
before the dossier reaches the `approvalNotifier`** — the harness's
one-recipient, point-to-point hand-off. Today the notifier fires inside
`ComputationApprover.adjudicate`, before any fold; that moves. A delivery
that still races the fold (the computation existed for a moment before the
notifier ran) is released by the worker rather than acknowledged, so
Continuum re-delivers it after the backoff, by which time the fact is
committed. `Escalated` is therefore the one event whose fold is
awaited by its producer; every other event is fire-and-forget through the
sink.

The tool kind's own deferral — a tool returning `Awaited.Deferred` — is
untouched. A deferred tool's `ToolFinished` arrives through the tool
computation's delivery as it does today; the only change is that the fold
that records it looks the call up in `calls` instead of `pending`.

## 5. Delivery — the lease pays for a message

`DeliveryWorker`'s approval consumer becomes: read the delivery's `Approval`
and routing, fold `ApprovalAnswered(call, id, approval)` into the scope, commit,
return. If the fold produced `RunTool`, it is dispatched after the commit on
the harness executor, the way every effect is dispatched. The lease
covers one `Substrate.batch`. §11.2 and §11.6 are not mitigated; they are
unrepresentable — no consumer of either kind ever runs a tool.

The tool kind's consumer is unchanged: it folds a `ToolFinished`.

## 6. Recovery — one arm, per call

A crash anywhere is answered by the existing re-fire arm (§6.1): a quiet
`AwaitingTools` re-emits `SeekApproval` for each `Pending` call and `RunTool`
for each `Running` call. That is at-least-once with a re-run
on real crash, which is what the lease used to buy and what "run inside the
lease" confused with slowness. A tool that ran before the crash may run again
after it and makes itself idempotent like every other tool (scope design §6).

**Ruled: re-run, not expire.** The alternative — give every approved run a
computation with a deadline and let a crash expire loudly — was considered
and rejected as machinery for a case the re-fire arm already covers; it also
turns a one-day default deadline into a one-day silence for a crashed
five-second tool.

One window is named rather than closed: a crash *after* the approver created
the approval computation and *before* `Escalated` folded leaves the
call `Pending`, and the re-fire re-evaluates it — the policy escalates again,
a second computation is created, and a human may be asked twice. Only the
answer whose id the phase names is honoured (§3, row five); the other is an
orphan, acknowledged and ignored, exactly the §11.3 resolution today. The
dossier can carry a "re-asked after recovery" note so a human who sees two
requests understands why. This is the same window the create-then-index
ordering has now, and it stays because computation ids are opaque
(`computation-ids-stay-opaque`): a deterministic address per call would close
it and was ruled out.

## 7. What retires

- `PolicyDecision`, `Adjudication`, `Decision` — replaced by `Approval` and
  `PolicyOutcome`.
- `DispatchIndex`, `CallAddress.indexKey()`, `ComputationDeferredToolCallPolicy
  .pendingComputation` absorption, and `DeliveryWorker.isCurrentDispatch` — the
  phase names its computations.
- `ToolCallExecutor.executeTool` and `executeGrantedToolNow` — replaced by
  `seekApproval` and `runTool`, each doing one half.
- The two-step `grant.assemble` then `grant.decide` — the policy returns the
  dossier.
- The sharing rule's Continuum half. Two harnesses sharing a type and a
  substrate still write one set of scopes, so they must share the Continuum
  those scopes name — but the failure when they do not is now *loud*: an
  answer arrives for a computation no phase names and is ignored with a WARN,
  rather than draining into a scope that reads `Idle`. The rule is documented
  the same way; its violation stops being silent.

## 8. What this buys, measured

- **Park dwell, per call:** `Escalated` entered → `ApprovalAnswered`
  folded. The number the o11y generation most wanted, with a home.
- **Approval latency, tagged by approver:** ~0 for the policy, seconds for a
  console, hours for a desk — one metric, not a special case.
- **A narratable wait.** `TurnEvent` gains nothing; `AgentObserver` sees the
  phase and can say "awaiting approval of `restart_prod` for 3h12m." The
  console's second-park hang is gone because the console can *see* the second
  park.
- **`nessy-agent` loses a class and a hazard** (`DispatchIndex` and its orphan
  path) and `nessy-api` loses two types.

## 9. Testing

- The reducer matrix in §3, cell by cell, in `PhaseTest` — every stated row,
  every stale row, and the all-finished → `CallModel` transition.
- `HarnessApprovalDemo`, `SharedContinuumTest`, `DurableResumeTest` pass with
  one change: the phase assertion reads `AwaitingTools` with the call
  `Escalated`, then `Idle`. Their outcome is unchanged; their *middle*
  is now observable.
- **A slow approved tool runs once.** An approval-gated `Ready` tool that
  sleeps past a deliberately tiny approval lease completes exactly once and
  the turn finishes — the §11.2 test that could not be written before.
- **Two slow grants do not starve the harness.** With both pool threads busy
  under the old design, the tool-kind deliver pump could not run; the new
  test parks two slow approvals and asserts a third, unrelated deferred tool's
  result is folded while they run.
- **Re-fire per status.** A `Pending` call is re-dispatched, a `Running` call
  is re-run, an `Escalated` call is left alone.
- **Stale answers.** An `ApprovalAnswered` naming an id the phase does not
  hold is ignored, with the scope unchanged.
- **Early answers.** An approver whose computation is answered *immediately*
  on creation (a test approver that approves in the same breath) still
  resolves the call: the request folds before the notifier fires, and a
  delivery that arrives against a `Pending` call is released and re-delivered
  rather than acknowledged and lost. This is the test that distinguishes
  "ordered by construction" from "usually fast enough."
- **The dossier.** An `Escalated` outcome carries the assembled context a
  `requireApproval()` grant used to build in the gate.

## 10. Documentation

`docs/concepts/durable-computation.md` and `docs/guides/harness.md` are
rewritten where they describe parks as an address book, the dispatch index,
and the "one desk answers approvals" wiring; `docs/guides/providers.md`'s
`ApprovalPlayground` walkthrough gains the narrated wait. The CHANGELOG's
Unreleased section records the vocabulary collapse as a breaking change.

## 11. Decisions ruled in conversation (2026-08-25)

1. The lease pays for delivering a message, never for doing the work.
2. Every call is approved; the policy is the first approver, answering now or
   handing over a dossier.
3. One answer type, `Approval {Approved, Denied}`; `PolicyOutcome {Answered,
   Escalated(ApprovalRequest)}`; `Approver` returns `Awaited<Approval>`.
4. Each call has its own lifecycle, folded into the state — per call, not as
   a set.

## 12. Needs sign-off

1. **`CallStatus`** and its four arms inside `AwaitingTools` — the phase
   grammar is the most spec-bound type in the tree.
2. **`Escalated` / `ApprovalAnswered`** as event arms, and
   **`SeekApproval` / `RunTool`** replacing `ExecuteTool` — the event grammar
   was called a "designed ceiling" in §2.4; this raises it by two.
3. **`ToolCallExecutor.seekApproval` and `runTool`** replacing `executeTool`
   and `executeGrantedToolNow`.
4. **Model visibility stays off** (§2) and **crash means re-run** (§6) —
   both ruled here by me from the conversation's direction; both reversible.
5. **`UsagePolicy.escalate()`** replacing `requireApproval()` — a public
   static's rename, so the policy's act and its outcome share one word.

## 13. Rejected

- **A distinct `AwaitingApprovals` phase the scope marches through.** Holds
  allowed calls hostage to siblings still awaiting approval; adds a second arm and a
  second set of transitions for a property that is better derived (§2).
- **Approved runs as tool computations with deadlines** (the earlier
  proposal). Correct but heavier: a completion door on the policy SPI, a
  computation per approved call, and a crash that goes quiet for a day. The
  re-fire arm already does the job (§6).
- **Bounding the inline run with a stopwatch.** Still does the work inside
  the lease; violates decision 1.
- **Deterministic approval addresses** to close the re-ask window (§6).
  Ruled out earlier: computation ids stay opaque.

## 14. Deliberately not done

- No change to the tool kind's deferral path or to `CompletionDesk`.
- No change to what the model sees.
- No metrics or spans — this spec gives them a place to attach; the o11y
  generation attaches them.
- `nessy-intent` and `nessy-tool-mcp` adapt to the vocabulary rename only.
