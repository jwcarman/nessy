# Plan Store Auto-Install Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The plan store on the classpath installs itself into every harness either factory makes, through a feature customizer, unless a property turns it off, exactly as the notebook does since `docs/superpowers/plans/2026-10-06-notebook-auto-install.md` landed at 8f828142f.

**Architecture:** `PlanTools.feature(Plans)` is a `Customizer<HarnessConfig<?>>` that installs the plan ambient and the `update_plan` tool and logs the agent type. A new `PlanAutoConfiguration` in the autoconfigure module contributes one such bean, `nessyPlanFeature`, one `JdbcPlans` per agent type, under the same conditions as `NotebookAutoConfiguration`: the planning class and the JDBC backend on the classpath, a `DataSource` and a `CodecFactory`, `nessy.plan.enabled` not false, no application-declared `Plans` bean, after the schema. The feature tier, the feature-bean collection in both auto-configurations and the starter's dependency pattern already exist; this plan adds no engine code. chat-web and chat-cli drop their hand wiring of the plan store, which leaves neither example wiring any memory by hand.

**Tech Stack:** Java 25, Spring Boot 4.1 auto-configuration (`ApplicationContextRunner` on H2 for wiring; Testcontainers PostgreSQL for the per-type test), JUnit 5, AssertJ, no mocking library.

**Spec:** James, 2026-10-06: "let's try it out on notebook first to see how we like it", then, after the notebook merged, "go do plan now. same recipe". The notebook plan and its final-review findings are the recipe; every finding that review made against the notebook is built in here from the start (JDBC-backend gate, `NessySchema` dependency, back-off on the application's own bean, `DataSourceAutoConfiguration` ordering pinned by a test, chat-cli included, docs and changelog in the same branch).

## Global Constraints

- `./mvnw -q clean verify` must pass with no API key and no model-provider network access. The branch's final gate is `./mvnw -q clean verify -Dnessy.excludedGroups=live`, which includes the Testcontainers tests.
- Iterate with warm scoped builds: `./mvnw -q -pl :<artifactId> -am test`. Postgres-backed tests are `@Tag("container")` and need `-Dnessy.excludedGroups=live`.
- Never run two Maven processes at once in one worktree. Check `pgrep -fl 'nessy-examp[l]e'` before any `install` and stop if it prints anything.
- `./mvnw spotless:apply license:format` before every commit; every source file carries the Apache header; a new package has a `package-info.java`.
- No `@SuppressWarnings`, no star imports.
- Exception-assertion lambdas hold exactly one call that can throw; assert non-emptiness before any all/none-match predicate.
- Auto-configurations idiomatic and boring: `@Bean` plus `@ConditionalOnMissingBean`, `ObjectProvider` only for optional or plural collaborators.
- Tests read as prose: snake_case sentence method names, `@Nested` groups as capitalized phrases.
- Docs describe what is: no history, no "previously", no roads not taken.
- Names, by analogy with the notebook and nothing else new: `PlanTools.feature(Plans)`, `PlanAutoConfiguration` in package `org.jwcarman.nessy.spring.boot.plan`, bean `nessyPlanFeature`, property `nessy.plan.enabled` (default true), log prefix `NESSY PLAN:`.

## Review Focus

1. An application that keeps wiring the plan store by hand gets a harness that refuses to build ("two ambient sources offer the kind 'plan'"), unless it declares its own `Plans` bean, which makes the starter back off. Task 2 pins the back-off; Task 3's red is the refusal in chat-web and chat-cli, and Task 3 removes both hand wirings.
2. A `DataSource` without `nessy-backend-jdbc` must not get the feature (nothing would create `nessy_plan_task`). Task 2 pins it with the in-memory backend and a filtered class loader, as `NotebookWithoutTheJdbcBackendTest` does.
3. A direct-door application without a `DataSource` must not fail its context. Task 2 pins it.
4. One `JdbcPlans` per agent type, not one shared store. Task 2's Postgres test writes a plan through type A's tool and reads type B's ambient, which must be absent (an empty plan contributes nothing, by `PlanTools.plan`'s own filter).
5. `after = DataSourceAutoConfiguration` is necessary for applications whose `DataSource` Boot makes; Task 2 pins it the way `a_data_source_boot_makes_is_enough` does for the notebook.

---

### Task 1: `PlanTools.feature(Plans)`

**Files:**
- Modify: `nessy-planning/pom.xml` (add `org.slf4j:slf4j-api`, version managed by the parent, as `nessy-memory/notebook/pom.xml` has it)
- Modify: `nessy-planning/src/main/java/org/jwcarman/nessy/planning/PlanTools.java`
- Test: `nessy-planning/src/test/java/org/jwcarman/nessy/planning/PlanToolsTest.java`

**Interfaces:**
- Consumes: `org.jwcarman.nessy.api.Customizer`, `org.jwcarman.nessy.api.HarnessConfig`, the existing `PlanTools.plan(Plans)` and `PlanTools.updatePlan(Plans)`.
- Produces: `public static Customizer<HarnessConfig<?>> feature(Plans store)` which calls `config.ambient(plan(store)).tool(updatePlan(store))` and logs one INFO line `NESSY PLAN: agent type '{}' keeps a plan (update_plan)` with the agent type.

- [ ] **Step 1: Write the failing test**

Read `nessy-memory/notebook/src/test/java/org/jwcarman/nessy/memory/notebook/NotebookToolsTest.java`'s nested `Installing` class and its `Recording implements HarnessConfig<Recording>` fake, and `PlanToolsTest` for how it builds a `Plans` store (its `Calls` helper). Add to `PlanToolsTest` a nested class with the same fake (copy it; `agentType()` returns `Calls.TYPE`):

```java
  @Nested
  class Installing {

    @Test
    @DisplayName("one call equips an agent with the plan ambient and the update_plan tool")
    void the_feature_installs_the_ambient_and_the_tool() {
      Recording config = new Recording();

      PlanTools.feature(store).customize(config);

      assertThat(config.ambients).hasSize(1);
      assertThat(config.tools).isNotEmpty();
      assertThat(config.tools)
          .extracting(tool -> tool.name().value())
          .containsExactly("update_plan");
    }
  }
```

where `store` is whatever `PlanToolsTest` already names its `Plans` instance. Implement every `HarnessConfig` method on the fake as of the current interface; only `tool` and `ambient` record.

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -pl :nessy-planning test -Dnessy.excludedGroups=live -Dtest=PlanToolsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: method feature(Plans)`.

- [ ] **Step 3: Implement**

In `PlanTools`, after `plan(...)`:

```java
  /**
   * Installs the plan on an agent: the current plan as ambient background, and the one tool that
   * changes it.
   *
   * <p>One call equips a harness, which is what a factory-level feature needs; the notebook's
   * feature is the precedent. The store passed in is keyed by this agent's type, so a caller
   * installing it for several types builds one per type and calls this for each.
   */
  public static Customizer<HarnessConfig<?>> feature(Plans store) {
    Objects.requireNonNull(store, "store must not be null");
    return config -> {
      config.ambient(plan(store)).tool(updatePlan(store));
      // Says so, because a tool nobody wired by hand is otherwise a mystery in the logs.
      log.info("NESSY PLAN: agent type '{}' keeps a plan (update_plan)", config.agentType().value());
    };
  }
```

With `private static final Logger log = LoggerFactory.getLogger(PlanTools.class);` and imports `org.jwcarman.nessy.api.Customizer`, `org.jwcarman.nessy.api.HarnessConfig`, `org.slf4j.Logger`, `org.slf4j.LoggerFactory`. The log line is not test-asserted.

- [ ] **Step 4: Run the module's tests**

Run: `./mvnw -q -pl :nessy-planning spotless:apply && ./mvnw -q -pl :nessy-planning test -Dnessy.excludedGroups=live`
Expected: exit 0.

- [ ] **Step 5: Commit**

```bash
git add nessy-planning
git commit -m "feat(planning): PlanTools.feature installs the plan ambient and update_plan in one call"
```

---

### Task 2: `PlanAutoConfiguration` with `nessy.plan.enabled`

**Files:**
- Modify: `nessy-spring-boot/autoconfigure/pom.xml` (add `nessy-planning` as `<optional>true</optional>`, beside `nessy-memory-notebook`)
- Modify: `nessy-spring-boot/starter/pom.xml` (add `nessy-planning` as a plain dependency, beside `nessy-memory-notebook`)
- Create: `nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/plan/PlanAutoConfiguration.java`
- Create: `nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/plan/package-info.java`
- Modify: `nessy-spring-boot/autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (add the class after the notebook's)
- Test: `nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/plan/PlanAutoConfigurationTest.java` (new, H2)
- Test: `nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/plan/PlanWithoutTheJdbcBackendTest.java` (new, H2 + in-memory backend)
- Test: `nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/plan/PlanPerTypeTest.java` (new, Postgres, `@Tag("container")`)
- Test helper: `nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/plan/PlanFeatures.java` (new; the plan-flavoured twin of `notebook/NotebookFeatures.java`)

**Interfaces:**
- Consumes: `PlanTools.feature(Plans)`, `JdbcPlans(DataSource, AgentType, CodecFactory)`, `NessySchema` from `JdbcBackendAutoConfiguration`, the `CodecFactory` bean.
- Produces: bean `nessyPlanFeature` of type `Customizer<HarnessConfig<?>>`.

- [ ] **Step 1: Write the failing tests**

The notebook's four test files in `src/test/java/org/jwcarman/nessy/spring/boot/notebook/` are the exact templates; read all four first. Write the plan twins with the same runners (same `AutoConfigurations.of(...)` lists, the same `ADatabase` embedded-database user configuration, the same transaction-manager bean, the same property values), substituting: `PlanAutoConfiguration`, `nessyPlanFeature`, `nessy.plan.enabled`, a user configuration `ItsOwnPlans` declaring any `Plans` bean, `JdbcPlans`, `PlanTools`, and the tool name `update_plan`. Tests, prose names:

`PlanAutoConfigurationTest`:
- `the_plan_feature_is_there_by_default` → `hasBean("nessyPlanFeature")`
- `the_property_turns_it_off` (`nessy.plan.enabled=false`) → `doesNotHaveBean`
- `without_a_data_source_there_is_no_feature_and_no_failure` → `hasNotFailed()` and `doesNotHaveBean`
- `an_application_with_its_own_plans_bean_gets_no_feature` → `doesNotHaveBean`
- `a_data_source_boot_makes_is_enough` (`spring.datasource.type=org.springframework.jdbc.datasource.SimpleDriverDataSource` with `DataSourceAutoConfiguration`) → `hasBean`
- `the_feature_installs_the_ambient_and_the_tool`: resolve the bean through `PlanFeatures.feature(context)` (the `ParameterizedTypeReference` lookup copied from `NotebookFeatures`), apply it to a `Recording` config, assert one ambient and exactly the tool `update_plan`.

`PlanWithoutTheJdbcBackendTest`: copy `NotebookWithoutTheJdbcBackendTest` (in-memory backend, H2, `FilteredClassLoader(JdbcDirectBackend.class)`, one direct ask) and assert the ask succeeds and `nessyPlanFeature` is absent.

`PlanPerTypeTest` (Postgres): copy `NotebookPerTypeTest`'s setup. Apply the feature to a `Recording` for type A and one for type B; call type A's `update_plan` tool with one task (`PlanTools.UpdatePlan` with a single `PENDING` task titled "write the plan"); read type A's ambient text for the agent → contains "write the plan"; read type B's ambient for the same agent id → `Optional.empty()` (an empty plan contributes nothing). Use `PlanFeatures.call(...)` for the tool call, as the notebook helper does.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test -Dtest='PlanAutoConfigurationTest,PlanWithoutTheJdbcBackendTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `PlanAutoConfiguration` does not exist.

- [ ] **Step 3: Implement**

Poms as listed. `PlanAutoConfiguration` is `NotebookAutoConfiguration` with the names swapped; read that file and mirror every annotation:

```java
@AutoConfiguration(
    after = {
      NessyAutoConfiguration.class,
      JdbcBackendAutoConfiguration.class,
      DataSourceAutoConfiguration.class
    })
@ConditionalOnClass(
    name = "org.jwcarman.nessy.backend.jdbc.JdbcDirectBackend",
    value = JdbcPlans.class)
@ConditionalOnBean({DataSource.class, CodecFactory.class})
@ConditionalOnProperty(name = "nessy.plan.enabled", havingValue = "true", matchIfMissing = true)
public class PlanAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(value = Plans.class, name = "nessyPlanFeature")
  public Customizer<HarnessConfig<?>> nessyPlanFeature(
      DataSource dataSource,
      CodecFactory codecs,
      // Not read: the schema bean is the dependency that makes Spring create the tables first.
      NessySchema schema) {
    return config ->
        PlanTools.feature(new JdbcPlans(dataSource, config.agentType(), codecs)).customize(config);
  }
}
```

Class Javadoc in the notebook's voice: installs on every agent of both doors when present; `nessy.plan.enabled=false` turns it off; an application that declares its own `Plans` bean keeps the starter out, and one that wires by hand without declaring the bean gets "two ambient sources offer the kind 'plan'" at harness build. `package-info.java` with one line. Imports entry after the notebook's.

- [ ] **Step 4: Run the autoconfigure tests, container tests included**

Run: `./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test -Dnessy.excludedGroups=live`
Expected: exit 0. Then, for Review Focus 5, temporarily drop `DataSourceAutoConfiguration.class` from `after`, run `PlanAutoConfigurationTest#a_data_source_boot_makes_is_enough`, see it fail, restore it, and record both runs in the report.

- [ ] **Step 5: Commit**

```bash
git add nessy-spring-boot
git commit -m "feat(boot): the plan store installs itself on every agent; nessy.plan.enabled turns it off"
```

At this commit the whole reactor is red in chat-web and chat-cli ("two ambient sources offer the kind 'plan'"); that is Task 3's red, accepted.

---

### Task 3: the examples stop wiring the plan store; docs; final gate

**Files:**
- Modify: `nessy-examples/chat-web/src/main/java/org/jwcarman/nessy/examples/chatweb/ChatConfiguration.java` (delete the `Plans` bean, the `Plans plans` parameter, `.ambient(PlanTools.plan(plans))`, `.tool(PlanTools.updatePlan(plans))`, the unused imports; fix the class Javadoc)
- Modify: `nessy-examples/chat-cli/src/main/java/org/jwcarman/nessy/examples/chatcli/Chat.java` (the same deletions)
- Modify: `nessy-examples/chat-web/README.md` and `nessy-examples/chat-cli/README.md` (the sentence that says the notebook comes with the starter now says the notebook and the plan do; and `nessy.plan.enabled=false` turns the plan off)
- Modify: `docs/guides/spring-boot.md` (property row for `nessy.plan.enabled` beside the notebook's; a `nessyPlanFeature` row in the "Every bean backs off" section beside `nessyNotebookFeature`, with the same three back-off cases; `nessy_plan_task` beside `nessy_note` in the schema paragraph, its DDL the `nessy-schema.sql` in the planning jar; the worked example's sentence about what features install names the plan too)
- Modify: `docs/concepts/planning.md` (after the hand-wiring snippet, one sentence like `docs/concepts/memory.md:74-76`: with the Spring Boot starter the plan installs itself on every agent, and an application that declares its own `Plans` bean keeps the starter out)
- Modify: `CHANGELOG.md` `[Unreleased]` (Added: the plan store installs itself, same shape as the notebook entry; `PlanTools.feature(Plans)`. Changed: `nessy.initialize-schema=false` applications must create `nessy_plan_task` too)
- Test: `nessy-examples/chat-web/src/test/java/org/jwcarman/nessy/examples/chatweb/ChatTurnsIntegrationTest.java` already asserts `update_plan` is offered exactly once (its `containsOnlyOnce` list); it is the red and the green. `nessy-examples/chat-cli/src/test/java/org/jwcarman/nessy/examples/chatcli/ChatTerminalStartupTest.java` is chat-cli's.

**Interfaces:**
- Consumes: the starter's `nessyPlanFeature`.
- Produces: nothing new.

- [ ] **Step 1: See the red**

Run: `./mvnw -q -pl :nessy-example-chat-web -am test -Dnessy.excludedGroups=live -Dtest=ChatTurnsIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` and the same for `:nessy-example-chat-cli` with `ChatTerminalStartupTest`.
Expected: both fail at context start with `two ambient sources offer the kind 'plan'`. Record both.

- [ ] **Step 2: Implement**

The deletions listed above, mirroring commits 25ed42489 (chat-web) and 699518484 (chat-cli), which did the same for the notebook; `git show <sha> -- <module>/src/main` shows the shape. Check each example's test tree for anything that autowires `Plans` and rebuild it in the test from the `DataSource` and `CodecFactory` if so. Then the docs and changelog edits, in the existing wording and tables.

- [ ] **Step 3: Run both examples' tests**

Run: `./mvnw -q -pl :nessy-example-chat-web,:nessy-example-chat-cli -am test -Dnessy.excludedGroups=live`
Expected: exit 0.

- [ ] **Step 4: The final gate**

Run: `pgrep -fl 'nessy-examp[l]e' | grep -q . && echo "an example is running; stop it first" || ./mvnw -q clean verify -Dnessy.excludedGroups=live`
Expected: exit 0. If watchman's `ApprovalsOverNessyTest` fails on a "restricted mode" page, rerun it once in isolation and report both runs.

- [ ] **Step 5: Commit**

```bash
./mvnw -q spotless:apply license:format
git add nessy-examples docs CHANGELOG.md
git commit -m "examples: the plan store comes from the starter; docs and changelog say so"
```
