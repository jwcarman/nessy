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

Each harness runs a dispatcher on a schedule, every 250 milliseconds by
default, that claims due rows with `FOR UPDATE SKIP LOCKED` (so any number
of processes may poll at once), marks each one running, and performs it on
its own virtual thread. At most `maxInFlight` rows are performed at once
per harness, and a permit is taken *before* a row is claimed, so nothing is
ever marked running while it waits for a thread.

## One column, three meanings

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

Giving up is not silence. Beside every effect row sits a second blob,
`failure_payload`, written at emit time: what to tell the agent if this
work can never be done. That is what reaches the agent when a deadline
passes or a row cannot even be decoded, and it is kept separate so that a
payload that will not decode does not take the handling of that failure
down with it. For a tool call it is a `ToolFailed` outcome naming the call;
the model is told the call failed and the turn carries on.

## Deferring

A tool or an approver that returns `Awaited.deferred()` has parked the
call. The row stays running, its `actionable_at` set to the binding's
timeout, and the agent moves on to whatever else its turn is waiting for.
Nothing holds a thread. The fold deliberately cannot tell a tool that takes
three days from one that takes 200 milliseconds and should not learn; the
one place "awaiting a person" appears is the event stream, as
`ApprovalDeferred` or `CallDeferred` with the moment the question expires.

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
process needs to still be waiting: an answer arriving is what locks the
agent's row and folds the outcome in, whichever process happens to receive
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
