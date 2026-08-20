# Agent Machine (Plan 1 of 4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `nessy-agent` module's pure machine — identity types, event grammar, the sealed phase machine, `Transition`, `State` with JSON serialization, and the versioned in-memory `AgentStateStore`.

**Architecture:** A new Maven module `nessy-agent` (package root `org.jwcarman.nessy.agent`) depending on `nessy-core` for vocabulary only (`Message`, `ContentBlock`, `ToolCall`, `ToolResult`). Everything in this plan is pure values — no I/O, no threads, no clocks — except the in-memory store, whose only job is the version CAS. Later plans add the shell, executors, memory, desk, and builders.

**Tech Stack:** Java 25, Maven multi-module, Jackson (databind + annotations), JUnit 5, AssertJ. No mocking libraries — ever (design-of-record promise).

**Spec:** `docs/superpowers/specs/2026-08-18-agent-as-scope-design.md` (§1–§3.4, §5.2, and the q5/q6/q8 rulings in §11 govern this plan)

## Global Constraints

- `./mvnw -q clean verify` must pass with no API key and no model-provider network access, always.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`, then re-stage.
- No `@SuppressWarnings` of any kind; no star imports (single-symbol imports only, including statics).
- Exception-assertion lambdas (`assertThatThrownBy`) contain exactly ONE invocation that can throw; all setup outside the lambda (Sonar S5778).
- Assert emptiness before any all/none-match-style predicate on the same collection (S5841-family).
- Prose test style: test method names are lower-case prose sentences, e.g. `void anObservationWhileIdleCommitsTheUserMessageAndCallsTheModel()`.
- No mocking libraries. Tests use real values and, where needed, tiny hand-written fakes.
- Parent POM: `org.jwcarman.nessy:nessy-parent:0.1.0-SNAPSHOT`. Java release 25.
- `nessy-agent` may depend on `nessy-core` only (plus Jackson + test deps). `nessy-core` must never reference any `org.jwcarman.nessy.agent` type (spec §11 q8 — checked by review in this plan; a build-time enforcer lands in Plan 4).

## Plan-level design decisions (deviations from the spec's illustrative sketches — deliberate, do not "fix")

1. **Pending tool calls are keyed by plain `String` ids** (`ToolCall.id()`), not a `ToolCallId` wrapper. Core owns the `ToolCall` vocabulary and its id is a `String`; a wrapper in `nessy-agent` would be a second vocabulary for a core-owned identifier.
2. **`AwaitingTools.gathered` is `List<ToolResultBlock>`**, not `List<Message>`. The spec's §2.2/§2.5 sketches disagree with each other here; the unit-commit builds `Message.toolResults(gathered)` at completion, which takes content blocks. Core's `ToolResultBlock(String toolUseId, String content, boolean isError)` is exactly the element type.
3. **`Transition.ignore` takes no argument.** The spec sketch passes the stale event, but the transition does nothing with it — the shell (Plan 2) already holds the event when it narrates `ignored(event)`.

## File Structure

```
nessy-agent/
  pom.xml
  src/main/java/org/jwcarman/nessy/agent/
    AgentId.java            — scope identity (record wrapping non-blank String)
    AgentType.java          — recipe identity (record wrapping non-blank String)
    AgentEvent.java         — sealed: Observed | ModelFinished | ToolFinished
    ModelOutcome.java       — sealed: Responded | Failed
    ToolOutcome.java        — sealed: Returned | Failed
    ToolError.java          — record (String message)
    Effect.java             — sealed: CallModel | ExecuteTool
    Transition.java         — record (Phase next, List<Message> commit, List<Effect> effects)
    Phase.java              — sealed interface + Idle, AwaitingModel, AwaitingTools (nested records)
    State.java              — record (Phase phase, long version)
    StateCodec.java         — JSON with "phase" type discriminator (Jackson)
    store/
      AgentStateStore.java        — SPI: load / save (CAS)
      StaleStateException.java
      InMemoryAgentStateStore.java
  src/test/java/org/jwcarman/nessy/agent/
    IdentityTest.java
    EventGrammarTest.java
    TransitionTest.java
    IdlePhaseTest.java
    AwaitingModelPhaseTest.java
    AwaitingToolsPhaseTest.java
    StateCodecTest.java
    store/InMemoryAgentStateStoreTest.java
```

---

### Task 1: Module scaffolding + identity types

**Files:**
- Create: `nessy-agent/pom.xml`
- Modify: `pom.xml` (repo root — add `<module>nessy-agent</module>` after `<module>nessy-core</module>`)
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/AgentId.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/AgentType.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/IdentityTest.java`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `AgentId(String value)` with `AgentId.of(String)`; `AgentType(String name)` with `AgentType.of(String)`. Both reject null/blank with `IllegalArgumentException`.

- [ ] **Step 1: Create the module POM**

`nessy-agent/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>nessy-agent</artifactId>
  <name>Nessy Agent</name>
  <dependencies>
    <dependency>
      <groupId>org.jwcarman.nessy</groupId>
      <artifactId>nessy-core</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-annotations</artifactId>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

If `./mvnw -q clean verify` later fails because the parent does not manage `junit-jupiter`/`assertj-core` versions, copy the exact test-dependency declarations (including any version/BOM usage) from `nessy-core/pom.xml` — match that file, do not invent versions.

- [ ] **Step 2: Register the module in the root POM**

In the repo-root `pom.xml`, inside `<modules>`, add after `<module>nessy-core</module>`:

```xml
    <module>nessy-agent</module>
```

- [ ] **Step 3: Write the failing test**

`IdentityTest.java`:

```java
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdentityTest {

  @Test
  void anAgentIdCarriesItsValue() {
    assertThat(AgentId.of("tenant-42").value()).isEqualTo("tenant-42");
  }

  @Test
  void agentIdsWithTheSameValueAreEqual() {
    assertThat(AgentId.of("a")).isEqualTo(AgentId.of("a"));
  }

  @Test
  void aBlankAgentIdIsRejected() {
    assertThatThrownBy(() -> AgentId.of(" ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNullAgentIdIsRejected() {
    assertThatThrownBy(() -> AgentId.of(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anAgentTypeCarriesItsName() {
    assertThat(AgentType.of("order-desk").name()).isEqualTo("order-desk");
  }

  @Test
  void aBlankAgentTypeIsRejected() {
    assertThatThrownBy(() -> AgentType.of("")).isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `./mvnw -q -pl nessy-agent test`
Expected: COMPILE FAILURE — `AgentId` and `AgentType` do not exist.

- [ ] **Step 5: Implement the identity records**

`AgentId.java`:

```java
package org.jwcarman.nessy.agent;

/** The scope: memory, state, and backlog are all keyed by it. Pure data (spec §1.1). */
public record AgentId(String value) {

  public AgentId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("agent id must not be blank");
    }
  }

  public static AgentId of(String value) {
    return new AgentId(value);
  }
}
```

`AgentType.java`:

```java
package org.jwcarman.nessy.agent;

/** The recipe's name. The type is code; the id is data (spec §1.1). */
public record AgentType(String name) {

  public AgentType {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("agent type name must not be blank");
    }
  }

  public static AgentType of(String name) {
    return new AgentType(name);
  }
}
```

- [ ] **Step 6: Run to verify it passes**

Run: `./mvnw -q -pl nessy-agent test`
Expected: PASS (6 tests).

- [ ] **Step 7: Full build, format, commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add pom.xml nessy-agent
git commit -m "feat: nessy-agent module is born with its two identities"
```

---

### Task 2: The event grammar

**Files:**
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/AgentEvent.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/ModelOutcome.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/ToolOutcome.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/ToolError.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/EventGrammarTest.java`

**Interfaces:**
- Consumes: core vocabulary — `org.jwcarman.nessy.api.message.ContentBlock`, `org.jwcarman.nessy.api.tool.ToolCall`, `org.jwcarman.nessy.api.tool.ToolResult`.
- Produces (exact shapes later tasks and plans rely on):
  - `AgentEvent.Observed(List<ContentBlock> content)`
  - `AgentEvent.ModelFinished(ModelOutcome outcome)`
  - `AgentEvent.ToolFinished(ToolCall call, ToolOutcome outcome)`
  - `ModelOutcome.Responded(List<ContentBlock> content, List<ToolCall> calls)` / `ModelOutcome.Failed(String reason)`
  - `ToolOutcome.Returned(ToolResult result)` / `ToolOutcome.Failed(ToolError error)`
  - `ToolError(String message)`

- [ ] **Step 1: Write the failing test**

`EventGrammarTest.java`:

```java
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

class EventGrammarTest {

  private static ToolCall call(String id) {
    return new ToolCall(id, "lookup", JsonNodeFactory.instance.objectNode());
  }

  @Test
  void anObservationCarriesItsRenderedContent() {
    var observed = new AgentEvent.Observed(List.of(new TextBlock("hi")));
    assertThat(observed.content()).containsExactly(new TextBlock("hi"));
  }

  @Test
  void anObservationRejectsNullContent() {
    assertThatThrownBy(() -> new AgentEvent.Observed(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aModelCompletionWrapsExactlyOneOutcome() {
    var responded = new ModelOutcome.Responded(List.of(new TextBlock("ok")), List.of());
    assertThat(new AgentEvent.ModelFinished(responded).outcome()).isEqualTo(responded);
  }

  @Test
  void aModelCompletionRejectsANullOutcome() {
    assertThatThrownBy(() -> new AgentEvent.ModelFinished(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aModelFailureCarriesItsReason() {
    assertThat(new ModelOutcome.Failed("overloaded").reason()).isEqualTo("overloaded");
  }

  @Test
  void aToolCompletionCarriesItsCallAndOutcome() {
    var outcome = new ToolOutcome.Returned(ToolResult.ok("42"));
    var finished = new AgentEvent.ToolFinished(call("c1"), outcome);
    assertThat(finished.call().id()).isEqualTo("c1");
    assertThat(finished.outcome()).isEqualTo(outcome);
  }

  @Test
  void aFailedToolOutcomeCarriesAnError() {
    var failed = new ToolOutcome.Failed(new ToolError("timed out"));
    assertThat(failed.error().message()).isEqualTo("timed out");
  }

  @Test
  void aToolErrorRejectsANullMessage() {
    assertThatThrownBy(() -> new ToolError(null)).isInstanceOf(NullPointerException.class);
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q -pl nessy-agent test`
Expected: COMPILE FAILURE — grammar types do not exist.

- [ ] **Step 3: Implement the grammar**

`AgentEvent.java`:

```java
package org.jwcarman.nessy.agent;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * Facts, past tense. Three variants is the designed ceiling: every effect has exactly one
 * completion event, and {@code Observed} is the sole inbound fact (spec §2.1).
 */
public sealed interface AgentEvent {

  record Observed(List<ContentBlock> content) implements AgentEvent {
    public Observed {
      Objects.requireNonNull(content, "content must not be null");
      content = List.copyOf(content);
    }
  }

  record ModelFinished(ModelOutcome outcome) implements AgentEvent {
    public ModelFinished {
      Objects.requireNonNull(outcome, "outcome must not be null");
    }
  }

  record ToolFinished(ToolCall call, ToolOutcome outcome) implements AgentEvent {
    public ToolFinished {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(outcome, "outcome must not be null");
    }
  }
}
```

`ModelOutcome.java`:

```java
package org.jwcarman.nessy.agent;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

/** What a model call came back with. Success and failure are outcomes, never separate events. */
public sealed interface ModelOutcome {

  record Responded(List<ContentBlock> content, List<ToolCall> calls) implements ModelOutcome {
    public Responded {
      Objects.requireNonNull(content, "content must not be null");
      Objects.requireNonNull(calls, "calls must not be null");
      content = List.copyOf(content);
      calls = List.copyOf(calls);
    }
  }

  record Failed(String reason) implements ModelOutcome {
    public Failed {
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }
}
```

`ToolOutcome.java`:

```java
package org.jwcarman.nessy.agent;

import java.util.Objects;
import org.jwcarman.nessy.api.tool.ToolResult;

/** What a tool call came back with. A failed tool is in-band: the model reads it and reacts. */
public sealed interface ToolOutcome {

  record Returned(ToolResult result) implements ToolOutcome {
    public Returned {
      Objects.requireNonNull(result, "result must not be null");
    }
  }

  record Failed(ToolError error) implements ToolOutcome {
    public Failed {
      Objects.requireNonNull(error, "error must not be null");
    }
  }
}
```

`ToolError.java`:

```java
package org.jwcarman.nessy.agent;

import java.util.Objects;

/** Why a tool call failed, in words the model can read. */
public record ToolError(String message) {

  public ToolError {
    Objects.requireNonNull(message, "message must not be null");
  }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q -pl nessy-agent test`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-agent
git commit -m "feat: the event grammar — three facts, sealed outcomes inside"
```

---

### Task 3: Effect and Transition

**Files:**
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/Effect.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/Transition.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/TransitionTest.java`

**Interfaces:**
- Consumes: `Phase` does not exist yet — `Transition` references it, so this task creates a minimal `Phase` marker? **No.** To keep every task compiling, `Transition` in this task holds `Object next`? **Also no — no fake types.** Resolution: Task 3 and Task 4 both touch `Phase`; this task creates `Effect` and `Transition` with `Phase` as its `next` type, and a *complete but minimal* `Phase.java` containing only the sealed interface declaration with its three permitted records **fully implemented is Task 4's job — so this task's `Transition` tests use `Phase` instances**. To avoid a half-built `Phase`, Task 3 builds `Effect` + `Transition` **and** the `Phase` file's three record *shells with real `handle` implementations deferred is not allowed* — therefore: **Task 3 delivers `Effect` and `Transition` only, typed against `Phase`, and includes the complete `Phase.java` exactly as specified in Task 4 Step 3.** Task 4 then adds only tests. The implementer of Task 3 copies `Phase.java` from Task 4 Step 3 verbatim; the implementer of Task 4 verifies it compiles untouched and writes the phase tests.
- Produces:
  - `Effect.CallModel()` / `Effect.ExecuteTool(ToolCall call)`
  - `Transition(Phase next, List<Message> commit, List<Effect> effects)` with:
    - `static Transition to(Phase next, Effect... effects)`
    - `static Transition ignore()` — stale marker; `isIgnored()` is `true`, all other accessors throw `IllegalStateException`
    - `Transition commit(Message... messages)` — returns a copy with messages appended
    - `Transition emit(List<Effect> more)` — returns a copy with effects appended
    - `boolean isIgnored()`

- [ ] **Step 1: Write the failing test**

`TransitionTest.java`:

```java
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Message;

class TransitionTest {

  @Test
  void aTransitionCarriesItsThreeDecisions() {
    var t =
        Transition.to(new Phase.AwaitingModel(), new Effect.CallModel())
            .commit(Message.user("hi"));
    assertThat(t.next()).isEqualTo(new Phase.AwaitingModel());
    assertThat(t.commit()).containsExactly(Message.user("hi"));
    assertThat(t.effects()).containsExactly(new Effect.CallModel());
    assertThat(t.isIgnored()).isFalse();
  }

  @Test
  void aBareTransitionCommitsNothingAndFiresNothing() {
    var t = Transition.to(new Phase.Idle());
    assertThat(t.commit()).isEmpty();
    assertThat(t.effects()).isEmpty();
  }

  @Test
  void emitAppendsEffectsInOrder() {
    var a = new Effect.CallModel();
    var t = Transition.to(new Phase.AwaitingModel()).emit(List.of(a));
    assertThat(t.effects()).containsExactly(a);
  }

  @Test
  void commitAppendsMessagesInOrder() {
    var t =
        Transition.to(new Phase.Idle())
            .commit(Message.user("first"))
            .commit(Message.user("second"));
    assertThat(t.commit()).containsExactly(Message.user("first"), Message.user("second"));
  }

  @Test
  void anIgnoredTransitionSaysSo() {
    assertThat(Transition.ignore().isIgnored()).isTrue();
  }

  @Test
  void anIgnoredTransitionHasNoNextPhase() {
    var ignored = Transition.ignore();
    assertThatThrownBy(ignored::next).isInstanceOf(IllegalStateException.class);
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q -pl nessy-agent test`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement `Effect`**

`Effect.java`:

```java
package org.jwcarman.nessy.agent;

import java.util.Objects;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * Commands, imperative. Two variants is the designed ceiling (spec §2.4). {@code CallModel} is a
 * bare marker: the executor asks Memory for context itself, and a fat effect could not be
 * re-derived by recovery (spec §6.1).
 */
public sealed interface Effect {

  record CallModel() implements Effect {}

  record ExecuteTool(ToolCall call) implements Effect {
    public ExecuteTool {
      Objects.requireNonNull(call, "call must not be null");
    }
  }
}
```

- [ ] **Step 4: Implement `Transition`**

`Transition.java`:

```java
package org.jwcarman.nessy.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.message.Message;

/**
 * What one event decides: what to become, what to commit to history, what to fire. Returned as a
 * value so that I/O is structurally impossible inside a phase (spec §2.5).
 */
public record Transition(Phase next, List<Message> commit, List<Effect> effects) {

  private static final Transition IGNORED = new Transition();

  public Transition {
    Objects.requireNonNull(next, "next must not be null");
    commit = List.copyOf(commit);
    effects = List.copyOf(effects);
  }

  /** Private ignored-marker constructor; bypasses the canonical null check via a sentinel. */
  private Transition() {
    this(Phase.SENTINEL, List.of(), List.of());
  }

  public static Transition to(Phase next, Effect... effects) {
    return new Transition(next, List.of(), List.of(effects));
  }

  /** A stale or duplicate event: fold nothing, commit nothing, fire nothing (spec §2.2). */
  public static Transition ignore() {
    return IGNORED;
  }

  public Transition commit(Message... messages) {
    requireNotIgnored();
    var all = new ArrayList<>(commit);
    all.addAll(List.of(messages));
    return new Transition(next, all, effects);
  }

  public Transition emit(List<Effect> more) {
    requireNotIgnored();
    var all = new ArrayList<>(effects);
    all.addAll(more);
    return new Transition(next, commit, all);
  }

  public boolean isIgnored() {
    return this == IGNORED;
  }

  @Override
  public Phase next() {
    requireNotIgnored();
    return next;
  }

  private void requireNotIgnored() {
    if (isIgnored()) {
      throw new IllegalStateException("an ignored transition decides nothing");
    }
  }
}
```

Note: `Phase.SENTINEL` is defined in `Phase.java` (next step) as a package-private static
`Phase.Idle` instance used only by the ignored marker. It never escapes: `next()` throws before
returning it.

- [ ] **Step 5: Implement `Phase` (copied verbatim from Task 4 Step 3 — both tasks reference one canonical listing)**

Create `Phase.java` exactly as printed in **Task 4 Step 3** below.

- [ ] **Step 6: Run to verify it passes**

Run: `./mvnw -q -pl nessy-agent test`
Expected: PASS.

- [ ] **Step 7: Format and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-agent
git commit -m "feat: Transition decides three things; Effect stays a two-word grammar"
```

---

### Task 4: The phase machine — tests for all three phases

**Files:**
- Verify (created in Task 3, must compile untouched): `nessy-agent/src/main/java/org/jwcarman/nessy/agent/Phase.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/IdlePhaseTest.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/AwaitingModelPhaseTest.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/AwaitingToolsPhaseTest.java`

**Interfaces:**
- Consumes: everything from Tasks 2–3.
- Produces: the canonical `Phase` behavior later plans build the shell against:
  - `Phase.Idle()` — `Observed` → `AwaitingModel` + commit user message + `CallModel`; completions → `ignore()`.
  - `Phase.AwaitingModel()` — terminal `Responded` → `Idle` + commit assistant message; `Responded` with calls → `AwaitingTools` + one `ExecuteTool` per call, commits **nothing** (held-back unit); `Failed` → `Idle`, commits nothing; `ToolFinished` → `ignore()`; `Observed` → `IllegalStateException`.
  - `Phase.AwaitingTools(Message assistantTurn, Set<String> pending, List<ToolResultBlock> gathered)` — known-id `ToolFinished` shrinks `pending`, gathers a `ToolResultBlock`; last one commits `assistantTurn` + `Message.toolResults(gathered)` and fires `CallModel`; unknown id → `ignore()`; `ModelFinished` → `ignore()`; `Observed` → `IllegalStateException`.

- [ ] **Step 1: Verify `Phase.java` matches this canonical listing (Task 3 created it)**

`Phase.java` — **the canonical listing** (spec §2.2/§2.5, concretized per the plan-level decisions):

```java
package org.jwcarman.nessy.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * State that carries its own data. Pending calls exist only inside {@code AwaitingTools}; "idle
 * with outstanding calls" is unrepresentable (spec §2.2). Every phase carries enough to
 * reconstruct its outstanding effects (spec §6.1).
 */
public sealed interface Phase {

  /** Used only by {@link Transition#ignore()}'s marker; never escapes. */
  Phase SENTINEL = new Idle();

  Transition handle(AgentEvent event);

  record Idle() implements Phase {
    @Override
    public Transition handle(AgentEvent event) {
      return switch (event) {
        case AgentEvent.Observed(var content) ->
            Transition.to(new AwaitingModel(), new Effect.CallModel())
                .commit(Message.user(content));
        case AgentEvent.ModelFinished ignored -> Transition.ignore();
        case AgentEvent.ToolFinished ignored -> Transition.ignore();
      };
    }
  }

  record AwaitingModel() implements Phase {
    @Override
    public Transition handle(AgentEvent event) {
      return switch (event) {
        case AgentEvent.ModelFinished(ModelOutcome.Responded(var content, var calls))
                when calls.isEmpty() ->
            Transition.to(new Idle()).commit(Message.assistant(content));
        case AgentEvent.ModelFinished(ModelOutcome.Responded(var content, var calls)) ->
            Transition.to(
                    new AwaitingTools(
                        Message.assistant(content),
                        calls.stream().map(ToolCall::id).collect(Collectors.toUnmodifiableSet()),
                        List.of()))
                .emit(calls.stream().map(Effect.ExecuteTool::new).map(Effect.class::cast).toList());
        case AgentEvent.ModelFinished(ModelOutcome.Failed ignored) -> Transition.to(new Idle());
        case AgentEvent.ToolFinished ignored -> Transition.ignore();
        case AgentEvent.Observed ignored ->
            throw new IllegalStateException("observations absorb only at Idle");
      };
    }
  }

  record AwaitingTools(Message assistantTurn, Set<String> pending, List<ToolResultBlock> gathered)
      implements Phase {

    public AwaitingTools {
      Objects.requireNonNull(assistantTurn, "assistantTurn must not be null");
      pending = Set.copyOf(pending);
      gathered = List.copyOf(gathered);
      if (pending.isEmpty()) {
        throw new IllegalArgumentException("awaiting tools with nothing pending is not a phase");
      }
    }

    @Override
    public Transition handle(AgentEvent event) {
      return switch (event) {
        case AgentEvent.ToolFinished(var call, var outcome) -> {
          if (!pending.contains(call.id())) {
            yield Transition.ignore(); // duplicate or stale — ToolCallId dedup (spec §2.5)
          }
          var left = new HashSet<>(pending);
          left.remove(call.id());
          var all = new ArrayList<>(gathered);
          all.add(resultBlock(call, outcome));
          if (left.isEmpty()) {
            yield Transition.to(new AwaitingModel(), new Effect.CallModel())
                .commit(assistantTurn, Message.toolResults(List.copyOf(all)));
          }
          yield Transition.to(new AwaitingTools(assistantTurn, left, all));
        }
        case AgentEvent.ModelFinished ignored -> Transition.ignore();
        case AgentEvent.Observed ignored ->
            throw new IllegalStateException("observations absorb only at Idle");
      };
    }

    private static ToolResultBlock resultBlock(ToolCall call, ToolOutcome outcome) {
      return switch (outcome) {
        case ToolOutcome.Returned(var result) ->
            new ToolResultBlock(call.id(), result.content(), result.isError());
        case ToolOutcome.Failed(var error) -> new ToolResultBlock(call.id(), error.message(), true);
      };
    }
  }
}
```

Note on signatures (spec §2.5): `AwaitingModel` builds the assistant turn with
`Message.assistant(content)` — the **content blocks as delivered by the provider**, which include
`ToolUseBlock`s carrying their signatures. Reconstructing tool-use blocks from the bare `calls`
list would silently drop every signature; `calls` is used only for `pending` ids and `ExecuteTool`
effects. The unused `ToolUseBlock` import documents this dependency direction — if spotless removes
it, delete the import; the comment in the test (Step 4) carries the guarantee.

- [ ] **Step 2: Write and pass the Idle tests**

`IdlePhaseTest.java`:

```java
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

class IdlePhaseTest {

  @Test
  void anObservationCommitsTheUserMessageAndCallsTheModel() {
    var content = List.<org.jwcarman.nessy.api.message.ContentBlock>of(new TextBlock("hi"));
    var t = new Phase.Idle().handle(new AgentEvent.Observed(content));
    assertThat(t.next()).isEqualTo(new Phase.AwaitingModel());
    assertThat(t.commit()).containsExactly(Message.user(content));
    assertThat(t.effects()).containsExactly(new Effect.CallModel());
  }

  @Test
  void aStrayModelCompletionIsIgnored() {
    var event = new AgentEvent.ModelFinished(new ModelOutcome.Failed("late"));
    assertThat(new Phase.Idle().handle(event).isIgnored()).isTrue();
  }

  @Test
  void aStrayToolCompletionIsIgnored() {
    var call = new ToolCall("c1", "lookup", JsonNodeFactory.instance.objectNode());
    var event = new AgentEvent.ToolFinished(call, new ToolOutcome.Returned(ToolResult.ok("x")));
    assertThat(new Phase.Idle().handle(event).isIgnored()).isTrue();
  }
}
```

Run: `./mvnw -q -pl nessy-agent test` — Expected: PASS (Phase already implemented; these tests
verify the canonical listing).

- [ ] **Step 3: Write and pass the AwaitingModel tests**

`AwaitingModelPhaseTest.java`:

```java
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

class AwaitingModelPhaseTest {

  private static final ToolCall CALL_A =
      new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
  private static final ToolCall CALL_B =
      new ToolCall("b", "restart", JsonNodeFactory.instance.objectNode());

  @Test
  void aTerminalAnswerCommitsTheAssistantMessageAndGoesIdle() {
    var content = List.<ContentBlock>of(new TextBlock("done"));
    var event = new AgentEvent.ModelFinished(new ModelOutcome.Responded(content, List.of()));
    var t = new Phase.AwaitingModel().handle(event);
    assertThat(t.next()).isEqualTo(new Phase.Idle());
    assertThat(t.commit()).containsExactly(Message.assistant(content));
    assertThat(t.effects()).isEmpty();
  }

  @Test
  void aToolRequestingAnswerHoldsTheTurnBackAndFiresEveryCall() {
    var content =
        List.<ContentBlock>of(new ToolUseBlock(CALL_A, "sig-a"), new ToolUseBlock(CALL_B, "sig-b"));
    var event =
        new AgentEvent.ModelFinished(new ModelOutcome.Responded(content, List.of(CALL_A, CALL_B)));
    var t = new Phase.AwaitingModel().handle(event);
    // the held-back unit: NOTHING committed until every result is in (spec §2.5)
    assertThat(t.commit()).isEmpty();
    assertThat(t.next())
        .isEqualTo(new Phase.AwaitingTools(Message.assistant(content), Set.of("a", "b"), List.of()));
    assertThat(t.effects())
        .containsExactly(new Effect.ExecuteTool(CALL_A), new Effect.ExecuteTool(CALL_B));
  }

  @Test
  void theHeldBackTurnKeepsProviderSignaturesBecauseItIsBuiltFromContentBlocks() {
    var content = List.<ContentBlock>of(new ToolUseBlock(CALL_A, "gemini-thought-sig"));
    var event =
        new AgentEvent.ModelFinished(new ModelOutcome.Responded(content, List.of(CALL_A)));
    var t = new Phase.AwaitingModel().handle(event);
    var held = ((Phase.AwaitingTools) t.next()).assistantTurn();
    assertThat(held.content()).containsExactly(new ToolUseBlock(CALL_A, "gemini-thought-sig"));
  }

  @Test
  void aModelFailureGoesIdleAndCommitsNothing() {
    var event = new AgentEvent.ModelFinished(new ModelOutcome.Failed("overloaded"));
    var t = new Phase.AwaitingModel().handle(event);
    assertThat(t.next()).isEqualTo(new Phase.Idle());
    assertThat(t.commit()).isEmpty();
    assertThat(t.effects()).isEmpty();
  }

  @Test
  void aStrayToolCompletionIsIgnored() {
    var event =
        new AgentEvent.ToolFinished(CALL_A, new ToolOutcome.Returned(ToolResult.ok("x")));
    assertThat(new Phase.AwaitingModel().handle(event).isIgnored()).isTrue();
  }

  @Test
  void anObservationReachingThisPhaseIsAProgrammingError() {
    var phase = new Phase.AwaitingModel();
    var event = new AgentEvent.Observed(List.of(new TextBlock("hi")));
    assertThatThrownBy(() -> phase.handle(event)).isInstanceOf(IllegalStateException.class);
  }
}
```

Run: `./mvnw -q -pl nessy-agent test` — Expected: PASS.

- [ ] **Step 4: Write and pass the AwaitingTools tests**

`AwaitingToolsPhaseTest.java`:

```java
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

class AwaitingToolsPhaseTest {

  private static final ToolCall CALL_A =
      new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
  private static final ToolCall CALL_B =
      new ToolCall("b", "restart", JsonNodeFactory.instance.objectNode());
  private static final Message TURN =
      Message.assistant(
          List.<ContentBlock>of(new ToolUseBlock(CALL_A, "sig-a"), new ToolUseBlock(CALL_B, null)));

  private static AgentEvent.ToolFinished returned(ToolCall call, String content) {
    return new AgentEvent.ToolFinished(call, new ToolOutcome.Returned(ToolResult.ok(content)));
  }

  @Test
  void aPartialResultShrinksPendingAndCommitsNothing() {
    var phase = new Phase.AwaitingTools(TURN, Set.of("a", "b"), List.of());
    var t = phase.handle(returned(CALL_A, "42"));
    assertThat(t.commit()).isEmpty();
    assertThat(t.effects()).isEmpty();
    assertThat(t.next())
        .isEqualTo(
            new Phase.AwaitingTools(
                TURN, Set.of("b"), List.of(new ToolResultBlock("a", "42", false))));
  }

  @Test
  void theLastResultCommitsTheWholeUnitAndCallsTheModel() {
    var gathered = List.of(new ToolResultBlock("a", "42", false));
    var phase = new Phase.AwaitingTools(TURN, Set.of("b"), gathered);
    var t = phase.handle(returned(CALL_B, "ok"));
    assertThat(t.next()).isEqualTo(new Phase.AwaitingModel());
    assertThat(t.commit())
        .containsExactly(
            TURN,
            Message.toolResults(
                List.of(
                    new ToolResultBlock("a", "42", false), new ToolResultBlock("b", "ok", false))));
    assertThat(t.effects()).containsExactly(new Effect.CallModel());
  }

  @Test
  void aFailedToolRendersInBandAsAnErrorResult() {
    var phase = new Phase.AwaitingTools(TURN, Set.of("a", "b"), List.of());
    var failed =
        new AgentEvent.ToolFinished(CALL_A, new ToolOutcome.Failed(new ToolError("timed out")));
    var t = phase.handle(failed);
    assertThat(t.next())
        .isEqualTo(
            new Phase.AwaitingTools(
                TURN, Set.of("b"), List.of(new ToolResultBlock("a", "timed out", true))));
  }

  @Test
  void aDuplicateDeliveryOfASettledCallIsIgnored() {
    var phase =
        new Phase.AwaitingTools(TURN, Set.of("b"), List.of(new ToolResultBlock("a", "42", false)));
    assertThat(phase.handle(returned(CALL_A, "42-again")).isIgnored()).isTrue();
  }

  @Test
  void aStrayModelCompletionIsIgnored() {
    var phase = new Phase.AwaitingTools(TURN, Set.of("a", "b"), List.of());
    var event = new AgentEvent.ModelFinished(new ModelOutcome.Failed("late duplicate"));
    assertThat(phase.handle(event).isIgnored()).isTrue();
  }

  @Test
  void anObservationReachingThisPhaseIsAProgrammingError() {
    var phase = new Phase.AwaitingTools(TURN, Set.of("a"), List.of());
    var event = new AgentEvent.Observed(List.of(new TextBlock("hi")));
    assertThatThrownBy(() -> phase.handle(event)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void awaitingNothingIsNotAPhase() {
    Set<String> empty = Set.of();
    List<ToolResultBlock> none = List.of();
    assertThatThrownBy(() -> new Phase.AwaitingTools(TURN, empty, none))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

Run: `./mvnw -q -pl nessy-agent test` — Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-agent
git commit -m "test: the phase machine proves itself with values alone"
```

---

### Task 5: State and the JSON codec

**Files:**
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/State.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/StateCodec.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/StateCodecTest.java`

**Interfaces:**
- Consumes: `Phase` (Task 3/4), core `Message`/`ContentBlock` family.
- Produces:
  - `State(Phase phase, long version)` — rejects null phase and negative version. `State.initial()` returns `new State(new Phase.Idle(), 0L)`.
  - `StateCodec` — `String encode(State state)` / `State decode(String json)`; discriminator property `"phase"` with values `IDLE`, `AWAITING_MODEL`, `AWAITING_TOOLS`; unknown discriminator → `IllegalArgumentException` (fail loudly, spec §2.3).

- [ ] **Step 1: Write the failing test**

`StateCodecTest.java`:

```java
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

class StateCodecTest {

  private final StateCodec codec = new StateCodec();

  @Test
  void anIdleStateRoundTrips() {
    var state = State.initial();
    assertThat(codec.decode(codec.encode(state))).isEqualTo(state);
  }

  @Test
  void anAwaitingModelStateRoundTrips() {
    var state = new State(new Phase.AwaitingModel(), 7L);
    assertThat(codec.decode(codec.encode(state))).isEqualTo(state);
  }

  @Test
  void anAwaitingToolsStateRoundTripsWithSignaturesIntact() {
    var call = new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
    var turn =
        Message.assistant(List.<ContentBlock>of(new ToolUseBlock(call, "gemini-thought-sig")));
    var state =
        new State(
            new Phase.AwaitingTools(
                turn, Set.of("a"), List.of(new ToolResultBlock("z", "done", false))),
            42L);
    assertThat(codec.decode(codec.encode(state))).isEqualTo(state);
  }

  @Test
  void theDiscriminatorPropertyIsCalledPhase() {
    assertThat(codec.encode(State.initial())).contains("\"phase\":\"IDLE\"");
  }

  @Test
  void anUnknownDiscriminatorFailsLoudly() {
    var codecUnderTest = codec;
    assertThatThrownBy(
            () -> codecUnderTest.decode("{\"phase\":\"AWAITING_APPROVAL\",\"version\":1}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aStateRejectsANegativeVersion() {
    var idle = new Phase.Idle();
    assertThatThrownBy(() -> new State(idle, -1L)).isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q -pl nessy-agent test`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement `State`**

`State.java`:

```java
package org.jwcarman.nessy.agent;

import java.util.Objects;

/** The whole of a scope's control state: what it is doing, and the optimistic-lock version. */
public record State(Phase phase, long version) {

  public State {
    Objects.requireNonNull(phase, "phase must not be null");
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
  }

  public static State initial() {
    return new State(new Phase.Idle(), 0L);
  }
}
```

- [ ] **Step 4: Implement `StateCodec`**

Jackson cannot serialize core's `Message`/`ContentBlock`/`ToolCall` polymorphism without help, and
core types must not grow agent-serving annotations (dependency direction, spec §11 q8). The codec
therefore owns a private, self-contained wire shape and maps by hand. This is more code than a
mixin registry but zero magic, zero core changes, and the wire format is explicit in one file.

`StateCodec.java`:

```java
package org.jwcarman.nessy.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.ImageBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * JSON with a type discriminator on the phase (spec §2.3, ruled 2026-08-20). The wire shape is
 * private to this codec; core types carry no serialization annotations. Unknown discriminators
 * fail loudly — a newer node wrote a phase this node does not know.
 */
public final class StateCodec {

  private static final String PHASE = "phase";
  private static final String IDLE = "IDLE";
  private static final String AWAITING_MODEL = "AWAITING_MODEL";
  private static final String AWAITING_TOOLS = "AWAITING_TOOLS";

  private final ObjectMapper mapper = new ObjectMapper();

  public String encode(State state) {
    Objects.requireNonNull(state, "state must not be null");
    ObjectNode root = mapper.createObjectNode();
    root.put("version", state.version());
    switch (state.phase()) {
      case Phase.Idle ignored -> root.put(PHASE, IDLE);
      case Phase.AwaitingModel ignored -> root.put(PHASE, AWAITING_MODEL);
      case Phase.AwaitingTools(Message turn, Set<String> pending, List<ToolResultBlock> gathered) -> {
        root.put(PHASE, AWAITING_TOOLS);
        root.set("assistantTurn", writeMessage(turn));
        ArrayNode pendingNode = root.putArray("pending");
        pending.stream().sorted().forEach(pendingNode::add);
        ArrayNode gatheredNode = root.putArray("gathered");
        gathered.forEach(g -> gatheredNode.add(writeResultBlock(g)));
      }
    }
    return root.toString();
  }

  public State decode(String json) {
    Objects.requireNonNull(json, "json must not be null");
    try {
      JsonNode root = mapper.readTree(json);
      long version = root.get("version").asLong();
      String discriminator = root.get(PHASE).asText();
      Phase phase =
          switch (discriminator) {
            case IDLE -> new Phase.Idle();
            case AWAITING_MODEL -> new Phase.AwaitingModel();
            case AWAITING_TOOLS -> readAwaitingTools(root);
            default ->
                throw new IllegalArgumentException("unknown phase discriminator: " + discriminator);
          };
      return new State(phase, version);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("unreadable state payload", e);
    }
  }

  private Phase readAwaitingTools(JsonNode root) {
    Message turn = readMessage(root.get("assistantTurn"));
    Set<String> pending = new HashSet<>();
    root.get("pending").forEach(n -> pending.add(n.asText()));
    List<ToolResultBlock> gathered = new ArrayList<>();
    root.get("gathered").forEach(n -> gathered.add(readResultBlock(n)));
    return new Phase.AwaitingTools(turn, pending, gathered);
  }

  private ObjectNode writeMessage(Message message) {
    ObjectNode node = mapper.createObjectNode();
    node.put("role", message.role().name());
    ArrayNode content = node.putArray("content");
    message.content().forEach(b -> content.add(writeBlock(b)));
    return node;
  }

  private Message readMessage(JsonNode node) {
    List<ContentBlock> content = new ArrayList<>();
    node.get("content").forEach(b -> content.add(readBlock(b)));
    return new Message(Role.valueOf(node.get("role").asText()), content);
  }

  private ObjectNode writeBlock(ContentBlock block) {
    ObjectNode node = mapper.createObjectNode();
    switch (block) {
      case TextBlock(String text) -> node.put("type", "text").put("text", text);
      case ThinkingBlock t -> {
        node.put("type", "thinking").put("text", t.text());
        if (t.signature() != null) {
          node.put("signature", t.signature());
        }
      }
      case RedactedThinkingBlock(String data) ->
          node.put("type", "redacted_thinking").put("data", data);
      case ToolUseBlock(ToolCall call, String signature) -> {
        node.put("type", "tool_use")
            .put("id", call.id())
            .put("name", call.name());
        node.set("arguments", call.arguments());
        if (signature != null) {
          node.put("signature", signature);
        }
      }
      case ToolResultBlock b -> node.set("block", writeResultBlock(b));
      case ImageBlock(String mediaType, String base64Data) ->
          node.put("type", "image").put("mediaType", mediaType).put("data", base64Data);
    }
    return node;
  }

  private ContentBlock readBlock(JsonNode node) {
    if (node.has("block")) {
      return readResultBlock(node.get("block"));
    }
    String type = node.get("type").asText();
    return switch (type) {
      case "text" -> new TextBlock(node.get("text").asText());
      case "thinking" ->
          new ThinkingBlock(
              node.get("text").asText(),
              node.has("signature") ? node.get("signature").asText() : null);
      case "redacted_thinking" -> new RedactedThinkingBlock(node.get("data").asText());
      case "tool_use" ->
          new ToolUseBlock(
              new ToolCall(
                  node.get("id").asText(), node.get("name").asText(), node.get("arguments")),
              node.has("signature") ? node.get("signature").asText() : null);
      case "image" ->
          new ImageBlock(node.get("mediaType").asText(), node.get("data").asText()); // data ↔ base64Data
      default -> throw new IllegalArgumentException("unknown content block type: " + type);
    };
  }

  private ObjectNode writeResultBlock(ToolResultBlock block) {
    ObjectNode node = mapper.createObjectNode();
    node.put("type", "tool_result")
        .put("toolUseId", block.toolUseId())
        .put("content", block.content())
        .put("isError", block.isError());
    return node;
  }

  private ToolResultBlock readResultBlock(JsonNode node) {
    return new ToolResultBlock(
        node.get("toolUseId").asText(),
        node.get("content").asText(),
        node.get("isError").asBoolean());
  }
}
```

**Implementer note:** core block components verified 2026-08-20: `TextBlock(String text)`,
`ThinkingBlock(String text, String signature)`, `RedactedThinkingBlock(String data)`,
`ImageBlock(String mediaType, String base64Data)`, `ToolResultBlock(String toolUseId, String
content, boolean isError)`. The code above matches them; the round-trip tests are the contract.

- [ ] **Step 5: Run to verify it passes**

Run: `./mvnw -q -pl nessy-agent test`
Expected: PASS.

- [ ] **Step 6: Format and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-agent
git commit -m "feat: State rides JSON with a loud discriminator"
```

---

### Task 6: The state store — SPI, exception, in-memory CAS

**Files:**
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/store/AgentStateStore.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/store/StaleStateException.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/store/InMemoryAgentStateStore.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/store/InMemoryAgentStateStoreTest.java`

**Interfaces:**
- Consumes: `State`, `Phase` (Tasks 3–5).
- Produces (the contract every store implements and the Plan-2 shell calls):
  - `AgentStateStore.load()` → `State` (never null; a fresh scope loads `State.initial()`).
  - `AgentStateStore.save(State state)` → persists at `state.version() + 1` **iff** the stored version still equals `state.version()`, else throws `StaleStateException`. The caller passes the state it loaded with the new phase — it never computes the next version (spec §3.4).
  - `StaleStateException extends RuntimeException`.

- [ ] **Step 1: Write the failing test**

`InMemoryAgentStateStoreTest.java`:

```java
package org.jwcarman.nessy.agent.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.State;

class InMemoryAgentStateStoreTest {

  @Test
  void aFreshScopeLoadsTheInitialState() {
    assertThat(new InMemoryAgentStateStore().load()).isEqualTo(State.initial());
  }

  @Test
  void aSaveAdvancesTheVersionByExactlyOne() {
    var store = new InMemoryAgentStateStore();
    store.save(new State(new Phase.AwaitingModel(), store.load().version()));
    assertThat(store.load()).isEqualTo(new State(new Phase.AwaitingModel(), 1L));
  }

  @Test
  void aSaveAgainstAStaleVersionIsRefused() {
    var store = new InMemoryAgentStateStore();
    store.save(new State(new Phase.AwaitingModel(), 0L)); // stored version is now 1
    var stale = new State(new Phase.Idle(), 0L);
    assertThatThrownBy(() -> store.save(stale)).isInstanceOf(StaleStateException.class);
  }

  @Test
  void racingSaversProduceExactlyOneWinnerPerVersion() throws Exception {
    var store = new InMemoryAgentStateStore();
    int racers = 16;
    List<Callable<Boolean>> attempts = new ArrayList<>();
    for (int i = 0; i < racers; i++) {
      attempts.add(
          () -> {
            try {
              store.save(new State(new Phase.AwaitingModel(), 0L));
              return true;
            } catch (StaleStateException e) {
              return false;
            }
          });
    }
    List<Boolean> outcomes = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
      for (var future : pool.invokeAll(attempts)) {
        outcomes.add(future.get());
      }
    }
    assertThat(outcomes).isNotEmpty();
    assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(1L);
    assertThat(store.load().version()).isEqualTo(1L);
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q -pl nessy-agent test`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement the SPI and exception**

`AgentStateStore.java`:

```java
package org.jwcarman.nessy.agent.store;

import org.jwcarman.nessy.agent.State;

/**
 * Owns a scope's control state. Pre-scoped: no id parameter anywhere (spec §3.5). Every
 * implementation enforces the version CAS — it is the system's only lock (spec §3.2), the
 * in-memory store included.
 */
public interface AgentStateStore {

  /** Never null; a scope that has never saved loads {@link State#initial()}. */
  State load();

  /**
   * Persists {@code state.phase()} at {@code state.version() + 1} if and only if the stored
   * version still equals {@code state.version()}; otherwise throws {@link StaleStateException}.
   * The caller passes the state it loaded — it never computes the next version (spec §3.4).
   */
  void save(State state);
}
```

`StaleStateException.java`:

```java
package org.jwcarman.nessy.agent.store;

/** Another writer advanced the scope first. Reload, re-handle, retry (spec §3.4). */
public class StaleStateException extends RuntimeException {

  public StaleStateException(long expected, long actual) {
    super("expected version " + expected + " but store holds " + actual);
  }
}
```

- [ ] **Step 4: Implement the in-memory store**

`InMemoryAgentStateStore.java`:

```java
package org.jwcarman.nessy.agent.store;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.nessy.agent.State;

/**
 * One scope, one atomic reference. Versioning is enforced exactly as a JDBC store would: the CAS
 * is the concurrency model, and a store that skips it removes the lock (spec §3.2).
 */
public final class InMemoryAgentStateStore implements AgentStateStore {

  private final AtomicReference<State> current = new AtomicReference<>(State.initial());

  @Override
  public State load() {
    return current.get();
  }

  @Override
  public void save(State state) {
    Objects.requireNonNull(state, "state must not be null");
    State next = new State(state.phase(), state.version() + 1);
    while (true) {
      State stored = current.get();
      if (stored.version() != state.version()) {
        throw new StaleStateException(state.version(), stored.version());
      }
      if (current.compareAndSet(stored, next)) {
        return;
      }
    }
  }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./mvnw -q -pl nessy-agent test`
Expected: PASS, including the 16-thread race (exactly one winner).

- [ ] **Step 6: Full build, format, commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-agent
git commit -m "feat: the store is the lock — versioned saves, in memory first"
```

---

## What Plans 2–4 pick up from here

- **Plan 2 (shell + executors):** `Agent<O>`, `DefaultAgent` (`apply` = load–handle–save–dispatch with retry, `drive()` both arms), `Sink`, `ModelCallExecutor`/`ToolCallExecutor` SPIs, `Backlog<O>`, `ObservationRenderer<O>`, pumped-executor tests. Consumes every type produced above.
- **Plan 3 (memory, desk, narration, builders):** `Memory` SPI + verbatim/in-memory impl, the park desk with expiry, `TurnEvent`/`TurnObserver` adjustments (`TurnEnded` reshape, `ToolCallParked` deletion), the sync adapter, `Nessy.cli()/web()/autonomous()`.
- **Plan 4 (core distillation):** shed the old loop from `nessy-core` per spec §9's table, re-point provider modules, add the dependency-direction enforcer.

