# Durable Computation

A tool can take three days. A person approving one can take a weekend. The
process that started the work will not necessarily be alive when the answer
comes back, and nothing about that should be exceptional.

## One row, one document

Each agent is one row in `nessy_agent`, serialized by a PostgreSQL row lock
(`SELECT ... FOR UPDATE`) rather than by an actor's mailbox. There is no
process that owns an agent — any node that can lock the row can read it,
decide, and write it back. It works one turn at a time, and what it
persists is small:

```java
record AgentState(TurnId turnId, Phase phase, String observation, Usage usage) {}
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

There is no "should we re-drive?" decision anywhere. The engine reads the row
before any command runs, and a stall sweep periodically feeds a `Recovered`
input to any agent left mid-turn — so the rare path (a node that vanished
mid-turn) is exercised by the same fold every other input goes through,
rather than a special case bolted on beside it.

| State | On recovery | Why |
|---|---|---|
| `Approving` | ask again | asking is idempotent |
| `Running` | run again | nobody else will answer |
| `Parked` | **leave it alone** | someone holds a reply token, and the row's own deadline governs it |
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
person for three days needed a process to stay up for three days. There is
no timer and no reminder table — the deadline lives on the same row that
already tracks the work: `nessy_effect`.

Every obligation an agent owes — take the next backlog item, call the
model, run a tool, ask an approver — is a row in `nessy_effect`, inserted
in the SAME transaction as the decision that produced it, so an effect and
the fact it came from commit together or not at all. That row is a
transactional outbox: nothing outside the agent's own transaction is ever
told about a decision except by a row landing in this table.

`nessy_effect.actionable_at` carries three different meanings, decided by
the row's `status`:

| `status` | what `actionable_at` means |
|---|---|
| `PENDING` | retry backoff — when this obligation may be attempted next |
| `RUNNING` | the attempt's own watchdog — when a worker that never reported back should be treated as dead |
| `PARKED` | a deferring tool or approver's own term — a person's or a webhook's deadline |

A single poller query finds work the same way regardless of which meaning
applies: `actionable_at <= now`, `ORDER BY actionable_at, ordinal`. **There
is no reaper.** Recovery is not a second mechanism reconciling a stalled
row against a healthy one — it is the absence of any exclusion. A row
whose `actionable_at` has passed is simply eligible again, picked up by the
same query that would attempt a fresh row, `FOR UPDATE SKIP LOCKED` so any
number of nodes may poll at once without contending.

When a parked row's term lapses, the agent is told `DeadlinePassed` —
distinct from `ToolCompleted` on purpose. The poller knows time ran out and
does not get to decide what that means; whether a timeout is a denial, an
error or a retry is policy, and policy belongs where it is testable.

`Parked` deliberately carries no instant of its own. The deadline is the
effect row's `actionable_at`; a second copy on the agent's own document
could only drift from it.

## Answers go to an address, not an object

> Work handed to the blocking executor has its answer addressed to an
> **agent id**, never to a reference or a handle.

The executor outlives any one process; an in-memory reference does not.
Work reports back by calling `dispatcher.dispatch(agentId, input, ...)` —
an id, which any node can resolve by locking that agent's row, never a
pointer to the specific process that started the work. If the node that
started a model call is gone by the time it answers, whichever node
receives the dispatch simply locks the row and folds the answer in —
**the answer arriving is itself what moves the agent forward**, with no
handoff and nothing to revive.

This is not a refinement. An earlier engine piped the model's answer back
to a specific actor incarnation; an agent unloaded in that window received
it into a dead reference, and nothing was left to finish the turn. Holding
no reference at all — only the id — is what removed the class of bug
rather than working around one instance of it.

## Answering from outside

A tool that defers hands out a `ReplyToken`. Whoever holds it — a webhook,
a person clicking Approve — answers through `Replies`:

```java
replies.answer(token, ToolResult.ok("the vendor said yes"));
replies.approve(token, ApprovalResult.approved());
```

The token names logical coordinates: agent type, agent id, turn, call. No
process needs to still be waiting — an answer arriving is what locks the
row and drives the agent forward, whichever node happens to receive it.

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
