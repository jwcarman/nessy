# Memory

`Memory` is the SPI that owns a scope's history. The machine never stores or
reconstructs what was said — it only ever writes through `Memory` and reads
through it.

## The interface

`Memory` lives in `nessy-spi`, and it is deliberately small:

```java
public interface Memory {
  void remember(Message message);

  Context recall();
}
```

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
a provider without a separate validation pass.

## VerbatimMemory: the cli() default

`VerbatimMemory` remembers everything, in order, and forgets nothing:

```java
public final class VerbatimMemory implements Memory {
  private final List<Message> messages = new ArrayList<>();

  public synchronized void remember(Message message) { messages.add(message); }

  public synchronized Context recall() { return Context.of(List.copyOf(messages)); }
}
```

It is synchronized because completions arrive on executor threads while the
shell commits on others — a synchronized list is entirely adequate at
conversation cadence. `Nessy.cli()` uses one of these per scope by default.

## The in-memory substrates

A single `VerbatimMemory` is one scope's history. `InMemoryMemorySubstrate`
is the shared underlay behind *many* scopes' history in one process — the
shape a host's `memoryFactory` actually wants when more than one id will
ever be bound:

```java
public final class InMemoryMemorySubstrate {
  public Memory forScope(String id) { return new View(id); }
}
```

`forScope(id)` returns a thin view onto one entry of a shared
`ConcurrentHashMap<String, List<Message>>` — a reference plus an id, never a
copy. Two views of the same id observe each other's writes; losing a view
loses nothing. This is the pattern every substrate in Nessy follows — see
[The Four Tiers](the-four-tiers.md) for the general shape and the
MUST-return-views contract a memory factory has to honor.

!!! note "Single-node, bounded population"
    Both in-memory substrates grow by one entry per distinct scope id ever
    touched and never evict. That is a deliberate posture for a single-node
    substrate, not an oversight — a durable substrate (JDBC or another
    backend) is what a production deployment reaches for instead.

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

- **The transition decides what gets committed**; the shell writes it to
  `Memory` before saving the next phase (`Memory.remember`), never after.
- **The executor asks `Memory` for context at the moment it dispatches**,
  not before — so a model call always sees everything committed up to that
  point, and nothing more.
- **Provider continuity tokens ride the content blocks Memory replays.**
  `ToolUseBlock`'s signature field and `ThinkingBlock` carry opaque
  provider-issued signatures (Gemini's thought signatures, Anthropic's
  thinking signatures) that must come back verbatim on the next request.
  Because the held-back assistant turn is built from the response's actual
  content blocks, and `Memory` stores and replays those blocks unchanged,
  the signatures survive across turns without the model executor doing
  anything special.

## Where next

- [Agent as Scope](agent-as-scope.md) — how a transition's `commit` list
  reaches `Memory.remember` in the decide-commit-save-dispatch order.
- [The Four Tiers](the-four-tiers.md) — substrates, views, and the
  MUST-return-views contract a `memoryFactory` has to honor.
- [Getting Started](../guides/getting-started.md) — the CLI door and its
  default `VerbatimMemory`.
