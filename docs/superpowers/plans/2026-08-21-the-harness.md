# The Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute spec §10.11 — the four tiers. `StalenessPolicy` replaces the clock+threshold knobs; in-memory storage becomes shared substrate with thin per-id handles; the `Harness` (per-type, id-free, compiled once) and `Binding` (per-scope, stamped by `bind(id)`) replace `AgentWiring`, which dies as a public surface; the autonomous host's per-scope cache is deleted.

**Architecture:** Three code tasks in dependency order: the staleness seam first (self-contained), the substrate reshape second (makes bindings free to discard), the harness/binding surgery third (consumes both). A docs task closes.

**Tech Stack:** Java 25, Maven; hand-rolled fakes (`PumpedExecutor`, `ScriptedModelProvider`, `RecordingTurnObserver`); no mocking libraries.

**Spec:** `docs/superpowers/specs/2026-08-18-agent-as-scope-design.md` §10.11 (the binding text), plus §3.2 (CAS is the only lock), §3.5 (pre-scoped), §6.1 (staleness arm), §7 (hosts).

## Global Constraints

- No `@SuppressWarnings`, no star imports, no mocking libraries; camelCase prose test names; S5778; S5841.
- No public `build(...)`/`builder(...)` in modules `NoPublicBuildersTest` scans (nessy-agent is NOT scanned — its CliBuilder/AutonomousBuilder pattern stands).
- Behavior invariants that must survive unchanged: commit-before-dispatch; CAS-only locking; suspension invisible; denial in-band; redispatch re-fires ExecuteTool only with Idle short-circuit; drainOnIdle semantics; ToolCallId dedup. Every existing demo (`DurableParkDemo`, `AutonomousApprovalDemo`, `GovernedTurnDemo`, `WiringDemo`) must still pass with identical assertions (fixture wiring may change; asserted values may not).
- **The factory contract change is normative (Task 2/3):** per-scope factories are invoked per binding and MUST return views of durable state, never fresh state. Javadoc it on every factory seam.
- Before every commit: `./mvnw license:format -Plicense -q` then `./mvnw spotless:apply -q`. Full `./mvnw -q clean verify` green (no API key, no network) per task; the four `*Demo` classes run under surefire and must stay green.

---

### Task 1: `StalenessPolicy` — the clock leaves the machine

**Files:**
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/StalenessPolicy.java`
- Modify: `nessy-agent/.../agent/AgentWiring.java` (components `staleThreshold`+`clock` → one `stalenessPolicy`), `DefaultAgent.java` (`isStale()` delegates), `host/Nessy.java` (CLI → `StalenessPolicy.never()`; autonomous → `after(Duration.ofMinutes(5))`, builder setter `staleness(StalenessPolicy)` replacing `staleThreshold(...)`), all `AgentWiring` construction sites (demos/tests)
- Test: `StalenessPolicyTest` (create), touched fixtures

**Shape (normative):**

```java
@FunctionalInterface
public interface StalenessPolicy {
  /** §6.1's judgment, named: is this quiet phase dead enough to re-fire? */
  boolean isStale(Phase phase, Instant lastSaved);

  static StalenessPolicy after(Duration threshold) { return after(threshold, Clock.systemUTC()); }
  static StalenessPolicy after(Duration threshold, Clock clock) { /* guards; compare Duration.between(lastSaved, clock.instant()) >= threshold */ }
  static StalenessPolicy never() { return (phase, lastSaved) -> false; }
}
```

`DefaultAgent.drive()`'s stale arm calls `wiring.stalenessPolicy().isStale(state.phase(), wiring.store().lastSaved())`. Delete the `clock`/`staleThreshold` components and every `Clock.systemUTC()`/`Duration.ofMinutes(5)` ceremony at construction sites (test fixtures that used a fixed clock use `after(threshold, fixedClock)`).

Tests: `after` boundary (exactly-at-threshold is stale — `>=`), `never()` never, phase is passed through (a policy asserting on the phase it receives). Commit: `feat: the clock leaves the machine — StalenessPolicy names the §6.1 judgment`.

---

### Task 2: The substrate — shared stores, thin handles

**Files:**
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/store/InMemoryStateSubstrate.java`, `.../memory/InMemoryMemorySubstrate.java`, `.../backlog/InMemoryBacklogSubstrate.java`
- Modify: `host/Nessy.java` (autonomous defaults use substrates; factory javadocs carry the views-of-durable-state contract)
- Test: one substrate test per class (create)

**Shape (normative):** each substrate is one shared, thread-safe object; `forScope(String id)` returns a **thin view** (handle) — two fields, no state of its own; two views of the same id observe each other's writes; views of different ids are isolated.

```java
public final class InMemoryStateSubstrate {
  public AgentStateStore forScope(String id) { /* view over one shared ConcurrentHashMap<String, Slot>;
      Slot holds phase-json/version/lastSaved; CAS semantics identical to InMemoryAgentStateStore */ }
}
public final class InMemoryMemorySubstrate {
  public Memory forScope(String id) { /* view over shared per-id message lists; remember/recall semantics identical to VerbatimMemory */ }
}
public final class InMemoryBacklogSubstrate {
  public InMemoryBacklogSubstrate(int capacity) { ... }
  public Backlog<String> forScope(String id) { /* per-id bounded deque in the shared map; rejection identical to BoundedBacklog */ }
}
```

`InMemoryAgentStateStore`, `VerbatimMemory`, `BoundedBacklog` remain (single-scope uses: CLI, unit fixtures) — the substrates are additive. Autonomous defaults become substrate-backed: `memoryFactory` default `substrate::forScope` etc. Tests must prove: same-id-two-views share; different-ids isolate; the CAS/bounded/recall semantics match the single-scope classes (reuse their test patterns). Commit: `feat: the substrate — shared stores, thin handles, nothing lost with a view`.

---

### Task 3: Harness and Binding — the wiring dies

**Files:**
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/Harness.java`, `Binding.java`
- Modify: `DefaultAgent.java` (ctor `(Harness<O>, Binding<O>)`), `host/Nessy.java` (builders compile a Harness; `AutonomousHost` drops the wiring cache entirely — resolver = `harness.bind(id)` per delivery), `tool/RegistryToolCallExecutor.java` (shared static ObjectMapper — one per class, not per instance), demos/tests (all `AgentWiring` sites), `WiringDemo` → rename `HarnessDemo` (it demos composition)
- Delete: `AgentWiring.java`
- Test: `HarnessTest` (bind stamps distinct handles per id; shared parts are the same references across bindings), reworked demos

**Shape (normative):**

```java
/** The recipe compiled (§10.11): one per AgentType, id-free, immortal. A harness is an agent with the scope left blank. */
public final class Harness<O> {
  // fields: type, renderer, observer, turnObserver?, model-call guts, tool-call guts,
  // drainOnIdle, stalenessPolicy, plus the per-scope factories (memory/store/backlog)
  public AgentType type();
  public Binding<O> bind(AgentId id) { /* stamps handles via the factories; thin, no I/O */ }
}

/** The scope strapped in (§10.11): thin handles, stamped fresh per delivery. */
public record Binding<O>(AgentId id, Memory memory, AgentStateStore store, Backlog<O> backlog) { /* requireNonNull */ }

// DefaultAgent
public DefaultAgent(Harness<O> harness, Binding<O> binding) { ... }
```

Executor construction: the tool/model executors are per-binding today because they hold the id/memory — restructure so the id-free guts (registry, turn, pool, policy, approver, THE ONE SHARED MAPPER) live once in the Harness and `bind(id)` produces the per-scope executor objects cheaply (they are small; the mapper is the only heavy part and it becomes `private static final`). Exact decomposition is implementer's judgment within one rule: **nothing heavier than a plain field-holding object is constructed per bind**.

`AutonomousHost`: delete `ConcurrentMap<AgentId, AgentWiring>` — `agentFor(id)` = `new DefaultAgent<>(harness, harness.bind(id))` every call; the substrate (Task 2) is what makes this correct. The unbounded-cache javadoc paragraph dies with the cache. `CliAgent` holds its one binding for its lifetime — construction otherwise unchanged.

All four demos keep their exact assertions; their fixtures compose a Harness (via builders where possible, direct construction where the demo needs raw seams). Commit: `feat: the harness binds the scope — AgentWiring dies, the cache with it`.

---

### Task 4: The paper trail

README (autonomous snippet: `storeFactory`/`memoryFactory` lines become substrate-backed defaults or show `.staleness(...)` if the snippet named the old knobs — verify against real builder surface), CHANGELOG entry, `docs/index.md` sample if stale. Prose only; never touch docs/superpowers. Commit: `docs: the four tiers reach the paper trail`.

---

## Model policy

| Task | Implementer | Review |
|---|---|---|
| 1 | Sonnet | Sonnet |
| 2 | Sonnet | Sonnet (concurrency in substrate — pointed instructions; escalate to Opus if the diff surprises) |
| 3 | Sonnet | **Opus** (machine surgery: DefaultAgent ctor, executor decomposition, cache deletion) |
| 4 | docs-writer (Sonnet) | Haiku scoped |
| Final whole-branch | — | **Opus** |
