# The Four Tiers

Nessy's runtime decomposes into four tiers, and the words are law:

> **substrate** ← **host** ← **harness** ← **binding** ← the agent.

Each tier wraps the one before it. An application usually only ever calls
into a host's builder (`Nessy.cli()`, `Nessy.autonomous()`); the other three
tiers are what that builder assembles underneath.

## Substrate — the shared durable underlay

The substrate is passive and storage-shaped: durable computation, state
storage, memory storage, backlog storage. It is **possibly shared across
many hosts** — a JDBC substrate serving ten nodes is the designed
deployment; an in-memory substrate is the degenerate single-node case of the
same idea, not a different one.

An in-memory substrate is one shared object per host, handing back **thin,
per-id views** rather than per-id copies. `InMemoryStateSubstrate` and
`InMemoryMemorySubstrate` both follow this shape — one `ConcurrentHashMap`
keyed by scope id, and `forScope(String)` returns a view that reads and
writes through to the shared map:

```java
public AgentStateStore forScope(String id) {
  return new View(id);   // a reference to the shared map, plus an id
}
```

Losing a view loses nothing; two views of the same id observe each other's
writes. Both in-memory substrates grow by one entry per distinct scope id
ever touched and never evict — a deliberate single-node, bounded-population
posture, not a durable substrate.

!!! warning "A factory MUST return a view, never fresh state"
    `id -> new InMemoryAgentStateStore()` compiles and looks identical to
    `id -> substrate.forScope(id)`, but it silently loses history on every
    delivery: each call gets a brand-new, empty store instead of a handle
    onto the id's real state. Factories handed to a harness must always
    return views over shared substrate.

## Host — one process's assembly

A host is one process's assembly around a substrate: the harnesses, the
continuation dispatcher and its registrations, the desks that resume parked
work, the executor pool, and the delivery doors. A host is born and dies
with its process.

Nessy ships two host shapes today, both built from `Nessy`'s static
builders:

```java
try (CliAgent agent = Nessy.cli().provider(provider).settings(settings).tools(new AddTool()).build()) {
  String reply = agent.converse("what is 2+2?");
}
```

```java
try (AutonomousHost host =
    Nessy.autonomous()
        .provider(provider)
        .settings(settings)
        .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
        .backend(new InMemoryDurableComputationBackend())
        .approvalNotifier(pending::add)
        .build()) {
  host.post("ops", "restart prod-1");
}
```

`Nessy.cli()` is interactive: one scope for the process, one turn at a time,
the caller's thread parks on the reply. `Nessy.autonomous()` posts
observations instead of blocking calls, and fronts tool calls that need a
human with an `ApprovalDesk`. Both doors are wiring, not new machinery —
each builds a `Harness` and hands it a `StalenessPolicy`, an `AgentObserver`,
and the factories described below.

## Harness — the recipe, compiled and id-free

A harness is the recipe made runnable: one per `AgentType`, id-free, and
immortal for the life of the host. It carries every collaborator that does
not vary by scope — the model-call and tool-call machinery (with one shared
`ObjectMapper`, never a per-scope one), the observer, the renderer, the
staleness policy — built once and never reconstructed per delivery.

> A harness is an agent with the scope left blank.

```java
public final class Harness<O> {
  public static <O> Harness<O> of(
      AgentType type,
      ObservationRenderer<O> renderer,
      AgentObserver observer,
      boolean drainOnIdle,
      StalenessPolicy stalenessPolicy,
      Function<String, Memory> memoryFactory,
      Function<String, AgentStateStore> storeFactory,
      Function<String, Backlog<O>> backlogFactory,
      Function<Binding<O>, ModelCallExecutor> modelExecutorFactory,
      Function<Binding<O>, ToolCallExecutor> toolExecutorFactory) { ... }

  public Binding<O> bind(AgentId id) { ... }
}
```

`Harness.of(...)` is the machine's own composition point, not an application
API — the host builders (`Nessy.cli()`, `Nessy.autonomous()`) are the doors
an application actually calls. Nothing about `Harness` invites building one
by hand: it takes ten positional arguments on purpose, to stay unpleasant to
hand-call.

## Binding — the scope strapped in

`harness.bind(id)` stamps a `Binding<O>`: a small value of thin handles — the
id, a `Memory`, an `AgentStateStore`, a `Backlog<O>` — built fresh, at
negligible cost, every time a scope needs one:

```java
public Binding<O> bind(AgentId id) {
  String rawId = id.value();
  return new Binding<>(
      id, memoryFactory.apply(rawId), storeFactory.apply(rawId), backlogFactory.apply(rawId));
}
```

Binding is cheap because the factories it calls are supposed to hand back
views over shared substrate (the MUST-return-views contract above), not
build new machinery. A `DefaultAgent` sits on top of one `Binding` and is
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
only outstanding call is suspended on an approval slot is not stale at all,
however long it has sat there. `StalenessPolicy.never()` is what a scope
with no automatic re-firing — the CLI's default — asks for.

## Multi-node in one sentence

Because binding is cheap and racing bindings are absorbed by the version
CAS, the same `AgentId` can be driven from any node holding the same
`AgentType`'s harness, against one shared substrate. A JDBC substrate behind
many hosts is the designed deployment; an in-memory substrate behind one
host, as in every snippet on this page, is that same design's single-node
case — not a simplified alternative to it.

## Vocabulary and prior art

Agent-as-scope is the virtual-actor model with one deliberate deviation:
`(AgentType, AgentId)` plays the role of Orleans' `(grain type, grain key)`,
and a binding is a grain activation *minus* the single-activation guarantee
— safety comes from CAS and at-least-once idempotence instead, which is
what lets any node answer. On the durable side, a slot plays the role of a
Restate/DBOS durable promise, and "host" agrees with MCP's own architecture
noun.

## Where next

- [Agent as Scope](agent-as-scope.md) — phases, transitions, and how a
  binding's `DefaultAgent` drives one.
- [Memory](memory.md) — the SPI a memory factory hands a binding.
- [Getting Started](../guides/getting-started.md) — the CLI door, end to
  end.
