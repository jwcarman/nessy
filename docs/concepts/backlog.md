# Backlog

An agent handles one turn at a time. If a `tell` arrives while a turn is
already running, that input cannot start a second one — there is only one
of the agent — so it has to go somewhere until the first turn ends. That
somewhere is the backlog.

This is entirely a [queued-door](../guides/harness.md) concern. The direct
door's bargain is different: a caller using `DirectHarness.ask` is standing
there, on the calling thread, waiting for the turn it started. A second
caller arriving mid-turn is not made to wait its turn in a queue — it is
told `AskOutcome.Busy` and sent away to decide for itself whether to retry.
There is nothing to coalesce, because there is no queue: `DefaultDirectHarness`
holds none of the backlog machinery — no coalescing, no claims, no leases,
no deferral. `QueuedHarness.tell`, by contrast, always accepts. Something
has to hold what it accepted, and that something is the backlog.

## Where it lives

A backlog is not part of an agent's event-sourced state. The state a turn
folds from — `AgentState`, rebuilt by replaying `AgentEvent`s since the last
turn started — knows nothing about what is waiting; it only knows what the
agent has already done. The backlog is a separate store, reached through
its own seam on `QueuedBackend`:

```java
public interface QueuedBackend {
  AgentEvents events();
  Payloads payloads();
  Locks locks();
  Agents agents();
  Effects effects();
  Chapters chapters();
  Leases leases();
  <I> Backlogs<I> backlogs(TypeRef<I> inputType);
}
```

`Backlogs<I>` hands back one agent's `Backlog<I>` on demand — the same
shape as `Payloads.forAgent`, for the same reason: the harness already
knows whose backlog it wants. On the JDBC backend this is `JdbcBacklog`,
rows in `nessy_agent_backlog`, keyed by `(agent_type, agent_id, ordinal)`
and ordered by that ordinal. It is a queue of `BacklogItem<I>` values, each
holding the caller's own input, unrendered, and the instant it arrived —
not a clock read while queuing, but the arriving item's own timestamp, so
anything that reasons about age (a policy expiring stale entries, say) is a
pure function of what it was handed rather than of when it happens to run.

The schema comment describes this table as text held outside
`nessy_payload`, whole and not bounded, which is why a harness never
builds its own codec for it: `QueuedBackend.backlogs` takes a `TypeRef`,
not a `Codec`, so the backend can compose Jackson with the application's
storage transform itself, and a caller can't bypass encryption for exactly
the table that would need it most.

## When it is consumed

At the turn boundary — the moment an agent goes idle. `DefaultQueuedHarness`
enforces this with one rule stated in its own code: an agent is never left
idle with work waiting. Three places call `driveIfIdle`, and all three run
it inside the same locked transaction that just made the agent idle:

- `tell`, after the policy has coalesced the new arrival into the backlog —
  in case the agent was already idle and this input is the one to start a
  turn on.
- `terminate`, after sealing the agent — in case an ending needs to be
  delivered as the next unit of work.
- `deliverOutcome`, after folding an effect's outcome — in case that fold
  is what just made the agent idle.

`driveIfIdle` reconstitutes the agent, and if it isn't `AgentState.Idle`,
does nothing. If it is, it calls `Backlog.take()`, which returns one of
three `Pull` values: `Item` (something to work on, removed as it is read —
one statement, so nothing can observe an entry both waiting and taken),
`Pill` (the agent has ended and there is nothing left to drain — offered
forever, so a late stray input can never undo a termination), or `Empty`
(nothing waiting; the agent goes quiet). An `Item` becomes an
`AgentCommand.StartTurn`; a `Pill` becomes an `AgentCommand.Terminate`.
Either way, taking and starting the next turn happen inside the transaction
that emptied the backlog, which is what keeps "busy, or backlog is empty,
or ended" the only three states an agent is ever caught in.

## `BacklogPolicy` and `Coalescing`

`BacklogPolicy<I>` is what a `tell` runs to decide what an arrival does to
what is already waiting. It says what to do rather than handing back a
list:

```java
@FunctionalInterface
public interface BacklogPolicy<I> {
  void coalesce(Coalescing<I> backlog, BacklogItem<I> incoming);
}
```

`Coalescing<I>` is the handle a policy is given — small on purpose. It can
`append` (behind everything waiting), `prepend` (ahead of it, for an
interrupt or a cancellation that would make what is queued behind it
wasted work), `replaceAll` (keep only this one), ask `size()`, `dropOldest`,
or read and rewrite the whole thing with `all()` and `rewrite`. Only `all()`
pays for decoding the backlog; everything else is a count, an insert, a
delete.

The choice this type exists to make concrete: some inputs are increments,
where every one deserves its own answer — two questions a person typed are
two things worth answering, so losing one is a bug. Others are snapshots,
where an older reading is worthless the instant a newer one exists —
forty queued sensor readings mean the agent reasons about a world that no
longer holds, so keeping all forty is the bug. Only the application knows
which kind of input it has, so `BacklogPolicy` is a customization point,
not an engine decision — set on the harness with `QueuedHarnessConfig.backlogPolicy(...)`.

The real factory methods, and what each does:

| Method | What it does to a pending backlog |
|---|---|
| `keepAll()` | Appends. Every input matters — the right default for anything a person said. |
| `keepLatest()` | Replaces the whole backlog with the arrival, so it never holds more than one — right for a clock tick, where catching up should mean one round, not every one missed. |
| `bounded(max)` | Appends, first dropping the oldest entries until there's room for `max`. The only one that bounds growth; `keepAll()` grows for as long as an agent is behind. |
| `replaceBy(key)` | Keeps the newest input per key, in the position the first one held. Reads the whole backlog, since a key isn't something the store is ordered by. |
| `dropRepeats(key)` | Keeps the first input per key and discards later arrivals with the same key — for a signal that means "go look," where a second is redundant until the first has been acted on. |
| `mergeBy(key, merge)` | Combines an arrival with the pending entry sharing its key, in place. |
| `.expiring(ttl)` (default method) | This policy, having first dropped anything older than `ttl`, measured against the arriving input's own time rather than a clock read. |
| `.capped(max)` (default method) | This policy, bounded to `max` afterward by dropping the oldest — turns a policy that can grow unbounded into a bounded loss. |

`QueuedHarnessConfig.backlogPolicy` defaults to `BacklogPolicy.keepAll()`.
Left alone, a queued harness treats everything that arrives while an agent
is busy as worth keeping and worth answering, in order.

## `BacklogItem`

`BacklogItem<I>` is the record actually held in the queue: `input` (the
caller's own value, unrendered — turning it into content a model reads is a
renderer's job, and happens when a turn opens, not when the input arrives)
and `arrivedAt` (an `Instant`, the item's own arrival time). It surfaces
directly in the `BacklogPolicy` and `Coalescing` signatures above, so an
application writing a custom policy — anything beyond the stock factory
methods — reads and constructs these values itself.

## Where next

- [The Harness](../guides/harness.md), the two doors and their different
  bargains
- [Durable Computation](durable-computation.md), the effects a turn owes
  once it starts
- [Storage](storage.md), the tables a queued harness writes through
