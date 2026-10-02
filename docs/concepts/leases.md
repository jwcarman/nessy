# Leases

A lease is how background work runs once when several processes could all
do it. It is one interface with one method, and it exists because of one
fact about deployments: an agent's events are heard by every instance of
the application, and some of what those events trigger, a chapter summary,
a sweep, a report, is work that should happen exactly once, not once per instance.

## The problem it solves

The engine's own work never needs a lease. A model call, a tool call, an
approval: each is an effect, a row one process claims with its deadline, and
the engine guarantees it is performed. That is work the agent is *owed*.

Background work is different. When a turn ends, the chapter keeper asks
whether the chapter policy says a chapter is due, and whether any closed
chapter still has no summary. Three instances hear the same turn end and all
three ask the same question and get the same answer. Without a lease, three
summaries are written of the same turns and three model calls are paid for.
With one, the first asker does the work and the other two find nothing left
to do.

The distinction is the design: **an effect is work somebody is owed; a lease
is work anybody may do, once.** Nothing waits on a lease, nothing queues
behind one, and a caller refused a lease simply moves on, because the same
question will be asked again at the next event, and by then the work is
either done or the lease has expired.

## The contract

```java
public interface Leases {
  <T> Attempt<T> tryWithLease(
      LeaseKind kind, AgentType type, AgentId agent, Duration ttl, Supplier<T> work);

  default Attempt<Void> tryWithLease(
      LeaseKind kind, AgentType type, AgentId agent, Duration ttl, Runnable work) { ... }
}
```

A lease is keyed on three things, not two: `kind` names the activity (the
chapter keeper's `nessy.chapters` and a nightly report for the same agent
are two leases, not one), and `type` plus `agent` name whose work it is. `ttl` is how long the
caller believes it will hold the lease before another process may assume it
died. `work` runs if the lease was taken; the return value is an `Attempt<T>`
rather than a boolean, because "nobody ran it" and "it ran and returned
nothing" are different outcomes a caller reasonably treats differently:

```java
public sealed interface Attempt<T> {
  record Ran<T>(T result) implements Attempt<T> {}
  record Ignored<T>() implements Attempt<T> {}
}
```

```java
LeaseKind kind = new LeaseKind("nightly-report");
Attempt<Void> attempt =
    leases.tryWithLease(kind, agentType, agentId, Duration.ofMinutes(5),
        () -> sendReport(agentId));
```

## How the JDBC implementation keeps its promise

`JdbcLeases`, in `nessy-backend-jdbc`, rests on one statement against
`nessy_lease`, a row per `(kind, agent_type, agent_id)`:

```sql
INSERT INTO nessy_lease (kind, agent_type, agent_id, holder, expires_at)
VALUES (?, ?, ?, ?, now() + make_interval(secs => ?))
    ON CONFLICT (kind, agent_type, agent_id) DO UPDATE
       SET holder = EXCLUDED.holder,
           expires_at = EXCLUDED.expires_at,
           takeovers = nessy_lease.takeovers + 1
     WHERE nessy_lease.expires_at < now()
RETURNING holder, takeovers
```

Three things fall out of it:

- **No read-then-write.** A caller never looks at the table and then decides.
  The insert either lands, takes over an expired row, or is refused, and
  PostgreSQL's row lock serialises two callers arriving together, so exactly
  one sees its own holder come back.
- **The database's clock.** Expiry is written and compared with the server's
  `now()`, so instances with drifting clocks cannot hold a lease forever or
  steal one early.
- **Only the holder releases.** Each attempt mints a fresh holder id, and the
  release is `DELETE ... WHERE kind = ? AND agent_type = ? AND agent_id = ?
  AND holder = ?`. A slow holder whose lease was taken over deletes nothing,
  because the row now carries someone else's id.

Takeover is real and intended. A process that dies mid-summary leaves a row
whose `expires_at` passes; the next process to ask takes it over in the same
statement it would have used for a free lease, and the work is done after
all, late rather than never. `takeovers` counts how many times one row has
been taken from a holder that never released it, which is the fact a log
line reports when it happens.

## What it does not do

A lease is taken once, for the TTL, and is not renewed while the work runs.
Work that outlives its TTL is no longer protected, and a second process may
start the same job. So two rules for anything that runs under one:

- **Choose the TTL for the slow case.** A summary by a hosted model takes
  seconds; the same summary by a local thinking model over a long chapter
  takes minutes. Size the TTL to the slowest model the work will run on. For
  chapters it is `chapterLeaseTtl(Duration)` on the context config, two
  minutes by default, and it applies to each step the keeper takes (the cut,
  and each summary) rather than to the pass as a whole.
- **Make the write idempotent anyway.** A chapter's summary is written with
  `WHERE summary IS NULL`, and a chapter is stored only if the closed
  chapters still end where the caller said they did. The lease makes
  duplicate work rare; the guarded write makes it harmless. The lease is an
  optimisation over correctness that is already there, which is the right
  way round.

There is no queue, no fairness, and no waiting. A caller that finds the
lease held gets `Attempt.Ignored` and nothing else, which is exactly what
opportunistic work wants and exactly wrong for work that is owed. For that,
write an effect. See [Durable Computation](durable-computation.md).

## Where it is used

- The chapter keeper takes a lease per agent, under the kind
  `nessy.chapters`, to cut the history into chapters, and again for each
  summary it writes. A lease held by someone else is an immediate refusal,
  and the keeper does nothing: whoever holds it is doing the same work, and
  looks at the history again when it lets go, so a chapter that became due
  while it held the lease is still closed and summarised. See
  [Context](context.md#chapters).
- Your own listeners, whenever they react to an agent event with work that
  costs something and must not be done twice: a nightly report, a reflection
  pass, an export.

The engine reaches leases through the backend it was given, as
`backend.leases()`, beside `backend.chapters()`, so a backend whose stores
are durable never pairs them with leases that only exclude work in one
process.

In a Boot application a `Leases` bean is contributed by whichever backend
auto-configuration fires — `JdbcBackendAutoConfiguration` (an
`INSERT ... ON CONFLICT` against `nessy_lease`) or
`InMemoryBackendAutoConfiguration` (excluding only work in that one JVM) —
alongside the `DirectBackend` and `QueuedBackend` beans it also contributes.
There is no separate module or property to add; see
[Spring Boot](../guides/spring-boot.md#leases).

## Where next

- [Durable Computation](durable-computation.md), the effects a lease is not
- [Context](context.md), the chapter keeper that runs under one
- [Storage](storage.md), the table
