# Notebook Auto-Install Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A notebook on the classpath installs itself into every harness either factory makes, through a feature customizer, unless a property turns it off.

**Architecture:** `NotebookTools.feature(Notebook)` is a `Customizer<HarnessConfig<?>>` that installs the index ambient and the four tools, the way `DeclaredChapters.feature(...)` already installs a tool and a policy. The queued factory config gains the `feature(...)` tier the direct one already has, applied before the caller's own customizer. Both Boot auto-configurations collect every `Customizer<HarnessConfig<?>>` bean as a feature, and a new `NotebookAutoConfiguration` contributes one such bean when the notebook class, a `DataSource` and a `CodecFactory` are present and `nessy.notebook.enabled` is not false. chat-web then drops its hand wiring of the notebook.

**Tech Stack:** Java 25, Spring Boot 4.1 auto-configuration (`ApplicationContextRunner` tests on H2 for wiring; Testcontainers PostgreSQL for the engine), JUnit 5, AssertJ, no mocking library.

**Spec:** This plan is its own record. Decided in conversation with James on 2026-10-06: install at the factory level by default when the notebook is on the classpath, with a property to turn it off; notebook only, the plan store waits to see how this is liked.

## Global Constraints

- `./mvnw -q clean verify` must pass with no API key and no model-provider network access.
- Iterate with warm scoped builds: `./mvnw -q -pl :<artifactId> -am test`. The engine's queued tests are `@Tag("container")` and need `-Dnessy.excludedGroups=live`.
- Never run two Maven processes at once in this worktree. Check `pgrep -fl 'nessy-examp[l]e'` (bracketed so the pattern cannot match its own shell) before any `install`, and never rebuild while an example is running from `target/`.
- `./mvnw spotless:apply license:format` before every commit; every source file carries the Apache header.
- No `@SuppressWarnings`, no star imports.
- Exception-assertion lambdas hold exactly one call that can throw; assert non-emptiness before any all/none-match predicate.
- Auto-configurations are idiomatic and boring: `@Bean` plus `@ConditionalOnMissingBean`, `ObjectProvider` only for optional or plural collaborators, no hand-rolled defaults.
- No new public vocabulary beyond what this plan names: `feature` already exists on the direct door; `nessy.notebook.enabled` is the only new property.

## Review Focus

1. An application that declares its own `Notebook` bean and still wires `NotebookTools` tools by hand ends up with the tools twice (once from the feature, once from its own config) and the model sees duplicate names. Task 5's test pins that chat-web, after the change, offers `remember` exactly once.
2. `nessy.notebook.enabled=false` must leave no trace: no feature bean, no `nessy_note` DDL demanded of an application without the table. Task 4 pins the bean's absence; the DDL is only ever read through `Schemas.declared()` on the classpath, which this plan does not change.
3. A feature must run before the caller's customizer on the queued door, as it does on the direct door, so an application can still override what the feature installed. Task 2's engine test calls a feature that adds a tool and then asserts the caller's instructions came last.
4. A direct-door application without a `DataSource` must not have the notebook auto-configuration fail its context. Task 4 gates on `@ConditionalOnBean(DataSource.class)` and tests the absent case.
5. `JdbcNotebook` is keyed by `AgentType`; a feature runs once per harness, so one bean must produce one notebook per type, not one shared notebook. Task 4's wiring test creates two agent types and asserts each harness got a notebook keyed to its own type by writing a note through one and checking the other's index does not list it.

---

### Task 1: `NotebookTools.feature(Notebook)`

**Files:**
- Modify: `nessy-memory/notebook/pom.xml` (add `org.slf4j:slf4j-api`, version managed by the parent, as `nessy-engine/pom.xml` does)
- Modify: `nessy-memory/notebook/src/main/java/org/jwcarman/nessy/memory/notebook/NotebookTools.java`
- Test: `nessy-memory/notebook/src/test/java/org/jwcarman/nessy/memory/notebook/NotebookToolsTest.java`

**Interfaces:**
- Consumes: `org.jwcarman.nessy.api.Customizer`, `org.jwcarman.nessy.api.HarnessConfig` (already dependencies of this module via `nessy-api`).
- Produces: `public static Customizer<HarnessConfig<?>> feature(Notebook notebook)` which calls `config.ambient(index(notebook)).tool(remember(notebook)).tool(revise(notebook)).tool(recall(notebook)).tool(forget(notebook))` and logs one INFO line naming the agent type it equipped. James, 2026-10-06: a tool that appears in an agent with nothing saying so is a mystery in the logs; the feature says what it installed and into which type, which is the parked customizer-logging idea applied to its first real feature.

- [ ] **Step 1: Write the failing test**

Add a nested class to `NotebookToolsTest`, after `Recalling`:

```java
  @Nested
  class Installing {

    /**
     * A recording {@link HarnessConfig}: the feature is a customizer, and what a customizer does
     * is call methods on a config. Recording which were called is the whole assertion.
     */
    private static final class Recording implements HarnessConfig<Recording> {
      private final List<Tool<?>> tools = new ArrayList<>();
      private final List<AmbientSource> ambients = new ArrayList<>();

      @Override
      public AgentType agentType() {
        return Calls.TYPE;
      }

      @Override
      public Recording turnPolicy(TurnPolicy policy) {
        return this;
      }

      @Override
      public <T> Recording tool(Tool<T> tool) {
        tools.add(tool);
        return this;
      }

      @Override
      public Recording instructions(String text) {
        return this;
      }

      @Override
      public Recording memory(MemorySource source) {
        return this;
      }

      @Override
      public Recording state(StateSource source) {
        return this;
      }

      @Override
      public Recording ambient(AmbientSource source) {
        ambients.add(source);
        return this;
      }

      @Override
      public Recording chapterPolicy(ChapterPolicy policy) {
        return this;
      }

      @Override
      public Recording summarizer(Summarizer summarizer) {
        return this;
      }
    }

    @Test
    @DisplayName("one call equips an agent with the index and the four tools")
    void the_feature_installs_the_index_and_the_four_tools() {
      Recording config = new Recording();

      NotebookTools.feature(notebook).customize(config);

      assertThat(config.ambients).hasSize(1);
      assertThat(config.tools).isNotEmpty();
      assertThat(config.tools)
          .extracting(tool -> tool.name().value())
          .containsExactly("remember", "revise", "recall", "forget");
    }
  }
```

Imports to add: `java.util.ArrayList`, `java.util.List`, `org.jwcarman.nessy.api.AgentType`, `org.jwcarman.nessy.api.AmbientSource`, `org.jwcarman.nessy.api.ChapterPolicy`, `org.jwcarman.nessy.api.HarnessConfig`, `org.jwcarman.nessy.api.MemorySource`, `org.jwcarman.nessy.api.StateSource`, `org.jwcarman.nessy.api.Summarizer`, `org.jwcarman.nessy.api.TurnPolicy`, `org.jwcarman.nessy.api.tool.Tool`. Check each against `HarnessConfig`'s own imports; the method set above is `HarnessConfig` as of 2026-10-06 (agentType, turnPolicy, tool, instructions, memory, state, ambient, chapterPolicy, summarizer). If the interface has gained a method, implement it as a no-op returning `this`.

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -pl :nessy-memory-notebook test -Dtest=NotebookToolsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: method feature(Notebook)`.

- [ ] **Step 3: Implement**

In `NotebookTools`, after `index(...)`:

```java
  /**
   * Installs the notebook on an agent: the index as ambient background, and the four tools.
   *
   * <p>One call equips a harness, which is what a factory-level feature needs: the chapters
   * feature is the precedent. The notebook passed in is the one this agent type keeps, so a caller
   * installing it for several types builds one per type and calls this for each.
   */
  public static Customizer<HarnessConfig<?>> feature(Notebook notebook) {
    Objects.requireNonNull(notebook, NOTEBOOK_NOT_NULL);
    return config -> {
      config
          .ambient(index(notebook))
          .tool(remember(notebook))
          .tool(revise(notebook))
          .tool(recall(notebook))
          .tool(forget(notebook));
      // Says so, because a tool nobody wired by hand is otherwise a mystery in the logs.
      log.info(
          "NESSY NOTEBOOK: agent type '{}' keeps notes (remember, revise, recall, forget)",
          config.agentType().value());
    };
  }
```

With `private static final Logger log = LoggerFactory.getLogger(NotebookTools.class);` and imports `org.jwcarman.nessy.api.Customizer`, `org.jwcarman.nessy.api.HarnessConfig`, `org.slf4j.Logger`, `org.slf4j.LoggerFactory`. The `NESSY NOTEBOOK:` prefix follows `NESSY INFERENCE:` and `NESSY EMBEDDING:` in the starter's reports. The logging is not asserted by a test: the test pins what was installed, and a log line is read by a person.

- [ ] **Step 4: Run the module's tests**

Run: `./mvnw -q -pl :nessy-memory-notebook spotless:apply && ./mvnw -q -pl :nessy-memory-notebook test`
Expected: exit 0.

- [ ] **Step 5: Commit**

```bash
git add nessy-memory/notebook
git commit -m "feat(notebook): NotebookTools.feature installs the index and the four tools in one call"
```

---

### Task 2: A feature tier on the queued factory

**Files:**
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/harness/queued/QueuedHarnessFactoryConfig.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/harness/queued/DefaultQueuedHarnessFactory.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/queued/QueuedHarnessFeaturesTest.java` (new)

**Interfaces:**
- Consumes: `DefaultQueuedHarnessConfig<I> implements QueuedHarnessConfig<I>`, and `QueuedHarnessConfig<I> extends HarnessConfig<...>` so a `Customizer<HarnessConfig<?>>` can be applied to it exactly as `DefaultDirectHarnessFactory.build` applies features to a `DefaultDirectHarnessConfig`.
- Produces: `public QueuedHarnessFactoryConfig feature(Customizer<HarnessConfig<?>> customizer)` and package-private `List<Customizer<HarnessConfig<?>>> features()`; `DefaultQueuedHarnessFactory.create` applies every feature before the caller's customizer.

- [ ] **Step 1: Write the failing test**

Create `QueuedHarnessFeaturesTest` beside `HarnessLoopTest`, using the same `EngineFixture` and `RecordingModel`:

```java
/*
 * (Apache header, as every file in this repository carries.)
 */
package org.jwcarman.nessy.engine.harness.queued;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.engine.EngineFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A feature equips every harness the queued factory makes, before the caller has a say.
 *
 * <p>The direct door has had this tier since the factory split; the queued door gets it so a jar
 * that installs itself (the notebook is the first) lands on both doors through one customizer.
 */
@Tag("container")
class QueuedHarnessFeaturesTest {
  private static final AgentType CHAT = new AgentType("chat");
  private final RecordingModel model = new RecordingModel();
  private EngineFixture engine;

  @AfterEach
  void stopEngine() {
    if (engine != null) {
      engine.close();
    }
  }

  @Test
  void aFeatureInstallsItsToolOnEveryHarnessAndTheCallerStillHasTheLastWord() {
    Customizer<HarnessConfig<?>> feature =
        config -> config.tool(HarnessLoopTest.probeTool()).instructions("from the feature");
    engine = new EngineFixture(model, factory -> factory.feature(feature));
    QueuedHarness<String> harness =
        engine
            .harnesses()
            .create(
                CHAT,
                String.class,
                config ->
                    config
                        .systemPrompt("You are a test assistant.")
                        .instructions("from the caller")
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(100))));
    AgentId agentId = new AgentId(UUID.randomUUID());

    harness.tell(agentId, "hello");

    await().atMost(Duration.ofSeconds(10)).until(() -> !model.requests().isEmpty());
    var request = model.requests().getFirst();
    assertThat(request.tools()).extracting(t -> t.name().value()).contains("probe");
    assertThat(request.systemPrompt())
        .as("the feature's instruction comes before the caller's, so the caller overrides")
        .containsSubsequence(List.of("from the feature", "from the caller"));
  }
}
```

Before writing this, read `HarnessLoopTest` and `RecordingModel` in the same package and `EngineFixture` in `org.jwcarman.nessy.engine` and adapt three names to what exists: the accessor that returns recorded requests on `RecordingModel`, the accessor for offered tools and the rendered system prompt on the recorded request, and the `EngineFixture` constructor. `EngineFixture` has constructors taking a provider plus a listener or a storage codec; if none takes a `Customizer<QueuedHarnessFactoryConfig>`, add one in this task (it is test code) that applies the customizer after the fixture's own settings, the same way the fixture's existing constructor builds the factory with `DefaultQueuedHarnessFactory.of(engine -> ...)`. If `HarnessLoopTest` has no `probeTool()`, define the tool inside this test: a `Tool<Query>` with `inputType()` returning `Query.class`, `name()` returning `new ToolName("probe")`, a one-line description, and `call` answering `Awaited.ready(ToolResult.said("probed"))`, with `record Query(String q) {}` nested in the test. Use whatever `ToolResult` factory `NotebookTools` uses for a text success.

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -pl :nessy-engine -am test -Dtest=QueuedHarnessFeaturesTest -Dsurefire.failIfNoSpecifiedTests=false -Dnessy.excludedGroups=live`
Expected: compilation failure, `cannot find symbol: method feature(...)` on `QueuedHarnessFactoryConfig`.

- [ ] **Step 3: Implement the tier**

In `QueuedHarnessFactoryConfig`, beside `listeners`:

```java
  private final List<Customizer<HarnessConfig<?>>> features = new ArrayList<>();
```

After `listener(...)`:

```java
  /**
   * Something that equips every agent this factory serves.
   *
   * <p>For a module rather than an application: it reaches {@link HarnessConfig}, so it can add
   * tools, instructions, memory, state and ambient sources, and a chapter policy or summariser, and
   * it reads the agent type to key whatever it keeps on. It cannot set the application's own system
   * prompt or its renderer, because the application already said what the agent is FOR. The same
   * tier the direct door has, so one feature lands on both doors.
   */
  public QueuedHarnessFactoryConfig feature(Customizer<HarnessConfig<?>> customizer) {
    features.add(Objects.requireNonNull(customizer, "customizer must not be null"));
    return this;
  }
```

In the "what the factory reads" section:

```java
  List<Customizer<HarnessConfig<?>>> features() {
    return List.copyOf(features);
  }
```

In `DefaultQueuedHarnessFactory`, a field `private final List<Customizer<HarnessConfig<?>>> features;` set in the constructor from `config.features()`, and in `create(...)` replace the single line `customizer.customize(config);` with:

```java
    // What jars installed, then what this caller asked for -- the caller able to override.
    features.forEach(feature -> feature.customize(config));
    customizer.customize(config);
```

Imports in both files: `org.jwcarman.nessy.api.Customizer` (already present in the factory), `org.jwcarman.nessy.api.HarnessConfig`.

- [ ] **Step 4: Run the engine's queued tests**

Run: `./mvnw -q -pl :nessy-engine -am test -Dnessy.excludedGroups=live`
Expected: exit 0. The container tests need Docker.

- [ ] **Step 5: Commit**

```bash
git add nessy-engine
git commit -m "feat(engine): the queued factory has the feature tier the direct one has"
```

---

### Task 3: Both auto-configurations collect feature beans

**Files:**
- Modify: `nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/DirectHarnessAutoConfiguration.java:86-105`
- Modify: `nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/QueuedHarnessAutoConfiguration.java`
- Test: `nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/HarnessFeaturesWiringTest.java` (new)

**Interfaces:**
- Consumes: `DirectHarnessFactoryConfig.feature(...)` and the new `QueuedHarnessFactoryConfig.feature(...)`.
- Produces: every bean of type `Customizer<HarnessConfig<?>>` is registered as a feature on whichever factories the context builds.

- [ ] **Step 1: Write the failing test**

Model it on `AgentWorkAutoConfigurationTest`'s runner (same `AutoConfigurations.of(...)` list and the same `AModel` user configuration and property values). The test registers one feature bean that records the agent types it was asked to equip, creates one harness on each door, and asserts the feature saw both:

```java
  @Configuration(proxyBeanMethods = false)
  static class AFeature {
    static final List<AgentType> equipped = new CopyOnWriteArrayList<>();

    @Bean
    Customizer<HarnessConfig<?>> recordingFeature() {
      return config -> equipped.add(config.agentType());
    }
  }

  @Test
  void aFeatureBeanEquipsHarnessesOnBothDoors() {
    AFeature.equipped.clear();
    runner
        .withUserConfiguration(AFeature.class)
        .run(
            context -> {
              context
                  .getBean(DirectHarnessFactory.class)
                  .create(
                      new AgentType("direct-one"),
                      String.class,
                      c -> c.systemPrompt("x").inference(in -> in.model("a-test-model")));
              context
                  .getBean(QueuedHarnessFactory.class)
                  .create(
                      new AgentType("queued-one"),
                      String.class,
                      c -> c.systemPrompt("x").inference(in -> in.model("a-test-model")));
              assertThat(AFeature.equipped)
                  .containsExactlyInAnyOrder(
                      new AgentType("direct-one"), new AgentType("queued-one"));
            });
  }
```

Read `AgentWorkAutoConfigurationTest` first for the exact `create` signatures it uses on each factory and the property values its runner sets; copy those, do not guess. The generic bean type `Customizer<HarnessConfig<?>>` resolves through `ObjectProvider` with `ResolvableType`; Spring matches the generic parameter from the `@Bean` method's return type, so declare the method's return type exactly as above.

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test -Dtest=HarnessFeaturesWiringTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, `equipped` is empty on both doors.

- [ ] **Step 3: Implement**

In both factory `@Bean` methods, add a parameter `ObjectProvider<Customizer<HarnessConfig<?>>> features` and, inside the starter's own customizer (the first element of `all`), after the provider/model block:

```java
          // Every feature bean -- a jar that equips every agent -- lands on this door too.
          features.orderedStream().forEach(config::feature);
```

Use `engine::feature` in the queued one, matching its lambda's parameter name. Import `org.jwcarman.nessy.api.HarnessConfig` in both.

- [ ] **Step 4: Run the autoconfigure tests**

Run: `./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test`
Expected: exit 0.

- [ ] **Step 5: Commit**

```bash
git add nessy-spring-boot/autoconfigure
git commit -m "feat(boot): every Customizer<HarnessConfig<?>> bean is a feature on both doors"
```

---

### Task 4: `NotebookAutoConfiguration` with `nessy.notebook.enabled`

**Files:**
- Modify: `nessy-spring-boot/autoconfigure/pom.xml` (add `nessy-memory-notebook` as `<optional>true</optional>`, beside `nessy-prompt-mustache`)
- Create: `nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/notebook/NotebookAutoConfiguration.java`
- Create: `nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/notebook/package-info.java`
- Modify: `nessy-spring-boot/autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (add the class after `NessyAutoConfiguration`)
- Modify: `nessy-spring-boot/starter/pom.xml` (add `nessy-memory-notebook` as a plain dependency, so the starter carries it)
- Test: `nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/notebook/NotebookAutoConfigurationTest.java` (new)

**Interfaces:**
- Consumes: `NotebookTools.feature(Notebook)`, `JdbcNotebook(DataSource, AgentType, CodecFactory)`, the `CodecFactory` bean from `NessyAutoConfiguration`.
- Produces: a bean `nessyNotebookFeature` of type `Customizer<HarnessConfig<?>>` that builds one `JdbcNotebook` per agent type on first use and installs the notebook on that harness.

- [ ] **Step 1: Write the failing test**

```java
/**
 * The notebook on the classpath installs itself, unless told not to.
 *
 * <p>H2 and no tables, like every wiring test here: the assertions are about which beans exist and
 * what the feature does to a harness config, never about a note reaching a database.
 */
class NotebookAutoConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  DataSourceAutoConfiguration.class,
                  JacksonAutoConfiguration.class,
                  NessyAutoConfiguration.class,
                  NotebookAutoConfiguration.class))
          .withPropertyValues(
              "nessy.initialize-schema=false",
              "spring.datasource.url=jdbc:h2:mem:notebook;DB_CLOSE_DELAY=-1");

  @Test
  void theNotebookFeatureIsThereByDefault() {
    runner.run(context -> assertThat(context).hasBean("nessyNotebookFeature"));
  }

  @Test
  void thePropertyTurnsItOff() {
    runner
        .withPropertyValues("nessy.notebook.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean("nessyNotebookFeature"));
  }

  @Test
  void withoutADataSourceThereIsNoFeatureAndNoFailure() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                NessyAutoConfiguration.class,
                NotebookAutoConfiguration.class))
        .withPropertyValues("nessy.initialize-schema=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean("nessyNotebookFeature");
            });
  }

  @Test
  void theFeatureInstallsTheIndexAndFourToolsKeyedToTheHarnessesOwnType() {
    runner.run(
        context -> {
          @SuppressWarnings("unchecked") // never: resolve by ResolvableType instead, see below
          Customizer<HarnessConfig<?>> feature = null;
          ...
        });
  }
}
```

Do not write the fourth test as sketched above; `@SuppressWarnings` is forbidden. Resolve the bean without a cast:

```java
  private static Customizer<HarnessConfig<?>> feature(AssertableApplicationContext context) {
    ResolvableType type =
        ResolvableType.forClassWithGenerics(
            Customizer.class, ResolvableType.forClassWithGenerics(HarnessConfig.class, Object.class));
    ObjectProvider<Customizer<HarnessConfig<?>>> provider = context.getBeanProvider(type);
    return provider.getObject();
  }
```

`ResolvableType.forClassWithGenerics(HarnessConfig.class, Object.class)` is how an unbounded wildcard is spelled for `getBeanProvider`; if the provider comes back empty, replace it with `ResolvableType.forClassWithGenerics(HarnessConfig.class, ResolvableType.NONE)` and keep whichever one resolves (verify, then delete the other). Then the fourth test records what the feature installs with a `Recording` config exactly like Task 1's, with `agentType()` returning a type the test chooses, and asserts the four tool names and one ambient. Pin Review Focus item 5 by applying the feature to two recordings with different types and asserting the two `Notebook` instances behind them differ: the `JdbcNotebook` the feature made for type A, written to through its `remember` tool, must not be listed by the index the feature gave type B. On H2 with no tables that write fails, so instead assert at the feature's seam: expose nothing new; use the Testcontainers PostgreSQL the engine tests use only if the autoconfigure module already has a Postgres-backed test to copy from (`JdbcStorageTransformWiringTest` is one; read it). If it does, write the note through type A's tool and read type B's index text; it must equal `"There are no notes in this notebook."`.

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test -Dtest=NotebookAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `NotebookAutoConfiguration` does not exist.

- [ ] **Step 3: Implement**

`pom.xml` of autoconfigure, in the dependencies beside `nessy-prompt-mustache`:

```xml
    <dependency>
      <groupId>org.jwcarman.nessy</groupId>
      <artifactId>nessy-memory-notebook</artifactId>
      <version>${project.version}</version>
      <optional>true</optional>
    </dependency>
```

`pom.xml` of the starter, a plain (non-optional) dependency on `nessy-memory-notebook` with `${project.version}`, so adding the starter is what puts the notebook on the classpath.

`NotebookAutoConfiguration`:

```java
package org.jwcarman.nessy.spring.boot.notebook;

/**
 * The notebook, installed on every agent of both doors when it is on the classpath.
 *
 * <p>Adding the starter adds the notebook; an application that does not want its agents to keep
 * notes says {@code nessy.notebook.enabled=false}. One that wires the notebook by hand should say
 * the same, or its agents hold every tool twice.
 *
 * <p>One notebook per agent type: {@link JdbcNotebook} is keyed by type, and a feature runs once
 * per harness, so the feature builds the notebook for the type it is equipping. The object is a
 * handle on a data source and a codec, cheap to make and safe to make again.
 */
@AutoConfiguration(after = NessyAutoConfiguration.class)
@ConditionalOnClass(JdbcNotebook.class)
@ConditionalOnBean({DataSource.class, CodecFactory.class})
@ConditionalOnProperty(name = "nessy.notebook.enabled", havingValue = "true", matchIfMissing = true)
public class NotebookAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(name = "nessyNotebookFeature")
  public Customizer<HarnessConfig<?>> nessyNotebookFeature(DataSource dataSource, CodecFactory codecs) {
    return config ->
        NotebookTools.feature(new JdbcNotebook(dataSource, config.agentType(), codecs))
            .customize(config);
  }
}
```

`@ConditionalOnBean` on a `DataSource` across auto-configurations needs ordering: add `DataSourceAutoConfiguration.class` to `after` if the condition evaluates before the data source exists (the `withoutADataSource` test and the default test together tell you). Add `package-info.java` with the Apache header and a one-line package Javadoc. Register the class in `AutoConfiguration.imports`.

- [ ] **Step 4: Run the autoconfigure tests**

Run: `./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test`
Expected: exit 0.

- [ ] **Step 5: Commit**

```bash
git add nessy-spring-boot
git commit -m "feat(boot): the notebook installs itself on every agent; nessy.notebook.enabled turns it off"
```

---

### Task 5: chat-web stops wiring the notebook by hand

**Files:**
- Modify: `nessy-examples/chat-web/src/main/java/org/jwcarman/nessy/examples/chatweb/ChatConfiguration.java:67-70,105,137,140-143`
- Modify: `nessy-examples/chat-web/src/test/java/org/jwcarman/nessy/examples/chatweb/StorageCodecWiringTest.java:84-110`
- Modify: `nessy-examples/chat-web/README.md` (the "What it shows" paragraph that lists the notebook among the tools; say the notebook comes from the starter and name the property)
- Test: `nessy-examples/chat-web/src/test/java/org/jwcarman/nessy/examples/chatweb/ChatTurnsIntegrationTest.java` (add one assertion)

**Interfaces:**
- Consumes: the starter's `nessyNotebookFeature`.
- Produces: nothing new; `ChatConfiguration` no longer declares a `Notebook` bean nor the four tools nor the index ambient.

- [ ] **Step 1: Write the failing test**

In `ChatTurnsIntegrationTest`, which drives real turns through a scripted provider against PostgreSQL, add a test that reads the first recorded request's offered tool names and asserts `remember` appears exactly once (Review Focus 1). Read `ScriptedProvider` for the accessor that returns recorded requests; the assertion is:

```java
  @Test
  void theNotebookToolsAreOfferedOnceEach() {
    // (send one message the way the neighbouring tests do, await the first request)
    List<String> offered = provider.requests().getFirst().tools().stream()
        .map(t -> t.name().value()).toList();
    assertThat(offered).isNotEmpty();
    assertThat(offered).containsOnlyOnce("remember", "revise", "recall", "forget");
  }
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -pl :nessy-example-chat-web -am test -Dtest=ChatTurnsIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dnessy.excludedGroups=live`
Expected: FAIL, each notebook tool offered twice (hand wiring plus the feature), or a startup failure from duplicate tool names if the engine rejects them. Either is the red.

- [ ] **Step 3: Implement**

In `ChatConfiguration`: delete the `notebook(...)` bean, the `Notebook notebook` parameter of `harness(...)`, the `.ambient(NotebookTools.index(notebook))` line, and the four `.tool(NotebookTools.*(notebook))` lines; remove the now-unused imports (`JdbcNotebook`, `Notebook`, `NotebookTools`). Update the class Javadoc's first line so it no longer lists the notebook as something this class wires. In `StorageCodecWiringTest`, the `@Autowired Notebook` is gone: build the notebook in the test from the autowired `DataSource` and `CodecFactory` as `new JdbcNotebook(dataSource, ChatConfiguration.TYPE, codecs)`, which is what the feature does, and keep its assertion. In the README's run section, after the table of variables, one line: the notebook comes with the starter and `nessy.notebook.enabled=false` turns it off.

- [ ] **Step 4: Run chat-web's tests**

Run: `./mvnw -q -pl :nessy-example-chat-web -am test -Dnessy.excludedGroups=live`
Expected: exit 0.

- [ ] **Step 5: The final gate, then commit**

Run: `pgrep -fl 'nessy-examp[l]e' | grep -q . && echo "an example is running; stop it first" || ./mvnw -q clean verify -Dnessy.excludedGroups=live`
Expected: exit 0.

```bash
./mvnw -q spotless:apply license:format
git add nessy-examples/chat-web
git commit -m "chat-web: the notebook comes from the starter"
```

Then run chat-web the usual way and confirm the startup log shows the notebook's tools offered and a fresh conversation can remember and recall a note.
