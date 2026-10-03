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
| `RUNNING` | the attempt's watchdog: when a worker that never reported back is treated as dead, or when a deferred call's term is up |

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
adapter returns a failure as a value, and only `Failure.Transient`, a value
saying the call might work next time, reaches the policy. `Permanent`,
`Rejected` and a *returned* `Unknown` stay terminal. `Permanent` means the
identical request fails identically. `Rejected` names content that will fail
every time it is sent, so the answer is to quarantine it rather than send it
again. A returned `Unknown` means nobody found out whether the call
happened, and repeating work that may already have run is not a chance this
engine takes on its own. Each retried model call is recorded as an
`InferenceAttempted`; see [Events](events.md).

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
has parked the call. The row stays running, its `actionable_at` set to the binding's
timeout, and the agent moves on to whatever else its turn is waiting for.
Nothing holds a thread. The fold deliberately cannot tell a tool that takes
three days from one that takes 200 milliseconds and should not learn; the
one place "awaiting a person" appears is narration, as `ApprovalDeferred`
or `CallDeferred` with the moment the question expires. See
[Narration](../guides/narration.md).

If the term passes with no answer, the stored failure reaches the agent and
the turn carries on with a failed call. Whether a timeout should be a
denial, an error or a retry is your policy, and it belongs in the approver
or the tool where it is testable.

## Answers go to an address, not an object

A deferring tool or approver hands out the call's `ReplyToken`. Whoever
holds it, a webhook, a person clicking Approve, answers through `Replies`:

```java
replies.complete(token, ToolResult.ok(new Block.Text("the vendor shipped it")));
replies.approve(token, ApprovalResult.approved());
```

The token names logical coordinates, agent type, agent id, request and
call, sealed with AES-GCM so the holder can neither read nor forge them. No
process needs to still be waiting: an answer arriving is what takes the
agent's lock and folds the outcome in, whichever process happens to receive
it. Answering returns a `ReplyOutcome`: `Settled`, `NotAwaiting` for a call
already settled or expired, or `Unreadable` for a token this engine did not
issue. An HTTP handler can report each one honestly.

## What this costs

Tool execution is at-least-once. A tool that was running when its process
died may have finished its work, and nothing recorded that it had; a
"started" marker would only move the ambiguity. The turn id and the call id
together are stable across a re-drive, so a tool that cares can deduplicate
on them. A re-driven turn may call the model again. Both are stated rather
than hidden, because a framework that pretended otherwise would be lying
about a distributed system.

## See also

- [Storage](storage.md), the tables
- [Authorization](authorization.md), approvers, grants and reply tokens
- [Tools](tools.md), `Awaited`, and how a tool defers
