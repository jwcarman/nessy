# Remembrance — memory learns to be anyone's

**Date:** 2026-08-23
**Status:** Ratified (James, in conversation, 2026-08-23: the escape-hatch
question → "Do we absolutely need memory to be atomically consistent?" →
the remember-verbs → the sealed-vocabulary consistency ruling → named
"Remembrance")
**Amends:** `2026-08-22-durable-deliveries-design.md` (memory leaves the
fold-advance batch) and the typed-stores spec §1.5 (the seq-coordination
raw site this makes moot). Companion to the computation-identity spec
(whose opaque ids provide every idempotency key used here).

## 1. The ruling: memory is not atomically consistent, by design

Rolling your own memory must be a first-class door, including memory in a
genuinely foreign store (vector DB, Redis, your own schema) — which can
never join a substrate batch. Interrogating the atomicity requirement
showed it was never load-bearing; three laws replace it:

1. **Append before commit.** The worker remembers the turn BEFORE the
   commit batch (state CAS + delivery delete). A crash between the two
   leaves the delivery pending; the redrive re-remembers.
2. **Remember is idempotent by turn identity.** Every `Remembrance`
   carries its opaque turn key; remembering the same key twice converges
   to one remembered fact. At-least-once execution, exactly-once effect —
   the same move deliveries made.
3. **Memory-ahead is benign.** Between append and commit, memory holds a
   fact state hasn't committed. A delivery, once created, is a fact that
   WILL fold (deliveries cannot abort; the fold is pure and
   CAS-converges), so a concurrent recall sees a true fact slightly early.
   Documented, tolerated.

A throwing `remember` (foreign store down) fails the delivery attempt;
the delivery stays pending and retries later — natural backpressure, not a
torn commit.

## 2. The vocabulary

```java
public sealed interface Remembrance {
  String key();   // the opaque turn identity — the idempotency key

  record UserMessage(String key, ...)      implements Remembrance {}
  record AssistantMessage(String key, ...) implements Remembrance {}
  record ToolExchange(String key, ...)     implements Remembrance {}
}
```

- The members map one-to-one onto fold moments, each arriving with its
  natural key already minted: an observation delivery folds →
  `UserMessage` (delivery identity); a model turn folds →
  `AssistantMessage` (the committed responseId); a tool delivery folds →
  `ToolExchange` (the execution computation id).
- **The pairing invariant lives in the record**: `ToolExchange` carries
  the full `ToolCall` and its `ToolResult` together — the continuation
  makes this possible even when a result folds days after its call. One
  exchange per callId under parallel calls.
- Benign redundancy is deliberate: the assistant message already contains
  the `tool_use` blocks and the exchange carries the call again — a
  memory can treat each remembrance as self-contained.
- The sealed grammar is the house etiquette (`TurnEvent`, `ToolEvent`,
  `Outcome`…): implementors switch exhaustively, no `default` arm; a new
  member breaks every custom memory loudly at compile time. A remembrance
  is data — storable, loggable, replayable — the same vocabulary a fact
  journal or trajectory view would fold over.

## 3. The SPI

`Memory` = `Context recall()` + `void remember(Remembrance r)`. The old
append shape dies. `recall()` owns provider-legal reassembly (assistant
message with its tool_use blocks, then the results, in order).

## 4. What dies

`requirePlainSubstrateMemory`, `SubstrateMemory#writesPlainlyTo`, the
worker's knowledge of any memory's write recipe, memory's ops in the
fold-advance batch, and the seq-coordination machinery that composed them
(`currentMemoryHead` — which also retires the second raw-access site the
typed-stores spec names, mooting the offered JournalStore head accessor).
The commit batch shrinks to [state CAS, delivery delete].

## 5. Conformance

`nessy-testing` ships `MemoryContractTest` (or equivalent runnable
harness): idempotent re-remember converges; ordering of recall respects
remember order; the pairing invariant; tolerance of re-delivery. Any
third-party memory can run it. `SubstrateMemory` rebases onto the new SPI
(it may use its own internal substrate batch to achieve idempotency — the
CONTRACT is convergence, not a mechanism) and passes the same contract
test.

## 6. What does not change

Delivery semantics and identity; the §5a gate; recall feeding the model;
wire compatibility of already-stored transcripts (SubstrateMemory keeps
reading what it wrote before this reform).
