# Engine Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Nessy Pekko-driven — extract the watchman port's engine into `nessy-engine`, put it behind `Harness`/`HarnessFactory`, and delete `nessy-agent`.

**Architecture:** `Harness<O>` becomes an interface with two implementations; the Pekko one is constructed from an `ActorSystem` plus infrastructure and spins its own actors up. Both engines coexist until the watchman soak passes on the new one, then the old is deleted in a single commit.

**Tech Stack:** Java 21, Pekko Typed + Persistence + Cluster Sharding, Micrometer Tracing, Jackson, Maven.

**Spec:** `docs/superpowers/specs/2026-08-28-engine-extraction-design.md`, which implements `docs/superpowers/specs/2026-08-28-actor-composition-design.md`. Both are binding; the composition spec governs runtime semantics, the extraction spec governs where code lives.

## Global Constraints

- **No star imports.** Explicit single-symbol imports everywhere, regular and static alike.
- **Never suppress warnings.** No `@SuppressWarnings` of any category; fix the cause.
- **`nessy-api` names no SPI type and no Pekko type.** This is checkable and must stay true after every task.
- **`nessy-engine` is the only module that knows Pekko exists.**
- Full verification is `./mvnw -q clean verify` — the FINAL GATE, run once per task before its last commit. While iterating use `./mvnw -q -pl <module> -am test` with no `clean`. Never run two Maven processes concurrently in one worktree.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`.
- Exception-assertion lambdas contain exactly ONE throwing invocation; arrange setup outside (Sonar S5778).
- Assert emptiness before any all/none-match predicate on the same collection (S5841).
- Tests are prose-style, no mocking library.
- **Two changes are NOT authorized and must not be made by any task:** `ToolResult` gaining `List<ContentBlock>`, and any new method on `Model`. If a task appears to need either, stop and report.

---

### Task 1: `nessy-engine` module exists

**Files:**
- Create: `nessy-engine/pom.xml`
- Modify: `pom.xml` (module list), `nessy-bom/pom.xml` (dependency management)
- Create: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/ModuleWiringTest.java`

**Interfaces:**
- Produces: the `nessy-engine` Maven coordinate, depended on by later tasks.

- [ ] **Step 1: Create the POM.** Model it on `nessy-examples/watchman-pekko/pom.xml` for the Pekko dependency set (`pekko-actor-typed`, `pekko-persistence-typed`, `pekko-cluster-sharding-typed`, `pekko-serialization-jackson`, `pekko-stream`), plus `nessy-api` and `nessy-spi`. Do NOT copy the example's Spring or OTLP dependencies. `pekko-actor-testkit-typed` at test scope.

- [ ] **Step 2: Register the module** in the reactor `<modules>` (after `nessy-agent`) and add its coordinate to `nessy-bom`.

- [ ] **Step 3: Write a test that proves the module builds and Pekko is on the classpath.**

```java
@Test
void theEngineModuleHasAnActorSystemAvailableToIt() {
  ActorTestKit kit = ActorTestKit.create();
  try {
    assertThat(kit.system().name()).isNotBlank();
  } finally {
    kit.shutdownTestKit();
  }
}
```

- [ ] **Step 4: Run it.** `./mvnw -q -pl nessy-engine -am test` — expect PASS.

- [ ] **Step 5: Full gate and commit.** `./mvnw -q clean verify`, then commit.

---

### Task 2: The port's engine moves into `nessy-engine`

**Files:**
- Move from `nessy-examples/watchman-pekko/src/main/java/org/jwcarman/nessy/examples/watchman/pekko/` into `nessy-engine/src/main/java/org/jwcarman/nessy/engine/`: `AgentActor`, `AgentState`, `AgentRegistry`, `ApprovalActor`, `ToolCallActor`, `ToolWorker`, `ModelDesk`, `ModelWorker`, `Backlog`, `BacklogItem`, `Coalescer`, `Claims`, `Traces`, and their tests.
- Leave in the example: Spring configuration, HTTP controllers, the watchman's own tools, `WatchmanGuardian`, `soak.sh`.
- Modify: `nessy-examples/watchman-pekko/pom.xml` to depend on `nessy-engine`.

**Interfaces:**
- Consumes: Task 1's module.
- Produces: `org.jwcarman.nessy.engine.*` types that Tasks 5-6 wire behind `HarnessFactory`.

- [ ] **Step 1: Move the files** with `git mv` so history follows, and rewrite package declarations and imports. `Coalescer` and `Backlog` are user-facing vocabulary per the spec — but they move to `nessy-engine` in THIS task and are promoted to `nessy-api` in Task 4, so that this task is a pure move with no API change.

- [ ] **Step 2: Point the example at the module.** Add the `nessy-engine` dependency; remove the Pekko dependencies it now inherits transitively.

- [ ] **Step 3: Run the example's tests.** `./mvnw -q -pl nessy-examples/watchman-pekko -am test` — all 52 must still pass. A move that changes behavior is not a move.

- [ ] **Step 4: Verify the boundary.** `grep -rl "org.apache.pekko" nessy-api/src/main nessy-spi/src/main` must return nothing.

- [ ] **Step 5: Full gate and commit.**

---

### Task 3: `Harness` becomes an interface

**Files:**
- Modify: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/Harness.java` → extract the public surface to an interface
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/DefaultHarness.java` (the existing final class, renamed)
- Modify: callers in `nessy-agent/host/`, `nessy-spring-boot-starter`, tests

**Interfaces:**
- Produces: `Harness<O>` as an interface — the seam Task 5's Pekko implementation plugs into.

- [ ] **Step 1: Write a failing test** asserting the type is an interface and that a hand-written stub can implement it.

```java
@Test
void harnessIsAnInterfaceSoAnEngineCanImplementIt() {
  assertThat(Harness.class.isInterface()).isTrue();
}
```

- [ ] **Step 2: Run it.** Expect FAIL — `Harness` is a final class.

- [ ] **Step 3: Extract.** Every currently-public method of `Harness` becomes an interface method; the existing class is renamed `DefaultHarness` and declares `implements Harness<O>`. Nothing changes about its behavior. `shutdown()` is on the interface.

- [ ] **Step 4: Update construction sites.** `HarnessConfig.build()` and `Nessy.harness(...)` return `Harness<O>` but instantiate `DefaultHarness`.

- [ ] **Step 5: Run `nessy-agent`'s full suite.** Every existing test passes unchanged — this is the evidence the extraction was behavior-preserving.

- [ ] **Step 6: Full gate and commit.**

---

### Task 4: `HarnessFactory` and the config split

**Files:**
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/harness/HarnessFactory.java`
- Move to `nessy-api`: `Harness`, `HarnessConfig`, `HarnessCustomizer`, `ObservationRenderer`, `Coalescer`, `BacklogItem`
- Modify: `nessy-agent/host/Nessy.java` to delegate to a default factory

**Interfaces:**
- Consumes: Task 3's `Harness` interface.
- Produces:

```java
public interface HarnessFactory {
  default Harness<String> create(HarnessCustomizer<String> customizer) { ... }
  <O> Harness<O> create(Class<O> observationType, HarnessCustomizer<O> customizer);
}
```

- [ ] **Step 1: Write the failing test** — the `String` default applies a text renderer that a customizer can override.

```java
@Test
void theStringDefaultSuppliesATextRendererTheCustomizerCanOverride() {
  var factory = new RecordingHarnessFactory();
  factory.create(cfg -> {});
  assertThat(factory.lastRenderer()).isNotNull();
}
```

- [ ] **Step 2: Run it.** Expect FAIL — no such type.

- [ ] **Step 3: Write `HarnessFactory`** exactly as in spec §3: `<O>` declared on the method, `HarnessCustomizer<O>` rather than a raw `Consumer`, and the default sets the text renderer BEFORE applying the customizer so the caller can override it.

- [ ] **Step 4: Move the doors to `nessy-api`** and strip SPI types from `HarnessConfig`'s surface per spec §2.1 — infrastructure (`Substrate`, `ModelProvider`, `ObjectMapper`, `Clock`) moves to factory construction; `Memory` leaves the surface entirely; model choice becomes an optional model NAME.

- [ ] **Step 5: Verify the boundary.** `grep -rn "org.jwcarman.nessy.spi" nessy-api/src/main` must return nothing. This is the task's real assertion.

- [ ] **Step 6: `Nessy.harness(...)` delegates** to a default factory so existing callers compile untouched.

- [ ] **Step 7: Full gate and commit.**

---

### Task 5: `PekkoHarnessFactory`

**Files:**
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/PekkoHarnessFactory.java`
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/PekkoHarness.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/PekkoHarnessFactoryTest.java`

**Interfaces:**
- Consumes: Task 4's `HarnessFactory`, Task 2's actors.
- Produces: the engine door used by Task 6.

- [ ] **Step 1: Write the failing test** — a whole agent runs in-process on the testkit's system, with no Spring, no HTTP, and an `InMemorySubstrate`.

```java
@Test
void anAgentIngestsAnObservationAndRunsATurn() {
  ActorTestKit kit = ActorTestKit.create();
  try {
    HarnessFactory factory = new PekkoHarnessFactory(kit.system(), new InMemorySubstrate(), scriptedProvider());
    Harness<String> harness = factory.create(cfg -> cfg.agentType("test"));
    harness.observe("agent-1", "something happened");
    assertThat(awaitTurn(harness)).isNotNull();
  } finally {
    kit.shutdownTestKit();
  }
}
```

- [ ] **Step 2: Run it.** Expect FAIL — no such type.

- [ ] **Step 3: Implement the factory.** Constructor takes the `ActorSystem` plus infrastructure. Spawning goes through `SpawnProtocol` or a named parent the factory owns — it CANNOT be the system's guardian (spec §3.1). Agents come up locally when no cluster extension is present, and via `ClusterSharding.get(system).init(...)` when one is.

- [ ] **Step 4: Implement `shutdown()`** to stop only the actors this harness spawned. **It must never terminate the `ActorSystem`.** Write a test that asserts the system is still alive after `shutdown()`.

- [ ] **Step 5: Run the tests.** Expect PASS.

- [ ] **Step 6: Full gate and commit.**

---

### Task 6: The watchman runs on `nessy-engine` — THE GATE

**Files:**
- Modify: `nessy-examples/watchman-pekko/src/main/java/.../WatchmanConfiguration.java` (or equivalent Spring wiring) to obtain its agent through `HarnessFactory`
- Modify: `nessy-examples/watchman-pekko/soak.sh` only if paths changed

**Interfaces:**
- Consumes: Task 5's `PekkoHarnessFactory`.

- [ ] **Step 1: Wire the example through the factory.** The `ActorSystem` stays a Spring bean; `PekkoHarnessFactory` becomes a bean built from it; the watchman agent comes from `factory.create(...)`.

- [ ] **Step 2: Run the module's tests.** All must pass.

- [ ] **Step 3: Full reactor gate.** `./mvnw -q clean verify`.

- [ ] **Step 4: RUN THE SOAK.** `cd nessy-examples/watchman-pekko && ./soak.sh 8`. This is the gate, not the test suite: on 2026-08-28 four defects shipped past 52 green tests and four rounds of review, and every one was caught by running the thing. **The run must report "parked at least once"** — without a park nothing arrived mid-turn, so the refusal count proves nothing and the run is vacuous. Report the full measurement block.

- [ ] **Step 5: Commit** only after the soak passes.

---

### Task 7: Delete `nessy-agent`

**Files:**
- Delete: `nessy-agent/` entirely
- Modify: root `pom.xml`, `nessy-bom/pom.xml`, `nessy-spring-boot-starter` (repoint at `nessy-engine`)

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: Find every remaining reference.** `grep -rn "nessy-agent\|org.jwcarman.nessy.agent" --include=pom.xml --include=*.java .` — every hit is either repointed at `nessy-api`/`nessy-engine` or deleted with the module.

- [ ] **Step 2: Delete the module** and drop it from the reactor and BOM.

- [ ] **Step 3: Full reactor gate.** `./mvnw -q clean verify` with no API key and no model-provider network access.

- [ ] **Step 4: Re-run the soak** to prove the deletion took nothing live with it.

- [ ] **Step 5: Commit.**
