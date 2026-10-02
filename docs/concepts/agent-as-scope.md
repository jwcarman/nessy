# Agent as Scope

An agent is a **recipe** bound to an **id**.

The recipe is an `AgentType`: system prompt, tools, model, context policy.
You compile it once into a harness and keep it for the life of the process.
The id is an `AgentId`, a UUID naming one conversation, one tenant, one
ticket, whatever your domain calls a "who."

```java
harness.tell(agentId, "the porch light came on");
```

There is no handle in between. A handle is a thing that can go stale, and a
lock already knows where an agent lives.

## Exactly one worker per id

A turn for `(AgentType, AgentId)` runs under a Postgres advisory lock, taken
for the length of one transaction:

```java
public interface Locks {
  LockKind TURN = new LockKind("nessy.agent.turn");

  <T> T withLock(LockKind kind, AgentType type, AgentId agent, Supplier<T> work);
}
```

Both doors lock under the same `TURN` kind — the direct door's `ask` and
`terminate`, the queued door's `tell`, `terminate` and its own delivery of a
tool or approval outcome — so a turn taken through one door excludes a turn
taken through the other over the same agent. That buys the guarantee a
cluster's single-activation scheme buys, **exactly one worker touches an
agent's state at a time**, from a database lock instead of a resident
process, so two callers still cannot corrupt one agent's state and any
process that can reach the database can serve any agent.

`pg_advisory_xact_lock` is held by the transaction and released by the
database the moment it ends — committed, rolled back, or its connection
simply dropped — so there is no stale holder to fence against and no
time-to-live to tune. It needs no row to be true of: the lock is taken
against a hash of `(kind, agent type, agent id)`, and `withLock` **absorbs
the transaction** — taking the lock, running the work and committing all
happen inside the one transaction it opens, because the writes a turn makes
must be atomic with holding the lock that guards them.

Waiting is the correct behaviour when a caller is standing there with
nothing else to do, and the wait is bounded because a turn does no slow I/O
while it holds the lock — a model call is an effect, dispatched and awaited
outside it. See [Storage](storage.md).

## Where an agent is, and how it is known

```java
sealed interface AgentState {
  record Idle(Seq seq) implements AgentState {}
  record Inferring(Seq seq, TurnId turn) implements AgentState {}
  record AwaitingActions(Seq seq, TurnId turn, Seq requestSeq,
                          Map<CallId, OutstandingAction> outstanding) implements AgentState {}
  record Terminal() implements AgentState {}
}
```

Nothing here is a row. `AgentState` is rebuilt by replaying
`nessy_agent_event` from the start onto `AgentState.idle(...)`, so where an
agent is is a fact reconstituted every time it is needed, not one read off
a snapshot. No history lives on it either: the story is its own table, one
row per event, and a decision says what to append by returning it, so the
state stays the size of a phase rather than growing with every turn the
agent has had.

**`AwaitingActions` names every call it is waiting for.** Each leaves the
map exactly once, when its outcome is folded — approved, denied, succeeded
or failed — which is what makes a redelivered outcome harmless rather than
a second result the provider will reject. The constructor refuses to build
an instance whose map is empty: an agent awaiting nothing is not awaiting,
because nothing would ever arrive to move it on.

**`Terminal` is a dead end reachable from nowhere but `Idle`.** Ending
cannot be delivered mid-turn — a turn already owes an outcome for the work
it started, and abandoning it would leave effects with nobody to deliver
them to — so termination only ever leaves `Idle`, and once there it refuses
every command loudly. The one exception is a redelivered outcome, which
every state answers with nothing rather than an exception, because
at-least-once delivery producing one is normal, not a caller's mistake.

## Phases are data, and a decision is a pure function

The engine is a thin shell around the lock and the story. It replays the
state, hands it and a command to a pure function, and appends what comes
back:

```java
Decision execute(AgentCommand command);

sealed interface Decision {
  List<AgentEvent> events();
  List<AgentEffect> effects();

  record Advance(List<AgentEvent> events, List<AgentEffect> effects) implements Decision {}
  record Ignore() implements Decision {}
}
```

`Advance` says what happened and what work is now owed: call the model, run
a tool, ask an approver. All of it commits in one transaction with the
lock, so an effect and the fact it came from land together or not at all.
`Ignore` writes nothing, not even an event, because a record showing
something happened when nothing did is worse than no record. A redelivered
outcome, a declined command, a second `Terminate`: each answers `Ignore`,
and that is the whole story of idempotency.

Every rule lives in `AgentState.execute`, which has no way to *do*
anything: no clock, no store, no thread, and nothing random. Every effect
lives in the shell around it, which decides nothing. That split is what
lets a three-day parked approval and a crash mid-model-call be ordinary
unit tests.

## Only a working agent has a backlog

A working agent cannot take a second turn's command directly — `Idle` is
the only state a `StartTurn` reaches. What arrives while an agent is busy
has nowhere to go but a backlog, and the backlog is not part of `AgentState`
at all: it belongs to the queued door, one row per waiting item in
`nessy_agent_backlog`, and a `BacklogPolicy<I>` decides what an arrival does
to what is already there — appended, replacing the one thing kept, merged
by key. See [Storage](storage.md) and [Durable Computation](durable-computation.md).

## Recovery is the common path

There is no "should we re-drive?" branch anywhere, and there is no sweep.
Work an agent owes is a row in `nessy_agent_effect` with a time it becomes
actionable. A process that dies mid-call leaves a row whose watchdog time
passes, at which point the same poll that picks up fresh rows picks it up
again. See [Durable Computation](durable-computation.md).

## Story positions

Every event has a `Seq`, and a turn's id is the `Seq` of the input that
opened it — `AgentState.Idle.execute` mints it as `seq.next()` and hands it
back as the `TurnId` that names the turn. Turn ids are therefore positions,
not counts: turns 1, 3, 5 are consecutive when each took one exchange.
Chapters, their summaries and tails are stated in turn ids, so "through turn 38" means the
same thing forever, whatever is appended afterwards.

## The type is the key

The agent type is the first column of every row. **Renaming an agent type
orphans its stored state.** That is not a bug to work around; it is what
renaming a type means.

## Where next

- [Durable Computation](durable-computation.md), effects, deadlines and answering from outside
- [Storage](storage.md), the tables
- [The Harness](../guides/harness.md), observing, coalescing and terminating
