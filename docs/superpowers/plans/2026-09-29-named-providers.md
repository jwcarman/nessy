# Named Providers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A harness factory holds several inference providers by name; every agent type names the provider and model it uses; under Boot, a catalogue of presets turns a vendor key (or an explicit `enabled`) into a registered provider.

**Architecture:** `ProviderId` (a value type in `nessy-api`) keys a registry held by both factory configs. Each agent type's `InferenceConfig` names a provider and a model, falling back to factory defaults; resolution happens once, in `create`, and the engine loop never sees an id. Under Boot, one `BeanDefinitionRegistryPostProcessor` registers every lit preset (and every custom `nessy.providers.<id>` entry) as an `InferenceProvider` bean named by its id; the harness auto-configurations register every `InferenceProvider` bean in the context under its bean name.

**Tech Stack:** Java 25, Maven reactor, Spring Boot 4 auto-configuration, JUnit 5 + AssertJ, `ApplicationContextRunner`, Micrometer Observation.

**Spec:** `docs/superpowers/specs/2026-09-29-named-providers-design.md` — read it before any task. Section numbers below (§n) refer to it.

**Rulings taken for the spec's open questions (James: "commit it and let's implement", 2026-09-29, spec recommendations adopted):**
- §14(2): the embedder half of the rename is done in Task 1 too.
- §14(3): an application bean whose name equals a lit preset id fails startup, naming both.
- §14(4): first cut ships `openai`, `xai`, `anthropic`, `gemini` (exist today) and `lmstudio` (measured in Task 5 against the LM Studio running on this machine). `ollama` and the other candidates do not ship.
- §14(5): `DirectHarnessFactory.providerName()` is deleted in Task 3.
- §14(6): the factory-defaults method is `inference(ProviderId, InferenceOptions)`.
- §14(7): no lookup-by-id on the factory.
- §14(8): the property is `nessy.provider`.
- §14(1), §14(9): not answered; nothing in this plan records a provider id on events.

## Global Constraints

- Full verification: `./mvnw -q clean verify` — must pass with no API key and no network. Run it ONCE per task, as the final gate before the task's last commit. While iterating use warm scoped builds: `./mvnw -q -pl :<artifactId> -am test` (artifactId form with the colon, never the path form).
- Never run two Maven processes at once in this worktree. Before every Maven command, check nothing from this repo is running: `pgrep -fl 'nessy-examples|spring-boot:run' | grep -v occlude` must print nothing about nessy.
- If a scoped run hangs after an interface change, suspect a stale jar in `~/.m2`: `./mvnw -q -pl :<changed>,:<dependent> -am install -DskipTests`, then retry.
- `spotless:check` runs before compilation: run `./mvnw -q spotless:apply license:format` before reading any build error, and before every commit.
- Every new file carries the Apache license header (copy it from any existing file in the same module; `license:format` adds it).
- Formatting is google-java-format (spotless enforces it).
- No warning suppression of any kind (`@SuppressWarnings`, etc.). No star imports, including static imports.
- Tests: prose-style method names (`an_agent_type_naming_a_registered_id_gets_that_provider`), `@DisplayName` only where the neighbouring tests use it, NO mocking library (providers in tests are lambdas or small classes), AssertJ.
- Sonar S5778: an `assertThatThrownBy` lambda contains exactly ONE call that can throw; build configs, ids and factories outside it.
- Assert a collection is non-empty before any `allMatch`/`noneMatch`/`allSatisfy` on it.
- Javadoc: never put a second `/** */` above a declaration that already has one (the first is silently dropped).
- XML comments may not contain `--`.
- Docs describe what is, never history or roads not taken.
- Every commit message ends with:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC
  ```
- Branch: `named-providers`. Commit to it; never push.

## Review Focus

1. **A bean name that is not a valid `ProviderId`** (a space, over 64 characters): registering it must fail naming the bean, not with a bare identifier error. → Task 4 test `a_bean_whose_name_is_not_a_provider_id_fails_naming_the_bean`.
2. **A blank key** (`OPENAI_API_KEY=` exported empty): the preset must NOT light. → Task 4 test `a_blank_key_does_not_light_a_preset`.
3. **Half the defaults** (`nessy.provider` set, `nessy.model` not, or the reverse): startup fails naming both properties, rather than a harness failing later with a message about agent types. → Task 4 test `setting_only_one_of_provider_and_model_fails_naming_both`.
4. **A lit preset whose wire module is absent** (`ANTHROPIC_API_KEY` set, no `nessy-inference-anthropic` on the classpath): the preset is skipped with an INFO line, never a `NoClassDefFoundError`. → Task 4 test using `FilteredClassLoader`.
5. **The environment-variable spelling of a prefixed key** (`NESSY_PROVIDERS_XAI_APIKEY`): it must bind to `nessy.providers.xai.api-key`. → Task 4 test `the_environment_variable_form_of_a_prefixed_key_lights_the_preset`.

---

### Task 1: The rename — `providerName()` becomes `vendor()`

Mechanical. No behaviour change; OTel tags byte-identical. Suggested implementer model: Sonnet (wide, needs judgment on the two senses of "gateway").

**Files:** every file listed in spec §4a and §4b, plus the embedder family (§14(2)):
- `nessy-api/src/main/java/org/jwcarman/nessy/api/embedding/Embedder.java` (`providerName()` → `vendor()`)
- `nessy-embedding/spi/src/main/java/org/jwcarman/nessy/embedding/EmbeddingProvider.java`
- the four embedding adapters under `nessy-embedding/{openai,gemini,bedrock,voyage}/src/main`
- `nessy-engine/src/main/java/org/jwcarman/nessy/engine/observability/ObservedEmbedder.java`
- `nessy-engine/src/main/java/org/jwcarman/nessy/engine/embedding/DefaultEmbedder.java`
- their tests

Find every site with: `grep -rn "providerName\|PROVIDER_NAME\|XAI_PROVIDER_NAME" --include='*.java' --include='*.md' . | grep -v /target/`

**Interfaces:**
- Produces: `String InferenceProvider.vendor()` (default method, same body as today's `providerName()`); `OpenAiProviderConfig vendor(String vendor)`; constants `VENDOR` on each of the four inference adapters (replacing `PROVIDER_NAME`); `OpenAiAutoConfiguration.XAI_VENDOR`; `String Embedder.vendor()`; `String EmbeddingProvider.vendor()`; `String DirectHarnessFactory.vendor()` (renamed here, deleted in Task 3).

- [ ] **Step 1: Rename the SPI method and every override/caller**

In `InferenceProvider.java` rename `providerName()` to `vendor()`, keep the body; update its javadoc to say it is the OpenTelemetry `gen_ai.provider.name` value — the vendor, which two providers can share. Rename every override (`OpenAiInferenceProvider`, `AnthropicInferenceProvider`, `GeminiInferenceProvider`, `BedrockInferenceProvider`, `ObservedInferenceProvider`) and every caller (`DefaultDirectHarnessFactory`, `DirectHarnessFactory`, `Repl`, `InferenceReport`, tests). In `ObservedInferenceProvider` the tag stays `gen_ai.provider.name`; only the method it reads changes.

- [ ] **Step 2: Rename the constants and the OpenAI config setter**

`PROVIDER_NAME` → `VENDOR` on all four adapters and every reference (`AnthropicRequests`, `GeminiRequests`, `BedrockRequests`, tests). `OpenAiProviderConfig.provider(String)` → `vendor(String)`, field `provider` → `vendor`; `OpenAiInferenceProvider`'s field/constructor parameter likewise. `OpenAiAutoConfiguration.XAI_PROVIDER_NAME` → `XAI_VENDOR`, and its call becomes `.vendor(XAI_VENDOR)`.

- [ ] **Step 3: Rename the embedder family the same way**

`Embedder.providerName()` → `vendor()`, `EmbeddingProvider.providerName()` → `vendor()`, every adapter override, `ObservedEmbedder`, `DefaultEmbedder`, tests. The tag the observed embedder writes does not change.

- [ ] **Step 4: Rename the two test classes**

`git mv` `OpenAiProviderNameTest.java` → `OpenAiVendorTest.java` and `AnthropicProviderNameTest.java` → `AnthropicVendorTest.java`; rename the class inside each; rename any test method whose name says "provider name" to say "vendor".

- [ ] **Step 5: Fix the prose**

Replace adapter-sense "gateway" with "provider" at exactly the sites spec §4b lists (HTTP-sense "gateway" stays). Replace the four "ModelProvider" mentions with the current name (`InferenceProvider`; the bean method `xaiModelProvider` is now `xaiInferenceProvider`). Update `docs/guides/providers.md` (`.provider("x_ai")` → `.vendor("x_ai")`, `providerName()` → `vendor()`) and `docs/guides/observability.md` (`InferenceProvider.providerName()` → `InferenceProvider.vendor()`).

- [ ] **Step 6: Prove nothing is left**

Run: `grep -rn "providerName\|PROVIDER_NAME\|ModelProvider" --include='*.java' --include='*.md' . | grep -v /target/ | grep -v docs/superpowers/`
Expected: no output.

- [ ] **Step 7: Format and verify**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q clean verify`
Expected: exit 0. The tests that pin `gen_ai.provider.name` values (`ObservedTest`, `InferenceProviderTest`, the vendor tests, the summariser tests) pass unchanged in their expected values.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: the name a provider reports is its vendor"
```

---

### Task 2: `ProviderId`

Suggested implementer model: Haiku (the code below is complete).

**Files:**
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/ProviderId.java`
- Test: `nessy-api/src/test/java/org/jwcarman/nessy/api/ProviderIdTest.java`

**Interfaces:**
- Consumes: `Identifiers.require(String value, String what, int maxLength)` (public, `nessy-api`).
- Produces: `public record ProviderId(String value)` with `public static ProviderId of(String value)`.

- [ ] **Step 1: Write the failing test**

```java
package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProviderIdTest {

  @Test
  void a_provider_id_keeps_the_name_it_was_given() {
    assertThat(ProviderId.of("openai-batch").value()).isEqualTo("openai-batch");
  }

  @Test
  void two_ids_with_the_same_name_are_the_same_id() {
    assertThat(ProviderId.of("xai")).isEqualTo(new ProviderId("xai"));
  }

  @Test
  void a_blank_id_is_refused() {
    assertThatThrownBy(() -> ProviderId.of(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("provider id");
  }

  @Test
  void an_id_with_a_space_in_it_is_refused() {
    assertThatThrownBy(() -> ProviderId.of("my provider"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("provider id");
  }

  @Test
  void an_id_longer_than_sixty_four_characters_is_refused() {
    String tooLong = "p".repeat(65);
    assertThatThrownBy(() -> ProviderId.of(tooLong))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("provider id");
  }
}
```

Before running, open `Identifiers.java` and confirm which exception type `require` throws for blank, bad characters and length; if it is not `IllegalArgumentException`, use the type it does throw in all three tests.

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -pl :nessy-api test -Dtest=ProviderIdTest`
Expected: compilation failure, `cannot find symbol: class ProviderId`.

- [ ] **Step 3: Write the record**

```java
package org.jwcarman.nessy.api;

/**
 * The name an application gives one of its inference providers: {@code openai}, {@code xai},
 * {@code openai-batch}.
 *
 * <p>Ours, not the vendor's. Two providers can speak to the same vendor -- two OpenAI keys with
 * different quotas -- and report the same vendor to a trace, but each has its own id, and an agent
 * type names the one it wants by it.
 */
public record ProviderId(String value) {

  private static final int MAX_LENGTH = 64;

  public ProviderId {
    value = Identifiers.require(value, "provider id", MAX_LENGTH);
  }

  public static ProviderId of(String value) {
    return new ProviderId(value);
  }
}
```

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q -pl :nessy-api test -Dtest=ProviderIdTest`
Expected: 5 tests pass.

- [ ] **Step 5: Format, verify, commit**

```bash
./mvnw -q spotless:apply license:format && ./mvnw -q clean verify
git add -A
git commit -m "feat: a provider has a name of our choosing"
```

---

### Task 3: The engine registry, both doors

The largest task. Suggested implementer model: Sonnet; reviewer: Sonnet. It changes public API on both doors and every caller, and the reactor must be green at the end.

**Files:**
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/InferenceConfig.java` (add `provider(ProviderId)` and default `provider(String)`)
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/DirectHarnessFactory.java` (delete `vendor()`)
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/harness/ProviderRegistry.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/ProviderRegistryTest.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/harness/direct/{DirectHarnessFactoryConfig,DefaultDirectHarnessConfig,DefaultDirectHarnessFactory}.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/harness/queued/{QueuedHarnessFactoryConfig,DefaultQueuedHarnessConfig,DefaultQueuedHarnessFactory}.java`
- Modify (callers): `nessy-engine/src/test/java/org/jwcarman/nessy/engine/EngineFixture.java`, `DefaultDirectHarnessTest`, `DirectHarnessFanOutTest`, `DirectHarnessLiveTest`, `DirectHarnessObservabilityTest`, `DurableDirectHarnessTest`, and any other test the compiler names; `nessy-spring-boot/autoconfigure/.../DirectHarnessAutoConfiguration.java`, `QueuedHarnessAutoConfiguration.java`; `nessy-console/.../Repl.java`; `nessy-examples/chat-cli/.../Chat.java`
- Test: new cases in `DefaultDirectHarnessTest` and in the queued harness's main test class (find it: `grep -rl "DefaultQueuedHarnessFactory.of" nessy-engine/src/test`)

**Interfaces:**
- Consumes: `ProviderId` (Task 2); `InferenceProvider.vendor()` (Task 1); `ObservedInferenceProvider.wrap(InferenceProvider, ObservationRegistry)` (idempotent).
- Produces:
  - `InferenceConfig provider(ProviderId id)`; `default InferenceConfig provider(String id) { return provider(ProviderId.of(id)); }`
  - `DirectHarnessFactoryConfig provider(ProviderId id, InferenceProvider provider)` (repeatable; replaces `provider(InferenceProvider)`)
  - `DirectHarnessFactoryConfig inference(ProviderId provider, InferenceOptions options)` (new)
  - `QueuedHarnessFactoryConfig provider(ProviderId id, InferenceProvider provider)` (new, repeatable)
  - `QueuedHarnessFactoryConfig inference(ProviderId provider, InferenceOptions options)` (replaces `inference(InferenceProvider, InferenceOptions)`)
  - package `org.jwcarman.nessy.engine.harness`: `public final class ProviderRegistry` (used by both doors; public only because the two doors are sibling packages)
  - log line from both `create`s: `NESSY INFERENCE: agent type '<type>' -> <id> / <model>, up to <maxTokens> tokens`
  - Boot harness auto-configurations register every `InferenceProvider` bean under its bean name, and call `inference(ProviderId.of(nessy.provider), new InferenceOptions(nessy.model, nessy.max-tokens))` only when BOTH `nessy.provider` and `nessy.model` are set. (Task 4 adds `provider` to `NessyProperties`; in THIS task read it with `environment.getProperty("nessy.provider")`.)

- [ ] **Step 1: Write the registry's failing test**

```java
package org.jwcarman.nessy.engine.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.engine.observability.ObservedInferenceProvider;
import org.jwcarman.nessy.inference.InferenceProvider;

class ProviderRegistryTest {

  private static final AgentType CHAT = new AgentType("chat");
  private static final ProviderId OPENAI = ProviderId.of("openai");
  private static final ProviderId XAI = ProviderId.of("xai");

  private final InferenceProvider openai = (request, narrator) -> null;
  private final InferenceProvider xai = (request, narrator) -> null;

  @Test
  void an_agent_type_naming_a_registered_id_gets_that_provider_observed() {
    ProviderRegistry registry = new ProviderRegistry();
    registry.register(OPENAI, openai);
    registry.register(XAI, xai);
    ProviderRegistry.Resolved observed = registry.observed(ObservationRegistry.NOOP);

    InferenceProvider resolved = observed.resolve(CHAT, XAI, OPENAI);

    assertThat(resolved).isInstanceOf(ObservedInferenceProvider.class);
    assertThat(resolved).isSameAs(observed.resolve(new AgentType("critic"), XAI, null));
  }

  @Test
  void an_agent_type_naming_nothing_gets_the_default() {
    ProviderRegistry registry = new ProviderRegistry();
    registry.register(OPENAI, openai);
    registry.register(XAI, xai);
    ProviderRegistry.Resolved observed = registry.observed(ObservationRegistry.NOOP);

    assertThat(observed.resolve(CHAT, null, OPENAI))
        .isSameAs(observed.resolve(CHAT, OPENAI, null));
  }

  @Test
  void an_unregistered_id_fails_naming_the_agent_type_and_what_is_registered() {
    ProviderRegistry registry = new ProviderRegistry();
    registry.register(OPENAI, openai);
    registry.register(XAI, xai);
    ProviderRegistry.Resolved observed = registry.observed(ObservationRegistry.NOOP);
    ProviderId claude = ProviderId.of("claude");

    assertThatThrownBy(() -> observed.resolve(CHAT, claude, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "agent type 'chat' names provider 'claude', which is not registered;"
                + " registered: [openai, xai]");
  }

  @Test
  void no_id_and_no_default_fails_naming_the_agent_type_and_what_is_registered() {
    ProviderRegistry registry = new ProviderRegistry();
    registry.register(OPENAI, openai);
    ProviderRegistry.Resolved observed = registry.observed(ObservationRegistry.NOOP);

    assertThatThrownBy(() -> observed.resolve(CHAT, null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "agent type 'chat' names no provider and the factory has no default;"
                + " registered: [openai]");
  }

  @Test
  void registering_the_same_id_twice_fails_at_once() {
    ProviderRegistry registry = new ProviderRegistry();
    registry.register(OPENAI, openai);

    assertThatThrownBy(() -> registry.register(OPENAI, xai))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("provider 'openai' is already registered");
  }

  @Test
  void the_registered_ids_are_listed_in_registration_order() {
    ProviderRegistry registry = new ProviderRegistry();
    registry.register(XAI, xai);
    registry.register(OPENAI, openai);

    assertThat(registry.ids()).containsExactly(XAI, OPENAI);
  }
}
```

Check `InferenceProvider`'s abstract method before writing the lambdas: it is `infer(InferenceRequest, InferenceNarrator)`; a lambda returning `null` is enough because nothing here calls it.

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -pl :nessy-engine -am test -Dtest=ProviderRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: class ProviderRegistry`.

- [ ] **Step 3: Write `ProviderRegistry`**

```java
package org.jwcarman.nessy.engine.harness;

import io.micrometer.observation.ObservationRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.engine.observability.ObservedInferenceProvider;
import org.jwcarman.nessy.inference.InferenceProvider;

/**
 * The providers a factory holds, by the names the application gave them.
 *
 * <p>Both doors keep one, and both resolve an agent type's provider against it exactly once, when
 * the harness is built. Nothing downstream of that ever sees an id: the harness is handed the
 * provider itself.
 */
public final class ProviderRegistry {

  private final Map<ProviderId, InferenceProvider> providers = new LinkedHashMap<>();

  /** Two things called {@code openai} is a configuration error, not a preference. */
  public void register(ProviderId id, InferenceProvider provider) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(provider, "provider must not be null");
    if (providers.putIfAbsent(id, provider) != null) {
      throw new IllegalArgumentException("provider '" + id.value() + "' is already registered");
    }
  }

  public List<ProviderId> ids() {
    return List.copyOf(providers.keySet());
  }

  /**
   * Every provider wrapped once, so two agent types naming the same id share one instance and one
   * wrapper.
   */
  public Resolved observed(ObservationRegistry observations) {
    Map<ProviderId, InferenceProvider> wrapped = new LinkedHashMap<>();
    providers.forEach((id, p) -> wrapped.put(id, ObservedInferenceProvider.wrap(p, observations)));
    return new Resolved(Map.copyOf(wrapped), ids());
  }

  /** The registry as a factory holds it once built: observed, and closed to registration. */
  public static final class Resolved {

    private final Map<ProviderId, InferenceProvider> providers;
    private final List<ProviderId> order;

    private Resolved(Map<ProviderId, InferenceProvider> providers, List<ProviderId> order) {
      this.providers = providers;
      this.order = order;
    }

    /** The agent type's own id if it named one, the factory default if not. */
    public ProviderId choose(AgentType agentType, @Nullable ProviderId named, @Nullable ProviderId fallback) {
      ProviderId id = named != null ? named : fallback;
      if (id == null) {
        throw new IllegalStateException(
            "agent type '"
                + agentType.value()
                + "' names no provider and the factory has no default; registered: "
                + registered());
      }
      if (!providers.containsKey(id)) {
        throw new IllegalStateException(
            "agent type '"
                + agentType.value()
                + "' names provider '"
                + id.value()
                + "', which is not registered; registered: "
                + registered());
      }
      return id;
    }

    public InferenceProvider resolve(
        AgentType agentType, @Nullable ProviderId named, @Nullable ProviderId fallback) {
      return providers.get(choose(agentType, named, fallback));
    }

    private String registered() {
      return order.stream().map(ProviderId::value).collect(Collectors.joining(", ", "[", "]"));
    }
  }
}
```

Check the engine already depends on `org.jspecify` (`grep -rl "org.jspecify" nessy-engine/src/main | head -1`); if it does not, drop the `@Nullable` annotations rather than adding a dependency.

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q -pl :nessy-engine -am test -Dtest=ProviderRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 6 tests pass.

- [ ] **Step 5: `InferenceConfig` names a provider**

Add to `InferenceConfig` (nessy-api), above `model`:

```java
  /**
   * Which of the factory's providers answers. Defaults to the factory's; an agent type that names
   * none and a factory with no default fail when the harness is built.
   */
  InferenceConfig provider(ProviderId id);

  /** {@link #provider(ProviderId)}, by name. */
  default InferenceConfig provider(String id) {
    return provider(ProviderId.of(id));
  }
```

Change `model`'s javadoc to: `Which model. Defaults to the factory's; required one way or the other.`

- [ ] **Step 6: The direct door's config holds a registry and defaults**

In `DirectHarnessFactoryConfig`:
- replace the `provider` field with `private final ProviderRegistry providers = new ProviderRegistry();` plus `private ProviderId defaultProvider;` and `private InferenceOptions defaultOptions;`
- replace `provider(InferenceProvider)` with:

```java
  /**
   * One of the providers this factory's agents may be answered by, under the name they will ask
   * for it by. Repeatable; the same id twice fails at once.
   */
  public DirectHarnessFactoryConfig provider(ProviderId id, InferenceProvider provider) {
    providers.register(id, provider);
    return this;
  }

  /**
   * What an agent type gets when it says nothing: which provider, which model, how much answer.
   * Optional; without it every agent type names both itself.
   */
  public DirectHarnessFactoryConfig inference(ProviderId provider, InferenceOptions options) {
    this.defaultProvider = Objects.requireNonNull(provider, "provider must not be null");
    this.defaultOptions = Objects.requireNonNull(options, "options must not be null");
    return this;
  }
```

- replace `requiredProvider()` with package-private readers `ProviderRegistry providers()`, `ProviderId defaultProvider()` (nullable), `InferenceOptions defaultOptions()` (nullable).

- [ ] **Step 7: The direct door's `Inference` carries a provider id and starts from the defaults**

In `DefaultDirectHarnessConfig`:
- `Inference` gains `private ProviderId provider;` and implements `provider(ProviderId id)` (null-checked, returns `this`) plus reader `ProviderId provider()`.
- Give `Inference` a constructor `Inference(@Nullable ProviderId provider, @Nullable InferenceOptions defaults)` that sets `provider`, and when `defaults != null` sets `modelName = defaults.modelName()` and `maxTokens = defaults.maxTokens()` (leave `maxTokens` at 4096 otherwise).
- `DefaultDirectHarnessConfig`'s constructor gains those two nullable parameters and builds its `Inference` with them. Find the constructor call in `DefaultDirectHarnessFactory.create` (`new DefaultDirectHarnessConfig<>(agentType, observations)`) and pass `defaultProvider, defaultOptions` held by the factory.

Before step 7, read how `InferenceOptions` validates (`nessy-inference/spi/.../InferenceOptions.java`): if it rejects `maxTokens == 0`, the defaults always carry a real number; keep that.

- [ ] **Step 8: The direct factory resolves once, logs once, and loses `vendor()`**

In `DefaultDirectHarnessFactory`:
- replace the `provider` field with `private final ProviderRegistry.Resolved providers;`, `private final ProviderId defaultProvider;`, `private final InferenceOptions defaultOptions;`, set in the constructor from the config (`config.providers().observed(observations)` — after `observations` is assigned).
- delete `vendor()` (and its javadoc) and delete `String vendor();` from `DirectHarnessFactory` in nessy-api.
- in `create`, replace the model check and the `ObservedInferenceProvider.wrap(provider, observations)` argument:

```java
    DefaultDirectHarnessConfig.Inference inference = config.inference();
    ProviderId providerId = providers.choose(agentType, inference.provider(), null);
    if (inference.modelName() == null) {
      throw new IllegalStateException(
          "agent type '" + agentType.value() + "' names no model and the factory has no default");
    }
    log.info(
        "NESSY INFERENCE: agent type '{}' -> {} / {}, up to {} tokens",
        agentType.value(),
        providerId.value(),
        inference.modelName(),
        inference.maxTokens());
```

  and pass `providers.resolve(agentType, providerId, null)` where `ObservedInferenceProvider.wrap(provider, observations)` was. The default is already folded into `inference.provider()` by step 7, so `choose` gets `null` as its fallback here. Add `private static final Logger log = LoggerFactory.getLogger(DefaultDirectHarnessFactory.class);` (slf4j, as `InferenceHandler` does). Remove the now-unused `ObservedInferenceProvider` import if nothing else uses it.

- [ ] **Step 9: The queued door, the same way**

In `QueuedHarnessFactoryConfig`: add a `ProviderRegistry providers` field and `provider(ProviderId, InferenceProvider)` with the same javadoc as step 6; replace `inference(InferenceProvider, InferenceOptions)` with `inference(ProviderId provider, InferenceOptions options)` (same javadoc as step 6); replace `requiredProvider()`/`requiredOptions()` with nullable readers `defaultProvider()`, `defaultOptions()` and `providers()`.

In `DefaultQueuedHarnessConfig`: `record Defaults(@Nullable ProviderId provider, @Nullable InferenceOptions options)`; `Inference` gets a `ProviderId provider` field initialised from `defaults.provider()`, a `provider(ProviderId)` override, and `modelName`/`maxTokens` initialised from `defaults.options()` only when it is non-null (maxTokens otherwise 4096, matching the direct door). `provider()` returns the `ProviderId`; `options()` is only called after the factory checked the model.

In `DefaultQueuedHarnessFactory`: hold `ProviderRegistry.Resolved providers = config.providers().observed(observations)`; build `Defaults` from the nullable readers; in `create` (or wherever the `Inference` is read before `createInferenceHandler`), choose the id, check the model with the same message as step 8, log the same line, and pass `providers.resolve(agentType, inference.provider(), null)` into `DefaultInferenceService` in place of `ObservedInferenceProvider.wrap(inference.provider(), observations)`. `createInferenceHandler` takes the resolved `InferenceProvider` as a parameter.

- [ ] **Step 10: Move every caller**

Compile the reactor and fix each error: `./mvnw -q -am -pl :nessy-engine,:nessy-console,:nessy-spring-boot-autoconfigure,:chat-cli test-compile` (check chat-cli's artifactId in its pom first).
- Engine tests: `.provider(model)` → `.provider(ProviderId.of("test"), model).inference(ProviderId.of("test"), InferenceOptions.of("a-model"))` when the test never set a model on the harness, otherwise just `.provider(ProviderId.of("test"), model)` plus `in.provider("test")` in the harness's `inference(...)` customizer. Prefer the factory-default form: it keeps each test's diff to one line. `EngineFixture`'s `.inference(provider, InferenceOptions.of("a-model"))` → `.provider(ProviderId.of("test"), provider).inference(ProviderId.of("test"), InferenceOptions.of("a-model"))`.
- `DirectHarnessAutoConfiguration` and `QueuedHarnessAutoConfiguration`: replace the `InferenceProvider models` parameter with `ListableBeanFactory beans` and `Environment environment`; register `beans.getBeansOfType(InferenceProvider.class).forEach((name, p) -> config.provider(ProviderId.of(name), p))`; call `inference(ProviderId.of(provider), new InferenceOptions(model, properties.maxTokens()))` only when `environment.getProperty("nessy.provider")` and `properties.model()` are both non-blank. Delete `requireModel`. (Task 4 replaces the environment read with `NessyProperties.provider()` and adds the both-or-neither rule.)
- `Repl.run(ReplConfig, ConsoleIo)`: replace `context.getBean(InferenceProvider.class)` with `context.getBeansOfType(InferenceProvider.class)`; if empty, say `no provider is configured: export a vendor key such as OPENAI_API_KEY`; read `nessy.provider` from the environment; if it is blank, say `no provider is chosen: set NESSY_PROVIDER to one of <sorted bean names>`; register every bean by name and pass the chosen id and model as the factory default (`.inference(ProviderId.of(chosen), new InferenceOptions(model, config.maxTokens()))`). `ReplLoop.Diagnostics`' `provider` becomes the chosen id's value, passed from where `factory.vendor()` was called.
- `Chat.java` (chat-cli): take `Map<String, InferenceProvider> providers` (Spring injects every bean by name) instead of one `InferenceProvider`, register each by name, and set the factory default from `nessy.provider`/`nessy.model` as the harness auto-configurations do.

- [ ] **Step 11: Pin the doors' behaviour**

Add to `DefaultDirectHarnessTest` (follow the file's existing fixture style for building a factory and asking a question), each as its own test:
- `an_agent_type_naming_a_registered_id_is_answered_by_that_provider`: register `first` and `second` as lambdas answering "first"/"second"; the harness says `in.provider("second").model("m")`; the answer is `"second"`.
- `an_agent_type_naming_nothing_is_answered_by_the_default`: same two, factory `inference(ProviderId.of("first"), InferenceOptions.of("m"))`, harness says nothing; the answer is `"first"`.
- `an_agent_type_naming_an_unknown_provider_fails_when_the_harness_is_built`: assert the message equals `agent type 'chat' names provider 'claude', which is not registered; registered: [first, second]` (use the test's own agent type name).
- `no_model_and_no_default_fails_naming_the_agent_type`: harness says `in.provider("first")` only; message `agent type '<type>' names no model and the factory has no default`.
Add the same four to the queued door's main test class. Build factory, customizer and ids outside every `assertThatThrownBy` lambda.

- [ ] **Step 12: Run the engine, console and Boot modules**

Run: `./mvnw -q -pl :nessy-engine,:nessy-console,:nessy-spring-boot-autoconfigure -am test`
Expected: exit 0.

- [ ] **Step 13: Format, verify, commit**

```bash
./mvnw -q spotless:apply license:format && ./mvnw -q clean verify
git add -A
git commit -m "feat: a factory holds its providers by name, and an agent type says which"
```

---

### Task 4: Boot presets, the registrar, and the report

Suggested implementer model: Sonnet; reviewer: Opus (bean-registration ordering and binding are where a plausible diff is quietly wrong).

**Files:**
- Create in `nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/inference/`:
  - `Wire.java` — package-private enum
  - `ProviderSettings.java` — package-private record bound from `nessy.providers.<id>`
  - `Preset.java` — package-private record, plus the catalogue as `static final List<Preset> CATALOGUE`
  - `ResolvedProvider.java` — package-private record
  - `ProviderCatalogue.java` — package-private; pure resolution, no Spring context
  - `WireProviders.java` — package-private; builds an `InferenceProvider` for a wire, class-loading each adapter only when present
  - `ProviderRegistrar.java` — `BeanDefinitionRegistryPostProcessor`
  - `InferenceProvidersAutoConfiguration.java` — public `@AutoConfiguration` declaring the registrar as a `static @Bean`
- Delete: `OpenAiAutoConfiguration.java`, `AnthropicAutoConfiguration.java`, `GeminiAutoConfiguration.java` and their three tests (their assertions move to the new tests)
- Modify: `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (three lines out, one in, placed where the three were)
- Modify: `NessyProperties.java` (add `String provider`), `InferenceReport.java` (rewrite), `NessyAutoConfiguration.java` (report bean wiring), `DirectHarnessAutoConfiguration.java`, `QueuedHarnessAutoConfiguration.java` (defaults from `NessyProperties`, both-or-neither)
- Keep `TransportTimeouts.java`
- Test: `inference/ProviderCatalogueTest.java`, `inference/InferenceProvidersAutoConfigurationTest.java`, `InferenceReportTest.java` (create or extend whatever exists)

**Interfaces:**
- Consumes: `ProviderId` (Task 2); `OpenAiProviderConfig.vendor(String)` (Task 1); the harness auto-configurations' "register every `InferenceProvider` bean by name" (Task 3).
- Produces: bean names equal to preset/custom ids; property `nessy.provider`; properties `nessy.providers.<id>.{api-key,enabled,wire,base-url,vendor}`; wire values `chat-completions`, `messages`, `generate-content`; a singleton bean `nessyResolvedProviders` of type `ResolvedProviders` (package-private record wrapping `List<ResolvedProvider>`) for the report.

- [ ] **Step 1: Write the catalogue's failing test**

`ProviderCatalogueTest` exercises `ProviderCatalogue.resolve(Map<String, ProviderSettings> settings, Function<String, String> property)` where `property` reads a plain property (e.g. `Map.of("xai.api-key", "k")::get`). One test per row:

| test | settings / properties | expected resolved list (id, wire, baseUrl, vendor, apiKey) |
|---|---|---|
| `nothing_set_lights_nothing` | none | empty |
| `an_openai_key_lights_openai` | `openai.api-key=k` | `(openai, CHAT_COMPLETIONS, null, "openai", k)` |
| `an_xai_key_lights_xai_at_its_own_url` | `xai.api-key=k` | `(xai, CHAT_COMPLETIONS, "https://api.x.ai/v1", "x_ai", k)` |
| `every_key_lights_its_own_preset` | openai, anthropic, gemini keys | three, in catalogue order `openai, xai, anthropic, gemini, lmstudio` minus the unlit |
| `either_gemini_key_lights_one_gemini` | `gemini.api-key=a`, `google.api-key=b` | ONE `gemini`, apiKey `a` |
| `a_blank_key_does_not_light_a_preset` | `openai.api-key=" "` | empty |
| `a_prefixed_key_lights_the_preset` | settings `xai -> apiKey=k` | `xai` with `k` |
| `the_openai_base_url_property_overrides_the_openai_preset` | `openai.api-key=k`, `openai.base-url=http://localhost:1234/v1` | `openai` at that URL |
| `a_setting_overrides_a_preset_field` | `anthropic.api-key=k`, settings `anthropic -> baseUrl=https://proxy/v1` | anthropic at the proxy |
| `a_custom_provider_needs_a_wire_and_a_url` | settings `mine -> wire=CHAT_COMPLETIONS, baseUrl=https://g/v1, apiKey=k` | `(mine, CHAT_COMPLETIONS, https://g/v1, "openai", k)` |
| `a_custom_provider_without_a_wire_fails_naming_it` | settings `mine -> baseUrl=https://g/v1` | `IllegalStateException` message `nessy.providers.mine.wire is required: mine is not a preset` |
| `a_custom_provider_without_a_url_fails_naming_it` | settings `mine -> wire=CHAT_COMPLETIONS` | message `nessy.providers.mine.base-url is required: mine is not a preset` |
| `a_custom_vendor_defaults_to_the_wires_own` | custom `messages` wire, no vendor | vendor `anthropic` |

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test -Dtest=ProviderCatalogueTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 3: Write the catalogue types**

```java
enum Wire {
  CHAT_COMPLETIONS("openai"),
  MESSAGES("anthropic"),
  GENERATE_CONTENT("gcp.gemini");

  private final String defaultVendor;

  Wire(String defaultVendor) {
    this.defaultVendor = defaultVendor;
  }

  String defaultVendor() {
    return defaultVendor;
  }
}
```

```java
/** What {@code nessy.providers.<id>} says. Every field optional; a preset fills the gaps. */
record ProviderSettings(
    @Nullable Wire wire,
    @Nullable String baseUrl,
    @Nullable String apiKey,
    @Nullable Boolean enabled,
    @Nullable String vendor) {}
```

```java
/**
 * A provider Nessy knows how to reach, waiting for its ingredient: a key for a hosted vendor, an
 * explicit {@code enabled} for one running on this machine.
 */
record Preset(
    String id,
    Wire wire,
    @Nullable String baseUrl,
    String vendor,
    List<String> keyProperties,
    @Nullable String keylessApiKey) {

  static final List<Preset> CATALOGUE =
      List.of(
          new Preset("openai", Wire.CHAT_COMPLETIONS, null, "openai", List.of("openai.api-key"), null),
          new Preset("xai", Wire.CHAT_COMPLETIONS, "https://api.x.ai/v1", "x_ai", List.of("xai.api-key"), null),
          new Preset("anthropic", Wire.MESSAGES, null, "anthropic", List.of("anthropic.api-key"), null),
          new Preset("gemini", Wire.GENERATE_CONTENT, null, "gcp.gemini", List.of("gemini.api-key", "google.api-key"), null));

  boolean keyless() {
    return keylessApiKey != null;
  }
}
```

The `lmstudio` row, and its tests, are added by Task 5 once it is measured. The keyless branch (`keylessApiKey != null`) is written here and first exercised there.

```java
record ResolvedProvider(
    String id, Wire wire, @Nullable String baseUrl, String vendor, @Nullable String apiKey) {}
```

`ProviderCatalogue.resolve`:

```java
static List<ResolvedProvider> resolve(
    Map<String, ProviderSettings> settings, Function<String, @Nullable String> property) {
  List<ResolvedProvider> lit = new ArrayList<>();
  for (Preset preset : Preset.CATALOGUE) {
    ProviderSettings own = settings.getOrDefault(preset.id(), EMPTY);
    // Candidates in order of precedence; the first that is non-null and non-blank wins.
    List<String> keys = new ArrayList<>();
    keys.add(own.apiKey());
    preset.keyProperties().forEach(name -> keys.add(property.apply(name)));
    String apiKey = firstNonBlank(keys);
    boolean on = preset.keyless() ? Boolean.TRUE.equals(own.enabled()) : apiKey != null;
    if (!on) {
      continue;
    }
    List<String> urls = new ArrayList<>();
    urls.add(own.baseUrl());
    if ("openai".equals(preset.id())) {
      urls.add(property.apply("openai.base-url"));
    }
    urls.add(preset.baseUrl());
    String baseUrl = firstNonBlank(urls);
    lit.add(
        new ResolvedProvider(
            preset.id(),
            own.wire() != null ? own.wire() : preset.wire(),
            baseUrl,
            own.vendor() != null ? own.vendor() : preset.vendor(),
            preset.keyless() ? preset.keylessApiKey() : apiKey));
  }
  settings.forEach(
      (id, own) -> {
        if (Preset.CATALOGUE.stream().noneMatch(p -> p.id().equals(id))) {
          lit.add(custom(id, own));
        }
      });
  return List.copyOf(lit);
}
```

`EMPTY` is `new ProviderSettings(null, null, null, null, null)`. Write the helpers (`static @Nullable String firstNonBlank(List<@Nullable String>)` treating blank as absent, `custom` enforcing the two messages from step 1, vendor defaulting to `wire.defaultVendor()`) so the tests pass; the snippet above fixes the rules, not the helper signatures — keep them private and simple. Custom providers are appended in the settings map's iteration order.

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q -pl :nessy-spring-boot-autoconfigure test -Dtest=ProviderCatalogueTest`
Expected: all rows pass.

- [ ] **Step 5: Build a provider per wire without loading absent adapters**

`WireProviders.build(ResolvedProvider r, @Nullable JsonMapper mapper)` returns `Optional<InferenceProvider>`: empty when the wire's adapter class is not on the classpath (`ClassUtils.isPresent("org.jwcarman.nessy.inference.openai.OpenAiInferenceProvider", classLoader)` and the Anthropic/Gemini equivalents). Put each adapter's construction in its own private static nested class (`ChatCompletions`, `Messages`, `GenerateContent`) so the adapter class is only loaded when that nested class is first used. Each builds exactly what today's auto-configurations built: `apiKey`, `baseUrl` when non-null, `timeout(TransportTimeouts.PROVIDER_TRANSPORT)`, `mapper` when non-null, and for chat-completions `.vendor(r.vendor())`. (Anthropic and Gemini report their own fixed vendor; a `vendor` override on those wires is ignored — note it in `ProviderSettings`' javadoc.)

- [ ] **Step 6: Write the registrar**

`ProviderRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, BeanFactoryAware`:
- `postProcessBeanDefinitionRegistry(registry)`: bind `Binder.get(environment).bind("nessy.providers", Bindable.mapOf(String.class, ProviderSettings.class)).orElse(Map.of())`; resolve with `environment::getProperty`; for each resolved provider:
  - if `registry.containsBeanDefinition(r.id())`: throw `IllegalStateException("a bean named '" + id + "' and the " + id + " provider would both be registered as '" + id + "'; rename the bean or unset the provider's key")`
  - if the wire's adapter is absent: log INFO `NESSY INFERENCE: <id> is configured but <artifactId> is not on the classpath; skipped` and continue
  - else register a `RootBeanDefinition(InferenceProvider.class, supplier)` under `r.id()`, where the supplier builds via `WireProviders` with `beanFactory.getBeanProvider(JsonMapper.class).getIfAvailable()` and wraps with `ObservedInferenceProvider.wrap(provider, beanFactory.getBeanProvider(ObservationRegistry.class).getIfAvailable(() -> ObservationRegistry.NOOP))`.
- register a singleton `nessyResolvedProviders` holding the list of registered (not skipped) `ResolvedProvider`s for the report.
- `postProcessBeanFactory`: nothing.

If `Binder` cannot bind the package-private `ProviderSettings` (a binding failure naming the class), STOP and report back rather than making the record public.

`InferenceProvidersAutoConfiguration`:

```java
@AutoConfiguration
public class InferenceProvidersAutoConfiguration {

  @Bean
  static ProviderRegistrar nessyProviderRegistrar() {
    return new ProviderRegistrar();
  }
}
```

- [ ] **Step 7: Write the auto-configuration matrix test**

`InferenceProvidersAutoConfigurationTest`, using `ApplicationContextRunner` with `AutoConfigurations.of(InferenceProvidersAutoConfiguration.class)` and an `ObservationRegistry` bean, one test per row of spec §12a EXCEPT the three harness-build rows (`nessy.provider=claude`, `nessy.provider=xai`, none-and-build) which go in step 9's test. Assert with `context.getBeansOfType(InferenceProvider.class)`: the key set, `isInstanceOf(ObservedInferenceProvider.class)` for each (non-empty first), and `vendor()` where the row names a vendor. Plus the Review Focus rows:
- `a_bean_whose_name_is_not_a_provider_id_fails_naming_the_bean` — this one belongs to the harness auto-configurations (they turn bean names into ids): user bean named `"my provider"` via `withBean("my provider", InferenceProvider.class, ...)`; context fails; the failure message contains `my provider`. If `ProviderId`'s own message does not name the bean, catch `IllegalArgumentException` where the harness auto-configurations call `ProviderId.of(name)` and rethrow `IllegalStateException("the InferenceProvider bean '" + name + "' cannot be a provider id: " + e.getMessage(), e)`. Put this test in step 9's test class.
- `a_blank_key_does_not_light_a_preset` — `openai.api-key=` → no beans.
- `a_lit_preset_whose_adapter_is_absent_is_skipped` — `.withClassLoader(new FilteredClassLoader(AnthropicInferenceProvider.class))`, `anthropic.api-key=k` → no `anthropic` bean, context starts.
- `the_environment_variable_form_of_a_prefixed_key_lights_the_preset` — add a `SystemEnvironmentPropertySource` with `NESSY_PROVIDERS_XAI_APIKEY=k` via `withInitializer` (the probe in the spec did the same for `NESSY_PROVIDER`) → `xai` bean. If this fails, the property name must be documented as `NESSY_PROVIDERS_XAI_APIKEY` (no underscore inside `APIKEY`) — find the spelling that binds and assert THAT one; record it for Task 7's docs.
- `a_bean_named_like_a_lit_preset_fails` — user bean `openai` plus `openai.api-key=k` → context fails, message contains `'openai'`.

- [ ] **Step 8: Remove the three vendor auto-configurations**

Delete the three classes and their tests; replace their three lines in the `.imports` file with `org.jwcarman.nessy.spring.boot.inference.InferenceProvidersAutoConfiguration`. Move any assertion from the deleted tests not already covered (the xAI vendor, the base-URL override, the observed wrapping, the no-registry-means-NOOP case) into `InferenceProvidersAutoConfigurationTest`.

- [ ] **Step 9: `nessy.provider`, both-or-neither, and the harness-build rows**

Add `String provider` to `NessyProperties` (after `type`, with javadoc: `The provider an agent type is answered by when it names none. Set with nessy.model, or not at all.`). In both harness auto-configurations replace step-10-of-Task-3's environment read with `properties.provider()`, and fail at factory creation when exactly one of `provider`/`model` is set:

```java
throw new IllegalStateException(
    "nessy.provider and nessy.model are a pair: set both, or neither and name them on each"
        + " agent type (nessy.provider="
        + provider
        + ", nessy.model="
        + model
        + ")");
```

Test class (extend the existing `NessyAutoConfigurationTest` or the harness auto-configuration test the module already has — find with `grep -rl "DirectHarnessAutoConfiguration" nessy-spring-boot/autoconfigure/src/test`): `setting_only_one_of_provider_and_model_fails_naming_both`; `nessy_provider_names_the_default_a_harness_gets` (`xai.api-key`, `nessy.provider=xai`, `nessy.model=m`: create a harness naming nothing, it builds); `a_default_that_is_not_registered_fails_listing_what_is` (`openai.api-key`, `nessy.provider=claude`, `nessy.model=m`: creating a harness fails with a message containing `registered: [openai]`); `an_application_bean_is_registered_beside_the_presets`; `a_bean_whose_name_is_not_a_provider_id_fails_naming_the_bean`.

- [ ] **Step 10: Rewrite the report**

`InferenceReport` takes `ResolvedProviders` (optional — `ObjectProvider`) and `ListableBeanFactory`. At `afterSingletonsInstantiated`:
- no `InferenceProvider` beans: `log.warn("NESSY INFERENCE: no provider is configured")`, return.
- otherwise one INFO line: `NESSY INFERENCE: providers: ` + for each bean in bean-name order: `<id> (<wire>, <endpoint>, vendor <vendor>)` for a resolved preset/custom (wire printed as its property value, e.g. `chat-completions`; endpoint `the vendor's own endpoint` when `baseUrl` is null), `<id> (vendor <vendor>)` for an application bean; joined with `; `. Never the key.
- delete `KEYS` and the multi-key warning.
Update `NessyAutoConfiguration`'s report bean method to the new constructor. Test: an `InferenceReportTest` with `OutputCaptureExtension` (Boot's) asserting the providers line for `openai.api-key` + `xai.api-key`, that the output never contains the key value, and the warning when nothing is lit.

- [ ] **Step 11: Format, verify, commit**

```bash
./mvnw -q spotless:apply license:format && ./mvnw -q clean verify
git add -A
git commit -m "feat: presets light providers by their key, and every one is registered"
```

---

### Task 5: The `lmstudio` preset, measured

Suggested implementer model: Sonnet. Requires LM Studio listening on `http://localhost:1234/v1` (it is, as of 2026-09-29: `curl -s localhost:1234/v1/models` lists `qwen/qwen3.6-35b-a3b`).

**Files:**
- Modify: `Preset.java` (one row)
- Modify: `ProviderCatalogueTest.java`, `InferenceProvidersAutoConfigurationTest.java` (the lmstudio rows)
- Create: `nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/inference/LmStudioPresetLiveTest.java`, tagged `@Tag("live")`

- [ ] **Step 1: Write the measuring test**

A `@Tag("live")` test that builds the `lmstudio` provider through the catalogue (`nessy.providers.lmstudio.enabled=true` in an `ApplicationContextRunner`), then drives a real direct harness (`DefaultDirectHarnessFactory` with `InMemoryDirectBackend`, as `Repl` does) with model `qwen/qwen3.6-35b-a3b` and one tool (`DaysUntilTool`-style: a record input, returns a number), asking a question that needs the tool. Assert: the turn answered, the tool was called at least once (count calls in the tool), and `Usage` on the answer is not `Usage.unreported()`. Second test: a `TurnPolicy` of `calls(1, 2)` so the second model call is `ToolChoice.Answer`; assert the turn still answers. Read `DirectHarnessLiveTest` in nessy-engine first and follow its shape.

- [ ] **Step 2: Add the row and run the measurement**

Add to `CATALOGUE`, last: `new Preset("lmstudio", Wire.CHAT_COMPLETIONS, "http://localhost:1234/v1", "lmstudio", List.of(), "lm-studio")`. Add the two catalogue tests (`lmstudio_is_off_until_enabled`, `lmstudio_lights_when_enabled`) and the auto-configuration rows (`nessy.providers.lmstudio.enabled=true` → `lmstudio` bean; nothing → absent).

Run: `./mvnw -q -pl :nessy-spring-boot-autoconfigure test -Dnessy.excludedGroups= -Dtest=LmStudioPresetLiveTest`
Expected: both pass. If either fails, remove the row, keep the failing test out of the commit, and report exactly what LM Studio returned.

- [ ] **Step 3: Format, verify (live excluded), commit**

```bash
./mvnw -q spotless:apply license:format && ./mvnw -q clean verify
git add -A
git commit -m "feat: LM Studio is a preset, turned on by name"
```

---

### Task 6: Examples

Suggested implementer model: Sonnet.

**Files:** `nessy-examples/chat-web/src/main/resources/application.yml`, `ChatConfiguration.java` (summariser provider), `nessy-examples/watchman/src/main/resources/application.yml` and whatever declares `ScriptedWatchmanProvider`, `nessy-examples/chat-cli` (already moved in Task 3; check its README/yml), `nessy-examples/mcp`, `nessy-examples/policy` (non-Spring: `grep -rn "provider(" nessy-examples/*/src/main`), `nessy-console/README.md`.

- [ ] **Step 1: Find every example's provider wiring**

Run: `grep -rn -E "InferenceProvider|openai\.|OPENAI_|nessy\.model|NESSY_MODEL|\.provider\(" nessy-examples nessy-console --include='*.java' --include='*.yml' --include='*.md' | grep -v /target/`

- [ ] **Step 2: Move each**

- chat-web and watchman point `openai.base-url` at LM Studio today: switch them to `nessy.providers.lmstudio.enabled: true`, `nessy.provider: ${CHAT_PROVIDER:lmstudio}` (watchman: `${WATCHMAN_PROVIDER:lmstudio}`), keep their model properties.
- chat-web's `ChatConfiguration` summariser: inject `Map<String, InferenceProvider> providers` and `NessyProperties properties`, and hand the summariser `providers.get(properties.provider())` (fail with a clear message if absent).
- watchman's scripted mode: its scripted provider bean joins the registry under its bean name; when scripted mode is on, the yml (or a profile) sets `nessy.provider` to that bean name. Read how `watchman.scripted` switches today and keep the switch a single property.
- Non-Spring examples: `provider(ProviderId.of("<vendor>"), ...)` + `inference(ProviderId.of("<vendor>"), InferenceOptions.of(...))`.

- [ ] **Step 3: Verify each example still starts (no network)**

Run the examples' own tests: `./mvnw -q -pl :chat-web,:watchman,:chat-cli -am test` (check artifactIds in each pom first). Then the full gate.

- [ ] **Step 4: Format, verify, commit**

```bash
./mvnw -q spotless:apply license:format && ./mvnw -q clean verify
git add -A
git commit -m "chore: the examples name the provider they use"
```

---

### Task 7: Docs

Dispatch the `docs-writer` agent (Sonnet), then a `task-reviewer`.

**Files:** `docs/guides/providers.md`, `docs/guides/spring-boot.md`, `docs/guides/observability.md`, `docs/guides/getting-started.md` (if it teaches `OPENAI_BASE_URL` for LM Studio), `README.md`, `nessy-console/README.md`, and `mkdocs.yml` only if a page is added.

- [ ] **Step 1: Write**

Describe what is: providers are registered by id; presets and their ingredients (table: id, wire value, base URL, vendor, ingredient) for exactly the rows in `Preset.CATALOGUE`; `nessy.providers.<id>.*` fields; custom providers; application beans join by bean name; `nessy.provider` + `nessy.model` as a pair; every agent type has a provider and a model; `InferenceConfig.provider(...)`; the non-Spring registration code from spec §8; the env-var spelling found in Task 4 step 7; the startup report lines. No history, no "previously", no roads not taken.

- [ ] **Step 2: Check every code sample against the code**

Each snippet's method names must exist: `grep` each one.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "docs: providers are named, and presets light them"
```

---

## Final gate

After Task 7: `./mvnw -q clean verify -Dnessy.excludedGroups=live` (runs the container tests too), then the `final-reviewer` agent (Opus) over `git diff main...named-providers`.
