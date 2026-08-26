# The Tiers

Nessy's runtime decomposes into three tiers, and the words are law:

> **substrate** ← **harness** ← **binding** ← the agent.

Each tier wraps the one before it. An application calls into exactly one
door, `Nessy.harness(HarnessCustomizer<O>)` (or its typed sibling); the
other two tiers are what that door assembles underneath.

**"Host" retires to meaning your process** (harness-first spec §4) — the
JVM that keeps a harness reference alive, nothing more. There used to be a
fourth tier, a host, sitting between the substrate and the harness; that
tier's machinery — the delivery worker, the approval and completion desks,
the computation scheduler's pumps — moved *into* the harness itself, so a harness is now the
recipe compiled *plus its own life-support*. Nothing application code
builds separately plays the old host's role any more.

## Substrate — the shared durable underlay

The substrate is passive and storage-shaped: durable computation, state,
memory, backlog. Its storage face is one interface, `Substrate`
(`nessy-spi`) — two shapes, documents and a journal, plus an atomic batch
across both; see [Storage](storage.md) for the full contract. It is
**possibly shared across many processes** — a JDBC-backed `Substrate`
serving ten nodes is the designed deployment; `InMemorySubstrate` is the
degenerate single-node case of the same idea, not a different one.

`Nessy.harness(h -> h.substrate(Substrate))` is the one storage seam: every
scope's state, memory (unless overridden), and backlog live as documents in
that one store. State, memory, and backlog each ride a *recipe* over it —
`SubstrateAgentStateStore`, `SubstrateMemory`, `SubstrateBacklog<O>` —
rather than a substrate of their own, so a scope's history is one shared
object's problem, not three:

```java
Function<String, Memory> memoryFactory =
    id -> new SubstrateMemory(sharedSubstrate, id, mapper);
```

Losing a recipe instance loses nothing; two recipes built over the same id
against the same substrate observe each other's writes. `InMemorySubstrate`
grows by one entry per distinct `(kind, key)` ever touched and never
evicts — a deliberate single-node, bounded-population posture, not a
durable substrate.

!!! warning "A factory MUST return a view over shared state, never fresh state"
    `id -> new SubstrateMemory(new InMemorySubstrate(), id, mapper)`
    compiles and looks identical to
    `id -> new SubstrateMemory(sharedSubstrate, id, mapper)`, but it silently
    loses history on every delivery: each call gets a fresh, empty
    substrate instead of a recipe over the id's real state. Factories handed
    to a harness must always build their recipe over one shared
    `Substrate`, never a new one per call.

## Harness — the recipe, compiled, plus its life-support

A harness is the recipe made runnable and kept alive: one per `AgentType`,
id-free, and immortal for the life of the process that holds it. It
carries every collaborator that does not vary by scope — the model-call
and tool-call machinery (with one shared `ObjectMapper`, never a per-scope
one), the observer, the renderer, the staleness policy — built once and
never reconstructed per delivery. It also owns the machinery a separate
"host" tier used to own: the delivery worker, the approval and completion
desks (`harness.approvals()`, `harness.completions()`), and the
`ComputationScheduler` driving their pumps, all daemon-threaded from the
harness's own constructor.

> A harness is an agent with the scope left blank, plus a computation
> scheduler.

```java
public final class Harness<O> {
  public static <O> Harness<O> of(
      AgentType type,
      ObservationRenderer<O> renderer,
      HarnessObserver harnessObserver,
      TurnObserver turnObserver,
      boolean drainOnIdle,
      StalenessPolicy stalenessPolicy,
      Function<String, Memory> memoryFactory,
      Function<String, AgentStateStore> storeFactory,
      Function<String, Backlog<O>> backlogFactory,
      BiFunction<AgentId, TurnObserver, ModelCallExecutor> modelExecutorFactory,
      BiFunction<AgentId, TurnObserver, ToolCallExecutor> toolExecutorFactory,
      Substrate substrate,
      ObjectMapper mapper,
      ContinuumClient<Approval, ApprovalRouting> approvalClient,
      ContinuumClient<ToolResult, Routing> toolClient,
      ConcurrentMap<AgentId, CompletableFuture<TurnOutcome.Parked>> approvalWaiters,
      ObservationRegistry observationRegistry,
      ConcurrentMap<AgentId, Observation> openSegments,
      ExecutorService ownedExecutor) { ... }

  public Agent<O> bind(AgentId id) { ... }
  public ApprovalDesk approvals() { ... }
  public CompletionDesk completions() { ... }
  public void shutdown() { ... }
}
```

`Harness.of(...)` is the machine's own composition point, not an
application API — `Nessy.harness(...)` is the door an application actually
calls, and it alone turns a filled-in `HarnessConfig` into the `Harness` it
describes. Nothing about `Harness.of(...)` invites building one by hand: it
takes sixteen positional arguments on purpose, to stay unpleasant to
hand-call. There is no `DispatchIndex` parameter — a call's status lives in
the phase itself (see [Storage](storage.md) and [Durable
Computation](durable-computation.md)); `approvalWaiters` is the map
`Agent#ask` registers its per-id waiter in, resolved by the scope's own
`ApprovalDeferred` fold.

The harness is kept, not closed. It is not `AutoCloseable`; `shutdown()` is
the one undecorated lifecycle method, and it exists for infrastructure —
a container's destroy callback, a test's teardown — never application
hygiene. See [the harness guide](../guides/harness.md) for the full
builder surface.

## Binding — the scope strapped in

`harness.bind(id)` hands back an `Agent<O>` — a small, transient object
wrapping the id-specific handles (`Memory`, `AgentStateStore`, `Backlog<O>`)
the harness's factories produce, built fresh, at negligible cost, every
time a scope needs one. The raw handle those factories fill in, `Binding`,
never crosses the public API — it's internal wiring the harness threads
through `DefaultAgent`.

Binding is cheap because the factories it calls are supposed to hand back
views over shared substrate (the MUST-return-views contract above), not
build new machinery. A `DefaultAgent` sits on top of one binding and is
itself transient — see [Agent as Scope](agent-as-scope.md) for what that
buys the crash-recovery story.

Binding is deliberately not called *activation*. The virtual-actor word
promises at-most-one live instance; Nessy promises no such thing — the
store's version CAS is the only lock, and racing bindings over the same id
are legal and absorbed, not prevented.

## StalenessPolicy: phase-aware, clock-owning

Recovery needs a judgment: is a quiet phase dead enough to re-fire, or is it
quiet on purpose? `StalenessPolicy` is that judgment, named:

```java
@FunctionalInterface
public interface StalenessPolicy {
  boolean isStale(Phase phase, Instant lastSaved);

  static StalenessPolicy after(Duration threshold) { ... }
  static StalenessPolicy after(Duration threshold, Clock clock) { ... }
  static StalenessPolicy never() { ... }
}
```

The `Clock` lives inside the policy, not the shell — the policy owns time.
Phase-awareness is the point of the interface, not an afterthought: an
implementation may inspect `phase` and decide that a scope quiet because its
only outstanding call is suspended on an approval computation is not stale
at all, however long it has sat there. `StalenessPolicy.never()` is a scope
with no automatic re-firing at all.

## Multi-node in one sentence

Because binding is cheap and racing bindings are absorbed by the version
CAS, the same `AgentId` can be driven from any process holding a harness of
the same `AgentType`, against one shared substrate. A JDBC substrate behind
many processes is the designed deployment; an in-memory substrate behind
one process, as in every snippet on this page, is that same design's
single-node case — not a simplified alternative to it. Each harness's
worker's pumps sweep only their own type's records (harness-first spec
§5): a delivery's routing data and a call's dispatch-index key both
carry `agentType`, so different types sharing one substrate never touch
each other's records, and same type ⇒ same harness ⇒ one in-JVM claim
covering racing drains.

## Vocabulary and prior art

Agent-as-scope is the virtual-actor model with one deliberate deviation:
`(AgentType, AgentId)` plays the role of Orleans' `(grain type, grain key)`,
and a binding is a grain activation *minus* the single-activation guarantee
— safety comes from CAS and at-least-once idempotence instead, which is
what lets any node answer. On the durable side, a pending computation plays
the role of a Restate/DBOS durable promise. MCP's own architecture uses
"host" for the process-level assembly noun too — the word survives here
with exactly that meaning, now that the harness itself carries the
machinery a Nessy host used to.

## Where next

- [Agent as Scope](agent-as-scope.md) — phases, transitions, and how a
  binding's `DefaultAgent` drives one.
- [Storage](storage.md) — `Substrate`'s full contract, the kinds table,
  and the recipes built over it.
- [Memory](memory.md) — the SPI a memory factory hands a binding.
- [Getting Started](../guides/getting-started.md) — the harness door, end
  to end.
