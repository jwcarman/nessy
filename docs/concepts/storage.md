# Storage

Nessy is PostgreSQL rows. Each kind of thing lives in a table shaped for how
it is read, and there is no abstraction between the engine and its SQL.

| What | Where | Lives for |
|---|---|---|
| An agent, so there is something to lock | `nessy_agent` | until it is terminated, and after |
| What happened to an agent, one row per event, append-only | `nessy_agent_event` | forever, unless you prune it |
| Content: what a message or a tool result actually said | `nessy_payload` | forever, unless you prune it |
| Work an agent owes, with its deadline | `nessy_agent_effect` | until it completes or is given up on |
| Work offered to a busy agent, waiting its turn (queued door only) | `nessy_agent_backlog` | until it is claimed or coalesced away |
| Closed chapters of an agent's history, each with the summary that stands in for it once written | `nessy_chapter` | forever, unless you prune it |
| Notes and plan tasks | `nessy_note`, `nessy_plan_task` | as their modules decide |
| Background work claimed once, see [Leases](leases.md) | `nessy_lease` | its TTL |

Every module that needs a table ships it in its own `nessy-schema.sql`.
The story, the outbox, the backlog, the chapters and the leases come from
`nessy-backend-jdbc`; notes and plan tasks come from `nessy-memory-notebook`
and `nessy-planning`.

## PostgreSQL, and only PostgreSQL

The queries this engine rests on are PostgreSQL's: `pg_advisory_xact_lock`
to serialise an agent for the length of one transaction, `FOR UPDATE SKIP
LOCKED` to claim work, `INSERT ... ON CONFLICT` to take a lease, `TIMESTAMPTZ`
columns. There is no in-memory fallback and no H2: a fallback would not run
a degraded Nessy, it would run one that fails on the first turn, and saying
so at startup is kinder than an embedded database that looks like it
worked. The test suite runs the same DDL against a real PostgreSQL
container.

## Applying the schema

`Schemas` gathers every module's `nessy-schema.sql` from the classpath and
runs them, in one call, safe to repeat:

```java
Schemas.initialize(dataSource);
```

**The name is the opt-in.** Spring Boot looks for `schema.sql`, so Nessy's
file never runs uninvited, and Nessy's loader never runs yours. Call this
yourself, let the starter do it (`nessy.initialize-schema`, on by default),
or feed the files to whatever runs your migrations. `classpath*:` matters:
it enumerates *every* matching resource rather than the first, so a module
added later brings its table with it.

## Two doors, two backends, different tables

`nessy-backend-spi` defines `DirectBackend` and `QueuedBackend`;
`nessy-backend-jdbc` implements both over one `DataSource`, and
`nessy-backend-inmemory` implements both in-process for a test or a CLI
with nothing behind them but this JVM.

A `DirectBackend` needs only the story and the lock: `JdbcDirectBackend`
touches `nessy_agent_event`, `nessy_payload` and the advisory lock, and
nothing else — the direct door never names `nessy_agent`, `nessy_agent_effect`
or `nessy_agent_backlog`, so a caller that only ever builds a direct backend
is not handed tables it will never write a row to. A `QueuedBackend` needs
the rest as well: `JdbcQueuedBackend` adds `nessy_agent` (something to lock
even between turns), `nessy_agent_effect` (the outbox a dispatcher polls)
and `nessy_agent_backlog` (work offered to a busy agent).

```java
JdbcQueuedBackend backend =
    new JdbcQueuedBackend(dataSource, transactionManager, codecFactory);

DefaultQueuedHarnessFactory factory =
    DefaultQueuedHarnessFactory.of(engine -> engine
        .backend(backend)
        .provider(providerId, provider)
        .inference(providerId, InferenceOptions.of("claude-sonnet-5")));
```

A Spring Boot application never builds a backend by hand: `nessy-backend-jdbc`
on the classpath with a `DataSource` bean gives you `JdbcBackendAutoConfiguration`,
which contributes the `DirectBackend`, `QueuedBackend` and `Leases` beans, and
each door's own auto-configuration turns those into a `DefaultDirectHarnessFactory`
or `DefaultQueuedHarnessFactory`. See [Spring Boot](../guides/spring-boot.md).

## Reading the story back

Only the queued door's factory exposes a read side, because only it keeps
work alive across a restart for something outside the engine to inspect:

```java
TurnHistories histories = factory.histories();
TurnHistory story = histories.forAgent(agentType, agentId);
List<Turn> turns = story.turnsFrom(0);
```

In a Boot application this is the `TurnHistories` bean contributed by
`QueuedHarnessAutoConfiguration`.

## Rows are Jackson, then whatever you say

Every payload column is bytes: the row encoded by Jackson, then passed
through whatever `Codec<byte[]>` the backend was built with. A backend built
from a Boot application's `CodecFactory` bean gets whatever the
`StorageCodecConfigurer` bean appended:

```java
@Bean
StorageCodecConfigurer storage() {
  return original -> original.andThen(gzip).andThen(aesGcm);
}
```

That is the seam for compression and for encryption at rest, and it covers
every store a backend builds — the story, the payloads, and for the queued
door the effects and the backlog too. `nessy_agent_backlog` is the one
table the schema flags as holding raw user text ahead of an event, so it is
the one table a storage transform must never skip. It is fixed for the life
of the data: rows written under one transform are unreadable under another,
which is the same fact as an encryption key.

## What an agent's row holds

`nessy_agent` is small on purpose: `agent_type`, `agent_id`, `created_at`,
and `terminated_at`, set once and never cleared. It exists only so there is
something to lock — `pg_advisory_xact_lock` is taken against a hash of
`(kind, agent_type, agent_id)` and needs no row of its own to be true of,
but the queued door still needs a durable place to record that an agent was
told to end, since ending cannot be delivered mid-turn and has to be
remembered until the agent is next idle.

Where an agent actually *is* — idle, inferring, or waiting on a named set of
outstanding calls — is not stored anywhere as a row. It is rebuilt by
replaying `nessy_agent_event` from the start, which is why that table has
no update statement in its schema: reconstitution is a loop over history,
not a read of a snapshot. See [Agent as Scope](agent-as-scope.md).

## The story

`nessy_agent_event` is one row per event, appended and never rewritten,
keyed by agent type, agent id and `seq`, with a `starts_turn` flag marking
where each turn began. The flag is there so replaying an agent's last turn
is a lookup rather than a scan: a partial index over `starts_turn` picks out
the handful of rows per agent that matter to that query, however long the
conversation.

Events carry no content directly. What a message said, or a tool returned,
lives in `nessy_payload`, addressed by the SHA-256 hash of its own encoded
bytes and scoped to the agent that produced it. That buys idempotence — an
effect retried after a failure writes the same row rather than a second
copy — and it buys forgetting: deleting everything one agent ever said is
one statement over one table, with nothing shared out from under another
agent. Identical content in two agents is stored twice, and that is the
trade.

## Effects

`nessy_agent_effect` carries the work an agent owes and everything needed
to perform it without decoding it: a status, when it is next actionable
(`actionable_at`, which serves both a first attempt's due time and a
running attempt's watchdog time), how many attempts it has had
(`attempts_made`), a per-attempt timeout (`timeout_millis`) and a hard
deadline that never moves, the W3C trace context of the turn it belongs to,
and beside the payload a second blob, `failure_payload`, saying what to
tell the agent if the work can never be dispatched at all. See
[Durable Computation](durable-computation.md).

## Chapters

`nessy_chapter` has one row per closed chapter, keyed by agent type, agent id
and `from_turn`, with `through_turn`, `after_turn` (where the agent's previous
chapter ended, or zero), `closed_at`, and `summary` and `summarized_at`,
which are null until the summary is written. A chapter's bounds are never
changed. Its summary is written once, by an update that only touches a row
whose summary is still null.

Two constraints keep an agent's chapters contiguous: the primary key, and a
unique `(agent_type, agent_id, after_turn)`. A chapter is stored only if the
agent's closed chapters still end where the caller said they did, so two
processes that cut the same turns differently cannot both succeed, and
neither leaves a gap or an overlap. The `Chapters` interface in
`nessy-backend-spi`, reached as `backend.chapters()`, is how the engine reads
and writes this table. See [Memory](memory.md#chapters).

## Retention

Nothing here deletes. Terminating an agent ends its activity and leaves its
rows; the story and its payloads grow until you prune them. That is a
policy your operators own, applied to the tables directly, and the schema
is plain enough to do it in one statement per table.

## Where next

- [Memory](memory.md), what a model call is built from
- [Durable Computation](durable-computation.md), what survives a crash, and how
