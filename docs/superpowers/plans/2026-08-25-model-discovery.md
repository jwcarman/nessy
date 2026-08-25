# Model Provider Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `nessy-model-env`'s hard dependency on every provider module with `ServiceLoader` discovery of a new SPI type, so a hello-world agent compiles against one provider's SDK instead of four.

**Architecture:** `ModelProviderBootstrap` (in `nessy-spi`) is implemented and registered by each provider module whose credential presence signals intent — Anthropic, OpenAI (twice: `openai` and `xai`), Gemini. Bedrock registers nothing. A new module `nessy-model-discovery` loads every registration, bootstraps each against the environment, and resolves none/one/many with fail-fast on ambiguity. `nessy-model-env` is deleted once its three callers move.

**Tech Stack:** Java 25, Maven reactor, `java.util.ServiceLoader`, JUnit 6 + AssertJ, no mocking library (hand-written fakes only).

**Spec:** `docs/superpowers/specs/2026-08-25-model-discovery-design.md` — read it first; every task below argues from it.

## Global Constraints

- **Never suppress warnings.** No `@SuppressWarnings` of any kind. Fix the cause.
- **No star imports**, in any form.
- Exception-assertion lambdas contain **exactly one** invocation that can throw; all setup — construction, lookups — goes outside the lambda (Sonar S5778).
- Assert non-emptiness **before** any `allSatisfy` / `allMatch` / `noneMatch`-style predicate on the same collection (S5841).
- **No `module-info.java`.** The project is anti-JPMS; `ServiceLoader` works from the classpath services file alone. Every jar sets `Automatic-Module-Name` in its manifest instead.
- **No `System.getenv()` inside any `ModelProviderBootstrap.bootstrap(env)`** — read only the map handed in (spec §3).
- **Every `ModelProviderBootstrap` implementation is `public final` with a public no-arg constructor** — `ServiceLoader` cannot instantiate it otherwise.
- `nessy-model-discovery` depends on `nessy-spi` **alone** at compile scope (spec §7). No SLF4J, no provider modules.
- Test naming voice in the modules this plan touches is **snake_case** (`chooses_the_anthropic_provider`), matching their existing tests.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`.
- One Maven process at a time. Iterate with warm scoped builds (`./mvnw -q -pl <module> -am test`, no `clean`); run the full `./mvnw -q clean verify` **once** per task, before its final commit.
- The reactor must be green at the end of every task. Task 3 adds the new module while `nessy-model-env` still exists; Task 4 deletes the old one. Do not delete early.
- Exact vocabulary tokens: `anthropic`, `openai`, `xai`, `gemini`. Exact env vars: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `XAI_API_KEY`, `GEMINI_API_KEY`, `GOOGLE_API_KEY`, `NESSY_PROVIDER`, `NESSY_MODEL`. Exact default model ids: `claude-haiku-4-5-20251001`, `gpt-4o-mini`, `grok-4.6`, `gemini-3.6-flash`. xAI base URL: `https://api.x.ai/v1`.

---

## File map

| Task | Creates | Modifies | Deletes |
|---|---|---|---|
| 1 | `nessy-spi/.../spi/model/ModelProviderBootstrap.java`; `nessy-model-anthropic/.../AnthropicModelProviderBootstrap.java` + test + services file | `nessy-spi/.../spi/model/ModelProvider.java` (javadoc) | — |
| 2 | `nessy-model-openai/.../OpenAiModelProviderBootstrap.java`, `XaiModelProviderBootstrap.java` + tests + services file; `nessy-model-gemini/.../GeminiModelProviderBootstrap.java` + test + services file | — | — |
| 3 | `nessy-model-discovery/` (pom, `ModelDiscovery.java`, `package-info.java`, tests, test services file) | `pom.xml` (module list), `nessy-bom/pom.xml` | — |
| 4 | — | `nessy-examples/hello/pom.xml` + `Hello.java`; `nessy-examples/approvals/pom.xml` + `Approvals.java`; `nessy-agent/pom.xml`, `ApprovalPlayground.java`, `CliLiveSmokeTest.java`; `nessy-api/.../NoPublicBuildersTest.java`; `pom.xml`; `nessy-bom/pom.xml` | `nessy-model-env/` entirely |
| 5 | `nessy-model-discovery/README.md` | `docs/guides/providers.md`, `README.md`, `docs/guides/getting-started.md`, `docs/index.md`, `docs/guides/harness.md`, `nessy-model-bedrock/README.md`, `CHANGELOG.md`, three amended specs | — |

---

### Task 1: `ModelProviderBootstrap`, and Anthropic registers

**Files:**
- Create: `nessy-spi/src/main/java/org/jwcarman/nessy/spi/model/ModelProviderBootstrap.java`
- Modify: `nessy-spi/src/main/java/org/jwcarman/nessy/spi/model/ModelProvider.java:39-46` (javadoc only)
- Create: `nessy-model-anthropic/src/main/java/org/jwcarman/nessy/model/anthropic/AnthropicModelProviderBootstrap.java`
- Create: `nessy-model-anthropic/src/main/resources/META-INF/services/org.jwcarman.nessy.spi.model.ModelProviderBootstrap`
- Test: `nessy-model-anthropic/src/test/java/org/jwcarman/nessy/model/anthropic/AnthropicModelProviderBootstrapTest.java`

**Interfaces:**
- Consumes: `AnthropicModelProvider.create(AnthropicProviderCustomizer)` and `AnthropicProviderConfig.apiKey(String)` — both exist today.
- Produces: `org.jwcarman.nessy.spi.model.ModelProviderBootstrap` with exactly `String name()`, `Set<String> environmentVariables()`, `String defaultModelId()`, `Optional<ModelProvider> bootstrap(Map<String, String> env)`. Tasks 2 and 3 implement and consume it by these exact signatures.

- [ ] **Step 1: Write the SPI interface**

```java
package org.jwcarman.nessy.spi.model;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A provider that builds itself from the environment, discovered through {@link
 * java.util.ServiceLoader}.
 *
 * <p>A provider module implements this interface only when <strong>the presence of its
 * credentials in the environment signals intent to use it</strong>. {@code ANTHROPIC_API_KEY} in
 * an environment means someone meant to talk to Anthropic. Ambient AWS credentials mean someone
 * once deployed something to AWS. The test is not "can this provider be built from the
 * environment" — it is "does the credential's presence mean the user chose this provider". A
 * provider whose credentials are ambient does not implement this interface; its users construct
 * it explicitly. Implementing the interface <em>is</em> the declaration: there is no way to
 * register for discovery while asking not to be discovered, so there is no flag to set wrong.
 *
 * <p>Registration is the standard services file, {@code
 * META-INF/services/org.jwcarman.nessy.spi.model.ModelProviderBootstrap}, in the provider
 * module's main resources. Implementations are {@code public final} with a public no-arg
 * constructor, stateless, and cheap to construct — {@code ServiceLoader} instantiates them on
 * every discovery.
 *
 * <p>Discovery never depends on {@code ServiceLoader}'s iteration order, which is
 * classpath-dependent: two providers that both bootstrap is an error resolved by {@code
 * NESSY_PROVIDER}, never by position.
 */
public interface ModelProviderBootstrap {

  /**
   * The vocabulary token {@code NESSY_PROVIDER} accepts for this provider, e.g. {@code
   * "anthropic"}. Lowercase, non-blank, and unique across every registration on a classpath — a
   * duplicate is a configuration error and fails at discovery.
   */
  String name();

  /**
   * Every environment variable {@link #bootstrap} reads, optional ones included, so a
   * no-credentials failure can say exactly what was checked.
   */
  Set<String> environmentVariables();

  /** The model id used when {@code NESSY_MODEL} is unset or blank. */
  String defaultModelId();

  /**
   * A gateway built from {@code env}, or empty when — and only when — this provider's credentials
   * are absent from it.
   *
   * <p>Reads only {@code env}, never {@link System#getenv()}, so the choice discovery makes from
   * the map it was handed is the choice that gets built. Must not throw for absent credentials.
   * May throw for present-but-malformed configuration: a present key is intent, and intent that
   * cannot be honoured is an error, not a silent skip.
   *
   * @throws NullPointerException if {@code env} is null
   */
  Optional<ModelProvider> bootstrap(Map<String, String> env);
}
```

- [ ] **Step 2: Update `ModelProvider.name()`'s javadoc**

Replace lines 41-45 of `ModelProvider.java` (the paragraph beginning "Direct-wired applications") with:

```java
   * <p>Direct-wired applications (one provider module, constructed explicitly) read this.
   * Applications built on {@code ModelDiscovery.select()} should prefer that method's {@code
   * Selection.providerName()} instead: it is the registered {@link ModelProviderBootstrap#name()}
   * of whichever provider bootstrapped (e.g. {@code "xai"} for an xAI key), where this default
   * falls back to the concrete class name, which for xAI is the shared {@code OpenAiModelProvider}
   * class — not the vendor the key named.
```

- [ ] **Step 3: Write the failing Anthropic bootstrap test**

```java
package org.jwcarman.nessy.model.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;

/**
 * Drives the bootstrap through its env-map argument only — no real environment variable, no
 * network. Construction of {@link AnthropicModelProvider} from a fake key is offline, as the
 * retired {@code EnvModelProvidersTest} already relied on.
 */
class AnthropicModelProviderBootstrapTest {

  private final AnthropicModelProviderBootstrap bootstrap = new AnthropicModelProviderBootstrap();

  @Test
  void is_named_anthropic() {
    assertThat(bootstrap.name()).isEqualTo("anthropic");
  }

  @Test
  void reads_only_the_anthropic_api_key() {
    assertThat(bootstrap.environmentVariables()).containsExactly("ANTHROPIC_API_KEY");
  }

  @Test
  void defaults_to_haiku() {
    assertThat(bootstrap.defaultModelId()).isEqualTo("claude-haiku-4-5-20251001");
  }

  @Test
  void is_empty_when_the_key_is_absent() {
    assertThat(bootstrap.bootstrap(Map.of("OPENAI_API_KEY", "not-mine"))).isEmpty();
  }

  @Test
  void builds_an_anthropic_provider_when_the_key_is_present() {
    var provider = bootstrap.bootstrap(Map.of("ANTHROPIC_API_KEY", "fake-anthropic-key"));

    assertThat(provider).containsInstanceOf(AnthropicModelProvider.class);
  }

  @Test
  void rejects_a_null_env() {
    assertThatThrownBy(() -> bootstrap.bootstrap(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void is_registered_for_service_loading() {
    var registered =
        ServiceLoader.load(ModelProviderBootstrap.class).stream()
            .map(ServiceLoader.Provider::type)
            .toList();

    assertThat(registered).containsExactly(AnthropicModelProviderBootstrap.class);
  }
}
```

The last test is the one that matters most: the services file is a resource, and a typo in it fails at runtime with no compiler to notice. `containsExactly` also pins that this module registers one bootstrap and no more.

- [ ] **Step 4: Run it to verify it fails**

Run: `./mvnw -q -pl nessy-model-anthropic -am test -Dtest=AnthropicModelProviderBootstrapTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure — `AnthropicModelProviderBootstrap` and `ModelProviderBootstrap` do not exist.

- [ ] **Step 5: Write the bootstrap**

```java
package org.jwcarman.nessy.model.anthropic;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;

/**
 * Anthropic's registration for discovery: {@code ANTHROPIC_API_KEY} present builds an {@link
 * AnthropicModelProvider} from that key alone — the same {@code create(c -> c.apiKey(key))} an
 * application would write, not {@link AnthropicModelProvider#fromEnv()}, so the key discovery
 * saw is the key that gets built and no other SDK-level variable is read underneath it.
 */
public final class AnthropicModelProviderBootstrap implements ModelProviderBootstrap {

  static final String API_KEY_ENV_VAR = "ANTHROPIC_API_KEY";

  /** Small and cheap — the model the demos used to hardcode. */
  static final String DEFAULT_MODEL_ID = "claude-haiku-4-5-20251001";

  @Override
  public String name() {
    return "anthropic";
  }

  @Override
  public Set<String> environmentVariables() {
    return Set.of(API_KEY_ENV_VAR);
  }

  @Override
  public String defaultModelId() {
    return DEFAULT_MODEL_ID;
  }

  @Override
  public Optional<ModelProvider> bootstrap(Map<String, String> env) {
    Objects.requireNonNull(env, "env must not be null");
    var apiKey = env.get(API_KEY_ENV_VAR);
    if (apiKey == null) {
      return Optional.empty();
    }
    return Optional.of(AnthropicModelProvider.create(c -> c.apiKey(apiKey)));
  }
}
```

- [ ] **Step 6: Write the services file**

`nessy-model-anthropic/src/main/resources/META-INF/services/org.jwcarman.nessy.spi.model.ModelProviderBootstrap`, one line, no trailing whitespace:

```
org.jwcarman.nessy.model.anthropic.AnthropicModelProviderBootstrap
```

The `resources` directory does not exist in this module yet; create it. The license plugin is configured for Java sources — check `./mvnw license:format -Plicense` leaves this file alone (a services file with a comment header would break `ServiceLoader` only if the comment lacked the leading `#`; verify the file still has exactly one non-comment line after formatting).

- [ ] **Step 7: Run the test to verify it passes**

Run: `./mvnw -q -pl nessy-model-anthropic -am test -Dtest=AnthropicModelProviderBootstrapTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 7 tests pass.

- [ ] **Step 8: Break-and-restore the registration test**

Temporarily change the services file's line to `org.jwcarman.nessy.model.anthropic.AnthropicModelProviderBootstrapX`, rerun, and confirm `is_registered_for_service_loading` fails with a `ServiceConfigurationError`. Restore. This is the only proof that the test guards the resource rather than the class.

- [ ] **Step 9: Full verify, then commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-spi nessy-model-anthropic
git commit -m "feat: ModelProviderBootstrap — the SPI type providers register for discovery; Anthropic registers"
```

---

### Task 2: OpenAI registers twice, and Gemini once

Same shape as Task 1 three times over, batched because it is one pattern. Each bootstrap gets its own test class; each module gets one services file and one registration test.

**Files:**
- Create: `nessy-model-openai/src/main/java/org/jwcarman/nessy/model/openai/OpenAiModelProviderBootstrap.java`
- Create: `nessy-model-openai/src/main/java/org/jwcarman/nessy/model/openai/XaiModelProviderBootstrap.java`
- Create: `nessy-model-openai/src/main/resources/META-INF/services/org.jwcarman.nessy.spi.model.ModelProviderBootstrap`
- Test: `nessy-model-openai/src/test/java/org/jwcarman/nessy/model/openai/OpenAiModelProviderBootstrapTest.java`
- Test: `nessy-model-openai/src/test/java/org/jwcarman/nessy/model/openai/XaiModelProviderBootstrapTest.java`
- Create: `nessy-model-gemini/src/main/java/org/jwcarman/nessy/model/gemini/GeminiModelProviderBootstrap.java`
- Create: `nessy-model-gemini/src/main/resources/META-INF/services/org.jwcarman.nessy.spi.model.ModelProviderBootstrap`
- Test: `nessy-model-gemini/src/test/java/org/jwcarman/nessy/model/gemini/GeminiModelProviderBootstrapTest.java`

**Interfaces:**
- Consumes: `ModelProviderBootstrap` from Task 1; `OpenAiModelProvider.create(OpenAiProviderCustomizer)`, `OpenAiProviderConfig.apiKey(String)`, `OpenAiProviderConfig.baseUrl(String)`, `GeminiModelProvider.create(GeminiProviderCustomizer)`, `GeminiProviderConfig.apiKey(String)` — all exist today.
- Produces: three registrations named `openai`, `xai`, `gemini`.

- [ ] **Step 1: Write the three failing tests**

`OpenAiModelProviderBootstrapTest`:

```java
package org.jwcarman.nessy.model.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;

class OpenAiModelProviderBootstrapTest {

  private final OpenAiModelProviderBootstrap bootstrap = new OpenAiModelProviderBootstrap();

  @Test
  void is_named_openai() {
    assertThat(bootstrap.name()).isEqualTo("openai");
  }

  @Test
  void reads_the_key_and_the_optional_base_url() {
    assertThat(bootstrap.environmentVariables())
        .containsExactlyInAnyOrder("OPENAI_API_KEY", "OPENAI_BASE_URL");
  }

  @Test
  void defaults_to_gpt_4o_mini() {
    assertThat(bootstrap.defaultModelId()).isEqualTo("gpt-4o-mini");
  }

  @Test
  void is_empty_when_the_key_is_absent() {
    assertThat(bootstrap.bootstrap(Map.of("ANTHROPIC_API_KEY", "not-mine"))).isEmpty();
  }

  @Test
  void a_base_url_alone_is_not_credentials() {
    // Pinned separately from the case above because it is the one a local-runtime user hits:
    // OPENAI_BASE_URL set, key forgotten. The answer must be "no credentials", never a provider
    // pointed at the URL with no key.
    assertThat(bootstrap.bootstrap(Map.of("OPENAI_BASE_URL", "http://127.0.0.1:1234/v1")))
        .isEmpty();
  }

  @Test
  void builds_an_openai_provider_when_the_key_is_present() {
    var provider = bootstrap.bootstrap(Map.of("OPENAI_API_KEY", "fake-openai-key"));

    assertThat(provider).containsInstanceOf(OpenAiModelProvider.class);
  }

  @Test
  void builds_an_openai_provider_with_a_base_url_layered_on() {
    var provider =
        bootstrap.bootstrap(
            Map.of("OPENAI_API_KEY", "lm-studio", "OPENAI_BASE_URL", "http://127.0.0.1:1234/v1"));

    assertThat(provider).containsInstanceOf(OpenAiModelProvider.class);
  }

  @Test
  void rejects_a_null_env() {
    assertThatThrownBy(() -> bootstrap.bootstrap(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void this_module_registers_openai_and_xai_and_nothing_else() {
    var registered =
        ServiceLoader.load(ModelProviderBootstrap.class).stream()
            .map(ServiceLoader.Provider::type)
            .toList();

    assertThat(registered)
        .containsExactlyInAnyOrder(
            OpenAiModelProviderBootstrap.class, XaiModelProviderBootstrap.class);
  }
}
```

Note the honest limitation: `OpenAiModelProvider` does not expose its base URL, so `builds_an_openai_provider_with_a_base_url_layered_on` proves the path constructs a provider without throwing, not the URL it carries. The retired `EnvModelProvidersTest` had the same limit and pinned xAI by its default model id instead — that is what the next test does.

`XaiModelProviderBootstrapTest`:

```java
package org.jwcarman.nessy.model.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class XaiModelProviderBootstrapTest {

  private final XaiModelProviderBootstrap bootstrap = new XaiModelProviderBootstrap();

  @Test
  void is_named_xai() {
    assertThat(bootstrap.name()).isEqualTo("xai");
  }

  @Test
  void reads_only_the_xai_api_key() {
    assertThat(bootstrap.environmentVariables()).containsExactly("XAI_API_KEY");
  }

  @Test
  void defaults_to_grok() {
    assertThat(bootstrap.defaultModelId()).isEqualTo("grok-4.6");
  }

  @Test
  void is_empty_when_the_key_is_absent() {
    assertThat(bootstrap.bootstrap(Map.of("OPENAI_API_KEY", "not-mine"))).isEmpty();
  }

  @Test
  void never_reads_openai_base_url() {
    // xAI's URL is fixed; an OPENAI_BASE_URL alongside an xAI key must be invisible here.
    var provider =
        bootstrap.bootstrap(
            Map.of("XAI_API_KEY", "fake-xai-key", "OPENAI_BASE_URL", "http://should.be.ignored"));

    assertThat(provider).containsInstanceOf(OpenAiModelProvider.class);
    assertThat(bootstrap.environmentVariables()).doesNotContain("OPENAI_BASE_URL");
  }

  @Test
  void builds_an_openai_wire_provider_when_the_key_is_present() {
    var provider = bootstrap.bootstrap(Map.of("XAI_API_KEY", "fake-xai-key"));

    assertThat(provider).containsInstanceOf(OpenAiModelProvider.class);
  }

  @Test
  void rejects_a_null_env() {
    assertThatThrownBy(() -> bootstrap.bootstrap(null)).isInstanceOf(NullPointerException.class);
  }
}
```

`GeminiModelProviderBootstrapTest`:

```java
package org.jwcarman.nessy.model.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;

class GeminiModelProviderBootstrapTest {

  private final GeminiModelProviderBootstrap bootstrap = new GeminiModelProviderBootstrap();

  @Test
  void is_named_gemini() {
    assertThat(bootstrap.name()).isEqualTo("gemini");
  }

  @Test
  void reads_googles_documented_pair() {
    assertThat(bootstrap.environmentVariables())
        .containsExactlyInAnyOrder("GEMINI_API_KEY", "GOOGLE_API_KEY");
  }

  @Test
  void defaults_to_flash() {
    assertThat(bootstrap.defaultModelId()).isEqualTo("gemini-3.6-flash");
  }

  @Test
  void is_empty_when_neither_key_is_present() {
    assertThat(bootstrap.bootstrap(Map.of("ANTHROPIC_API_KEY", "not-mine"))).isEmpty();
  }

  @Test
  void builds_from_gemini_api_key() {
    var provider = bootstrap.bootstrap(Map.of("GEMINI_API_KEY", "fake-gemini-key"));

    assertThat(provider).containsInstanceOf(GeminiModelProvider.class);
  }

  @Test
  void builds_from_google_api_key_too() {
    var provider = bootstrap.bootstrap(Map.of("GOOGLE_API_KEY", "fake-google-key"));

    assertThat(provider).containsInstanceOf(GeminiModelProvider.class);
  }

  @Test
  void both_present_still_builds_one_provider() {
    var provider =
        bootstrap.bootstrap(Map.of("GEMINI_API_KEY", "fake-gemini", "GOOGLE_API_KEY", "fake-google"));

    assertThat(provider).containsInstanceOf(GeminiModelProvider.class);
  }

  @Test
  void rejects_a_null_env() {
    assertThatThrownBy(() -> bootstrap.bootstrap(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void is_registered_for_service_loading() {
    var registered =
        ServiceLoader.load(ModelProviderBootstrap.class).stream()
            .map(ServiceLoader.Provider::type)
            .toList();

    assertThat(registered).containsExactly(GeminiModelProviderBootstrap.class);
  }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q -pl nessy-model-openai,nessy-model-gemini -am test -Dtest='*BootstrapTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failures in both modules.

- [ ] **Step 3: Write the three bootstraps**

`OpenAiModelProviderBootstrap`:

```java
package org.jwcarman.nessy.model.openai;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;

/**
 * OpenAI's registration for discovery: {@code OPENAI_API_KEY} present builds an {@link
 * OpenAiModelProvider} from that key, with {@code OPENAI_BASE_URL} layered on when it is also
 * present — the provider-expansion design's §7 amendment, by which local runtimes (LM Studio,
 * Ollama) and gateways (OpenRouter) become zero-code env citizens. A base URL with no key is not
 * credentials and bootstraps nothing.
 */
public final class OpenAiModelProviderBootstrap implements ModelProviderBootstrap {

  static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";
  static final String BASE_URL_ENV_VAR = "OPENAI_BASE_URL";
  static final String DEFAULT_MODEL_ID = "gpt-4o-mini";

  @Override
  public String name() {
    return "openai";
  }

  @Override
  public Set<String> environmentVariables() {
    return Set.of(API_KEY_ENV_VAR, BASE_URL_ENV_VAR);
  }

  @Override
  public String defaultModelId() {
    return DEFAULT_MODEL_ID;
  }

  @Override
  public Optional<ModelProvider> bootstrap(Map<String, String> env) {
    Objects.requireNonNull(env, "env must not be null");
    var apiKey = env.get(API_KEY_ENV_VAR);
    if (apiKey == null) {
      return Optional.empty();
    }
    var baseUrl = env.get(BASE_URL_ENV_VAR);
    return Optional.of(
        OpenAiModelProvider.create(
            c -> {
              c.apiKey(apiKey);
              if (baseUrl != null) {
                c.baseUrl(baseUrl);
              }
            }));
  }
}
```

`XaiModelProviderBootstrap`:

```java
package org.jwcarman.nessy.model.openai;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;

/**
 * Grok as a first-class discovery citizen with zero new provider code: OpenAI's wire protocol,
 * xAI's URL. Lives in this module because it <em>is</em> this module's provider at a fixed base
 * URL — the second of two registrations in one services file, which is the honest shape for a
 * vendor that has no module of its own and never will.
 */
public final class XaiModelProviderBootstrap implements ModelProviderBootstrap {

  static final String API_KEY_ENV_VAR = "XAI_API_KEY";
  static final String BASE_URL = "https://api.x.ai/v1";

  /**
   * xAI ships no small/cheap alias; {@code grok-4.6} is the vendor's own current general-purpose
   * recommendation (docs.x.ai, 2026-08-15).
   */
  static final String DEFAULT_MODEL_ID = "grok-4.6";

  @Override
  public String name() {
    return "xai";
  }

  @Override
  public Set<String> environmentVariables() {
    return Set.of(API_KEY_ENV_VAR);
  }

  @Override
  public String defaultModelId() {
    return DEFAULT_MODEL_ID;
  }

  @Override
  public Optional<ModelProvider> bootstrap(Map<String, String> env) {
    Objects.requireNonNull(env, "env must not be null");
    var apiKey = env.get(API_KEY_ENV_VAR);
    if (apiKey == null) {
      return Optional.empty();
    }
    return Optional.of(OpenAiModelProvider.create(c -> c.apiKey(apiKey).baseUrl(BASE_URL)));
  }
}
```

`GeminiModelProviderBootstrap`:

```java
package org.jwcarman.nessy.model.gemini;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;

/**
 * Gemini's registration for discovery: {@code GEMINI_API_KEY} first, then {@code GOOGLE_API_KEY}
 * — Google's own documented pair, in that order, mirroring {@link GeminiProviderConfig#fromEnv()}.
 */
public final class GeminiModelProviderBootstrap implements ModelProviderBootstrap {

  static final String GEMINI_API_KEY_ENV_VAR = "GEMINI_API_KEY";
  static final String GOOGLE_API_KEY_ENV_VAR = "GOOGLE_API_KEY";

  /** per ai.google.dev, 2026-08-16; model availability churns — override with NESSY_MODEL. */
  static final String DEFAULT_MODEL_ID = "gemini-3.6-flash";

  @Override
  public String name() {
    return "gemini";
  }

  @Override
  public Set<String> environmentVariables() {
    return Set.of(GEMINI_API_KEY_ENV_VAR, GOOGLE_API_KEY_ENV_VAR);
  }

  @Override
  public String defaultModelId() {
    return DEFAULT_MODEL_ID;
  }

  @Override
  public Optional<ModelProvider> bootstrap(Map<String, String> env) {
    Objects.requireNonNull(env, "env must not be null");
    var gemini = env.get(GEMINI_API_KEY_ENV_VAR);
    var apiKey = gemini != null ? gemini : env.get(GOOGLE_API_KEY_ENV_VAR);
    if (apiKey == null) {
      return Optional.empty();
    }
    return Optional.of(GeminiModelProvider.create(c -> c.apiKey(apiKey)));
  }
}
```

If `GeminiProviderConfig` has no `fromEnv()` to `{@link}`, link `GeminiModelProvider#fromEnv()` instead — javadoc must resolve, and doclint runs in the release profile.

- [ ] **Step 4: Write the two services files**

`nessy-model-openai/src/main/resources/META-INF/services/org.jwcarman.nessy.spi.model.ModelProviderBootstrap`:

```
org.jwcarman.nessy.model.openai.OpenAiModelProviderBootstrap
org.jwcarman.nessy.model.openai.XaiModelProviderBootstrap
```

`nessy-model-gemini/src/main/resources/META-INF/services/org.jwcarman.nessy.spi.model.ModelProviderBootstrap`:

```
org.jwcarman.nessy.model.gemini.GeminiModelProviderBootstrap
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q -pl nessy-model-openai,nessy-model-gemini -am test -Dtest='*BootstrapTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 9 + 7 + 9 = 25 tests pass.

- [ ] **Step 6: Break-and-restore one registration**

Remove the `Xai` line from the OpenAI services file, rerun, confirm `this_module_registers_openai_and_xai_and_nothing_else` fails on the missing class. Restore.

- [ ] **Step 7: Full verify, then commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-model-openai nessy-model-gemini
git commit -m "feat: OpenAI registers openai and xai for discovery; Gemini registers gemini"
```

---

### Task 3: `nessy-model-discovery` and `ModelDiscovery`

**Files:**
- Create: `nessy-model-discovery/pom.xml`
- Create: `nessy-model-discovery/src/main/java/org/jwcarman/nessy/model/discovery/ModelDiscovery.java`
- Create: `nessy-model-discovery/src/main/java/org/jwcarman/nessy/model/discovery/package-info.java`
- Test: `nessy-model-discovery/src/test/java/org/jwcarman/nessy/model/discovery/ModelDiscoveryTest.java`
- Test: `nessy-model-discovery/src/test/java/org/jwcarman/nessy/model/discovery/FakeBootstrap.java`
- Test: `nessy-model-discovery/src/test/java/org/jwcarman/nessy/model/discovery/RegisteredFakeBootstrap.java`
- Test: `nessy-model-discovery/src/test/resources/META-INF/services/org.jwcarman.nessy.spi.model.ModelProviderBootstrap`
- Modify: `pom.xml:56-70` (module list — add, do not remove)
- Modify: `nessy-bom/pom.xml:82-86` (add an entry beside `nessy-model-env`, do not remove)

**Interfaces:**
- Consumes: `ModelProviderBootstrap` (Task 1); `Model`, `ModelProvider` from `nessy-spi`.
- Produces: `org.jwcarman.nessy.model.discovery.ModelDiscovery` with `public static Model fromEnv()`, `public static Selection select()`, and `public record Selection(Model model, String providerName)`. Task 4's callers use exactly `ModelDiscovery.select().model()` and `selection.providerName()`.

**Design decision carried from the spec, made concrete here:** the tests need to drive discovery with an empty registration list (spec §9: "no registrations → the message"), but a services file on the test classpath is visible to every test in the module. So `ModelDiscovery` gets a package-private seam `select(Map<String, String> env, Iterable<ModelProviderBootstrap> bootstraps)` that the public methods call with `ServiceLoader.load(...)`. Every semantic test injects a list of fakes through that seam; **one** test uses the real `ServiceLoader` path against a fake registered in `src/test/resources` to prove the wiring. This is the same package-private-seam pattern `EnvModelProviders` used for `env`, extended by one argument.

- [ ] **Step 1: Create the module pom**

`nessy-model-discovery/pom.xml`:

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

  <artifactId>nessy-model-discovery</artifactId>
  <name>Nessy Model Discovery</name>
  <description>The provider follows the classpath: discovers every ModelProviderBootstrap a provider module registered, bootstraps each from the environment, and hands back the one that applies — depending on no provider module itself.</description>

  <dependencies>
    <!-- nessy-spi alone, on purpose (2026-08-25 model-discovery design §7): the provider modules
         register themselves through ServiceLoader, so the application chooses which SDKs ride its
         classpath by choosing which provider jars it adds — this module imposes none. -->
    <dependency>
      <groupId>org.jwcarman.nessy</groupId>
      <artifactId>nessy-spi</artifactId>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-jar-plugin</artifactId>
        <configuration>
          <archive>
            <manifestEntries>
              <Automatic-Module-Name>org.jwcarman.nessy.model.discovery</Automatic-Module-Name>
            </manifestEntries>
          </archive>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

The license header comment goes above `<project>` exactly as every other module pom carries it — copy the block from `nessy-model-env/pom.xml` lines 2-18. JUnit and AssertJ arrive at test scope from the root pom.

- [ ] **Step 2: Register the module in the reactor and the BOM**

`pom.xml`: after `<module>nessy-model-env</module>` add `<module>nessy-model-discovery</module>`. Do not remove the `env` line — Task 4 does.

`nessy-bom/pom.xml`: after the `nessy-model-env` `<dependency>` block add the same block for `nessy-model-discovery`.

- [ ] **Step 3: Write the test fakes**

`FakeBootstrap` — the injectable fake for the seam:

```java
package org.jwcarman.nessy.model.discovery;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * A bootstrap that claims one environment variable and, when it is present, hands back a provider
 * whose models carry their id and nothing else. Which bootstrap won is read off {@code
 * Selection.providerName()}; which model id was resolved is read off {@code Model#id()} — the
 * same two observables the real providers offer, with no SDK behind them.
 */
final class FakeBootstrap implements ModelProviderBootstrap {

  private final String name;
  private final String environmentVariable;
  private final String defaultModelId;
  private final boolean throwsOnPresentKey;

  FakeBootstrap(String name, String environmentVariable, String defaultModelId) {
    this(name, environmentVariable, defaultModelId, false);
  }

  private FakeBootstrap(
      String name, String environmentVariable, String defaultModelId, boolean throwsOnPresentKey) {
    this.name = name;
    this.environmentVariable = environmentVariable;
    this.defaultModelId = defaultModelId;
    this.throwsOnPresentKey = throwsOnPresentKey;
  }

  /** The malformed-configuration case: a present key it cannot honour. */
  static FakeBootstrap throwingOnPresentKey(String name, String environmentVariable) {
    return new FakeBootstrap(name, environmentVariable, name + "-default", true);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Set<String> environmentVariables() {
    return Set.of(environmentVariable);
  }

  @Override
  public String defaultModelId() {
    return defaultModelId;
  }

  @Override
  public Optional<ModelProvider> bootstrap(Map<String, String> env) {
    Objects.requireNonNull(env, "env must not be null");
    if (!env.containsKey(environmentVariable)) {
      return Optional.empty();
    }
    if (throwsOnPresentKey) {
      throw new IllegalArgumentException(name + ": " + environmentVariable + " is malformed");
    }
    return Optional.of(new FakeProvider(name));
  }

  private record FakeProvider(String providerName) implements ModelProvider {

    @Override
    public Model model(String id) {
      return new FakeModel(id);
    }

    @Override
    public String name() {
      return providerName;
    }
  }

  private record FakeModel(String id) implements Model {

    @Override
    public ModelStream stream(ModelRequest request) {
      throw new UnsupportedOperationException("discovery tests never stream");
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }
  }
}
```

If `Model` declares members beyond `stream`, `capabilities`, and `id` (check `nessy-spi/src/main/java/org/jwcarman/nessy/spi/model/Model.java` — it declared exactly those three at `ee038469`), implement them the same throwing way.

`RegisteredFakeBootstrap` — the one that goes through the real services file, so it must be `public` with a public no-arg constructor:

```java
package org.jwcarman.nessy.model.discovery;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;

/** Registered in this module's test services file; proves the ServiceLoader path end to end. */
public final class RegisteredFakeBootstrap implements ModelProviderBootstrap {

  static final String ENV_VAR = "REGISTERED_FAKE_KEY";

  private final FakeBootstrap delegate = new FakeBootstrap("registered", ENV_VAR, "registered-default");

  @Override
  public String name() {
    return delegate.name();
  }

  @Override
  public Set<String> environmentVariables() {
    return delegate.environmentVariables();
  }

  @Override
  public String defaultModelId() {
    return delegate.defaultModelId();
  }

  @Override
  public Optional<ModelProvider> bootstrap(Map<String, String> env) {
    return delegate.bootstrap(env);
  }
}
```

Services file `nessy-model-discovery/src/test/resources/META-INF/services/org.jwcarman.nessy.spi.model.ModelProviderBootstrap`:

```
org.jwcarman.nessy.model.discovery.RegisteredFakeBootstrap
```

- [ ] **Step 4: Write the failing tests**

```java
package org.jwcarman.nessy.model.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;

/**
 * Drives {@link ModelDiscovery#select(Map, Iterable)} through its two seams — an env map and an
 * explicit registration list — so every outcome in the design's §4 is pinned with no real
 * provider on the classpath. One test at the end goes through the real {@link
 * java.util.ServiceLoader} path against {@link RegisteredFakeBootstrap}, which is the only
 * registration this module's test classpath carries.
 */
class ModelDiscoveryTest {

  private static final FakeBootstrap ALPHA = new FakeBootstrap("alpha", "ALPHA_KEY", "alpha-default");
  private static final FakeBootstrap BETA = new FakeBootstrap("beta", "BETA_KEY", "beta-default");

  @Nested
  class With_nothing_registered {

    @Test
    void fails_naming_the_modules_that_would_register_something() {
      Map<String, String> env = Map.of("ALPHA_KEY", "present-but-nobody-claims-it");
      List<ModelProviderBootstrap> none = List.of();

      assertThatThrownBy(() -> ModelDiscovery.select(env, none))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no model provider modules are on the classpath")
          .hasMessageContaining("nessy-model-anthropic")
          .hasMessageContaining("nessy-model-openai")
          .hasMessageContaining("nessy-model-gemini");
    }
  }

  @Nested
  class With_one_registered {

    @Test
    void fails_naming_that_provider_and_its_variables_when_its_key_is_absent() {
      Map<String, String> env = Map.of("BETA_KEY", "present-but-beta-is-not-registered");
      List<ModelProviderBootstrap> only = List.of(ALPHA);

      assertThatThrownBy(() -> ModelDiscovery.select(env, only))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no model provider credentials found")
          .hasMessageContaining("alpha [ALPHA_KEY]")
          .hasMessageNotContaining("beta");
    }

    @Test
    void chooses_it_when_its_key_is_present() {
      var selection = ModelDiscovery.select(Map.of("ALPHA_KEY", "k"), List.of(ALPHA));

      assertThat(selection.providerName()).isEqualTo("alpha");
      assertThat(selection.model().id()).isEqualTo("alpha-default");
    }

    @Test
    void ignores_nessy_provider_however_wrong_because_one_candidate_is_no_tie() {
      var selection =
          ModelDiscovery.select(
              Map.of("ALPHA_KEY", "k", "NESSY_PROVIDER", "something-else"), List.of(ALPHA));

      assertThat(selection.providerName()).isEqualTo("alpha");
    }
  }

  @Nested
  class With_two_registered_and_both_keys_present {

    private final Map<String, String> both = Map.of("ALPHA_KEY", "a", "BETA_KEY", "b");

    @Test
    void fails_naming_both_when_nessy_provider_is_unset() {
      List<ModelProviderBootstrap> pair = List.of(ALPHA, BETA);

      assertThatThrownBy(() -> ModelDiscovery.select(both, pair))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("multiple model providers can bootstrap from this environment")
          .hasMessageContaining("(alpha, beta)")
          .hasMessageContaining("set NESSY_PROVIDER")
          .hasMessageNotContaining("names none of them");
    }

    @Test
    void nessy_provider_naming_one_of_them_chooses_it_silently() {
      var env = withProvider(both, "beta");

      var selection = ModelDiscovery.select(env, List.of(ALPHA, BETA));

      assertThat(selection.providerName()).isEqualTo("beta");
      assertThat(selection.model().id()).isEqualTo("beta-default");
    }

    @Test
    void nessy_provider_is_read_case_insensitively() {
      var env = withProvider(both, "BeTa");

      var selection = ModelDiscovery.select(env, List.of(ALPHA, BETA));

      assertThat(selection.providerName()).isEqualTo("beta");
    }

    @Test
    void fails_naming_both_and_the_value_when_nessy_provider_names_neither() {
      var env = withProvider(both, "gamma");
      List<ModelProviderBootstrap> pair = List.of(ALPHA, BETA);

      assertThatThrownBy(() -> ModelDiscovery.select(env, pair))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("(alpha, beta)")
          .hasMessageContaining("NESSY_PROVIDER=gamma names none of them");
    }

    @Test
    void a_registered_provider_whose_key_is_absent_is_not_a_candidate() {
      // Only alpha's key is present, so two registrations still yield one candidate: no tie.
      var selection =
          ModelDiscovery.select(Map.of("ALPHA_KEY", "a"), List.of(ALPHA, BETA));

      assertThat(selection.providerName()).isEqualTo("alpha");
    }

    private static Map<String, String> withProvider(Map<String, String> env, String provider) {
      var copy = new HashMap<>(env);
      copy.put("NESSY_PROVIDER", provider);
      return Map.copyOf(copy);
    }
  }

  @Nested
  class Registration_errors {

    @Test
    void two_registrations_sharing_a_name_fail_naming_the_token_and_both_classes() {
      var alphaAgain = new FakeBootstrap("alpha", "OTHER_KEY", "other-default");
      List<ModelProviderBootstrap> clash = List.of(ALPHA, alphaAgain);
      Map<String, String> env = Map.of();

      assertThatThrownBy(() -> ModelDiscovery.select(env, clash))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("share the name 'alpha'")
          .hasMessageContaining(FakeBootstrap.class.getName());
    }

    @Test
    void a_bootstrap_that_throws_on_a_present_key_propagates() {
      var broken = FakeBootstrap.throwingOnPresentKey("broken", "BROKEN_KEY");
      List<ModelProviderBootstrap> only = List.of(broken);
      Map<String, String> env = Map.of("BROKEN_KEY", "present");

      assertThatThrownBy(() -> ModelDiscovery.select(env, only))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("BROKEN_KEY is malformed");
    }
  }

  @Nested
  class The_model_id {

    @Test
    void comes_from_the_winners_default_when_nessy_model_is_unset() {
      var selection = ModelDiscovery.select(Map.of("ALPHA_KEY", "k"), List.of(ALPHA));

      assertThat(selection.model().id()).isEqualTo("alpha-default");
    }

    @Test
    void nessy_model_wins_over_the_default() {
      var selection =
          ModelDiscovery.select(
              Map.of("ALPHA_KEY", "k", "NESSY_MODEL", "alpha-large"), List.of(ALPHA));

      assertThat(selection.model().id()).isEqualTo("alpha-large");
    }

    @Test
    void a_blank_nessy_model_is_ignored() {
      var selection =
          ModelDiscovery.select(Map.of("ALPHA_KEY", "k", "NESSY_MODEL", "   "), List.of(ALPHA));

      assertThat(selection.model().id()).isEqualTo("alpha-default");
    }
  }

  @Nested
  class Selection_record {

    @Test
    void rejects_a_null_model() {
      assertThatThrownBy(() -> new ModelDiscovery.Selection(null, "alpha"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_a_null_provider_name() {
      var model = ALPHA.bootstrap(Map.of("ALPHA_KEY", "k")).orElseThrow().model("m");

      assertThatThrownBy(() -> new ModelDiscovery.Selection(model, null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class Through_the_real_service_loader {

    @Test
    void finds_the_registration_in_this_modules_test_services_file() {
      var selection = ModelDiscovery.select(Map.of(RegisteredFakeBootstrap.ENV_VAR, "k"));

      assertThat(selection.providerName()).isEqualTo("registered");
      assertThat(selection.model().id()).isEqualTo("registered-default");
    }

    @Test
    void the_no_credentials_message_names_only_what_is_registered() {
      Map<String, String> env = Map.of();

      assertThatThrownBy(() -> ModelDiscovery.select(env))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("registered [REGISTERED_FAKE_KEY]");
    }
  }
}
```

- [ ] **Step 5: Run them to verify they fail**

Run: `./mvnw -q -pl nessy-model-discovery -am test`
Expected: compilation failure — `ModelDiscovery` does not exist.

- [ ] **Step 6: Write `ModelDiscovery`**

```java
package org.jwcarman.nessy.model.discovery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;

/**
 * The provider follows the classpath (model-discovery design, 2026-08-25): every {@link
 * ModelProviderBootstrap} a provider module registered is loaded through {@link ServiceLoader},
 * each is asked to bootstrap from the environment, and the one that applies wins. This module
 * depends on no provider module; an application chooses which SDKs ride its classpath by choosing
 * which provider jars it adds, then configures the one it chose with its key.
 *
 * <p>Three outcomes and nothing in between:
 *
 * <ul>
 *   <li><strong>None</strong> bootstrapped — {@link IllegalStateException} naming, per registered
 *       provider, its name and the variables it reads. Only providers actually on the classpath
 *       are named; a user with one jar is told about one variable.
 *   <li><strong>One</strong> bootstrapped — chosen, silently. {@code NESSY_PROVIDER} is ignored
 *       here whatever it says: it exists to break ties, and one candidate has none.
 *   <li><strong>Two or more</strong> bootstrapped — {@code NESSY_PROVIDER} (case-insensitive)
 *       naming one of them chooses it silently; anything else is an {@link IllegalStateException}
 *       naming every candidate. Two providers that both bootstrap means the application
 *       deliberately shipped two provider jars and set two keys; that ambiguity is a configuration
 *       error, and a log line nobody reads is the wrong place to resolve it.
 * </ul>
 *
 * <p>The model id is {@code NESSY_MODEL} when set and non-blank, otherwise the winner's {@link
 * ModelProviderBootstrap#defaultModelId()} — applied here, once, so the precedence rule has one
 * owner rather than one copy per provider.
 *
 * <p>Nothing here depends on {@code ServiceLoader}'s iteration order, which varies with classpath
 * layout: messages list providers sorted by name, and no outcome is decided by position.
 */
public final class ModelDiscovery {

  static final String NESSY_PROVIDER_ENV_VAR = "NESSY_PROVIDER";
  static final String NESSY_MODEL_ENV_VAR = "NESSY_MODEL";

  private ModelDiscovery() {}

  /** The minimal door: a bound {@link Model} from the real process environment. */
  public static Model fromEnv() {
    return select().model();
  }

  /**
   * The door for applications that also want to show what was chosen: the bound model plus the
   * winning provider's registered name, from the real process environment.
   */
  public static Selection select() {
    return select(System.getenv());
  }

  /** The offline seam for the environment: real registrations, caller-supplied {@code env}. */
  static Selection select(Map<String, String> env) {
    return select(env, ServiceLoader.load(ModelProviderBootstrap.class));
  }

  /** The offline seam for both: caller-supplied {@code env} and caller-supplied registrations. */
  static Selection select(Map<String, String> env, Iterable<ModelProviderBootstrap> bootstraps) {
    Objects.requireNonNull(env, "env must not be null");
    Objects.requireNonNull(bootstraps, "bootstraps must not be null");
    var registered = registered(bootstraps);
    if (registered.isEmpty()) {
      throw new IllegalStateException(
          "no model provider modules are on the classpath: add one of nessy-model-anthropic,"
              + " nessy-model-openai, or nessy-model-gemini (or construct a provider directly)");
    }
    var candidates = new ArrayList<Candidate>();
    for (var bootstrap : registered) {
      bootstrap.bootstrap(env).ifPresent(p -> candidates.add(new Candidate(bootstrap, p)));
    }
    var chosen =
        switch (candidates.size()) {
          case 0 -> throw noCredentials(registered);
          case 1 -> candidates.get(0);
          default -> tiebreak(env.get(NESSY_PROVIDER_ENV_VAR), candidates);
        };
    var override = env.get(NESSY_MODEL_ENV_VAR);
    var modelId =
        override != null && !override.isBlank() ? override : chosen.bootstrap().defaultModelId();
    return new Selection(chosen.provider().model(modelId), chosen.bootstrap().name());
  }

  /** Materialises the registrations and rejects a duplicated name. */
  private static List<ModelProviderBootstrap> registered(Iterable<ModelProviderBootstrap> bootstraps) {
    var byName = new HashMap<String, ModelProviderBootstrap>();
    var all = new ArrayList<ModelProviderBootstrap>();
    for (var bootstrap : bootstraps) {
      var previous = byName.putIfAbsent(bootstrap.name(), bootstrap);
      if (previous != null) {
        throw new IllegalStateException(
            "two model provider bootstraps share the name '"
                + bootstrap.name()
                + "': "
                + previous.getClass().getName()
                + " and "
                + bootstrap.getClass().getName());
      }
      all.add(bootstrap);
    }
    return all;
  }

  private static IllegalStateException noCredentials(List<ModelProviderBootstrap> registered) {
    var listing =
        registered.stream()
            .sorted((a, b) -> a.name().compareTo(b.name()))
            .map(b -> b.name() + " " + new TreeSet<>(b.environmentVariables()))
            .collect(Collectors.joining("; "));
    return new IllegalStateException(
        "no model provider credentials found — registered providers and the variables they read: "
            + listing);
  }

  private static Candidate tiebreak(String preference, List<Candidate> candidates) {
    var names =
        candidates.stream()
            .map(c -> c.bootstrap().name())
            .sorted()
            .collect(Collectors.joining(", ", "(", ")"));
    if (preference != null) {
      var wanted = preference.toLowerCase(Locale.ROOT);
      for (var candidate : candidates) {
        if (candidate.bootstrap().name().equals(wanted)) {
          return candidate;
        }
      }
    }
    var suffix =
        preference == null || preference.isBlank()
            ? ""
            : " (" + NESSY_PROVIDER_ENV_VAR + "=" + preference + " names none of them)";
    throw new IllegalStateException(
        "multiple model providers can bootstrap from this environment "
            + names
            + " — set "
            + NESSY_PROVIDER_ENV_VAR
            + " to one of them to choose"
            + suffix);
  }

  private record Candidate(ModelProviderBootstrap bootstrap, ModelProvider provider) {}

  /**
   * What {@link #select()} chose: the bound {@code model} — its id reachable as {@code
   * model().id()} — and the winning provider's registered {@link ModelProviderBootstrap#name()},
   * so a banner or a log line can show what was picked without re-deriving it via {@code
   * instanceof}.
   */
  public record Selection(Model model, String providerName) {

    public Selection {
      Objects.requireNonNull(model, "model must not be null");
      Objects.requireNonNull(providerName, "providerName must not be null");
    }
  }
}
```

`TreeSet.toString()` renders as `[ALPHA_KEY]` / `[GEMINI_API_KEY, GOOGLE_API_KEY]`, which is the `alpha [ALPHA_KEY]` shape the tests and the spec's message pin.

`package-info.java`:

```java
/**
 * The provider follows the classpath: {@link org.jwcarman.nessy.model.discovery.ModelDiscovery}
 * resolves a bound model from whatever provider modules registered themselves, configured by the
 * environment.
 */
package org.jwcarman.nessy.model.discovery;
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -q -pl nessy-model-discovery -am test`
Expected: all 19 `ModelDiscoveryTest` cases pass.

- [ ] **Step 8: Break-and-restore two of them**

(a) In `tiebreak`, make the no-match path return `candidates.get(0)` instead of throwing; `fails_naming_both_when_nessy_provider_is_unset` must go red. Restore. (b) In `registered`, drop the duplicate check; `two_registrations_sharing_a_name_fail_...` must go red. Restore. Record both failures' messages in the report.

- [ ] **Step 9: Full verify, then commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add pom.xml nessy-bom/pom.xml nessy-model-discovery
git commit -m "feat: nessy-model-discovery — ModelDiscovery resolves a model from whatever registered, depending on no provider"
```

---

### Task 4: Callers move, and `nessy-model-env` retires

**Files:**
- Modify: `nessy-examples/hello/pom.xml:53-57`, `nessy-examples/hello/src/main/java/org/jwcarman/nessy/examples/hello/Hello.java:29,37-38,57`
- Modify: `nessy-examples/approvals/pom.xml:54-58`, `nessy-examples/approvals/src/main/java/org/jwcarman/nessy/examples/approvals/Approvals.java:38,50-51,136`
- Modify: `nessy-agent/pom.xml:61-66`, `nessy-agent/src/test/java/org/jwcarman/nessy/agent/host/ApprovalPlayground.java:32,82`, `nessy-agent/src/test/java/org/jwcarman/nessy/agent/host/CliLiveSmokeTest.java:28,32,50`
- Modify: `nessy-api/src/test/java/org/jwcarman/nessy/NoPublicBuildersTest.java:43,87`
- Modify: `pom.xml` (remove `<module>nessy-model-env</module>`), `nessy-bom/pom.xml` (remove the `nessy-model-env` block)
- Delete: `nessy-model-env/` (the whole directory)

**Interfaces:**
- Consumes: `ModelDiscovery.select()` → `Selection(model, providerName)` from Task 3, and the three provider modules' registrations from Tasks 1-2.

- [ ] **Step 1: `hello` — the minimal template**

`nessy-examples/hello/pom.xml`: replace the `nessy-model-env` dependency block with:

```xml
    <!-- One provider, on purpose: this is the template a new application copies, and it should
         teach the minimal shape — discovery plus the one SDK you actually use. Swap
         nessy-model-anthropic for nessy-model-openai or nessy-model-gemini (or add a second) and
         set that provider's key; nothing else changes. -->
    <dependency>
      <groupId>org.jwcarman.nessy</groupId>
      <artifactId>nessy-model-discovery</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.jwcarman.nessy</groupId>
      <artifactId>nessy-model-anthropic</artifactId>
      <version>${project.version}</version>
    </dependency>
```

`Hello.java`: change the import to `org.jwcarman.nessy.model.discovery.ModelDiscovery`; the javadoc sentence to "without it, {@link ModelDiscovery#select()} picks a real, bound model handle from the one provider on this example's classpath, configured by {@code ANTHROPIC_API_KEY}."; line 57 to `ModelDiscovery.select().model()`.

- [ ] **Step 2: `approvals` — the switch demo**

`nessy-examples/approvals/pom.xml`: replace the `nessy-model-env` block with `nessy-model-discovery` plus all three of `nessy-model-anthropic`, `nessy-model-openai`, `nessy-model-gemini`, each `${project.version}`, preceded by:

```xml
    <!-- All three keyed providers, on purpose: this demo shows the environment switch — set a
         different key, get a different vendor, no code change. hello shows the minimal shape. -->
```

`Approvals.java`: import and the two references (javadoc line 50-51, call line 136) to `ModelDiscovery`.

- [ ] **Step 3: `nessy-agent` test scope**

`nessy-agent/pom.xml`: replace the `nessy-model-env` test dependency with four test-scope dependencies — `nessy-model-discovery`, `nessy-model-anthropic`, `nessy-model-openai`, `nessy-model-gemini` — each `${project.version}`, `<scope>test</scope>`, with the comment: `<!-- ApprovalPlayground and CliLiveSmokeTest run against whichever real key the developer has; every keyed provider rides the test classpath so any of them works. -->`

`ApprovalPlayground.java` and `CliLiveSmokeTest.java`: import and call sites to `ModelDiscovery`; in `CliLiveSmokeTest`'s javadoc, `{@link EnvModelProviders}` → `{@link ModelDiscovery}`.

- [ ] **Step 4: `NoPublicBuildersTest`**

Line 87: `"nessy-model-env"` → `"nessy-model-discovery"`. Lines 42-43 javadoc: `{@code nessy-model-env}` → `{@code nessy-model-discovery}`.

- [ ] **Step 5: Retire the old module**

```bash
git rm -r nessy-model-env
```

Remove `<module>nessy-model-env</module>` from `pom.xml` and the `nessy-model-env` dependency block from `nessy-bom/pom.xml`.

- [ ] **Step 6: Prove nothing references it**

Run: `grep -rn 'nessy-model-env\|model\.env\|EnvModelProviders' --exclude-dir=target --exclude-dir=.git --exclude-dir=docs .`
Expected: no output. (`docs/` is Task 5's; everything else must be clean now.)

- [ ] **Step 7: Measure the thing this plan exists for**

Run: `./mvnw -q -pl nessy-examples/hello dependency:tree -Dscope=compile -DoutputFile=nessy-examples/hello/target/hello-tree.txt && grep -c ':compile' nessy-examples/hello/target/hello-tree.txt`
Expected: **at most 40** (it was 99 at `ee038469`). Put the number in the report. If it is above 40, something still drags a provider in — find it before committing.

Also confirm no AWS or Google jar survives: `grep -c 'software.amazon\|com.google' nessy-examples/hello/target/hello-tree.txt` → `0`.

- [ ] **Step 8: Full verify, then commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A
git commit -m "refactor: callers move to ModelDiscovery; nessy-model-env retires"
```

`HelloTest` (scripted) and the whole reactor must be green with the old module gone.

---

### Task 5: Documentation

Every page that states the old premise is corrected, not annotated (spec §8). This task has no unit test; its verification is Step 7's grep and a read-through.

**Files:**
- Modify: `docs/guides/providers.md:28-33,99-101,103-201,294-304,345-346,406-408,431`
- Modify: `README.md:63-66,228-234`
- Modify: `docs/guides/getting-started.md:66-69`
- Modify: `docs/index.md:122-125`
- Modify: `docs/guides/harness.md:446`
- Create: `nessy-model-discovery/README.md`
- Modify: `nessy-model-bedrock/README.md` (after the `fromEnv()` snippet, ~line 15)
- Modify: `CHANGELOG.md:26` (first bullet under `### Added`)
- Modify: `nessy-spi/src/main/java/org/jwcarman/nessy/spi/model/ModelProviderBootstrap.java` — no change; listed so the reviewer checks the guide's example matches it
- Modify: `docs/superpowers/specs/2026-08-15-nessy-console-design.md`, `docs/superpowers/specs/2026-08-16-bedrock-provider-design.md`, `docs/superpowers/specs/2026-08-15-provider-expansion-design.md` — one appended amendment section each

- [ ] **Step 1: `docs/guides/providers.md`**

Lines 28-33 become:

```markdown
Four native gateway modules ship today — `nessy-model-anthropic`,
`nessy-model-openai`, `nessy-model-gemini`, and `nessy-model-bedrock` — and
a fifth, `nessy-model-discovery`, resolves a bound `Model` from whichever of
them is on the classpath, configured by that provider's key. Add the provider
jar you want and set its key; switch providers by swapping the jar.
`OpenAiModelProvider` also reaches every service that speaks OpenAI's wire
protocol, covered below.
```

Lines 99-101 (`nessy-model-env, below, only ever reads the API key…`) become: "`nessy-model-discovery`, below, only ever reads the API key (and, for OpenAI, `OPENAI_BASE_URL`)."

Replace the whole `## Switching by environment variable` section (lines 103-201, through the `ApprovalPlayground` snippet) with:

````markdown
## Discovery: the provider follows the classpath

`nessy-model-discovery` depends on no provider module. Each provider module
registers a `ModelProviderBootstrap` through `java.util.ServiceLoader`;
discovery loads every registration on the classpath, asks each to bootstrap
from the environment, and hands back the one that applies:

```java
Model model = ModelDiscovery.fromEnv();
```

Two steps, then, and the first is the one that used to be hidden:

1. **Add the provider jar.** `nessy-model-anthropic`, `nessy-model-openai`,
   or `nessy-model-gemini` — one, or more than one if you mean to switch
   between them.
2. **Set its key.** `ANTHROPIC_API_KEY`; `OPENAI_API_KEY` (plus
   `OPENAI_BASE_URL` for a compatible endpoint — see
   [The OpenAI-compatible universe](#the-openai-compatible-universe));
   `XAI_API_KEY` (Grok, via the OpenAI module); `GEMINI_API_KEY` or
   `GOOGLE_API_KEY`.

Three outcomes and nothing in between:

- **None** of the registered providers finds its key → `IllegalStateException`
  listing, per provider on the classpath, its name and the variables it
  reads — `anthropic [ANTHROPIC_API_KEY]; openai [OPENAI_API_KEY, OPENAI_BASE_URL]; xai [XAI_API_KEY]`.
  Only providers actually present are named. No provider module at all is a
  different message, naming the three modules that register one.
- **One** finds its key → chosen, silently. `NESSY_PROVIDER` is ignored here
  whatever it says: it exists to break ties, and one candidate has none.
- **Two or more** find their keys → `NESSY_PROVIDER` (`anthropic`/`openai`/
  `xai`/`gemini`, case-insensitive) naming one of them chooses it silently.
  Anything else — unset, or naming a provider that did not bootstrap — fails
  with `IllegalStateException` naming every candidate: two providers that
  both bootstrap means you shipped two jars and set two keys, and that
  ambiguity is a configuration error, not something to resolve with a log
  line nobody reads.

Each gateway is built the same way its own module builds one from an
explicit key — `Provider.create(c -> c.apiKey(key))`, not that provider's
own `fromEnv()`. The key discovery saw is the key that gets built, and no
other SDK-level environment variable is read underneath it. Construct the
gateway directly when one of those matters.

**Bedrock is not discovered.** It registers no bootstrap, so it never enters
the candidate list — see [Bedrock](#bedrock) below for why, and for the one
line that constructs it.

### Picking a model too — `select()`

`fromEnv()` returns only the bound `Model`. `select()` returns a
`Selection` — the model handle and the winning provider's registered name
(`"anthropic"`/`"openai"`/`"xai"`/`"gemini"`, the same vocabulary
`NESSY_PROVIDER` accepts) — so an application that wants to show or log what
was picked doesn't re-derive it via `instanceof`:

```java
ModelDiscovery.Selection selection = ModelDiscovery.select();
Model model = selection.model();
String vendor = selection.providerName();
```

The model comes from `NESSY_MODEL` when that variable is set and non-blank —
it wins outright, whichever provider was chosen. That is the one way to name
a model whose gateway can't reveal it on its own: a Grok, OpenRouter, or LM
Studio model reached through `OpenAiModelProvider`'s base-url override looks,
by type, exactly like an OpenAI model. Without `NESSY_MODEL`, the winner's
own default applies: Anthropic's `claude-haiku-4-5-20251001`, OpenAI's
`gpt-4o-mini`, xAI's `grok-4.6`, Gemini's `gemini-3.6-flash`.

`ApprovalPlayground` (`nessy-agent`'s test sources, an IDE-run tinker door)
is this in practice: one `main`, no `if` branch for which provider to
import, because discovery already decided both the vendor and the model:

```java
ModelDiscovery.Selection selection;
try {
    selection = ModelDiscovery.select();
} catch (IllegalStateException e) {
    System.out.println(e.getMessage());
    System.exit(1);
    return;
}

var harness = Nessy.harness(h -> h.model(selection.model()).systemPrompt("You are a terse assistant."));
```

### Writing your own provider

A provider module joins discovery by implementing `ModelProviderBootstrap`
(in `nessy-spi`) and registering it. Do this **only** when the presence of
your credentials in the environment signals intent to use you — a vendor API
key, not an ambient cloud identity. Bedrock is the worked example of a
provider that must not register: AWS credentials are on far too many
machines to mean "talk to Bedrock".

```java
public final class AcmeModelProviderBootstrap implements ModelProviderBootstrap {

  @Override
  public String name() {
    return "acme";
  }

  @Override
  public Set<String> environmentVariables() {
    return Set.of("ACME_API_KEY");
  }

  @Override
  public String defaultModelId() {
    return "acme-small";
  }

  @Override
  public Optional<ModelProvider> bootstrap(Map<String, String> env) {
    var key = env.get("ACME_API_KEY");
    return key == null ? Optional.empty() : Optional.of(AcmeModelProvider.create(c -> c.apiKey(key)));
  }
}
```

Then one line in `src/main/resources/META-INF/services/org.jwcarman.nessy.spi.model.ModelProviderBootstrap`:

```
com.acme.nessy.AcmeModelProviderBootstrap
```

The class is `public final` with a public no-arg constructor — `ServiceLoader`
needs both. Read only the `env` map you are handed, never `System.getenv()`;
return empty for an absent key and throw for a present key you cannot honour.
Write one test that `ServiceLoader.load(ModelProviderBootstrap.class)` finds
your class: the services file is a resource, and a typo in it fails at runtime
with no compiler to notice.
````

Lines 294-304 (the `**Explicit selection only.**` paragraph under Bedrock) become:

```markdown
**Not discovered.** `nessy-model-bedrock` registers no
`ModelProviderBootstrap`, so `ModelDiscovery` never sees it — not by key,
not by classpath presence, not as a tiebreak participant. This is deliberate:
AWS credentials (and, on some platforms, `AWS_REGION` itself — Lambda sets it
automatically) are ambient on a large fraction of machines, so any mechanism
that let their presence choose Bedrock would silently route an application
with a stray AWS profile to it. An application that wants Bedrock says so in
code:

```java
Model model = BedrockModelProvider.fromEnv().model("us.anthropic.claude-haiku-4-5-20251001-v1:0");
```

`us.anthropic.claude-haiku-4-5-20251001-v1:0` is the `us` cross-region
inference profile id for Claude Haiku 4.5 — a documented starting point, not
a default, since there is no bootstrap to hold one.
```

Lines 345-346: "`XAI_API_KEY` is a first-class discovery citizen — with `nessy-model-openai` on the classpath, set it alone and `ModelDiscovery.fromEnv()` wires Grok with no other code."

Lines 406-408: "`OPENAI_BASE_URL`, set alongside `OPENAI_API_KEY`, makes any of these a zero-code env citizen too: `ModelDiscovery.fromEnv()` layers it onto the OpenAI gateway exactly as shown above, the same way it wires Grok."

Line 431: "`ApprovalPlayground` builds its model choice from `ModelDiscovery.select()`, and `nessy-agent`'s test classpath carries every keyed provider, so any of the env setups above just works".

- [ ] **Step 2: `README.md`**

Lines 63-66 become:

```markdown
This snippet runs — nothing else is required. `OPENAI_API_KEY` and
`OpenAiModelProvider.fromEnv()` are the one-line swap for OpenAI instead,
with nothing else about the shape above changing. `ModelDiscovery.fromEnv()`
(from `nessy-model-discovery`) picks whichever provider module you shipped
and configures it from its key, so an application switches vendors by
swapping one dependency and one environment variable, not its code.
```

Lines 228-234 become:

```xml
  <!-- Optional: discovery. Resolves a model from whichever provider modules above are on the
       classpath, configured by their keys; depends on none of them itself. Bedrock is never
       discovered — construct BedrockModelProvider directly. -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-model-discovery</artifactId>
  </dependency>
```

- [ ] **Step 3: `getting-started.md`, `index.md`, `harness.md`**

`getting-started.md` lines 66-69: "`nessy-model-openai`, `nessy-model-gemini`, and `nessy-model-bedrock` are the other provider gateways; `nessy-model-discovery` resolves whichever of them is on the classpath from its key, so an application switches vendors by swapping a dependency and a variable rather than its code — see [Providers](providers.md)."

`index.md` lines 122-125: "A model provider module (`nessy-model-anthropic`, `nessy-model-openai`, `nessy-model-gemini`, or `nessy-model-bedrock`) sits alongside `nessy-agent` in every application's dependency list; `nessy-model-discovery` resolves the one you shipped from the environment."

`harness.md` line 446: `EnvModelProviders.select()` → `ModelDiscovery.select()`.

- [ ] **Step 4: `nessy-model-discovery/README.md`**

````markdown
# Nessy Model Discovery

The provider follows the classpath. This module depends on no provider
module: each of `nessy-model-anthropic`, `nessy-model-openai`, and
`nessy-model-gemini` registers a `ModelProviderBootstrap` through
`java.util.ServiceLoader`, and `ModelDiscovery` loads whatever registrations
it finds, asks each to bootstrap from the environment, and hands back the one
that applies. An application chooses which SDKs ride its classpath by
choosing which provider jars it adds, then configures the one it chose with
its key.

Two entry points, both reading the real process environment:

```java
Model model = ModelDiscovery.fromEnv();
```

```java
ModelDiscovery.Selection selection = ModelDiscovery.select();
Model model = selection.model();
String vendor = selection.providerName();   // "anthropic", "openai", "xai", "gemini"
```

## Three outcomes

| Registered providers that find their key | Result |
|---|---|
| none | `IllegalStateException` listing each registered provider and the variables it reads, e.g. `anthropic [ANTHROPIC_API_KEY]; openai [OPENAI_API_KEY, OPENAI_BASE_URL]`. Only providers on the classpath are named. |
| one | chosen, silently — `NESSY_PROVIDER` is ignored, since one candidate is no tie |
| two or more | `NESSY_PROVIDER` naming one of them (case-insensitive) chooses it silently; anything else fails naming every candidate |

No provider module on the classpath at all is its own message, naming the
three that register one.

## The variables

| Variable | Read by |
|---|---|
| `ANTHROPIC_API_KEY` | `anthropic` |
| `OPENAI_API_KEY`, `OPENAI_BASE_URL` (optional) | `openai` |
| `XAI_API_KEY` | `xai` — Grok on OpenAI's wire protocol, in `nessy-model-openai` |
| `GEMINI_API_KEY`, then `GOOGLE_API_KEY` | `gemini` |
| `NESSY_PROVIDER` | breaks a tie between two or more of the above |
| `NESSY_MODEL` | names the model, winning over the chosen provider's default |

Each provider is built the way its own module builds one from an explicit
key — `Provider.create(c -> c.apiKey(key))` — never that provider's own
`fromEnv()`, so the key discovery saw is the key that gets built and no other
SDK-level variable is read underneath it.

## Bedrock

`nessy-model-bedrock` registers nothing and is never discovered. AWS
credentials are ambient on too many machines to mean "talk to Bedrock";
construct it directly: `BedrockModelProvider.fromEnv().model("...")`.

## Testing

Offline, entirely: the public doors read the real environment, but every
test drives the package-private `select(Map<String, String>, Iterable<ModelProviderBootstrap>)`
seam with hand-written fakes and no provider module on the classpath. One
test goes through the real `ServiceLoader` against a registration in this
module's own test resources, proving the wiring.

## Writing your own

See "Writing your own provider" in
[`docs/guides/providers.md`](../docs/guides/providers.md).
````

- [ ] **Step 5: `nessy-model-bedrock/README.md` and `CHANGELOG.md`**

Bedrock README, after the `fromEnv()` snippet (~line 15), insert:

```markdown
Bedrock is **never discovered** by `nessy-model-discovery`: this module
registers no `ModelProviderBootstrap`, because AWS credentials are ambient on
far too many machines to mean "talk to Bedrock". Construct it as above and
bind a model explicitly — `us.anthropic.claude-haiku-4-5-20251001-v1:0` is
the `us` cross-region inference profile for Claude Haiku 4.5, a documented
starting point rather than a default.
```

`CHANGELOG.md`, first bullet under `### Added` (line 26):

```markdown
- **Model provider discovery: `nessy-model-env` becomes `nessy-model-discovery`,
  and depends on no provider module.** Providers register a new SPI type,
  `ModelProviderBootstrap`, through `ServiceLoader`; `ModelDiscovery` loads
  whatever is on the classpath and bootstraps it from the environment. A
  hello-world agent's compile classpath drops from 99 jars to under 40.
  Removed with it: `NESSY_PROVIDER=bedrock` (Bedrock registers nothing —
  construct it directly), the `grok` alias for `xai`, and the
  warn-and-default on ambiguous keys, which now fails fast naming every
  candidate.
```

- [ ] **Step 6: Amend the three specs**

Append to each, as a new final section, in the same "Amendment" voice `2026-08-15-provider-expansion-design.md` §7 already uses:

`2026-08-15-nessy-console-design.md`:

```markdown
## Amendment (2026-08-25): §4a superseded

§4a's premise — a micro-module depending on both provider modules
non-optionally so any key just works — is superseded by
`2026-08-25-model-discovery-design.md`. `nessy-model-env` is now
`nessy-model-discovery`, depends on no provider module, and finds providers
through `ServiceLoader`. Switching provider is swapping a dependency, then
setting its key. §4a's "no reflection" clause is amended, not broken:
`ServiceLoader` instantiates registered classes through a no-arg constructor
and inspects nothing else, which is not what that clause exists to forbid.
```

`2026-08-16-bedrock-provider-design.md`:

```markdown
## Amendment (2026-08-25): §4's mechanism changes, its rule survives

Explicit-only selection is now enforced by non-registration:
`nessy-model-bedrock` ships no `ModelProviderBootstrap`, so there is no code
path by which it enters discovery's candidate list. `NESSY_PROVIDER=bedrock`
retires; applications construct `BedrockModelProvider` directly. See
`2026-08-25-model-discovery-design.md` §6.
```

`2026-08-15-provider-expansion-design.md`:

```markdown
## Amendment (2026-08-25): §3's precedence table retires

The Anthropic → OpenAI → Gemini → xAI precedence, and the `grok` alias, are
gone with the warn-and-default they served. Two providers that both
bootstrap now fail fast unless `NESSY_PROVIDER` names one. See
`2026-08-25-model-discovery-design.md` §4.
```

- [ ] **Step 7: Prove the old name is gone**

Run: `grep -rn 'nessy-model-env\|EnvModelProviders\|NESSY_PROVIDER=bedrock' --exclude-dir=target --exclude-dir=.git . | grep -v 'docs/superpowers/'`
Expected: no output outside `docs/superpowers/` (historical plans and specs keep the old name as a record; the three amended ones now also carry the new one).

- [ ] **Step 8: Full verify, then commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A
git commit -m "docs: the provider follows the classpath — guides, READMEs, changelog, and three spec amendments"
```

---

## Self-review

**Spec coverage.** §3 interface → Task 1. §4 selection, all seven steps and both exact messages → Task 3 (`ModelDiscovery`, tests for every branch). §5 registrations → Tasks 1-2, tokens/vars/defaults verbatim. §6 Bedrock exits → Task 4 deletes the env path, Task 5 documents construction. §7 rename and every dependency change in its table → Tasks 3-4. §8 docs, every bullet → Task 5. §9 testing: discovery with no real provider (Task 3, seam + one `ServiceLoader` test), per-provider bootstrap tests plus a registration test per module (Tasks 1-2), `NoPublicBuildersTest` (Task 4), `hello` scripted with one provider (Task 4). §10/§12 impose nothing. §11 sign-offs are all granted; Task 4 implements `hello` = discovery + Anthropic per item 3.

**Placeholder scan.** None. Every code step is complete; every doc replacement is the actual text.

**Type consistency.** `ModelProviderBootstrap`'s four members are identical in Task 1's interface, Tasks 1-2's implementations, Task 3's fakes and `ModelDiscovery`, and Task 5's guide example. `ModelDiscovery.select()` / `.fromEnv()` / `Selection(model, providerName)` are identical in Task 3 and every Task 4 call site. Constant names (`API_KEY_ENV_VAR`, `DEFAULT_MODEL_ID`) are package-private and never referenced across modules.
