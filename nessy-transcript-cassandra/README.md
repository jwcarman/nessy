# Nessy Transcript Cassandra

A second durable `Transcript` implementation — `nessy-store-jdbc`'s sibling,
`CqlSession` in place of `DataSource` — and the first proof that the store
rework's three front doors (`ConversationStore`, `Parks`, `Transcript`) are
genuinely separable stores. An application can run its conversation control
block and parks on Postgres while its transcript — the naturally
append-only, naturally partition-per-conversation, potentially enormous
message log — lives on Cassandra instead.

## Why the fence stays on Postgres

`ConversationStore` and `Parks` need fenced compare-and-swap semantics: one
writer wins a save, a stale writer reloads and re-drives, never overwrites.
That discipline is Postgres-happy and Cassandra-hostile — a `SELECT ... FOR
UPDATE` row lock has no clean Cassandra analog, and forcing one on would
fight the database rather than use it. So this module does not attempt a
`ConversationStore` or a `Parks`: **one front door per module per backend**.
The transcript is different — a strictly append-only, per-conversation log
is exactly Cassandra's shape, partition key and clustering column doing the
whole job. The polyglot split is the story here, not a limitation: swap the
transcript's backend, and the control block underneath doesn't notice.

## Schema

```cql
CREATE TABLE IF NOT EXISTS nessy_transcript (
  conversation_id text,
  version        bigint,
  message        text,   -- the same JSON contract JdbcTranscript writes
  PRIMARY KEY ((conversation_id), version)
) WITH CLUSTERING ORDER BY (version ASC);
```

One row per message, partitioned by conversation and clustered by an
append-only `version`. The `Transcript` contract's three reads are native
clustering slices: `all` is the partition in version order; `tail(after)` is
`version > ?`; `page(before, limit)` is `version < ? ORDER BY version DESC
LIMIT ?`, reversed back into ascending order in memory before it returns —
the contract promises version order, Cassandra's `LIMIT` only cooperates
with the database's own clustering order. `message` carries the same JSON
wire format `JdbcTranscript` writes — `StateCodec`'s message-codec surface is
duplicated here rather than depended on (the two modules don't share a
runtime dependency), because two stores agreeing on a wire format is a
specification, not a library.

The constructor alone never runs DDL — a caller pointing at a keyspace
another process already bootstrapped shouldn't pay a schema round trip on
every startup. `CassandraTranscript.create(CqlSession, ObjectMapper)`
bootstraps `nessy_transcript` (`CREATE TABLE IF NOT EXISTS`, safe to run more
than once) and returns a working transcript in one call. The keyspace itself
is the session's business — this class never creates or selects one, exactly
as `nessy-store-jdbc` never creates the database.

## LWT instead of a row lock

Cassandra has no row lock and no sequence, so `append` can't serialize
concurrent writers the way `JdbcTranscript` does with `SELECT ... FOR
UPDATE`. It compare-and-inserts in a loop instead:

1. Read the partition's last row at `SERIAL` consistency — a linearized read
   that also finishes any Paxos round a previous attempt left in flight, so
   it never observes a half-committed write.
2. If that row's message already equals the incoming one, return it — the
   no-stutter rule, held exactly as every other `Transcript` implementation
   holds it: an at-least-once re-telling is absorbed rather than duplicated.
3. Otherwise `INSERT ... IF NOT EXISTS` at `last.version + 1` (or `0` for an
   empty partition — versions are zero-based).
4. If the insert isn't applied, another writer won that version: re-read at
   `SERIAL` (so the winner's write is visible) and re-evaluate the
   no-stutter rule against it — the same serialization the row lock gives
   `JdbcTranscript` for free.

A writer that keeps losing this race gives up after a bounded number of
attempts with an `IllegalStateException` naming the contention, rather than
spinning forever. The slice reads (`all`/`tail`/`page`) run at the session's
default consistency — only the append loop's re-reads need `SERIAL`, because
only they must observe a concurrently-settling LWT.

## Wiring an application

Add the dependency (version managed by `nessy-bom`):

```xml
<dependency>
  <groupId>org.jwcarman.nessy</groupId>
  <artifactId>nessy-transcript-cassandra</artifactId>
</dependency>
```

In a Spring Boot app, that's the whole story — this module adds no session
configuration of its own. A `CqlSession` bean arrives from Boot's own
Cassandra auto-configuration exactly as it would for any other Boot app:
service-connection detection of a `cassandra` compose service or a
Testcontainers `@ServiceConnection`, or plain `spring.cassandra.*`
properties pointing at a real cluster. Once a `CqlSession` bean exists,
`nessy-autoconfigure`'s `CassandraTranscriptAutoConfiguration` bootstraps
`nessy_transcript` and publishes the `Transcript` bean —
`nessy.cassandra.enabled=false` is the master switch if an application needs
to opt out.

Arbitration is one sentence: a present `CqlSession` bean wins the
`Transcript` — `nessy-store-jdbc`'s own `Transcript` bean backs off by its
existing `@ConditionalOnMissingBean` rule — and everything else is
unchanged: `ConversationStore` and `Parks` stay on Postgres if
`nessy-store-jdbc` and a `DataSource` are present, and the `Memory` bean
composes over whichever `Transcript` won.

## Testing

`CassandraTranscript` runs `nessy-core`'s `TranscriptContract` test-jar suite
against a real Cassandra via Testcontainers, plus a concurrency proof
(parallel appenders on one conversation, Awaitility — no sleeps — asserting
strictly monotonic versions, no duplicates, no lost messages, the
no-stutter rule held under contention) and a bounded-attempts test for the
give-up path. Both are tagged `container`, excluded from the default build
the same way `live` tests are — `./mvnw verify` needs no Docker daemon.
`./mvnw test -Dnessy.excludedGroups=live` runs them (needs a Docker daemon
that can pull `cassandra:5.0`).
