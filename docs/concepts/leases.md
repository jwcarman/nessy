# Leases

A lease is how background work runs once when several processes could all
do it. It is the smallest module in the engine, one interface and one table,
and it exists because of one fact about deployments: an agent's events are
heard by every instance of the application, and some of what those events
trigger, a summary, a sweep, a report, is work that should happen exactly
once, not once per instance.

## The problem it solves

The engine's own work never needs a lease. A model call, a tool call, an
approval: each is an [effect](durable-computation.md), a row one process
claims with its deadline, and the engine guarantees it is performed. That is
work the agent is *owed*.

Background work is different. When a turn ends, the head summariser asks
whether the story has outgrown the tail; the episode summariser asks whether
any closed episode still has no summary. Three instances hear the same turn
end and all three ask the same question and get the same answer. Without a
lease, three summaries are written of the same turns, three model calls are
paid for, and the last writer wins. With one, the first asker does the work
and the other two find nothing left to do.

The distinction is the design: **an effect is work somebody is owed; a lease
is work anybody may do, once.** Nothing waits on a lease, nothing queues
behind one, and a caller refused a lease simply moves on, because the same
question will be asked again at the next event, and by then the work is
either done or the lease has expired.

## The contract

```java
public interface Leases {
  boolean tryRun(String kind, String key, Duration ttl, Runnable work);
}
```

`kind` names the job, `key` names its subject, and together they are the
lease: `summary` for agent `2f9c…`, `episode` for the same agent, and the two
do not collide. `ttl` is how long the caller may hold it before another
process may assume it died. `work` runs if the lease was taken, and the
return value says whether it was. That is the whole surface.

```java
leases.tryRun("episode", agentId.value().toString(), Duration.ofMinutes(5),
    () -> summarizeAll(agentId));
```

## How the JDBC implementation keeps its promise

`JdbcLeases` rests on one statement against `nessy_lease`, a row per
`(kind, key)`:

```sql
INSERT INTO nessy_lease (kind, key, holder, expires_at)
VALUES (?, ?, ?, now() + make_interval(secs => ?))
    ON CONFLICT (kind, key) DO UPDATE
       SET holder = EXCLUDED.holder, expires_at = EXCLUDED.expires_at
     WHERE nessy_lease.expires_at < now()
RETURNING holder
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
  release is `DELETE … WHERE holder = ?`. A slow holder whose lease was taken
  over deletes nothing, because the row now carries someone else's id.

Takeover is real and intended. A process that dies mid-summary leaves a row
whose `expires_at` passes; the next process to ask takes it over in the same
statement it would have used for a free lease, and the work is done after
all, late rather than never.

## What it does not do

A lease is taken once, for the TTL, and is not renewed while the work runs.
Work that outlives its TTL is no longer protected, and a second process may
start the same job. So two rules for anything that runs under one:

- **Choose the TTL for the slow case.** A summary by a hosted model takes
  seconds; the same summary by a local thinking model over a long episode
  took nearly two minutes in testing. The web chat example uses five.
- **Make the write idempotent anyway.** The episode summary is written with
  `WHERE summary IS NULL`; the head summary only ever replaces one that
  reaches less far. The lease makes duplicate work rare; the guarded write
  makes it harmless. The lease is an optimisation over correctness that is
  already there, which is the right way round.

There is no queue, no fairness, and no waiting. A caller that is refused
gets `false` and nothing else, which is exactly what opportunistic work
wants and exactly wrong for work that is owed. For that, write an effect.

## Where it is used

- The head summariser takes `summary` per agent when the story has
  outgrown the tail. See [Memory](memory.md#the-head-summarizer).
- The episode summariser takes `episode` per agent when a closed episode has
  no summary. See [Memory](memory.md#episodes).
- Your own listeners, whenever they react to an agent event with work that
  costs something and must not be done twice: a nightly report, a reflection
  pass, an export.

In a Boot application the starter contributes a `Leases` bean when
`nessy-lease` is on the classpath; see [Spring Boot](../guides/spring-boot.md#leases).

## Where next

- [Durable Computation](durable-computation.md), the effects a lease is not
- [Memory](memory.md), the two summarisers that run under one
- [Storage](storage.md), the table
