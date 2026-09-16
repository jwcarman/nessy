# Agent as Scope

An agent is a **recipe** bound to an **id**.

The recipe is an `AgentType`: system prompt, tools, model, context policy.
You compile it once into a `Harness` and keep it for the life of the
process. The id is an `AgentId`, a UUID naming one conversation, one tenant,
one ticket, whatever your domain calls a "who."

```java
harness.observe(agentId, "the porch light came on");
```

There is no handle in between. A handle is a thing that can go stale, and a
row lock already knows where an agent lives.

## Exactly one worker per id

Each `(AgentType, AgentId)` is one row in `nessy_agent_state`, locked with
`SELECT ... FOR UPDATE` for the duration of a fold. That buys the guarantee
a cluster's single-activation scheme buys, **exactly one worker touches an
agent's state at a time**, from a row lock instead of a resident process,
so two callers still cannot corrupt one agent's state and any process that
can reach the database can serve any agent.

The lock is pessimistic on purpose. An optimistic version check would mean
two observations arriving at a busy agent race, one commits and the other
fails, and a lost update here is a lost observation. Waiting is the correct
behaviour, and the wait is bounded because a fold does no I/O of its own.

## The state is small on purpose

```java
sealed interface AgentState<O> {
  record Idle(Seq lastSeq) {}
  record Inferring(Seq lastSeq, TurnId turn, Backlog<O> backlog) {}
  record AwaitingActions(Seq lastSeq, TurnId turn, Seq requestSeq,
                         Backlog<O> backlog, Map<CallId, Outstanding> outstanding) {}
  record Terminated(Seq lastSeq) {}
}
```

Where the agent is, and nothing else. No history: the story lives in its
own table, one row per message, and the fold says what to append by
returning it, so the state stays the size of a phase rather than growing
with every conversation the agent has had.

**Only a working agent has a backlog.** An observation arriving at `Idle`
is acted on at once, so it never queues; one arriving mid-turn has nowhere
to go but the queue. That makes an idle agent with work waiting
unrepresentable rather than merely unlikely.

**`AwaitingActions` names every call it is waiting for.** Each leaves
exactly once, when its outcome is folded. The turn cannot advance while the
map has members and it cannot shrink twice for the same call, which is what
makes a redelivered outcome harmless rather than a second result the
provider will reject. An agent awaiting nothing is not a state; the type
refuses to be built.

## Phases are data, and the fold is a function

The engine is a thin shell around that row. It takes the lock, hands the
state and an input to a pure function, and writes what comes back:

```java
Decision<O> decide(AgentState<O> state, Input input);

sealed interface Decision<O> {
  record Advance(AgentState<O> next, List<HistoryEntry> recorded,
                 Opening<O> opening, List<AgentEffect> effects) {}
  record Ignore() {}
}
```

`Advance` says what the next state is, what to append to the story, which
observation opened a turn, and what work is now owed: call the model, run a
tool, ask an approver. All of it commits in one transaction with the state,
so an effect and the fact it came from land together or not at all.
`Ignore` writes nothing, not even a version bump, because a record showing
something happening when nothing did is worse than no record. A redelivered
outcome is ignored, and that is the whole story of idempotency.

Every rule lives in the function, which has no way to *do* anything: no
clock, no store, no thread. Every effect lives in the shell, which decides
nothing. That split is what lets a three-day parked approval and a crash
mid-model-call be ordinary unit tests.

## Recovery is the common path

There is no "should we re-drive?" branch anywhere, and there is no sweep.
Work an agent owes is a row in `nessy_agent_effect` with a time it becomes
actionable. A process that dies mid-call leaves a row whose watchdog time
passes, at which point the same poll that picks up fresh rows picks it up
again. See [Durable Computation](durable-computation.md).

## Story positions

Every entry in the story has a `Seq`, and a turn's id is the `Seq` of the
observation that opened it. Turn ids are therefore positions, not counts:
turns 1, 3, 5 are consecutive when each took one exchange. Summaries and
tails are stated in turn ids, so "through turn 38" means the same thing
forever, whatever is appended afterwards.

## The type is the key

The agent type is the first column of every row. **Renaming an agent type
orphans its stored state.** That is not a bug to work around; it is what
renaming a type means.

## Where next

- [Durable Computation](durable-computation.md), effects, deadlines and answering from outside
- [Storage](storage.md), the tables
- [The Harness](../guides/harness.md), observing, coalescing and terminating
