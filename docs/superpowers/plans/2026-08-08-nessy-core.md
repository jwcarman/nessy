# Nessy Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `nessy-core` and `nessy-testing` — a complete, streaming, tool-calling agent loop with an approval gate, provably correct against a scripted model provider with no network access.

**Architecture:** A pure reducer (`SessionState + Event -> Step`) carries all agent semantics and performs no I/O. An `ExecutionEngine` owns the impure half: it executes the `Effect`s the reducer emits, drives the model stream on a virtual thread, and feeds every arrival back through the reducer. Streaming tokens are ordinary events, so the loop streams natively rather than by retrofit.

**Tech Stack:** Java 25, Maven multi-module, Jackson (databind), victools jsonschema-generator, JUnit Jupiter, AssertJ.

**Source spec:** `docs/superpowers/specs/2026-08-08-nessy-agent-harness-design.md`

**Scope:** This is Plan 1 of 6. It delivers `nessy-core` + `nessy-testing`. Out of scope, each getting its own plan later: `nessy-model-anthropic`, `nessy-model-openai`, `DurableEngine` and parking, `nessy-spring-boot-starter`, `nessy-tui`.

## Global Constraints

- **Java 25.** `<maven.compiler.release>25</maven.compiler.release>`. Records, sealed interfaces, and pattern matching for switch are load-bearing.
- **groupId** `org.jwcarman.nessy`, **version** `0.1.0-SNAPSHOT`, base package `org.jwcarman.nessy`. Matches the sibling `substrate` project's convention (`org.jwcarman.substrate`).
- **No star imports.** Every import is a single explicit symbol, including static imports. This is a hard project rule.
- **No warning suppression.** No `@SuppressWarnings` of any kind. Fix the underlying cause instead.
- **Apache License 2.0.** Every `.java` file carries the Apache 2.0 header applied by the mycila `license-maven-plugin` under the non-default `license` profile. Run `mvn license:format -Plicense` after creating files, and commit the headers.
- **Spotless with Google Java Format is enforced.** `spotless:check` is bound to the `validate` phase, so an unformatted file fails the build. Run `mvn spotless:apply` before committing. This reformats the plan's code samples to Google style (2-space indent) — that reformatting is expected and correct, not a deviation from the plan.
- **`nessy-core` depends only on** `jackson-databind`, `jackson-annotations`, and `victools jsonschema-generator` + `jsonschema-module-jackson`. No Spring, no reactive types, no model SDKs.
- **Dependency versions:** jackson `2.19.2`, victools `4.38.0`, junit-jupiter `6.1.2`, assertj-core `3.27.7`, maven-surefire-plugin `3.5.6`.
- **Live tests are tagged `live` and excluded by default.** `mvn verify` must pass with no API key and no network. This plan produces zero live tests, but the exclusion is configured now so later plans inherit it.
- **Every seam is a plain blocking interface.** No `CompletableFuture`, no `Flow.Publisher`, anywhere.
- Commit after every task.

---

## File Structure

**`nessy-core/src/main/java/org/jwcarman/nessy/core/`** — the reducer and its data. Depends on nothing outside itself.

| File | Responsibility |
|---|---|
| `SessionId.java` | Opaque session identifier |
| `Role.java` | `USER` / `ASSISTANT` |
| `ContentBlock.java` | Sealed: `TextBlock`, `ToolUseBlock`, `ToolResultBlock` |
| `TextBlock.java`, `ToolUseBlock.java`, `ToolResultBlock.java` | The three block types |
| `Message.java` | A role plus its content blocks |
| `ToolCall.java` | Model's request to run a tool: id, name, JSON arguments |
| `ToolResult.java` | A tool's outcome: content plus error flag |
| `StopReason.java` | Why a model turn ended |
| `Decision.java` | Sealed: `Allow`, `Deny(reason)` — lives here so `Event` can reference it |
| `SessionStatus.java` | Lifecycle status |
| `SessionState.java` | The whole reducer state, serializable by construction |
| `Event.java` | Sealed: things that happened |
| `Effect.java` | Sealed: things that should happen |
| `Step.java` | Reducer output: next state plus effects |
| `Reducer.java` | The pure function |
| `Awaited.java` | Sealed: `Ready<T>`, `Parked<T>` |
| `ParkToken.java` | Single-use resume token |

**`nessy-core/src/main/java/org/jwcarman/nessy/tool/`** — the tool seam.

| File | Responsibility |
|---|---|
| `Tool.java` | `Tool<T>` — name, description, input record type, approval requirement, execute |
| `ToolSpec.java` | Wire-neutral description handed to providers: name, description, JSON schema |
| `ToolContext.java` | What a tool learns about its invocation |
| `ToolRegistry.java` | Lookup by name plus specs for the model |
| `MapToolRegistry.java` | Default registry over a `Map` |
| `Schemas.java` | Record → JSON Schema via victools |
| `ToolInvoker.java` | Deserializes arguments and invokes, bridging the generic capture |

**`nessy-core/src/main/java/org/jwcarman/nessy/model/`** — the model seam.

| File | Responsibility |
|---|---|
| `ModelProvider.java` | `stream(ModelRequest)` plus `capabilities()` |
| `ModelRequest.java` | Everything a provider needs for one call |
| `ModelEvent.java` | Sealed: `TextChunk`, `ToolUseEmitted`, `TurnEnded` |
| `ModelStream.java` | `AutoCloseable Iterable<ModelEvent>` |
| `Capability.java` | What a provider supports |

**`nessy-core/src/main/java/org/jwcarman/nessy/approval/`** — the approval seam.

| File | Responsibility |
|---|---|
| `Approver.java` | The interceptor |
| `ApprovalRequest.java` | What the human is being asked |
| `ApproveEverything.java`, `DenyEverything.java` | Non-interactive modes |

**`nessy-core/src/main/java/org/jwcarman/nessy/session/`** — persistence seam.

| File | Responsibility |
|---|---|
| `SessionStore.java` | `load` / `save` / `consumeToken` |
| `InMemorySessionStore.java` | Default |

**`nessy-core/src/main/java/org/jwcarman/nessy/engine/`** — execution.

| File | Responsibility |
|---|---|
| `ExecutionEngine.java` | `run` / `resume` |
| `RunOutcome.java` | Sealed: `Completed`, `Parked` |
| `AgentEventListener.java` | Every front-end's window into the loop |
| `AgentConfig.java` | Model name, system prompt, max tokens |
| `InProcessEngine.java` | Default engine: virtual threads, never parks |
| `Nessy.java` | Builder facade |

**`nessy-testing/src/main/java/org/jwcarman/nessy/testing/`**

| File | Responsibility |
|---|---|
| `ScriptedModelProvider.java` | Replays canned turns; records the requests it received |
| `RecordingEventListener.java` | Captures events for assertions |

---

### Task 1: Maven skeleton

**Files:**
- Create: `pom.xml`
- Create: `nessy-core/pom.xml`
- Create: `nessy-testing/pom.xml`
- Create: `.gitignore`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/BuildSmokeTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: two Maven modules, `org.jwcarman.nessy:nessy-core:0.1.0-SNAPSHOT` and `org.jwcarman.nessy:nessy-testing:0.1.0-SNAPSHOT`, both on Java 25 with JUnit and AssertJ on the test classpath, and surefire excluding the `live` group.

- [ ] **Step 1: Write the failing test**

Create `nessy-core/src/test/java/org/jwcarman/nessy/BuildSmokeTest.java`:

```java
package org.jwcarman.nessy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildSmokeTest {

    @Test
    void runsOnJava25OrLater() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(25);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q test`
Expected: FAIL — there is no `pom.xml`, so Maven reports "The goal you specified requires a project to execute but there is no POM in this directory".

- [ ] **Step 3: Write the parent POM**

Create `pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>org.jwcarman.nessy</groupId>
  <artifactId>nessy-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <name>Nessy</name>
  <description>An AI agent harness framework for Java</description>

  <modules>
    <module>nessy-core</module>
    <module>nessy-testing</module>
  </modules>

  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>25</maven.compiler.release>
    <jackson.version>2.19.2</jackson.version>
    <victools.version>4.38.0</victools.version>
    <junit.version>6.1.2</junit.version>
    <assertj.version>3.27.7</assertj.version>
    <maven-surefire-plugin.version>3.5.6</maven-surefire-plugin.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.jwcarman.nessy</groupId>
        <artifactId>nessy-core</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>${jackson.version}</version>
      </dependency>
      <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-annotations</artifactId>
        <version>${jackson.version}</version>
      </dependency>
      <dependency>
        <groupId>com.github.victools</groupId>
        <artifactId>jsonschema-generator</artifactId>
        <version>${victools.version}</version>
      </dependency>
      <dependency>
        <groupId>com.github.victools</groupId>
        <artifactId>jsonschema-module-jackson</artifactId>
        <version>${victools.version}</version>
      </dependency>
      <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>${junit.version}</version>
      </dependency>
      <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <version>${assertj.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
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

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-surefire-plugin</artifactId>
          <version>${maven-surefire-plugin.version}</version>
          <configuration>
            <!-- Tests that spend real tokens are tagged `live`. The default
                 build stays green with no API key and no network. -->
            <excludedGroups>live</excludedGroups>
          </configuration>
        </plugin>
      </plugins>
    </pluginManagement>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 4: Write the module POMs**

Create `nessy-core/pom.xml`:

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

  <artifactId>nessy-core</artifactId>
  <name>Nessy Core</name>

  <dependencies>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-annotations</artifactId>
    </dependency>
    <dependency>
      <groupId>com.github.victools</groupId>
      <artifactId>jsonschema-generator</artifactId>
    </dependency>
    <dependency>
      <groupId>com.github.victools</groupId>
      <artifactId>jsonschema-module-jackson</artifactId>
    </dependency>
  </dependencies>
</project>
```

Create `nessy-testing/pom.xml`:

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

  <artifactId>nessy-testing</artifactId>
  <name>Nessy Testing</name>

  <dependencies>
    <dependency>
      <groupId>org.jwcarman.nessy</groupId>
      <artifactId>nessy-core</artifactId>
    </dependency>
  </dependencies>
</project>
```

- [ ] **Step 5: Write .gitignore**

Create `.gitignore`:

```
target/
*.iml
.idea/
.DS_Store
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -q test`
Expected: PASS. `BuildSmokeTest` runs green in `nessy-core`.

- [ ] **Step 7: Commit**

```bash
git add pom.xml nessy-core/pom.xml nessy-testing/pom.xml .gitignore nessy-core/src/test/java/org/jwcarman/nessy/BuildSmokeTest.java
git commit -m "build: add Maven skeleton for nessy-core and nessy-testing"
```

---

### Task 2: Core message model

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/core/SessionId.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/core/Role.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/core/ContentBlock.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/core/TextBlock.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/core/ToolUseBlock.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/core/ToolResultBlock.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/core/ToolCall.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/core/ToolResult.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/core/StopReason.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/core/MessageTest.java`

**Interfaces:**
- Consumes: Task 1's `nessy-core` module.
- Produces: `SessionId.random()`, `SessionId(String value)`; `Role.USER`, `Role.ASSISTANT`; sealed `ContentBlock` permitting `TextBlock(String text)`, `ToolUseBlock(ToolCall call)`, `ToolResultBlock(String toolUseId, String content, boolean isError)`; `Message(Role role, List<ContentBlock> content)` with statics `Message.user(String)`, `Message.assistant(List<ContentBlock>)`, `Message.toolResults(List<ContentBlock>)`; `ToolCall(String id, String name, JsonNode arguments)`; `ToolResult(String content, boolean isError)` with statics `ToolResult.ok(String)`, `ToolResult.error(String)`; `StopReason.END_TURN`, `StopReason.TOOL_USE`, `StopReason.MAX_TOKENS`.

- [ ] **Step 1: Write the failing test**

Create `nessy-core/src/test/java/org/jwcarman/nessy/core/MessageTest.java`:

```java
package org.jwcarman.nessy.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageTest {

    @Test
    void userMessageWrapsTextInABlock() {
        Message message = Message.user("hello");

        assertThat(message.role()).isEqualTo(Role.USER);
        assertThat(message.content()).containsExactly(new TextBlock("hello"));
    }

    @Test
    void toolResultsAreCarriedOnAUserMessage() {
        ToolResultBlock block = new ToolResultBlock("call_1", "contents", false);

        Message message = Message.toolResults(List.of(block));

        assertThat(message.role()).isEqualTo(Role.USER);
        assertThat(message.content()).containsExactly(block);
    }

    @Test
    void contentIsDefensivelyCopied() {
        List<ContentBlock> mutable = new ArrayList<>();
        mutable.add(new TextBlock("first"));

        Message message = new Message(Role.ASSISTANT, mutable);
        mutable.add(new TextBlock("sneaked in"));

        assertThat(message.content()).hasSize(1);
    }

    @Test
    void contentIsUnmodifiable() {
        Message message = Message.user("hello");

        assertThatThrownBy(() -> message.content().add(new TextBlock("nope")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toolResultFactoriesSetTheErrorFlag() {
        assertThat(ToolResult.ok("fine").isError()).isFalse();
        assertThat(ToolResult.error("boom").isError()).isTrue();
        assertThat(ToolResult.error("boom").content()).isEqualTo("boom");
    }

    @Test
    void randomSessionIdsAreDistinct() {
        assertThat(SessionId.random()).isNotEqualTo(SessionId.random());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -pl nessy-core test`
Expected: FAIL — compilation errors, `cannot find symbol: class Message`.

- [ ] **Step 3: Write the implementation**

`SessionId.java`:

```java
package org.jwcarman.nessy.core;

import java.util.UUID;

/** Identifies one conversation. Opaque on purpose: the store chooses what it means. */
public record SessionId(String value) {

    public SessionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("session id must not be blank");
        }
    }

    public static SessionId random() {
        return new SessionId(UUID.randomUUID().toString());
    }
}
```

`Role.java`:

```java
package org.jwcarman.nessy.core;

/**
 * Who a message came from.
 *
 * <p>There is no {@code SYSTEM} role: the system prompt is a separate field on
 * {@code ModelRequest}, not a message, because that is what the providers we
 * target actually model.
 */
public enum Role {
    USER,
    ASSISTANT
}
```

`ContentBlock.java`:

```java
package org.jwcarman.nessy.core;

/** One piece of a message. Messages are lists of these, not strings. */
public sealed interface ContentBlock permits TextBlock, ToolUseBlock, ToolResultBlock {}
```

`TextBlock.java`:

```java
package org.jwcarman.nessy.core;

/** Prose, from either side of the conversation. */
public record TextBlock(String text) implements ContentBlock {}
```

`ToolUseBlock.java`:

```java
package org.jwcarman.nessy.core;

/** The model asking for a tool to run. Always on an assistant message. */
public record ToolUseBlock(ToolCall call) implements ContentBlock {}
```

`ToolResultBlock.java`:

```java
package org.jwcarman.nessy.core;

/**
 * What a tool produced, addressed back to the call that asked for it.
 *
 * <p>Carried on a {@link Role#USER} message: the model asked, so the harness
 * answers, and to the model an answer arrives from the user side.
 */
public record ToolResultBlock(String toolUseId, String content, boolean isError) implements ContentBlock {}
```

`ToolCall.java`:

```java
package org.jwcarman.nessy.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The model's request to run one tool.
 *
 * @param id        provider-assigned; the tool result must quote it back
 * @param name      the tool's registered name
 * @param arguments raw JSON, not yet bound to the tool's input record
 */
public record ToolCall(String id, String name, JsonNode arguments) {}
```

`ToolResult.java`:

```java
package org.jwcarman.nessy.core;

/**
 * What a tool produced.
 *
 * <p>{@code isError} is the factor-9 hinge: an errored result still flows into
 * context so the model can recover, rather than blowing up the loop.
 */
public record ToolResult(String content, boolean isError) {

    public static ToolResult ok(String content) {
        return new ToolResult(content, false);
    }

    public static ToolResult error(String content) {
        return new ToolResult(content, true);
    }
}
```

`StopReason.java`:

```java
package org.jwcarman.nessy.core;

/** Why a model turn ended. */
public enum StopReason {
    END_TURN,
    TOOL_USE,
    MAX_TOKENS
}
```

`Message.java`:

```java
package org.jwcarman.nessy.core;

import java.util.List;

/** One turn of the conversation, as a role and its content blocks. */
public record Message(Role role, List<ContentBlock> content) {

    public Message {
        content = List.copyOf(content);
    }

    public static Message user(String text) {
        return new Message(Role.USER, List.of(new TextBlock(text)));
    }

    public static Message assistant(List<ContentBlock> content) {
        return new Message(Role.ASSISTANT, content);
    }

    /** Tool results go back as a user message — see {@link ToolResultBlock}. */
    public static Message toolResults(List<ContentBlock> results) {
        return new Message(Role.USER, results);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl nessy-core test`
Expected: PASS — all six tests in `MessageTest` green.

- [ ] **Step 5: Commit**

```bash
git add nessy-core/src/main/java/org/jwcarman/nessy/core nessy-core/src/test/java/org/jwcarman/nessy/core
git commit -m "feat(core): add message, tool call, and tool result model"
```

---

### Task 3: SessionState

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/core/SessionStatus.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/core/SessionState.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/core/SessionStateTest.java`

**Interfaces:**
- Consumes: Task 2's `SessionId`, `Message`, `ContentBlock`, `ToolCall`.
- Produces: `SessionStatus` with `IDLE`, `AWAITING_MODEL`, `AWAITING_APPROVAL`, `EXECUTING_TOOL`, `COMPLETE`, `FAILED`; `SessionState(SessionId id, List<Message> messages, List<ContentBlock> pendingBlocks, List<ToolCall> pendingCalls, List<ContentBlock> pendingResults, int consecutiveErrors, SessionStatus status)` with `SessionState.newSession(SessionId)` and withers `with(SessionStatus)`, `withMessageAppended(Message)`, `withPendingBlocks(List<ContentBlock>)`, `withPendingCalls(List<ToolCall>)`, `withPendingResults(List<ContentBlock>)`, `withConsecutiveErrors(int)`.

- [ ] **Step 1: Write the failing test**

Create `nessy-core/src/test/java/org/jwcarman/nessy/core/SessionStateTest.java`:

```java
package org.jwcarman.nessy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SessionStateTest {

    private static final SessionId ID = new SessionId("s1");

    @Test
    void newSessionStartsEmptyAndIdle() {
        SessionState state = SessionState.newSession(ID);

        assertThat(state.id()).isEqualTo(ID);
        assertThat(state.messages()).isEmpty();
        assertThat(state.pendingBlocks()).isEmpty();
        assertThat(state.pendingCalls()).isEmpty();
        assertThat(state.pendingResults()).isEmpty();
        assertThat(state.consecutiveErrors()).isZero();
        assertThat(state.status()).isEqualTo(SessionStatus.IDLE);
    }

    @Test
    void withersReturnNewInstancesAndLeaveTheOriginalAlone() {
        SessionState original = SessionState.newSession(ID);

        SessionState changed = original
                .withMessageAppended(Message.user("hi"))
                .with(SessionStatus.AWAITING_MODEL)
                .withConsecutiveErrors(2);

        assertThat(changed.messages()).hasSize(1);
        assertThat(changed.status()).isEqualTo(SessionStatus.AWAITING_MODEL);
        assertThat(changed.consecutiveErrors()).isEqualTo(2);

        assertThat(original.messages()).isEmpty();
        assertThat(original.status()).isEqualTo(SessionStatus.IDLE);
        assertThat(original.consecutiveErrors()).isZero();
    }

    @Test
    void allListsAreUnmodifiable() {
        SessionState state = SessionState.newSession(ID);

        assertThat(state.messages()).isUnmodifiable();
        assertThat(state.pendingBlocks()).isUnmodifiable();
        assertThat(state.pendingCalls()).isUnmodifiable();
        assertThat(state.pendingResults()).isUnmodifiable();
    }

    @Test
    void withPendingBlocksReplacesRatherThanAppends() {
        SessionState state = SessionState.newSession(ID)
                .withPendingBlocks(List.of(new TextBlock("a")))
                .withPendingBlocks(List.of(new TextBlock("b")));

        assertThat(state.pendingBlocks()).containsExactly(new TextBlock("b"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -pl nessy-core test`
Expected: FAIL — `cannot find symbol: class SessionState`.

- [ ] **Step 3: Write the implementation**

`SessionStatus.java`:

```java
package org.jwcarman.nessy.core;

/** Where a session is in its lifecycle. */
public enum SessionStatus {

    /** Nothing has happened yet, or the last turn finished and we are waiting on a human. */
    IDLE,

    /** A model call is in flight. */
    AWAITING_MODEL,

    /** A tool call needs an approval decision before it can run. */
    AWAITING_APPROVAL,

    /** An approved tool is running. */
    EXECUTING_TOOL,

    /** The model ended its turn with nothing left to do. */
    COMPLETE,

    /** Too many consecutive tool errors. The loop gave up rather than burn tokens. */
    FAILED
}
```

`SessionState.java`:

```java
package org.jwcarman.nessy.core;

import java.util.List;

/**
 * Everything the loop knows, as data.
 *
 * <p>This record is the whole of the agent's memory. It holds no connections, no
 * threads, and no callbacks, which is what makes the reducer pure, the loop
 * testable without a network, and durable resume a storage concern rather than
 * an engine change.
 *
 * @param id                the session this state belongs to
 * @param messages          the settled conversation
 * @param pendingBlocks     the assistant message currently being streamed in
 * @param pendingCalls      tool calls the model asked for and we have not finished
 * @param pendingResults    results collected so far, flushed as one user message
 *                          when the last pending call resolves
 * @param consecutiveErrors errored tool results in a row; any success resets it
 * @param status            lifecycle position
 */
public record SessionState(
        SessionId id,
        List<Message> messages,
        List<ContentBlock> pendingBlocks,
        List<ToolCall> pendingCalls,
        List<ContentBlock> pendingResults,
        int consecutiveErrors,
        SessionStatus status) {

    public SessionState {
        messages = List.copyOf(messages);
        pendingBlocks = List.copyOf(pendingBlocks);
        pendingCalls = List.copyOf(pendingCalls);
        pendingResults = List.copyOf(pendingResults);
    }

    public static SessionState newSession(SessionId id) {
        return new SessionState(id, List.of(), List.of(), List.of(), List.of(), 0, SessionStatus.IDLE);
    }

    public SessionState with(SessionStatus newStatus) {
        return new SessionState(
                id, messages, pendingBlocks, pendingCalls, pendingResults, consecutiveErrors, newStatus);
    }

    public SessionState withMessageAppended(Message message) {
        List<Message> appended = new java.util.ArrayList<>(messages);
        appended.add(message);
        return new SessionState(
                id, appended, pendingBlocks, pendingCalls, pendingResults, consecutiveErrors, status);
    }

    public SessionState withPendingBlocks(List<ContentBlock> blocks) {
        return new SessionState(id, messages, blocks, pendingCalls, pendingResults, consecutiveErrors, status);
    }

    public SessionState withPendingCalls(List<ToolCall> calls) {
        return new SessionState(id, messages, pendingBlocks, calls, pendingResults, consecutiveErrors, status);
    }

    public SessionState withPendingResults(List<ContentBlock> results) {
        return new SessionState(id, messages, pendingBlocks, pendingCalls, results, consecutiveErrors, status);
    }

    public SessionState withConsecutiveErrors(int errors) {
        return new SessionState(id, messages, pendingBlocks, pendingCalls, pendingResults, errors, status);
    }
}
```

Replace the inline `java.util.ArrayList` reference with a proper import — the file's imports must read:

```java
import java.util.ArrayList;
import java.util.List;
```

and the body uses `new ArrayList<>(messages)`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl nessy-core test`
Expected: PASS — four tests in `SessionStateTest` green.

- [ ] **Step 5: Commit**

```bash
git add nessy-core/src/main/java/org/jwcarman/nessy/core nessy-core/src/test/java/org/jwcarman/nessy/core
git commit -m "feat(core): add SessionState and SessionStatus"
```

---

### Task 4: Events, effects, and Step

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/core/Decision.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/core/Event.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/core/Effect.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/core/Step.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/core/EventTest.java`

**Interfaces:**
- Consumes: Task 2's `ToolCall`, `ToolResult`, `StopReason`.
- Produces: sealed `Decision` permitting `Decision.Allow` and `Decision.Deny(String reason)`, with `Decision.allow()` returning a shared instance; sealed `Event` permitting nested records `Event.UserSaid(String text)`, `Event.TextDelta(String text)`, `Event.ToolCallRequested(ToolCall call)`, `Event.ModelTurnEnded(StopReason reason)`, `Event.ApprovalDecided(ToolCall call, Decision decision)`, `Event.ToolFinished(ToolCall call, ToolResult result)`; sealed `Effect` permitting `Effect.CallModel`, `Effect.RequestApproval(ToolCall call)`, `Effect.ExecuteTool(ToolCall call)`, with `Effect.callModel()` returning a shared instance; `Step(SessionState state, List<Effect> effects)` with `Step.of(SessionState, Effect...)`.

- [ ] **Step 1: Write the failing test**

Create `nessy-core/src/test/java/org/jwcarman/nessy/core/EventTest.java`:

```java
package org.jwcarman.nessy.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventTest {

    @Test
    void eventsAreExhaustivelyMatchable() {
        Event event = new Event.UserSaid("hello");

        String described = switch (event) {
            case Event.UserSaid e -> "user:" + e.text();
            case Event.TextDelta e -> "delta:" + e.text();
            case Event.ToolCallRequested e -> "call:" + e.call().name();
            case Event.ModelTurnEnded e -> "end:" + e.reason();
            case Event.ApprovalDecided e -> "approval:" + e.call().name();
            case Event.ToolFinished e -> "finished:" + e.call().name();
        };

        assertThat(described).isEqualTo("user:hello");
    }

    @Test
    void allowIsASharedInstance() {
        assertThat(Decision.allow()).isSameAs(Decision.allow());
    }

    @Test
    void denyCarriesItsReason() {
        Decision decision = new Decision.Deny("user pressed n");

        assertThat(decision).isInstanceOf(Decision.Deny.class);
        assertThat(((Decision.Deny) decision).reason()).isEqualTo("user pressed n");
    }

    @Test
    void stepOfCollectsItsEffects() {
        SessionState state = SessionState.newSession(new SessionId("s1"));

        Step step = Step.of(state, Effect.callModel());

        assertThat(step.state()).isSameAs(state);
        assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void stepEffectsAreUnmodifiable() {
        SessionState state = SessionState.newSession(new SessionId("s1"));

        assertThat(Step.of(state).effects()).isUnmodifiable();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -pl nessy-core test`
Expected: FAIL — `cannot find symbol: class Event`.

- [ ] **Step 3: Write the implementation**

`Decision.java`:

```java
package org.jwcarman.nessy.core;

/**
 * The answer to an approval question.
 *
 * <p>Lives in {@code core} rather than {@code approval} so {@link Event} can
 * reference it without the core package depending on the approval package.
 */
public sealed interface Decision {

    /** Run it. */
    record Allow() implements Decision {}

    /** Do not run it. The reason goes into context so the model can adapt. */
    record Deny(String reason) implements Decision {}

    Allow ALLOW = new Allow();

    static Decision allow() {
        return ALLOW;
    }
}
```

`Event.java`:

```java
package org.jwcarman.nessy.core;

/**
 * Something that happened.
 *
 * <p>Events are the only input to {@link Reducer}. Streaming text arrives as
 * ordinary events, which is why the loop streams natively instead of growing a
 * second code path for it.
 */
public sealed interface Event {

    /** A human said something. */
    record UserSaid(String text) implements Event {}

    /** A chunk of assistant prose arrived from the stream. */
    record TextDelta(String text) implements Event {}

    /** The model finished emitting one complete tool call. */
    record ToolCallRequested(ToolCall call) implements Event {}

    /** The model's turn is over. */
    record ModelTurnEnded(StopReason reason) implements Event {}

    /** The approval question for one call has been answered. */
    record ApprovalDecided(ToolCall call, Decision decision) implements Event {}

    /** A tool ran to completion, successfully or not. */
    record ToolFinished(ToolCall call, ToolResult result) implements Event {}
}
```

`Effect.java`:

```java
package org.jwcarman.nessy.core;

/**
 * Something that should happen.
 *
 * <p>The reducer emits these; the engine performs them. The reducer never does
 * I/O, so every side effect in the system is named here.
 */
public sealed interface Effect {

    /** Call the model with the conversation as it now stands. */
    record CallModel() implements Effect {}

    /**
     * Resolve the approval question for a call.
     *
     * <p>Note this says <em>resolve</em>, not <em>prompt</em>. For a tool whose
     * {@code requiresApproval()} is false the engine answers {@link Decision#allow()}
     * itself without troubling the approver. The reducer stays tool-agnostic and
     * the model still cannot route around the gate.
     */
    record RequestApproval(ToolCall call) implements Effect {}

    /** Run an approved tool. */
    record ExecuteTool(ToolCall call) implements Effect {}

    CallModel CALL_MODEL = new CallModel();

    static Effect callModel() {
        return CALL_MODEL;
    }
}
```

`Step.java`:

```java
package org.jwcarman.nessy.core;

import java.util.List;

/** What one turn of the reducer produced: the next state, and what to do about it. */
public record Step(SessionState state, List<Effect> effects) {

    public Step {
        effects = List.copyOf(effects);
    }

    public static Step of(SessionState state, Effect... effects) {
        return new Step(state, List.of(effects));
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl nessy-core test`
Expected: PASS — five tests in `EventTest` green.

- [ ] **Step 5: Commit**

```bash
git add nessy-core/src/main/java/org/jwcarman/nessy/core nessy-core/src/test/java/org/jwcarman/nessy/core
git commit -m "feat(core): add Event, Effect, Decision, and Step"
```

---

### Task 5: Reducer — user input and streaming text

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/core/Reducer.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/core/ReducerTextTest.java`

**Interfaces:**
- Consumes: Tasks 2–4.
- Produces: `Reducer(int maxConsecutiveErrors)` with `Reducer.DEFAULT_MAX_CONSECUTIVE_ERRORS = 3`, `Reducer.withDefaults()`, and `Step reduce(SessionState state, Event event)`. This task implements `UserSaid`, `TextDelta`, and the no-tool-calls path of `ModelTurnEnded`; later tasks fill in the rest.

- [ ] **Step 1: Write the failing test**

Create `nessy-core/src/test/java/org/jwcarman/nessy/core/ReducerTextTest.java`:

```java
package org.jwcarman.nessy.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReducerTextTest {

    private final Reducer reducer = Reducer.withDefaults();
    private final SessionState initial = SessionState.newSession(new SessionId("s1"));

    @Test
    void userInputIsRecordedAndAsksForTheModel() {
        Step step = reducer.reduce(initial, new Event.UserSaid("what is 2+2?"));

        assertThat(step.state().messages()).containsExactly(Message.user("what is 2+2?"));
        assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
        assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void textDeltasAccumulateIntoASinglePendingBlock() {
        SessionState state = reducer.reduce(initial, new Event.UserSaid("hi")).state();

        state = reducer.reduce(state, new Event.TextDelta("Hel")).state();
        state = reducer.reduce(state, new Event.TextDelta("lo, ")).state();
        state = reducer.reduce(state, new Event.TextDelta("world")).state();

        assertThat(state.pendingBlocks()).containsExactly(new TextBlock("Hello, world"));
    }

    @Test
    void textDeltasProduceNoEffects() {
        Step step = reducer.reduce(initial, new Event.TextDelta("anything"));

        assertThat(step.effects()).isEmpty();
    }

    @Test
    void turnEndWithNoToolCallsSettlesTheMessageAndCompletes() {
        SessionState state = reducer.reduce(initial, new Event.UserSaid("hi")).state();
        state = reducer.reduce(state, new Event.TextDelta("Hello!")).state();

        Step step = reducer.reduce(state, new Event.ModelTurnEnded(StopReason.END_TURN));

        assertThat(step.state().messages())
                .containsExactly(
                        Message.user("hi"),
                        Message.assistant(java.util.List.of(new TextBlock("Hello!"))));
        assertThat(step.state().pendingBlocks()).isEmpty();
        assertThat(step.state().status()).isEqualTo(SessionStatus.COMPLETE);
        assertThat(step.effects()).isEmpty();
    }

    @Test
    void turnEndWithNothingPendingAddsNoEmptyMessage() {
        SessionState state = reducer.reduce(initial, new Event.UserSaid("hi")).state();

        Step step = reducer.reduce(state, new Event.ModelTurnEnded(StopReason.END_TURN));

        assertThat(step.state().messages()).containsExactly(Message.user("hi"));
    }
}
```

Use an explicit `import java.util.List;` in the test rather than the inline
`java.util.List.of(...)` shown above — the project forbids neither, but explicit
imports match the house style. The assertion becomes `List.of(new TextBlock("Hello!"))`.

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -pl nessy-core test`
Expected: FAIL — `cannot find symbol: class Reducer`.

- [ ] **Step 3: Write the implementation**

`Reducer.java`:

```java
package org.jwcarman.nessy.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole of the agent's semantics, as a pure function.
 *
 * <p>Performs no I/O and holds no state beyond its own configuration. Given the
 * same state and event it always produces the same step, which is what makes the
 * loop testable without a model and resumable on another machine.
 *
 * @param maxConsecutiveErrors how many errored tool results in a row before the
 *                             session fails rather than burning tokens in a loop
 */
public record Reducer(int maxConsecutiveErrors) {

    public static final int DEFAULT_MAX_CONSECUTIVE_ERRORS = 3;

    public Reducer {
        if (maxConsecutiveErrors < 1) {
            throw new IllegalArgumentException("maxConsecutiveErrors must be at least 1");
        }
    }

    public static Reducer withDefaults() {
        return new Reducer(DEFAULT_MAX_CONSECUTIVE_ERRORS);
    }

    public Step reduce(SessionState state, Event event) {
        return switch (event) {
            case Event.UserSaid e -> userSaid(state, e);
            case Event.TextDelta e -> textDelta(state, e);
            case Event.ModelTurnEnded e -> modelTurnEnded(state, e);
            case Event.ToolCallRequested e -> throw new UnsupportedOperationException("Task 6");
            case Event.ApprovalDecided e -> throw new UnsupportedOperationException("Task 7");
            case Event.ToolFinished e -> throw new UnsupportedOperationException("Task 8");
        };
    }

    private Step userSaid(SessionState state, Event.UserSaid event) {
        return Step.of(
                state.withMessageAppended(Message.user(event.text())).with(SessionStatus.AWAITING_MODEL),
                Effect.callModel());
    }

    /**
     * Merges a chunk into the trailing text block rather than appending a new one,
     * so a hundred deltas become one block instead of a hundred.
     */
    private Step textDelta(SessionState state, Event.TextDelta event) {
        List<ContentBlock> blocks = new ArrayList<>(state.pendingBlocks());
        if (!blocks.isEmpty() && blocks.getLast() instanceof TextBlock last) {
            blocks.set(blocks.size() - 1, new TextBlock(last.text() + event.text()));
        } else {
            blocks.add(new TextBlock(event.text()));
        }
        return Step.of(state.withPendingBlocks(blocks));
    }

    private Step modelTurnEnded(SessionState state, Event.ModelTurnEnded event) {
        SessionState settled = settleAssistantMessage(state);
        return Step.of(settled.with(SessionStatus.COMPLETE));
    }

    /** Moves the in-flight blocks into the settled conversation. */
    private SessionState settleAssistantMessage(SessionState state) {
        if (state.pendingBlocks().isEmpty()) {
            return state;
        }
        return state.withMessageAppended(Message.assistant(state.pendingBlocks())).withPendingBlocks(List.of());
    }
}
```

The unused `event` parameter in `modelTurnEnded` will draw a warning in some
setups. Do not suppress it — Task 6 rewrites this method to use the state's
pending calls, and the parameter stays for symmetry with the other handlers. If
your build treats it as an error, drop the parameter now and reinstate it in
Task 6.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl nessy-core test`
Expected: PASS — five tests in `ReducerTextTest` green.

- [ ] **Step 5: Commit**

```bash
git add nessy-core/src/main/java/org/jwcarman/nessy/core/Reducer.java nessy-core/src/test/java/org/jwcarman/nessy/core/ReducerTextTest.java
git commit -m "feat(core): reduce user input and streaming text deltas"
```

---

### Task 6: Reducer — tool calls request approval

**Files:**
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/core/Reducer.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/core/ReducerToolCallTest.java`

**Interfaces:**
- Consumes: Task 5's `Reducer`.
- Produces: `Event.ToolCallRequested` handling, and `ModelTurnEnded` now branching on pending calls. No new public signatures.

- [ ] **Step 1: Write the failing test**

Create `nessy-core/src/test/java/org/jwcarman/nessy/core/ReducerToolCallTest.java`:

```java
package org.jwcarman.nessy.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReducerToolCallTest {

    private final Reducer reducer = Reducer.withDefaults();
    private final SessionState initial = SessionState.newSession(new SessionId("s1"));

    private static ToolCall call(String id, String name) {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("path", "pom.xml");
        return new ToolCall(id, name, args);
    }

    @Test
    void aRequestedCallIsRecordedAsABlockAndAsPendingWork() {
        ToolCall toolCall = call("c1", "read_file");

        Step step = reducer.reduce(initial, new Event.ToolCallRequested(toolCall));

        assertThat(step.state().pendingBlocks()).containsExactly(new ToolUseBlock(toolCall));
        assertThat(step.state().pendingCalls()).containsExactly(toolCall);
        assertThat(step.effects()).isEmpty();
    }

    @Test
    void turnEndWithCallsAsksForApprovalOfTheFirst() {
        ToolCall first = call("c1", "read_file");
        ToolCall second = call("c2", "grep");

        SessionState state = reducer.reduce(initial, new Event.TextDelta("Let me look.")).state();
        state = reducer.reduce(state, new Event.ToolCallRequested(first)).state();
        state = reducer.reduce(state, new Event.ToolCallRequested(second)).state();

        Step step = reducer.reduce(state, new Event.ModelTurnEnded(StopReason.TOOL_USE));

        assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_APPROVAL);
        assertThat(step.effects()).containsExactly(new Effect.RequestApproval(first));
    }

    @Test
    void turnEndSettlesTextAndToolUseBlocksIntoOneAssistantMessage() {
        ToolCall toolCall = call("c1", "read_file");

        SessionState state = reducer.reduce(initial, new Event.TextDelta("Looking.")).state();
        state = reducer.reduce(state, new Event.ToolCallRequested(toolCall)).state();

        Step step = reducer.reduce(state, new Event.ModelTurnEnded(StopReason.TOOL_USE));

        assertThat(step.state().messages())
                .containsExactly(Message.assistant(List.of(new TextBlock("Looking."), new ToolUseBlock(toolCall))));
        assertThat(step.state().pendingBlocks()).isEmpty();
        assertThat(step.state().pendingCalls()).containsExactly(toolCall);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -pl nessy-core test`
Expected: FAIL — `UnsupportedOperationException: Task 6`.

- [ ] **Step 3: Update the reducer**

In `Reducer.java`, replace the `ToolCallRequested` arm of the switch:

```java
            case Event.ToolCallRequested e -> toolCallRequested(state, e);
```

and add the handler:

```java
    private Step toolCallRequested(SessionState state, Event.ToolCallRequested event) {
        List<ContentBlock> blocks = new ArrayList<>(state.pendingBlocks());
        blocks.add(new ToolUseBlock(event.call()));

        List<ToolCall> calls = new ArrayList<>(state.pendingCalls());
        calls.add(event.call());

        return Step.of(state.withPendingBlocks(blocks).withPendingCalls(calls));
    }
```

Replace `modelTurnEnded` entirely:

```java
    private Step modelTurnEnded(SessionState state, Event.ModelTurnEnded event) {
        SessionState settled = settleAssistantMessage(state);
        if (settled.pendingCalls().isEmpty()) {
            return Step.of(settled.with(SessionStatus.COMPLETE));
        }
        return Step.of(
                settled.with(SessionStatus.AWAITING_APPROVAL),
                new Effect.RequestApproval(settled.pendingCalls().getFirst()));
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl nessy-core test`
Expected: PASS — `ReducerToolCallTest` green and `ReducerTextTest` still green.

- [ ] **Step 5: Commit**

```bash
git add nessy-core/src/main/java/org/jwcarman/nessy/core/Reducer.java nessy-core/src/test/java/org/jwcarman/nessy/core/ReducerToolCallTest.java
git commit -m "feat(core): reduce tool calls into approval requests"
```

---

### Task 7: Reducer — tool results, batching, and the error ceiling

**Files:**
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/core/Reducer.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/core/ReducerToolResultTest.java`

**Interfaces:**
- Consumes: Task 6's `Reducer`.
- Produces: `Event.ApprovalDecided` and `Event.ToolFinished` handling. A denial becomes an errored tool result rather than a separate path, so both flow through one place.

- [ ] **Step 1: Write the failing test**

Create `nessy-core/src/test/java/org/jwcarman/nessy/core/ReducerToolResultTest.java`:

```java
package org.jwcarman.nessy.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReducerToolResultTest {

    private final Reducer reducer = new Reducer(2);
    private final SessionState initial = SessionState.newSession(new SessionId("s1"));

    private static ToolCall call(String id) {
        return new ToolCall(id, "read_file", JsonNodeFactory.instance.objectNode());
    }

    /** Drives the loop to the point where {@code calls} are pending approval. */
    private SessionState awaitingApproval(ToolCall... calls) {
        SessionState state = initial;
        for (ToolCall each : calls) {
            state = reducer.reduce(state, new Event.ToolCallRequested(each)).state();
        }
        return reducer.reduce(state, new Event.ModelTurnEnded(StopReason.TOOL_USE)).state();
    }

    @Test
    void approvalAsksForExecution() {
        ToolCall toolCall = call("c1");
        SessionState state = awaitingApproval(toolCall);

        Step step = reducer.reduce(state, new Event.ApprovalDecided(toolCall, Decision.allow()));

        assertThat(step.state().status()).isEqualTo(SessionStatus.EXECUTING_TOOL);
        assertThat(step.effects()).containsExactly(new Effect.ExecuteTool(toolCall));
    }

    @Test
    void denialBecomesAnErroredResultTheModelCanSee() {
        ToolCall toolCall = call("c1");
        SessionState state = awaitingApproval(toolCall);

        Step step = reducer.reduce(state, new Event.ApprovalDecided(toolCall, new Decision.Deny("no thanks")));

        assertThat(step.state().messages().getLast().content())
                .containsExactly(new ToolResultBlock("c1", "Denied by user: no thanks", true));
        assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
        assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void aFinishedToolFlushesResultsAndCallsTheModelAgain() {
        ToolCall toolCall = call("c1");
        SessionState state = awaitingApproval(toolCall);
        state = reducer.reduce(state, new Event.ApprovalDecided(toolCall, Decision.allow())).state();

        Step step = reducer.reduce(state, new Event.ToolFinished(toolCall, ToolResult.ok("file contents")));

        assertThat(step.state().messages().getLast())
                .isEqualTo(Message.toolResults(List.of(new ToolResultBlock("c1", "file contents", false))));
        assertThat(step.state().pendingCalls()).isEmpty();
        assertThat(step.state().pendingResults()).isEmpty();
        assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
        assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void resultsAreBatchedIntoOneMessageWhenSeveralCallsArePending() {
        ToolCall first = call("c1");
        ToolCall second = call("c2");
        SessionState state = awaitingApproval(first, second);

        state = reducer.reduce(state, new Event.ApprovalDecided(first, Decision.allow())).state();
        Step afterFirst = reducer.reduce(state, new Event.ToolFinished(first, ToolResult.ok("one")));

        assertThat(afterFirst.state().pendingResults()).hasSize(1);
        assertThat(afterFirst.effects()).containsExactly(new Effect.RequestApproval(second));

        SessionState afterApproval =
                reducer.reduce(afterFirst.state(), new Event.ApprovalDecided(second, Decision.allow())).state();
        Step afterSecond = reducer.reduce(afterApproval, new Event.ToolFinished(second, ToolResult.ok("two")));

        assertThat(afterSecond.state().messages().getLast().content())
                .containsExactly(
                        new ToolResultBlock("c1", "one", false),
                        new ToolResultBlock("c2", "two", false));
        assertThat(afterSecond.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void aSuccessfulResultResetsTheErrorCount() {
        ToolCall toolCall = call("c1");
        SessionState state = awaitingApproval(toolCall).withConsecutiveErrors(1);
        state = reducer.reduce(state, new Event.ApprovalDecided(toolCall, Decision.allow())).state();

        Step step = reducer.reduce(state, new Event.ToolFinished(toolCall, ToolResult.ok("fine")));

        assertThat(step.state().consecutiveErrors()).isZero();
    }

    @Test
    void reachingTheErrorCeilingFailsTheSessionInsteadOfLooping() {
        ToolCall toolCall = call("c1");
        SessionState state = awaitingApproval(toolCall).withConsecutiveErrors(1);
        state = reducer.reduce(state, new Event.ApprovalDecided(toolCall, Decision.allow())).state();

        Step step = reducer.reduce(state, new Event.ToolFinished(toolCall, ToolResult.error("boom")));

        assertThat(step.state().consecutiveErrors()).isEqualTo(2);
        assertThat(step.state().status()).isEqualTo(SessionStatus.FAILED);
        assertThat(step.effects()).isEmpty();
        assertThat(step.state().messages().getLast().content())
                .containsExactly(new ToolResultBlock("c1", "boom", true));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -pl nessy-core test`
Expected: FAIL — `UnsupportedOperationException: Task 7`.

- [ ] **Step 3: Update the reducer**

Replace the two remaining `throw` arms of the switch:

```java
            case Event.ApprovalDecided e -> approvalDecided(state, e);
            case Event.ToolFinished e -> toolFinished(state, e.call(), e.result());
```

and add the handlers:

```java
    private Step approvalDecided(SessionState state, Event.ApprovalDecided event) {
        return switch (event.decision()) {
            case Decision.Allow ignored ->
                    Step.of(state.with(SessionStatus.EXECUTING_TOOL), new Effect.ExecuteTool(event.call()));
            // A denial is not a special path: it is a result the model can read and
            // adapt to, exactly like a tool that failed.
            case Decision.Deny deny ->
                    toolFinished(state, event.call(), ToolResult.error("Denied by user: " + deny.reason()));
        };
    }

    private Step toolFinished(SessionState state, ToolCall call, ToolResult result) {
        List<ContentBlock> results = new ArrayList<>(state.pendingResults());
        results.add(new ToolResultBlock(call.id(), result.content(), result.isError()));

        List<ToolCall> remaining = new ArrayList<>(state.pendingCalls());
        remaining.removeIf(pending -> pending.id().equals(call.id()));

        int errors = result.isError() ? state.consecutiveErrors() + 1 : 0;

        SessionState next = state
                .withPendingResults(results)
                .withPendingCalls(remaining)
                .withConsecutiveErrors(errors);

        if (errors >= maxConsecutiveErrors) {
            return Step.of(flushResults(next).with(SessionStatus.FAILED));
        }
        if (!remaining.isEmpty()) {
            return Step.of(
                    next.with(SessionStatus.AWAITING_APPROVAL),
                    new Effect.RequestApproval(remaining.getFirst()));
        }
        return Step.of(flushResults(next).with(SessionStatus.AWAITING_MODEL), Effect.callModel());
    }

    /**
     * Collected results become one user message. They are batched rather than sent
     * one at a time because the providers we target require every result for a turn
     * to arrive together in the message that follows it.
     */
    private SessionState flushResults(SessionState state) {
        if (state.pendingResults().isEmpty()) {
            return state;
        }
        return state
                .withMessageAppended(Message.toolResults(state.pendingResults()))
                .withPendingResults(List.of());
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl nessy-core test`
Expected: PASS — seven tests in `ReducerToolResultTest` green, earlier reducer tests still green.

- [ ] **Step 5: Commit**

```bash
git add nessy-core/src/main/java/org/jwcarman/nessy/core/Reducer.java nessy-core/src/test/java/org/jwcarman/nessy/core/ReducerToolResultTest.java
git commit -m "feat(core): reduce approvals, tool results, and the error ceiling"
```

---

### Task 8: Parking types

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/core/ParkToken.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/core/Awaited.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/core/AwaitedTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `ParkToken(String value)` with `ParkToken.random()`; sealed `Awaited<T>` permitting `Awaited.Ready<T>(T value)` and `Awaited.Parked<T>(ParkToken token)`, with statics `Awaited.ready(T)` and `Awaited.parked(ParkToken)`.

- [ ] **Step 1: Write the failing test**

Create `nessy-core/src/test/java/org/jwcarman/nessy/core/AwaitedTest.java`:

```java
package org.jwcarman.nessy.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AwaitedTest {

    @Test
    void readyCarriesItsValue() {
        Awaited<String> awaited = Awaited.ready("done");

        String resolved = switch (awaited) {
            case Awaited.Ready<String> ready -> ready.value();
            case Awaited.Parked<String> parked -> "parked:" + parked.token().value();
        };

        assertThat(resolved).isEqualTo("done");
    }

    @Test
    void parkedCarriesItsToken() {
        ParkToken token = new ParkToken("t1");

        Awaited<String> awaited = Awaited.parked(token);

        assertThat(awaited).isEqualTo(new Awaited.Parked<String>(token));
    }

    @Test
    void randomTokensAreDistinct() {
        assertThat(ParkToken.random()).isNotEqualTo(ParkToken.random());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -pl nessy-core test`
Expected: FAIL — `cannot find symbol: class Awaited`.

- [ ] **Step 3: Write the implementation**

`ParkToken.java`:

```java
package org.jwcarman.nessy.core;

import java.util.UUID;

/**
 * Names one parked wait so a later resume can find it.
 *
 * <p>Single-use. Resume delivery is at-least-once in every real transport —
 * webhooks retry, queues redeliver — so the store must reject a second resume
 * against a consumed token, or a duplicate click replays a tool call.
 */
public record ParkToken(String value) {

    public ParkToken {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("park token must not be blank");
        }
    }

    public static ParkToken random() {
        return new ParkToken(UUID.randomUUID().toString());
    }
}
```

`Awaited.java`:

```java
package org.jwcarman.nessy.core;

/**
 * The outcome of something that might have to wait.
 *
 * <p>Virtual threads unmount a task from a carrier thread; this unmounts a
 * session from a process. An in-process implementation blocks and returns
 * {@link Ready}; a durable one returns {@link Parked} so the engine can persist
 * the session and let another machine finish it.
 *
 * @param <T> what the wait produces
 */
public sealed interface Awaited<T> {

    record Ready<T>(T value) implements Awaited<T> {}

    record Parked<T>(ParkToken token) implements Awaited<T> {}

    static <T> Awaited<T> ready(T value) {
        return new Ready<>(value);
    }

    static <T> Awaited<T> parked(ParkToken token) {
        return new Parked<>(token);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl nessy-core test`
Expected: PASS — three tests in `AwaitedTest` green.

- [ ] **Step 5: Commit**

```bash
git add nessy-core/src/main/java/org/jwcarman/nessy/core/ParkToken.java nessy-core/src/main/java/org/jwcarman/nessy/core/Awaited.java nessy-core/src/test/java/org/jwcarman/nessy/core/AwaitedTest.java
git commit -m "feat(core): add Awaited and ParkToken"
```

---

### Task 9: Tool schema generation

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/tool/Schemas.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/tool/SchemasTest.java`

**Interfaces:**
- Consumes: victools from Task 1.
- Produces: `Schemas.of(Class<?> inputType)` returning a Jackson `ObjectNode` holding a Draft 2020-12 JSON Schema. Every record component is required unless its type is `Optional`. `@JsonPropertyDescription` on a component becomes the property description.

- [ ] **Step 1: Write the failing test**

Create `nessy-core/src/test/java/org/jwcarman/nessy/tool/SchemasTest.java`:

```java
package org.jwcarman.nessy.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SchemasTest {

    record ReadFile(
            @JsonPropertyDescription("Path relative to the workspace root") String path,
            Optional<Integer> maxLines) {}

    @Test
    void componentsBecomeProperties() {
        ObjectNode schema = Schemas.of(ReadFile.class);

        assertThat(schema.get("properties").has("path")).isTrue();
        assertThat(schema.get("properties").has("maxLines")).isTrue();
    }

    @Test
    void descriptionsSurviveIntoTheSchema() {
        ObjectNode schema = Schemas.of(ReadFile.class);

        assertThat(schema.get("properties").get("path").get("description").asText())
                .isEqualTo("Path relative to the workspace root");
    }

    @Test
    void everythingIsRequiredExceptOptionals() {
        ObjectNode schema = Schemas.of(ReadFile.class);

        assertThat(schema.get("required")).hasSize(1);
        assertThat(schema.get("required").get(0).asText()).isEqualTo("path");
    }

    @Test
    void theSchemaDescribesAnObject() {
        ObjectNode schema = Schemas.of(ReadFile.class);

        assertThat(schema.get("type").asText()).isEqualTo("object");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -pl nessy-core test`
Expected: FAIL — `cannot find symbol: class Schemas`.

- [ ] **Step 3: Write the implementation**

`Schemas.java`:

```java
package org.jwcarman.nessy.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.FieldScope;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import java.util.Optional;

/**
 * Turns a record into the JSON Schema a model needs to call it.
 *
 * <p>The record is the single source of truth. Its components become the
 * schema's properties and {@code @JsonPropertyDescription} becomes the text the
 * model reads. Nobody hand-writes JSON Schema, so it cannot drift from the code.
 */
public final class Schemas {

    private static final SchemaGenerator GENERATOR = generator();

    private Schemas() {}

    public static ObjectNode of(Class<?> inputType) {
        return GENERATOR.generateSchema(inputType);
    }

    private static SchemaGenerator generator() {
        SchemaGeneratorConfigBuilder config =
                new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                        .with(new JacksonModule());
        config.forFields().withRequiredCheck(Schemas::isRequired);
        return new SchemaGenerator(config.build());
    }

    /** Everything a record declares is required unless it is an {@link Optional}. */
    private static boolean isRequired(FieldScope field) {
        return !Optional.class.isAssignableFrom(field.getType().getErasedType());
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl nessy-core test`
Expected: PASS — four tests in `SchemasTest` green.

If `type` is absent from the generated root, add `.with(com.github.victools.jsonschema.generator.Option.DEFINITIONS_FOR_ALL_OBJECTS)` is **not** the fix — instead assert on the generator's actual output and adjust the test to match victools 4.38's Draft 2020-12 shape. Do not change `isRequired`.

- [ ] **Step 5: Commit**

```bash
git add nessy-core/src/main/java/org/jwcarman/nessy/tool/Schemas.java nessy-core/src/test/java/org/jwcarman/nessy/tool/SchemasTest.java
git commit -m "feat(tool): derive JSON Schema from record input types"
```

---

### Task 10: The tool seam

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/tool/ToolSpec.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/tool/ToolContext.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/tool/Tool.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/tool/ToolRegistry.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/tool/MapToolRegistry.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/tool/ToolInvoker.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/tool/ToolRegistryTest.java`

**Interfaces:**
- Consumes: Task 2's `ToolResult`, `SessionId`; Task 8's `Awaited`; Task 9's `Schemas`.
- Produces:
  - `ToolSpec(String name, String description, ObjectNode inputSchema)`
  - `ToolContext(SessionId sessionId)`
  - `Tool<T>` with `String name()`, `String description()`, `Class<T> inputType()`, `boolean requiresApproval()`, `default String describe(T input)`, `Awaited<ToolResult> execute(T input, ToolContext context)`, and `default ToolSpec spec()`
  - `ToolRegistry` with `Optional<Tool<?>> find(String name)` and `List<ToolSpec> specs()`
  - `MapToolRegistry.of(Tool<?>... tools)`
  - `ToolInvoker(ObjectMapper mapper)` with `Awaited<ToolResult> invoke(Tool<?> tool, ToolCall call, ToolContext context)` and `String describe(Tool<?> tool, ToolCall call)`

- [ ] **Step 1: Write the failing test**

Create `nessy-core/src/test/java/org/jwcarman/nessy/tool/ToolRegistryTest.java`:

```java
package org.jwcarman.nessy.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jwcarman.nessy.core.Awaited;
import org.jwcarman.nessy.core.SessionId;
import org.jwcarman.nessy.core.ToolCall;
import org.jwcarman.nessy.core.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    record Greet(String name) {}

    static final class GreetTool implements Tool<Greet> {
        @Override
        public String name() {
            return "greet";
        }

        @Override
        public String description() {
            return "Greets somebody by name";
        }

        @Override
        public Class<Greet> inputType() {
            return Greet.class;
        }

        @Override
        public boolean requiresApproval() {
            return false;
        }

        @Override
        public String describe(Greet input) {
            return "greet(" + input.name() + ")";
        }

        @Override
        public Awaited<ToolResult> execute(Greet input, ToolContext context) {
            return Awaited.ready(ToolResult.ok("Hello, " + input.name()));
        }
    }

    private final ToolRegistry registry = MapToolRegistry.of(new GreetTool());
    private final ToolInvoker invoker = new ToolInvoker(new ObjectMapper());

    private static ToolCall greetCall(String name) {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("name", name);
        return new ToolCall("c1", "greet", args);
    }

    @Test
    void findsARegisteredTool() {
        assertThat(registry.find("greet")).isPresent();
    }

    @Test
    void returnsEmptyForAnUnknownTool() {
        assertThat(registry.find("nope")).isEmpty();
    }

    @Test
    void specsCarryNameDescriptionAndSchema() {
        ToolSpec spec = registry.specs().getFirst();

        assertThat(spec.name()).isEqualTo("greet");
        assertThat(spec.description()).isEqualTo("Greets somebody by name");
        assertThat(spec.inputSchema().get("properties").has("name")).isTrue();
    }

    @Test
    void invokingBindsJsonArgumentsToTheRecord() {
        Tool<?> tool = registry.find("greet").orElseThrow();

        Awaited<ToolResult> awaited = invoker.invoke(tool, greetCall("Ada"), new ToolContext(new SessionId("s1")));

        assertThat(awaited).isEqualTo(Awaited.ready(ToolResult.ok("Hello, Ada")));
    }

    @Test
    void describeRendersTheCallForAHuman() {
        Tool<?> tool = registry.find("greet").orElseThrow();

        assertThat(invoker.describe(tool, greetCall("Ada"))).isEqualTo("greet(Ada)");
    }

    @Test
    void duplicateNamesAreRejectedAtRegistrationTime() {
        assertThatThrownBy(() -> MapToolRegistry.of(new GreetTool(), new GreetTool()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greet");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -pl nessy-core test`
Expected: FAIL — `cannot find symbol: interface Tool`.

- [ ] **Step 3: Write the implementation**

`ToolSpec.java`:

```java
package org.jwcarman.nessy.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What a provider needs to tell the model a tool exists.
 *
 * <p>Wire-neutral on purpose: the schema is a Jackson node, not any SDK's type,
 * so {@code nessy-core} stays free of model SDKs and each provider module
 * converts on its way out.
 */
public record ToolSpec(String name, String description, ObjectNode inputSchema) {}
```

`ToolContext.java`:

```java
package org.jwcarman.nessy.tool;

import org.jwcarman.nessy.core.SessionId;

/** What a tool learns about the invocation it is serving. */
public record ToolContext(SessionId sessionId) {}
```

`Tool.java`:

```java
package org.jwcarman.nessy.tool;

import org.jwcarman.nessy.core.Awaited;
import org.jwcarman.nessy.core.ToolResult;

/**
 * Something the model can ask the harness to do.
 *
 * <p>A tool is a name, a sentence explaining when to use it, a record describing
 * its arguments, and a method that runs. The JSON Schema is derived from
 * {@link #inputType()} rather than written by hand.
 *
 * @param <T> the record this tool's arguments arrive in
 */
public interface Tool<T> {

    /** What the model calls it. Must be unique within a registry. */
    String name();

    /** When to use it, written for the model rather than for you. */
    String description();

    /** The record its arguments deserialize into. */
    Class<T> inputType();

    /**
     * Whether a human must say yes before this runs.
     *
     * <p>Deliberately not defaulted. A default of {@code false} fails open: add a
     * tool later, forget to override, and it runs ungated and silently. Abstract
     * means a new tool does not compile until someone answers the question.
     */
    boolean requiresApproval();

    /**
     * What this call looks like to a human, in the approval prompt.
     *
     * <p>The default is the record's {@code toString}, which is usable but reads
     * like {@code Greet[name=Ada]}. Override it: a prompt you skim is a prompt you
     * approve without reading.
     */
    default String describe(T input) {
        return String.valueOf(input);
    }

    /** Runs the tool. Returns {@link Awaited.Parked} only if it genuinely must wait. */
    Awaited<ToolResult> execute(T input, ToolContext context);

    /** The wire description derived from {@link #inputType()}. */
    default ToolSpec spec() {
        return new ToolSpec(name(), description(), Schemas.of(inputType()));
    }
}
```

`ToolRegistry.java`:

```java
package org.jwcarman.nessy.tool;

import java.util.List;
import java.util.Optional;

/** Which tools this agent has. */
public interface ToolRegistry {

    Optional<Tool<?>> find(String name);

    /** Every tool's wire description, for handing to the model. */
    List<ToolSpec> specs();
}
```

`MapToolRegistry.java`:

```java
package org.jwcarman.nessy.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The default registry: a fixed set of tools, resolved by name. */
public final class MapToolRegistry implements ToolRegistry {

    private final Map<String, Tool<?>> tools;

    private MapToolRegistry(Map<String, Tool<?>> tools) {
        // NOT Map.copyOf: it returns an immutable MapN whose iteration order is
        // unspecified and salted per JVM run, which would defeat the LinkedHashMap
        // above. specs() feeds every model request, so a reshuffling tool list
        // busts provider prompt-cache prefixes on every restart.
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(tools));
    }

    public static MapToolRegistry of(Tool<?>... tools) {
        Map<String, Tool<?>> byName = new LinkedHashMap<>();
        for (Tool<?> tool : tools) {
            Tool<?> existing = byName.put(tool.name(), tool);
            if (existing != null) {
                throw new IllegalArgumentException("duplicate tool name: " + tool.name());
            }
        }
        return new MapToolRegistry(byName);
    }

    @Override
    public Optional<Tool<?>> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public List<ToolSpec> specs() {
        return tools.values().stream().map(Tool::spec).toList();
    }
}
```

`ToolInvoker.java`:

```java
package org.jwcarman.nessy.tool;

import org.jwcarman.nessy.core.Awaited;
import org.jwcarman.nessy.core.ToolCall;
import org.jwcarman.nessy.core.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Binds a call's JSON arguments to a tool's input record and runs it.
 *
 * <p>Exists as its own type because {@code Tool<?>} cannot be invoked directly —
 * the wildcard has to be captured by a type variable first, which is what the
 * private helpers here do.
 */
public final class ToolInvoker {

    private final ObjectMapper mapper;

    public ToolInvoker(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Awaited<ToolResult> invoke(Tool<?> tool, ToolCall call, ToolContext context) {
        return invokeCaptured(tool, call, context);
    }

    public String describe(Tool<?> tool, ToolCall call) {
        return describeCaptured(tool, call);
    }

    private <T> Awaited<ToolResult> invokeCaptured(Tool<T> tool, ToolCall call, ToolContext context) {
        return tool.execute(bind(tool, call), context);
    }

    private <T> String describeCaptured(Tool<T> tool, ToolCall call) {
        return tool.describe(bind(tool, call));
    }

    private <T> T bind(Tool<T> tool, ToolCall call) {
        return mapper.convertValue(call.arguments(), tool.inputType());
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl nessy-core test`
Expected: PASS — six tests in `ToolRegistryTest` green.

- [ ] **Step 5: Commit**

```bash
git add nessy-core/src/main/java/org/jwcarman/nessy/tool nessy-core/src/test/java/org/jwcarman/nessy/tool
git commit -m "feat(tool): add Tool, ToolRegistry, and ToolInvoker"
```

---

### Task 11: The model seam

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/model/Capability.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/model/ModelEvent.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/model/ModelStream.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/model/ModelRequest.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/model/ModelProvider.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/model/ModelRequestTest.java`

**Interfaces:**
- Consumes: Task 2's `Message`, `ToolCall`, `StopReason`; Task 10's `ToolSpec`.
- Produces:
  - `Capability` enum: `THINKING`, `PROMPT_CACHING`, `PARALLEL_TOOL_CALLS`, `IMAGE_INPUT`
  - sealed `ModelEvent` permitting `ModelEvent.TextChunk(String text)`, `ModelEvent.ToolUseEmitted(ToolCall call)`, `ModelEvent.TurnEnded(StopReason reason)`
  - `ModelStream extends Iterable<ModelEvent>, AutoCloseable` with `void close()` overriding to drop the checked exception
  - `ModelRequest(List<Message> messages, String systemPrompt, String model, int maxTokens, List<ToolSpec> tools, Set<Capability> requested)` with `ModelRequest.supports(Capability)` unused here and `requested` defensively copied
  - `ModelProvider` with `ModelStream stream(ModelRequest request)` and `Set<Capability> capabilities()`

- [ ] **Step 1: Write the failing test**

Create `nessy-core/src/test/java/org/jwcarman/nessy/model/ModelRequestTest.java`:

```java
package org.jwcarman.nessy.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.jwcarman.nessy.core.Message;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelRequestTest {

    @Test
    void collectionsAreDefensivelyCopied() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("hi"));
        Set<Capability> requested = EnumSet.of(Capability.PROMPT_CACHING);

        ModelRequest request =
                new ModelRequest(messages, "be helpful", "some-model", 1024, List.of(), requested);

        messages.add(Message.user("sneaked in"));
        requested.add(Capability.THINKING);

        assertThat(request.messages()).hasSize(1);
        assertThat(request.requested()).containsExactly(Capability.PROMPT_CACHING);
    }

    @Test
    void unsupportedCapabilitiesAreVisibleRatherThanSilent() {
        ModelRequest request = new ModelRequest(
                List.of(Message.user("hi")),
                "be helpful",
                "some-model",
                1024,
                List.of(),
                Set.of(Capability.PROMPT_CACHING, Capability.THINKING));

        Set<Capability> unsupported = request.unsupportedBy(Set.of(Capability.THINKING));

        assertThat(unsupported).containsExactly(Capability.PROMPT_CACHING);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -pl nessy-core test`
Expected: FAIL — `cannot find symbol: class ModelRequest`.

- [ ] **Step 3: Write the implementation**

`Capability.java`:

```java
package org.jwcarman.nessy.model;

/**
 * Something a provider may or may not be able to do.
 *
 * <p>This enum is the anti-rot mechanism for the model seam. A request may
 * <em>ask</em> for prompt caching; a provider that cannot do it says so, and the
 * harness degrades explicitly. Flattening every model to what the weakest one
 * supports is how the 2023-era abstractions died.
 */
public enum Capability {
    THINKING,
    PROMPT_CACHING,
    PARALLEL_TOOL_CALLS,
    IMAGE_INPUT
}
```

`ModelEvent.java`:

```java
package org.jwcarman.nessy.model;

import org.jwcarman.nessy.core.StopReason;
import org.jwcarman.nessy.core.ToolCall;

/**
 * Something a provider emitted while streaming one turn.
 *
 * <p>Distinct from {@code Event} on purpose: a provider should be able to report
 * what the model did, and nothing else. Reusing the core event type would let a
 * provider inject a user message or an approval decision into the loop.
 */
public sealed interface ModelEvent {

    record TextChunk(String text) implements ModelEvent {}

    /** Emitted once the provider has assembled a complete tool call. */
    record ToolUseEmitted(ToolCall call) implements ModelEvent {}

    record TurnEnded(StopReason reason) implements ModelEvent {}
}
```

`ModelStream.java`:

```java
package org.jwcarman.nessy.model;

/**
 * One turn's worth of streamed events.
 *
 * <p>An {@code Iterable} rather than a publisher: on virtual threads, blocking
 * iteration is the cheap and readable option, and it maps directly onto what the
 * Anthropic and OpenAI Java SDKs already hand you. {@code close()} narrows
 * {@link AutoCloseable} to drop the checked exception so try-with-resources at
 * the call site stays clean.
 */
public interface ModelStream extends Iterable<ModelEvent>, AutoCloseable {

    @Override
    void close();
}
```

`ModelRequest.java`:

```java
package org.jwcarman.nessy.model;

import org.jwcarman.nessy.core.Message;
import org.jwcarman.nessy.tool.ToolSpec;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything a provider needs for one call.
 *
 * <p>The system prompt is a field rather than a message because that is how the
 * providers we target actually model it.
 *
 * @param requested capabilities the harness would like used, not a guarantee any
 *                  provider offers them
 */
public record ModelRequest(
        List<Message> messages,
        String systemPrompt,
        String model,
        int maxTokens,
        List<ToolSpec> tools,
        Set<Capability> requested) {

    public ModelRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        requested = Set.copyOf(requested);
    }

    /** What this request asked for that the given provider cannot do. */
    public Set<Capability> unsupportedBy(Set<Capability> supported) {
        Set<Capability> missing = new LinkedHashSet<>(requested);
        missing.removeAll(supported);
        return Set.copyOf(missing);
    }
}
```

`ModelProvider.java`:

```java
package org.jwcarman.nessy.model;

import java.util.Set;

/** Where tokens come from. */
public interface ModelProvider {

    /**
     * Starts one turn. The caller iterates the returned stream and must close it.
     *
     * <p>Blocking by design: on virtual threads that is cheaper and far more
     * readable than a callback protocol.
     */
    ModelStream stream(ModelRequest request);

    /** What this provider can actually do. See {@link Capability}. */
    Set<Capability> capabilities();
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl nessy-core test`
Expected: PASS — two tests in `ModelRequestTest` green.

- [ ] **Step 5: Commit**

```bash
git add nessy-core/src/main/java/org/jwcarman/nessy/model nessy-core/src/test/java/org/jwcarman/nessy/model
git commit -m "feat(model): add capability-aware ModelProvider seam"
```

---

### Task 12: The approval seam

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/approval/ApprovalRequest.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/approval/Approver.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/approval/ApproveEverything.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/approval/DenyEverything.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/approval/ApproverTest.java`

**Interfaces:**
- Consumes: Task 2's `ToolCall`, `SessionId`; Task 4's `Decision`; Task 8's `Awaited`.
- Produces: `ApprovalRequest(SessionId sessionId, ToolCall call, String description)`; `Approver` with `Awaited<Decision> approve(ApprovalRequest request)`; `ApproveEverything` and `DenyEverything(String reason)` implementations.

- [ ] **Step 1: Write the failing test**

Create `nessy-core/src/test/java/org/jwcarman/nessy/approval/ApproverTest.java`:

```java
package org.jwcarman.nessy.approval;

import static org.assertj.core.api.Assertions.assertThat;

import org.jwcarman.nessy.core.Awaited;
import org.jwcarman.nessy.core.Decision;
import org.jwcarman.nessy.core.SessionId;
import org.jwcarman.nessy.core.ToolCall;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

class ApproverTest {

    private final ApprovalRequest request = new ApprovalRequest(
            new SessionId("s1"),
            new ToolCall("c1", "delete_everything", JsonNodeFactory.instance.objectNode()),
            "delete_everything()");

    @Test
    void approveEverythingAllows() {
        assertThat(new ApproveEverything().approve(request)).isEqualTo(Awaited.ready(Decision.allow()));
    }

    @Test
    void denyEverythingDeniesWithItsReason() {
        Awaited<Decision> awaited = new DenyEverything("read-only mode").approve(request);

        assertThat(awaited).isEqualTo(Awaited.ready(new Decision.Deny("read-only mode")));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -pl nessy-core test`
Expected: FAIL — `cannot find symbol: class ApprovalRequest`.

- [ ] **Step 3: Write the implementation**

`ApprovalRequest.java`:

```java
package org.jwcarman.nessy.approval;

import org.jwcarman.nessy.core.SessionId;
import org.jwcarman.nessy.core.ToolCall;

/**
 * The question put to a human.
 *
 * @param description the tool's own rendering of the call, from
 *                    {@code Tool.describe} — this is what a person actually reads
 */
public record ApprovalRequest(SessionId sessionId, ToolCall call, String description) {}
```

`Approver.java`:

```java
package org.jwcarman.nessy.approval;

import org.jwcarman.nessy.core.Awaited;
import org.jwcarman.nessy.core.Decision;

/**
 * The safety gate.
 *
 * <p>This is a harness-side interceptor: the model cannot see it, name it, or
 * route around it. That keeps 12-factor's Factor 7 structure while rejecting its
 * trigger. The factor's mechanism — a structured request that is persisted,
 * breaks the loop, and resumes later — is right, and {@link Awaited.Parked}
 * implements it. Its trigger, letting the model decide when to reach a human, is
 * right for clarification and unsafe for approval: a model that never emits the
 * intent simply never asks, and that is indistinguishable from a question that
 * was answered. You cannot put the gate on the far side of the thing it guards.
 *
 * <p>Model-initiated clarification is a separate, ordinary tool.
 *
 * <p>Blocking is fine — an interactive approver parks a virtual thread while a
 * human decides. Return {@link Awaited.Parked} only when the wait must outlive
 * the process.
 */
public interface Approver {

    Awaited<Decision> approve(ApprovalRequest request);
}
```

`ApproveEverything.java`:

```java
package org.jwcarman.nessy.approval;

import org.jwcarman.nessy.core.Awaited;
import org.jwcarman.nessy.core.Decision;

/** Says yes to everything. For tests, sandboxes, and users who have opted in. */
public final class ApproveEverything implements Approver {

    @Override
    public Awaited<Decision> approve(ApprovalRequest request) {
        return Awaited.ready(Decision.allow());
    }
}
```

`DenyEverything.java`:

```java
package org.jwcarman.nessy.approval;

import org.jwcarman.nessy.core.Awaited;
import org.jwcarman.nessy.core.Decision;

/** Says no to everything, with a reason the model can read and adapt to. */
public record DenyEverything(String reason) implements Approver {

    @Override
    public Awaited<Decision> approve(ApprovalRequest request) {
        return Awaited.ready(new Decision.Deny(reason));
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl nessy-core test`
Expected: PASS — two tests in `ApproverTest` green.

- [ ] **Step 5: Commit**

```bash
git add nessy-core/src/main/java/org/jwcarman/nessy/approval nessy-core/src/test/java/org/jwcarman/nessy/approval
git commit -m "feat(approval): add the Approver interceptor seam"
```

---

### Task 13: The session store

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/session/SessionStore.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/session/InMemorySessionStore.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/session/InMemorySessionStoreTest.java`

**Interfaces:**
- Consumes: Task 3's `SessionState`; Task 8's `ParkToken`.
- Produces: `SessionStore` with `Optional<SessionState> load(SessionId)`, `void save(SessionState)`, `boolean consumeToken(ParkToken)`; `InMemorySessionStore` implementing it over `ConcurrentHashMap`.

- [ ] **Step 1: Write the failing test**

Create `nessy-core/src/test/java/org/jwcarman/nessy/session/InMemorySessionStoreTest.java`:

```java
package org.jwcarman.nessy.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.jwcarman.nessy.core.ParkToken;
import org.jwcarman.nessy.core.SessionId;
import org.jwcarman.nessy.core.SessionState;
import org.jwcarman.nessy.core.SessionStatus;
import org.junit.jupiter.api.Test;

class InMemorySessionStoreTest {

    private final SessionStore store = new InMemorySessionStore();
    private final SessionId id = new SessionId("s1");

    @Test
    void loadingAnUnknownSessionIsEmpty() {
        assertThat(store.load(id)).isEmpty();
    }

    @Test
    void savedStateComesBack() {
        SessionState state = SessionState.newSession(id).with(SessionStatus.COMPLETE);

        store.save(state);

        assertThat(store.load(id)).contains(state);
    }

    @Test
    void savingAgainReplaces() {
        store.save(SessionState.newSession(id).with(SessionStatus.AWAITING_MODEL));
        store.save(SessionState.newSession(id).with(SessionStatus.COMPLETE));

        assertThat(store.load(id).orElseThrow().status()).isEqualTo(SessionStatus.COMPLETE);
    }

    @Test
    void aTokenCanBeConsumedExactlyOnce() {
        ParkToken token = ParkToken.random();

        assertThat(store.consumeToken(token)).isTrue();
        assertThat(store.consumeToken(token)).isFalse();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -pl nessy-core test`
Expected: FAIL — `cannot find symbol: class InMemorySessionStore`.

- [ ] **Step 3: Write the implementation**

`SessionStore.java`:

```java
package org.jwcarman.nessy.session;

import org.jwcarman.nessy.core.ParkToken;
import org.jwcarman.nessy.core.SessionId;
import org.jwcarman.nessy.core.SessionState;
import java.util.Optional;

/**
 * Where a session lives between steps.
 *
 * <p>Because {@code SessionState} is a plain serializable record, durable resume
 * is an implementation of this interface rather than a change to the engine.
 */
public interface SessionStore {

    Optional<SessionState> load(SessionId id);

    void save(SessionState state);

    /**
     * Claims a park token, returning {@code false} if it was already claimed.
     *
     * <p>Resume delivery is at-least-once in every real transport, so without
     * this a retried webhook or a double-clicked Slack button replays a tool call.
     */
    boolean consumeToken(ParkToken token);
}
```

`InMemorySessionStore.java`:

```java
package org.jwcarman.nessy.session;

import org.jwcarman.nessy.core.ParkToken;
import org.jwcarman.nessy.core.SessionId;
import org.jwcarman.nessy.core.SessionState;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The zero-configuration default: sessions live in this JVM and die with it.
 *
 * <p>Correct for a CLI, a test, or any front-end that owns the whole session.
 * Anything that needs a session to survive a restart wants a durable store.
 */
public final class InMemorySessionStore implements SessionStore {

    private final Map<SessionId, SessionState> sessions = new ConcurrentHashMap<>();
    private final Set<ParkToken> consumed = ConcurrentHashMap.newKeySet();

    @Override
    public Optional<SessionState> load(SessionId id) {
        return Optional.ofNullable(sessions.get(id));
    }

    @Override
    public void save(SessionState state) {
        sessions.put(state.id(), state);
    }

    @Override
    public boolean consumeToken(ParkToken token) {
        return consumed.add(token);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl nessy-core test`
Expected: PASS — four tests in `InMemorySessionStoreTest` green.

- [ ] **Step 5: Commit**

```bash
git add nessy-core/src/main/java/org/jwcarman/nessy/session nessy-core/src/test/java/org/jwcarman/nessy/session
git commit -m "feat(session): add SessionStore with in-memory default"
```

---

### Task 14: The scripted model provider

**Files:**
- Create: `nessy-testing/src/main/java/org/jwcarman/nessy/testing/ScriptedModelProvider.java`
- Create: `nessy-testing/src/main/java/org/jwcarman/nessy/testing/RecordingEventListener.java`
- Test: `nessy-testing/src/test/java/org/jwcarman/nessy/testing/ScriptedModelProviderTest.java`

**Interfaces:**
- Consumes: Task 11's model seam; Task 15 will consume `RecordingEventListener`, so `AgentEventListener` must exist first — **implement Task 15's `AgentEventListener` interface file as part of this task if executing out of order.** In the intended order, do Task 15 before this one if you prefer; the plan lists this first because the engine test needs both.
- Produces:
  - `ScriptedModelProvider.builder()` with `.text(String)`, `.toolUse(String id, String name, ObjectNode arguments)`, `.endTurn()`, `.endWithToolUse()`, `.build()`
  - `ScriptedModelProvider.requests()` returning every `ModelRequest` it received, in order
  - `RecordingEventListener.events()` and `.states()`

**Note:** This task depends on `AgentEventListener` from Task 15. Execute Task 15 first, then return here. The ordering in this document puts the provider before the engine for narrative reasons; the dependency runs the other way.

- [ ] **Step 1: Write the failing test**

Create `nessy-testing/src/test/java/org/jwcarman/nessy/testing/ScriptedModelProviderTest.java`:

```java
package org.jwcarman.nessy.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jwcarman.nessy.core.Message;
import org.jwcarman.nessy.core.StopReason;
import org.jwcarman.nessy.model.ModelEvent;
import org.jwcarman.nessy.model.ModelRequest;
import org.jwcarman.nessy.model.ModelStream;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScriptedModelProviderTest {

    private static ModelRequest request() {
        return new ModelRequest(List.of(Message.user("hi")), "system", "fake-model", 1024, List.of(), Set.of());
    }

    private static List<ModelEvent> drain(ModelStream stream) {
        List<ModelEvent> events = new ArrayList<>();
        try (ModelStream open = stream) {
            open.forEach(events::add);
        }
        return events;
    }

    @Test
    void replaysASingleTextTurn() {
        ScriptedModelProvider provider =
                ScriptedModelProvider.builder().text("Hello").endTurn().build();

        List<ModelEvent> events = drain(provider.stream(request()));

        assertThat(events)
                .containsExactly(
                        new ModelEvent.TextChunk("Hello"),
                        new ModelEvent.TurnEnded(StopReason.END_TURN));
    }

    @Test
    void replaysTurnsInOrder() {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        ScriptedModelProvider provider = ScriptedModelProvider.builder()
                .toolUse("c1", "read_file", args)
                .endWithToolUse()
                .text("Done")
                .endTurn()
                .build();

        assertThat(drain(provider.stream(request()))).hasSize(2);
        assertThat(drain(provider.stream(request())))
                .containsExactly(new ModelEvent.TextChunk("Done"), new ModelEvent.TurnEnded(StopReason.END_TURN));
    }

    @Test
    void recordsEveryRequestItReceived() {
        ScriptedModelProvider provider =
                ScriptedModelProvider.builder().text("Hello").endTurn().build();

        provider.stream(request()).close();

        assertThat(provider.requests()).hasSize(1);
        assertThat(provider.requests().getFirst().model()).isEqualTo("fake-model");
    }

    @Test
    void runningOutOfScriptIsALoudFailure() {
        ScriptedModelProvider provider =
                ScriptedModelProvider.builder().text("Hello").endTurn().build();
        provider.stream(request()).close();

        assertThatThrownBy(() -> provider.stream(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("script exhausted");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -pl nessy-testing -am test`
Expected: FAIL — `cannot find symbol: class ScriptedModelProvider`.

- [ ] **Step 3: Write the implementation**

`ScriptedModelProvider.java`:

```java
package org.jwcarman.nessy.testing;

import org.jwcarman.nessy.core.StopReason;
import org.jwcarman.nessy.core.ToolCall;
import org.jwcarman.nessy.model.Capability;
import org.jwcarman.nessy.model.ModelEvent;
import org.jwcarman.nessy.model.ModelProvider;
import org.jwcarman.nessy.model.ModelRequest;
import org.jwcarman.nessy.model.ModelStream;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * A model that says exactly what you told it to.
 *
 * <p>This is how the whole loop gets tested without a key, a network, or a
 * nondeterministic remote service that charges per call. It also records every
 * request it received, so tests can assert on what the harness <em>sent</em>,
 * which is usually the more interesting half.
 */
public final class ScriptedModelProvider implements ModelProvider {

    private final List<List<ModelEvent>> turns;
    private final List<ModelRequest> requests = new ArrayList<>();
    private int nextTurn;

    private ScriptedModelProvider(List<List<ModelEvent>> turns) {
        this.turns = List.copyOf(turns);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ModelStream stream(ModelRequest request) {
        if (nextTurn >= turns.size()) {
            throw new IllegalStateException(
                    "script exhausted: the harness asked for turn " + (nextTurn + 1) + " of " + turns.size());
        }
        requests.add(request);
        Iterator<ModelEvent> events = turns.get(nextTurn++).iterator();
        return new ModelStream() {
            @Override
            public Iterator<ModelEvent> iterator() {
                return events;
            }

            @Override
            public void close() {
                // Nothing to release: the script is already in memory.
            }
        };
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of();
    }

    /** Every request this provider was handed, oldest first. */
    public List<ModelRequest> requests() {
        return Collections.unmodifiableList(requests);
    }

    public static final class Builder {

        private final List<List<ModelEvent>> turns = new ArrayList<>();
        private List<ModelEvent> current = new ArrayList<>();

        public Builder text(String text) {
            current.add(new ModelEvent.TextChunk(text));
            return this;
        }

        public Builder toolUse(String id, String name, ObjectNode arguments) {
            current.add(new ModelEvent.ToolUseEmitted(new ToolCall(id, name, arguments)));
            return this;
        }

        public Builder endTurn() {
            return end(StopReason.END_TURN);
        }

        public Builder endWithToolUse() {
            return end(StopReason.TOOL_USE);
        }

        private Builder end(StopReason reason) {
            current.add(new ModelEvent.TurnEnded(reason));
            turns.add(List.copyOf(current));
            current = new ArrayList<>();
            return this;
        }

        public ScriptedModelProvider build() {
            if (!current.isEmpty()) {
                throw new IllegalStateException("last turn was never ended: call endTurn() or endWithToolUse()");
            }
            return new ScriptedModelProvider(turns);
        }
    }
}
```

`RecordingEventListener.java`:

```java
package org.jwcarman.nessy.testing;

import org.jwcarman.nessy.core.Event;
import org.jwcarman.nessy.core.SessionId;
import org.jwcarman.nessy.core.SessionState;
import org.jwcarman.nessy.engine.AgentEventListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Captures everything the loop emitted, so tests can assert on it. */
public final class RecordingEventListener implements AgentEventListener {

    private final List<Event> events = new ArrayList<>();
    private final List<SessionState> states = new ArrayList<>();

    @Override
    public void onEvent(SessionId id, Event event, SessionState state) {
        events.add(event);
        states.add(state);
    }

    public List<Event> events() {
        return Collections.unmodifiableList(events);
    }

    public List<SessionState> states() {
        return Collections.unmodifiableList(states);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl nessy-testing -am test`
Expected: PASS — four tests in `ScriptedModelProviderTest` green.

- [ ] **Step 5: Commit**

```bash
git add nessy-testing/src
git commit -m "feat(testing): add ScriptedModelProvider and RecordingEventListener"
```

---

### Task 15: The execution engine

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/engine/AgentEventListener.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/engine/RunOutcome.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/engine/ExecutionEngine.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/engine/AgentConfig.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/engine/InProcessEngine.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/engine/InProcessEngineTest.java`

**Interfaces:**
- Consumes: everything from Tasks 2–13.
- Produces:
  - `AgentEventListener` with `void onEvent(SessionId id, Event event, SessionState state)`
  - sealed `RunOutcome` permitting `RunOutcome.Completed(SessionState state)` and `RunOutcome.Parked(SessionState state, ParkToken token)`
  - `ExecutionEngine` with `RunOutcome run(SessionId, Event)` and `RunOutcome resume(SessionId, ParkToken, Event)`
  - `AgentConfig(String model, String systemPrompt, int maxTokens)`
  - `InProcessEngine(ModelProvider, ToolRegistry, Approver, SessionStore, List<AgentEventListener>, Reducer, AgentConfig, ObjectMapper)`

**Note on ordering:** Task 14 depends on `AgentEventListener` from this task. Create this task's files before Task 14, or create `AgentEventListener` early.

- [ ] **Step 1: Write the failing test**

Create `nessy-core/src/test/java/org/jwcarman/nessy/engine/InProcessEngineTest.java`. Because `nessy-core` cannot depend on `nessy-testing` (that would be circular), this test declares a minimal inline scripted provider rather than reusing `ScriptedModelProvider`:

```java
package org.jwcarman.nessy.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jwcarman.nessy.approval.ApproveEverything;
import org.jwcarman.nessy.approval.Approver;
import org.jwcarman.nessy.approval.DenyEverything;
import org.jwcarman.nessy.core.Awaited;
import org.jwcarman.nessy.core.Event;
import org.jwcarman.nessy.core.Message;
import org.jwcarman.nessy.core.ParkToken;
import org.jwcarman.nessy.core.Reducer;
import org.jwcarman.nessy.core.Role;
import org.jwcarman.nessy.core.SessionId;
import org.jwcarman.nessy.core.SessionStatus;
import org.jwcarman.nessy.core.StopReason;
import org.jwcarman.nessy.core.TextBlock;
import org.jwcarman.nessy.core.ToolCall;
import org.jwcarman.nessy.core.ToolResult;
import org.jwcarman.nessy.core.ToolResultBlock;
import org.jwcarman.nessy.model.Capability;
import org.jwcarman.nessy.model.ModelEvent;
import org.jwcarman.nessy.model.ModelProvider;
import org.jwcarman.nessy.model.ModelRequest;
import org.jwcarman.nessy.model.ModelStream;
import org.jwcarman.nessy.session.InMemorySessionStore;
import org.jwcarman.nessy.session.SessionStore;
import org.jwcarman.nessy.tool.MapToolRegistry;
import org.jwcarman.nessy.tool.Tool;
import org.jwcarman.nessy.tool.ToolContext;
import org.jwcarman.nessy.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InProcessEngineTest {

    private static final SessionId ID = new SessionId("s1");
    private static final AgentConfig CONFIG = new AgentConfig("fake-model", "be helpful", 1024);

    /** A model that replays scripted turns, one per call. */
    private static final class FakeProvider implements ModelProvider {

        private final Deque<List<ModelEvent>> turns = new ArrayDeque<>();

        // Takes a List of turns rather than varargs: generic varargs would raise an
        // unchecked warning, and this project forbids @SuppressWarnings outright.
        FakeProvider(List<List<ModelEvent>> scripted) {
            turns.addAll(scripted);
        }

        @Override
        public ModelStream stream(ModelRequest request) {
            Iterator<ModelEvent> events = turns.removeFirst().iterator();
            return new ModelStream() {
                @Override
                public Iterator<ModelEvent> iterator() {
                    return events;
                }

                @Override
                public void close() {
                    // nothing to release
                }
            };
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of();
        }
    }

    record Echo(String value) {}

    private static final class EchoTool implements Tool<Echo> {

        private final boolean needsApproval;

        EchoTool(boolean needsApproval) {
            this.needsApproval = needsApproval;
        }

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "Echoes its input";
        }

        @Override
        public Class<Echo> inputType() {
            return Echo.class;
        }

        @Override
        public boolean requiresApproval() {
            return needsApproval;
        }

        @Override
        public Awaited<ToolResult> execute(Echo input, ToolContext context) {
            return Awaited.ready(ToolResult.ok("echoed:" + input.value()));
        }
    }

    /** A tool that throws, to prove the loop survives a broken tool. */
    private static final class ExplodingTool implements Tool<Echo> {

        @Override
        public String name() {
            return "boom";
        }

        @Override
        public String description() {
            return "Always throws";
        }

        @Override
        public Class<Echo> inputType() {
            return Echo.class;
        }

        @Override
        public boolean requiresApproval() {
            return false;
        }

        @Override
        public Awaited<ToolResult> execute(Echo input, ToolContext context) {
            throw new IllegalStateException("kaboom");
        }
    }

    /** An approver that records whether it was ever consulted. */
    private static final class CountingApprover implements Approver {

        private int calls;
        private final Approver delegate;

        CountingApprover(Approver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Awaited<org.jwcarman.nessy.core.Decision> approve(
                org.jwcarman.nessy.approval.ApprovalRequest request) {
            calls++;
            return delegate.approve(request);
        }
    }

    private static ObjectNode echoArgs(String value) {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("value", value);
        return args;
    }

    private static InProcessEngine engine(
            ModelProvider provider, ToolRegistry tools, Approver approver, SessionStore store, AgentEventListener... listeners) {
        return new InProcessEngine(
                provider,
                tools,
                approver,
                store,
                List.of(listeners),
                Reducer.withDefaults(),
                CONFIG,
                new ObjectMapper());
    }

    @Test
    void aPlainAnswerCompletesTheSession() {
        FakeProvider provider = new FakeProvider(
                List.of(new ModelEvent.TextChunk("Four."), new ModelEvent.TurnEnded(StopReason.END_TURN)));

        RunOutcome outcome = engine(provider, MapToolRegistry.of(), new ApproveEverything(), new InMemorySessionStore())
                .run(ID, new Event.UserSaid("what is 2+2?"));

        assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
        RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
        assertThat(completed.state().status()).isEqualTo(SessionStatus.COMPLETE);
        assertThat(completed.state().messages())
                .containsExactly(
                        Message.user("what is 2+2?"),
                        Message.assistant(List.of(new TextBlock("Four."))));
    }

    @Test
    void aToolCallRunsAndFeedsItsResultBack() {
        FakeProvider provider = new FakeProvider(
                List.of(
                        new ModelEvent.ToolUseEmitted(new ToolCall("c1", "echo", echoArgs("hi"))),
                        new ModelEvent.TurnEnded(StopReason.TOOL_USE)),
                List.of(new ModelEvent.TextChunk("Done."), new ModelEvent.TurnEnded(StopReason.END_TURN)));

        RunOutcome outcome = engine(
                        provider,
                        MapToolRegistry.of(new EchoTool(true)),
                        new ApproveEverything(),
                        new InMemorySessionStore())
                .run(ID, new Event.UserSaid("echo hi"));

        RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
        assertThat(completed.state().messages()).hasSize(4);
        assertThat(completed.state().messages().get(2).role()).isEqualTo(Role.USER);
        assertThat(completed.state().messages().get(2).content())
                .containsExactly(new ToolResultBlock("c1", "echoed:hi", false));
        assertThat(completed.state().status()).isEqualTo(SessionStatus.COMPLETE);
    }

    @Test
    void toolsThatDoNotRequireApprovalNeverReachTheApprover() {
        FakeProvider provider = new FakeProvider(
                List.of(
                        new ModelEvent.ToolUseEmitted(new ToolCall("c1", "echo", echoArgs("hi"))),
                        new ModelEvent.TurnEnded(StopReason.TOOL_USE)),
                List.of(new ModelEvent.TextChunk("Done."), new ModelEvent.TurnEnded(StopReason.END_TURN)));
        CountingApprover approver = new CountingApprover(new ApproveEverything());

        engine(provider, MapToolRegistry.of(new EchoTool(false)), approver, new InMemorySessionStore())
                .run(ID, new Event.UserSaid("echo hi"));

        assertThat(approver.calls).isZero();
    }

    @Test
    void aDenialBecomesAnErroredResultRatherThanAnException() {
        FakeProvider provider = new FakeProvider(
                List.of(
                        new ModelEvent.ToolUseEmitted(new ToolCall("c1", "echo", echoArgs("hi"))),
                        new ModelEvent.TurnEnded(StopReason.TOOL_USE)),
                List.of(new ModelEvent.TextChunk("Understood."), new ModelEvent.TurnEnded(StopReason.END_TURN)));

        RunOutcome outcome = engine(
                        provider,
                        MapToolRegistry.of(new EchoTool(true)),
                        new DenyEverything("not allowed"),
                        new InMemorySessionStore())
                .run(ID, new Event.UserSaid("echo hi"));

        RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
        assertThat(completed.state().messages().get(2).content())
                .containsExactly(new ToolResultBlock("c1", "Denied by user: not allowed", true));
    }

    @Test
    void anUnknownToolBecomesAnErroredResultTheModelCanSee() {
        FakeProvider provider = new FakeProvider(
                List.of(
                        new ModelEvent.ToolUseEmitted(new ToolCall("c1", "missing", echoArgs("hi"))),
                        new ModelEvent.TurnEnded(StopReason.TOOL_USE)),
                List.of(new ModelEvent.TextChunk("Oh."), new ModelEvent.TurnEnded(StopReason.END_TURN)));

        RunOutcome outcome = engine(provider, MapToolRegistry.of(), new ApproveEverything(), new InMemorySessionStore())
                .run(ID, new Event.UserSaid("go"));

        RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
        ToolResultBlock block = (ToolResultBlock) completed.state().messages().get(2).content().getFirst();
        assertThat(block.isError()).isTrue();
        assertThat(block.content()).contains("missing");
    }

    @Test
    void aThrowingToolBecomesAnErroredResultRatherThanKillingTheLoop() {
        FakeProvider provider = new FakeProvider(
                List.of(
                        new ModelEvent.ToolUseEmitted(new ToolCall("c1", "boom", echoArgs("hi"))),
                        new ModelEvent.TurnEnded(StopReason.TOOL_USE)),
                List.of(new ModelEvent.TextChunk("Oh."), new ModelEvent.TurnEnded(StopReason.END_TURN)));

        Tool<Echo> exploding = new ExplodingTool();

        RunOutcome outcome = engine(
                        provider, MapToolRegistry.of(exploding), new ApproveEverything(), new InMemorySessionStore())
                .run(ID, new Event.UserSaid("go"));

        RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
        ToolResultBlock block = (ToolResultBlock) completed.state().messages().get(2).content().getFirst();
        assertThat(block.isError()).isTrue();
        assertThat(block.content()).contains("kaboom");
    }

    @Test
    void listenersSeeEveryEventAsItHappens() {
        FakeProvider provider = new FakeProvider(
                List.of(
                        new ModelEvent.TextChunk("Fo"),
                        new ModelEvent.TextChunk("ur."),
                        new ModelEvent.TurnEnded(StopReason.END_TURN)));
        RecordingListener listener = new RecordingListener();

        engine(provider, MapToolRegistry.of(), new ApproveEverything(), new InMemorySessionStore(), listener)
                .run(ID, new Event.UserSaid("what is 2+2?"));

        assertThat(listener.events)
                .containsExactly(
                        new Event.UserSaid("what is 2+2?"),
                        new Event.TextDelta("Fo"),
                        new Event.TextDelta("ur."),
                        new Event.ModelTurnEnded(StopReason.END_TURN));
    }

    @Test
    void theFinalStateIsSaved() {
        FakeProvider provider = new FakeProvider(
                List.of(new ModelEvent.TextChunk("Four."), new ModelEvent.TurnEnded(StopReason.END_TURN)));
        SessionStore store = new InMemorySessionStore();

        engine(provider, MapToolRegistry.of(), new ApproveEverything(), store)
                .run(ID, new Event.UserSaid("what is 2+2?"));

        assertThat(store.load(ID)).isPresent();
        assertThat(store.load(ID).orElseThrow().status()).isEqualTo(SessionStatus.COMPLETE);
    }

    @Test
    void resumeIsRefusedBecauseThisEngineNeverParks() {
        FakeProvider provider = new FakeProvider();

        assertThatThrownBy(() -> engine(
                                provider,
                                MapToolRegistry.of(),
                                new ApproveEverything(),
                                new InMemorySessionStore())
                        .resume(ID, ParkToken.random(), new Event.UserSaid("x")))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("DurableEngine");
    }

    private static final class RecordingListener implements AgentEventListener {

        private final List<Event> events = new java.util.ArrayList<>();

        @Override
        public void onEvent(SessionId id, Event event, org.jwcarman.nessy.core.SessionState state) {
            events.add(event);
        }
    }
}
```

Before running, tidy this test's imports: replace every inline fully-qualified
reference (`org.jwcarman.nessy.core.Decision`, `org.jwcarman.nessy.approval.ApprovalRequest`,
`org.jwcarman.nessy.core.SessionState`, `java.util.ArrayList`) with explicit
single-symbol imports at the top. The project forbids star imports and prefers
imports over inline qualification.

Also wrap every `FakeProvider` call site. Its constructor takes
`List<List<ModelEvent>>` — a list of turns — rather than generic varargs, because
generic varargs raise an unchecked warning and this project forbids
`@SuppressWarnings` outright. So each construction wraps its turn lists:

```java
// one scripted turn
new FakeProvider(List.of(
        List.of(new ModelEvent.TextChunk("Four."), new ModelEvent.TurnEnded(StopReason.END_TURN))));

// two scripted turns
new FakeProvider(List.of(
        List.of(new ModelEvent.ToolUseEmitted(call), new ModelEvent.TurnEnded(StopReason.TOOL_USE)),
        List.of(new ModelEvent.TextChunk("Done."), new ModelEvent.TurnEnded(StopReason.END_TURN))));

// no scripted turns
new FakeProvider(List.of());
```

Apply that at all nine call sites.

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -pl nessy-core test`
Expected: FAIL — `cannot find symbol: class InProcessEngine`.

- [ ] **Step 3: Write the supporting types**

`AgentEventListener.java`:

```java
package org.jwcarman.nessy.engine;

import org.jwcarman.nessy.core.Event;
import org.jwcarman.nessy.core.SessionId;
import org.jwcarman.nessy.core.SessionState;

/**
 * Every front-end's window into the loop.
 *
 * <p>Called synchronously as each event is reduced, so a TUI paints tokens as
 * they arrive. Implementations must be quick and must not throw: this is a
 * notification channel, not a control point. The approver is where control lives.
 */
public interface AgentEventListener {

    void onEvent(SessionId id, Event event, SessionState state);
}
```

`RunOutcome.java`:

```java
package org.jwcarman.nessy.engine;

import org.jwcarman.nessy.core.ParkToken;
import org.jwcarman.nessy.core.SessionState;

/** How a run ended: finished, or waiting for something that outlives this process. */
public sealed interface RunOutcome {

    record Completed(SessionState state) implements RunOutcome {}

    record Parked(SessionState state, ParkToken token) implements RunOutcome {}
}
```

`ExecutionEngine.java`:

```java
package org.jwcarman.nessy.engine;

import org.jwcarman.nessy.core.Event;
import org.jwcarman.nessy.core.ParkToken;
import org.jwcarman.nessy.core.SessionId;

/**
 * Drives the reducer and performs its effects.
 *
 * <p>The line this interface draws is the sharpest one in Nessy: the reducer is
 * the <em>semantics</em>, an engine is the <em>execution strategy</em>. Swapping
 * engines changes durability, retry, and concurrency. It never changes what the
 * agent does.
 *
 * <p>Two methods on purpose. {@code cancel}, {@code status}, and {@code list} all
 * feel obvious to add and are all guesses until a front-end needs them.
 */
public interface ExecutionEngine {

    RunOutcome run(SessionId id, Event input);

    RunOutcome resume(SessionId id, ParkToken token, Event resolution);
}
```

`AgentConfig.java`:

```java
package org.jwcarman.nessy.engine;

/** The knobs one agent needs that are not seams. */
public record AgentConfig(String model, String systemPrompt, int maxTokens) {}
```

- [ ] **Step 4: Write the engine**

`InProcessEngine.java`:

```java
package org.jwcarman.nessy.engine;

import org.jwcarman.nessy.approval.ApprovalRequest;
import org.jwcarman.nessy.approval.Approver;
import org.jwcarman.nessy.core.Awaited;
import org.jwcarman.nessy.core.Decision;
import org.jwcarman.nessy.core.Effect;
import org.jwcarman.nessy.core.Event;
import org.jwcarman.nessy.core.ParkToken;
import org.jwcarman.nessy.core.Reducer;
import org.jwcarman.nessy.core.SessionId;
import org.jwcarman.nessy.core.SessionState;
import org.jwcarman.nessy.core.Step;
import org.jwcarman.nessy.core.ToolCall;
import org.jwcarman.nessy.core.ToolResult;
import org.jwcarman.nessy.model.ModelEvent;
import org.jwcarman.nessy.model.ModelProvider;
import org.jwcarman.nessy.model.ModelRequest;
import org.jwcarman.nessy.model.ModelStream;
import org.jwcarman.nessy.session.SessionStore;
import org.jwcarman.nessy.tool.Tool;
import org.jwcarman.nessy.tool.ToolContext;
import org.jwcarman.nessy.tool.ToolInvoker;
import org.jwcarman.nessy.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The default engine: blocking calls on whatever thread you hand it, and it
 * never parks.
 *
 * <p>Correct for a CLI, a test, or any front-end that owns the session for its
 * whole life. Run it on a virtual thread and a human taking an hour to approve
 * something costs a few hundred bytes of heap.
 *
 * <p>A tool or approver that returns {@link Awaited.Parked} is a configuration
 * error here, not a runtime condition: this engine has nowhere to park a session
 * to. It says so loudly rather than hanging.
 */
public final class InProcessEngine implements ExecutionEngine {

    private final ModelProvider provider;
    private final ToolRegistry tools;
    private final Approver approver;
    private final SessionStore store;
    private final List<AgentEventListener> listeners;
    private final Reducer reducer;
    private final AgentConfig config;
    private final ToolInvoker invoker;

    public InProcessEngine(
            ModelProvider provider,
            ToolRegistry tools,
            Approver approver,
            SessionStore store,
            List<AgentEventListener> listeners,
            Reducer reducer,
            AgentConfig config,
            ObjectMapper mapper) {
        this.provider = provider;
        this.tools = tools;
        this.approver = approver;
        this.store = store;
        this.listeners = List.copyOf(listeners);
        this.reducer = reducer;
        this.config = config;
        this.invoker = new ToolInvoker(mapper);
    }

    @Override
    public RunOutcome run(SessionId id, Event input) {
        SessionState state = store.load(id).orElseGet(() -> SessionState.newSession(id));
        SessionState finished = feed(state, input);
        store.save(finished);
        return new RunOutcome.Completed(finished);
    }

    @Override
    public RunOutcome resume(SessionId id, ParkToken token, Event resolution) {
        throw new UnsupportedOperationException(
                "InProcessEngine never parks, so there is nothing to resume. Use DurableEngine.");
    }

    /** Reduces one event and tells the listeners, without performing anything. */
    private Step reduceAndNotify(SessionState state, Event event) {
        Step step = reducer.reduce(state, event);
        for (AgentEventListener listener : listeners) {
            listener.onEvent(step.state().id(), event, step.state());
        }
        return step;
    }

    /** Reduces one event, tells the listeners, then performs whatever it asked for. */
    private SessionState feed(SessionState state, Event event) {
        Step step = reduceAndNotify(state, event);
        SessionState next = step.state();
        for (Effect effect : step.effects()) {
            next = perform(next, effect);
        }
        return next;
    }

    private SessionState perform(SessionState state, Effect effect) {
        return switch (effect) {
            case Effect.CallModel ignored -> callModel(state);
            case Effect.RequestApproval request -> feed(state, decide(state, request.call()));
            case Effect.ExecuteTool execute -> feed(state, executeTool(state, execute.call()));
        };
    }

    private SessionState callModel(SessionState state) {
        SessionState current = state;
        List<Effect> deferred = new ArrayList<>();
        // Effects are collected here and performed only AFTER the stream closes, so a
        // tool round-trip never opens a second stream while this one is still held.
        // Against a real provider the naive version keeps N HTTP connections alive for
        // N round-trips. Listeners are still notified inside the loop, so a TUI paints
        // tokens live — text deltas produce no effects, only the terminal event does.
        try (ModelStream stream = provider.stream(requestFor(current))) {
            for (ModelEvent modelEvent : stream) {
                Step step = reduceAndNotify(current, translate(modelEvent));
                current = step.state();
                deferred.addAll(step.effects());
            }
        }
        for (Effect effect : deferred) {
            current = perform(current, effect);
        }
        return current;
    }

    private ModelRequest requestFor(SessionState state) {
        return new ModelRequest(
                state.messages(),
                config.systemPrompt(),
                config.model(),
                config.maxTokens(),
                tools.specs(),
                Set.of());
    }

    private static Event translate(ModelEvent event) {
        return switch (event) {
            case ModelEvent.TextChunk chunk -> new Event.TextDelta(chunk.text());
            case ModelEvent.ToolUseEmitted emitted -> new Event.ToolCallRequested(emitted.call());
            case ModelEvent.TurnEnded ended -> new Event.ModelTurnEnded(ended.reason());
        };
    }

    /**
     * Answers the approval question for one call.
     *
     * <p>A tool that does not require approval is allowed here without troubling
     * the approver. The decision still belongs to the harness — the model has no
     * say in whether it is asked.
     */
    private Event decide(SessionState state, ToolCall call) {
        Optional<Tool<?>> found = tools.find(call.name());
        if (found.isEmpty()) {
            // Resolved as an allow so the missing-tool error surfaces once, in
            // execution, rather than as two different errors in two places.
            return new Event.ApprovalDecided(call, Decision.allow());
        }
        Tool<?> tool = found.get();
        if (!tool.requiresApproval()) {
            return new Event.ApprovalDecided(call, Decision.allow());
        }
        ApprovalRequest request =
                new ApprovalRequest(state.id(), call, describeForApproval(tool, call));
        return new Event.ApprovalDecided(call, resolve(approver.approve(request), "approver"));
    }

    /**
     * Renders a call for a human, tolerating arguments the tool's record cannot bind.
     *
     * <p>{@code describe} binds the arguments exactly as {@code invoke} does, so a
     * malformed tool call from the model would throw here. Unguarded, that escapes
     * {@code run} and the session is never saved — while the identical malformed call
     * against a tool needing no approval stays recoverable. Same mistake, opposite
     * outcomes, decided by a flag unrelated to argument validity. So fall back to the
     * raw JSON: the human still sees the arguments are malformed rather than being
     * shown something that looks like it parsed.
     */
    private String describeForApproval(Tool<?> tool, ToolCall call) {
        try {
            return invoker.describe(tool, call);
        } catch (RuntimeException e) {
            return call.name() + "(" + call.arguments() + ")";
        }
    }

    private Event executeTool(SessionState state, ToolCall call) {
        Optional<Tool<?>> found = tools.find(call.name());
        if (found.isEmpty()) {
            return new Event.ToolFinished(call, ToolResult.error("No such tool: " + call.name()));
        }
        Awaited<ToolResult> awaited;
        try {
            awaited = invoker.invoke(found.get(), call, new ToolContext(state.id()));
        } catch (RuntimeException e) {
            // Factor 9: the model sees a compact error and gets to recover. It
            // never sees a stack trace, and the loop never dies on a bad tool.
            return new Event.ToolFinished(call, ToolResult.error(describe(e)));
        }
        // resolve() is deliberately OUTSIDE the catch. It throws when a tool returns
        // Awaited.Parked, and that is a configuration error this engine must fail
        // loudly on — swallowing it into a ToolResult would turn a misconfiguration
        // into confusing model-visible noise.
        return new Event.ToolFinished(call, resolve(awaited, "tool " + call.name()));
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + message;
    }

    private static <T> T resolve(Awaited<T> awaited, String what) {
        return switch (awaited) {
            case Awaited.Ready<T> ready -> ready.value();
            case Awaited.Parked<T> ignored ->
                    throw new UnsupportedOperationException(
                            "InProcessEngine cannot park, but the " + what + " asked to. Use DurableEngine.");
        };
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q -pl nessy-core test`
Expected: PASS — nine tests in `InProcessEngineTest` green, and every earlier test still green.

- [ ] **Step 6: Commit**

```bash
git add nessy-core/src/main/java/org/jwcarman/nessy/engine nessy-core/src/test/java/org/jwcarman/nessy/engine
git commit -m "feat(engine): add ExecutionEngine seam and InProcessEngine"
```

---

### Task 16: The Nessy facade and an end-to-end test

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/engine/Nessy.java`
- Create: `nessy-testing/src/test/java/org/jwcarman/nessy/testing/EndToEndTest.java`
- Create: `README.md`

**Interfaces:**
- Consumes: everything.
- Produces: `Nessy.builder()` returning a `Nessy.Builder` with `.model(ModelProvider)`, `.tools(ToolRegistry)`, `.approver(Approver)`, `.store(SessionStore)`, `.listener(AgentEventListener)`, `.modelName(String)`, `.systemPrompt(String)`, `.maxTokens(int)`, `.maxConsecutiveErrors(int)`, and `.build()` returning an `ExecutionEngine`. Defaults: `MapToolRegistry.of()`, `ApproveEverything`, `InMemorySessionStore`, no listeners, `Reducer.withDefaults()`, 4096 max tokens. `model` and `modelName` are required.

- [ ] **Step 1: Write the failing test**

Create `nessy-testing/src/test/java/org/jwcarman/nessy/testing/EndToEndTest.java`:

```java
package org.jwcarman.nessy.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jwcarman.nessy.approval.ApproveEverything;
import org.jwcarman.nessy.core.Awaited;
import org.jwcarman.nessy.core.Event;
import org.jwcarman.nessy.core.SessionId;
import org.jwcarman.nessy.core.SessionStatus;
import org.jwcarman.nessy.core.ToolResult;
import org.jwcarman.nessy.engine.ExecutionEngine;
import org.jwcarman.nessy.engine.Nessy;
import org.jwcarman.nessy.engine.RunOutcome;
import org.jwcarman.nessy.tool.MapToolRegistry;
import org.jwcarman.nessy.tool.Tool;
import org.jwcarman.nessy.tool.ToolContext;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class EndToEndTest {

    record Add(int left, int right) {}

    static final class AddTool implements Tool<Add> {
        @Override
        public String name() {
            return "add";
        }

        @Override
        public String description() {
            return "Adds two integers";
        }

        @Override
        public Class<Add> inputType() {
            return Add.class;
        }

        @Override
        public boolean requiresApproval() {
            return true;
        }

        @Override
        public String describe(Add input) {
            return "add(" + input.left() + ", " + input.right() + ")";
        }

        @Override
        public Awaited<ToolResult> execute(Add input, ToolContext context) {
            return Awaited.ready(ToolResult.ok(String.valueOf(input.left() + input.right())));
        }
    }

    private static ObjectNode addArgs(int left, int right) {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("left", left);
        args.put("right", right);
        return args;
    }

    @Test
    void aFullToolCallingConversationRunsEndToEnd() {
        ScriptedModelProvider provider = ScriptedModelProvider.builder()
                .text("Let me add those.")
                .toolUse("c1", "add", addArgs(2, 2))
                .endWithToolUse()
                .text("The answer is 4.")
                .endTurn()
                .build();
        RecordingEventListener listener = new RecordingEventListener();

        ExecutionEngine engine = Nessy.builder()
                .model(provider)
                .modelName("fake-model")
                .systemPrompt("be helpful")
                .tools(MapToolRegistry.of(new AddTool()))
                .approver(new ApproveEverything())
                .listener(listener)
                .build();

        RunOutcome outcome = engine.run(new SessionId("s1"), new Event.UserSaid("what is 2+2?"));

        RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
        assertThat(completed.state().status()).isEqualTo(SessionStatus.COMPLETE);
        assertThat(completed.state().messages()).hasSize(4);
        assertThat(listener.events()).isNotEmpty();
    }

    @Test
    void theToolSchemaReachesTheModel() {
        ScriptedModelProvider provider =
                ScriptedModelProvider.builder().text("Hi").endTurn().build();

        Nessy.builder()
                .model(provider)
                .modelName("fake-model")
                .tools(MapToolRegistry.of(new AddTool()))
                .build()
                .run(new SessionId("s1"), new Event.UserSaid("hello"));

        assertThat(provider.requests().getFirst().tools()).hasSize(1);
        assertThat(provider.requests().getFirst().tools().getFirst().name()).isEqualTo("add");
        assertThat(provider.requests().getFirst().tools().getFirst().inputSchema().get("properties").has("left"))
                .isTrue();
    }

    @Test
    void aMissingModelIsRejectedAtBuildTime() {
        assertThatThrownBy(() -> Nessy.builder().modelName("fake-model").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -pl nessy-testing -am test`
Expected: FAIL — `cannot find symbol: class Nessy`.

- [ ] **Step 3: Write the facade**

`Nessy.java`:

```java
package org.jwcarman.nessy.engine;

import org.jwcarman.nessy.approval.ApproveEverything;
import org.jwcarman.nessy.approval.Approver;
import org.jwcarman.nessy.core.Reducer;
import org.jwcarman.nessy.model.ModelProvider;
import org.jwcarman.nessy.session.InMemorySessionStore;
import org.jwcarman.nessy.session.SessionStore;
import org.jwcarman.nessy.tool.MapToolRegistry;
import org.jwcarman.nessy.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * The front door.
 *
 * <p>Everything except the model has a default that works, so the smallest
 * useful agent is a provider and a model name. Every default here is a seam you
 * can replace, which is the whole point of the framework.
 */
public final class Nessy {

    private Nessy() {}

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private static final int DEFAULT_MAX_TOKENS = 4096;

        private ModelProvider model;
        private String modelName;
        private String systemPrompt = "";
        private int maxTokens = DEFAULT_MAX_TOKENS;
        private ToolRegistry tools = MapToolRegistry.of();
        private Approver approver = new ApproveEverything();
        private SessionStore store = new InMemorySessionStore();
        private final List<AgentEventListener> listeners = new ArrayList<>();
        private int maxConsecutiveErrors = Reducer.DEFAULT_MAX_CONSECUTIVE_ERRORS;
        private ObjectMapper mapper = new ObjectMapper();

        private Builder() {}

        public Builder model(ModelProvider model) {
            this.model = model;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder tools(ToolRegistry tools) {
            this.tools = tools;
            return this;
        }

        public Builder approver(Approver approver) {
            this.approver = approver;
            return this;
        }

        public Builder store(SessionStore store) {
            this.store = store;
            return this;
        }

        public Builder listener(AgentEventListener listener) {
            this.listeners.add(listener);
            return this;
        }

        public Builder maxConsecutiveErrors(int maxConsecutiveErrors) {
            this.maxConsecutiveErrors = maxConsecutiveErrors;
            return this;
        }

        public Builder objectMapper(ObjectMapper mapper) {
            this.mapper = mapper;
            return this;
        }

        public ExecutionEngine build() {
            if (model == null) {
                throw new IllegalStateException("a model provider is required: call model(...)");
            }
            if (modelName == null || modelName.isBlank()) {
                throw new IllegalStateException("a model name is required: call modelName(...)");
            }
            return new InProcessEngine(
                    model,
                    tools,
                    approver,
                    store,
                    listeners,
                    new Reducer(maxConsecutiveErrors),
                    new AgentConfig(modelName, systemPrompt, maxTokens),
                    mapper);
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q verify`
Expected: PASS — the whole reactor green, every module, no network access, no API key.

- [ ] **Step 5: Write the README**

Create `README.md`:

```markdown
# Nessy

An AI agent harness framework for Java.

Nessy supplies the machinery that turns a model API into an agent — the loop, the
tool plumbing, the approval gate, the session lifecycle — and exposes every
pluggable part of it as a seam.

## Status

Early. `nessy-core` and `nessy-testing` are implemented: a complete streaming,
tool-calling loop with an approval gate, tested end to end against a scripted
model. Provider modules, durable execution, the Spring Boot starter, and the TUI
are not built yet.

## Requirements

JDK 25 and Maven.

## The smallest agent

```java
ExecutionEngine engine = Nessy.builder()
        .model(someProvider)
        .modelName("some-model")
        .systemPrompt("You are a helpful assistant.")
        .tools(MapToolRegistry.of(new ReadFileTool()))
        .approver(new ApproveEverything())
        .build();

RunOutcome outcome = engine.run(SessionId.random(), new Event.UserSaid("what is 2+2?"));
```

## How it works

The core is an **effectful reducer**. `reduce(SessionState, Event)` is pure,
synchronous, and does no I/O — it returns the next state plus a list of `Effect`s
describing what should happen. An `ExecutionEngine` performs those effects and
feeds every result back in as an `Event`.

Streaming tokens are ordinary events, so the loop streams natively rather than by
retrofit. `SessionState` is a plain serializable record, so pausing is "stop
feeding events" and resuming is "load the state and keep feeding" — whether the
gap is 200 milliseconds or two days.

Every seam is a plain blocking interface. On virtual threads that is cheaper and
far more readable than a callback protocol.

## The seams

| Seam | What you plug in |
|---|---|
| `ExecutionEngine` | how the loop runs: in-process, durable, or on a workflow engine |
| `ModelProvider` | where tokens come from, with explicit capability negotiation |
| `Tool` / `ToolRegistry` | what the agent can do; schemas derive from records |
| `Approver` | the safety gate the model cannot route around |
| `SessionStore` | where a session lives between steps |
| `AgentEventListener` | how a front-end sees inside the loop |

## Building

```bash
mvn verify
```

The default build needs no API key and makes no network calls. Tests that spend
real tokens are tagged `live` and excluded:

```bash
mvn test -Dgroups=live
```

## Design

See `docs/superpowers/specs/2026-08-08-nessy-agent-harness-design.md`.
```

- [ ] **Step 6: Run the full build one more time**

Run: `mvn -q verify`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add nessy-core/src/main/java/org/jwcarman/nessy/engine/Nessy.java nessy-testing/src/test README.md
git commit -m "feat: add Nessy builder facade and end-to-end coverage"
```

---

## Self-Review

**Spec coverage.** Every v1 item in the spec's scope section maps to a task, except the deliberately deferred ones:

| Spec requirement | Task |
|---|---|
| reducer, `SessionState`, `Event`, `Effect` | 3–7 |
| `Awaited`, `ParkToken` | 8 |
| `ExecutionEngine` + `InProcessEngine` | 15 |
| `ModelProvider` with capability negotiation | 11 |
| `Tool`, `ToolRegistry` | 9, 10 |
| `Approver` | 12 |
| `SessionStore` | 13 |
| `AgentEventListener` | 15 |
| `nessy-testing` | 14 |
| factor 9 error split | 7 (model-visible), 15 (infrastructure boundary) |
| tool result batching | 7 |
| single-use park tokens | 13 |

**Deliberately not in this plan**, deferred to Plans 2–6 as stated in the scope note: `DurableEngine`, `nessy-model-anthropic`, `nessy-model-openai`, `nessy-spring-boot-starter`, `nessy-tui`. `InProcessEngine` refuses to park with a message naming `DurableEngine`, so the gap is loud rather than silent.

**Known gap:** the spec's infrastructure-error handling (retry with backoff on 429s and 5xx) is **not** implemented in this plan. `InProcessEngine` lets provider exceptions propagate out of `run`, which is the correct *boundary* but not the retry behavior. Retry belongs with a real provider, so it lands in Plan 2 where it can be tested against real failure modes rather than invented ones.

**Type consistency check.** `SessionState` withers, `Event` and `Effect` nested record names, `Awaited.ready`/`Awaited.parked`, `Decision.allow()`, `Tool.spec()`, `ToolInvoker.invoke`/`describe`, and `ModelEvent` variant names are used identically everywhere they appear. `Reducer.DEFAULT_MAX_CONSECUTIVE_ERRORS` is referenced by `Nessy.Builder`, and both are defined in Task 5.

**Ordering note carried in the plan:** Task 14 depends on `AgentEventListener` from Task 15. Both tasks flag it; execute Task 15's interface files first.
