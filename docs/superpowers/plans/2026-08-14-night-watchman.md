# The Night Watchman Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A long-lived Spring Boot example (`nessy-examples/night-watchman`) exhibiting the time-triggered agent pattern: a `@Scheduled(cron = …)` firing initiates each turn of ONE continuous conversation, whose recalled context is hard-bounded by a windowing `Memory`.

**Architecture:** `@EnableScheduling` keeps the JVM alive; each firing tells the same conversation "It is HH:mm — do your rounds." The agent reads drifting synthetic vitals via `check_vitals`, judges trends against its standing orders, and either reports all-quiet or calls `raise_alarm`. `WindowedMemory` (delegate `ListMemory`, recall `keepRecent(window)`) is the first custom-`Memory` dogfood. No web, no JDBC, no Docker anywhere — the starter's in-memory defaults carry everything, and the whole test suite runs in the offline default build.

**Tech Stack:** Java 25, Spring Boot 4.1.0 (plain `spring-boot-starter` + `spring-context` scheduling), `nessy-spring-boot-starter`, `nessy-model-anthropic`, scripted `ModelProvider` for tests (no Testcontainers).

**Spec:** `docs/superpowers/specs/2026-08-14-night-watchman-design.md` — read it before implementing any task.

## Global Constraints

- **TDD** where a test is prescribed: failing test first, watch it fail, implement, watch it pass; RED/GREEN evidence in the report.
- **The whole suite is offline** — no Docker, no API key, no `@Tag("container")` anywhere in this module. `./mvnw -q clean verify` must stay green.
- **Before every commit:** `./mvnw license:format -Plicense && ./mvnw spotless:apply`, then re-stage. Never hand-write license headers. Never stage IDE metadata (`.classpath`, `.project`, `.settings/`, `.factorypath`).
- **No warning suppressions. No star imports. No mocking libraries** (Boot's test starter excludes `mockito-core`, copying chat-web).
- **Prose snake_case test names.** S5778: one throwing invocation per exception-assertion lambda. S5841: assert non-empty before match-predicates. S8688-style: no zone-implicit `now()` — pass `ZoneId.systemDefault()` explicitly with a comment (see `ClockTool` in chat-cli).
- **Boot BOM confined in-module** (`spring-boot-dependencies` 4.1.0 imported only in this example's pom). `<maven.deploy.skip>true</maven.deploy.skip>`.
- **Package:** `org.jwcarman.nessy.examples.watchman`. **Module dir:** `nessy-examples/night-watchman`. **ArtifactId:** `nessy-example-night-watchman`.
- Commit messages follow the repo's house style; end each with the trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: Module scaffold — pom, application class, config

**Files:**
- Modify: `nessy-examples/pom.xml` (add `<module>night-watchman</module>`)
- Create: `nessy-examples/night-watchman/pom.xml`
- Create: `nessy-examples/night-watchman/src/main/java/org/jwcarman/nessy/examples/watchman/NightWatchmanApplication.java`
- Create: `nessy-examples/night-watchman/src/main/resources/application.yaml`

**Interfaces:**
- Consumes: nothing.
- Produces: the module every later task builds in; `NightWatchmanApplication` (`@SpringBootApplication @EnableScheduling`).

- [ ] **Step 1: Add the module to the examples aggregator**

In `nessy-examples/pom.xml`, change:

```xml
  <modules>
    <module>chat-cli</module>
    <module>chat-web</module>
  </modules>
```

to:

```xml
  <modules>
    <module>chat-cli</module>
    <module>chat-web</module>
    <module>night-watchman</module>
  </modules>
```

- [ ] **Step 2: Write the module pom**

Create `nessy-examples/night-watchman/pom.xml` (license header arrives via `license:format` — do not hand-write it):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-examples</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>nessy-example-night-watchman</artifactId>
  <name>Nessy Example: Night Watchman</name>
  <description>A time-triggered agent: Spring scheduling initiates each turn of one continuous, memory-bounded conversation</description>

  <properties>
    <!-- Examples are never published: no jar, sources, or javadoc leaves this module. -->
    <maven.deploy.skip>true</maven.deploy.skip>

    <spring-boot.version>4.1.0</spring-boot.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <!-- Spring enters the reactor only inside example modules: this import, confined here,
           exactly the chat-web discipline. -->
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <!-- The plain starter: no web, no JDBC, no Docker anywhere in this module — the starter's
         in-memory defaults are the whole substrate (spec §3). @Scheduled comes with
         spring-context, already inside this starter. -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <!-- Boot 4's default logging starter only bridges Log4j2/JUL onto SLF4J; it ships no SLF4J
         binding of its own. Compile-scope, as in chat-web: examples are runnable apps, and an app
         picks its own SLF4J provider. -->
    <dependency>
      <groupId>ch.qos.logback</groupId>
      <artifactId>logback-classic</artifactId>
      <scope>compile</scope>
    </dependency>

    <dependency>
      <groupId>org.jwcarman.nessy</groupId>
      <artifactId>nessy-spring-boot-starter</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.jwcarman.nessy</groupId>
      <artifactId>nessy-model-anthropic</artifactId>
      <version>${project.version}</version>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
      <exclusions>
        <!-- The house bans mocking libraries (no test doubles from a framework); Boot's test
             starter pulls Mockito in transitively, so it is excluded explicitly. -->
        <exclusion>
          <groupId>org.mockito</groupId>
          <artifactId>mockito-core</artifactId>
        </exclusion>
      </exclusions>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <!-- Version comes from the BOM's pluginManagement. No repackage execution: the demo runs
           `spring-boot:run` (spec §1's success criterion) — nothing ships a jar. -->
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Write the application class**

```java
package org.jwcarman.nessy.examples.watchman;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The night watchman: a long-lived app whose turns are initiated by the clock (spec §1). No web,
 * no console loop — {@code @EnableScheduling}'s non-daemon scheduler thread is what keeps the JVM
 * alive, and the {@code @Scheduled} round in {@code Watchman} is the only driver. The log is the
 * UI.
 */
@SpringBootApplication
@EnableScheduling
public class NightWatchmanApplication {

  public static void main(String[] args) {
    SpringApplication.run(NightWatchmanApplication.class, args);
  }
}
```

- [ ] **Step 4: Write application.yaml**

```yaml
spring:
  application:
    name: nessy-night-watchman
  main:
    web-application-type: none
    banner-mode: off

watchman:
  # When the watchman walks: Spring cron, default the top of each minute.
  cadence: "0 * * * * *"
  # The recall window, in messages — the hard bound on what a round's model call can see.
  window: 40
```

- [ ] **Step 5: Verify the module builds offline**

Run: `./mvnw -q clean verify -pl nessy-examples/night-watchman -am`
Expected: BUILD SUCCESS, no Docker, no key.

- [ ] **Step 6: Format and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-examples/pom.xml nessy-examples/night-watchman
git commit -m "feat: the night watchman is scaffolded — a clock, soon, will be the caller"
```

---

### Task 2: The engine room — drifting vitals

**Files:**
- Create: `nessy-examples/night-watchman/src/main/java/org/jwcarman/nessy/examples/watchman/EngineRoom.java`
- Test: `nessy-examples/night-watchman/src/test/java/org/jwcarman/nessy/examples/watchman/EngineRoomTest.java`

**Interfaces:**
- Produces (Tasks 4–5 rely on these exact signatures): `EngineRoom` — `@Component`, public no-arg constructor (fixed default seed), package-private `EngineRoom(long seed)` for tests; `public record Vitals(double boilerPressurePsi, double bilgeLevelCm, double hullStressMpa)` (nested); `public synchronized Vitals read()` — advances the walk one step and returns the new readings, one decimal place each.

- [ ] **Step 1: Write the failing test**

```java
package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The synthetic vitals, pinned: deterministic under a seed (so the demo and the tests tell the
 * same story) and biased so the bilge genuinely rises — the drift is what gives the demo its arc
 * (spec §2).
 */
class EngineRoomTest {

  @Test
  void the_same_seed_tells_the_same_story() {
    EngineRoom first = new EngineRoom(42L);
    EngineRoom second = new EngineRoom(42L);
    for (int i = 0; i < 10; i++) {
      assertThat(first.read()).isEqualTo(second.read());
    }
  }

  @Test
  void the_bilge_rises_because_the_walk_is_biased() {
    EngineRoom engineRoom = new EngineRoom(42L);
    double start = engineRoom.read().bilgeLevelCm();
    double last = start;
    for (int i = 0; i < 19; i++) {
      last = engineRoom.read().bilgeLevelCm();
    }
    // Bias is +3.5/step against noise sd 1.5: after 20 steps the climb dominates decisively.
    assertThat(last).isGreaterThan(start + 30.0);
  }

  @Test
  void every_vital_stays_inside_its_physical_clamp() {
    EngineRoom engineRoom = new EngineRoom(7L);
    for (int i = 0; i < 200; i++) {
      EngineRoom.Vitals vitals = engineRoom.read();
      assertThat(vitals.boilerPressurePsi()).isBetween(150.0, 260.0);
      assertThat(vitals.bilgeLevelCm()).isBetween(0.0, 100.0);
      assertThat(vitals.hullStressMpa()).isBetween(20.0, 90.0);
    }
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -pl nessy-examples/night-watchman -am`
Expected: COMPILE FAILURE — `EngineRoom` does not exist.

- [ ] **Step 3: Implement EngineRoom**

```java
package org.jwcarman.nessy.examples.watchman;

import java.util.Random;
import org.springframework.stereotype.Component;

/**
 * The engine room's synthetic vitals (spec §2): a seeded random walk, so the story is
 * reproducible, with the bilge deliberately biased upward so a demo run is guaranteed its arc —
 * quiet rounds, a trend, an alarm — within five-to-eight minutes at the default cadence. Fake and
 * obviously so, the coupon-tool ethos. {@code read()} is synchronized out of caution; the default
 * single-threaded scheduler already serializes rounds.
 */
@Component
public class EngineRoom {

  /** One reading of the three gauges, each rounded to one decimal place. */
  public record Vitals(double boilerPressurePsi, double bilgeLevelCm, double hullStressMpa) {}

  private static final long DEFAULT_SEED = 7L;

  private final Random walk;
  private double boiler = 180.0;
  private double bilge = 12.0;
  private double hull = 40.0;

  public EngineRoom() {
    this(DEFAULT_SEED);
  }

  EngineRoom(long seed) {
    this.walk = new Random(seed);
  }

  /** Advances the walk one step and reads all three gauges. */
  public synchronized Vitals read() {
    boiler = clamp(boiler + walk.nextGaussian() * 2.0, 150.0, 260.0);
    bilge = clamp(bilge + walk.nextGaussian() * 1.5 + 3.5, 0.0, 100.0);
    hull = clamp(hull + walk.nextGaussian(), 20.0, 90.0);
    return new Vitals(round1(boiler), round1(bilge), round1(hull));
  }

  private static double clamp(double value, double floor, double ceiling) {
    return Math.min(ceiling, Math.max(floor, value));
  }

  private static double round1(double value) {
    return Math.round(value * 10.0) / 10.0;
  }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -pl nessy-examples/night-watchman -am`
Expected: PASS (3 tests). If `the_bilge_rises...` fails on the chosen seed, adjust the assertion threshold downward toward `start + 20.0` rather than reseeding — the bias math (20 × 3.5 = 70 against noise sd ≈ 6.7) leaves enormous margin, so a failure means a real bug, not seed luck.

- [ ] **Step 5: Format and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-examples/night-watchman
git commit -m "feat: the engine room breathes — three gauges, one biased walk"
```

---

### Task 3: The bound — WindowedMemory

**Files:**
- Create: `nessy-examples/night-watchman/src/main/java/org/jwcarman/nessy/examples/watchman/WindowedMemory.java`
- Test: `nessy-examples/night-watchman/src/test/java/org/jwcarman/nessy/examples/watchman/WindowedMemoryTest.java`

**Interfaces:**
- Consumes from nessy-core: `Memory` (`remember(ConversationId, Message)`, `recall(ConversationId)`), `ListMemory` (public no-arg constructor), `Context.keepRecent(int)` (pair-safe trim).
- Produces (Task 4 relies on it): `public final class WindowedMemory implements Memory`, constructor `WindowedMemory(int window)` (throws `IllegalArgumentException` if `window < 1`).

- [ ] **Step 1: Write the failing test**

```java
package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * The border law, observed at the seam (spec §4): retention stays whole, recall is trimmed to the
 * window, and the trim is pair-safe — a Context self-validates on construction, so recall
 * returning at all proves wire-legality; these tests pin the bound and the pairing behavior.
 */
class WindowedMemoryTest {

  @Test
  void recall_is_bounded_by_the_window() {
    WindowedMemory memory = new WindowedMemory(4);
    ConversationId id = ConversationId.generate();
    for (int i = 0; i < 10; i++) {
      memory.remember(id, Message.user("round " + i));
      memory.remember(id, Message.assistant(List.of()));
    }

    Context recalled = memory.recall(id);

    assertThat(recalled.messages()).hasSize(4);
    assertThat(recalled.messages().getFirst().content()).isNotEmpty();
  }

  @Test
  void a_tool_exchange_survives_the_cut_whole_or_not_at_all() {
    WindowedMemory memory = new WindowedMemory(3);
    ConversationId id = ConversationId.generate();
    ToolCall call = new ToolCall("c1", "check_vitals", JsonNodeFactory.instance.objectNode());
    memory.remember(id, Message.user("round 1"));
    memory.remember(id, Message.assistant(List.of(new ToolUseBlock(call))));
    memory.remember(id, Message.toolResults(List.of(new ToolResultBlock("c1", "vitals", false))));
    memory.remember(id, Message.user("round 2"));
    memory.remember(id, Message.assistant(List.of()));

    Context recalled = memory.recall(id);

    // keepRecent cuts only at a genuine user turn, so the exchange either survives with its
    // results or is dropped entirely; Context's own constructor makes a split unconstructible.
    boolean hasToolUse =
        recalled.messages().stream()
            .flatMap(message -> message.content().stream())
            .anyMatch(ToolUseBlock.class::isInstance);
    boolean hasToolResult =
        recalled.messages().stream()
            .flatMap(message -> message.content().stream())
            .anyMatch(ToolResultBlock.class::isInstance);
    assertThat(hasToolUse).isEqualTo(hasToolResult);
    assertThat(recalled.messages()).isNotEmpty();
    assertThat(recalled.messages().getLast().content()).isEmpty();
  }

  @Test
  void a_window_below_one_is_rejected() {
    assertThatThrownBy(() -> new WindowedMemory(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
```

Note: `Message`'s canonical constructor accepts empty content (verified against `Message.java` — it only null-checks the role and copies the list), so `Message.assistant(List.of())` is legal as written.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -pl nessy-examples/night-watchman -am`
Expected: COMPILE FAILURE — `WindowedMemory` does not exist.

- [ ] **Step 3: Implement WindowedMemory**

```java
package org.jwcarman.nessy.examples.watchman;

import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.memory.ListMemory;
import org.jwcarman.nessy.spi.memory.Memory;

/**
 * The bound (spec §4): freedom of retention, rule of law at the border. Retention delegates whole
 * to {@link ListMemory}; {@code recall} hands the loop only the last {@code window} messages via
 * {@link Context#keepRecent}, whose cut is pair-safe by construction — the trimmed context is
 * always wire-legal, no tool exchange ever split. The watchman's horizon is its window: it
 * remembers recent rounds, not its whole life, which is why an endless conversation cannot grow
 * the model call.
 */
public final class WindowedMemory implements Memory {

  private final Memory delegate = new ListMemory();
  private final int window;

  public WindowedMemory(int window) {
    if (window < 1) {
      throw new IllegalArgumentException("window must be at least 1");
    }
    this.window = window;
  }

  @Override
  public void remember(ConversationId id, Message message) {
    delegate.remember(id, message);
  }

  @Override
  public Context recall(ConversationId id) {
    return delegate.recall(id).keepRecent(window);
  }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -pl nessy-examples/night-watchman -am`
Expected: PASS (6 tests total in module).

- [ ] **Step 5: Format and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-examples/night-watchman
git commit -m "feat: WindowedMemory — freedom of retention, a hard law at the border"
```

---

### Task 4: The tools and the agent bean

**Files:**
- Create: `nessy-examples/night-watchman/src/main/java/org/jwcarman/nessy/examples/watchman/CheckVitalsTool.java`
- Create: `nessy-examples/night-watchman/src/main/java/org/jwcarman/nessy/examples/watchman/RaiseAlarmTool.java`
- Create: `nessy-examples/night-watchman/src/main/java/org/jwcarman/nessy/examples/watchman/WatchmanConfig.java`
- Test: `nessy-examples/night-watchman/src/test/java/org/jwcarman/nessy/examples/watchman/WatchmanToolsTest.java`

**Interfaces:**
- Consumes: `EngineRoom.read()` → `Vitals` (Task 2); `WindowedMemory(int)` (Task 3); from nessy: `Tool`, `ToolContext`, `ToolResult`, `Awaited.ready`, `ToolGrant.grant`, `UsagePolicy.allow()`, `Harness.agent()`.
- Produces (Task 5 relies on): tool names exactly `check_vitals` (input `record Input() {}` — zero-arg, the chat-cli `ClockTool` precedent) and `raise_alarm` (input `record Input(String severity, String reason) {}`); `WatchmanConfig` — the example's ONE nessy bean, `Agent<String> agent(Harness, EngineRoom, @Value("${watchman.window:40}") int window)`.

- [ ] **Step 1: Write the failing test**

```java
package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The watchman's two hands, pinned without Spring: the wire names the model calls, and both
 * execute paths returning {@code Ready} — no parks anywhere in this example (spec §7).
 */
class WatchmanToolsTest {

  private static ToolContext context(String callId, String name) {
    ToolCall call = new ToolCall(callId, name, JsonNodeFactory.instance.objectNode());
    return new ToolContext(ConversationId.generate(), call, EventEmitter.noop());
  }

  @Test
  void check_vitals_reads_all_three_gauges() {
    CheckVitalsTool tool = new CheckVitalsTool(new EngineRoom(42L));
    assertThat(tool.name()).isEqualTo("check_vitals");

    Awaited<ToolResult> awaited =
        tool.execute(new CheckVitalsTool.Input(), context("c1", "check_vitals"));

    assertThat(awaited).isInstanceOf(Awaited.Ready.class);
    ToolResult result = ((Awaited.Ready<ToolResult>) awaited).value();
    assertThat(result.isError()).isFalse();
    assertThat(result.content()).contains("boiler pressure");
    assertThat(result.content()).contains("bilge level");
    assertThat(result.content()).contains("hull stress");
  }

  @Test
  void raise_alarm_answers_ready_and_echoes_the_cause() {
    RaiseAlarmTool tool = new RaiseAlarmTool();
    assertThat(tool.name()).isEqualTo("raise_alarm");

    Awaited<ToolResult> awaited =
        tool.execute(
            new RaiseAlarmTool.Input("high", "bilge level climbing three rounds straight"),
            context("c2", "raise_alarm"));

    assertThat(awaited).isInstanceOf(Awaited.Ready.class);
    ToolResult result = ((Awaited.Ready<ToolResult>) awaited).value();
    assertThat(result.isError()).isFalse();
    assertThat(result.content()).contains("high");
    assertThat(result.content()).contains("bilge level climbing three rounds straight");
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -pl nessy-examples/night-watchman -am`
Expected: COMPILE FAILURE — the tools do not exist.

- [ ] **Step 3: Implement the tools**

`CheckVitalsTool.java`:

```java
package org.jwcarman.nessy.examples.watchman;

import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;

/** The watchman's lantern: reads the engine room's three gauges. Zero arguments, always ready. */
public final class CheckVitalsTool implements Tool<CheckVitalsTool.Input> {

  public record Input() {}

  private final EngineRoom engineRoom;

  public CheckVitalsTool(EngineRoom engineRoom) {
    this.engineRoom = engineRoom;
  }

  @Override
  public String name() {
    return "check_vitals";
  }

  @Override
  public String description() {
    return "Reads the engine room's current vitals: boiler pressure, bilge level, hull stress."
        + " Use once per round.";
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public Awaited<ToolResult> execute(Input input, ToolContext context) {
    EngineRoom.Vitals vitals = engineRoom.read();
    return Awaited.ready(
        ToolResult.ok(
            "boiler pressure "
                + vitals.boilerPressurePsi()
                + " psi; bilge level "
                + vitals.bilgeLevelCm()
                + " cm; hull stress "
                + vitals.hullStressMpa()
                + " MPa"));
  }
}
```

`RaiseAlarmTool.java`:

```java
package org.jwcarman.nessy.examples.watchman;

import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The alarm bell: logs at WARN, loudly and obviously fake — no pager is harmed (spec §2, the
 * coupon-tool ethos).
 */
public final class RaiseAlarmTool implements Tool<RaiseAlarmTool.Input> {

  private static final Logger LOGGER = LoggerFactory.getLogger(RaiseAlarmTool.class);

  public record Input(String severity, String reason) {}

  @Override
  public String name() {
    return "raise_alarm";
  }

  @Override
  public String description() {
    return "Raises the engine-room alarm. Use decisively when a vital is out of its normal band"
        + " or clearly trending toward it.";
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public Awaited<ToolResult> execute(Input input, ToolContext context) {
    LOGGER.warn("ALARM [{}] {}", input.severity(), input.reason());
    return Awaited.ready(
        ToolResult.ok(
            "Alarm raised (demo — nothing real was paged): ["
                + input.severity()
                + "] "
                + input.reason()));
  }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -pl nessy-examples/night-watchman -am`
Expected: PASS.

- [ ] **Step 5: Write the agent bean**

`WatchmanConfig.java`:

```java
package org.jwcarman.nessy.examples.watchman;

import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The nessy wiring — one bean, the agent (spec §5). {@code Harness} and {@code ModelProvider}
 * arrive from the starter's autoconfiguration over the in-memory defaults; identity is declared
 * here: the standing orders, the two always-allowed tools (no human in this loop, nothing parks),
 * and the {@link WindowedMemory} bound.
 */
@Configuration
public class WatchmanConfig {

  private static final String SYSTEM_PROMPT =
      "You are the night watchman of a ship's engine room. Standing orders: each round, check"
          + " the vitals with your tool and compare them with your recent rounds. Normal bands:"
          + " boiler pressure 150-220 psi; bilge level below 35 cm; hull stress below 70 MPa."
          + " If all is well, report all quiet in one terse sentence. If a vital is out of band"
          + " or clearly trending toward it across rounds, raise the alarm decisively with your"
          + " alarm tool, then summarize why in one sentence.";

  @Bean
  Agent<String> agent(
      Harness harness, EngineRoom engineRoom, @Value("${watchman.window:40}") int window) {
    return harness
        .agent()
        .model("claude-sonnet-4-5")
        .systemPrompt(SYSTEM_PROMPT)
        .memory(new WindowedMemory(window))
        .tools(
            ToolGrant.grant(new CheckVitalsTool(engineRoom), UsagePolicy.allow()),
            ToolGrant.grant(new RaiseAlarmTool(), UsagePolicy.allow()))
        .build();
  }
}
```

- [ ] **Step 6: Verify the offline build stays green**

Run: `./mvnw -q clean verify`
Expected: BUILD SUCCESS (`WatchmanConfig` only compiles; no context boots without a test).

- [ ] **Step 7: Format and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-examples/night-watchman
git commit -m "feat: two hands and standing orders — the watchman's identity is one bean"
```

---

### Task 5: The rounds — Watchman and the smoke test

**Files:**
- Create: `nessy-examples/night-watchman/src/main/java/org/jwcarman/nessy/examples/watchman/Watchman.java`
- Test: `nessy-examples/night-watchman/src/test/java/org/jwcarman/nessy/examples/watchman/NightWatchmanSmokeTest.java`

**Interfaces:**
- Consumes: the `Agent<String>` bean (Task 4); from nessy: `Conversation.tell(String, TurnObserver)`, `TurnEvent.TextDelta`/`ToolCallRequested`, `RunOutcome`, `Agent.contextFor(ConversationId)`.
- Produces: `Watchman` — `@Component`; `public ConversationId conversationId()`; `@Scheduled(cron = "${watchman.cadence:0 * * * * *}") public void round()`; package-private `RunOutcome round(LocalTime time)` — the test's entry point (the scheduler is only a trigger).

- [ ] **Step 1: Write the failing smoke test**

Plain `@SpringBootTest` — non-web, NO Docker, NO container tag; the scheduler is disabled via Spring's own cron sentinel `-` (`ScheduledTaskRegistrar.CRON_DISABLED`), so only the test drives rounds. The scripted provider serves calls by index: round 1 costs two calls (tool-use `check_vitals`, then all-quiet text), round 2 costs two (tool-use `raise_alarm`, then alarm text), every later round costs one (all-quiet text). A sync `onToolFinished` listener on the test harness records tool activity.

```java
package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.ObservationRegistry;
import java.time.LocalTime;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.HarnessBuilder;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The time-triggered story, proved offline (spec §6): rounds land on ONE conversation, the alarm
 * path executes, and the recall window holds under the real loop. The scheduler itself is not
 * under test — cadence is Spring's own disabled-cron sentinel, and the test drives {@code round}
 * directly; {@code @Scheduled} needs no re-proving.
 */
@SpringBootTest(properties = {"watchman.cadence=-", "watchman.window=6"})
class NightWatchmanSmokeTest {

  /** Heard by the sync ToolFinished listener the test harness declares. */
  private static final List<ConversationEvent.ToolFinished> FINISHED = new CopyOnWriteArrayList<>();

  @Autowired private Watchman watchman;
  @Autowired private Agent<String> agent;

  @Test
  void the_clock_calls_and_one_bounded_conversation_answers() {
    // round 1: check_vitals then all-quiet — the round completes
    assertThat(watchman.round(LocalTime.of(2, 0)).state().status())
        .isEqualTo(ConversationStatus.COMPLETE);
    assertThat(FINISHED).isNotEmpty();
    assertThat(FINISHED).anyMatch(f -> f.call().name().equals("check_vitals"));

    // round 2: the alarm path executes
    assertThat(watchman.round(LocalTime.of(2, 1)).state().status())
        .isEqualTo(ConversationStatus.COMPLETE);
    assertThat(FINISHED).anyMatch(f -> f.call().name().equals("raise_alarm"));

    // continuity: both rounds live in the SAME conversation's recalled context
    Context afterTwo = agent.contextFor(watchman.conversationId());
    List<String> texts = afterTwo.messages().stream().map(NightWatchmanSmokeTest::textOf).toList();
    assertThat(texts).isNotEmpty();
    assertThat(texts).anyMatch(t -> t.contains("It is 02:00"));
    assertThat(texts).anyMatch(t -> t.contains("It is 02:01"));

    // the bound: run six more all-quiet rounds; recall stays inside the window of 6
    for (int minute = 2; minute < 8; minute++) {
      watchman.round(LocalTime.of(2, minute));
    }
    Context bounded = agent.contextFor(watchman.conversationId());
    assertThat(bounded.messages()).hasSize(6);
  }

  private static String textOf(Message message) {
    StringBuilder text = new StringBuilder();
    message.content().stream()
        .filter(TextBlock.class::isInstance)
        .map(TextBlock.class::cast)
        .forEach(block -> text.append(block.text()));
    return text.toString();
  }

  /**
   * A harness over the scripted provider, in-memory end to end; wins over the starter's own by
   * {@code @ConditionalOnMissingBean(Harness.class)}, which also keeps the real Anthropic
   * provider from ever being constructed — no key, no network. ObservationRegistry stays an
   * ObjectProvider: no actuator here, so no such bean is guaranteed.
   */
  @TestConfiguration
  static class WatchmanTestConfig {

    @Bean
    Harness harness(ObjectProvider<ObservationRegistry> observations) {
      HarnessBuilder builder =
          Nessy.harness(new ScriptedWatchProvider()).onToolFinished(FINISHED::add);
      observations.ifAvailable(builder::observations);
      return builder.build();
    }
  }

  /**
   * Serves calls by index: round one is a check_vitals exchange, round two a raise_alarm
   * exchange, every later call a plain all-quiet turn — the chat-web scripted two-turn pattern,
   * stretched across a night of rounds.
   */
  private static final class ScriptedWatchProvider implements ModelProvider {

    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      List<ModelEvent> turn =
          switch (calls.incrementAndGet()) {
            case 1 ->
                List.of(
                    new ModelEvent.ToolUseEmitted(
                        new ToolCall("c1", "check_vitals", noArguments())),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()));
            case 2 ->
                List.of(
                    new ModelEvent.TextChunk("All quiet; vitals inside their bands."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
            case 3 ->
                List.of(
                    new ModelEvent.ToolUseEmitted(
                        new ToolCall("c2", "raise_alarm", alarmArguments())),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()));
            case 4 ->
                List.of(
                    new ModelEvent.TextChunk("Alarm raised: the bilge is climbing."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
            default ->
                List.of(
                    new ModelEvent.TextChunk("All quiet."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
          };
      Iterator<ModelEvent> events = turn.iterator();
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return events;
        }

        @Override
        public void close() {
          // scripted stream holds no resources to release
        }
      };
    }

    private static JsonNode noArguments() {
      return JsonNodeFactory.instance.objectNode();
    }

    private static JsonNode alarmArguments() {
      ObjectNode arguments = JsonNodeFactory.instance.objectNode();
      arguments.put("severity", "high");
      arguments.put("reason", "bilge level climbing three rounds straight");
      return arguments;
    }
  }
}
```

On the final `hasSize(6)`: with this exact script the recalled tail is six alternating plain user/assistant messages (the cut lands on a genuine user turn), so the size is deterministic. If the implementation run shows `keepRecent`'s pair-safe boundary yielding a different-but-stable count, verify by hand that the count is what pair-safety requires for this message sequence, assert that exact number, and explain the arithmetic in a test comment — do NOT weaken to an inequality without doing that arithmetic.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q verify -pl nessy-examples/night-watchman -am`
Expected: COMPILE FAILURE — `Watchman` does not exist.

- [ ] **Step 3: Implement Watchman**

```java
package org.jwcarman.nessy.examples.watchman;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Conversation;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The rounds (spec §5): the clock is the caller. Every cron firing tells the SAME conversation
 * "do your rounds" — one continuous conversation is what lets the agent see trends across
 * firings, and {@link WindowedMemory} is what keeps that conversation from growing the model
 * call. Spring's default single-threaded scheduler serializes rounds: a slow round delays the
 * next rather than overlapping it. The package-private overload is the test's entry point — the
 * scheduler is only a trigger.
 */
@Component
public class Watchman {

  private static final Logger LOGGER = LoggerFactory.getLogger(Watchman.class);
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

  private final Conversation<String> conversation;

  public Watchman(Agent<String> agent) {
    this.conversation = agent.converse();
  }

  public ConversationId conversationId() {
    return conversation.conversationId();
  }

  @Scheduled(cron = "${watchman.cadence:0 * * * * *}")
  public void round() {
    // Explicit zone (S8688): the watchman reports the machine's own local time, so
    // ZoneId.systemDefault() names the zone the implicit no-arg now() would silently assume.
    round(LocalTime.now(ZoneId.systemDefault()));
  }

  RunOutcome round(LocalTime time) {
    String prompt = "It is " + CLOCK.format(time) + " — do your rounds.";
    LOGGER.info("round begins: {}", prompt);
    StringBuilder said = new StringBuilder();
    RunOutcome outcome =
        conversation.tell(
            prompt,
            event -> {
              switch (event) {
                case TurnEvent.TextDelta(String text) -> said.append(text);
                case TurnEvent.ToolCallRequested(ToolCall call) ->
                    LOGGER.info("tool: {}", call.name());
                // deliberate extender-tolerance default (chat-cli's discipline): the log ignores
                // variants it has no rendering for.
                default -> {}
              }
            });
    if (!said.isEmpty()) {
      LOGGER.info("watchman says: {}", said);
    }
    LOGGER.info("round ends: {}", outcome.state().status());
    return outcome;
  }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q verify -pl nessy-examples/night-watchman -am`
Expected: PASS — whole module suite green, still no Docker and no key.

- [ ] **Step 5: Verify the offline reactor stays green**

Run: `./mvnw -q clean verify`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Format and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-examples/night-watchman
git commit -m "feat: the clock calls and the watchman answers — one conversation, bounded"
```

---

### Task 6: The paperwork — README, examples matrix, CHANGELOG

**Files:**
- Create: `nessy-examples/night-watchman/README.md`
- Modify: `README.md` (root — the Examples section gains the third example)
- Modify: `CHANGELOG.md` (the generation's entry)

**Interfaces:**
- Consumes: everything above, as facts the docs must state truthfully.

- [ ] **Step 1: Write the module README**

Match the repo's README register (first person of the project, precise, no filler — read the root README's Examples section and `nessy-examples/chat-web/README.md` first). Content requirements:

- **What it demonstrates** (spec §1): the time-triggered agent pattern — the trigger event is the clock; wake → observe → judge → act or stay quiet; one continuous conversation across firings (trend judgment is conversation state at work); bounded recall via a custom `Memory`.
- **The story** (spec §2): the engine room, the three gauges, the biased bilge, the guaranteed arc in ~5–8 minutes at default cadence.
- **Run it:** `ANTHROPIC_API_KEY=… ./mvnw -q -pl nessy-examples/night-watchman -am spring-boot:run` — then watch the log. No Docker, no database, nothing else. Ctrl-C ends the watch; the conversation honestly dies with the JVM (in-memory is the point).
- **The two properties:** `watchman.cadence` (Spring cron, default each minute — show speeding it up: `-Dspring-boot.run.arguments=--watchman.cadence="*/15 * * * * *"`), `watchman.window` (default 40).
- **How the bound works:** `WindowedMemory` in ~ten lines — retention whole, recall `keepRecent(window)`, pair-safe by construction; the watchman's horizon is its window.
- **What it deliberately isn't** (spec §7): durable, web-faced, HITL, parking, really alerting.

- [ ] **Step 2: Update the root README's Examples section**

The section currently opens "`nessy-examples` is a family of two runnable apps…". Make it three, adding **`night-watchman`** after the `chat-web` entry in the existing entries' exact format: the time-triggered agent — `@Scheduled` cron initiates each turn of one continuous conversation; a windowing `Memory` keeps endless rounds from growing the model call; the leanest example (no web, no database, no Docker). Link `nessy-examples/night-watchman/README.md`, show the one-line run command. State the matrix once: chat-cli (plain + interactive), chat-web (Boot web + HITL), night-watchman (Boot + scheduled autonomy).

- [ ] **Step 3: Add the CHANGELOG entry**

Append one top-level bullet at the END of the `### Added` section (before `### Breaking (pre-1.0)`), matching the existing entries' voice, roughly 20–35 lines: **`nessy-example-night-watchman` — the clock is the caller.** Sub-bullets: (1) the pattern — `@Scheduled(cron)` initiates each turn of ONE continuous conversation; trend judgment is conversation state at work; (2) `WindowedMemory`, the first custom-`Memory` dogfood — freedom of retention, `keepRecent` at the border, the recall bound that lets a conversation run forever; (3) the leanest example: in-memory substrate from the starter's defaults, whole suite offline, no Docker anywhere. Also note in one line that the patient-researcher spec retired UNBUILT (branch archived) and the examples matrix now reads chat-cli / chat-web / night-watchman.

- [ ] **Step 4: Full offline verify**

Run: `./mvnw -q clean verify`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Format and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-examples/night-watchman README.md CHANGELOG.md
git commit -m "docs: the night watchman signs the paperwork — matrix of three, one clock"
```

---

## Self-Review Notes (already applied)

- Spec §6's three test surfaces map: `WindowedMemoryTest` (bound + pair-safety), `EngineRoomTest` (determinism + drift + clamps), `NightWatchmanSmokeTest` (rounds on one conversation, alarm path via the `onToolFinished` listener, window held under the loop, scheduler excluded via the `-` cron sentinel).
- `Message.assistant(List.of())` verified legal against `Message.java` (empty content accepted). The smoke test's window arithmetic hand-checked: after 2 tool rounds (8 messages) no pair-safe cut exists short of the whole context, so both rounds remain visible for the continuity assertion; after 8 rounds (20 messages) the cut lands on the round-6 user turn, so `hasSize(6)` is exact.
- `ObservationRegistry` is an `ObjectProvider` in the test harness bean (no actuator in this module) — the lesson the patient-researcher branch taught, applied from the start.
- The smoke test's `hasSize(6)` carries an explicit instruction to re-derive rather than weaken if the pair-safe boundary lands elsewhere.
- CHANGELOG is a plan task this time, not an afterthought.
