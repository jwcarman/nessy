# Storage

Every store in Nessy is the same sentence: save this JSON, scoped to an id,
safely. State is a versioned blob. Memory is a blob list. Intent is
last-blob-wins. A durable computation is a blob with a one-way status flip.
`ScopedStore` is the one primitive underneath all of them.

## The kernel in one paragraph

`ScopedStore` holds two shapes. **Documents** are mutable current-truth:
read, write-CAS, delete, and list keys, addressed by `(kind, key)`. The
**journal** is immutable history: append and read-from, addressed by
`(kind, key, seq)`. **`batch`** applies a list of document writes, document
deletes, and journal appends all-or-nothing across both shapes. Payloads
are opaque strings — JSON by convention, but the kernel never parses them.
The store is the lock: every mutation carries a CAS expectation, and a miss
is a conflict, never a wait. Seven methods, two tables, and an adapter that
implements them gets the entire system — state, transcripts, intent,
backlogs, durable computations, and (once built) the outbox.

```java
public interface ScopedStore {

  // documents — mutable current-truth
  Optional<Document> read(String kind, String key);
  void write(String kind, String key, String payload, long expectedVersion); // 0 = create
  void delete(String kind, String key, long expectedVersion);
  List<String> keys(String kind, int limit); // ascending key order

  // journal — immutable history
  void append(String kind, String key, long expectedSeq, String payload); // create-only
  List<Entry> entries(String kind, String key, long fromSeq); // ascending, inclusive

  // atomicity — all-or-nothing across both shapes
  void batch(List<Op> ops);
}
```

`write` with `expectedVersion == 0` creates; any other value must match the
stored version exactly or the call throws `ConflictException`. `append`
works the same way against a sequence number — sequences start at 1, and an
entry already sitting at the expected seq is a conflict, never an
overwrite. There are no locks and no waits: a caller that loses a race
re-reads and retries.

`org.jwcarman.nessy.spi.store` (module `nessy-spi`) is the whole package:
`ScopedStore`, `ConflictException`, and `InMemoryScopedStore` — the
reference substrate, shipped alongside the contract so a feature jar can
test against it without depending on `nessy-agent`. `Nessy.autonomous()`
defaults to a fresh `InMemoryScopedStore`; supply a durable implementation
through `.store(ScopedStore)` to persist every scope beyond the process.

## The kinds table

A `kind` is a namespace with exactly one owning recipe. These names are
reserved — a feature jar declares its own kinds and must not reuse one:

| kind | shape | key | owner |
|---|---|---|---|
| `state` | document | agentId | scope engine |
| `memory` | journal | agentId | transcript recipe |
| `summary` | document | agentId | summarization sidecar (future) |
| `intent` | document | agentId | `nessy-intent` |
| `backlog` | document | agentId | backlog recipe |
| `computation` | document | computationId | durable computations |
| `outbox` | document | UUIDv7 | outbox recipe (future) |

## Layout rules

Three rules decide which shape a new kind gets, normative for anything
built on the kernel:

- **Mutable current-truth → document. Immutable history → journal. Derived
  artifacts** (summaries, folds, snapshots) **→ documents pointing at a
  seq.**
- **Shared queue, many writers → document-per-item under a kind.** The
  outbox is the model: independent inserts, delete-on-ack, no write
  contention between producers.
- **Per-scope queue, one effective writer → queue-as-one-document under the
  scope's key.** The backlog is the model: the scope's own CAS already
  serializes its activity, so one document is enough.

## Recipes, not more SPI

`Memory`, `AgentStateStore`, `Backlog`, and `DurableComputationBackend`
survive as vocabulary — floor, not ceiling — with a kernel recipe as each
one's default and only shipped implementation. A recipe owns its
serialization; the kernel never sees anything but a string.

- **State** (`kind=state`) — one document per scope. The document version
  *is* the scope version: `StoredAgentStateStore.save` writes at
  `expectedVersion = state.version()`, and a lost race throws
  `StaleStateException`.
- **Memory** (`kind=memory`) — one journal per scope, one entry per
  message. `StoredMemory.remember` appends at `head + 1`; a conflicting
  append means the head moved, so it re-reads and retries. `recall()` folds
  every entry from seq 1 forward. Nothing here ever rewrites an entry — see
  [Memory](memory.md).
- **Backlog** (`kind=backlog`) — one document per scope holding the pending
  observations as a JSON array. `StoredBacklog.add`/`.poll` are
  read-mutate-CAS-retry loops; a full queue throws `IllegalStateException`.
- **Durable computations** (`kind=computation`) — one document per
  computation: `{ status, outcome?, continuations[] }`. `StoredComputations`
  maps `create`/`await`/`complete` onto read-decide-CAS, in `nessy-agent`.
  `DurableComputationBackend` is no longer an adapter SPI; `.backend(...)`
  on the builder survives only as an override seam for a genuinely foreign
  engine (Restate, Temporal) — see
  [Durable Computation](durable-computation.md).
- **Intent** (`kind=intent`) — one document per scope, last-write-wins via
  read-then-CAS retry, shipped in `nessy-intent` — see [Intent](intent.md).

## The adapter pitch

Implement seven methods against your database and every feature above
works, unmodified, on top of it — no per-feature schema, no per-feature
concurrency discipline to get right. The reference mapping onto two plain
tables:

```sql
CREATE TABLE nessy_document (
    kind        VARCHAR(64)   NOT NULL,
    doc_key     VARCHAR(255)  NOT NULL,
    payload     TEXT          NOT NULL,   -- JSONB on Postgres, as an ops nicety only
    version     BIGINT        NOT NULL,
    updated_at  TIMESTAMP(6)  NOT NULL,   -- database server time
    PRIMARY KEY (kind, doc_key)
);

CREATE TABLE nessy_journal (
    kind         VARCHAR(64)   NOT NULL,
    doc_key      VARCHAR(255)  NOT NULL,
    seq          BIGINT        NOT NULL,
    payload      TEXT          NOT NULL,
    appended_at  TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (kind, doc_key, seq)
);
```

`read` is a point `SELECT`. A create-mode `write` (`expectedVersion == 0`)
is an `INSERT`, where a duplicate-key error *is* the conflict. An
update-mode `write` is `UPDATE … SET version = version + 1 WHERE kind = ?
AND doc_key = ? AND version = ?`, where a zero rowcount *is* the conflict.
`keys` is an index range walk with `LIMIT`. `append` is an `INSERT`, again
reading a duplicate key as the conflict. `entries` is a range `SELECT`.
`batch` is one transaction. No secondary indexes, no `SELECT FOR UPDATE` —
dialects differ only in the payload column type and the duplicate-key error
code, which is why adding a new one is cheap.

!!! note "No JDBC adapter ships yet"
    This schema is the reference mapping the spec ratifies, not a shipped
    class. The in-memory kernel (`InMemoryScopedStore`, `nessy-spi`) is
    what ships on this branch; a JDBC adapter is the next piece of work,
    not part of it.

## What the kernel deliberately leaves out

- **No queue primitive.** A queue is documents plus `batch` plus polling,
  not a fourth shape. The property that matters — enqueueing atomically
  with a state flip — lives in `batch` already.
- **No truncate, no TTL.** The journal is immutable truth. Retention is an
  operations concern, not a kernel one.
- **No querying into payloads.** Reporting reads the database directly if
  it must; the kernel contract stays string-in, string-out.

## Specified, not built: the outbox and the summary sidecar

Two pieces of this design are ratified in the spec but have no code on
this branch. Both are documented here as the intended shape, not as
something you can reach for today.

**The outbox** (`kind=outbox`, future) is a shared queue: document-per-item
under UUIDv7 keys, so `keys("outbox", n)` scans oldest-first for free. An
enqueue is meant to join whatever flip it announces in the same `batch`
call — the transactional-outbox pattern, by construction, once a worker
exists to drain it. It lands with the first durable (non-in-memory)
adapter, not before.

**The summarization sidecar** (`kind=summary`, future) never rewrites the
transcript. A `summary` document is meant to hold `{ text, throughSeq }`;
a working context would then be the summary plus
`entries("memory", id, throughSeq + 1)`. Re-summarizing would CAS-advance
the sidecar document; the journal underneath would never hear about it.

Neither of these exists in `nessy-spi` or `nessy-agent` today — treat both
sections as forward-looking design, not an API reference.

## Where next

- [The Four Tiers](the-four-tiers.md) — where `ScopedStore` sits as the
  substrate tier's one storage face.
- [Memory](memory.md) — the journal recipe in full, and why the transcript
  is never rewritten.
- [Durable Computation](durable-computation.md) — how the `computation`
  document maps onto the create/await/complete lifecycle.
