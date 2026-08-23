# Agent as Scope

There is no `Conversation` type in Nessy. There is an agent — a recipe —
bound to an identity, and its state is a small, explicit phase.

## The thesis

An agent instance is an [`AgentType`](../concepts/the-four-tiers.md) — system
prompt, tools, model, wiring — bound to an `AgentId`. The id is the scope:
memory, state, and backlog are all keyed by it, and nothing in the machine
ever takes an id as a method parameter. The instance *is* the scope.

Three rules carry the design:

1. Deciding what happens next touches nothing outside its arguments.
2. History belongs to `Memory`. The machine never owns, stores, or
   reconstructs what was said.
3. The instance is the scope — no core-facing method takes an `AgentId`.

`AgentType` and `AgentId` are both plain records:

```java
public record AgentType(String name) { }
public record AgentId(String value) { }
```

The type is code — every node running the same build can rebuild the
instance from it. The id is data — it lives in stores and durable addresses.
Attaching to an id from any node works because that node already holds the
type half and is handed the id half.

## Phases: state that carries its own data

A scope's state is a `Phase`, and phases carry their own data rather than
deriving status from a side collection:

```java
public sealed interface Phase {
  record Idle() implements Phase { }
  record AwaitingModel() implements Phase { }
  record AwaitingTools(Message assistantTurn,
                        Set<String> pending,
                        List<ToolResultBlock> gathered) implements Phase { }
}
```

`AwaitingTools` is the one phase with real weight: it holds the assistant's
tool-use turn back, uncommitted, and accumulates tool results as they land.
A model turn carrying tool calls and the results answering it are one
indivisible unit — most providers require the `tool_use` turn to be followed
immediately by a turn carrying *all* of its results, so committing the
assistant turn early would leave `Memory` holding a dangling call with no
answer. Only when `pending` empties does the phase commit both the assistant
turn and the results together, in the same step.

Two things fall out of this design:

- **"Idle with an outstanding call" is unrepresentable**, not just avoided.
  Pending tool calls exist nowhere except inside `AwaitingTools`'s own field.
- **A completion is correlated by tool-call id, never by comparing whole
  calls.** Keying by id also makes redelivery idempotent for free: a
  duplicate `ToolFinished` for an id no longer in `pending` is ignored —
  dedup, not defensive coding.

## The transition: decide, commit, save, dispatch

Handling one event is four steps, always in this order:

1. **Decide** — `phase.handle(event)` is a pure function returning a
   `Transition`: the next phase, the messages to commit, the effects to
   fire. Nothing here touches memory, a store, or the network.
2. **Commit** — the transition's messages are written to `Memory` *before*
   the new phase is saved. Committing is synchronous and produces no event
   of its own; there is deliberately no `Remember` effect in the grammar.
3. **Save (CAS)** — the new `Phase` is written to the `AgentStateStore` at
   the version it was loaded at. A version mismatch throws
   `StaleStateException` rather than overwriting a sibling's progress.
4. **Dispatch** — only after the save succeeds are the transition's effects
   (`CallModel`, `ExecuteTool`) handed to their executors.

`DefaultAgent#applyOnce` is this loop, almost verbatim:

```java
private void applyOnce(State state, AgentEvent event) {
  Transition t = state.phase().handle(event);       // decide before committing
  if (t.isIgnored()) { ... return; }
  remember(state.phase(), event, t);                // remember before commit
  binding.store().save(new State(t.next(), state.version()));
  t.effects().forEach(this::dispatch);
}
```

`remember` maps the event and its transition onto the `Remembrance` the
fold implies — a `UserMessage`, an `AssistantMessage`, or a `ToolExchange`
(remembrance spec §2) — and hands it to `binding.memory()`. Memory is not
part of any atomic batch here: it is simply the first write, ahead of the
state save, per `Memory`'s own append-before-commit law. See
[Memory](memory.md) for the full vocabulary and the three laws that govern
it.

Save-before-dispatch is what makes the crash story clean: a phase saved to
the store never *overstates* progress, because nothing dispatches until the
save has already committed.

## The store is the lock

There is no claim flag, no continuation queue, no scheduled trampoline
anywhere in the shell. Two rules do the whole job:

- **Executors are always asynchronous.** A `Sink` is never invoked on the
  stack that dispatched the effect — completion arrives back on the
  executor's own thread, a virtual thread wrapping a blocking call being the
  normal shape.
- **The version CAS is the only serialization.** Two racing applies — a
  fan-out's sibling tool results, or two nodes driving the same scope — are
  resolved by `save` at an expected version. The loser's `handle` was wasted
  work; it reloads and re-handles against what actually landed.

`deliver` is the continuation door executors hold a reference to, and it is
exactly a retry loop around that CAS:

```java
void deliver(AgentEvent event) {
  while (true) {
    try {
      applyOnce(event);
      return;
    } catch (StaleStateException _) {
      // another writer advanced the scope — re-handle against what it left behind
    } catch (RuntimeException e) {
      harness.observer().applyFailed(event, e);
      return;
    }
  }
}
```

## Recovery is `drive()`'s second arm

`drive()` is "make this scope make progress." When the phase is `Idle` it
drains the backlog. When it isn't, one of two things is true: a turn is
genuinely running, or the process that started it is gone.

```java
public void drive() {
  State state = binding.store().load();
  if (state.phase() instanceof Phase.Idle) { drain(); return; }
  if (isStale(state)) {
    List<Effect> outstanding = state.phase().outstandingEffects();
    outstanding.forEach(this::dispatch);   // the re-fire arm
  }
}
```

Every phase can re-derive its own outstanding effects — `AwaitingModel`
implies a bare `CallModel`, `AwaitingTools` re-derives one `ExecuteTool` per
still-pending id from the calls held in `assistantTurn`. Nothing about
recovery is a separate code path; it is `drive()` reading the same phase
data a fresh delivery would have read. Whether a phase counts as stale is a
`StalenessPolicy` judgment — see [The Four Tiers](the-four-tiers.md) for how
that policy stays phase-aware.

## Transient instances

`DefaultAgent` holds no state field of its own beyond its harness and
binding — `apply` loads, handles, and saves every time, and the store is the
only authority. That is deliberate: an instance that might die between
requests should never have trusted an in-memory field anyway. Building a new
`DefaultAgent` over the same scope is cheap and safe, which is exactly what
lets a parked tool call resume the turn from a completely different process:

```java
var agent = new DefaultAgent<>(harness, harness.bind(AgentId.of("demo-scope")));
```

Nothing here is a singleton, a session, or a cache entry — it's a value
built fresh on demand, over durable state that outlives it.

## Where next

- [The Four Tiers](the-four-tiers.md) — how a harness, a binding, and a
  shared substrate compose into the instances above.
- [Memory](memory.md) — what `Memory.remember`/`recall` actually store and
  what "the memory owns history" means for a model call.
- [Getting Started](../guides/getting-started.md) — the smallest agent that
  runs.
