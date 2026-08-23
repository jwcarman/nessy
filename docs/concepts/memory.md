# Memory

`Memory` is the SPI that owns a scope's history. The machine never stores or
reconstructs what was said — it only ever writes through `Memory` and reads
through it. Memory is **not** part of the fold's own atomic commit (the
escape-hatch ruling, remembrance spec §1): a genuinely foreign store — a
vector DB, Redis, a bespoke schema — can never join a substrate batch, so
rolling your own memory is a first-class door, not a second-class one bolted
on around an atomicity requirement that turned out not to be load-bearing.

## The interface

`Memory` lives in `nessy-spi`, and it is deliberately small:

```java
public interface Memory {
  void remember(Remembrance remembrance);

  Context recall();
}
```

`Remembrance` is the sealed vocabulary one `remember` call carries — three
members, each arriving with its own opaque turn-identity key already minted
at the fold site:

```java
public sealed interface Remembrance {
  String key();

  record UserMessage(String key, Message message) implements Remembrance {}
  record AssistantMessage(String key, Message message) implements Remembrance {}
  record ToolExchange(String key, ToolCall call, ToolResult result) implements Remembrance {}
}
```

- An observation folds into a `UserMessage`.
- A model turn folds into an `AssistantMessage`, keyed by its committed
  `ModelResponseId` — whether or not it carries `tool_use` blocks. When it
  does, its `ToolExchange`s may remember *after* it does (the exchange for a
  call that finishes before its siblings is remembered the moment it
  finishes; the assistant message itself is remembered only once every
  sibling has answered) — `recall()` withholds the assistant message until
  every one of its call ids has a matching exchange, however the two arrive.
- A completed tool call folds into a `ToolExchange` — the call and its
  result, paired, never split — keyed by its execution `ComputationId`. One
  exchange per call id, even when the result folds long after the call: the
  continuation carries everything a memory needs to answer it, whenever it
  arrives.

`Memory` is **pre-scoped**: an implementation you receive is already one
scope's memory, never a shared store you index by id yourself. A harness's
`memoryFactory` hands `Binding#bind` a `Memory` for one raw id, and nothing
downstream of that point ever passes an id back in. A no-op `remember` is
legal — a `Memory` that discards everything is a valid implementation, not a
bug.

`recall()` returns a `Context`: a wire-safe slice of the conversation that
enforces the tool-pairing invariant on construction — every assistant
message carrying `ToolUseBlock`s is immediately followed by a user message
whose `ToolResultBlock`s answer exactly that set of ids. A `Context` cannot
be built any other way, which is what lets `recall()` be handed straight to
a provider without a separate validation pass. `recall()` owns the
provider-legal reassembly: an `AssistantMessage` carrying `tool_use` blocks
is held until every one of those call ids has a matching `ToolExchange`
remembered somewhere (arrival order between the two is not guaranteed), at
which point the assistant message and the results message it pairs with
both emit together.

## Three laws, not one atomicity requirement

Interrogating "does memory have to be atomically consistent with the fold?"
showed the answer was never load-bearing. Three laws replace it:

1. **Append before commit — the caller's law.** Whoever folds a
   transition remembers every `Remembrance` the fold implies *before*
   committing its own state. A `remember` that throws aborts the attempt
   before anything commits — but what "stays pending" means differs by
   caller: the durable, outbox-driven fold (`DeliveryWorker`) leaves the
   delivery undeleted, and the next heartbeat (or `nudge()`) redrives it —
   at-least-once, no caller-visible failure. The non-durable shell fold
   (`DefaultAgent`) re-queues the observation onto its own backlog and lets
   the exception surface to whoever called `tell()` — there is no
   heartbeat to redrive it silently, so the caller sees the failure and
   decides whether to retry. Either way, the work this attempt would have
   committed is preserved, not lost.
2. **Remember is idempotent by turn identity — the implementor's law.**
   Every `Remembrance` carries its own opaque `key()`; remembering the same
   key twice must converge to one remembered fact, and `recall()` must
   return messages in the order they were first remembered — except an
   `AssistantMessage` naming `tool_use` call ids, which withholds until
   every one of them has a matching `ToolExchange` remembered somewhere,
   then emits together with the results message it pairs with. At-least-once
   execution, exactly-once effect — the same move deliveries already made.
3. **Memory-ahead is benign.** Between a caller's own remember and its own
   commit, a memory may hold a fact the caller has not yet committed
   elsewhere. A concurrent `recall()` may see that fact slightly early —
   tolerated, since a delivery, once created, is a fact that *will* fold
   (deliveries cannot abort; the fold is pure and CAS-converges).

Any `Memory` that honors these three laws is first-class, substrate-backed
or not. `nessy-testing` ships `MemoryContractTest`, a runnable conformance
suite any implementation — including one living outside this repository —
extends directly to prove it honors them.

## VerbatimMemory: the cli() default

`VerbatimMemory` remembers every `Remembrance`, deduplicated by key, in
first-remembered order:

```java
public final class VerbatimMemory implements Memory {
  private final Map<String, Remembrance> remembered = new LinkedHashMap<>();

  public synchronized void remember(Remembrance r) { remembered.putIfAbsent(r.key(), r); }

  public synchronized Context recall() { /* reassembles the LinkedHashMap's values */ }
}
```

It is synchronized because completions arrive on executor threads while the
shell commits on others — a synchronized map is entirely adequate at
conversation cadence. `Nessy.cli()` uses one of these per scope by default.

## SubstrateMemory: the journal recipe

A single `VerbatimMemory` is one scope's history, held in a Java map.
`SubstrateMemory` is the shape a host's `memoryFactory` reaches for once more
than one scope needs to persist: a recipe over
[`Substrate`](storage.md), `kind=memory`, one journal per scope, **one
entry per `Remembrance`**. Idempotence is one create-only marker document
*per remembered key* (`kind=memory-keys`, key = `agentId + "/" +
remembrance.key()`), CAS-written in the *same* substrate batch as the
journal append it guards — never a single per-scope list that would grow
and get rewritten on every call. The marker create succeeding IS "not yet
remembered"; a conflict on that exact create IS "already remembered",
which is how re-remembering the same key converges to one fact, in O(1),
with no read before the write. A lost race on the guarding batch — genuine,
near-zero in practice since a scope's own fold is already serialized
upstream — re-reads the journal's raw head and retries; `remember` never
decodes the transcript to find that head, so a caller-supplied codec's
decode failures stay confined to `recall()`.

```java
public final class SubstrateMemory implements Memory {
  public void remember(Remembrance r) { /* CAS-guarded append; no-op if r.key() is already known */ }
  public Context recall() { /* fold every entry from seq 1 forward, reassembling pairs */ }
}
```

**The journal is never rewritten**: there is no update, no delete, and no
truncate operation on it anywhere in `Substrate` — a transcript only ever
grows.

Two views built over the same shared store observe each other's writes;
losing one loses nothing. See [The Four Tiers](the-four-tiers.md) for the
general shape and the MUST-return-views contract a memory factory has to
honor, and [Storage](storage.md) for the substrate `SubstrateMemory` rides.

!!! note "Wire compatibility"
    A transcript written before the remembrance reform is a bare `Message`
    per journal entry, with no `"type"` discriminator on the wire.
    `SubstrateMemory` still reads those entries — recognizing the absent
    discriminator — and emits them verbatim in `recall()`, unchanged,
    alongside whatever `Remembrance`-shaped entries it writes from here on.

!!! note "Single-node by construction, not a durable substrate on its own"
    `InMemorySubstrate` grows by one entry per distinct `(kind, key)`
    ever touched and never evicts. That is a deliberate posture for the
    reference substrate, not an oversight — a durable `Substrate` (JDBC
    or another backend) is what a production deployment supplies through
    `.substrate(...)` instead.

## What "the memory owns history" means for a model call

`ProviderModelCallExecutor` is the bridge from the machine to a
`ModelProvider`, and it never assembles context from anything but `Memory`:

```java
ModelRequest request = new ModelRequest(memory.recall(), ...);
```

There is no second path by which conversation content reaches the model.
`Effect.CallModel` is a bare marker with no payload, on purpose — putting an
assembled `Context` in the effect would let two disagreeing views of history
exist at once, one riding the effect and one living in memory. Instead:

- **The fold decides what gets remembered**; the caller remembers it
  *before* committing the next phase — never after (law 1, above).
- **The executor asks `Memory` for context at the moment it dispatches**,
  not before — so a model call always sees everything remembered up to that
  point, and nothing more (modulo the memory-ahead law).
- **Provider continuity tokens ride the content blocks Memory replays.**
  `ToolUseBlock`'s signature field and `ThinkingBlock` carry opaque
  provider-issued signatures (Gemini's thought signatures, Anthropic's
  thinking signatures) that must come back verbatim on the next request.
  Because a remembered `AssistantMessage` is built from the response's
  actual content blocks, and `Memory` stores and replays those blocks
  unchanged, the signatures survive across turns without the model executor
  doing anything special.

## Backpressure, not corruption

A `Memory` backed by a foreign store that goes down does not corrupt a
scope's history or fold a turn twice: `remember` throws, and the caller's
attempt aborts before its own commit. What happens next depends on which
caller: the durable, outbox-driven fold leaves the delivery exactly where
it was, waiting for the next heartbeat or the next `nudge()` — no exception
escapes to anything outside the worker. The non-durable shell fold instead
re-queues the observation onto its own backlog and lets the exception
surface to whoever called `tell()` — there is no heartbeat backing that
path, so the caller finds out and decides whether to retry. Either way,
once the foreign store recovers, the same at-least-once retry that already
governs delivery redrive (or the caller's own next `tell()`/`drive()`)
carries memory along for free: the same keys, remembered again, converge to
the same facts.

## Where next

- [Agent as Scope](agent-as-scope.md) — how a transition's `commit` list
  becomes the `Remembrance`s a fold remembers, in the decide-remember-save-
  dispatch order.
- [The Four Tiers](the-four-tiers.md) — substrates, views, and the
  MUST-return-views contract a `memoryFactory` has to honor.
- [Storage](storage.md) — the substrate `SubstrateMemory` rides, and why
  `memory` is a reserved journal kind.
- [Getting Started](../guides/getting-started.md) — the CLI door and its
  default `VerbatimMemory`.
