# Durable Computation

A tool can take three days. A person approving one can take a weekend. The
process that started the work will not necessarily be alive when the answer
comes back, and nothing about that should be exceptional.

## Everything owed is a row

Every obligation an agent has, a model call to make, a tool to run, an
approver to ask, is a row in `nessy_agent_effect`, inserted in the **same
transaction** as the fold that decided on it. Nothing outside the agent's
own transaction is ever told about a decision except by a row landing in
this table: it is a transactional outbox, and a crash between the decision
and the work cannot happen because there is no between.

Each harness runs a dispatcher on a schedule, once a second by default,
that claims due rows with `FOR UPDATE SKIP LOCKED` (so any number
of processes may poll at once), marks each one running, and performs it on
its own virtual thread. Writes from the same process nudge the dispatcher at
once, so the poll only bounds how late it finds retries coming due, timeouts
and rows another process wrote. At most `maxInFlight` rows, four by default,
are performed at once per harness, and a permit is taken *before* a row is claimed, so nothing is
ever marked running while it waits for a thread.

## One column, two meanings

A row's `actionable_at` is when it is next due, and what "due" means
depends on its status:

| `status` | what `actionable_at` means |
|---|---|
| `PENDING` | when this may be attempted: now, or after a retry's backoff |
| `RUNNING` | the attempt's watchdog: when a worker that never reported back is treated as dead |
| `RUNNING`, with `parked_at` set | the row's deadline, and not before |

A running row can be marked parked: `Effects.park` sets `parked_at` and moves
`actionable_at` to the row's deadline. A parked row is waiting for an answer
from outside, not working, so nothing takes it again before its deadline, even
when the clock of the process that claimed it ran behind the one that wrote
the row. It stays `RUNNING`: a reply that arrives while it waits still finds
it, and completing it deletes it like any other. `park` is fenced on the
attempt's status and count, like `complete` and `reschedule`, and returns
false when the row was settled or another attempt holds it.

There is no reaper. A row whose `actionable_at` has passed is simply
eligible again, picked up by the same query that would attempt a fresh
one. Recovery is not a second mechanism reconciling a stalled row against a
healthy one; it is the absence of any exclusion.

A row is marked running *before* it is performed, and that mark commits on
its own. So a crash mid-call leaves a row whose watchdog will pass, not one
nothing will ever pick up.

## Deadlines and retries

Two durations travel with every row, frozen when it is written so that a
setting changed later reaches new work only:

- **`timeout_millis`**: how long one attempt gets once it starts. A tool
  call's default is 30 seconds, an approver's 10 minutes, a model call's 5
  minutes; each binding can say otherwise.
- **`deadline`**: when the work stops being worth doing at all, measured
  from when the row was written, so time spent queued counts as time the
  agent spent waiting. Retries spend one budget rather than restarting it.

When an attempt fails, the binding's `RetryPolicy` decides: `Never`, a
`FixedDelay`, or `Exponential` backoff with jitter. A backoff that would
land past the deadline is not a later retry; it is a give-up. A row that
comes due at its deadline is claimed to be given up on, not filtered out,
because a row nobody claims is a row nobody retires and its agent waits
forever.

**`Never` is the default, for every kind of work.** Tools, approvers and
model calls all retry only when an application says to. Retrying spends
tokens or repeats a side effect, and which of those is acceptable is not
something an engine can assume.

**What counts as a failure worth repeating depends on what failed.** A tool
or an approver that throws has told you nothing, so the policy decides. A
model call has two paths. If it throws, the engine records the failure as
`Failure.Unknown` and the policy decides, as it does for a tool. If its
adapter catches its vendor's exception and classifies what went wrong, the
adapter returns a failure as a value, and `Failure.Transient` and
`Failure.Unknown` reach the policy. `Transient` says the call might work next
time. `Unknown` says nobody found out whether the call happened; every adapter
reports a dropped connection this way. A model call that runs twice changes
nothing but the bill, so whether to try again is the policy's question.
`Permanent` and `Rejected` stay terminal. `Permanent` means the identical
request fails identically. `Rejected` names content that will fail every time
it is sent, so the answer is to quarantine it rather than send it again. Each
retried model call is recorded as an `InferenceAttempted`; see
[Events](events.md). Narration is not durable: a call that fails partway
through its answer and is tried again narrates the new attempt from the
start, so a listener can see the first part twice.

Retries belong to the queued door. A `DirectHarness` retries nothing: not a
tool, not an approver, not a model call. A retry policy set on a binding is
stored and read back, but a direct harness never honours it. The caller is
standing right there, and asking again is theirs to decide.

Giving up is not silence. Beside every effect row sits a second blob,
`failure_payload`, written at emit time: what to tell the agent if this
work can never be done. That is what reaches the agent when a deadline
passes or a row cannot even be decoded, and it is kept separate so that a
payload that will not decode does not take the handling of that failure
down with it. For a tool call it is a `ToolFailed` outcome naming the call;
the model is told the call failed and the turn carries on.

## Deferring

Deferral works only on the queued door. On a `DirectHarness`, a tool or
approver that returns `Awaited.deferred()` does not park anything: the call
becomes a failed call, with the message "the effect was deferred, and
nothing here can wait for it". A caller at the direct door is already
waiting and has nowhere for a late answer to arrive.

On the queued door, a tool or an approver that returns `Awaited.deferred()`
has parked the call, and the deferral is recorded when it happens. One locked
step has the fold write the event, `ApprovalDeferred` or `ToolDeferred`, with
the moment the request or the call stands until, and marks the effect row
parked. Both are in one transaction, so a marked row always has a deferral in
the story. If the row has moved on by the time the deferral is recorded (another
attempt re-claimed it), the deferral stands and no row is marked. The event is told as
the narration `ApprovalDeferred` or `CallDeferred` once that step commits. See
[Events](events.md) and [Narration](../guides/narration.md).

An approval's deferral also holds the facts the approver was shown, and is recorded whether or
not there were any.

The row stays running and is due at its deadline, and nothing takes it before
then. The agent moves on to whatever else its turn is waiting for. Nothing holds
a thread, and the approver is not asked again while the request stands. The fold
deliberately cannot tell a tool that takes three days from one that takes 200
milliseconds and should not learn; the story records only that the call is
waiting, and until when.

The step writes nothing when the call has already moved on. If the answer
reaches the engine before the deferral is recorded, the answer settles the call
and its row, and no deferral is written. If the deferral cannot be recorded at
all, the failure is logged and the call is unaffected: the row stays as it was
claimed, and an answer or the deadline still settles it.

If the term passes with no answer, the stored failure reaches the agent and
the turn carries on with a failed call. Whether a timeout should be a
denial, an error or a retry is your policy, and it belongs in the approver
or the tool where it is testable.

## Answers go to an address, not an object

A deferring tool must hand the call's address to whatever will answer: the agent type, the agent
id and the idempotency key, all on the request it was handed. Waiting tool calls are only counted
in a status, never listed. An approver may do the same or keep nothing, because the approvals
waiting on a person are read from `AgentWork`. Whoever has the three values, a webhook, a person
clicking Approve, answers through `Replies`:

```java
replies.complete(agentType, agentId, key, ToolResult.ok(new Block.Text("the vendor shipped it")));
replies.approve(agentType, agentId, key, ApprovalResult.approved());
```

No process needs to still be waiting: an answer arriving is what takes the agent's lock and folds
the outcome in, whichever process happens to receive it. Answering returns a `ReplyOutcome`:
`Applied` when the agent took the answer, or `Ignored` when nothing changed, whether the call was
already settled, had expired, was answered the wrong way, or is not known here. An answer that
arrives at or after the call's deadline is ignored, even if the engine has not yet recorded the
expiry. Nessy does not
check who is answering; guard the endpoint that calls `Replies`. See
[Authorization](authorization.md#answering-a-waiting-call).

What is waiting is read, not kept. A call is waiting now when its effect row
is parked, still running, and not yet at its deadline. An application reads
the agent's activity, and the approval requests waiting on a person, through
`AgentWork`; nothing is stored for it beyond the rows and the story. See
[The Harness](../guides/harness.md#what-is-waiting-and-answering-it).

## What this costs

Tool execution is at-least-once. A tool that was running when its process
died may have finished its work, and nothing recorded that it had; a
"started" marker would only move the ambiguity. A call's `idempotencyKey()`
is the same on every run of it, so a tool that cares can deduplicate on it.
A re-driven turn may call the model again. Both are stated rather
than hidden, because a framework that pretended otherwise would be lying
about a distributed system.

## See also

- [Storage](storage.md), the tables
- [Authorization](authorization.md), approvers, grants and answering a waiting call
- [Tools](tools.md), `Awaited`, and how a tool defers
- [The Harness](../guides/harness.md#what-is-waiting-and-answering-it), reading what is waiting
