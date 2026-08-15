# The Cassandra Transcript — the log meets the log-structured store

**Date:** 2026-08-15
**Status:** APPROVED — 2026-08-15 (owner: "spec and plan and build the
cassandra transcript impl"; rulings surfaced in session, not gated)

---

## 1. Purpose and scope

A second durable `Transcript` implementation, backed by Apache Cassandra —
and the first proof that the store rework's three front doors are
genuinely separable stores: an application can run its conversation
control block and parks on Postgres while its transcript — the naturally
append-only, naturally partition-per-conversation, potentially enormous
log — lives on Cassandra. **Transcript ONLY**: `ConversationStore` and
`Parks` need fenced CAS semantics that are Cassandra-hostile and
Postgres-happy; the polyglot split is the story, not a limitation.

New module: **`nessy-transcript-cassandra`**, Spring-free like its JDBC
sibling — it depends on the Cassandra Java driver (`CqlSession`) exactly
as `nessy-store-jdbc` depends on `DataSource`, versions managed by Boot's
BOM. Reactor + `nessy-bom` entries; root README Install section gains the
artifact row.

## 2. Schema and reads

```cql
CREATE TABLE IF NOT EXISTS nessy_transcript (
  conversation_id text,
  version        bigint,
  message        text,   -- the same JSON contract JdbcTranscript writes
  PRIMARY KEY ((conversation_id), version)
) WITH CLUSTERING ORDER BY (version ASC);
```

The `Transcript` contract's three reads are native clustering slices:
`all` = the partition in order; `tail(after)` = `version > ?`;
`page(before, limit)` = `version < ? ORDER BY version DESC LIMIT ?`,
reversed in memory before return (the contract wants version order).
Message JSON mirrors `JdbcTranscript`'s codec contract — read
`StateCodec`'s message half and reproduce it (it is package-private in
the JDBC module; the small serialization is duplicated, not exported —
two stores sharing a wire format by specification, not by dependency).

Bootstrap mirrors the JDBC sibling: constructor never does DDL;
`CassandraTranscript.create(CqlSession, ObjectMapper)` runs the
`IF NOT EXISTS` DDL then constructs. The keyspace is the session's
business (tests create their own), exactly as the JDBC store never
creates the database.

## 3. Version minting and the no-stutter rule — the LWT loop

Cassandra has no sequences; `Entry.version` is a contract-pinned `long`.
Both obligations — monotonic minting AND the no-stutter dedupe — settle
in one compare-and-insert loop:

1. Read the partition's last row (`ORDER BY version DESC LIMIT 1`).
2. If its message equals the incoming one: return that entry — the
   at-least-once re-telling absorbed, same as every other impl.
3. Else `INSERT … IF NOT EXISTS` at `last.version + 1` (or `0` for an
   empty partition — `TranscriptContract` pins zero-based versions, as
   `JdbcTranscript` already mints; corrected at Task 1, the spec's
   original "1" was a wording slip).
4. If the LWT is not applied, another writer won that version: re-read
   and loop — which re-evaluates the stutter rule against the winner's
   message, exactly the serialization `JdbcTranscript` gets from
   `SELECT … FOR UPDATE`.

Bounded attempts (mirror the loop's `MAX_DRIVE_ATTEMPTS` posture) with an
`IllegalStateException` naming the contention when exhausted. Reads in
the loop use `SERIAL` consistency so a failed LWT's re-read observes the
winner; the slice reads (`all`/`tail`/`page`) use the session default.
The javadoc says why in one paragraph — this is the file a reader opens
to learn how LWT replaces a row lock.

## 4. Starter integration — arbitration by the existing rule

New `CassandraTranscriptAutoConfiguration` in `nessy-autoconfigure`:
`@ConditionalOnClass(CassandraTranscript.class)`,
`@ConditionalOnBean(CqlSession.class)`, gated on the same enabled-property
family the JDBC auto-config uses, and ordered
**`before = JdbcPersistenceAutoConfiguration.class`** — its `Transcript`
bean lands first, and the JDBC auto-config's own
`@ConditionalOnMissingBean` `Transcript` backs off by the rule it already
lives by. The JDBC `Memory` bean composes over whichever `Transcript`
won: that composition IS the polyglot story, and a context test proves it
(DataSource + CqlSession both present → JDBC store/parks, Cassandra
transcript, one Memory over it). `CqlSession` itself arrives from Boot's
own Cassandra auto-configuration — service connections included
(compose-detected `cassandra` image, Testcontainers `@ServiceConnection`)
— nessy adds no session configuration of its own.

## 5. No example module

The matrix does not grow and no example switches stores. The proof lives
in the module's container tests and the autoconfigure context test; the
documentation lives in a `nessy-transcript-cassandra/README.md` (the polyglot
rationale, the schema, the LWT-instead-of-row-lock explanation, the
compose/service-connection wiring an app would use) plus the root
README's substrate section gaining one sentence and the Install section
one artifact.

## 6. Testing

- **`TranscriptContract`** (the nessy-core test-jar contract
  `JdbcTranscriptTest` already implements) run against a real Cassandra
  via Testcontainers — the correctness suite comes free.
- **Concurrency proof for the LWT loop**: parallel appenders on one
  conversation (Awaitility, no sleeps) → versions strictly monotonic, no
  duplicates, no lost messages, stutter rule held under contention.
- **Autoconfigure**: context runner tests — CqlSession present →
  Cassandra transcript wins and JDBC Memory composes over it; absent →
  JDBC transcript as today; property-disabled → back off. Annotation-pin
  test for the `before =` ordering (the house has the pattern).
- Full offline reactor green without Docker (container tests tagged like
  the JDBC ones); container sweep green with it.

## 7. Breaking (pre-1.0)

None. Purely additive: new module, new auto-configuration, no core or
SPI change of any kind.
