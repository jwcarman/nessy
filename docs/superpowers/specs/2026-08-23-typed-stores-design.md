# Typed Stores — the substrate learns documents and journals

**Date:** 2026-08-23
**Status:** Ratified (James, in conversation, 2026-08-23: proposed the shape —
"JournalStore<T> substrate.journal(...)", "DocumentStore<T>
substrate.document(...)", "The codec factory would be at the substrate
level", "I like the ops factory idea" — and "Go when identity is merged")
**Amends:** `2026-08-21-scoped-store-design.md` (the Substrate grows typed
doors; the byte contract underneath is unchanged).
**Companion pending:** the Memory SPI reform (atomicity dropped,
remember-vocabulary) is ratified in conversation and ships as its own spec
once James names the sealed type; this reform does not touch the Memory SPI.

## 1. The rulings

1. **`DocumentStore<T>` and `JournalStore<T>`** — typed views over the
   substrate, implemented ONCE as library code. A feature holds a typed
   store and writes logic; the codec dance, the version plumbing, and the
   CAS-retry loop live in the view, not in every feature.
2. **Kind-explicit minting:** `substrate.document(kind, Class<T>)` /
   `substrate.journal(kind, Class<T>)`. The kind is a stable storage name
   given at the mint, never derived from a Java class name (a rename must
   never orphan data).
3. **The codec factory lives at the substrate level.** `Substrate` exposes
   `codecs()`; the typed doors are default methods that derive their codecs
   from it. Adapter authors stay byte-pure by extending the support base,
   which owns one pinned-mapper standard factory per substrate instance
   (statics-die law: no shared static mapper). Overriding the factory at
   substrate construction is THE codec extension point — the parked
   `.backlogCodec` seam and the per-feature codec threading both retire
   into it.
4. **The ops factory is the primary write path.** Typed views mint
   batch-composable operations (`writeOp`, `deleteOp`, `appendOp`) that
   lower to the existing batch primitives, so multi-store commits
   (fold-advance, completion) stay atomic THROUGH the typed layer. Direct
   `write`/`update` conveniences exist for single-store cases;
   `update(key, fn)` owns the read-modify-write CAS-retry loop once, for
   everyone.
5. **Total migration, one carve-out.** Every feature rebases in this
   reform — backlog, memory plumbing (internally; its SPI is untouched
   here), agent state, intents, computations/deliveries wire documents.
   After it, no feature performs a raw byte read/write against the
   substrate outside the typed layer, EXCEPT `DeliveryWorker`: rebasing the
   four-kind atomic-batch concurrency hub surfaced a real TOCTOU regression
   during this round (a decoding read widening a presence-check window in a
   racing hot path — fixed with a no-decode `exists()`), and its rebase is
   deferred to a dedicated high-risk round with Opus review rather than
   rushed here (controller ruling, 2026-08-23). That round landed and
   CLOSED the carve-out (7f2538bc): the worker's batches compose through
   op minting, byte-identical. Byte-level access otherwise remains only
   inside the views, in tests that pin wire formats, and at two named
   seq/timestamp-only sites that never touch a payload:
   `SubstrateAgentStateStore#lastSaved` (Versioned carries no timestamp)
   and `DeliveryWorker#currentMemoryHead` (needs only the head seq; the
   typed journal route would decode the full transcript per fold). A
   non-decoding head accessor on `JournalStore` would retire the second
   site — offered, not landed; new SPI surface awaits James.

## 2. Shapes

- `DocumentStore<T>`: read (value + version), write-with-expected-version,
  `update(key, fn)` with the retry loop inside, keys scan, op minting. A
  small `Versioned<T>` carrier pairs value and version (mechanical
  necessity, disclosed).
- `JournalStore<T>`: typed append (direct and as ops) and typed read of a
  scope's entries. The journal's byte contract underneath is unchanged.
- The in-memory substrate keeps its byte-round-trip enforcement; typed
  views inherit it by construction — a codec that cannot round-trip fails
  in tests exactly as before.

## 3. What retires

Per-feature codec/mapper constructor threading; every hand-rolled CAS-retry
loop; the `.backlogCodec` parked seam; the hand-written
encode/decode call sites in the computation/delivery plumbing (wire records
stay — they are the explicit Jackson shapes; the manual mapper calls go).

## 4. What does not change

The byte-level `Substrate` contract adapters implement; wire formats on
disk; execution and delivery semantics; the Memory SPI (companion spec);
test coverage meaning.
