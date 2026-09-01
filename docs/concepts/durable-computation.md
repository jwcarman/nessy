# Durable Computation

A tool can take three days. A person approving one can take a weekend. The
process that started the work will not necessarily be alive when the answer
comes back, and nothing about that should be exceptional.

## One actor, one document

Each agent is one sharded, durable actor. It works one turn at a time, and
what it persists is small:

```java
record AgentState(String turnId, Phase phase, String observation, Usage usage) {}
```

`Phase` is `Idle`, `CallingModel`, or `WorkingTools` — and `WorkingTools`
carries what each call is waiting on:

```java
sealed interface CallState {
  record Approving(String toolName) {}   // the approver was asked
  record Running(String toolName) {}     // approved, the tool is running
  record Parked() {}                     // waiting on the world
  record Completed() {}                  // its result is in claims
}
```

**These four arms exist because recovery needs four answers.** That is the
whole reason the type is not a boolean.

## Recovery is not a mode

There is no "should we re-drive?" decision anywhere. Pekko reads the
document before any command runs, and the agent then feeds itself a
`Recovered` input on **every** activation — so the rare path is the common
path, exercised constantly rather than only after a crash.

| State | On recovery | Why |
|---|---|---|
| `Approving` | ask again | asking is idempotent |
| `Running` | run again | nobody else will answer |
| `Parked` | **leave it alone** | someone holds a reply token and an alarm is armed |
| `Completed` | nothing | the result is claimed |

The `Parked` row is the one that earns the design. Re-asking a parked
approval mints a *second* reply token and invalidates the one already
sitting in somebody's inbox. An earlier engine re-ran any call without a
stored result, parked ones included, and it went unnoticed for exactly as
long as nothing ordinary ran that path.

## Tool execution is at-least-once

A `Running` call whose process died may have finished its work; nothing
recorded that it had. No marker fixes this — a "started" marker only moves
the ambiguity. So it is a contract rather than an accident, and the
engine's mitigation is to hand the tool a stable key it can use for itself.

## Deadlines are rows, not timers

An in-memory timer dies with its actor, which meant an approval parked on a
person for three days needed a process to stay up for three days. A row
does not:

```sql
CREATE TABLE nessy_reminder (
  reminder_key TEXT                     NOT NULL,
  expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
  payload      BYTEA                    NOT NULL,
  PRIMARY KEY (reminder_key)
);
```

A sweep reads from the front of the index and stops at the first row not
yet due, so its cost is the number of *expired* reminders rather than the
number outstanding. When one fires, the agent is told `DeadlinePassed` —
distinct from `ToolCompleted` on purpose. The sweep knows time ran out and
does not get to decide what that means; whether a timeout is a denial, an
error or a retry is policy, and policy belongs where it is testable.

`Parked` deliberately carries no instant. The deadline is the row; a second
copy on the document could only drift from it.

## Answers go to an address, not an object

> Work handed to the blocking executor has its answer addressed to a
> **logical** address, never to `self`.

The executor outlives actors; a reference does not. An `EntityRef` is
resolved by the shard at delivery, so if the agent was unloaded while the
model was thinking, the shard creates one and delivers there — **the answer
arriving is itself the knock that revives the agent.**

This is not a refinement. An earlier engine piped the model's answer back
to a specific incarnation; an agent unloaded in that window received it
into a dead reference, and nothing was left to finish the turn. The fix
that shipped was a message the dying actor posted to itself. The fix that
lasted was deleting the need for one.

## Answering from outside

A tool that defers hands out a `ReplyToken`. Whoever holds it — a webhook,
a person clicking Approve — answers through `Replies`:

```java
replies.answer(token, ToolResult.ok("the vendor said yes"));
replies.approve(token, ApprovalResult.approved());
```

The token names logical coordinates: agent type, agent id, turn, call. The
actors that were waiting need not still exist.

**The result is claimed before the agent is told.** That is the same rule
an in-process tool follows, which is why the agent has one message for both:
`ToolCompleted` is identical whether a future finished in two milliseconds
or a webhook answered three days later. It has no reason to care, and an
earlier engine that gave the two paths different names ended up relaying
everything down a hierarchy to keep them apart.

Answering returns a stage that completes when the answer has actually
reached the call, so an HTTP handler can wait before returning 200. An
answer arriving too late, for a call already settled, is reported honestly
rather than dropped.

## What this costs

Tool execution is at-least-once, and a re-driven turn may call the model
again. Both are stated rather than hidden, because a framework that
pretended otherwise would be lying about a distributed system.

## See also

- [Storage](storage.md) — the tables, and why there is no abstraction over them
- [Authorization](authorization.md) — approvers, grants, and reply tokens
- [Tools](tools.md) — `Awaited`, and how a tool defers
