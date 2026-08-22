# Storage

Every store in Nessy is the same sentence: save this, scoped to an id,
safely. State is a versioned blob. Memory is a blob list. Intent is
last-blob-wins. A durable computation is a blob with a one-way status flip.
`Substrate` is the one primitive underneath all of them, and it stores
bytes, not text.

## The substrate in one paragraph

`Substrate` holds two shapes. **Documents** are mutable current-truth:
read, write-CAS, delete, and list keys, addressed by `(kind, key)`. The
**journal** is immutable history: append and read-from, addressed by
`(kind, key, seq)`. **`batch`** applies a list of document writes, document
deletes, and journal appends all-or-nothing across both shapes. Payloads
are opaque `byte[]` — the substrate never parses or constrains them; UTF-8
JSON is the house convention *above* the seam, not a substrate promise. The
store is the lock: every mutation carries a CAS expectation, and a miss
is a conflict, never a wait. Seven methods, two tables, and an adapter that
implements them gets the entire system — state, transcripts, intent,
backlogs, durable computations, and (once built) the outbox.

```java
public interface Substrate {

  // documents — mutable current-truth
  Optional<Document> read(String kind, String key);
  void write(String kind, String key, byte[] payload, long expectedVersion); // 0 = create
  void delete(String kind, String key, long expectedVersion);
  List<String> keys(String kind, int limit); // ascending key order

  // journal — immutable history
  void append(String kind, String key, long expectedSeq, byte[] payload); // create-only
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
re-reads and retries. Implementations must not alias the caller's array —
bytes are copied on write and on read, so nothing downstream can mutate
stored truth behind the CAS.

`org.jwcarman.nessy.spi.substrate` (module `nessy-spi`) is the whole package:
`Substrate`, `Codec`, `ConflictException`, and `InMemorySubstrate` — the
reference substrate, shipped alongside the contract so a feature jar can
test against it without depending on `nessy-agent`. `Nessy.autonomous()`
defaults to a fresh `InMemorySubstrate`; supply a durable implementation
through `.substrate(Substrate)` to persist every scope beyond the process.

## `Codec<T>`: the typed seam above the bytes

Nothing above the substrate hand-rolls byte encoding. `Codec<T>` is the
seam every recipe stores its shape through:

```java
public interface Codec<T> {
  byte[] encode(T value);
  T decode(byte[] bytes);

  default Codec<T> then(Codec<byte[]> next) { ... }

  static <T> Codec<T> json(ObjectMapper mapper, Class<T> type) { ... }
}
```

`Codec.json(mapper, type)` is a plain `writeValueAsBytes`/`readValue` pair
through `mapper`, exactly as `mapper` is configured — this call inspects
neither `type` nor the mapper's configuration first. There is no
construction-time check and no collision guard: a sealed `type` binds
through whatever polymorphism `mapper` resolves for it — `@JsonTypeInfo`/
`@JsonSubTypes` directly on the type, a `mapper.addMixIn(...)`, a custom
`AnnotationIntrospector` — the same vocabulary a tool input's schema/binding
rides (see [Tools](tools.md#sealed-inputs-a-vocabulary-as-one-argument)).
Annotate your sealed vocabularies: an unannotated sealed `type` simply gets
Jackson's own natural behavior, no discriminator is ever written, and
decoding fails with Jackson's own error.

Misconfiguration surfaces exactly as it would in any Jackson application —
Nessy does not inspect or police a caller's own mapper setup. What
`Codec.json` does own is the boundary: malformed bytes, an unknown
discriminator, or a shape mismatch never leak a raw Jackson exception past
it — every failure surfaces as `IllegalArgumentException` naming the
offense.

Test over `InMemorySubstrate`: storage there is real encoded bytes, so a
Jackson misconfiguration fails in your own unit tests, not in production.

## Transforms are patterns, not products

A `Codec<byte[]>` is a byte-to-byte transform, and `then` chains one onto
any `Codec<T>`: encoding runs left-to-right, decoding runs the chain
backwards. That makes an enterprise at-rest story one line:

```java
Codec<Transcript> stored = Codec.json(mapper, Transcript.class).then(gzip).then(aes);
```

Nessy ships no compression and no cryptography — `gzip` and `aes` above are
illustrative, not shipped types. `nessy-crypto` (`AesCodec`, AES-GCM with a
versioned key-id envelope) is designed but not built — it's tracked in
`ROADMAP.md` at the repo root, under Safety & governance. Two sanctioned
homes for a transform, neither shipped:

- **Wrap the codec** — a `Codec<T>` decorating a `Codec<T>`: per-kind
  control (encrypt transcripts, leave state plain).
- **Wrap the substrate** — a `Substrate` decorating a delegate: blanket,
  backend-agnostic; payloads transform passing through, metadata (`kind`,
  `key`, seq, version, timestamps) stays plaintext.

The `byte[]` contract is what makes both wrappers compose cleanly — and a
database's own at-rest encryption (TDE, KMS) remains the zero-code
alternative below the seam entirely.

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
built on the substrate:

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

`Memory`, `AgentStateStore`, `Backlog<O>`, and `DurableComputationBackend`
survive as vocabulary — floor, not ceiling — with a substrate recipe as each
one's default and only shipped implementation. A recipe owns its
serialization; the substrate never sees anything but bytes.

- **State** (`kind=state`) — one document per scope. The document version
  *is* the scope version: `SubstrateAgentStateStore.save` writes at
  `expectedVersion = state.version()`, and a lost race throws
  `StaleStateException`.
- **Memory** (`kind=memory`) — one journal per scope, one entry per
  message. `SubstrateMemory.remember` appends at `head + 1`; a conflicting
  append means the head moved, so it re-reads and retries. `recall()` folds
  every entry from seq 1 forward. Nothing here ever rewrites an entry — see
  [Memory](memory.md).
- **Backlog** (`kind=backlog`) — one document per scope holding the pending
  observations as a JSON array. **Observations are typed:**
  `SubstrateBacklog<O>` takes a `Codec<O>` — the `String` door defaults to a
  trivial UTF-8 codec, the typed door (`Nessy.autonomous(Class<O>)`) derives
  `Codec.json(mapper, observationType)` automatically. The stored document
  is a JSON array whose *elements* are the base64 of each encoded
  observation — uniform regardless of what codec produced the bytes,
  because the backlog is self-draining transient state and glance-readability
  yields to uniformity here. `SubstrateBacklog#add`/`.poll` are
  read-mutate-CAS-retry loops; a full queue throws `IllegalStateException`.
- **Durable computations** (`kind=computation`) — one document per
  computation: `{ status, outcome?, continuations[] }`. `SubstrateComputations`
  maps `create`/`await`/`complete` onto read-decide-CAS, in `nessy-agent`.
  `DurableComputationBackend` is no longer an adapter SPI; `.backend(...)`
  on the builder survives only as an override seam for a genuinely foreign
  engine (Restate, Temporal) — see
  [Durable Computation](durable-computation.md).
- **Intent** (`kind=intent`) — one document per scope, last-write-wins via
  read-then-CAS retry, shipped in `nessy-intent` — see [Intent](intent.md).

> **Discriminator conventions differ by kind.** Every polymorphic payload
> above carries a `"type"` field, but its values aren't spelled the same
> way: the message/phase/outcome codecs (`kind=memory`, `kind=state`,
> `kind=computation`) write kebab-case values (`tool-use`, `redacted-thinking`,
> `tool-result`), while the sealed intent vocabularies (`kind=intent`) write
> the declared record's verbatim simple name (`Restart`, `Diagnose`). A
> reader of the raw tables will see both conventions, one per kind.

## The one-mapper story

One `ObjectMapper` is handed to a host builder — `.objectMapper(ObjectMapper)`
on both `Nessy.cli()` and `Nessy.autonomous()` — and everything downstream
binds through it. `build()` calls `mapper.copy()` and pins the
format-critical settings on the copy: lower-camel property naming, tolerant
reads (unknown fields ignored), no default typing, `ALWAYS` inclusion, no
root wrapping. User-registered modules and serializers survive the copy;
only the wire-format knobs are pinned, because the stored format is a
compatibility surface and cannot float on presentation preferences.

Two horror stories are why the pin exists, not a hypothetical:

- A caller mapper set to `SNAKE_CASE` naming would rename every stored
  field the moment it replaced the default — a scope's own state document
  would stop parsing on the very next read.
- A caller mapper set to `NON_EMPTY` inclusion would drop
  `SubstrateComputations#create`'s empty `continuations` array from the
  wire — and the very next `await` would fail to parse the document it
  just wrote, because the field it needs is simply missing.

The pinned copy feeds every recipe default codec and the tool executor's
binding. `Schemas` generation and tool-result rendering are not threaded
through it: `Schemas.of` takes no mapper argument at all, and
`ConfiguredTool` renders a plain (non-`ToolResult`) return value through
its own private static `ObjectMapper`. Both are named surfaces still to
thread — parked, not forgotten — rather than already-closed doors. Model-
provider wire mappers are exempt from the pin by design — they serve
vendor protocol contracts, not user data, and build from the same
copy-and-pin path with their format pinned by the vendor instead.

## The annotations law

- **Every sealed hierarchy carries Jackson annotations directly** — user
  vocabularies and Nessy-owned types alike (the 2026-08-22 repeal). Nessy
  binds nothing bespoke and polices nothing: `@JsonTypeInfo`/`@JsonSubTypes`
  on the type is what `Schemas`, `Codec.json`, and the tool executor's
  binding all read. A user vocabulary that skips the annotations gets
  either `Schemas`' own rejection (tool inputs — it cannot generate a
  discriminated schema without them) or Jackson's own unannotated behavior
  (stored shapes through `Codec.json`).
- **`ContentBlock` and `Phase` carry `@JsonTypeInfo`/`@JsonSubTypes` on
  their sealed hierarchies**; the hand-rolled tree-walking codecs that used
  to bind them are gone. The pinned mapper does the binding, and the wire
  format is pinned by golden round-trip tests, not by artisanal code.
  Discriminator values are unchanged (`text`, `image`, `thinking`,
  `redacted-thinking`, `tool-use`, `tool-result`; `idle`, `awaiting-model`,
  `awaiting-tools`).

## The adapter pitch

Implement seven methods against your database and every feature above
works, unmodified, on top of it — no per-feature schema, no per-feature
concurrency discipline to get right. The reference mapping onto two plain
tables:

```sql
CREATE TABLE nessy_document (
    kind        VARCHAR(64)   NOT NULL,
    doc_key     VARCHAR(255)  NOT NULL,
    payload     BYTEA         NOT NULL,   -- BLOB on Oracle/MySQL/H2; never a JSON-typed column
    version     BIGINT        NOT NULL,
    updated_at  TIMESTAMP(6)  NOT NULL,   -- database server time
    PRIMARY KEY (kind, doc_key)
);

CREATE TABLE nessy_journal (
    kind         VARCHAR(64)   NOT NULL,
    doc_key      VARCHAR(255)  NOT NULL,
    seq          BIGINT        NOT NULL,
    payload      BYTEA         NOT NULL,
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
code, which is why adding a new one is cheap. A `BYTEA`/`BLOB` column is not
optional: the contract promises to store *any* bytes, a wrapping
transform's ciphertext included, so a JSON-typed column would be a lie.
Ops readability on untransformed payloads is one incantation away —
`convert_from(payload, 'UTF8')` on Postgres, the dialect's equivalent
elsewhere — and stops working the moment a codec wraps the payload in a
transform, exactly as expected of encrypted or compressed bytes.

!!! note "No JDBC adapter ships yet"
    This schema is the reference mapping the spec ratifies, not a shipped
    class. The in-memory substrate (`InMemorySubstrate`, `nessy-spi`) is
    what ships on this branch; a JDBC adapter is the next piece of work,
    not part of it.

## What the substrate deliberately leaves out

- **No queue primitive.** A queue is documents plus `batch` plus polling,
  not a fourth shape. The property that matters — enqueueing atomically
  with a state flip — lives in `batch` already.
- **No truncate, no TTL.** The journal is immutable truth. Retention is an
  operations concern, not a substrate one.
- **No querying into payloads.** Reporting reads the database directly if
  it must; the substrate contract stays bytes-in, bytes-out.

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

- [The Four Tiers](the-four-tiers.md) — where `Substrate` sits as the
  substrate tier's one storage face.
- [Memory](memory.md) — the journal recipe in full, and why the transcript
  is never rewritten.
- [Durable Computation](durable-computation.md) — how the `computation`
  document maps onto the create/await/complete lifecycle.
