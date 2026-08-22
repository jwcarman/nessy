# ScopedStore — the storage kernel

**Date:** 2026-08-21
**Status:** Ratified (James, in conversation, 2026-08-21)
**Amends:** `2026-08-18-agent-as-scope-design.md` (the substrate tier's storage face)
**Supersedes:** `2026-08-20-durable-computation.md` §9 (backend SPI), §17–§22 (SQL
reference, continuation table, outbox tables, SQL await/completion, outbox worker
SQL), §29–§30 (backend architecture rule, capability abstraction) — the storage
*mechanics* move here. That spec's semantic law — one flip, atomic await,
deterministic identity, idempotent completion, the delivery model — remains
binding and is implemented by the recipes in §6.

---

## 1. Motivation

Every store in Nessy is the same sentence: *"save this JSON, scoped to an id,
safely."* State is a versioned blob plus a timestamp. Memory is a blob list.
Intent is last-blob-wins. Computations are a blob with a one-way status flip.
Yet each one carries its own SPI, its own in-memory substrate, its own builder
factory, and — in any future database adapter — its own table.

That per-concern architecture taxes every axis at once:

- **Feature cost.** A new feature (intent, notebook, fact journal) means a new
  SPI × a new substrate × a new builder seam × a new table in every backend —
  and users who never touch the feature still carry its schema.
- **Adapter cost.** A database adapter must implement N interfaces and get N
  concurrency disciplines right.
- **Transaction cost.** Atomicity across stores requires cross-SPI unit-of-work
  machinery nobody wants to design.

The kernel replaces all of it with the two primitives every database is
secretly made of — a **document store** (the page store) and a **journal** (the
WAL) — plus one **atomic batch** across them. Storage design ends there;
everything else is a recipe.

## 2. The kernel in one paragraph

`ScopedStore` holds two shapes. **Documents** are mutable current-truth:
read / write-CAS / delete / keys, addressed by `(kind, key)`. The **journal** is
immutable history: append / read-from, addressed by `(kind, key, seq)`. **batch**
applies a list of document writes, document deletes, and journal appends
all-or-nothing. Payloads are opaque strings (JSON by convention — the kernel
never parses them). The store is the lock: every mutation carries a CAS
expectation, and a miss is a conflict, never a wait. Seven methods, two tables,
and an adapter that implements them gets the entire system — state,
transcripts, intent, backlogs, durable computations, the outbox.

## 3. SPI definition

Package `org.jwcarman.nessy.spi.store`, module `nessy-spi`.

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

  record Document(String payload, long version, Instant updatedAt) {}

  record Entry(long seq, String payload, Instant appendedAt) {}

  sealed interface Op {
    record WriteDocument(String kind, String key, String payload, long expectedVersion)
        implements Op {}
    record DeleteDocument(String kind, String key, long expectedVersion) implements Op {}
    record AppendEntry(String kind, String key, long seq, String payload) implements Op {}
  }
}
```

One conflict signal, same package:

```java
public final class ConflictException extends RuntimeException { ... }
```

Naming note: `keys` lists document keys; `entries` lists journal entries — the
symmetric noun pair is deliberate. `Op` members follow sealed-grammar etiquette
(records nested in the sealed interface, no external implementations).

## 4. Semantics

1. **CAS law.** `write` with `expectedVersion == 0` creates the document at
   version 1; a document already present is a conflict. `write` with
   `expectedVersion == v` succeeds iff the stored version is `v`, storing at
   `v + 1`. `delete` requires the same match, with one symmetry:
   `delete` with `expectedVersion == 0` against an absent document is a no-op
   success ("I believe it is absent" — satisfied), while against a present
   document it conflicts. Any miss throws
   `ConflictException`. There are no locks, no waits, no partial states.
2. **Journal law.** Sequences start at 1. `append(kind, key, expectedSeq, …)`
   creates the entry at exactly `expectedSeq`; an entry already at that seq is
   a conflict (the caller re-reads the head and retries). Entries are never
   rewritten and never deleted — the kernel has **no destructive journal
   operation** (compaction is a sidecar, §6.7; retention is an ops concern,
   archived by `appendedAt` — S3 is the ruled archival tier).
3. **Batch law.** `batch` applies its ops atomically: all succeed or none
   apply, and any CAS/seq miss fails the whole batch with `ConflictException`.
   Batches are small by design (2–3 ops); adapters may document a ceiling no
   lower than 10 (DynamoDB's `TransactWriteItems` allows 25).
4. **`keys` law.** Ascending lexicographic key order, at most `limit` results,
   `limit >= 1`. Because reserved queue kinds use UUIDv7 keys, key order is
   creation order — fairness for workers, free.
5. **Opacity.** Payloads are non-null strings the kernel never inspects. JSON
   is the house convention; the kernel contract says "string".
6. **Time.** `updatedAt`/`appendedAt` come from the adapter's single time
   source. A shared-database adapter MUST use database server time
   (`CURRENT_TIMESTAMP`), not per-host clocks — staleness decisions read these
   timestamps, and host clock skew would make them lie.
7. **Thread safety.** Implementations MUST be safe for concurrent use; the CAS
   discipline is the only coordination callers get or need.

## 5. Kinds — reserved names and layout rules

A `kind` is a namespace with exactly one owning recipe. Reserved kinds:

| kind | shape | key | owner |
|---|---|---|---|
| `state` | document | agentId | scope engine |
| `memory` | journal | agentId | transcript recipe |
| `summary` | document | agentId | summarization sidecar (§6.7, future) |
| `intent` | document | agentId | `nessy-intent` |
| `backlog` | document | agentId | backlog recipe |
| `computation` | document | computationId | durable computations |
| `outbox` | document | UUIDv7 | outbox recipe (§6.6, lands with first durable adapter) |

Feature jars declare their own kinds and must not reuse a reserved name.
Layout rules, normative:

- **Mutable current-truth → document. Immutable history → journal. Derived
  artifacts (summaries, folds, snapshots) → documents pointing at a seq.**
- **Shared queue, many writers → document-per-item under a kind** (the outbox:
  independent inserts, delete-on-ack, zero write contention).
- **Per-scope queue, one effective writer → queue-as-one-document under the
  scope's key** (the backlog: the scope CAS already serializes activity).

## 6. Recipes

Recipes are library code over the kernel. They own serialization (§7); the
kernel sees strings. Domain interfaces (`Memory`, `IntentStore`, `Backlog`,
`DurableComputationBackend`) survive as **vocabulary and override seams** —
floor, not ceiling — with kernel recipes as their default implementations.

### 6.1 State
`kind=state`, one document per scope. **The document version IS the scope
version** — `State(phase, version)` maps to (payload, document version), `save`
is `write(…, expectedVersion = state.version())`, `StaleStateException` wraps
`ConflictException`, and `lastSaved()` is `updatedAt`. One lock, held by the
store, exactly as the agent-as-scope spec demands.

### 6.2 Memory (transcript)
`kind=memory`, journal, one entry per message. `remember` appends at
`head + 1` (conflict → re-read head, retry; near-zero in practice — the scope
CAS serializes turns). `recall` reads `entries(…, 1)` and folds to `Context`.
The transcript is the permanent record; nothing rewrites it.

### 6.3 Intent
`kind=intent`, one document per scope, last-write-wins via read-then-CAS retry.
Ships in `nessy-intent` (§12).

### 6.4 Backlog
`kind=backlog`, one document per scope holding the JSON array of pending
observations, bounded (default 1024, checked in the recipe). `add`/`poll` are
read-mutate-CAS loops.

### 6.5 Durable computations
`kind=computation`, `key = computationId`, one document:
`{ status, outcome?, continuations[] }`. The durable spec's semantic law maps
onto CAS:

- `create` — `write(…, 0)`; conflict → the slot exists, read it (get-or-create).
- `await` — read; terminal → return the outcome; else CAS-append the
  continuation to the document (set semantics — an equal continuation twice is
  one registration); conflict → retry. The §12 atomicity holds because the CAS
  is the row lock.
- `complete` — read; terminal → `ALREADY_TERMINAL`; else CAS the flip.
  Unknown id → create already-terminal (ruling 6); conflict → retry.

`DurableComputationBackend` is **no longer an adapter SPI** — it remains as the
internal vocabulary the desks and dispatcher speak, with the kernel recipe
(`StoredComputations`, in `nessy-agent`) as its default and only shipped
implementation. The builder's `backend(…)` seam survives for genuinely foreign
engines (Restate, Temporal); nobody implements it to get a database.

Retention: terminal `computation` documents are never reaped by the recipe.
In-memory this mirrors the old map's behavior; a durable adapter deployment
needs a retention story (age-out of terminal documents by `updatedAt`) before
tables grow without bound — ruled to land with the first durable adapter,
alongside the outbox.

### 6.6 Outbox (specified now; lands with the first durable adapter)
Document-per-item under `kind=outbox`, UUIDv7 keys (oldest-first scan for
free), payload `{ computationId, continuation, attempts, leasedUntil }`.
Enqueue joins the flip via `batch([flip, enqueue])` — the transactional outbox,
by construction. The worker: `keys("outbox", n)` → CAS-lease (`leasedUntil`)
→ deliver → versioned delete. Discipline, normative:

- **Self-draining:** rows die on ack; steady-state size ≈ pending deliveries.
- **Poison rows:** CAS `attempts` per try, back off by `attempts`, and after N
  failures park the row (dead-letter marker) and surface it to the observer —
  one broken delivery never starves the queue.
- **Ordering is fairness, not correctness.** Correctness rests on CAS + receipt
  dedup; UUIDv7 order, poll cadence, and lease jitter are quality-of-service.
- In-process deployments keep direct dispatch (complete → nudge); the poll is
  the recovery net. A Postgres adapter may swap the worker's scan for
  `SKIP LOCKED`/`LISTEN-NOTIFY` — the override seam is the worker, never the
  kernel.

### 6.7 Summarization sidecar (future, shape ruled now)
Summarization never rewrites the transcript. A `kind=summary` document holds
`{ text, throughSeq }`; working context = summary + `entries(memory, id,
throughSeq + 1)`. Re-summarizing CAS-advances the sidecar; the journal never
hears about it.

## 7. The serialization seam

Payload rendering lives in recipes, not the kernel, and is the one real
engineering lift here:

- Recipes serialize with Jackson to plain JSON — **zero Jackson annotations on
  domain types** (house law: nessy-owned binding).
- `Outcome.Success(Object)` payloads are a **closed vocabulary**: `ToolResult`
  (completions desk) and `Decision` (approvals desk), stored with a type
  discriminator. Completing with any other payload type is out of contract and
  rejected at the door. Opening the vocabulary is a spec amendment.
- **Payload evolution is ours, not ALTER TABLE's**: stored shapes evolve via
  tolerant reads (unknown fields ignored, absent fields defaulted) in recipe
  code. This discipline is permanent.

## 8. JDBC reference schema

The whole system, two tables:

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

Statement mapping: `read` = point `SELECT`; create = `INSERT` (duplicate key
**is** the conflict); update = `UPDATE … SET version = version + 1 WHERE … AND
version = ?` (rowcount 0 **is** the conflict); `keys` = index range walk with
`LIMIT`; `append` = `INSERT` (duplicate key is the conflict); `entries` = range
`SELECT`; `batch` = one transaction. No secondary indexes; no `SELECT FOR
UPDATE`. Dialects differ only in the payload column type and the duplicate-key
error code — which is why dialect support is cheap.

## 9. DynamoDB mapping

Partition key `kind`, sort key `doc_key` (journal: `doc_key#seq`), conditional
expressions on `version`/attribute-absence for CAS, `TransactWriteItems` for
`batch` (25-op ceiling, far above our 2–3). Honesty: `kind` as partition key
puts each kind in one partition (~1000 writes/sec ceiling) — irrelevant at
control-plane volume; if ever needed, the adapter write-shards
(`outbox#0..3`) internally with no kernel API change.

## 10. What the kernel is not

- **No queue primitive.** A queue is an access pattern composed of documents +
  batch + polling, not a fourth storage shape. The atomic-with-the-flip
  property — the outbox's whole point — lives in `batch`; a native `enqueue`
  that can't join the flip's transaction would be worthless, and one that can
  is a document write. Promotion path: if real deployments show lease
  contention, adding a queue primitive later is additive; shipping one
  prematurely is forever.
- **No truncate, no TTL.** The journal is immutable truth; summarization is a
  sidecar; retention is ops (archive by `appendedAt`).
- **No querying into payloads.** Reporting/BI reads the database directly if
  it must; the kernel contract stays string-in, string-out.
- **Database-as-queue is the design, not a compromise.** Transactional enqueue
  is the point; control-plane volume is the workload; brokers (RabbitMQ et al.)
  attach *downstream of the outbox* as relays, never instead of it.

## 11. Feature modularity — features are jars

Because a feature is now recipes + a kind string, features ship as optional
artifacts with zero storage footprint unless used:

- **`nessy-intent`** (this branch): `IntentTool`, `IntentEnricher`,
  `IntentPolicies`, `IntentStore`, `Intent`, and the kernel-backed store
  recipe move out of api/spi/agent into their own module (depends on
  `nessy-api` + `nessy-spi`). The governed example gains the dependency.
- **Notebook, fact journal, trajectory** (future): same pattern — own jar, own
  kind, no core tax.

`SealedInputs`/`Schemas` stay in `nessy-api` — they are schema binding for all
tools, not an intent feature.

## 12. What dies, what survives

Dies: `InMemoryStateSubstrate`, `InMemoryMemorySubstrate`,
`InMemoryBacklogSubstrate`, `InMemoryAgentStateStore`, `InMemoryIntentStore`,
`InMemoryDurableComputationBackend`, and the builder's `storeFactory` seam.

Arrives: `ScopedStore` + `ConflictException` + `InMemoryScopedStore` (all in
`nessy-spi` — the reference substrate travels with the contract, a documented
exception in the spirit of the old backend ruling, so feature jars test
against it without depending on `nessy-agent`), the recipes, and one builder
seam: `.store(ScopedStore)` (default `InMemoryScopedStore`).

Survives as override seams: `.memoryFactory(…)` (custom `Memory`),
`.backend(…)` (foreign durable engines). The `.store(…)` seam lives on the
autonomous builder only: the CLI door is **ephemeral by design** (in-process,
one sitting, `StalenessPolicy.never()`) and builds its own discarded kernel —
it deliberately exposes no store seam. The scope engine, phases, CAS
discipline, desks, dispatcher, doors: untouched — this reform is entirely
below the waterline of the agent-as-scope design.

## 13. Sequencing

1. **This branch:** kernel + in-memory substrate + recipes (state, memory,
   backlog, computations) + intent extraction to `nessy-intent` + the deaths
   in §12 + docs. Outbox and summary are specified, not built.
2. **JDBC adapter:** one class, two tables, the TCK reuse question, and the
   first real `batch` transaction — where "can we make all of this
   transactional" becomes yes in production.
3. **Spring rebirth** (held plan): lands after, exposing one store bean
   instead of three factories.

The broader API reduction pass (kill list, demotions, toString collapse)
remains a separate piece awaiting James's cut of the audit buckets; this spec
executes only the reductions the kernel itself causes.
