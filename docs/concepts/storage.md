# Storage

Every store in Nessy is the same sentence: save this, scoped to an id,
safely. State is a versioned blob. Memory is a blob list. Intent is
last-blob-wins. `Substrate` is the one primitive underneath all of them,
and it stores bytes, not text.

Durable computations — approvals and deferred tool calls — are not part of
`Substrate` anymore. They live in a separate store owned by
`org.jwcarman.continuum`. `Substrate` keeps only the dispatch index that
remembers which computation a call is currently in flight under. See the
warning below.

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
implements them gets the entire system on Nessy's side — state,
transcripts, intent, backlogs, and the dispatch index.

!!! warning "Two stores, one durability tier"
    A harness now writes to two stores: this `Substrate`, and Continuum's
    own computation store (approvals and deferred tool calls, plus their
    outbox). **Both must be in memory, or both must be durable — never one
    of each.**

    Mixing them breaks in opposite ways. Durable computations over an
    in-memory substrate: a restart wipes scope state but not Continuum's
    pending work, so every surviving delivery lands on a scope restored to
    `Idle`, whose reducer ignores it — a tool result that never arrives and
    a call that never completes, with nothing logged as an error.
    In-memory computations over a durable substrate: a restart wipes
    Continuum's pending work but not the dispatch index naming it, so the
    gate absorbs every redrive against a computation that no longer exists
    — the call hangs permanently.

    `InMemorySubstrate` is the only `Substrate` Nessy ships today, so the
    only coherent wiring right now is both in memory — the default
    `Nessy.harness(...)` gives you. A durable pairing needs a durable
    `Substrate`, which does not exist yet; Continuum's own `continuum-jdbc`
    provider is a TCK-certified PostgreSQL backend and is not what's
    missing. See [Durable Computation](durable-computation.md) for the full
    story.

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

`org.jwcarman.nessy.spi.substrate` (module `nessy-spi`) is `Substrate`,
`ConflictException`, `DocumentStore`/`JournalStore`, `Versioned`,
`SubstrateSupport`, and `InMemorySubstrate` — the reference substrate,
shipped alongside the contract so a feature jar can test against it without
depending on `nessy-agent`. The typed seam above the bytes — `Codec<T>` and
`CodecFactory` — is not Nessy's own: it's `org.jwcarman.codec.spi` from the
released `org.jwcarman.codec` library (`codec-core` for the SPI,
`codec-jackson2` for the Jackson binding), the same shapes this page
described before the 2026-08-24 codec-adoption reform, now maintained
outside Nessy. `Nessy.harness(...)` defaults to a fresh `InMemorySubstrate`;
supply a durable implementation through `.substrate(Substrate)` to persist
state, transcripts, memory, intent, and backlogs beyond the process — but
that alone does not make durable computations durable. Durable computations
(approvals, deferred tool calls) now live in a separate store owned by
`org.jwcarman.continuum`, and the two stores must agree on durability. See
the warning above and [Durable Computation](durable-computation.md).

## `DocumentStore<T>`/`JournalStore<T>`: typed views, implemented once

A feature never juggles the byte dance directly — it mints a typed view and
writes domain logic:

```java
DocumentStore<Phase> states = substrate.document("state", Phase.class);
JournalStore<Message> transcript = substrate.journal("memory", Message.class);
```

`kind` is given explicitly at the mint, never derived from `T`'s class name
— a rename must never orphan data. The codec comes from `Substrate#codecs()`
(a `CodecFactory`) unless a `Codec<T>` is supplied directly, bypassing the
factory for a caller-owned binding (a transform, a test probe). Every
substrate implementation gets its `CodecFactory` for free by extending
`SubstrateSupport`, which owns one `ObjectMapper` per substrate instance
(never a shared static) — and "pinned" here means genuinely copy-and-pinned:
`SubstrateSupport.copyAndPin` (the single source of truth for the
format-critical knob list — see "The one-mapper story" below) runs on BOTH
the default, standard mapper and any caller-supplied one, so a document's
stored format is safe regardless of who constructs the substrate. Overriding
the mapper at construction (`new InMemorySubstrate(mapper)`) *is* the codec
extension point; there is no separate per-feature codec seam to thread
anymore.

`DocumentStore<T>` reads back a `Versioned<T>` (value plus version, the same
pairing `Substrate.Document` always carried) and owns the read-modify-write
CAS-retry loop once, for every caller: `documents.update(key, seed, fn)`
reads current truth (or `seed` if absent), applies `fn`, and retries on a
lost race until the write lands. `JournalStore<T>` owns the equivalent
append-retry loop and returns decoded entries directly.

Both views mint the same `Substrate.Op`s a hand-rolled batch would build —
`writeOp`/`deleteOp`/`appendOp` — so a multi-store atomic commit (a
fold-advance, a completion) composes typed writes from several stores into
one `Substrate#batch` call without ever touching a raw payload. `create` in
[Durable Computation](durable-computation.md) is exactly this: a
`computations.writeOp(...)` composed with whatever else the caller's own
batch needs.

Every recipe below — state, memory, backlog, intent, computations — rides a
typed view now; the CAS-retry loops described in earlier revisions of this
page as hand-rolled per recipe are this one implementation, reused.

## `Codec<T>`: the typed seam above the bytes

Nothing above the substrate hand-rolls byte encoding. `org.jwcarman.codec.spi.Codec<T>`
is the seam every recipe stores its shape through — released separately
from Nessy (`org.jwcarman.codec:codec-core`, 0.2.0):

```java
public interface Codec<T> {
  byte[] encode(T value);
  T decode(byte[] bytes);

  default Codec<T> andThen(Codec<byte[]> next) { ... }
}

public interface CodecFactory {
  <T> Codec<T> create(TypeRef<T> type);
  default <T> Codec<T> create(Class<T> type) { ... }
}
```

`SubstrateSupport` mints its `CodecFactory` as one `Jackson2CodecFactory`
(`org.jwcarman.codec:codec-jackson2`) over the substrate's pinned mapper — a
plain `writeValueAsBytes`/`readValue` pair through that mapper, exactly as
it is configured; this binding inspects neither the requested type nor the
mapper's configuration first. There is no construction-time check and no
collision guard: a sealed type binds through whatever polymorphism the
mapper resolves for it — `@JsonTypeInfo`/`@JsonSubTypes` directly on the
type, a `mapper.addMixIn(...)`, a custom `AnnotationIntrospector` — the same
vocabulary a tool input's schema/binding rides (see
[Tools](tools.md#sealed-inputs-a-vocabulary-as-one-argument)). Annotate your
sealed vocabularies: an unannotated sealed type simply gets Jackson's own
natural behavior, no discriminator is ever written, and decoding fails with
Jackson's own error.

Misconfiguration surfaces exactly as it would in any Jackson application —
Nessy does not inspect or police a caller's own mapper setup. The external
codec's own failure contract is `UncheckedIOException`; Nessy's typed views
(`DocumentStore<T>`/`JournalStore<T>`) are where that gets translated back
into the `IllegalArgumentException` naming the offense that every malformed-
payload test here has always seen — a raw Jackson exception never leaks past
that boundary.

Test over `InMemorySubstrate`: storage there is real encoded bytes, so a
Jackson misconfiguration fails in your own unit tests, not in production.

## Transforms are patterns, not products

A `Codec<byte[]>` is a byte-to-byte transform, and `andThen` chains one onto
any `Codec<T>`: encoding runs left-to-right, decoding runs the chain
backwards. That makes an enterprise at-rest story one line:

```java
Codec<Transcript> stored =
    new Jackson2CodecFactory(mapper).create(Transcript.class).andThen(gzip).andThen(aes);
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
| `dispatch/<agentType>` | document | call address digest | dispatch index |

Approvals and deferred tool calls no longer live in `Substrate` at all —
`org.jwcarman.continuum` owns that store now, under its own `approval/<agentType>`
and `tool/<agentType>` kinds. `dispatch/<agentType>` is what `Substrate`
keeps instead: one entry per in-flight call, naming which Continuum
computation currently owns it, so a staleness redrive can be absorbed
without asking Continuum again. See
[Durable Computation](durable-computation.md#the-dispatch-index-what-survives-a-redrive).

## Layout rules

Three rules decide which shape a new kind gets, normative for anything
built on the substrate:

- **Mutable current-truth → document. Immutable history → journal. Derived
  artifacts** (summaries, folds, snapshots) **→ documents pointing at a
  seq.**
- **Shared queue, many writers → document-per-item under a kind.**
  Continuum's own outbox (no longer a `Substrate` recipe) is the model:
  independent inserts, delete-on-ack, no write contention between
  producers.
- **Per-scope queue, one effective writer → queue-as-one-document under the
  scope's key.** The backlog is the model: the scope's own CAS already
  serializes its activity, so one document is enough.

## Recipes, not more SPI

`Memory`, `AgentStateStore`, and `Backlog<O>` survive as vocabulary — floor,
not ceiling — with a substrate recipe as each one's default and only
shipped implementation. A recipe owns its serialization; the substrate
never sees anything but bytes.

- **State** (`kind=state`) — one document per scope. The document version
  *is* the scope version: `SubstrateAgentStateStore.save` writes at
  `expectedVersion = state.version()`, and a lost race throws
  `StaleStateException`.
- **Memory** (`kind=memory`, plus `kind=memory-keys` for the idempotency
  marker) — one journal per scope, one entry per `Remembrance`.
  `SubstrateMemory.remember` is CAS-guarded against its marker document: a
  key already known there makes `remember` a no-op; otherwise it appends at
  `head + 1` in the same batch as the marker update, retrying on a lost
  race. `recall()` folds every entry from seq 1 forward, reassembling
  paired messages. Nothing here ever rewrites an entry — see
  [Memory](memory.md). Memory is not part of the fold-advance batch below
  (remembrance spec §1): a scope's `Memory` remembers before that batch
  commits, never inside it.
- **Backlog** (`kind=backlog`) — one document per scope holding the pending
  observations as a JSON array. **Observations are typed:**
  `SubstrateBacklog<O>` takes a `Codec<O>` — the `String` door defaults to a
  trivial UTF-8 codec, the typed door (`Nessy.harness(Class<O>, ...)`) derives
  one from the substrate's own `CodecFactory` over `observationType`
  automatically. The stored document
  is a JSON array whose *elements* are the base64 of each encoded
  observation — uniform regardless of what codec produced the bytes,
  because the backlog is self-draining transient state and glance-readability
  yields to uniformity here. `SubstrateBacklog#add`/`.poll` are
  read-mutate-CAS-retry loops; a full queue throws `IllegalStateException`.
- **Durable computations** live outside `Substrate` entirely — approvals
  and deferred tool calls are owned by `org.jwcarman.continuum`, under its
  own `approval/<agentType>` and `tool/<agentType>` kinds, including its
  own outbox. What `Substrate` still holds is the **dispatch index**
  (`kind=dispatch/<agentType>`) — one document per in-flight call, `{
  computationId, kind }`, naming which Continuum computation currently
  owns that call. There is no status field and no terminal record —
  presence alone means in flight; the entry is deleted in the same batch
  as the fold that resolves it. See
  [Durable Computation](durable-computation.md).
- **Intent** (`kind=intent`) — one document per scope, last-write-wins via
  read-then-CAS retry, shipped in `nessy-intent` — see [Intent](intent.md).

> **Discriminator conventions differ by kind.** Every polymorphic payload
> above carries a `"type"` field, but its values aren't spelled the same
> way: the message/phase codecs (`kind=memory`, `kind=state`) write
> kebab-case values (`tool-use`, `redacted-thinking`, `tool-result`), while
> the sealed intent vocabularies (`kind=intent`) write the declared
> record's verbatim simple name (`Restart`, `Diagnose`). A reader of the
> raw tables will see both conventions, one per kind.

## The one-mapper story

One `ObjectMapper` is handed to a harness config — `.objectMapper(ObjectMapper)`
on both `Nessy.cli()` and `Nessy.harness(...)` — and everything downstream
binds through it. Nessy calls `mapper.copy()` and pins the
format-critical settings on the copy: lower-camel property naming, tolerant
reads (unknown fields ignored), no default typing, `ALWAYS` inclusion, no
root wrapping. User-registered modules and serializers survive the copy;
only the wire-format knobs are pinned, because the stored format is a
compatibility surface and cannot float on presentation preferences.

`SubstrateSupport.copyAndPin` (`nessy-spi`) is the single source of truth for
this knob list — the host module's own `Codecs.copyAndPin` (`nessy-agent`)
delegates to it rather than carrying a second copy. `SubstrateSupport`
applies it to every substrate's mapper, default or caller-supplied alike, so
`Substrate#codecs()` is stored-format-safe no matter who constructed the
substrate — a caller supplying their own `Substrate` implementation, or a
bare `new InMemorySubstrate()` with no manual pin call, gets the identical
pin a harness's `.objectMapper(ObjectMapper)` path always has.

Two horror stories are why the pin exists, not a hypothetical:

- A caller mapper set to `SNAKE_CASE` naming would rename every stored
  field the moment it replaced the default — a scope's own state document
  would stop parsing on the very next read.
- A caller mapper set to `NON_EMPTY` inclusion could silently drop a field
  a recipe's own codec expects present on read — a `computation` or
  `outbox` document written under one inclusion policy and read back under
  another is exactly the kind of drift the pin exists to rule out.

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
  on the type is what `Schemas`, the substrate's `Codec`, and the tool
  executor's binding all read. A user vocabulary that skips the annotations
  gets either `Schemas`' own rejection (tool inputs — it cannot generate a
  discriminated schema without them) or Jackson's own unannotated behavior
  (stored shapes through the substrate's `Codec`).
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
CREATE TABLE IF NOT EXISTS nessy_document (
  kind        TEXT             NOT NULL,
  key         TEXT COLLATE "C" NOT NULL,
  payload     BYTEA            NOT NULL,
  version     BIGINT           NOT NULL,
  updated_at  TIMESTAMPTZ      NOT NULL,   -- stamped from a JVM Clock, never SQL now()
  PRIMARY KEY (kind, key)
);

CREATE TABLE IF NOT EXISTS nessy_journal (
  kind         TEXT             NOT NULL,
  key          TEXT COLLATE "C" NOT NULL,
  seq          BIGINT           NOT NULL,
  payload      BYTEA            NOT NULL,
  appended_at  TIMESTAMPTZ      NOT NULL,
  PRIMARY KEY (kind, key, seq)
);
```

`key` is pinned to the `"C"` collation on both tables — PostgreSQL's raw
byte-order comparison — so `keys`' ascending order matches this interface's
documented lexicographic order regardless of the database's own default
collation, rather than a locale-aware dictionary ordering that would sort
`"a"`, `"a-b"`, `"ab"`, `"B"` instead.

`updated_at`/`appended_at` are stamped from a JVM `Clock` on every write,
never SQL `now()` (spec §4): the reference in-memory substrate stamps in
the JVM too, so both implementations agree and a test can control time by
injecting a fixed clock.

`read` is a point `SELECT`. A create-mode `write` (`expectedVersion == 0`)
is an `INSERT`, where a duplicate-key error *is* the conflict. An
update-mode `write` is `UPDATE … SET version = version + 1 WHERE kind = ?
AND key = ? AND version = ?`, where a zero rowcount *is* the conflict.
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

`nessy-substrate-jdbc` ships a PostgreSQL-backed `Substrate`; its DDL is a
classpath resource the application runs through its own migration tool.
Nessy never creates or migrates schema.

## What the substrate deliberately leaves out

- **No queue primitive.** A queue is documents plus `batch` plus polling,
  not a fourth shape. The property that matters — enqueueing atomically
  with a state flip — lives in `batch` already.
- **No truncate, no TTL.** The journal is immutable truth. Retention is an
  operations concern, not a substrate one.
- **No querying into payloads.** Reporting reads the database directly if
  it must; the substrate contract stays bytes-in, bytes-out.

## Specified, not built: the summary sidecar

One piece of this design is ratified in the spec but has no code on this
branch — documented here as the intended shape, not as something you can
reach for today.

**The summarization sidecar** (`kind=summary`, future) never rewrites the
transcript. A `summary` document is meant to hold `{ text, throughSeq }`;
a working context would then be the summary plus
`entries("memory", id, throughSeq + 1)`. Re-summarizing would CAS-advance
the sidecar document; the journal underneath would never hear about it.

This does not exist in `nessy-spi` or `nessy-agent` today — treat this
section as forward-looking design, not an API reference. The outbox it was
once specified alongside now lives in Continuum's own store, not
`Substrate` — see [Durable Computation](durable-computation.md).

## Where next

- [The Tiers](the-four-tiers.md) — where `Substrate` sits as the
  substrate tier's one storage face.
- [Memory](memory.md) — the journal recipe in full, and why the transcript
  is never rewritten.
- [Durable Computation](durable-computation.md) — Continuum's half of the
  pipeline, the dispatch index, and the durability rule that binds them.
