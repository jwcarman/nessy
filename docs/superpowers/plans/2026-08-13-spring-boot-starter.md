# Spring Boot Starter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `docs/superpowers/specs/2026-08-13-spring-boot-starter-design.md`: `nessy-autoconfigure` (all `@AutoConfiguration` classes behind `@ConditionalOnClass` gates) + src-less `nessy-spring-boot-starter`, with chat-web's round-two rewrite as the acceptance test.

**Architecture:** The mocapi shape, which is Boot's own: one autoconfigure module whose every feature dependency is `<optional>true</optional>`, each configuration self-gating on classpath presence; a dependency-only starter pom. Provider → persistence → harness autoconfigs land first (each with `ApplicationContextRunner` permutation tests, fully offline), then the web bridge (`@ConditionalOnClass(SseEmitter)`), then chat-web consumes it all and sheds five beans plus its hand-rolled bridge, then docs.

**Tech Stack:** Java 25, Spring Boot 4.1.0 (BOM imported in-module only — `nessy-parent` never learns Spring), `spring-boot-configuration-processor` for property metadata, `ApplicationContextRunner` tests (offline, no containers), no mocking libraries.

## Global Constraints

- Spring enters published code ONLY inside `nessy-autoconfigure`/`nessy-spring-boot-starter`; both are real published artifacts (they join `nessy-bom`); Boot 4.x is the stated compatibility baseline (pom comment + README).
- Every autoconfigured bean is `@ConditionalOnMissingBean` — a user bean always wins, silently. Classpath expresses intent; `nessy.*` properties are the escape hatches.
- Agents are NEVER autoconfigured — identity is not configuration (spec §3; the docs say it as a feature).
- No warning suppressions; no star imports; prose snake_case test names; S5778; S5841; core sealed switches exhaustive without `default` (the bridge's `TurnEvent` switch is in-reactor extender code — exhaustive, no default, same as chat-web's was).
- `./mvnw -q clean verify` offline green (no Docker, no key) after every task; chat-web container smoke green with Docker for Task 5.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`.
- **Model policy:** implementers Sonnet; task reviews Sonnet; scoped re-reviews Haiku; final whole-branch review Opus. No High-risk task in this plan (no loop/fold/transcript changes — it's wiring).
- Commit trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: The two modules + provider autoconfiguration

**Files:**
- Create: `nessy-autoconfigure/pom.xml`, `nessy-spring-boot-starter/pom.xml`
- Modify: root `pom.xml` (add both `<module>` entries after `nessy-store-jdbc`), `nessy-bom/pom.xml` (both artifacts in dependencyManagement)
- Create under `nessy-autoconfigure/src/main/java/org/jwcarman/nessy/autoconfigure/`: `NessyProperties.java`, `AnthropicProviderAutoConfiguration.java`, `OpenAiProviderAutoConfiguration.java`, `ProviderSelectionFailureAnalyzer.java` (only if step 4's fail-fast needs it — prefer a plain thrown exception first)
- Create: `nessy-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `nessy-autoconfigure/src/test/java/org/jwcarman/nessy/autoconfigure/ProviderAutoConfigurationTest.java`

**Interfaces:**
- Produces: `nessy-autoconfigure` module (artifactId `nessy-autoconfigure`; deps: `nessy-core` compile; `spring-boot-autoconfigure` compile; `nessy-model-anthropic`, `nessy-model-openai`, `nessy-store-jdbc` all `<optional>true</optional>`; `spring-boot-configuration-processor` as annotationProcessor; test: `spring-boot-test` + JUnit/AssertJ per house rules, mockito excluded if it rides in). Boot BOM 4.1.0 imported in-module. `nessy-spring-boot-starter` = pom-only deps aggregator: `nessy-autoconfigure` + `nessy-core`.
- Produces: `@ConfigurationProperties(prefix = "nessy") record NessyProperties(String provider, Anthropic anthropic, OpenAi openAi, String defaultModel, Jdbc jdbc)` with nested `record Anthropic(String apiKey, String baseUrl)`, `record OpenAi(String apiKey, String baseUrl)`, `record Jdbc(Boolean enabled, Boolean bootstrapSchema)` (Boolean so absent ≠ false; accessors defaulting via helper methods `jdbcEnabled()`/`bootstrapSchema()` returning true when unset). Tasks 2–3 consume it.
- Produces: `ModelProvider` bean from whichever provider module is present; both present → `nessy.provider` chooses, unset → fail fast naming the property.

- [ ] **Step 1: module scaffolding.** Both poms follow `nessy-store-jdbc/pom.xml`'s conventions (license header, parent `nessy-parent`, publishing inherited — these ARE published; no deploy-skip). `nessy-autoconfigure` imports `spring-boot-dependencies:4.1.0` in `dependencyManagement` (scope import) exactly as `nessy-examples/chat-web/pom.xml` does. Reactor + BOM entries. `./mvnw -q clean verify` green with the empty modules before writing code.
- [ ] **Step 2: failing tests first** — `ProviderAutoConfigurationTest`, `ApplicationContextRunner` style:

```java
class ProviderAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AnthropicProviderAutoConfiguration.class, OpenAiProviderAutoConfiguration.class));

  @Test
  void anthropic_on_the_classpath_with_a_key_yields_a_provider() {
    runner
        .withPropertyValues("nessy.anthropic.api-key=test-key")
        .run(context -> assertThat(context).hasSingleBean(ModelProvider.class));
  }

  @Test
  void a_user_declared_provider_bean_always_wins() {
    runner
        .withPropertyValues("nessy.anthropic.api-key=test-key")
        .withBean("mine", ModelProvider.class, () -> new ScriptedModelProvider(/* per nessy-testing's builder */))
        .run(context -> assertThat(context).getBean(ModelProvider.class).isSameAs(context.getBean("mine")));
  }

  @Test
  void both_provider_jars_without_a_choice_fail_fast_naming_the_property() {
    runner
        .withPropertyValues("nessy.anthropic.api-key=k", "nessy.openai.api-key=k")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasRootCauseMessage(/* contains */ "nessy.provider");
            });
  }

  @Test
  void nessy_provider_property_selects_between_two_present_jars() {
    runner
        .withPropertyValues(
            "nessy.provider=openai", "nessy.anthropic.api-key=k", "nessy.openai.api-key=k")
        .run(context -> assertThat(context).getBean(ModelProvider.class)
            .isInstanceOf(OpenAiModelProvider.class));
  }
}
```

  (Both provider jars are test-scope deps of the autoconfigure module, so "present" is the test default; classpath-ABSENT cases use `FilteredClassLoader` — add `anthropic_absent_means_no_anthropic_bean` using `new FilteredClassLoader(AnthropicModelProvider.class)`. Adapt assertions to the real builder APIs — no network is touched because construction only stores the key.)
- [ ] **Step 3: run — fails** (classes missing).
- [ ] **Step 4: implement.** Each provider autoconfig: `@AutoConfiguration`, `@ConditionalOnClass(AnthropicModelProvider.class)`, `@EnableConfigurationProperties(NessyProperties.class)`; bean method `@ConditionalOnMissingBean(ModelProvider.class)` guarded by the selection rule: single-jar → build (property key wins, else `fromEnv()`); both jars → consult `nessy.provider` (`anthropic`/`openai`), unset → `throw new IllegalStateException("two model-provider modules are on the classpath; set nessy.provider=anthropic|openai")`. Detect "both present" via `ClassUtils.isPresent` on the other module's class. Register both in the `.imports` file.
- [ ] **Step 5: green; full offline reactor verify; format.**
- [ ] **Step 6: Commit:** `feat: nessy-autoconfigure is born — providers arrive by classpath`

---

### Task 2: Persistence autoconfiguration

**Files:**
- Create: `nessy-autoconfigure/src/main/java/org/jwcarman/nessy/autoconfigure/JdbcPersistenceAutoConfiguration.java` (+ `.imports` line)
- Test: `nessy-autoconfigure/src/test/java/org/jwcarman/nessy/autoconfigure/JdbcPersistenceAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `NessyProperties.jdbc` (Task 1), `JdbcPersistence.create(DataSource, ObjectMapper)` and the two public constructors `new JdbcConversationStore(ds, mapper)` / `new JdbcMemory(ds, mapper)` (bootstrap-free — READ both classes to confirm the constructors are public and skip DDL; if they are not public, widen nothing: call the factories and drop the `bootstrap-schema=false` distinction, noting it in your report and the property javadoc).
- Produces: `ConversationStore` + `AgentMemory` beans when `nessy-store-jdbc` and a `DataSource` bean are present; `nessy.jdbc.enabled=false` disables; user beans win.

- [ ] **Step 1: failing tests.** Runner with `JdbcPersistenceAutoConfiguration` + a stub `DataSource` bean (the offline stub pattern from `JdbcPersistenceRecordTest` — construction never opens a connection when bootstrap is off; for the bootstrap-on default use `bootstrap-schema=false` in these context tests and leave real-DDL proof to chat-web's smoke):
  - `jdbc_on_the_classpath_with_a_datasource_yields_store_and_memory` (both beans present, `bootstrap-schema=false`);
  - `no_datasource_means_no_jdbc_beans` (context fine, beans absent);
  - `nessy_jdbc_enabled_false_is_the_master_switch` (datasource present, beans absent);
  - `a_user_declared_store_bean_wins_and_memory_still_autoconfigures`;
  - `jdbc_module_absent_means_no_jdbc_beans` (`FilteredClassLoader(JdbcPersistence.class)`).
- [ ] **Step 2: implement.** `@AutoConfiguration(after = …nothing needed…)`, `@ConditionalOnClass(JdbcPersistence.class)`, `@ConditionalOnBean(DataSource.class)`, `@ConditionalOnProperty(name = "nessy.jdbc.enabled", havingValue = "true", matchIfMissing = true)`; two bean methods (`@ConditionalOnMissingBean` each): store and memory, built from one shared private helper honoring `bootstrapSchema()` (factories when true, constructors when false). Javadoc: adding the jar next to a datasource flips the app durable — the right default the moment the classpath says "I have a database" (spec §3).
- [ ] **Step 3: green; offline reactor verify; format; commit:** `feat: a datasource plus the jdbc jar equals durable — persistence by classpath`

---

### Task 3: Harness autoconfiguration

**Files:**
- Create: `nessy-autoconfigure/src/main/java/org/jwcarman/nessy/autoconfigure/NessyAutoConfiguration.java` (+ `.imports` line)
- Test: `nessy-autoconfigure/src/test/java/org/jwcarman/nessy/autoconfigure/NessyAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `ModelProvider` bean (Task 1), optional `ConversationStore` (Task 2), Boot's `ObservationRegistry`/`ObjectMapper` when present.
- Produces: `Harness` bean: `Nessy.harness(provider)` + `.store(store)` when a store bean exists + `.observations(registry)` when one exists + `.defaultModel(props.defaultModel())` when set + Boot's mapper via `HarnessBuilder`'s mapper method if one exists (READ HarnessBuilder for the exact seam — `.mapper(...)` or constructor-only; wire what exists, note what doesn't).

- [ ] **Step 1: failing tests.** Runner combining all three autoconfigurations:
  - `a_provider_alone_yields_a_harness_on_defaults` (in-memory store, NOOP observations — assert `hasSingleBean(Harness.class)`);
  - `a_store_bean_is_woven_in` (runner with the Task 2 config + stub datasource + `bootstrap-schema=false`; assert harness exists AND — observable seam — `harness.peek(ParkToken.generate())` returns empty rather than throwing, proving a store is wired; adapt to what's actually observable without reflection and note the choice);
  - `boots_observation_registry_is_woven_in` (`withBean(ObservationRegistry.class, ObservationRegistry::create)` — assert context succeeds; deeper observability proof stays with chat-web's Grafana story);
  - `nessy_default_model_reaches_the_harness` (property set; assert via `harness.agent().build()`-level behavior only if cheap — otherwise assert context success and note the coverage boundary);
  - `a_user_declared_harness_wins`.
- [ ] **Step 2: implement.** `@AutoConfiguration(after = {AnthropicProviderAutoConfiguration.class, OpenAiProviderAutoConfiguration.class, JdbcPersistenceAutoConfiguration.class})`, `@ConditionalOnBean(ModelProvider.class)`, `@ConditionalOnMissingBean(Harness.class)`; `ObjectProvider<ConversationStore>`, `ObjectProvider<ObservationRegistry>`, `ObjectProvider<ObjectMapper>` parameters — `ifAvailable` weaving. Javadoc opens with the razor: substrate arrives, identity stays yours; agents are never autoconfigured.
- [ ] **Step 3: green; offline verify; format; commit:** `feat: the harness assembles itself — substrate by autoconfiguration, identity still yours`

---

### Task 4: The web bridge

**Files:**
- Create under `nessy-autoconfigure/src/main/java/org/jwcarman/nessy/autoconfigure/web/`: `TurnEventSse.java`, `TurnRunner.java`, `NessyWebAutoConfiguration.java` (+ `.imports` line)
- Modify: `nessy-autoconfigure/pom.xml` (add `spring-webmvc` + `io.micrometer:context-propagation` optional; both BOM-managed)
- Test: `nessy-autoconfigure/src/test/java/org/jwcarman/nessy/autoconfigure/web/TurnEventSseTest.java`, `TurnRunnerTest.java`, plus conditional-presence cases in a `NessyWebAutoConfigurationTest`

**Interfaces:**
- Produces (chat-web's Task 5 consumes these names exactly):

```java
public final class TurnEventSse {
  public record Event(String name, Map<String, Object> payload) {}
  public static Event of(TurnEvent event);            // exhaustive switch, no default; park arm
                                                      // carries {token, tool, args} with
                                                      // args = call.arguments().toPrettyString()
  public static TurnObserver observer(Consumer<Event> sink);
  public static void send(SseEmitter emitter, Event event);  // broken pipe → complete, never throw
}

public final class TurnRunner {                        // bean, constructed by the autoconfig
  public SseEmitter run(Supplier<RunOutcome> turn, BiConsumer<SseEmitter, RunOutcome> onOutcome);
  // creates SseEmitter(0L), captures ContextSnapshotFactory.captureAll() on the CALLING thread,
  // starts a virtual thread with the snapshot scope restored, invokes turn.get(), hands the
  // outcome to onOutcome(emitter, outcome); catches RuntimeException → emits
  // Event("done", {status:"ERROR", failureReason}) and completeWithError. The caller's observer
  // (built via TurnEventSse.observer) streams during turn.get() as today.
}
```

- Event-name vocabulary (the starter's stable wire contract, spec §4): `delta`, `thinking`, `tool-requested`, `tool-progress`, `tool-decided`, `tool-completed`, `tool-parked`, `done`. NOTE the park event is **`tool-parked`** here — chat-web adopts the starter's names in Task 5 (its `approval-needed` was app-vocabulary; the reference consumer conforms to the published contract, not vice versa).

- [ ] **Step 1: failing tests.** `TurnEventSseTest` ports chat-web's `SseEventsTest` cases to the new names (all 8 variants pinned — copy the exhaustive-list pattern from `TurnObserverAdapterTest.oneOfEveryVariant`, not a hand-list; the park arm asserts token, tool, AND pretty-printed args — `contains("\n")` pins pretty-printedness, closing the deferred minor). `TurnRunnerTest` offline: a scripted `Supplier<RunOutcome>` returning a `Completed` — assert `onOutcome` received it on another thread with the emitter; a throwing supplier — assert the emitter got the ERROR `done` (collect via a recording emitter subclass or by asserting completion state — choose the smallest honest probe and note it). `NessyWebAutoConfigurationTest`: `TurnRunner` bean present with webmvc on classpath, absent under `FilteredClassLoader(SseEmitter.class)`.
- [ ] **Step 2: implement.** `NessyWebAutoConfiguration`: `@AutoConfiguration`, `@ConditionalOnClass(SseEmitter.class)`, `@ConditionalOnMissingBean` `TurnRunner` bean. `TurnEventSse.of` is chat-web's `SseEvents` switch generalized (bring the javadoc voice along; the zero-emission race note points at `Agent.snapshot` as the rebuild source). `send` is chat-web's `sendEvent` verbatim in spirit (log-and-complete on IOException).
- [ ] **Step 3: green; offline verify; format; commit:** `feat: the web bridge ships — eight events, one tolerant send, an unmakeable trace bug`

---

### Task 5: chat-web round two — the acceptance rewrite

**Files:**
- Modify: `nessy-examples/chat-web/pom.xml` (depend on `nessy-spring-boot-starter`; drop nothing else — provider/store jars stay, that's the classpath-intent model)
- Modify: `NessyConfig.java` (delete provider/persistence/harness beans — the starter supplies them; keep ONLY the agent bean; the `@Profile("!test")` split dissolves: the smoke's `@TestConfiguration` `Harness` bean wins by `@ConditionalOnMissingBean`), `ChatController.java` + `ApprovalController.java` (use the injected `TurnRunner` + `TurnEventSse`; delete the local `CONTEXT_SNAPSHOT_FACTORY`, `sendEvent`, `fail`, and emitter/thread plumbing), delete `SseEvents.java` + `SseEventsTest.java` (superseded by `TurnEventSse`)
- Modify: `static/app.js` (handler rename `approval-needed` → `tool-parked`; dedupe logic unchanged), `ChatWebSmokeTest.java` (event-name assertions follow; nothing weakened)
- Test: container smoke green with Docker.

**Interfaces:** consumes Tasks 1–4 by exact name. Acceptance greps before commit: `grep -rn "ContextSnapshotFactory\|class SseEvents" nessy-examples/chat-web/src/main` → empty; `grep -c "@Bean" NessyConfig.java` → 1; `grep -rn "approval-needed" nessy-examples/chat-web/src` → empty.

- [ ] **Step 1:** pom + `NessyConfig` shrink (application.yaml gains nothing — datasource props already exist; verify the starter picks them up).
- [ ] **Step 2:** controllers on `TurnRunner`/`TurnEventSse`; app.js rename; smoke updated.
- [ ] **Step 3:** offline reactor verify green; with Docker `./mvnw -pl nessy-examples/chat-web test -Dnessy.excludedGroups=live` green; acceptance greps empty.
- [ ] **Step 4:** format; commit: `refactor: chat-web round two — one bean left, the bridge is the framework's now`

---

### Task 6: Docs

**Files:**
- Modify: root `README.md` (Observability section's "that starter does not exist yet" flips to the two-line Boot recipe + pointer; Status section's not-yet-built list drops the starter; durable section gains the add-the-jar recipe sentence), `nessy-examples/chat-web/README.md` (wiring snippet reflects the one-bean reality), `CHANGELOG.md` (Unreleased: both new artifacts, the autoconfiguration graph in the house voice, the bridge vocabulary, chat-web round two; note `approval-needed`→`tool-parked` as an example-app wire change), spec status flip: starter spec → `IMPLEMENTED (see plan 2026-08-13-spring-boot-starter)`.
- Sweep: `grep -rn "starter does not exist\|planned Spring Boot starter\|approval-needed" README.md docs/ nessy-examples/*/README.md`.

- [ ] **Step 1:** write; offline verify; format; commit: `docs: the starter exists now — the paperwork agrees`

---

## Self-review notes (performed at plan time)

- **Spec coverage:** §2→T1 (modules/BOM/baseline); §3 providers→T1, persistence→T2, harness→T3, never-agents→T3 javadoc + docs; §4→T4 (with the park-name contract decision recorded: chat-web conforms); §5 properties→T1/T2/T3 (metadata via processor in T1's pom); §6→every task's runner tests + T5's smoke; §7→T5 (acceptance) + T6 (docs/BOM/CHANGELOG). No gaps.
- **Placeholder scan:** T2/T3 carry read-first adaptation clauses (constructor visibility, mapper seam) with report-back requirements — judgment clauses, not placeholders.
- **Type consistency:** `TurnEventSse.Event(name, payload)` / `TurnRunner.run(Supplier, BiConsumer)` / property keys identical across producing (T1–T4) and consuming (T5) tasks; event vocabulary stated once in T4 and referenced by T5.
