# Named Embedders Implementation Plan

Executes after vendor-properties is fully built; the controller restacks this branch onto it first.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An embedder factory holds embedding providers by name (`ProviderId`); a store names the provider and model it wants, or gets the factory's both-or-neither default; under Boot a preset catalogue under `nessy.embedders.<id>` lights an embedding provider from a vendor key only when that vendor's embedding adapter is on the classpath, application `EmbeddingProvider` beans join under their bean names, the default is `nessy.embedder` + `nessy.embedding-model`, and a startup report lists what lit.

**Architecture:** The engine's `DefaultEmbedderFactory` is built from a new public `EmbedderFactoryConfig` (`provider(ProviderId, EmbeddingProvider)`, `embedding(ProviderId, EmbeddingOptions)`, `observations(ObservationRegistry)`), resolves a store's provider once at `create`, asks the provider to `validate` the options, and wraps every embedder it mints in `ObservedEmbedder`; `DefaultEmbedder` refuses a reply whose width differs from the width asked for. The four embedding adapters lose their model/width defaults, read their `openai.`/`gemini.`/`bedrock.`/`voyage.` vendor properties (pass-through only, with a clash table), and the OpenAI one gains `vendor(String)`. Boot mirrors the inference catalogue in `org.jwcarman.nessy.spring.boot.embedding` (`EmbeddingWire`, `EmbedderPreset`, `EmbedderSettings`, `ResolvedEmbedder(s)`, `EmbedderCatalogue`, `WireEmbedders`, `EmbedderRegistrar`, `EmbeddingReport`, `EmbeddingProvidersAutoConfiguration`), replacing the three vendor auto-configurations and every `nessy.embedding.*` property.

**Tech Stack:** Java 25, Maven reactor, Spring Boot 4.1.1 (`Binder`, `BeanDefinitionRegistryPostProcessor`, `ApplicationContextRunner`, `FilteredClassLoader`, `OutputCaptureExtension`), Micrometer Observation, Jackson 3 (`tools.jackson`), openai-java 4.69.2, google-genai 1.73.0, AWS SDK bedrockruntime, JDK `HttpClient` (Voyage), JUnit 5 + AssertJ, SLF4J/Logback.

**Spec:** `docs/superpowers/specs/2026-09-30-named-embedders-design.md` -- the design of record, APPROVED. Read it whole before any task; section numbers below (§n) refer to it. Do not re-open its decisions. The source of truth for what exists beneath this branch is `docs/superpowers/plans/2026-09-30-vendor-properties.md` (cited below as "VP Task n"). Every public name this plan introduces is in the spec's §14 table; the package-private classes (`EmbeddingWire`, `EmbedderPreset`, `EmbedderSettings`, `ResolvedEmbedder`, `ResolvedEmbedders`, `EmbedderCatalogue`, `WireEmbedders`, `EmbedderRegistrar`, `EmbeddingReport`'s internals, and the four `XEmbeddingProperties` helpers) are mechanical internals under the repo's design-authority rule.

**Sequencing.** Task 1 is spec §12 step 1's engine half (with every caller of the old constructors moved in the same commit, so the reactor stays green). Task 2 is step 1's adapter half (§5d, §6e). Task 3 is step 3 (§7), moved ahead of Boot because the vendor-properties item has landed by the time this plan runs (Plan ruling 9). Tasks 4 and 5 are step 2 (§6), split into the pure resolution units and the Spring wiring. Task 6 is step 4 (live; written here, run by James). Task 7 is step 5 (examples' docs, guides, ROADMAP, CHANGELOG). Each task ends green under `./mvnw -q clean verify` with no key and no network.

## Before Task 1: restack onto `vendor-properties`

This branch was cut from `be2d100a9` (the vendor-properties plan commit); its own commits are docs only (the spec, its overnight rulings, and this plan), so the rebase cannot conflict. Once every vendor-properties task is committed on `vendor-properties`, the controller runs, once, before dispatching Task 1:

```bash
cd /Users/jcarman/IdeaProjects/nessy-embedders
git status --short            # expected: nothing
git rebase vendor-properties
git log --oneline -5          # expected: this plan's commit and the spec commits on top of vendor-properties' last commit
# What this plan consumes from vendor-properties must exist, in the shape VP Tasks 1, 2, 8 and 9 gave it:
test -f nessy-vendor-properties/src/main/java/org/jwcarman/nessy/vendor/VendorProperties.java && echo helper-ok
git grep -n "EmbedderConfig property(String name, String value)" -- nessy-api
git grep -n "default void validate(EmbeddingOptions options)" -- nessy-embedding/spi
git grep -n "Map<String, String> properties" -- nessy-embedding/spi/src/main/java/org/jwcarman/nessy/embedding/EmbeddingOptions.java
git grep -n "public OpenAiEmbedderConfig properties(Map<String, String> properties)" -- nessy-embedding/openai
git grep -n "provider.validate(options)" -- nessy-engine/src/main/java/org/jwcarman/nessy/engine/embedding/DefaultEmbedderFactory.java
git grep -n "Map<String, String> properties" -- nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/inference/ResolvedProvider.java
```

Each `git grep` must print a line. If one does not -- a vendor-properties item landed in a different shape -- stop and tell James which, before Task 1: the names in this plan follow whatever landed (spec §15 (4)), and the code blocks below were written against VP Tasks 1, 2, 8 and 9 exactly. Never push.

## Plan rulings

Where the code on this branch disagrees with the spec, or the spec leaves a mechanical choice open, this plan follows the code and records the choice here. None of these is a new public concept.

1. **The Responses rename is already here.** This branch's merge-base with `responses-api` is its tip (`b904d105c`), so `Wire.OPENAI_CHAT` / `OPENAI_RESPONSES` exist (`nessy-spring-boot/autoconfigure/.../inference/Wire.java:30-34`) and the chat config is `OpenAiChatProviderConfig` (`nessy-inference/openai/.../OpenAiChatProviderConfig.java:135`). The spec's §15 (4) and §6e (`OpenAiProviderConfig.vendor`) predate that; `OpenAiEmbedderConfig.vendor(String)` mirrors `OpenAiChatProviderConfig.vendor(String)` exactly: a null check, no blank check.
2. **No generified registry.** The spec leaves "`ProviderRegistry` generified, or a twin" to the implementer (§5a). `ProviderRegistry` (`nessy-engine/.../harness/ProviderRegistry.java:37-107`) is public and speaks of agent types; generifying it would change a public class for nothing. The embedding registry is a private `LinkedHashMap<ProviderId, EmbeddingProvider>` inside `EmbedderFactoryConfig`, resolved in `DefaultEmbedderFactory`. Its duplicate message is `embedding provider '<id>' is already registered`.
3. **All three resolution failures are `IllegalStateException`**, as `ProviderRegistry.Resolved.choose` (lines 81-94) and the direct factory's missing-model check are. Today's missing-model failure is a `NullPointerException` from `Objects.requireNonNull` (`DefaultEmbedderFactory.java:92-95`); its message is kept verbatim (§5c) and its type becomes `IllegalStateException`.
4. **The defaults seed each field independently**, as `DefaultDirectHarnessConfig.Inference`'s constructor does (lines 306-314): a store that names only `provider("voyage")` keeps the factory's default model and width. Pinned by `a_store_naming_only_a_provider_keeps_the_default_model`. The factory default's properties seed the store's map, as VP Task 2 seeds an agent type's; an adapter ignores entries under another adapter's prefix (Review Focus 5).
5. **A mistyped default fails at startup by a trial `create`.** §5c resolves at `create`; §11a wants `nessy.embedder=cohere` to fail at startup. The Boot factory bean, when the pair is set, calls `factory.create(c -> {})` once before returning -- no network, the §5c message, and `validate` on the default's properties. The engine does not check its default at construction, exactly as the harness factories do not.
6. **A custom embedder's bean is also `<id>Embeddings`.** §6f names the rule for presets; the registrar registers custom embedders the same way (`localEmbeddings`), so one rule decides every bean the registrar owns, and the collision check covers both.
7. **A custom embedder must state `api-key`.** §6e: all three wires require one. `EmbedderCatalogue.custom` refuses its absence at resolution, naming the id and the field (`nessy.embedders.<id>.api-key is required: the openai wire needs a key`), rather than letting an adapter's `an API key is required` surface without the id.
8. **`WireEmbedders` sets no timeout.** §10 says it "sets what each config offers and no more"; only `VoyageEmbedderConfig` offers `timeout(...)`, and `TransportTimeouts.PROVIDER_TRANSPORT` (six minutes) exists for the inference deadline (`TransportTimeouts.java:20-35`), not for embeddings. Voyage keeps its own 30-second default.
9. **Vendor properties (spec step 3) run before Boot.** The spec sequenced them after "that item lands"; it has landed by the time this plan runs, so Task 3 lands the adapters' reading first and Task 4's `WireEmbedders` hands `resolved.properties()` to the configs from its first commit.
10. **Each adapter reads through a package-private `XEmbeddingProperties`** (`OpenAiEmbeddingProperties`, `GeminiEmbeddingProperties`, `BedrockEmbeddingProperties`, `VoyageEmbeddingProperties`): four near-copies, because a shared one would need a new public method on `VendorProperties`, which the design-authority rule forbids without a yes. **The clash rule covers names under a clash root** (`openai.input.extra` clashes with `input`), because a nested pass-through object would replace the field the adapter wrote; this extends §7c's exact-name table the way VP ruling 6 does for owned roots, and is flagged in Open questions.
11. **A provider's own map is checked when its config builds**, as every inference config's is (VP Tasks 3-7: `XProperties.read(properties, mapper)` in `build()`): prefix (VP Task 8's `requireOwnProperties`) and clashes, before any client is built.
12. **`NessyProperties` turns a blank `embedder` / `embeddingModel` into `null`.** `${CHAT_EMBEDDER:}` binds as `""`; §6i's chat-web code tests `properties.embedder() == null`, and §6h says blank means unset. The inference pair is untouched.
13. **Report details.** The default line omits `, N wide` when no width is set (`NESSY EMBEDDING: default: voyage / voyage-3.5`); lines are ordered by registry id; an application bean prints `id (vendor <vendor>)` as `InferenceReport.describe` does (line 80).
14. **`LocalEmbedderLiveTest` is tagged, not port-gated.** The spec says "gated the same way" as `LmStudioPresetLiveTest`; that test has no gate beyond `@Tag("live")` (`LmStudioPresetLiveTest.java:68`), so neither does this one.
15. **A bad wire value is pinned by the property path.** §11a says "fails to start naming `openai`, `gemini`, `voyage`"; the allowed values are printed by Boot's failure analyzer at a real startup, not carried by the exception, so the test asserts the stack trace names `nessy.embedders.mine.wire`, as `the_retired_openai_wire_value_fails_to_start_naming_the_property` does for inference (`InferenceProvidersAutoConfigurationTest.java:233-245`).
16. **Line numbers in the spec that moved.** `memory.md`'s `nessy-embedding-api` is at line 239 and the stale `Embedder` listing at 245-250; the two factory examples at 157-158 and 267-276; `observability.md`'s wrap text at 138-152; README line 183; `docs/index.md` line 142; ROADMAP line 42 and the entry at 158-169 (not 156-167); the "Reasoning effort, vendor-neutral" entry no longer exists on this branch -- the ROADMAP now carries a "Vendor properties" entry at 150-157 instead, whose status Task 7 changes to built rather than rewriting it.
17. **chat-web's README recipe for OpenAI changes.** Removing the `openai:` block (§6i) means `CHAT_MODEL_API_KEY` no longer lights the `openai` inference preset; the README's "OpenAI itself" recipe (lines 58-67) becomes `OPENAI_API_KEY=… CHAT_PROVIDER=openai CHAT_MODEL_ID=…`. Task 5 changes it with the yml.

## Open questions (for James; nothing below is decided by this plan)

1. **Names under a clash root (ruling 10).** Refusing `openai.input.extra` beside the exact `openai.input` is this plan's reading; confirm or overrule.
2. **Gemini's `extraBody` on the batch endpoint.** The adapter calls `batchEmbedContents` (the SDK chooses it for a list, `Models.privateEmbedContent`); `extraBody` merges into that call's top-level body, not into each `requests[i]`. A pass-through meant for a per-request field (`gemini.title`) may therefore not land where the user expects. Unmeasured; Task 6's Gemini live case sends one and records whether the call is accepted. If it is not honoured, the follow-up is the adapter nesting its pass-through per request -- not in this plan.
3. **`openai.encoding_format` passes through.** It is not in §7c's clash table, and `openai.encoding_format=base64` would make the SDK's reply a base64 string the adapter reads as floats. Should it join the table (the adapter reads floats, so it "decides" the format)? Not added here.
4. **Width drift after learning.** §5f checks replies against a width *asked for*. An embedder that asked for none learns the first reply's width and never checks again, so a server that changes model mid-run is not caught. Spec-silent; not added.
5. **The trial `create` at startup (ruling 5)** is the one place the starter mints an embedder it throws away. Confirm this is the shape wanted, or say whether the engine should check its default at construction instead (a change to the mirror).

## Global Constraints

- Full verification: `./mvnw -q clean verify` -- must pass with no API key and no model-provider network access. Run it ONCE per task, as the final gate before the task's last commit, never per step. Judge Maven by its exit code (`echo exit=$?`), never by grepping its output. The chat-web tests start a PostgreSQL container, so Docker must be running for the gate.
- While iterating use warm scoped builds, artifactId form with the colon, `-am` whenever an upstream module changed: `./mvnw -q -pl :nessy-api -am test`, `./mvnw -q -pl :nessy-engine -am test`, `./mvnw -q -pl :nessy-embedding-openai -am test`, `:nessy-embedding-gemini`, `:nessy-embedding-bedrock`, `:nessy-embedding-voyage`, `./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test`, `./mvnw -q -pl :nessy-example-chat-web -am test`. Never the path form (`-pl nessy-embedding/openai`).
- If a scoped run hangs or reports "cannot find symbol" after a signature change, suspect a stale jar in `~/.m2`: `./mvnw -q -pl :nessy-api,:nessy-embedding-spi,:nessy-engine -am install -DskipTests`, then retry.
- Maven runs in the FOREGROUND. Never two Maven processes at once in this worktree. Never poll with `pgrep -f` (it matches its own command line and never ends). Before a build, check once, in the same command, that no example app is running from this checkout: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q ...` -- the bracket keeps the pattern from matching itself; if it prints anything about nessy, stop and ask.
- `spotless:check` runs before compilation, so an unformatted file fails the build with no compiler output. Run `./mvnw -q spotless:apply license:format` before reading any build error and before every commit. `./mvnw -q spotless:check license:check` is what CI runs.
- Every new file carries the Apache license header (copy it from any file in the same module; `license:format` adds it to Java files). If `license:format` touches far more files than the task did, commit the headers alone first.
- Formatting is google-java-format (spotless enforces it). The code below is written close to it; `spotless:apply` settles the rest.
- No warning suppression of any kind (`@SuppressWarnings`, etc.) -- write code that raises no warning. No star imports, including static imports.
- Tests: prose-style snake_case method names in the module's voice; `junit-platform.properties` turns underscores into the display sentence. **No mocking library** -- providers are small scripted classes, SDK clients are JDK proxies or the modules' own seams (`GeminiEmbeddingClient`, `BedrockEmbeddingClient`), Voyage keeps its in-process `HttpServer`. AssertJ.
- Sonar S5778: an `assertThatThrownBy` lambda contains exactly ONE call that can throw; build factories, configs, customizers, ids and lists outside it.
- Assert a collection is non-empty before any `allMatch` / `noneMatch` / `allSatisfy` on it.
- Line numbers cite this branch as it stands before the restack. Where a vendor-properties task inserted code above a cited member (VP Task 8 added a block after each embedder config's last setter; VP Task 2 rewrote `DefaultEmbedderFactory`), find the member by its name; the cited range is its extent today.
- Javadoc: never put a second `/** */` above a declaration that already has one (the first is silently dropped). To change a comment, edit the existing one.
- XML comments may not contain `--`.
- macOS: BSD `sed` has no `\b` -- use `perl -pi -e` for word-boundary replacements. zsh does not word-split unquoted variables.
- Docs describe what is -- never history or roads not taken. Dated records under `docs/superpowers/` and released changelog entries are history and stay as written.
- Live (token-spending) tests are `@Tag("live")` and are never run by the controller or an implementer; James runs them with his keys. Their commands are in Task 6.
- Every commit message ends with:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC
  ```
- Branch: `named-embedders`, worktree `/Users/jcarman/IdeaProjects/nessy-embedders`. Commit to it; never push. Never touch `/Users/jcarman/IdeaProjects/nessy`, `/Users/jcarman/IdeaProjects/nessy-responses` or `/Users/jcarman/IdeaProjects/nessy-props`.
- Model policy (repo `CLAUDE.md`): implementers default to Sonnet (Task 4 is transcription: Haiku). Per-task review on Sonnet, except **Task 1 and Task 5, reviewed with Opus**: Task 1 changes a public factory every non-Spring application builds and puts a width check in front of every vector any store will ever write; Task 5 decides which provider every Boot application's stores embed with, across two registries sharing one Spring bean namespace. Scoped re-reviews of small fix diffs on Haiku. Final whole-branch review on Opus.

## Review Focus

The inputs the spec implies but no spec-listed test exercises, most likely to bite first. Each has its test in the owning task.

1. **A pass-through at or under a field the adapter writes** (`openai.input.extra`, `voyage.model.alias`): the nested object would replace the texts or the model the adapter put in the body, and the vendor would embed something else or reject a body nobody wrote. It must be refused when the embedder is made, naming the property. → Task 3, `a_property_under_a_field_the_adapter_writes_is_refused_when_the_embedder_is_made` in each adapter.
2. **An application `EmbeddingProvider` bean named like a lit preset's id** (a bean `voyage` beside `VOYAGE_API_KEY`): two registrations under one registry id. It must fail at startup naming the id, never silently pick one. → Task 5, `an_application_bean_named_like_a_lit_preset_s_id_fails_naming_the_id`.
3. **Both halves of the pair blank** (`nessy.embedder: ${CHAT_EMBEDDER:}` and `nessy.embedding-model: ${CHAT_EMBEDDING_MODEL:}` with neither variable set, chat-web's own shape): that is "no default", the context starts and stores rank by recency -- not a pair error; one blank and one set is a pair error. → Task 5, `a_blank_pair_is_no_default` and `a_blank_embedder_beside_a_model_fails_naming_the_pair`.
4. **A reply in which only a later vector has the wrong width** (a server returning a mixed batch): every vector is checked against the width asked for, not the first alone. → Task 1, `a_batch_with_one_vector_of_the_wrong_width_fails`.
5. **The factory default's properties reaching a store that names another provider** (`voyage.truncation` on the default, a store naming `openai`): the OpenAI adapter must ignore another prefix, not refuse it and not send it. → Task 3, `another_adapter_s_property_is_not_sent` in each adapter.

---

### Task 1: The engine -- a factory of named providers, the wrap, the width check

Spec §4a, §5a, §5b, §5c, §5e, §5f, §11b; Plan rulings 2-4. Every caller of the three old constructors moves in this commit so the reactor stays green: the four adapters' unit and live tests, and the three Boot auto-configurations (which keep reading `nessy.embedding.*` until Task 5 deletes them -- the spec's step-1 bridge). Suggested implementer: Sonnet. **Review: Opus** (see Global Constraints).

**Files:**
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/ProviderId.java` (class javadoc, lines 18-25)
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/embedding/EmbedderConfig.java`
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/embedding/EmbedderFactory.java` (class javadoc, lines 20-26)
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/embedding/EmbedderFactoryConfig.java`
- Modify (rewrite): `nessy-engine/src/main/java/org/jwcarman/nessy/engine/embedding/DefaultEmbedderFactory.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/embedding/DefaultEmbedder.java` (`learn`, lines 86-91)
- Test (rewrite): `nessy-engine/src/test/java/org/jwcarman/nessy/engine/embedding/DefaultEmbedderFactoryTest.java`
- Test: `nessy-embedding/openai/src/test/java/org/jwcarman/nessy/embedding/openai/OpenAiEmbeddingProviderTest.java`, `OpenAiEmbedderLiveTest.java`
- Test: `nessy-embedding/gemini/src/test/java/org/jwcarman/nessy/embedding/gemini/GeminiEmbedderTest.java`, `GeminiEmbedderLiveTest.java`
- Test: `nessy-embedding/bedrock/src/test/java/org/jwcarman/nessy/embedding/bedrock/BedrockEmbedderTest.java`, `BedrockEmbedderLiveTest.java`
- Test: `nessy-embedding/voyage/src/test/java/org/jwcarman/nessy/embedding/voyage/VoyageEmbedderTest.java`, `VoyageEmbedderLiveTest.java`
- Modify: `nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/embedding/OpenAiEmbeddingAutoConfiguration.java`, `GeminiEmbeddingAutoConfiguration.java`, `VoyageEmbeddingAutoConfiguration.java` (the private `factory(...)` helper in each)

**Interfaces:**
- Consumes (VP Tasks 1-2): `EmbeddingOptions(String modelName, OptionalInt dimension, Map<String, String> properties)` plus `EmbeddingOptions(String, OptionalInt)` and `of(String)`; `EmbeddingProvider.validate(EmbeddingOptions)` (default no-op); `EmbedderConfig.property(String, String)`; `ObservedEmbedder.wrap(Embedder, ObservationRegistry)` (exists, `ObservedEmbedder.java:64`).
- Produces (public, `nessy-api`): `EmbedderConfig provider(ProviderId id)`; `default EmbedderConfig provider(String id)`.
- Produces (public, `org.jwcarman.nessy.engine.embedding`): `final class EmbedderFactoryConfig` with `provider(ProviderId, EmbeddingProvider)`, `embedding(ProviderId, EmbeddingOptions)`, `observations(ObservationRegistry)`; `static DefaultEmbedderFactory of(Customizer<EmbedderFactoryConfig>)` and `of(List<Customizer<EmbedderFactoryConfig>>)`. The three public constructors are gone.
- Produces (behaviour later tasks rely on): `create` resolves the provider (`IllegalStateException`: `an embedder names no provider and the factory has no default; registered: [a, b]` / `an embedder names provider 'x', which is not registered; registered: [a, b]` / `an embedder needs a model: model(...), or a factory default`), calls `provider.validate(options)` (its `IllegalArgumentException` escapes unchanged), and returns `ObservedEmbedder.wrap(new DefaultEmbedder(provider, options), observations)`. A reply whose vector width differs from a width asked for throws `IllegalStateException("asked for 256 coordinates, the model returned 768")`.

- [ ] **Step 1: Write the failing engine test**

Replace the whole of `DefaultEmbedderFactoryTest.java` (keep the license header) with:

```java
package org.jwcarman.nessy.engine.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.EmbedderConfig;
import org.jwcarman.nessy.api.embedding.Embedding;
import org.jwcarman.nessy.embedding.EmbeddingOptions;
import org.jwcarman.nessy.embedding.EmbeddingProvider;
import org.jwcarman.nessy.engine.observability.ObservedEmbedder;

/**
 * Which provider a store's embedder is minted over, what it is asked with, and what comes back.
 *
 * <p>A store names a provider and a model, or takes the factory's defaults; either way the provider
 * is resolved once, when the embedder is made, and a width asked for is a width received.
 */
@DisplayName("Embedders over named providers")
class DefaultEmbedderFactoryTest {

  private static final ProviderId OPENAI = ProviderId.of("openai");
  private static final ProviderId VOYAGE = ProviderId.of("voyage");

  /**
   * Remembers what it was asked and validated. Answers with vectors as wide as it was asked for, or
   * three wide when asked for no width -- unless given a fixed width, which it answers with whatever
   * it was asked, as a server that ignores {@code dimensions} does.
   */
  private static final class Asked implements EmbeddingProvider {
    final AtomicReference<EmbeddingOptions> lastAsked = new AtomicReference<>();
    final List<EmbeddingOptions> validated = new ArrayList<>();
    private final String vendor;
    private final int fixedWidth;

    Asked() {
      this("asked", 0);
    }

    Asked(String vendor, int fixedWidth) {
      this.vendor = vendor;
      this.fixedWidth = fixedWidth;
    }

    private Embedding answer(EmbeddingOptions asked) {
      lastAsked.set(asked);
      float[] vector = new float[fixedWidth > 0 ? fixedWidth : asked.dimension().orElse(3)];
      Arrays.fill(vector, 1f);
      return new Embedding(asked.modelName(), vector);
    }

    @Override
    public List<Embedding> embedDocuments(List<String> texts, EmbeddingOptions options) {
      return texts.stream().map(text -> answer(options)).toList();
    }

    @Override
    public Embedding embedQuery(String query, EmbeddingOptions options) {
      return answer(options);
    }

    @Override
    public String vendor() {
      return vendor;
    }

    @Override
    public void validate(EmbeddingOptions options) {
      if (options.properties().containsKey("test.model")) {
        throw new IllegalArgumentException("property 'test.model' is refused");
      }
      validated.add(options);
    }
  }

  /** Answers a batch of two with a vector two wide, then one three wide: a mixed reply. */
  private static final class Mixed implements EmbeddingProvider {
    @Override
    public List<Embedding> embedDocuments(List<String> texts, EmbeddingOptions options) {
      return List.of(
          new Embedding(options.modelName(), new float[] {1f, 1f}),
          new Embedding(options.modelName(), new float[] {1f, 1f, 1f}));
    }

    @Override
    public Embedding embedQuery(String query, EmbeddingOptions options) {
      return new Embedding(options.modelName(), new float[] {1f, 1f});
    }

    @Override
    public String vendor() {
      return "mixed";
    }
  }

  /** One provider, registered as {@code openai}, and the factory's default on it. */
  private static DefaultEmbedderFactory over(Asked provider, EmbeddingOptions defaults) {
    return DefaultEmbedderFactory.of(f -> f.provider(OPENAI, provider).embedding(OPENAI, defaults));
  }

  // ---- widths ---------------------------------------------------------------------------

  @Test
  void inherit_the_factory_default_width_when_they_ask_for_none() {
    Asked provider = new Asked();
    over(provider, new EmbeddingOptions("a-model", OptionalInt.of(1024)))
        .create(c -> {})
        .embedDocument("anything");

    assertThat(provider.lastAsked.get().dimension()).hasValue(1024);
    assertThat(provider.lastAsked.get().modelName()).isEqualTo("a-model");
  }

  @Test
  void keep_their_own_width_when_they_ask_for_one() {
    Asked provider = new Asked();
    over(provider, new EmbeddingOptions("a-model", OptionalInt.of(1024)))
        .create(c -> c.dimension(256))
        .embedDocument("anything");

    assertThat(provider.lastAsked.get().dimension()).hasValue(256);
  }

  /** A factory with no opinion leaves the width to the model, which is most of them. */
  @Test
  void ask_for_no_width_when_neither_the_factory_nor_the_embedder_named_one() {
    Asked provider = new Asked();
    over(provider, EmbeddingOptions.of("a-model")).create(c -> {}).embedDocument("anything");

    assertThat(provider.lastAsked.get().dimension()).isEmpty();
  }

  // ---- properties (VP Task 2's two cases, on the new construction) ------------------------

  @Test
  void carry_their_properties_to_the_provider() {
    Asked provider = new Asked();
    over(provider, EmbeddingOptions.of("a-model"))
        .create(c -> c.property("voyage.truncation", "false"))
        .embedDocument("anything");

    assertThat(provider.lastAsked.get().properties())
        .containsExactly(Map.entry("voyage.truncation", "false"));
  }

  @Test
  void are_refused_when_the_provider_refuses_their_terms() {
    DefaultEmbedderFactory factory = over(new Asked(), EmbeddingOptions.of("a-model"));
    Customizer<EmbedderConfig> refused = c -> c.property("test.model", "x");

    assertThatThrownBy(() -> factory.create(refused))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("property 'test.model' is refused");
  }

  @Test
  void the_factory_default_properties_seed_every_embedder() {
    Asked provider = new Asked();
    over(provider, new EmbeddingOptions("a-model", OptionalInt.empty(), Map.of("x.a", "1")))
        .create(c -> c.property("x.b", "2"))
        .embedDocument("anything");

    assertThat(provider.lastAsked.get().properties()).containsOnlyKeys("x.a", "x.b");
  }

  // ---- which provider ---------------------------------------------------------------------

  @Test
  void a_store_that_names_a_registered_provider_gets_that_one() {
    Asked openai = new Asked("openai", 0);
    Asked voyage = new Asked("voyage", 0);
    DefaultEmbedderFactory factory =
        DefaultEmbedderFactory.of(
            f ->
                f.provider(OPENAI, openai)
                    .provider(VOYAGE, voyage)
                    .embedding(OPENAI, EmbeddingOptions.of("text-embedding-3-small")));

    Embedder embedder = factory.create(c -> c.provider("voyage").model("voyage-3.5"));
    embedder.embedDocument("anything");

    assertThat(embedder.vendor()).isEqualTo("voyage");
    assertThat(voyage.lastAsked.get().modelName()).isEqualTo("voyage-3.5");
    assertThat(openai.lastAsked.get()).isNull();
  }

  @Test
  void a_store_that_names_nothing_gets_the_factory_default() {
    Asked openai = new Asked("openai", 0);
    Asked voyage = new Asked("voyage", 0);
    DefaultEmbedderFactory factory =
        DefaultEmbedderFactory.of(
            f ->
                f.provider(OPENAI, openai)
                    .provider(VOYAGE, voyage)
                    .embedding(VOYAGE, EmbeddingOptions.of("voyage-3.5")));

    Embedder embedder = factory.create(c -> {});
    embedder.embedDocument("anything");

    assertThat(embedder.vendor()).isEqualTo("voyage");
    assertThat(embedder.model()).isEqualTo("voyage-3.5");
    assertThat(openai.lastAsked.get()).isNull();
  }

  /** Plan ruling 4: the defaults seed each field on its own, as an agent type's do. */
  @Test
  void a_store_naming_only_a_provider_keeps_the_default_model() {
    Asked openai = new Asked("openai", 0);
    Asked voyage = new Asked("voyage", 0);
    DefaultEmbedderFactory factory =
        DefaultEmbedderFactory.of(
            f ->
                f.provider(OPENAI, openai)
                    .provider(VOYAGE, voyage)
                    .embedding(OPENAI, EmbeddingOptions.of("a-model")));

    factory.create(c -> c.provider(VOYAGE)).embedDocument("anything");

    assertThat(voyage.lastAsked.get().modelName()).isEqualTo("a-model");
  }

  @Test
  void a_store_naming_an_unknown_provider_fails_listing_every_registered_one() {
    DefaultEmbedderFactory factory =
        DefaultEmbedderFactory.of(f -> f.provider(OPENAI, new Asked()).provider(VOYAGE, new Asked()));
    Customizer<EmbedderConfig> cohere = c -> c.provider("cohere").model("embed-v4");

    assertThatThrownBy(() -> factory.create(cohere))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "an embedder names provider 'cohere', which is not registered; registered: [openai,"
                + " voyage]");
  }

  @Test
  void a_store_naming_no_provider_from_a_factory_with_no_default_fails_listing_what_is_registered() {
    DefaultEmbedderFactory factory =
        DefaultEmbedderFactory.of(f -> f.provider(OPENAI, new Asked()).provider(VOYAGE, new Asked()));
    Customizer<EmbedderConfig> modelOnly = c -> c.model("a-model");

    assertThatThrownBy(() -> factory.create(modelOnly))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "an embedder names no provider and the factory has no default; registered: [openai,"
                + " voyage]");
  }

  @Test
  void a_factory_with_nothing_registered_says_so() {
    DefaultEmbedderFactory factory = DefaultEmbedderFactory.of(f -> {});
    Customizer<EmbedderConfig> nothing = c -> {};

    assertThatThrownBy(() -> factory.create(nothing))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "an embedder names no provider and the factory has no default; registered: []");
  }

  @Test
  void a_store_naming_no_model_from_a_factory_with_no_default_fails() {
    DefaultEmbedderFactory factory = DefaultEmbedderFactory.of(f -> f.provider(OPENAI, new Asked()));
    Customizer<EmbedderConfig> providerOnly = c -> c.provider(OPENAI);

    assertThatThrownBy(() -> factory.create(providerOnly))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("an embedder needs a model: model(...), or a factory default");
  }

  @Test
  void registering_one_id_twice_fails_at_registration() {
    Asked first = new Asked();
    Asked second = new Asked();
    Customizer<EmbedderFactoryConfig> twice = f -> f.provider(OPENAI, first).provider(OPENAI, second);

    assertThatThrownBy(() -> DefaultEmbedderFactory.of(twice))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("embedding provider 'openai' is already registered");
  }

  @Test
  void two_stores_on_one_id_share_the_provider_and_have_their_own_wrappers() {
    Asked provider = new Asked();
    DefaultEmbedderFactory factory = over(provider, EmbeddingOptions.of("a-model"));

    Embedder notes = factory.create(c -> c.model("small"));
    Embedder episodes = factory.create(c -> c.model("large"));
    notes.embedDocument("a");
    String askedFirst = provider.lastAsked.get().modelName();
    episodes.embedDocument("b");

    assertThat(notes).isNotSameAs(episodes);
    assertThat(notes).isInstanceOf(ObservedEmbedder.class);
    assertThat(episodes).isInstanceOf(ObservedEmbedder.class);
    assertThat(askedFirst).isEqualTo("small");
    assertThat(provider.lastAsked.get().modelName()).isEqualTo("large");
  }

  // ---- validate, and the wrap -----------------------------------------------------------

  @Test
  void validate_is_asked_with_the_resolved_options_before_anything_is_embedded() {
    Asked provider = new Asked();
    over(provider, EmbeddingOptions.of("a-model")).create(c -> c.dimension(8));

    assertThat(provider.validated)
        .containsExactly(new EmbeddingOptions("a-model", OptionalInt.of(8), Map.of()));
    assertThat(provider.lastAsked.get()).isNull();
  }

  @Test
  void with_a_registry_every_embedder_is_observed() {
    DefaultEmbedderFactory factory =
        DefaultEmbedderFactory.of(
            f ->
                f.provider(OPENAI, new Asked())
                    .embedding(OPENAI, EmbeddingOptions.of("a-model"))
                    .observations(ObservationRegistry.create()));

    assertThat(factory.create(c -> {})).isInstanceOf(ObservedEmbedder.class);
  }

  /** Observed either way: with nothing configured the registry is the no-op one. */
  @Test
  void without_one_every_embedder_is_still_observed() {
    assertThat(over(new Asked(), EmbeddingOptions.of("a-model")).create(c -> {}))
        .isInstanceOf(ObservedEmbedder.class);
  }

  // ---- a width asked for is a width received (§5f) ---------------------------------------

  @Test
  void a_width_asked_for_and_not_received_fails_naming_both_on_every_call() {
    Asked server = new Asked("lmstudio", 768);
    Embedder embedder =
        over(server, new EmbeddingOptions("nomic", OptionalInt.of(256))).create(c -> {});
    List<String> one = List.of("a lake monster");

    assertThatThrownBy(() -> embedder.embedDocuments(one))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("asked for 256 coordinates, the model returned 768");
    assertThatThrownBy(() -> embedder.embedQuery("where does it live"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("asked for 256 coordinates, the model returned 768");
  }

  @Test
  void an_embedder_that_asked_for_no_width_learns_the_model_s() {
    Embedder embedder =
        over(new Asked("lmstudio", 768), EmbeddingOptions.of("nomic")).create(c -> {});

    assertThat(embedder.dimension()).isZero();
    embedder.embedDocument("a lake monster");

    assertThat(embedder.dimension()).isEqualTo(768);
  }

  /** Review Focus 4: every vector in a reply is checked, not the first alone. */
  @Test
  void a_batch_with_one_vector_of_the_wrong_width_fails() {
    Embedder embedder =
        DefaultEmbedderFactory.of(
                f ->
                    f.provider(OPENAI, new Mixed())
                        .embedding(OPENAI, new EmbeddingOptions("m", OptionalInt.of(2))))
            .create(c -> {});
    List<String> two = List.of("a", "b");

    assertThatThrownBy(() -> embedder.embedDocuments(two))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("asked for 2 coordinates, the model returned 3");
  }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -pl :nessy-engine -am test -Dtest=DefaultEmbedderFactoryTest -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: compilation failure (`cannot find symbol: method of(...)`, `cannot find symbol: class EmbedderFactoryConfig`, `cannot find symbol: method provider(...)`); `exit=1`.

- [ ] **Step 3: The API**

`ProviderId.java` -- replace the class javadoc (lines 18-25) with:

```java
/**
 * The name an application gives one of its providers, inference or embedding: {@code openai},
 * {@code xai}, {@code openai-batch}, {@code voyage}.
 *
 * <p>Ours, not the vendor's. Two providers can speak to the same vendor -- two OpenAI keys with
 * different quotas -- and report the same vendor to a trace, but each has its own id, and an agent
 * type or a store names the one it wants by it. Inference providers and embedding providers are
 * registered in two separate registries, so one id may name one of each.
 */
```

`EmbedderConfig.java` -- replace the class javadoc with the one below, add `import org.jwcarman.nessy.api.ProviderId;`, and add the two methods as the first members of the interface (leave `model`, `dimension` and VP Task 2's `property` as they are):

```java
/**
 * What varies between one embedder and the next.
 *
 * <p>Which registered provider makes the vectors, which model, how many coordinates, and any vendor
 * properties. The connections are the factory's, registered once under the names a store asks for
 * them by.
 */
public interface EmbedderConfig {

  /**
   * Which of the factory's embedding providers makes the vectors. Defaults to the factory's; an
   * embedder that names none, made by a factory with no default, fails when it is made, listing
   * what is registered.
   */
  EmbedderConfig provider(ProviderId id);

  /** {@link #provider(ProviderId)}, by name. */
  default EmbedderConfig provider(String id) {
    return provider(ProviderId.of(id));
  }
```

`EmbedderFactory.java` -- replace the class javadoc (lines 20-26) with:

```java
/**
 * Makes embedders over the embedding providers it holds by name.
 *
 * <p>Each provider is one connection, registered once; a store names the provider and the model it
 * wants, or takes the factory's defaults, and gets an embedder of its own. A notebook and an
 * episode log can be keyed on different models -- from different vendors -- without either of them
 * owning a client, which is what makes changing one a change to that store rather than to the
 * application.
 */
```

- [ ] **Step 4: `EmbedderFactoryConfig`**

Create `nessy-engine/src/main/java/org/jwcarman/nessy/engine/embedding/EmbedderFactoryConfig.java` (license header, then):

```java
package org.jwcarman.nessy.engine.embedding;

import io.micrometer.observation.ObservationRegistry;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.embedding.EmbeddingOptions;
import org.jwcarman.nessy.embedding.EmbeddingProvider;

/**
 * Everything an embedder factory is built from, in one place a customizer can reach: the embedding
 * providers by name, the defaults a store that says nothing gets, and where every embedder reports
 * its work.
 *
 * <p>The counterpart of {@code DirectHarnessFactoryConfig}'s {@code provider} and {@code inference}
 * for embeddings: the id and the terms given together, so a default model cannot be set without
 * saying whose it is.
 */
public final class EmbedderFactoryConfig {

  private final Map<ProviderId, EmbeddingProvider> providers = new LinkedHashMap<>();
  private @Nullable ProviderId defaultProvider;
  private @Nullable EmbeddingOptions defaultOptions;
  private ObservationRegistry observations = ObservationRegistry.NOOP;

  EmbedderFactoryConfig() {}

  /**
   * One of the providers this factory's embedders may be minted over, under the name a store will
   * ask for it by. Repeatable; the same id twice fails at once.
   */
  public EmbedderFactoryConfig provider(ProviderId id, EmbeddingProvider provider) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(provider, "provider must not be null");
    if (providers.putIfAbsent(id, provider) != null) {
      throw new IllegalArgumentException(
          "embedding provider '" + id.value() + "' is already registered");
    }
    return this;
  }

  /**
   * What an embedder gets when it says nothing: which provider, which model, how wide, which vendor
   * properties. Optional; without it every store names a provider and a model itself.
   */
  public EmbedderFactoryConfig embedding(ProviderId provider, EmbeddingOptions options) {
    this.defaultProvider = Objects.requireNonNull(provider, "provider must not be null");
    this.defaultOptions = Objects.requireNonNull(options, "options must not be null");
    return this;
  }

  /**
   * Where every embedder this factory mints reports its calls. Defaults to {@link
   * ObservationRegistry#NOOP}: every embedder is wrapped either way, and a no-op registry costs a
   * check per call.
   */
  public EmbedderFactoryConfig observations(ObservationRegistry observations) {
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
    return this;
  }

  // ---- what the factory reads ------------------------------------------------------------

  Map<ProviderId, EmbeddingProvider> providers() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(providers));
  }

  @Nullable ProviderId defaultProvider() {
    return defaultProvider;
  }

  @Nullable EmbeddingOptions defaultOptions() {
    return defaultOptions;
  }

  ObservationRegistry observations() {
    return observations;
  }
}
```

- [ ] **Step 5: `DefaultEmbedderFactory`**

Replace the whole of `DefaultEmbedderFactory.java` (keep the license header) with the following. It keeps VP Task 2's `property` method and `provider.validate(options)` call, rebuilt on the new construction:

```java
package org.jwcarman.nessy.engine.embedding;

import io.micrometer.observation.ObservationRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.EmbedderConfig;
import org.jwcarman.nessy.api.embedding.EmbedderFactory;
import org.jwcarman.nessy.embedding.EmbeddingOptions;
import org.jwcarman.nessy.embedding.EmbeddingProvider;
import org.jwcarman.nessy.engine.observability.ObservedEmbedder;

/**
 * Embedders over the embedding providers an application registered by name.
 *
 * <p>The only implementation there needs to be, because nothing here is a vendor's business: the
 * connections are the providers' and the model is the store's. A store's provider is resolved
 * exactly once, when its embedder is made; nothing downstream of that sees an id. Every embedder
 * is handed out observed, so an application that gives the factory a registry gets spans without
 * knowing the wrapper's name.
 */
public final class DefaultEmbedderFactory implements EmbedderFactory {

  private final Map<ProviderId, EmbeddingProvider> providers;
  private final @Nullable ProviderId defaultProvider;
  private final @Nullable EmbeddingOptions defaultOptions;
  private final ObservationRegistry observations;

  private DefaultEmbedderFactory(EmbedderFactoryConfig config) {
    this.providers = config.providers();
    this.defaultProvider = config.defaultProvider();
    this.defaultOptions = config.defaultOptions();
    this.observations = config.observations();
  }

  /**
   * One factory, from every customizer that has something to say about it, in order, each adding
   * to the same config before anything is built from it.
   */
  public static DefaultEmbedderFactory of(List<Customizer<EmbedderFactoryConfig>> customizers) {
    Objects.requireNonNull(customizers, "customizers must not be null");
    EmbedderFactoryConfig config = new EmbedderFactoryConfig();
    customizers.forEach(customizer -> customizer.customize(config));
    return new DefaultEmbedderFactory(config);
  }

  /** One customizer, for a caller that is not a container. */
  public static DefaultEmbedderFactory of(Customizer<EmbedderFactoryConfig> customizer) {
    return of(List.of(Objects.requireNonNull(customizer, "customizer must not be null")));
  }

  /**
   * Resolves the provider, then the model, asks the provider whether it can honour the terms, and
   * mints an observed embedder. Every failure happens here, where a store is built, rather than at
   * its first write.
   */
  @Override
  public Embedder create(Customizer<EmbedderConfig> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    Settings settings = new Settings();
    customizer.customize(settings);
    EmbeddingProvider provider = resolve(settings.provider);
    EmbeddingOptions options = settings.options();
    provider.validate(options);
    return ObservedEmbedder.wrap(new DefaultEmbedder(provider, options), observations);
  }

  private EmbeddingProvider resolve(@Nullable ProviderId named) {
    if (named == null) {
      throw new IllegalStateException(
          "an embedder names no provider and the factory has no default; registered: "
              + registered());
    }
    EmbeddingProvider provider = providers.get(named);
    if (provider == null) {
      throw new IllegalStateException(
          "an embedder names provider '"
              + named.value()
              + "', which is not registered; registered: "
              + registered());
    }
    return provider;
  }

  private String registered() {
    return providers.keySet().stream()
        .map(ProviderId::value)
        .collect(Collectors.joining(", ", "[", "]"));
  }

  /** One store's say, seeded field by field from the factory's defaults. */
  private final class Settings implements EmbedderConfig {

    private @Nullable ProviderId provider = defaultProvider;
    private @Nullable String model = defaultOptions == null ? null : defaultOptions.modelName();
    private OptionalInt dimension =
        defaultOptions == null ? OptionalInt.empty() : defaultOptions.dimension();
    private final Map<String, String> properties =
        new LinkedHashMap<>(defaultOptions == null ? Map.of() : defaultOptions.properties());

    @Override
    public EmbedderConfig provider(ProviderId id) {
      this.provider = Objects.requireNonNull(id, "id must not be null");
      return this;
    }

    @Override
    public EmbedderConfig model(String model) {
      this.model = Objects.requireNonNull(model, "model must not be null");
      return this;
    }

    @Override
    public EmbedderConfig dimension(int dimension) {
      this.dimension = OptionalInt.of(dimension);
      return this;
    }

    @Override
    public EmbedderConfig property(String name, String value) {
      Objects.requireNonNull(name, "name must not be null");
      Objects.requireNonNull(value, "value must not be null");
      if (name.isBlank()) {
        throw new IllegalArgumentException("name must not be blank");
      }
      if (value.isBlank()) {
        throw new IllegalArgumentException("value must not be blank");
      }
      properties.put(name, value);
      return this;
    }

    private EmbeddingOptions options() {
      if (model == null) {
        throw new IllegalStateException(
            "an embedder needs a model: model(...), or a factory default");
      }
      return new EmbeddingOptions(model, dimension, properties);
    }
  }
}
```

- [ ] **Step 6: The width check**

`DefaultEmbedder.java` -- replace `learn` (lines 86-91) with:

```java
  /**
   * Checks every vector against the width asked for, and learns the width when none was.
   *
   * <p>A server that ignores the width it is asked for answers at its model's own, and a store that
   * sized an index from {@link #dimension()} would fill it with vectors of the wrong shape. So a
   * difference fails here, on every reply rather than the first: the check is one comparison per
   * vector, and what it prevents is an index nothing can rank.
   */
  private List<Embedding> learn(List<Embedding> embeddings) {
    int asked = options.dimension().orElse(0);
    if (asked > 0) {
      for (Embedding embedding : embeddings) {
        if (embedding.dimension() != asked) {
          throw new IllegalStateException(
              "asked for " + asked + " coordinates, the model returned " + embedding.dimension());
        }
      }
    }
    if (dimension == 0 && !embeddings.isEmpty()) {
      dimension = embeddings.getFirst().dimension();
    }
    return embeddings;
  }
```

- [ ] **Step 7: Run the engine test to see it pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-engine -am test -Dtest=DefaultEmbedderFactoryTest -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: `exit=0`.

- [ ] **Step 8: Move the adapters' tests onto the new construction**

The old constructors are gone, so every adapter test that built a factory stops compiling; four of them also asked for a width the scripted reply did not have, which the width check now refuses. Each module's test sources already see `nessy-api` and `nessy-embedding-spi` through the adapter's own dependencies.

`OpenAiEmbeddingProviderTest.java` -- add imports `org.jwcarman.nessy.api.ProviderId`, `org.jwcarman.nessy.embedding.EmbeddingOptions`; replace the `embedderOver` helper (lines 43-51) with:

```java
  private static final ProviderId OPENAI = ProviderId.of("openai");

  /**
   * An embedder over a provider, which is how one is made: the connection is the provider's, the
   * model is the caller's.
   */
  private static Embedder embedderOver(Customizer<OpenAiEmbedderConfig> connection) {
    OpenAiEmbeddingProvider provider = OpenAiEmbeddingProvider.of(connection);
    return DefaultEmbedderFactory.of(
            f ->
                f.provider(OPENAI, provider)
                    .embedding(OPENAI, EmbeddingOptions.of(OpenAiEmbedderConfig.DEFAULT_MODEL)))
        .create(c -> {});
  }
```

and replace `a_model_and_a_dimension_asked_for_are_sent_and_reported` (lines 152-172) with:

```java
    @Test
    void a_model_and_a_dimension_asked_for_are_sent_and_reported() {
      AtomicReference<EmbeddingCreateParams> sent = new AtomicReference<>();
      OpenAiEmbeddingProvider provider =
          OpenAiEmbeddingProvider.of(
              c ->
                  c.client(
                      fakeClient(
                          params -> {
                            sent.set(params);
                            return reply(item(0, 1f, 2f));
                          },
                          new AtomicBoolean())));
      Embedder embedder =
          DefaultEmbedderFactory.of(f -> f.provider(OPENAI, provider))
              .create(c -> c.provider(OPENAI).model("text-embedding-3-large").dimension(2));

      assertThat(embedder.model()).isEqualTo("text-embedding-3-large");
      assertThat(embedder.dimension()).isEqualTo(2);
      embedder.embedDocument("x");
      assertThat(sent.get().dimensions()).contains(2L);
    }
```

`GeminiEmbedderTest.java` -- add imports `org.jwcarman.nessy.api.ProviderId`, `org.jwcarman.nessy.embedding.EmbeddingOptions`; replace the helper (lines 40-43) with:

```java
  private static final ProviderId GEMINI = ProviderId.of("gemini");

  /** An embedder over a provider: the connection is the provider's, the model the caller's. */
  private static Embedder embedderOver(GeminiEmbeddingProvider provider, String model) {
    return DefaultEmbedderFactory.of(
            f -> f.provider(GEMINI, provider).embedding(GEMINI, EmbeddingOptions.of(model)))
        .create(c -> {});
  }
```

and replace `a_dimension_and_a_task_type_asked_for_are_sent` (lines 106-122) with:

```java
    @Test
    void a_dimension_and_a_task_type_asked_for_are_sent() {
      AtomicReference<Sent> sent = new AtomicReference<>();
      GeminiEmbeddingProvider provider =
          new GeminiEmbeddingProvider(
              scripted(reply(new float[] {1, 0}), sent, new AtomicBoolean()), "RETRIEVAL_QUERY");
      Embedder embedder =
          DefaultEmbedderFactory.of(f -> f.provider(GEMINI, provider))
              .create(c -> c.provider(GEMINI).model("m").dimension(2));

      assertThat(embedder.dimension()).isEqualTo(2);
      embedder.embedDocument("x");

      assertThat(sent.get().config().outputDimensionality()).contains(2);
      assertThat(sent.get().config().taskType()).contains("RETRIEVAL_QUERY");
    }
```

`VoyageEmbedderTest.java` -- add imports `org.jwcarman.nessy.api.ProviderId`, `org.jwcarman.nessy.embedding.EmbeddingOptions`; replace the helper (lines 48-51) with:

```java
  private static final ProviderId VOYAGE = ProviderId.of("voyage");

  /** An embedder over a provider: the connection is the provider's, the model the caller's. */
  private static Embedder embedderOver(VoyageEmbeddingProvider provider, String model) {
    return DefaultEmbedderFactory.of(
            f -> f.provider(VOYAGE, provider).embedding(VOYAGE, EmbeddingOptions.of(model)))
        .create(c -> {});
  }
```

and replace `a_dimension_and_an_input_type_asked_for_are_sent` (lines 129-143) with:

```java
    @Test
    void a_dimension_and_an_input_type_asked_for_are_sent() {
      answer = body -> reply(new float[] {1, 0});
      try (VoyageEmbeddingProvider connection = provider(c -> c.inputType("query"))) {
        Embedder embedder =
            DefaultEmbedderFactory.of(f -> f.provider(VOYAGE, connection))
                .create(c -> c.provider(VOYAGE).model("voyage-3.5-lite").dimension(2));

        assertThat(embedder.dimension()).isEqualTo(2);
        embedder.embedDocument("x");

        assertThat(received.getFirst().path("output_dimension").asInt()).isEqualTo(2);
        assertThat(received.getFirst().path("input_type").asString()).isEqualTo("query");
        assertThat(received.getFirst().path("model").asString()).isEqualTo("voyage-3.5-lite");
      }
    }
```

`BedrockEmbedderTest.java` -- add imports `org.jwcarman.nessy.api.ProviderId`, `org.jwcarman.nessy.embedding.EmbeddingOptions`; replace the helper (lines 76-81) with:

```java
  private static final ProviderId BEDROCK = ProviderId.of("bedrock");

  /** An embedder over a provider: the connection is the provider's, the model the caller's. */
  private static Embedder embedder(Scripted client, String model, OptionalInt dimension) {
    BedrockEmbeddingProvider provider =
        new BedrockEmbeddingProvider(client, "search_document", MAPPER);
    return DefaultEmbedderFactory.of(
            f ->
                f.provider(BEDROCK, provider)
                    .embedding(BEDROCK, new EmbeddingOptions(model, dimension)))
        .create(c -> {});
  }
```

and replace `a_dimension_asked_for_is_sent_with_normalisation` (lines 103-113) with:

```java
    @Test
    void a_dimension_asked_for_is_sent_with_normalisation() {
      Scripted client = new Scripted(body -> "{\"embedding\":[0.6,0.8]}");
      Embedder embedder = embedder(client, "amazon.titan-embed-text-v2:0", OptionalInt.of(2));

      embedder.embedDocument("x");

      assertThat(client.sent.getFirst().path("dimensions").asInt()).isEqualTo(2);
      assertThat(client.sent.getFirst().path("normalize").asBoolean()).isTrue();
      assertThat(embedder.dimension()).isEqualTo(2);
    }
```

The four live tests (compiled by every build, run by none here) -- add imports `org.jwcarman.nessy.api.ProviderId`, `org.jwcarman.nessy.embedding.EmbeddingOptions` to each, then:

`OpenAiEmbedderLiveTest.java`: add, after the `MODEL` constant,

```java
  private static final ProviderId OPENAI = ProviderId.of("openai");

  /** An embedder over a provider: the connection is the provider's, the model the caller's. */
  private static Embedder embedderOver(OpenAiEmbeddingProvider provider, String model) {
    return DefaultEmbedderFactory.of(
            f -> f.provider(OPENAI, provider).embedding(OPENAI, EmbeddingOptions.of(model)))
        .create(c -> {});
  }
```

and change line 47 to `Embedder embedder = embedderOver(provider, MODEL);`.

`GeminiEmbedderLiveTest.java` (also import `java.util.OptionalInt`): replace the helper (lines 36-39) with

```java
  private static final ProviderId GEMINI = ProviderId.of("gemini");

  /** An embedder over a provider: the connection is the provider's, the model and width the caller's. */
  private static Embedder embedderOver(
      GeminiEmbeddingProvider provider, String model, int dimension) {
    return DefaultEmbedderFactory.of(
            f ->
                f.provider(GEMINI, provider)
                    .embedding(GEMINI, new EmbeddingOptions(model, OptionalInt.of(dimension))))
        .create(c -> {});
  }
```

and change `embedderOver(provider, MODEL)` to `embedderOver(provider, MODEL, 768)`.

`BedrockEmbedderLiveTest.java`: replace the helper (lines 37-40) with the OpenAI live test's helper above, with `BEDROCK = ProviderId.of("bedrock")` and `BedrockEmbeddingProvider` for the parameter type. `VoyageEmbedderLiveTest.java`: the same, with `VOYAGE = ProviderId.of("voyage")` and `VoyageEmbeddingProvider`. The written-out forms:

```java
  private static final ProviderId BEDROCK = ProviderId.of("bedrock");

  /** An embedder over a provider: the connection is the provider's, the model the caller's. */
  private static Embedder embedderOver(BedrockEmbeddingProvider provider, String model) {
    return DefaultEmbedderFactory.of(
            f -> f.provider(BEDROCK, provider).embedding(BEDROCK, EmbeddingOptions.of(model)))
        .create(c -> {});
  }
```

```java
  private static final ProviderId VOYAGE = ProviderId.of("voyage");

  /** An embedder over a provider: the connection is the provider's, the model the caller's. */
  private static Embedder embedderOver(VoyageEmbeddingProvider provider, String model) {
    return DefaultEmbedderFactory.of(
            f -> f.provider(VOYAGE, provider).embedding(VOYAGE, EmbeddingOptions.of(model)))
        .create(c -> {});
  }
```

- [ ] **Step 9: Move the three Boot auto-configurations onto the new construction**

Each of `OpenAiEmbeddingAutoConfiguration`, `GeminiEmbeddingAutoConfiguration` and `VoyageEmbeddingAutoConfiguration` keeps its beans and its `nessy.embedding.*` properties until Task 5 deletes it. In each: remove the import `org.jwcarman.nessy.engine.observability.ObservedEmbedder`, add `org.jwcarman.nessy.api.ProviderId` and `org.jwcarman.nessy.embedding.EmbeddingOptions`, and replace the private `factory(...)` helper and its javadoc with:

```java
  /**
   * Embedders over one connection, registered under the vendor's id with the configured model as
   * the default; the factory wraps every embedder it mints.
   */
  private static EmbedderFactory factory(
      String id,
      EmbeddingProvider provider,
      String model,
      OptionalInt dimension,
      ObservationRegistry observations) {
    ProviderId providerId = ProviderId.of(id);
    return DefaultEmbedderFactory.of(
        f ->
            f.provider(providerId, provider)
                .embedding(providerId, new EmbeddingOptions(model, dimension))
                .observations(observations));
  }
```

Then pass the id as the first argument at every call: `factory("openai", provider, ...)` in both `OpenAiEmbeddingAutoConfiguration` bean methods, `factory("gemini", provider, ...)` in `GeminiEmbeddingAutoConfiguration.observed`, `factory("voyage", provider, ...)` in `VoyageEmbeddingAutoConfiguration.voyageEmbedders`. `EmbeddingAutoConfigurationTest` is unchanged and must still pass: every assertion it makes holds on the new construction (the wrap moved into the factory, so `defaultEmbedder(context)` is still an `ObservedEmbedder`).

- [ ] **Step 10: Run the moved tests to see them pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-embedding-openai,:nessy-embedding-gemini,:nessy-embedding-bedrock,:nessy-embedding-voyage,:nessy-spring-boot-autoconfigure -am test; echo exit=$?`
Expected: `exit=0`.

- [ ] **Step 11: Full gate and commit**

Run: `git grep -n "new DefaultEmbedderFactory(" -- '*.java'` -- expected: nothing. Then `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?`
Expected: `exit=0`.

```bash
cd /Users/jcarman/IdeaProjects/nessy-embedders
git add nessy-api/src/main nessy-engine/src nessy-embedding/openai/src/test nessy-embedding/gemini/src/test \
  nessy-embedding/bedrock/src/test nessy-embedding/voyage/src/test \
  nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/embedding
git commit -m "feat: an embedder factory holds providers by name, wraps what it mints, and checks widths

DefaultEmbedderFactory.of(EmbedderFactoryConfig) replaces the three
constructors: provider(ProviderId, EmbeddingProvider) registers,
embedding(ProviderId, EmbeddingOptions) sets the default, and
observations(...) drives the ObservedEmbedder wrap inside create.
EmbedderConfig.provider(...) names the provider a store wants; an unknown
or missing one fails at create listing what is registered. A reply whose
width differs from the width asked for fails naming both.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 2: The adapters hold a connection and nothing about a model

Spec §5d, §6e, §11c; Plan ruling 1. The four embedder configs lose `model(...)` / `dimension(...)`, the four providers lose `defaultModel()` / `defaultDimension()`; the `DEFAULT_MODEL` constants stay for a caller to cite. `OpenAiEmbedderConfig` gains `vendor(String)`. Suggested implementer: Sonnet. Review: Sonnet.

**Files:**
- Modify: `nessy-embedding/openai/src/main/java/org/jwcarman/nessy/embedding/openai/OpenAiEmbedderConfig.java`, `OpenAiEmbeddingProvider.java`
- Modify: `nessy-embedding/gemini/src/main/java/org/jwcarman/nessy/embedding/gemini/GeminiEmbedderConfig.java`, `GeminiEmbeddingProvider.java`
- Modify: `nessy-embedding/bedrock/src/main/java/org/jwcarman/nessy/embedding/bedrock/BedrockEmbedderConfig.java`, `BedrockEmbeddingProvider.java`
- Modify: `nessy-embedding/voyage/src/main/java/org/jwcarman/nessy/embedding/voyage/VoyageEmbedderConfig.java`, `VoyageEmbeddingProvider.java`
- Test: `OpenAiEmbedderConfigTest.java` (created by VP Task 8), `OpenAiEmbeddingProviderTest.java`, `GeminiEmbedderTest.java`, `BedrockEmbedderTest.java`, `VoyageEmbedderTest.java`, and the live tests of Gemini, Bedrock and Voyage
- Modify: `nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/embedding/OpenAiEmbeddingAutoConfiguration.java`, `GeminiEmbeddingAutoConfiguration.java`, `VoyageEmbeddingAutoConfiguration.java`

**Interfaces:**
- Consumes: Task 1's `DefaultEmbedderFactory.of(...)`.
- Produces (public): `OpenAiEmbedderConfig vendor(String vendor)`; `OpenAiEmbeddingProvider.vendor()` returns it (`openai` by default). Removed: `OpenAiEmbedderConfig.model(String)`, `.dimension(int)`, `GeminiEmbedderConfig.model/dimension`, `BedrockEmbedderConfig.model/dimension`, `VoyageEmbedderConfig.model/dimension`, and `defaultModel()` / `defaultDimension()` on all four providers.
- Produces (package-private constructors Task 3 extends): `OpenAiEmbeddingProvider(OpenAIClient client, boolean ownsClient, String vendor)`; `GeminiEmbeddingProvider(GeminiEmbeddingClient client, String taskType)`; `BedrockEmbeddingProvider(BedrockEmbeddingClient client, String cohereInputType, JsonMapper mapper)`; `VoyageEmbeddingProvider(HttpClient, URI, String, VoyageEmbedderConfig)` (unchanged signature).

- [ ] **Step 1: Write the failing tests**

`OpenAiEmbedderConfigTest.java` (VP Task 8's file) -- add the import `static org.assertj.core.api.Assertions.assertThat` (the file has `assertThatCode` and `assertThatThrownBy` already), then add:

```java
  @Test
  void the_vendor_is_openai_unless_said_otherwise() {
    try (OpenAiEmbeddingProvider provider = OpenAiEmbeddingProvider.of(c -> c.apiKey("test-key"))) {
      assertThat(provider.vendor()).isEqualTo("openai");
    }
  }

  /** A custom openai-wire embedder -- a local server, a gateway -- says who it really is. */
  @Test
  void a_vendor_given_is_the_vendor_reported() {
    try (OpenAiEmbeddingProvider provider =
        OpenAiEmbeddingProvider.of(
            c -> c.apiKey("lm-studio").baseUrl("http://localhost:1234/v1").vendor("lmstudio"))) {
      assertThat(provider.vendor()).isEqualTo("lmstudio");
    }
  }

  @Test
  void a_null_vendor_is_refused() {
    Customizer<OpenAiEmbedderConfig> customizer = c -> c.apiKey("test-key").vendor(null);

    assertThatThrownBy(() -> OpenAiEmbeddingProvider.of(customizer))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("vendor");
  }
```

(`OpenAiEmbeddingProvider.close()` declares no checked exception, so try-with-resources needs no `catch`.)

In the four adapters' existing tests, delete the lines that exercise the removed setters -- these are the whole of this task's test deletions, and they make the tests fail to compile until Step 3 is done, which is the failing state wanted:

- `OpenAiEmbeddingProviderTest.what_is_refused_at_configuration` (lines 204-214): delete the two assertions on `c.model(" ")` and `c.dimension(0)` (lines 208-211).
- `GeminiEmbedderTest.what_is_refused_at_configuration` (lines 169-177): delete the two assertions on `c.model(" ")` and `c.dimension(0)` (lines 173-176). Rename `a_key_a_base_url_a_model_and_a_dimension_build_an_embedder` (line 155) to `a_key_a_base_url_and_a_task_type_build_a_provider` and delete its `.model("gemini-embedding-001")` and `.dimension(768)` lines (162-163).
- `BedrockEmbedderTest.what_is_refused_at_configuration` (lines 244-256): delete the two assertions on `c.model(" ")` and `c.dimension(0)` (lines 246-249). In `a_region_and_credentials_build_an_embedder_and_a_handed_in_client_is_not_closed` delete `.model("cohere.embed-english-v3")` (line 219) and `.dimension(512)` (line 221).
- `VoyageEmbedderTest.what_is_refused_at_configuration` (lines 210-220): delete the two assertions on `c.model(" ")` and `c.dimension(0)` (lines 215-218).
- `GeminiEmbedderLiveTest`: `GeminiEmbeddingProvider.of(c -> c.fromEnv().dimension(768))` becomes `GeminiEmbeddingProvider.of(GeminiEmbedderConfig::fromEnv)` (the width now comes from the factory's default, Task 1).
- `BedrockEmbedderLiveTest`: `BedrockEmbeddingProvider.of(c -> c.fromEnv().model(model))` becomes `BedrockEmbeddingProvider.of(BedrockEmbedderConfig::fromEnv)`.
- `VoyageEmbedderLiveTest`: `VoyageEmbeddingProvider.of(c -> c.fromEnv().model(model))` becomes `VoyageEmbeddingProvider.of(VoyageEmbedderConfig::fromEnv)`.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -pl :nessy-embedding-openai -am test -Dtest=OpenAiEmbedderConfigTest -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: compilation failure (`cannot find symbol: method vendor(String)`); `exit=1`.

- [ ] **Step 3: OpenAI**

`OpenAiEmbedderConfig.java`:
- delete `import java.util.OptionalInt;`, the fields `model` and `dimension` (lines 38-39), the methods `model(String)` (lines 70-78) and `dimension(int)` (lines 80-91), and the package-private `model()` getter with its javadoc (lines 103-106);
- change the `DEFAULT_MODEL` javadoc to: `/** OpenAI's current small model, 1536 dimensions unless asked for fewer: a name to cite, as in {@code EmbeddingOptions.of(OpenAiEmbedderConfig.DEFAULT_MODEL)}. */`
- add a field `private String vendor = "openai";` beside `organization`, and, after `organization(String)`:

```java
  /**
   * The vendor name this provider reports in spans ({@code gen_ai.provider.name}): {@code openai}
   * unless the same wire is being spoken to somebody else -- {@code lmstudio} for a local server, a
   * gateway's own name.
   */
  public OpenAiEmbedderConfig vendor(String vendor) {
    this.vendor = Objects.requireNonNull(vendor, "vendor must not be null");
    return this;
  }
```

- in `build()`, the three constructions become `new OpenAiEmbeddingProvider(client, false, vendor)`, `new OpenAiEmbeddingProvider(buildFromEnv(), true, vendor)` and `new OpenAiEmbeddingProvider(builder.build(), true, vendor)`.

`OpenAiEmbeddingProvider.java`:
- delete `import java.util.OptionalInt;`, the fields `defaultModel` and `defaultDimension`, both constructors (lines 45-56), and `defaultModel()` / `defaultDimension()` with their javadoc (lines 58-71); add:

```java
  private final String vendor;

  OpenAiEmbeddingProvider(OpenAIClient client, boolean ownsClient, String vendor) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.ownsClient = ownsClient;
    this.vendor = Objects.requireNonNull(vendor, "vendor must not be null");
  }
```

- `fromEnv()`'s javadoc becomes `/** {@code OPENAI_API_KEY}, through the SDK's own reading of the environment. */`
- `vendor()` becomes:

```java
  /**
   * {@code openai}, or whatever the config's {@code vendor(...)} said: the same wire at another base
   * URL is somebody else, and a trace should say who.
   */
  @Override
  public String vendor() {
    return vendor;
  }
```

- the class javadoc's second paragraph becomes: `<p>Holds a connection and nothing about a model: which model, and how wide, arrive per call in {@link EmbeddingOptions}, so one provider serves every store that names it.`

- [ ] **Step 4: Gemini**

`GeminiEmbedderConfig.java`: delete `import java.util.OptionalInt;`, the fields `model` and `dimension` (lines 38-39), `model(String)` (lines 64-72), `dimension(int)` (lines 74-81) and the `model()` getter with its javadoc (lines 98-101); change the `DEFAULT_MODEL` javadoc to `/** Google's current embedding model, 3072 dimensions unless asked for fewer: a name to cite, as in {@code EmbeddingOptions.of(GeminiEmbedderConfig.DEFAULT_MODEL)}. */`; `build()` becomes:

```java
  GeminiEmbeddingProvider build() {
    return new GeminiEmbeddingProvider(resolveClient(), taskType);
  }
```

(If VP Task 8 put `requireOwnProperties();` first in `build()`, keep it first.)

`GeminiEmbeddingProvider.java`: delete `import java.util.OptionalInt;`, the fields `defaultModel` / `defaultDimension`, both constructors (lines 44-58) and the two getters (lines 60-68); add:

```java
  GeminiEmbeddingProvider(GeminiEmbeddingClient client, String taskType) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.taskType = taskType;
  }
```

`fromEnv()`'s javadoc becomes `/** {@code GEMINI_API_KEY} or {@code GOOGLE_API_KEY}. */`; the class javadoc's second paragraph becomes the same sentence as OpenAI's above.

- [ ] **Step 5: Bedrock**

`BedrockEmbedderConfig.java`: delete `import java.util.OptionalInt;`, the fields `model` and `dimension` (lines 47-48), `model(String)` (lines 70-78), `dimension(int)` (lines 80-87) and the `model()` getter with its javadoc (lines 105-108); change the `DEFAULT_MODEL` javadoc to `/** Amazon's current embedding model, 1024 dimensions unless asked for 512 or 256: a name to cite, as in {@code EmbeddingOptions.of(BedrockEmbedderConfig.DEFAULT_MODEL)}. Titan ({@code amazon.titan-embed-…}) and Cohere ({@code cohere.embed-…}) are the two families this adapter speaks. */`; `build()` becomes:

```java
  BedrockEmbeddingProvider build() {
    return new BedrockEmbeddingProvider(resolveClient(), cohereInputType, mapper);
  }
```

(keeping VP Task 8's `requireOwnProperties();` first if it is there).

`BedrockEmbeddingProvider.java`: delete `import java.util.OptionalInt;`, the fields `defaultModel` / `defaultDimension`, both constructors (lines 74-91) and the two getters (lines 93-101); add:

```java
  BedrockEmbeddingProvider(
      BedrockEmbeddingClient client, String cohereInputType, JsonMapper mapper) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.cohereInputType = Objects.requireNonNull(cohereInputType, "inputType must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }
```

`fromEnv()`'s javadoc becomes `/** The AWS default credentials chain, and the region from the environment. */`.

- [ ] **Step 6: Voyage**

`VoyageEmbedderConfig.java`: delete `import java.util.OptionalInt;`, the fields `model` and `dimension` (lines 41-42), `model(String)` (lines 68-75), `dimension(int)` (lines 77-84) and the getters `model()` and `dimension()` (lines 128-134); change the `DEFAULT_MODEL` javadoc to `/** Voyage's current general model, 1024 dimensions unless asked for 256, 512 or 2048: a name to cite, as in {@code EmbeddingOptions.of(VoyageEmbedderConfig.DEFAULT_MODEL)}. */`.

`VoyageEmbeddingProvider.java`: delete `import java.util.OptionalInt;`, the fields `defaultModel` / `defaultDimension` (lines 52-53), the two constructor lines that set them (65-66) and the two getters (lines 69-77). `fromEnv()`'s javadoc becomes `/** {@code VOYAGE_API_KEY}. */`.

- [ ] **Step 7: The Boot bridge stops asking the provider for a width**

In `OpenAiEmbeddingAutoConfiguration` (both bean methods), `GeminiEmbeddingAutoConfiguration.observed` and `VoyageEmbeddingAutoConfiguration.voyageEmbedders`, every `EmbeddingModels.dimensionOr(dimension, provider.defaultDimension())` becomes `EmbeddingModels.dimensionOr(dimension, OptionalInt.empty())` (`OptionalInt` is already imported in each).

- [ ] **Step 8: Run them to see them pass**

Run: `git grep -n "defaultModel()\|defaultDimension()" -- '*.java'` -- expected: nothing. Then `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-embedding-openai,:nessy-embedding-gemini,:nessy-embedding-bedrock,:nessy-embedding-voyage,:nessy-spring-boot-autoconfigure -am test; echo exit=$?`
Expected: `exit=0`.

- [ ] **Step 9: Full gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?`
Expected: `exit=0`.

```bash
cd /Users/jcarman/IdeaProjects/nessy-embedders
git add nessy-embedding nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/embedding
git commit -m "feat: an embedding provider holds a connection and nothing about a model

The four embedder configs lose model(...) and dimension(...), and the four
providers lose defaultModel() and defaultDimension(): the factory's default
is the only default. The DEFAULT_MODEL constants stay for a caller to cite.
OpenAiEmbedderConfig.vendor(String) lets a custom openai-wire embedder say
who it really is.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 3: The embedding adapters read their vendor properties

Spec §7c, §11c; VP ruling 13 (the configs carried and ignored their maps until now); Plan rulings 9-11. Each adapter reads the provider's own map overlaid by the embedder's, under its prefix; no name is typed, every one passes through, and a name at or under a field the adapter writes is refused. `validate` runs the same reading, so a mistake fails where the store is built; Bedrock's family check moves into it. Suggested implementer: Sonnet. Review: Sonnet.

**Files:**
- Create: `nessy-embedding/openai/src/main/java/org/jwcarman/nessy/embedding/openai/OpenAiEmbeddingProperties.java`
- Create: `nessy-embedding/gemini/src/main/java/org/jwcarman/nessy/embedding/gemini/GeminiEmbeddingProperties.java`
- Create: `nessy-embedding/bedrock/src/main/java/org/jwcarman/nessy/embedding/bedrock/BedrockEmbeddingProperties.java`
- Create: `nessy-embedding/voyage/src/main/java/org/jwcarman/nessy/embedding/voyage/VoyageEmbeddingProperties.java`
- Modify: the four `XEmbedderConfig.java` (VP Task 8's `properties` block, `build()`) and the four `XEmbeddingProvider.java`
- Test: `OpenAiEmbeddingProviderTest.java`, `GeminiEmbedderTest.java`, `BedrockEmbedderTest.java`, `VoyageEmbedderTest.java`

**Interfaces:**
- Consumes (VP Task 1): `VendorProperties.under(Map<String, String>, String)`, `merge(Map<String, String> provider, Map<String, String> agentType)`, `nest(String prefix, Map<String, String> flat, JsonMapper)`, `refuseClashes(String prefix, Map<String, String> underPrefix, Map<String, String> clashTable)`. (VP Task 8): each config's private `Map<String, String> properties` field, `property`/`properties` setters and private `requireOwnProperties()`. (Task 2): the providers' constructors.
- Produces (package-private): `static Map<String, Object> XEmbeddingProperties.read(Map<String, String> provider, Map<String, String> embedder, JsonMapper mapper)` in each adapter; `OpenAiEmbeddingProperties.MAPPER` and `GeminiEmbeddingProperties.MAPPER` (`static final JsonMapper`); each config's `Map<String, String> properties()` getter; constructors `OpenAiEmbeddingProvider(OpenAIClient, boolean, String vendor, Map<String, String> properties)`, `GeminiEmbeddingProvider(GeminiEmbeddingClient, String taskType, Map<String, String> properties)` (the two-argument one kept, passing `Map.of()`), `BedrockEmbeddingProvider(BedrockEmbeddingClient, String, JsonMapper, Map<String, String> properties)` (the three-argument one kept, passing `Map.of()`).
- Produces (public, behaviour): each provider's `validate(EmbeddingOptions)` refuses a clash, a name with no prefix, or a malformed path with an `IllegalArgumentException` naming the property; `BedrockEmbeddingProvider.validate` also refuses a model of neither family. Every config's `build()` refuses a clash in its own map (the first place Task 4's `WireEmbedders` sees a mistake in `nessy.embedders.<id>.properties.*`).

| adapter | prefix | clash table (name → what decides it) | pass-through lands in |
|---|---|---|---|
| OpenAI | `openai.` | `model` → `EmbedderConfig.model`; `input` → `the texts the store embeds`; `dimensions` → `EmbedderConfig.dimension` | `EmbeddingCreateParams.Builder.putAdditionalBodyProperty` |
| Gemini | `gemini.` | `model` → `EmbedderConfig.model`; `contents` → `the texts the store embeds`; `outputDimensionality` → `EmbedderConfig.dimension`; `taskType` → `GeminiEmbedderConfig.taskType, or the document or query role` | `EmbedContentConfig.Builder.httpOptions(HttpOptions.builder().extraBody(...))` |
| Bedrock | `bedrock.` | `inputText`, `texts` → `the texts the store embeds`; `dimensions` → `EmbedderConfig.dimension`; `normalize` → `the adapter, which normalises a vector it asked a width of`; `input_type` → `BedrockEmbedderConfig.cohereInputType`; `truncate` → `the adapter, which truncates at END` | `ObjectNode.set` on the request body |
| Voyage | `voyage.` | `model` → `EmbedderConfig.model`; `input` → `the texts the store embeds`; `output_dimension` → `EmbedderConfig.dimension`; `input_type` → `VoyageEmbedderConfig.inputType, or the document or query role` | `ObjectNode.set` on the request body |

- [ ] **Step 1: Write the failing OpenAI tests**

`OpenAiEmbeddingProviderTest.java` -- add imports `com.openai.core.JsonValue`, `java.util.Map`, `org.jwcarman.nessy.api.embedding.EmbedderConfig`, then a nested class at the end of the outer class:

```java
  @Nested
  class ItsVendorProperties {

    private final AtomicReference<EmbeddingCreateParams> sent = new AtomicReference<>();

    private OpenAiEmbeddingProvider provider(Customizer<OpenAiEmbedderConfig> more) {
      return OpenAiEmbeddingProvider.of(
          c -> {
            c.client(
                fakeClient(
                    params -> {
                      sent.set(params);
                      return reply(item(0, 1f));
                    },
                    new AtomicBoolean()));
            more.customize(c);
          });
    }

    private DefaultEmbedderFactory factory(OpenAiEmbeddingProvider provider) {
      return DefaultEmbedderFactory.of(
          f ->
              f.provider(OPENAI, provider)
                  .embedding(OPENAI, EmbeddingOptions.of(OpenAiEmbedderConfig.DEFAULT_MODEL)));
    }

    @Test
    void a_provider_s_property_is_sent_in_the_body() {
      factory(provider(c -> c.property("openai.user", "tenant-42")))
          .create(c -> {})
          .embedDocument("x");

      assertThat(sent.get()._additionalBodyProperties().get("user").asString())
          .contains("tenant-42");
    }

    @Test
    void an_embedder_s_property_overrides_the_provider_s_by_name() {
      factory(provider(c -> c.property("openai.user", "provider")))
          .create(c -> c.property("openai.user", "store"))
          .embedDocument("x");

      assertThat(sent.get()._additionalBodyProperties().get("user").asString()).contains("store");
    }

    @Test
    void a_dotted_name_nests_and_a_json_literal_keeps_its_type() {
      factory(provider(c -> {}))
          .create(c -> c.property("openai.extra.depth", "3"))
          .embedDocument("x");

      Map<String, JsonValue> extra =
          sent.get()._additionalBodyProperties().get("extra").asObject().orElseThrow();
      assertThat(extra.get("depth").asNumber().map(Number::intValue)).contains(3);
    }

    /** Review Focus 1: a nested object would replace the texts the adapter put in the body. */
    @Test
    void a_property_under_a_field_the_adapter_writes_is_refused_when_the_embedder_is_made() {
      DefaultEmbedderFactory factory = factory(provider(c -> {}));
      Customizer<EmbedderConfig> underInput = c -> c.property("openai.input.extra", "x");
      Customizer<EmbedderConfig> dimensions = c -> c.property("openai.dimensions", "8");

      assertThatThrownBy(() -> factory.create(underInput))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'openai.input.extra'")
          .hasMessageContaining("the texts the store embeds");
      assertThatThrownBy(() -> factory.create(dimensions))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'openai.dimensions'")
          .hasMessageContaining("EmbedderConfig.dimension");
      assertThat(sent.get()).isNull();
    }

    @Test
    void a_clash_on_the_provider_is_refused_when_it_is_built() {
      Customizer<OpenAiEmbedderConfig> model =
          c -> c.apiKey("test-key").property("openai.model", "text-embedding-3-large");

      assertThatThrownBy(() -> OpenAiEmbeddingProvider.of(model))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'openai.model'")
          .hasMessageContaining("EmbedderConfig.model");
    }

    /** Review Focus 5: a factory default's properties may be another vendor's. */
    @Test
    void another_adapter_s_property_is_not_sent() {
      factory(provider(c -> {}))
          .create(c -> c.property("voyage.truncation", "false"))
          .embedDocument("x");

      assertThat(sent.get()._additionalBodyProperties()).isEmpty();
    }
  }
```

- [ ] **Step 2: Write the failing Gemini tests**

`GeminiEmbedderTest.java` -- add imports `com.google.genai.types.HttpOptions`, `java.util.Map`, `java.util.Optional`, `org.jwcarman.nessy.api.Customizer`, `org.jwcarman.nessy.api.embedding.EmbedderConfig`, then:

```java
  @Nested
  class ItsVendorProperties {

    private final AtomicReference<Sent> sent = new AtomicReference<>();

    private DefaultEmbedderFactory factory(Map<String, String> providerProperties) {
      GeminiEmbeddingProvider provider =
          new GeminiEmbeddingProvider(
              scripted(reply(new float[] {1, 0}), sent, new AtomicBoolean()),
              null,
              providerProperties);
      return DefaultEmbedderFactory.of(
          f ->
              f.provider(GEMINI, provider)
                  .embedding(GEMINI, EmbeddingOptions.of(GeminiEmbedderConfig.DEFAULT_MODEL)));
    }

    private Optional<Map<String, Object>> extraBody() {
      return sent.get().config().httpOptions().flatMap(HttpOptions::extraBody);
    }

    @Test
    void a_provider_s_property_is_sent_as_extra_body() {
      factory(Map.of("gemini.title", "A lake")).create(c -> {}).embedDocument("x");

      assertThat(extraBody())
          .hasValueSatisfying(body -> assertThat(body).containsEntry("title", "A lake"));
    }

    @Test
    void an_embedder_s_property_overrides_the_provider_s_by_name() {
      factory(Map.of("gemini.title", "provider"))
          .create(c -> c.property("gemini.title", "store"))
          .embedDocument("x");

      assertThat(extraBody())
          .hasValueSatisfying(body -> assertThat(body).containsEntry("title", "store"));
    }

    @Test
    void a_dotted_name_nests_and_a_json_literal_keeps_its_type() {
      factory(Map.of()).create(c -> c.property("gemini.extra.depth", "3")).embedDocument("x");

      assertThat(extraBody())
          .hasValueSatisfying(
              body -> assertThat(body).containsEntry("extra", Map.of("depth", 3)));
    }

    /** Review Focus 1. */
    @Test
    void a_property_under_a_field_the_adapter_writes_is_refused_when_the_embedder_is_made() {
      DefaultEmbedderFactory factory = factory(Map.of());
      Customizer<EmbedderConfig> taskType = c -> c.property("gemini.taskType", "CLUSTERING");
      Customizer<EmbedderConfig> underContents = c -> c.property("gemini.contents.parts", "[]");

      assertThatThrownBy(() -> factory.create(taskType))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'gemini.taskType'")
          .hasMessageContaining("GeminiEmbedderConfig.taskType");
      assertThatThrownBy(() -> factory.create(underContents))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'gemini.contents.parts'");
      assertThat(sent.get()).isNull();
    }

    @Test
    void a_clash_on_the_provider_is_refused_when_it_is_built() {
      Customizer<GeminiEmbedderConfig> width =
          c -> c.apiKey("k").property("gemini.outputDimensionality", "8");

      assertThatThrownBy(() -> GeminiEmbeddingProvider.of(width))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'gemini.outputDimensionality'")
          .hasMessageContaining("EmbedderConfig.dimension");
    }

    /** Review Focus 5. */
    @Test
    void another_adapter_s_property_is_not_sent() {
      factory(Map.of()).create(c -> c.property("openai.user", "x")).embedDocument("x");

      assertThat(extraBody()).isEmpty();
    }
  }
```

- [ ] **Step 3: Write the failing Bedrock tests**

`BedrockEmbedderTest.java` -- add imports `java.util.Map`, `org.jwcarman.nessy.api.Customizer`, `org.jwcarman.nessy.api.embedding.EmbedderConfig`. In the nested `Configuration`, replace `a_model_of_an_unknown_family_is_refused_when_it_is_used` and its javadoc (lines 182-200) with:

```java
    /**
     * Refused when the embedder is built: the factory asks the provider to validate its terms, and
     * the model -- so the family -- is known then. Nothing reaches the wire.
     */
    @Test
    void a_model_of_an_unknown_family_is_refused_when_the_embedder_is_built() {
      Scripted client = new Scripted(body -> "{}");
      BedrockEmbeddingProvider provider =
          new BedrockEmbeddingProvider(client, "search_document", MAPPER);
      DefaultEmbedderFactory factory = DefaultEmbedderFactory.of(f -> f.provider(BEDROCK, provider));
      Customizer<EmbedderConfig> llama = c -> c.provider(BEDROCK).model("meta.llama3-8b");

      assertThatThrownBy(() -> factory.create(llama))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Titan")
          .hasMessageContaining("Cohere");
      assertThat(client.sent).isEmpty();
    }
```

and add, at the end of the outer class:

```java
  @Nested
  class ItsVendorProperties {

    private static final StaticCredentialsProvider CREDENTIALS =
        StaticCredentialsProvider.create(AwsBasicCredentials.create("akid", "secret"));

    private DefaultEmbedderFactory factory(
        Scripted client, Map<String, String> providerProperties, String model) {
      BedrockEmbeddingProvider provider =
          new BedrockEmbeddingProvider(client, "search_document", MAPPER, providerProperties);
      return DefaultEmbedderFactory.of(
          f -> f.provider(BEDROCK, provider).embedding(BEDROCK, EmbeddingOptions.of(model)));
    }

    @Test
    void a_provider_s_property_is_sent_in_the_titan_body() {
      Scripted client = new Scripted(body -> "{\"embedding\":[1,0]}");
      factory(client, Map.of("bedrock.embeddingTypes", "[\"float\"]"), "amazon.titan-embed-text-v2:0")
          .create(c -> {})
          .embedDocument("x");

      assertThat(client.sent.getFirst().path("embeddingTypes").get(0).asString())
          .isEqualTo("float");
    }

    @Test
    void a_provider_s_property_is_sent_in_the_cohere_body() {
      Scripted client = new Scripted(body -> "{\"embeddings\":[[1,0]]}");
      factory(client, Map.of("bedrock.embedding_types", "[\"float\"]"), "cohere.embed-english-v3")
          .create(c -> {})
          .embedDocument("x");

      assertThat(client.sent.getFirst().path("embedding_types").get(0).asString())
          .isEqualTo("float");
    }

    @Test
    void an_embedder_s_property_overrides_the_provider_s_by_name() {
      Scripted client = new Scripted(body -> "{\"embedding\":[1,0]}");
      factory(client, Map.of("bedrock.extra", "provider"), "amazon.titan-embed-text-v2:0")
          .create(c -> c.property("bedrock.extra", "store"))
          .embedDocument("x");

      assertThat(client.sent.getFirst().path("extra").asString()).isEqualTo("store");
    }

    /** Review Focus 1. */
    @Test
    void a_property_under_a_field_the_adapter_writes_is_refused_when_the_embedder_is_made() {
      Scripted client = new Scripted(body -> "{}");
      DefaultEmbedderFactory factory = factory(client, Map.of(), "cohere.embed-english-v3");
      Customizer<EmbedderConfig> truncate = c -> c.property("bedrock.truncate", "NONE");
      Customizer<EmbedderConfig> underTexts = c -> c.property("bedrock.texts.first", "x");

      assertThatThrownBy(() -> factory.create(truncate))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'bedrock.truncate'");
      assertThatThrownBy(() -> factory.create(underTexts))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'bedrock.texts.first'");
      assertThat(client.sent).isEmpty();
    }

    @Test
    void a_clash_on_the_provider_is_refused_when_it_is_built() {
      Customizer<BedrockEmbedderConfig> inputType =
          c ->
              c.region(Region.US_EAST_1)
                  .credentialsProvider(CREDENTIALS)
                  .property("bedrock.input_type", "search_query");

      assertThatThrownBy(() -> BedrockEmbeddingProvider.of(inputType))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'bedrock.input_type'")
          .hasMessageContaining("BedrockEmbedderConfig.cohereInputType");
    }

    /** Review Focus 5. */
    @Test
    void another_adapter_s_property_is_not_sent() {
      Scripted client = new Scripted(body -> "{\"embedding\":[1,0]}");
      factory(client, Map.of(), "amazon.titan-embed-text-v2:0")
          .create(c -> c.property("voyage.truncation", "false"))
          .embedDocument("x");

      assertThat(client.sent.getFirst().size()).isEqualTo(1);
      assertThat(client.sent.getFirst().has("inputText")).isTrue();
    }
  }
```

(A `static` field in a non-static `@Nested` inner class is legal since Java 16.)

- [ ] **Step 4: Write the failing Voyage tests**

`VoyageEmbedderTest.java` -- add imports `org.jwcarman.nessy.api.embedding.EmbedderConfig` (`Customizer` is imported already), then:

```java
  @Nested
  class ItsVendorProperties {

    private DefaultEmbedderFactory factory(VoyageEmbeddingProvider connection) {
      return DefaultEmbedderFactory.of(
          f ->
              f.provider(VOYAGE, connection)
                  .embedding(VOYAGE, EmbeddingOptions.of(VoyageEmbedderConfig.DEFAULT_MODEL)));
    }

    @Test
    void a_provider_s_property_is_sent_in_the_body() {
      answer = body -> reply(new float[] {1, 0});
      try (VoyageEmbeddingProvider connection =
          provider(c -> c.property("voyage.truncation", "false"))) {
        factory(connection).create(c -> {}).embedDocument("x");
      }

      assertThat(received.getFirst().path("truncation").isBoolean()).isTrue();
      assertThat(received.getFirst().path("truncation").asBoolean()).isFalse();
    }

    @Test
    void an_embedder_s_property_overrides_the_provider_s_by_name() {
      answer = body -> reply(new float[] {1, 0});
      try (VoyageEmbeddingProvider connection =
          provider(c -> c.property("voyage.extra", "provider"))) {
        factory(connection).create(c -> c.property("voyage.extra", "store")).embedDocument("x");
      }

      assertThat(received.getFirst().path("extra").asString()).isEqualTo("store");
    }

    @Test
    void a_dotted_name_nests_and_a_json_literal_keeps_its_type() {
      answer = body -> reply(new float[] {1, 0});
      try (VoyageEmbeddingProvider connection = provider(c -> {})) {
        factory(connection).create(c -> c.property("voyage.extra.depth", "3")).embedDocument("x");
      }

      assertThat(received.getFirst().path("extra").path("depth").isInt()).isTrue();
      assertThat(received.getFirst().path("extra").path("depth").asInt()).isEqualTo(3);
    }

    /** Review Focus 1. */
    @Test
    void a_property_under_a_field_the_adapter_writes_is_refused_when_the_embedder_is_made() {
      try (VoyageEmbeddingProvider connection = provider(c -> {})) {
        DefaultEmbedderFactory factory = factory(connection);
        Customizer<EmbedderConfig> underModel = c -> c.property("voyage.model.alias", "x");
        Customizer<EmbedderConfig> inputType = c -> c.property("voyage.input_type", "query");

        assertThatThrownBy(() -> factory.create(underModel))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'voyage.model.alias'")
            .hasMessageContaining("EmbedderConfig.model");
        assertThatThrownBy(() -> factory.create(inputType))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'voyage.input_type'");
      }
      assertThat(received).isEmpty();
    }

    @Test
    void a_clash_on_the_provider_is_refused_when_it_is_built() {
      Customizer<VoyageEmbedderConfig> width =
          c -> c.apiKey("k").property("voyage.output_dimension", "256");

      assertThatThrownBy(() -> VoyageEmbeddingProvider.of(width))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'voyage.output_dimension'")
          .hasMessageContaining("EmbedderConfig.dimension");
    }

    /** Review Focus 5. */
    @Test
    void another_adapter_s_property_is_not_sent() {
      answer = body -> reply(new float[] {1, 0});
      try (VoyageEmbeddingProvider connection = provider(c -> {})) {
        factory(connection).create(c -> c.property("openai.user", "x")).embedDocument("x");
      }

      assertThat(received.getFirst().has("user")).isFalse();
    }
  }
```

- [ ] **Step 5: Run them to see them fail**

Run: `./mvnw -q -pl :nessy-embedding-openai,:nessy-embedding-gemini,:nessy-embedding-bedrock,:nessy-embedding-voyage -am test -Dtest='OpenAiEmbeddingProviderTest,GeminiEmbedderTest,BedrockEmbedderTest,VoyageEmbedderTest' -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: compilation failure (`no suitable constructor found for GeminiEmbeddingProvider(...,Map)` and `BedrockEmbeddingProvider(...,Map)`); `exit=1`.

- [ ] **Step 6: The four readers**

`OpenAiEmbeddingProperties.java` (license header, then):

```java
package org.jwcarman.nessy.embedding.openai;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jwcarman.nessy.vendor.VendorProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code openai.} vendor properties of an embedding request (named-embedders spec §7c). No name
 * is read into a typed field: every one passes through into the request body, nested by its dots,
 * its value read as a JSON literal. A name that would overwrite a field this adapter writes -- or
 * reach under one -- is refused, naming what decides that field. Another adapter's prefix is
 * ignored: a store's properties may carry settings for several vendors.
 */
final class OpenAiEmbeddingProperties {

  static final String PREFIX = "openai.";

  /** Reads the values as JSON literals; nothing an application's own mapper adds matters here. */
  static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** The fields this adapter writes, and what decides each. */
  private static final Map<String, String> CLASHES =
      Map.of(
          "model", "EmbedderConfig.model",
          "input", "the texts the store embeds",
          "dimensions", "EmbedderConfig.dimension");

  private OpenAiEmbeddingProperties() {}

  /**
   * The provider's entries overlaid by the embedder's, name by name, read under {@value #PREFIX}.
   *
   * @return the pass-through, as the request body's extra fields
   * @throws IllegalArgumentException naming the property: a clash, a name with no prefix, or a
   *     malformed path
   */
  static Map<String, Object> read(
      Map<String, String> provider, Map<String, String> embedder, JsonMapper mapper) {
    Map<String, String> own =
        VendorProperties.under(VendorProperties.merge(provider, embedder), PREFIX);
    VendorProperties.refuseClashes(PREFIX, own, clashesAmong(own));
    return VendorProperties.nest(PREFIX, own, mapper);
  }

  /** A field the adapter writes, or any name beneath one: {@code input.x} would replace it. */
  private static Map<String, String> clashesAmong(Map<String, String> own) {
    Map<String, String> clashes = new LinkedHashMap<>();
    for (String name : own.keySet()) {
      int dot = name.indexOf('.');
      String decider = CLASHES.get(dot < 0 ? name : name.substring(0, dot));
      if (decider != null) {
        clashes.put(name, decider);
      }
    }
    return clashes;
  }
}
```

`GeminiEmbeddingProperties.java` (license header, then):

```java
package org.jwcarman.nessy.embedding.gemini;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jwcarman.nessy.vendor.VendorProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code gemini.} vendor properties of an embedding request (named-embedders spec §7c), sent
 * through the request's {@code extraBody}. No name is read into a typed field: every one passes
 * through, nested by its dots, its value read as a JSON literal. A name that would overwrite a
 * field this adapter writes -- or reach under one -- is refused, naming what decides that field.
 * Another adapter's prefix is ignored.
 */
final class GeminiEmbeddingProperties {

  static final String PREFIX = "gemini.";

  /** Reads the values as JSON literals; nothing an application's own mapper adds matters here. */
  static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** The fields this adapter writes, and what decides each. */
  private static final Map<String, String> CLASHES =
      Map.of(
          "model", "EmbedderConfig.model",
          "contents", "the texts the store embeds",
          "outputDimensionality", "EmbedderConfig.dimension",
          "taskType", "GeminiEmbedderConfig.taskType, or the document or query role");

  private GeminiEmbeddingProperties() {}

  /**
   * The provider's entries overlaid by the embedder's, name by name, read under {@value #PREFIX}.
   *
   * @return the pass-through, as the request's extra body
   * @throws IllegalArgumentException naming the property: a clash, a name with no prefix, or a
   *     malformed path
   */
  static Map<String, Object> read(
      Map<String, String> provider, Map<String, String> embedder, JsonMapper mapper) {
    Map<String, String> own =
        VendorProperties.under(VendorProperties.merge(provider, embedder), PREFIX);
    VendorProperties.refuseClashes(PREFIX, own, clashesAmong(own));
    return VendorProperties.nest(PREFIX, own, mapper);
  }

  /** A field the adapter writes, or any name beneath one. */
  private static Map<String, String> clashesAmong(Map<String, String> own) {
    Map<String, String> clashes = new LinkedHashMap<>();
    for (String name : own.keySet()) {
      int dot = name.indexOf('.');
      String decider = CLASHES.get(dot < 0 ? name : name.substring(0, dot));
      if (decider != null) {
        clashes.put(name, decider);
      }
    }
    return clashes;
  }
}
```

`BedrockEmbeddingProperties.java` (license header, then):

```java
package org.jwcarman.nessy.embedding.bedrock;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jwcarman.nessy.vendor.VendorProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code bedrock.} vendor properties of an embedding request (named-embedders spec §7c), set on
 * the model's own JSON body -- Titan's or Cohere's. No name is read into a typed field: every one
 * passes through, nested by its dots, its value read as a JSON literal. A name that would overwrite
 * a field this adapter writes -- or reach under one -- is refused, naming what decides that field.
 * Another adapter's prefix is ignored.
 */
final class BedrockEmbeddingProperties {

  static final String PREFIX = "bedrock.";

  private static final String TEXTS = "the texts the store embeds";

  /** The fields this adapter writes in either family's body, and what decides each. */
  private static final Map<String, String> CLASHES =
      Map.of(
          "inputText", TEXTS,
          "texts", TEXTS,
          "dimensions", "EmbedderConfig.dimension",
          "normalize", "the adapter, which normalises a vector it asked a width of",
          "input_type", "BedrockEmbedderConfig.cohereInputType",
          "truncate", "the adapter, which truncates at END");

  private BedrockEmbeddingProperties() {}

  /**
   * The provider's entries overlaid by the embedder's, name by name, read under {@value #PREFIX}.
   *
   * @return the pass-through, as fields of the request body
   * @throws IllegalArgumentException naming the property: a clash, a name with no prefix, or a
   *     malformed path
   */
  static Map<String, Object> read(
      Map<String, String> provider, Map<String, String> embedder, JsonMapper mapper) {
    Map<String, String> own =
        VendorProperties.under(VendorProperties.merge(provider, embedder), PREFIX);
    VendorProperties.refuseClashes(PREFIX, own, clashesAmong(own));
    return VendorProperties.nest(PREFIX, own, mapper);
  }

  /** A field the adapter writes, or any name beneath one. */
  private static Map<String, String> clashesAmong(Map<String, String> own) {
    Map<String, String> clashes = new LinkedHashMap<>();
    for (String name : own.keySet()) {
      int dot = name.indexOf('.');
      String decider = CLASHES.get(dot < 0 ? name : name.substring(0, dot));
      if (decider != null) {
        clashes.put(name, decider);
      }
    }
    return clashes;
  }
}
```

`VoyageEmbeddingProperties.java` (license header, then):

```java
package org.jwcarman.nessy.embedding.voyage;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jwcarman.nessy.vendor.VendorProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code voyage.} vendor properties of an embedding request (named-embedders spec §7c). No name
 * is read into a typed field: every one passes through into the request body, nested by its dots,
 * its value read as a JSON literal -- {@code voyage.truncation=false} is the boolean. A name that
 * would overwrite a field this adapter writes -- or reach under one -- is refused, naming what
 * decides that field. Another adapter's prefix is ignored.
 */
final class VoyageEmbeddingProperties {

  static final String PREFIX = "voyage.";

  /** The fields this adapter writes, and what decides each. */
  private static final Map<String, String> CLASHES =
      Map.of(
          "model", "EmbedderConfig.model",
          "input", "the texts the store embeds",
          "output_dimension", "EmbedderConfig.dimension",
          "input_type", "VoyageEmbedderConfig.inputType, or the document or query role");

  private VoyageEmbeddingProperties() {}

  /**
   * The provider's entries overlaid by the embedder's, name by name, read under {@value #PREFIX}.
   *
   * @return the pass-through, as fields of the request body
   * @throws IllegalArgumentException naming the property: a clash, a name with no prefix, or a
   *     malformed path
   */
  static Map<String, Object> read(
      Map<String, String> provider, Map<String, String> embedder, JsonMapper mapper) {
    Map<String, String> own =
        VendorProperties.under(VendorProperties.merge(provider, embedder), PREFIX);
    VendorProperties.refuseClashes(PREFIX, own, clashesAmong(own));
    return VendorProperties.nest(PREFIX, own, mapper);
  }

  /** A field the adapter writes, or any name beneath one. */
  private static Map<String, String> clashesAmong(Map<String, String> own) {
    Map<String, String> clashes = new LinkedHashMap<>();
    for (String name : own.keySet()) {
      int dot = name.indexOf('.');
      String decider = CLASHES.get(dot < 0 ? name : name.substring(0, dot));
      if (decider != null) {
        clashes.put(name, decider);
      }
    }
    return clashes;
  }
}
```

- [ ] **Step 7: The configs hand their maps over, checked**

In each of the four configs (VP Task 8 gave each a private `properties` field, `property`/`properties` setters and a private `requireOwnProperties()`):

- add the imports `java.util.Collections` and `java.util.LinkedHashMap` if absent;
- change the `property(String, String)` javadoc's second sentence from "Carried and not yet read: the embedding adapters read their properties from the named-embedders item on (spec §9f)." to "Sent with every request this provider makes, overlaid name by name by an embedder's own; a name at or under a field the adapter writes fails at build.";
- add, after `requireOwnProperties()`:

```java
  /** The provider's own properties, in the order given. */
  Map<String, String> properties() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(properties));
  }
```

- and make `build()` check the map before any client is built, then hand it over:

`OpenAiEmbedderConfig.build()` -- directly after `requireOwnProperties();`:

```java
    OpenAiEmbeddingProperties.read(properties, Map.of(), OpenAiEmbeddingProperties.MAPPER);
```

and the three constructions become `new OpenAiEmbeddingProvider(client, false, vendor, properties())`, `new OpenAiEmbeddingProvider(buildFromEnv(), true, vendor, properties())`, `new OpenAiEmbeddingProvider(builder.build(), true, vendor, properties())`.

`GeminiEmbedderConfig.build()`:

```java
  GeminiEmbeddingProvider build() {
    requireOwnProperties();
    GeminiEmbeddingProperties.read(properties, Map.of(), GeminiEmbeddingProperties.MAPPER);
    return new GeminiEmbeddingProvider(resolveClient(), taskType, properties());
  }
```

`BedrockEmbedderConfig.build()`:

```java
  BedrockEmbeddingProvider build() {
    requireOwnProperties();
    BedrockEmbeddingProperties.read(properties, Map.of(), mapper);
    return new BedrockEmbeddingProvider(resolveClient(), cohereInputType, mapper, properties());
  }
```

`VoyageEmbedderConfig.build()` -- directly after `requireOwnProperties();`:

```java
    VoyageEmbeddingProperties.read(properties, Map.of(), mapper);
```

(the provider reads the map from the config itself, below).

- [ ] **Step 8: The providers read them**

`OpenAiEmbeddingProvider.java` (imports `com.openai.core.JsonValue`, `java.util.Collections`, `java.util.LinkedHashMap`, `java.util.Map`): the constructor becomes

```java
  /** The provider's own {@code openai.} properties, checked at build; an embedder's overlay them. */
  private final Map<String, String> properties;

  OpenAiEmbeddingProvider(
      OpenAIClient client, boolean ownsClient, String vendor, Map<String, String> properties) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.ownsClient = ownsClient;
    this.vendor = Objects.requireNonNull(vendor, "vendor must not be null");
    this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
  }

  /** Reads the merged properties exactly as a request would, so a mistake fails at create. */
  @Override
  public void validate(EmbeddingOptions options) {
    OpenAiEmbeddingProperties.read(
        properties, options.properties(), OpenAiEmbeddingProperties.MAPPER);
  }
```

and in `embedDocuments`, directly after `options.dimension().ifPresent(params::dimensions);`:

```java
    OpenAiEmbeddingProperties.read(
            properties, options.properties(), OpenAiEmbeddingProperties.MAPPER)
        .forEach((name, value) -> params.putAdditionalBodyProperty(name, JsonValue.from(value)));
```

`GeminiEmbeddingProvider.java` (imports `com.google.genai.types.HttpOptions`, `java.util.Collections`, `java.util.LinkedHashMap`, `java.util.Map`): the constructor becomes the pair

```java
  /** The provider's own {@code gemini.} properties, checked at build; an embedder's overlay them. */
  private final Map<String, String> properties;

  GeminiEmbeddingProvider(GeminiEmbeddingClient client, String taskType) {
    this(client, taskType, Map.of());
  }

  GeminiEmbeddingProvider(
      GeminiEmbeddingClient client, String taskType, Map<String, String> properties) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.taskType = taskType;
    this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
  }

  /** Reads the merged properties exactly as a request would, so a mistake fails at create. */
  @Override
  public void validate(EmbeddingOptions options) {
    GeminiEmbeddingProperties.read(
        properties, options.properties(), GeminiEmbeddingProperties.MAPPER);
  }
```

and in `embed`, directly after `config.taskType(role);`:

```java
    Map<String, Object> passThrough =
        GeminiEmbeddingProperties.read(
            properties, options.properties(), GeminiEmbeddingProperties.MAPPER);
    if (!passThrough.isEmpty()) {
      // Per request; the SDK overlays these options on the client's own, so the base URL and key
      // the client was built with stay.
      config.httpOptions(HttpOptions.builder().extraBody(passThrough).build());
    }
```

`BedrockEmbeddingProvider.java` (imports `java.util.Collections`, `java.util.LinkedHashMap`, `java.util.Map`): the constructor becomes the pair

```java
  /** The provider's own {@code bedrock.} properties, checked at build; an embedder's overlay them. */
  private final Map<String, String> properties;

  BedrockEmbeddingProvider(
      BedrockEmbeddingClient client, String cohereInputType, JsonMapper mapper) {
    this(client, cohereInputType, mapper, Map.of());
  }

  BedrockEmbeddingProvider(
      BedrockEmbeddingClient client,
      String cohereInputType,
      JsonMapper mapper,
      Map<String, String> properties) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.cohereInputType = Objects.requireNonNull(cohereInputType, "inputType must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
  }

  /**
   * Refuses, before anything reaches the wire, a model of neither family this adapter speaks and
   * any property it cannot honour.
   */
  @Override
  public void validate(EmbeddingOptions options) {
    Family.of(options.modelName());
    BedrockEmbeddingProperties.read(properties, options.properties(), mapper);
  }
```

in `titan`, directly after the `options.dimension().ifPresent(...)` line, and in `cohere`, directly after `body.put("input_type", cohereInputType).put("truncate", "END");`, add `passThrough(body, options);`, and add:

```java
  /** The vendor properties, set on the model's own body. */
  private void passThrough(ObjectNode body, EmbeddingOptions options) {
    BedrockEmbeddingProperties.read(properties, options.properties(), mapper)
        .forEach(
            (name, value) -> {
              if (value == null) {
                body.putNull(name);
              } else {
                body.set(name, mapper.valueToTree(value));
              }
            });
  }
```

`VoyageEmbeddingProvider.java` (import `java.util.Map`): a field `private final Map<String, String> properties;` set in the constructor from `config.properties()` (beside `this.mapper = ...`), a `validate` override

```java
  /** Reads the merged properties exactly as a request would, so a mistake fails at create. */
  @Override
  public void validate(EmbeddingOptions options) {
    VoyageEmbeddingProperties.read(properties, options.properties(), mapper);
  }
```

and in `post`, directly after `body.put("input_type", role);`:

```java
    VoyageEmbeddingProperties.read(properties, options.properties(), mapper)
        .forEach(
            (name, value) -> {
              if (value == null) {
                body.putNull(name);
              } else {
                body.set(name, mapper.valueToTree(value));
              }
            });
```

- [ ] **Step 9: Run them to see them pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-embedding-openai,:nessy-embedding-gemini,:nessy-embedding-bedrock,:nessy-embedding-voyage,:nessy-spring-boot-autoconfigure -am test; echo exit=$?`
Expected: `exit=0`. (VP Task 8's `XEmbedderConfigTest` files must still pass: their own-prefix properties -- `openai.user`, `gemini.labels.team`, `bedrock.truncate`, `voyage.truncation` -- are carried to build. **`bedrock.truncate` now clashes**: in `BedrockEmbedderConfigTest`, change the own-prefix property used by `a_property_under_its_own_prefix_builds` and `a_blank_value_is_refused_naming_the_property` from `bedrock.truncate` / `END` to `bedrock.embeddingTypes` / `["float"]`, and say so in the report.)

- [ ] **Step 10: Full gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?`
Expected: `exit=0`.

```bash
cd /Users/jcarman/IdeaProjects/nessy-embedders
git add nessy-embedding
git commit -m "feat: the embedding adapters read their vendor properties

Each adapter reads the provider's own map overlaid by the embedder's under
its prefix and passes every name through into the request body (OpenAI's
additional body, Gemini's extraBody, Bedrock's and Voyage's JSON). A name at
or under a field the adapter writes is refused at build and at create.
Bedrock's model-family check moves into validate, so it fails where the
store is built.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 4: Boot, the pure half -- the wire, the catalogue, the settings, and the builders

Spec §4c, §6a-§6e, §7a, §11a's unit tests; Plan rulings 6-8. Everything here is package-private in `org.jwcarman.nessy.spring.boot.embedding`, needs no Spring context, and sits beside the three old auto-configurations until Task 5 wires it in and deletes them. Every code block is complete: this is transcription. Suggested implementer: **Haiku** (transcription). Review: Sonnet.

**Files** (all under `nessy-spring-boot/autoconfigure/src/`):
- Create: `main/java/org/jwcarman/nessy/spring/boot/embedding/EmbeddingWire.java`
- Create: `main/java/org/jwcarman/nessy/spring/boot/embedding/EmbedderPreset.java`
- Create: `main/java/org/jwcarman/nessy/spring/boot/embedding/EmbedderSettings.java`
- Create: `main/java/org/jwcarman/nessy/spring/boot/embedding/ResolvedEmbedder.java`
- Create: `main/java/org/jwcarman/nessy/spring/boot/embedding/ResolvedEmbedders.java`
- Create: `main/java/org/jwcarman/nessy/spring/boot/embedding/EmbedderCatalogue.java`
- Create: `main/java/org/jwcarman/nessy/spring/boot/embedding/WireEmbedders.java`
- Test (create): `test/java/org/jwcarman/nessy/spring/boot/embedding/EmbedderCatalogueTest.java`, `EmbedderSettingsTest.java`, `ResolvedEmbedderTest.java`, `WireEmbeddersTest.java`

**Interfaces:**
- Consumes (Tasks 2-3): `OpenAiEmbeddingProvider.of(Customizer<OpenAiEmbedderConfig>)` with `apiKey`, `baseUrl`, `vendor`, `properties(Map)`; `GeminiEmbeddingProvider.of(...)` with `apiKey`, `baseUrl`, `properties(Map)`; `VoyageEmbeddingProvider.of(...)` with `apiKey`, `baseUrl`, `properties(Map)`, `mapper(JsonMapper)`; each config's `build()` refusing a clash in its own map.
- Produces (package-private, Task 5 relies on these exact names):
  - `enum EmbeddingWire { OPENAI, GEMINI, VOYAGE }` with `String defaultVendor()` and `String propertyValue()`
  - `record EmbedderPreset(String id, EmbeddingWire wire, @Nullable String baseUrl, String vendor, List<String> keyProperties, @Nullable String keylessApiKey, Map<String, String> defaultProperties)` with `static final List<EmbedderPreset> CATALOGUE` and `boolean keyless()`
  - `record EmbedderSettings(@Nullable EmbeddingWire wire, @Nullable String baseUrl, @Nullable String apiKey, @Nullable Boolean enabled, @Nullable String vendor, @Nullable Map<String, String> properties)`
  - `record ResolvedEmbedder(String id, EmbeddingWire wire, @Nullable String baseUrl, String vendor, @Nullable String apiKey, Map<String, String> properties)` with `String beanName()` = `id + "Embeddings"`
  - `record ResolvedEmbedders(List<ResolvedEmbedder> embedders)`
  - `static List<ResolvedEmbedder> EmbedderCatalogue.resolve(Map<String, EmbedderSettings> settings, Function<String, @Nullable String> property)`
  - `static boolean WireEmbedders.isPresent(EmbeddingWire, ClassLoader)`, `static String WireEmbedders.artifactId(EmbeddingWire)`, `static Optional<EmbeddingProvider> WireEmbedders.build(ResolvedEmbedder, @Nullable JsonMapper, ClassLoader)`

- [ ] **Step 1: Write the failing tests**

`EmbedderSettingsTest.java` (license header, then):

```java
package org.jwcarman.nessy.spring.boot.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code toString()} is what a log line or a failed assertion prints -- the key and property values
 * must not be in it.
 */
class EmbedderSettingsTest {

  @Test
  void tostring_does_not_print_the_key() {
    EmbedderSettings settings =
        new EmbedderSettings(
            EmbeddingWire.OPENAI, "https://g/v1", "sk-super-secret", null, null, null);

    assertThat(settings.toString()).doesNotContain("sk-super-secret").contains("apiKey=***");
  }

  @Test
  void tostring_prints_property_names_never_values() {
    EmbedderSettings settings =
        new EmbedderSettings(null, null, null, null, null, Map.of("voyage.truncation", "false"));

    assertThat(settings.toString()).contains("voyage.truncation").doesNotContain("false");
  }
}
```

`ResolvedEmbedderTest.java` (license header, then):

```java
package org.jwcarman.nessy.spring.boot.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ResolvedEmbedderTest {

  @Test
  void tostring_redacts_the_key_and_prints_property_names_never_values() {
    ResolvedEmbedder resolved =
        new ResolvedEmbedder(
            "voyage",
            EmbeddingWire.VOYAGE,
            "https://api.voyageai.com/v1",
            "voyage",
            "pa-super-secret",
            Map.of("voyage.truncation", "false"));

    assertThat(resolved.toString())
        .contains("apiKey=***")
        .contains("voyage.truncation")
        .doesNotContain("pa-super-secret")
        .doesNotContain("false");
  }

  /**
   * The registry id is the id; the bean carries a suffix, because one Spring bean namespace also
   * holds the inference provider lit by the same key (spec §6f).
   */
  @Test
  void the_bean_is_named_for_the_id_with_the_embeddings_suffix() {
    ResolvedEmbedder resolved =
        new ResolvedEmbedder("openai", EmbeddingWire.OPENAI, null, "openai", "k", Map.of());

    assertThat(resolved.beanName()).isEqualTo("openaiEmbeddings");
  }
}
```

`EmbedderCatalogueTest.java` (license header, then):

```java
package org.jwcarman.nessy.spring.boot.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a set of {@code nessy.embedders.<id>} settings, plus the vendor environment properties that
 * light a hosted preset, resolve to -- pure, with no Spring context.
 */
@DisplayName("The embedder catalogue's resolution")
class EmbedderCatalogueTest {

  private static final String VOYAGE_URL = "https://api.voyageai.com/v1";

  private static EmbedderSettings settings(
      EmbeddingWire wire, String baseUrl, String apiKey, Boolean enabled, String vendor) {
    return new EmbedderSettings(wire, baseUrl, apiKey, enabled, vendor, null);
  }

  @Test
  void the_catalogue_is_the_three_measured_rows_and_none_is_keyless() {
    assertThat(EmbedderPreset.CATALOGUE)
        .extracting(EmbedderPreset::id)
        .containsExactly("openai", "gemini", "voyage");
    assertThat(EmbedderPreset.CATALOGUE).isNotEmpty().noneMatch(EmbedderPreset::keyless);
  }

  @Test
  void the_wire_values_are_the_vendors_names() {
    assertThat(EmbeddingWire.values())
        .extracting(EmbeddingWire::propertyValue)
        .containsExactly("openai", "gemini", "voyage");
    assertThat(EmbeddingWire.values())
        .extracting(EmbeddingWire::defaultVendor)
        .containsExactly("openai", "gcp.gemini", "voyage");
  }

  @Test
  void nothing_set_lights_nothing() {
    assertThat(EmbedderCatalogue.resolve(Map.of(), key -> null)).isEmpty();
  }

  @Test
  void an_openai_key_lights_openai() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(Map.of(), Map.of("openai.api-key", "k")::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedEmbedder("openai", EmbeddingWire.OPENAI, null, "openai", "k", Map.of()));
  }

  @Test
  void a_voyage_key_lights_voyage_at_its_own_url() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(Map.of(), Map.of("voyage.api-key", "k")::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedEmbedder(
                "voyage", EmbeddingWire.VOYAGE, VOYAGE_URL, "voyage", "k", Map.of()));
  }

  @Test
  void either_gemini_key_lights_one_gemini_and_the_first_spelling_wins() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(
            Map.of(), Map.of("gemini.api-key", "g", "google.api-key", "o")::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedEmbedder(
                "gemini", EmbeddingWire.GEMINI, null, "gcp.gemini", "g", Map.of()));
  }

  @Test
  void every_key_lights_its_own_preset_and_none_is_chosen() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(
            Map.of(),
            Map.of("openai.api-key", "a", "gemini.api-key", "b", "voyage.api-key", "c")::get);

    assertThat(resolved)
        .extracting(ResolvedEmbedder::id)
        .containsExactly("openai", "gemini", "voyage");
  }

  @Test
  void a_blank_key_does_not_light_a_preset() {
    assertThat(EmbedderCatalogue.resolve(Map.of(), Map.of("voyage.api-key", " ")::get)).isEmpty();
  }

  @Test
  void a_prefixed_key_lights_the_preset() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(
            Map.of("voyage", settings(null, null, "k", null, null)), key -> null);

    assertThat(resolved)
        .containsExactly(
            new ResolvedEmbedder(
                "voyage", EmbeddingWire.VOYAGE, VOYAGE_URL, "voyage", "k", Map.of()));
  }

  @Test
  void the_openai_base_url_property_overrides_the_openai_preset_and_no_other() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(
            Map.of(),
            Map.of(
                    "openai.api-key", "k",
                    "openai.base-url", "http://localhost:1234/v1",
                    "voyage.api-key", "v")
                ::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedEmbedder(
                "openai",
                EmbeddingWire.OPENAI,
                "http://localhost:1234/v1",
                "openai",
                "k",
                Map.of()),
            new ResolvedEmbedder(
                "voyage", EmbeddingWire.VOYAGE, VOYAGE_URL, "voyage", "v", Map.of()));
  }

  @Test
  void a_setting_overrides_a_preset_field() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(
            Map.of("voyage", settings(null, "https://proxy/v1", null, null, null)),
            Map.of("voyage.api-key", "k")::get);

    assertThat(resolved)
        .extracting(ResolvedEmbedder::baseUrl)
        .containsExactly("https://proxy/v1");
  }

  @Test
  void a_hosted_preset_with_its_key_set_and_enabled_false_is_absent() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(
            Map.of("gemini", settings(null, null, null, false, null)),
            Map.of("gemini.api-key", "k")::get);

    assertThat(resolved).isEmpty();
  }

  @Test
  void a_custom_embedder_with_a_wire_a_url_and_a_key_is_resolved_with_the_wire_s_vendor() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(
            Map.of("mine", settings(EmbeddingWire.OPENAI, "https://g/v1", "k", null, null)),
            key -> null);

    assertThat(resolved)
        .containsExactly(
            new ResolvedEmbedder(
                "mine", EmbeddingWire.OPENAI, "https://g/v1", "openai", "k", Map.of()));
  }

  @Test
  void a_custom_embedder_keeps_the_vendor_it_names() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(
            Map.of(
                "local",
                settings(
                    EmbeddingWire.OPENAI, "http://localhost:1234/v1", "lm-studio", null, "lmstudio")),
            key -> null);

    assertThat(resolved).extracting(ResolvedEmbedder::vendor).containsExactly("lmstudio");
  }

  @Test
  void a_custom_embedder_without_a_wire_fails_naming_it() {
    Map<String, EmbedderSettings> mine =
        Map.of("mine", settings(null, "https://g/v1", "k", null, null));

    assertThatThrownBy(() -> EmbedderCatalogue.resolve(mine, key -> null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("nessy.embedders.mine.wire is required: mine is not a preset");
  }

  @Test
  void a_custom_embedder_without_a_url_fails_naming_it() {
    Map<String, EmbedderSettings> mine =
        Map.of("mine", settings(EmbeddingWire.OPENAI, " ", "k", null, null));

    assertThatThrownBy(() -> EmbedderCatalogue.resolve(mine, key -> null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("nessy.embedders.mine.base-url is required: mine is not a preset");
  }

  /** Plan ruling 7: every embedding wire needs a key. */
  @Test
  void a_custom_embedder_without_a_key_fails_naming_it() {
    Map<String, EmbedderSettings> mine =
        Map.of("mine", settings(EmbeddingWire.VOYAGE, "https://g/v1", null, null, null));

    assertThatThrownBy(() -> EmbedderCatalogue.resolve(mine, key -> null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("nessy.embedders.mine.api-key is required: the voyage wire needs a key");
  }

  /** §6c made checkable: lmstudio is not a preset, so it is a custom embedder with no wire. */
  @Test
  void an_id_that_is_not_a_preset_turned_on_alone_fails_naming_the_wire() {
    Map<String, EmbedderSettings> lmstudio =
        Map.of("lmstudio", settings(null, null, null, true, null));

    assertThatThrownBy(() -> EmbedderCatalogue.resolve(lmstudio, key -> null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("nessy.embedders.lmstudio.wire is required: lmstudio is not a preset");
  }

  @Test
  void a_custom_entry_with_enabled_false_is_absent() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(
            Map.of("mine", settings(EmbeddingWire.OPENAI, "https://g/v1", "k", false, null)),
            key -> null);

    assertThat(resolved).isEmpty();
  }

  @Test
  void a_preset_carries_the_properties_its_settings_give() {
    EmbedderSettings voyage =
        new EmbedderSettings(null, null, null, null, null, Map.of("voyage.truncation", "false"));

    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(Map.of("voyage", voyage), Map.of("voyage.api-key", "k")::get);

    assertThat(resolved)
        .singleElement()
        .extracting(ResolvedEmbedder::properties)
        .isEqualTo(Map.of("voyage.truncation", "false"));
  }

  @Test
  void a_custom_embedder_carries_its_own_properties() {
    EmbedderSettings mine =
        new EmbedderSettings(
            EmbeddingWire.OPENAI, "https://g/v1", "k", null, null, Map.of("openai.user", "t"));

    List<ResolvedEmbedder> resolved = EmbedderCatalogue.resolve(Map.of("mine", mine), key -> null);

    assertThat(resolved)
        .singleElement()
        .extracting(ResolvedEmbedder::properties)
        .isEqualTo(Map.of("openai.user", "t"));
  }
}
```

`WireEmbeddersTest.java` (license header, then):

```java
package org.jwcarman.nessy.spring.boot.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.embedding.EmbeddingProvider;
import org.jwcarman.nessy.embedding.gemini.GeminiEmbeddingProvider;
import org.jwcarman.nessy.embedding.openai.OpenAiEmbeddingProvider;
import org.jwcarman.nessy.embedding.voyage.VoyageEmbeddingProvider;
import org.springframework.boot.test.context.FilteredClassLoader;
import tools.jackson.databind.json.JsonMapper;

/** Which adapter each embedding wire builds, and that what was resolved reaches it. */
class WireEmbeddersTest {

  private static final ClassLoader LOADER = WireEmbeddersTest.class.getClassLoader();

  @Test
  void the_openai_wire_builds_the_openai_adapter_reporting_the_resolved_vendor() {
    ResolvedEmbedder resolved =
        new ResolvedEmbedder(
            "local",
            EmbeddingWire.OPENAI,
            "http://localhost:1234/v1",
            "lmstudio",
            "lm-studio",
            Map.of());

    Optional<EmbeddingProvider> built = WireEmbedders.build(resolved, null, LOADER);

    assertThat(built)
        .get()
        .isInstanceOfSatisfying(
            OpenAiEmbeddingProvider.class,
            provider -> {
              assertThat(provider.vendor()).isEqualTo("lmstudio");
              provider.close();
            });
  }

  @Test
  void the_gemini_wire_builds_the_gemini_adapter() {
    ResolvedEmbedder resolved =
        new ResolvedEmbedder("gemini", EmbeddingWire.GEMINI, null, "gcp.gemini", "k", Map.of());

    Optional<EmbeddingProvider> built = WireEmbedders.build(resolved, null, LOADER);

    assertThat(built)
        .get()
        .isInstanceOfSatisfying(GeminiEmbeddingProvider.class, GeminiEmbeddingProvider::close);
  }

  @Test
  void the_voyage_wire_builds_the_voyage_adapter() {
    ResolvedEmbedder resolved =
        new ResolvedEmbedder(
            "voyage", EmbeddingWire.VOYAGE, "https://api.voyageai.com/v1", "voyage", "k", Map.of());

    Optional<EmbeddingProvider> built =
        WireEmbedders.build(resolved, JsonMapper.builder().build(), LOADER);

    assertThat(built)
        .get()
        .isInstanceOfSatisfying(VoyageEmbeddingProvider.class, VoyageEmbeddingProvider::close);
  }

  @Test
  void each_wire_names_the_module_it_needs() {
    assertThat(WireEmbedders.artifactId(EmbeddingWire.OPENAI)).isEqualTo("nessy-embedding-openai");
    assertThat(WireEmbedders.artifactId(EmbeddingWire.GEMINI)).isEqualTo("nessy-embedding-gemini");
    assertThat(WireEmbedders.artifactId(EmbeddingWire.VOYAGE)).isEqualTo("nessy-embedding-voyage");
  }

  @Test
  void a_wire_whose_adapter_is_absent_builds_nothing() {
    ClassLoader withoutVoyage = new FilteredClassLoader(VoyageEmbeddingProvider.class);
    ResolvedEmbedder resolved =
        new ResolvedEmbedder("voyage", EmbeddingWire.VOYAGE, null, "voyage", "k", Map.of());

    assertThat(WireEmbedders.isPresent(EmbeddingWire.VOYAGE, withoutVoyage)).isFalse();
    assertThat(WireEmbedders.build(resolved, null, withoutVoyage)).isEmpty();
  }

  /** Only the adapter config's own build-time check can refuse it, so it got there. */
  @Test
  void the_resolved_properties_reach_the_adapter() {
    ResolvedEmbedder resolved =
        new ResolvedEmbedder(
            "voyage",
            EmbeddingWire.VOYAGE,
            "https://api.voyageai.com/v1",
            "voyage",
            "k",
            Map.of("voyage.model", "voyage-3.5"));

    assertThatThrownBy(() -> WireEmbedders.build(resolved, null, LOADER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'voyage.model'");
  }

  @Test
  void the_openai_wire_hands_its_properties_to_the_adapter() {
    ResolvedEmbedder resolved =
        new ResolvedEmbedder(
            "openai", EmbeddingWire.OPENAI, null, "openai", "k", Map.of("openai.input", "x"));

    assertThatThrownBy(() -> WireEmbedders.build(resolved, null, LOADER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.input'");
  }

  @Test
  void the_gemini_wire_hands_its_properties_to_the_adapter() {
    ResolvedEmbedder resolved =
        new ResolvedEmbedder(
            "gemini",
            EmbeddingWire.GEMINI,
            null,
            "gcp.gemini",
            "k",
            Map.of("gemini.taskType", "CLUSTERING"));

    assertThatThrownBy(() -> WireEmbedders.build(resolved, null, LOADER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'gemini.taskType'");
  }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test -Dtest='EmbedderCatalogueTest,EmbedderSettingsTest,ResolvedEmbedderTest,WireEmbeddersTest' -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: compilation failure (`cannot find symbol: class EmbedderSettings` and the rest); `exit=1`.

- [ ] **Step 3: The wire, the preset, the settings, the resolved records**

`EmbeddingWire.java` (license header, then):

```java
package org.jwcarman.nessy.spring.boot.embedding;

import java.util.Locale;

/**
 * The API shape an embedding provider speaks, named for the vendor that defined it: {@code openai}
 * is OpenAI's {@code /v1/embeddings} shape, which local servers and gateways also serve; {@code
 * gemini} and {@code voyage} are those vendors' own. Bound from {@code nessy.embedders.<id>.wire}'s
 * property values by Boot's relaxed binding, so a typo is a binding error naming the allowed values
 * rather than an embedder that silently fails to exist.
 *
 * <p>Its own enum, apart from the inference wire: the property path already says which family is
 * meant, and OpenAI has one embeddings shape, so the value needs no qualifier. Package-private and
 * not in the SPI: nothing outside the starter depends on it.
 */
enum EmbeddingWire {
  OPENAI("openai"),
  GEMINI("gcp.gemini"),
  VOYAGE("voyage");

  private final String defaultVendor;

  EmbeddingWire(String defaultVendor) {
    this.defaultVendor = defaultVendor;
  }

  String defaultVendor() {
    return defaultVendor;
  }

  /** The property value this wire is spelled as under {@code nessy.embedders.<id>.wire}. */
  String propertyValue() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
```

`EmbedderPreset.java` (license header, then):

```java
package org.jwcarman.nessy.spring.boot.embedding;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An embedder Nessy knows how to reach, waiting for its ingredient: a vendor's key. Becomes an
 * embedding provider when the key is supplied and the vendor's embedding adapter is on the
 * classpath.
 *
 * <p>Only measured rows ship: a preset is a promise that the row works. No keyless row ships -- a
 * local server is a custom embedder, stating its URL, key and model in the open -- but {@link
 * #keyless()} and the catalogue's keyless branch stay, so a measured row can join without a code
 * shape changing.
 */
record EmbedderPreset(
    String id,
    EmbeddingWire wire,
    @Nullable String baseUrl,
    String vendor,
    List<String> keyProperties,
    @Nullable String keylessApiKey,
    Map<String, String> defaultProperties) {

  static final List<EmbedderPreset> CATALOGUE =
      List.of(
          new EmbedderPreset(
              "openai",
              EmbeddingWire.OPENAI,
              null,
              "openai",
              List.of("openai.api-key"),
              null,
              Map.of()),
          new EmbedderPreset(
              "gemini",
              EmbeddingWire.GEMINI,
              null,
              "gcp.gemini",
              List.of("gemini.api-key", "google.api-key"),
              null,
              Map.of()),
          new EmbedderPreset(
              "voyage",
              EmbeddingWire.VOYAGE,
              "https://api.voyageai.com/v1",
              "voyage",
              List.of("voyage.api-key"),
              null,
              Map.of()));

  boolean keyless() {
    return keylessApiKey != null;
  }
}
```

`EmbedderSettings.java` (license header, then):

```java
package org.jwcarman.nessy.spring.boot.embedding;

import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * What {@code nessy.embedders.<id>} says. Every field optional; a preset fills the gaps, and a
 * custom embedder (an id not in the catalogue) must supply {@code wire}, {@code baseUrl} and {@code
 * apiKey} itself.
 *
 * <p>{@code vendor} is honoured on the {@link EmbeddingWire#OPENAI} wire only: the Gemini and
 * Voyage adapters report their own fixed vendor, and the setting is ignored for them. {@code
 * properties} are vendor properties for this embedder, bound as {@code Map<String, String>} so a
 * dotted key stays one entry; a preset's defaults are overlaid by them, name by name.
 */
record EmbedderSettings(
    @Nullable EmbeddingWire wire,
    @Nullable String baseUrl,
    @Nullable String apiKey,
    @Nullable Boolean enabled,
    @Nullable String vendor,
    @Nullable Map<String, String> properties) {

  /**
   * Redacts the key, and prints property names but never their values: the generated form would
   * print both in a log or a test failure.
   */
  @Override
  public String toString() {
    return "EmbedderSettings[wire="
        + wire
        + ", baseUrl="
        + baseUrl
        + ", apiKey="
        + (apiKey != null ? "***" : "null")
        + ", enabled="
        + enabled
        + ", vendor="
        + vendor
        + ", properties="
        + (properties != null ? new TreeSet<>(properties.keySet()) : "null")
        + "]";
  }
}
```

`ResolvedEmbedder.java` (license header, then):

```java
package org.jwcarman.nessy.spring.boot.embedding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * A preset or a custom embedder once every field has been decided: what {@link EmbedderCatalogue}
 * resolves, {@link WireEmbedders} builds from, and the report reads to say what is registered.
 *
 * <p>Its registry id is {@link #id()}; its Spring bean is {@link #beanName()}, because one bean
 * namespace also holds the inference provider the same key lights under the same id.
 */
record ResolvedEmbedder(
    String id,
    EmbeddingWire wire,
    @Nullable String baseUrl,
    String vendor,
    @Nullable String apiKey,
    Map<String, String> properties) {

  ResolvedEmbedder {
    properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
  }

  /** The {@code EmbeddingProvider} bean this embedder is registered as: {@code openaiEmbeddings}. */
  String beanName() {
    return id + "Embeddings";
  }

  /** Redacts the key; prints property names, never values. */
  @Override
  public String toString() {
    return "ResolvedEmbedder[id="
        + id
        + ", wire="
        + wire
        + ", baseUrl="
        + baseUrl
        + ", vendor="
        + vendor
        + ", apiKey="
        + (apiKey != null ? "***" : "null")
        + ", properties="
        + new TreeSet<>(properties.keySet())
        + "]";
  }
}
```

`ResolvedEmbedders.java` (license header, then):

```java
package org.jwcarman.nessy.spring.boot.embedding;

import java.util.List;

/**
 * Every preset or custom embedder the registrar actually registered (never a skipped one), for the
 * factory bean to register under its id and for the report to tell a resolved embedder apart from
 * an application's own {@code EmbeddingProvider} bean.
 *
 * <p>Registered as the singleton bean {@code nessyResolvedEmbedders}.
 */
record ResolvedEmbedders(List<ResolvedEmbedder> embedders) {

  ResolvedEmbedders {
    embedders = List.copyOf(embedders);
  }
}
```

- [ ] **Step 4: The catalogue**

`EmbedderCatalogue.java` (license header, then):

```java
package org.jwcarman.nessy.spring.boot.embedding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Turns what an application said -- {@code nessy.embedders.<id>} settings, plus the vendor
 * environment properties that light a hosted preset -- into the embedders that will actually be
 * registered.
 *
 * <p>Pure resolution: no Spring context, no bean, nothing that reads an {@code Environment}
 * directly. The registrar hands it {@code environment::getProperty} as the property lookup so this
 * class stays testable with a plain {@link Map}. The twin of the inference side's catalogue.
 */
final class EmbedderCatalogue {

  private static final EmbedderSettings EMPTY =
      new EmbedderSettings(null, null, null, null, null, null);

  private EmbedderCatalogue() {}

  static List<ResolvedEmbedder> resolve(
      Map<String, EmbedderSettings> settings, Function<String, @Nullable String> property) {
    List<ResolvedEmbedder> lit = new ArrayList<>();
    for (EmbedderPreset preset : EmbedderPreset.CATALOGUE) {
      EmbedderSettings own = settings.getOrDefault(preset.id(), EMPTY);
      // Candidates in order of precedence; the first that is non-null and non-blank wins.
      List<@Nullable String> keys = new ArrayList<>();
      keys.add(own.apiKey());
      preset.keyProperties().forEach(name -> keys.add(property.apply(name)));
      String apiKey = firstNonBlank(keys);
      // enabled=false turns a preset off no matter what ingredient it has; unset means "on if its
      // ingredient is present" for a hosted preset, and stays "off unless said" for a keyless one.
      boolean on;
      if (Boolean.FALSE.equals(own.enabled())) {
        on = false;
      } else if (preset.keyless()) {
        on = Boolean.TRUE.equals(own.enabled());
      } else {
        on = apiKey != null;
      }
      if (!on) {
        continue;
      }
      List<@Nullable String> urls = new ArrayList<>();
      urls.add(own.baseUrl());
      if ("openai".equals(preset.id())) {
        // The same key and URL pair the inference preset of the same name reads, so the two move
        // together.
        urls.add(property.apply("openai.base-url"));
      }
      urls.add(preset.baseUrl());
      Map<String, String> properties = new LinkedHashMap<>(preset.defaultProperties());
      if (own.properties() != null) {
        properties.putAll(own.properties());
      }
      lit.add(
          new ResolvedEmbedder(
              preset.id(),
              own.wire() != null ? own.wire() : preset.wire(),
              firstNonBlank(urls),
              own.vendor() != null ? own.vendor() : preset.vendor(),
              preset.keyless() ? keylessApiKey(own, preset) : apiKey,
              properties));
    }
    settings.forEach(
        (id, own) -> {
          if (Boolean.FALSE.equals(own.enabled())) {
            return;
          }
          if (EmbedderPreset.CATALOGUE.stream().noneMatch(p -> p.id().equals(id))) {
            lit.add(custom(id, own));
          }
        });
    return List.copyOf(lit);
  }

  /** A keyless preset's key: what an application overrode it to, or its placeholder. */
  private static @Nullable String keylessApiKey(EmbedderSettings own, EmbedderPreset preset) {
    List<@Nullable String> keys = new ArrayList<>();
    keys.add(own.apiKey());
    keys.add(preset.keylessApiKey());
    return firstNonBlank(keys);
  }

  private static ResolvedEmbedder custom(String id, EmbedderSettings own) {
    EmbeddingWire wire = own.wire();
    if (wire == null) {
      throw new IllegalStateException(
          "nessy.embedders." + id + ".wire is required: " + id + " is not a preset");
    }
    if (own.baseUrl() == null || own.baseUrl().isBlank()) {
      throw new IllegalStateException(
          "nessy.embedders." + id + ".base-url is required: " + id + " is not a preset");
    }
    if (own.apiKey() == null || own.apiKey().isBlank()) {
      throw new IllegalStateException(
          "nessy.embedders."
              + id
              + ".api-key is required: the "
              + wire.propertyValue()
              + " wire needs a key");
    }
    return new ResolvedEmbedder(
        id,
        wire,
        own.baseUrl(),
        own.vendor() != null ? own.vendor() : wire.defaultVendor(),
        own.apiKey(),
        own.properties() != null ? own.properties() : Map.of());
  }

  /**
   * The first candidate that is non-null and non-blank, or {@code null} when none is: a property
   * set to blank, as {@code ${VAR:}} yields when the variable is unset, is a property nobody set.
   */
  private static @Nullable String firstNonBlank(List<@Nullable String> candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate;
      }
    }
    return null;
  }
}
```

- [ ] **Step 5: The builders**

`WireEmbedders.java` (license header, then):

```java
package org.jwcarman.nessy.spring.boot.embedding;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.jwcarman.nessy.embedding.EmbeddingProvider;
import org.jwcarman.nessy.embedding.gemini.GeminiEmbeddingProvider;
import org.jwcarman.nessy.embedding.openai.OpenAiEmbeddingProvider;
import org.jwcarman.nessy.embedding.voyage.VoyageEmbeddingProvider;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds an {@link EmbeddingProvider} for a resolved embedder's wire, loading the wire's adapter
 * class only when it is actually needed.
 *
 * <p>Each wire's construction lives in its own private static nested class, so a wire whose adapter
 * jar is not on the classpath never triggers that adapter class's loading: the nested class is
 * first used only after the classpath check has passed. Sets what each adapter's config offers and
 * no more; no timeout is set, so Voyage keeps its own.
 */
final class WireEmbedders {

  private static final String OPENAI_CLASS =
      "org.jwcarman.nessy.embedding.openai.OpenAiEmbeddingProvider";
  private static final String GEMINI_CLASS =
      "org.jwcarman.nessy.embedding.gemini.GeminiEmbeddingProvider";
  private static final String VOYAGE_CLASS =
      "org.jwcarman.nessy.embedding.voyage.VoyageEmbeddingProvider";

  private WireEmbedders() {}

  /**
   * Whether the wire's adapter class is on the given classpath, without loading it. The caller
   * supplies the bean factory's class loader, so a test's {@code FilteredClassLoader} is consulted.
   */
  static boolean isPresent(EmbeddingWire wire, ClassLoader classLoader) {
    return ClassUtils.isPresent(adapterClassName(wire), classLoader);
  }

  /** The Maven artifact a lit embedder needs, named for the registrar's skip line. */
  static String artifactId(EmbeddingWire wire) {
    return switch (wire) {
      case OPENAI -> "nessy-embedding-openai";
      case GEMINI -> "nessy-embedding-gemini";
      case VOYAGE -> "nessy-embedding-voyage";
    };
  }

  private static String adapterClassName(EmbeddingWire wire) {
    return switch (wire) {
      case OPENAI -> OPENAI_CLASS;
      case GEMINI -> GEMINI_CLASS;
      case VOYAGE -> VOYAGE_CLASS;
    };
  }

  /** Builds the provider, or nothing when the wire's adapter is absent. */
  static Optional<EmbeddingProvider> build(
      ResolvedEmbedder resolved, @Nullable JsonMapper mapper, ClassLoader classLoader) {
    if (!isPresent(resolved.wire(), classLoader)) {
      return Optional.empty();
    }
    return Optional.of(
        switch (resolved.wire()) {
          case OPENAI -> OpenAi.build(resolved);
          case GEMINI -> Gemini.build(resolved);
          case VOYAGE -> Voyage.build(resolved, mapper);
        });
  }

  private static final class OpenAi {

    private OpenAi() {}

    static EmbeddingProvider build(ResolvedEmbedder resolved) {
      return OpenAiEmbeddingProvider.of(
          c -> {
            c.apiKey(resolved.apiKey());
            if (resolved.baseUrl() != null) {
              c.baseUrl(resolved.baseUrl());
            }
            c.vendor(resolved.vendor());
            c.properties(resolved.properties());
          });
    }
  }

  private static final class Gemini {

    private Gemini() {}

    static EmbeddingProvider build(ResolvedEmbedder resolved) {
      return GeminiEmbeddingProvider.of(
          c -> {
            c.apiKey(resolved.apiKey());
            if (resolved.baseUrl() != null) {
              c.baseUrl(resolved.baseUrl());
            }
            c.properties(resolved.properties());
          });
    }
  }

  private static final class Voyage {

    private Voyage() {}

    static EmbeddingProvider build(ResolvedEmbedder resolved, @Nullable JsonMapper mapper) {
      return VoyageEmbeddingProvider.of(
          c -> {
            c.apiKey(resolved.apiKey());
            if (resolved.baseUrl() != null) {
              c.baseUrl(resolved.baseUrl());
            }
            c.properties(resolved.properties());
            if (mapper != null) {
              c.mapper(mapper);
            }
          });
    }
  }
}
```

(`nessy-embedding-openai`, `-gemini` and `-voyage` are already optional dependencies of this module, `nessy-spring-boot/autoconfigure/pom.xml` lines 186-203; `spring-boot-test`, which holds `FilteredClassLoader`, is already on its test classpath, as `InferenceProvidersAutoConfigurationTest` uses it.)

- [ ] **Step 6: Run them to see them pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test -Dtest='EmbedderCatalogueTest,EmbedderSettingsTest,ResolvedEmbedderTest,WireEmbeddersTest' -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: `exit=0`.

- [ ] **Step 7: Full gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?`
Expected: `exit=0`.

```bash
cd /Users/jcarman/IdeaProjects/nessy-embedders
git add nessy-spring-boot/autoconfigure/src
git commit -m "feat: the embedder catalogue -- three measured presets, custom embedders, and the builders

EmbeddingWire (openai, gemini, voyage), EmbedderPreset, EmbedderSettings,
ResolvedEmbedder(s) and EmbedderCatalogue mirror the inference catalogue for
nessy.embedders.<id>; WireEmbedders builds each wire's adapter only when its
jar is present and hands it the resolved vendor properties. Not wired in
yet.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 5: Boot, the wired half -- registrar, factory bean, the pair, the report, and chat-web

Spec §6a, §6b, §6f-§6i, §8a, §11a; Plan rulings 5, 12, 13, 15, 17. The three vendor auto-configurations, their two conditions and `EmbeddingModels` are deleted; `EmbeddingProvidersAutoConfiguration` replaces them in the imports file; chat-web moves in the same commit, because its `episodes` bean decided "no embeddings" by the factory's absence and the factory is now always present. Suggested implementer: Sonnet. **Review: Opus** (see Global Constraints).

**Files:**
- Create (all in `nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/embedding/`): `EmbedderRegistrar.java`, `EmbeddingReport.java`, `EmbeddingProvidersAutoConfiguration.java`
- Delete (same directory): `OpenAiEmbeddingAutoConfiguration.java`, `GeminiEmbeddingAutoConfiguration.java`, `VoyageEmbeddingAutoConfiguration.java`, `ConditionalOnConfiguredProperty.java`, `OnConfiguredProperty.java`, `OnNoOpenAiBaseUrl.java`, `EmbeddingModels.java`
- Delete: `nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/embedding/EmbeddingAutoConfigurationTest.java`
- Modify: `nessy-spring-boot/autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (lines 5-7)
- Modify: `nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/NessyProperties.java`
- Test: `nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/NessyPropertiesTest.java`
- Test (create): `nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/embedding/EmbeddingProvidersAutoConfigurationTest.java`, `EmbeddingReportTest.java`
- Modify: `nessy-memory/episodic/src/main/java/org/jwcarman/nessy/memory/episodic/JdbcEpisodes.java` (`Config.embedder` javadoc, lines 130-134)
- Modify: `nessy-examples/chat-web/src/main/java/org/jwcarman/nessy/examples/chatweb/ChatConfiguration.java` (lines 110-121), `nessy-examples/chat-web/src/main/resources/application.yml` (lines 69-84), `nessy-examples/chat-web/src/test/java/org/jwcarman/nessy/examples/chatweb/EpisodesWiringTest.java`, `nessy-examples/chat-web/README.md` (lines 47-67)

**Interfaces:**
- Consumes (Task 4): `EmbedderCatalogue.resolve(...)`, `EmbedderSettings`, `ResolvedEmbedder` (`id()`, `wire()`, `baseUrl()`, `vendor()`, `properties()`, `beanName()`), `ResolvedEmbedders(List<ResolvedEmbedder>)`, `WireEmbedders.isPresent/artifactId/build`, `EmbeddingWire.propertyValue()`. (Task 1): `DefaultEmbedderFactory.of(Customizer<EmbedderFactoryConfig>)`, `EmbedderFactoryConfig.provider/embedding/observations`.
- Produces (public): `EmbeddingProvidersAutoConfiguration` with beans `nessyEmbedderRegistrar` (static), `nessyEmbedderFactory` (`DefaultEmbedderFactory`, `@ConditionalOnMissingBean(EmbedderFactory.class)`), `nessyEmbeddingReport`; `NessyProperties` gains trailing components `String embedder, String embeddingModel, Integer embeddingDimension` (properties `nessy.embedder`, `nessy.embedding-model`, `nessy.embedding-dimension`); blank `embedder` / `embeddingModel` become `null`.
- Produces (beans): one `EmbeddingProvider` bean per registered preset or custom embedder, named `<id>Embeddings`; `nessyResolvedEmbedders` (`ResolvedEmbedders`).
- Produces (messages Task 7 documents): `NESSY EMBEDDING: <id> is configured but <artifact> is not on the classpath; skipped`; `NESSY EMBEDDING: embedders: ...`; `NESSY EMBEDDING: default: <id> / <model>[, <n> wide]`; `NESSY EMBEDDING: no embedder is configured; stores rank by recency`; `NESSY EMBEDDING: no default embedder; every store names its own`; `nessy.embedder and nessy.embedding-model are a pair: ...`; `a bean named '<id>Embeddings' and the <id> embedder would both be registered as '<id>Embeddings'; rename the bean or unset the embedder's key`.

- [ ] **Step 1: The pair on `NessyProperties`, test first**

`NessyPropertiesTest.java` -- every construction gains three trailing `null`s:

```bash
cd /Users/jcarman/IdeaProjects/nessy-embedders
perl -0pi -e 's/new NessyProperties\(((?:[^()]|\([^()]*\))*)\)/new NessyProperties($1, null, null, null)/g' \
  nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/NessyPropertiesTest.java
git grep -n "new NessyProperties(" -- nessy-spring-boot/autoconfigure/src/test
```

Expected: every listed construction now has eleven arguments. Then add, as a nested class beside the file's others (import `org.junit.jupiter.api.Nested` if absent):

```java
  @Nested
  class TheDefaultEmbedder {

    /** §6h: a blank placeholder, as ${CHAT_EMBEDDER:} yields, is a property nobody set. */
    @Test
    void a_blank_embedder_and_model_are_unset() {
      NessyProperties properties =
          new NessyProperties(null, null, null, null, null, null, null, null, " ", "", null);

      assertThat(properties.embedder()).isNull();
      assertThat(properties.embeddingModel()).isNull();
    }

    @Test
    void a_given_pair_and_width_are_kept() {
      NessyProperties properties =
          new NessyProperties(
              null, null, null, null, null, null, null, null, "voyage", "voyage-3.5", 1024);

      assertThat(properties.embedder()).isEqualTo("voyage");
      assertThat(properties.embeddingModel()).isEqualTo("voyage-3.5");
      assertThat(properties.embeddingDimension()).isEqualTo(1024);
    }
  }
```

`NessyProperties.java` -- add three components after `initializeSchema`, their `@param`s in the existing javadoc (edit that comment; do not add a second one), and two lines at the end of the compact constructor:

```java
 * @param embedder the embedding provider a store's embedder is minted over when the store names
 *     none. Set with {@link #embeddingModel}, or not at all; blank is unset
 * @param embeddingModel the model a store's embedder asks for when the store names none, on
 *     whichever registered {@code EmbeddingProvider} {@link #embedder} names; blank is unset
 * @param embeddingDimension how many coordinates that default embedder asks for; optional, and
 *     only beside {@link #embedder} and {@link #embeddingModel}
```

```java
    Boolean initializeSchema,
    String embedder,
    String embeddingModel,
    Integer embeddingDimension) {
```

```java
    // Blank is unset: ${CHAT_EMBEDDER:} binds as the empty string when the variable is not set, and
    // a store asks whether a default embedder was named.
    embedder = embedder == null || embedder.isBlank() ? null : embedder;
    embeddingModel = embeddingModel == null || embeddingModel.isBlank() ? null : embeddingModel;
```

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test -Dtest=NessyPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: `exit=0`. (The test and the record change in one step: the perl edit alone does not compile.)

- [ ] **Step 2: Write the failing matrix and report tests**

`EmbeddingProvidersAutoConfigurationTest.java` (license header, then):

```java
package org.jwcarman.nessy.spring.boot.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.EmbedderConfig;
import org.jwcarman.nessy.api.embedding.EmbedderFactory;
import org.jwcarman.nessy.api.embedding.Embedding;
import org.jwcarman.nessy.embedding.EmbeddingOptions;
import org.jwcarman.nessy.embedding.EmbeddingProvider;
import org.jwcarman.nessy.embedding.openai.OpenAiEmbeddingProvider;
import org.jwcarman.nessy.engine.observability.ObservedEmbedder;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.spring.boot.NessyProperties;
import org.jwcarman.nessy.spring.boot.inference.InferenceProvidersAutoConfiguration;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * What {@code nessy.embedders.*} -- a vendor key, a prefixed key, an explicit {@code enabled}, a
 * custom id -- registers as {@code EmbeddingProvider} beans, what the factory's default is, and
 * what fails, re-run as {@code ApplicationContextRunner} rows against the named-embedders design
 * record's §11a. Every outcome is decided by something written down.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("The embedders, and what lights them")
class EmbeddingProvidersAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(EmbeddingProvidersAutoConfiguration.class))
          .withBean(ObservationRegistry.class, ObservationRegistry::create);

  private static Map<String, EmbeddingProvider> providers(AssertableApplicationContext context) {
    return context.getBeansOfType(EmbeddingProvider.class);
  }

  private static ResolvedEmbedder resolved(AssertableApplicationContext context, String id) {
    return context.getBean(ResolvedEmbedders.class).embedders().stream()
        .filter(embedder -> embedder.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private static EmbedderFactory factory(AssertableApplicationContext context) {
    return context.getBean(EmbedderFactory.class);
  }

  private static SystemEnvironmentPropertySource environment(Map<String, Object> variables) {
    return new SystemEnvironmentPropertySource(
        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables);
  }

  // ---- what lights --------------------------------------------------------------------------

  @Test
  void nothing_configured_registers_nothing_and_the_factory_says_so() {
    runner.run(
        context -> {
          assertThat(providers(context)).isEmpty();
          EmbedderFactory embedders = factory(context);
          Customizer<EmbedderConfig> defaults = c -> {};
          assertThatThrownBy(() -> embedders.create(defaults))
              .isInstanceOf(IllegalStateException.class)
              .hasMessage(
                  "an embedder names no provider and the factory has no default; registered: []");
        });
  }

  @Test
  void an_openai_key_registers_one_embedder_whose_bean_carries_the_suffix() {
    runner
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context -> {
              assertThat(providers(context)).containsOnlyKeys("openaiEmbeddings");
              assertThat(providers(context).get("openaiEmbeddings").vendor()).isEqualTo("openai");
              assertThat(resolved(context, "openai").wire()).isEqualTo(EmbeddingWire.OPENAI);
            });
  }

  /** §6b: every application with OPENAI_API_KEY and no embedding jar reads one line, once. */
  @Test
  void a_key_whose_embedding_adapter_is_absent_is_skipped_with_one_line(CapturedOutput output) {
    Logger registrar = (Logger) LoggerFactory.getLogger(EmbedderRegistrar.class);
    registrar.setLevel(Level.INFO);
    try {
      runner
          .withClassLoader(new FilteredClassLoader(OpenAiEmbeddingProvider.class))
          .withPropertyValues("openai.api-key=sk-test")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(providers(context)).isEmpty();
              });
      assertThat(output)
          .contains(
              "NESSY EMBEDDING: openai is configured but nessy-embedding-openai is not on the"
                  + " classpath; skipped");
    } finally {
      registrar.setLevel(null);
    }
  }

  @Test
  void three_keys_register_three_embedders_and_none_is_chosen() {
    runner
        .withPropertyValues(
            "openai.api-key=sk-test", "gemini.api-key=g-test", "voyage.api-key=v-test")
        .run(
            context -> {
              assertThat(providers(context))
                  .containsOnlyKeys("openaiEmbeddings", "geminiEmbeddings", "voyageEmbeddings");
              EmbedderFactory embedders = factory(context);
              Customizer<EmbedderConfig> defaults = c -> {};
              assertThatThrownBy(() -> embedders.create(defaults))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessage(
                      "an embedder names no provider and the factory has no default; registered:"
                          + " [openai, gemini, voyage]");
            });
  }

  /** The row that used to make Voyage win: both register, and neither is chosen. */
  @Test
  void openai_and_voyage_keys_register_two() {
    runner
        .withPropertyValues("openai.api-key=sk-test", "voyage.api-key=v-test")
        .run(
            context ->
                assertThat(providers(context))
                    .containsOnlyKeys("openaiEmbeddings", "voyageEmbeddings"));
  }

  @Test
  void both_gemini_keys_register_one_gemini_embedder() {
    runner
        .withPropertyValues("gemini.api-key=g-test", "google.api-key=o-test")
        .run(context -> assertThat(providers(context)).containsOnlyKeys("geminiEmbeddings"));
  }

  @Test
  void the_prefixed_form_of_a_key_registers_the_preset() {
    runner
        .withPropertyValues("nessy.embedders.voyage.api-key=v-test")
        .run(context -> assertThat(providers(context)).containsOnlyKeys("voyageEmbeddings"));
  }

  @Test
  void the_environment_variable_form_of_a_prefixed_key_registers_the_preset() {
    runner
        .withInitializer(
            context ->
                context
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(environment(Map.of("NESSY_EMBEDDERS_VOYAGE_APIKEY", "v-test"))))
        .run(context -> assertThat(providers(context)).containsOnlyKeys("voyageEmbeddings"));
  }

  /** §6b: Voyage's key has its conventional name, the variable the adapter's fromEnv reads. */
  @Test
  void voyage_s_conventional_environment_variable_registers_voyage() {
    runner
        .withInitializer(
            context ->
                context
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(environment(Map.of("VOYAGE_API_KEY", "v-test"))))
        .run(context -> assertThat(providers(context)).containsOnlyKeys("voyageEmbeddings"));
  }

  /** No model named anywhere, and nothing fails: a key registers; a store names the model. */
  @Test
  void the_openai_base_url_moves_the_endpoint_and_no_model_is_required() {
    runner
        .withPropertyValues("openai.api-key=lm-studio", "openai.base-url=http://localhost:1234/v1")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(resolved(context, "openai").baseUrl())
                  .isEqualTo("http://localhost:1234/v1");
            });
  }

  /** §6c made checkable: lmstudio is not an embedding preset. */
  @Test
  void lmstudio_is_not_a_preset_and_turning_it_on_fails_naming_the_wire() {
    runner
        .withPropertyValues("nessy.embedders.lmstudio.enabled=true")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining(
                      "nessy.embedders.lmstudio.wire is required: lmstudio is not a preset");
            });
  }

  @Test
  void a_custom_embedder_at_a_local_server_registers_with_its_own_vendor() {
    runner
        .withPropertyValues(
            "nessy.embedders.local.wire=openai",
            "nessy.embedders.local.base-url=http://localhost:1234/v1",
            "nessy.embedders.local.api-key=lm-studio",
            "nessy.embedders.local.vendor=lmstudio")
        .run(
            context -> {
              assertThat(providers(context)).containsOnlyKeys("localEmbeddings");
              Embedder embedder =
                  factory(context)
                      .create(c -> c.provider("local").model("text-embedding-nomic-embed-text-v1.5"));
              assertThat(embedder.vendor()).isEqualTo("lmstudio");
            });
  }

  @Test
  void a_custom_embedder_s_vendor_defaults_to_the_wire_s() {
    runner
        .withPropertyValues(
            "nessy.embedders.mine.wire=openai",
            "nessy.embedders.mine.base-url=https://g/v1",
            "nessy.embedders.mine.api-key=k")
        .run(
            context -> {
              assertThat(providers(context)).containsOnlyKeys("mineEmbeddings");
              assertThat(providers(context).get("mineEmbeddings").vendor()).isEqualTo("openai");
            });
  }

  /** §6h left this binder fact open; this pins it. */
  @Test
  void a_blank_enabled_binds_as_unset() {
    runner
        .withPropertyValues("gemini.api-key=g-test", "nessy.embedders.gemini.enabled=")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(providers(context)).containsOnlyKeys("geminiEmbeddings");
            });
  }

  @Test
  void a_gemini_key_set_for_chat_can_leave_embeddings_off() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                InferenceProvidersAutoConfiguration.class,
                EmbeddingProvidersAutoConfiguration.class))
        .withBean(ObservationRegistry.class, ObservationRegistry::create)
        .withPropertyValues("gemini.api-key=g-test", "nessy.embedders.gemini.enabled=false")
        .run(
            context -> {
              assertThat(context.getBeansOfType(InferenceProvider.class)).containsOnlyKeys("gemini");
              assertThat(providers(context)).isEmpty();
            });
  }

  /** §6f: one key, two registries, one bean namespace, and no collision. */
  @Test
  void one_openai_key_lights_an_inference_provider_and_an_embedder_side_by_side() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                InferenceProvidersAutoConfiguration.class,
                EmbeddingProvidersAutoConfiguration.class))
        .withBean(ObservationRegistry.class, ObservationRegistry::create)
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context -> {
              assertThat(context.getBeansOfType(InferenceProvider.class)).containsOnlyKeys("openai");
              assertThat(providers(context)).containsOnlyKeys("openaiEmbeddings");
            });
  }

  @Test
  void a_custom_embedder_with_no_wire_fails_naming_it() {
    runner
        .withPropertyValues("nessy.embedders.mine.base-url=https://g/v1")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("nessy.embedders.mine.wire is required");
            });
  }

  /** Plan ruling 15: Boot's failure analyzer prints the allowed values at a real startup. */
  @Test
  void a_bogus_wire_value_fails_naming_the_property() {
    runner
        .withPropertyValues(
            "nessy.embedders.mine.wire=bogus",
            "nessy.embedders.mine.base-url=https://g/v1",
            "nessy.embedders.mine.api-key=k")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("nessy.embedders.mine.wire");
            });
  }

  /** §4c made checkable: the OpenAI embeddings wire is openai, not openai-embeddings. */
  @Test
  void the_wire_value_openai_embeddings_does_not_exist() {
    runner
        .withPropertyValues(
            "nessy.embedders.mine.wire=openai-embeddings",
            "nessy.embedders.mine.base-url=https://g/v1",
            "nessy.embedders.mine.api-key=k")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("nessy.embedders.mine.wire");
            });
  }

  // ---- application beans ---------------------------------------------------------------------

  @Test
  void an_application_bean_joins_under_its_bean_name_beside_a_preset() {
    runner
        .withUserConfiguration(AScriptedEmbedder.class)
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context -> {
              assertThat(providers(context)).containsOnlyKeys("scripted", "openaiEmbeddings");
              factory(context).create(c -> c.provider("scripted").model("m")).embedDocument("x");
              assertThat(AScriptedEmbedder.INSTANCE.asked.get().modelName()).isEqualTo("m");
            });
  }

  @Test
  void an_application_bean_named_like_a_preset_s_bean_fails_naming_both() {
    runner
        .withUserConfiguration(AnOpenAiEmbeddingsBean.class)
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining(
                      "a bean named 'openaiEmbeddings' and the openai embedder would both be"
                          + " registered as 'openaiEmbeddings'");
            });
  }

  /** Review Focus 2: two registrations under one registry id is a failure, never a choice. */
  @Test
  void an_application_bean_named_like_a_lit_preset_s_id_fails_naming_the_id() {
    runner
        .withUserConfiguration(AVoyageBean.class)
        .withPropertyValues("voyage.api-key=v-test")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("embedding provider 'voyage' is already registered");
            });
  }

  @Test
  void an_application_embedder_factory_backs_the_starter_s_off_and_the_presets_stay() {
    EmbedderFactory ours =
        customizer -> {
          throw new UnsupportedOperationException("ours makes nothing");
        };
    runner
        .withBean(EmbedderFactory.class, () -> ours)
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context -> {
              assertThat(context).hasSingleBean(EmbedderFactory.class);
              assertThat(context.getBean(EmbedderFactory.class)).isSameAs(ours);
              assertThat(providers(context)).containsOnlyKeys("openaiEmbeddings");
            });
  }

  // ---- the default ---------------------------------------------------------------------------

  @Test
  void the_pair_names_the_default_a_store_that_says_nothing_gets() {
    runner
        .withPropertyValues(
            "voyage.api-key=v-test", "nessy.embedder=voyage", "nessy.embedding-model=voyage-3.5")
        .run(
            context -> {
              Embedder embedder = factory(context).create(c -> {});
              assertThat(embedder.model()).isEqualTo("voyage-3.5");
              assertThat(embedder.vendor()).isEqualTo("voyage");
            });
  }

  @Test
  void an_embedder_alone_fails_naming_the_pair() {
    runner
        .withPropertyValues("voyage.api-key=v-test", "nessy.embedder=voyage")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("nessy.embedder and nessy.embedding-model are a pair");
            });
  }

  @Test
  void an_embedding_model_alone_fails_naming_the_pair() {
    runner
        .withPropertyValues("voyage.api-key=v-test", "nessy.embedding-model=voyage-3.5")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("nessy.embedder and nessy.embedding-model are a pair");
            });
  }

  /** Review Focus 3: chat-web's own shape, with neither variable set. */
  @Test
  void a_blank_pair_is_no_default() {
    runner
        .withPropertyValues("openai.api-key=sk-test", "nessy.embedder=", "nessy.embedding-model=")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(NessyProperties.class).embedder()).isNull();
            });
  }

  /** Review Focus 3. */
  @Test
  void a_blank_embedder_beside_a_model_fails_naming_the_pair() {
    runner
        .withPropertyValues("nessy.embedder=", "nessy.embedding-model=voyage-3.5")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("nessy.embedder and nessy.embedding-model are a pair");
            });
  }

  /** Plan ruling 5: at startup, not at the first store that asks. */
  @Test
  void a_default_naming_an_unregistered_embedder_fails_at_startup_listing_what_is() {
    runner
        .withPropertyValues(
            "openai.api-key=sk-test", "nessy.embedder=cohere", "nessy.embedding-model=embed-v4")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining(
                      "an embedder names provider 'cohere', which is not registered; registered:"
                          + " [openai]");
            });
  }

  @Test
  void the_default_width_is_the_width_a_default_embedder_asks_for() {
    runner
        .withPropertyValues(
            "voyage.api-key=v-test",
            "nessy.embedder=voyage",
            "nessy.embedding-model=voyage-3.5",
            "nessy.embedding-dimension=256")
        .run(context -> assertThat(factory(context).create(c -> {}).dimension()).isEqualTo(256));
  }

  @Test
  void a_width_without_the_pair_fails() {
    runner
        .withPropertyValues("voyage.api-key=v-test", "nessy.embedding-dimension=256")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("nessy.embedding-dimension");
            });
  }

  // ---- vendor properties ---------------------------------------------------------------------

  /** §7a: a dotted key under properties binds as one entry, as it does for inference. */
  @Test
  void a_dotted_property_binds_as_one_entry() {
    runner
        .withPropertyValues(
            "voyage.api-key=v-test", "nessy.embedders.voyage.properties.voyage.truncation=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(resolved(context, "voyage").properties())
                  .containsExactly(Map.entry("voyage.truncation", "false"));
            });
  }

  @Test
  void a_property_naming_a_field_the_adapter_writes_fails_at_startup() {
    runner
        .withPropertyValues(
            "voyage.api-key=v-test", "nessy.embedders.voyage.properties.voyage.model=voyage-3.5")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasStackTraceContaining("'voyage.model'");
            });
  }

  @Test
  void another_adapter_s_property_on_an_embedder_fails_at_startup_naming_the_prefix() {
    runner
        .withPropertyValues(
            "voyage.api-key=v-test", "nessy.embedders.voyage.properties.openai.user=x")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("'openai.user'")
                  .hasStackTraceContaining("'voyage.'");
            });
  }

  // ---- observed ------------------------------------------------------------------------------

  @Test
  void with_a_registry_every_embedder_is_observed() {
    runner
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context ->
                assertThat(
                        factory(context)
                            .create(c -> c.provider("openai").model("text-embedding-3-small")))
                    .isInstanceOf(ObservedEmbedder.class));
  }

  @Test
  void without_one_every_embedder_is_still_observed() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(EmbeddingProvidersAutoConfiguration.class))
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context ->
                assertThat(
                        factory(context)
                            .create(c -> c.provider("openai").model("text-embedding-3-small")))
                    .isInstanceOf(ObservedEmbedder.class));
  }

  // ---- fixtures ------------------------------------------------------------------------------

  /** Answers two-wide vectors and remembers what it was asked. */
  static final class Scripted implements EmbeddingProvider {
    final AtomicReference<EmbeddingOptions> asked = new AtomicReference<>();

    @Override
    public List<Embedding> embedDocuments(List<String> texts, EmbeddingOptions options) {
      asked.set(options);
      return texts.stream()
          .map(text -> new Embedding(options.modelName(), new float[] {1f, 0f}))
          .toList();
    }

    @Override
    public Embedding embedQuery(String query, EmbeddingOptions options) {
      asked.set(options);
      return new Embedding(options.modelName(), new float[] {1f, 0f});
    }

    @Override
    public String vendor() {
      return "scripted";
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AScriptedEmbedder {

    static final Scripted INSTANCE = new Scripted();

    @Bean
    EmbeddingProvider scripted() {
      return INSTANCE;
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AnOpenAiEmbeddingsBean {

    @Bean
    EmbeddingProvider openaiEmbeddings() {
      return new Scripted();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AVoyageBean {

    @Bean
    EmbeddingProvider voyage() {
      return new Scripted();
    }
  }
}
```

If `a_blank_enabled_binds_as_unset` fails because the binder refuses a blank `Boolean`, apply the contingency the spec records (§15 (3)) rather than stopping: `EmbedderSettings.enabled` becomes `@Nullable String enabled`, and `EmbedderCatalogue` reads it through a private `static @Nullable Boolean enabled(String id, @Nullable String raw)` that returns `null` for null or blank, `Boolean.TRUE`/`FALSE` for `true`/`false` (case-insensitive), and otherwise throws `IllegalStateException("nessy.embedders." + id + ".enabled must be true or false, was '" + raw + "'")`; the two `Boolean.FALSE.equals(own.enabled())` / `Boolean.TRUE.equals(own.enabled())` tests use it. Say so in the report.

`EmbeddingReportTest.java` (license header, then):

```java
package org.jwcarman.nessy.spring.boot.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.nessy.spring.boot.NessyProperties;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * What the startup lines say once every registered {@code EmbeddingProvider} bean exists: every
 * embedder by id, the default, and never a key or a property's value.
 *
 * <p>The house test logging config (root {@code WARN}) would swallow these lines, which are INFO;
 * this class's logger is turned up for the duration of these tests alone.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("The embedding report")
class EmbeddingReportTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(EmbeddingProvidersAutoConfiguration.class))
          .withBean(ObservationRegistry.class, ObservationRegistry::create);

  @BeforeEach
  void turnOnInfoLogging() {
    logger().setLevel(Level.INFO);
  }

  @AfterEach
  void restoreLogging() {
    logger().setLevel(null);
  }

  private static Logger logger() {
    return (Logger) LoggerFactory.getLogger(EmbeddingReport.class);
  }

  private static void report(AssertableApplicationContext context) {
    new EmbeddingReport(
            context.getBeanProvider(ResolvedEmbedders.class),
            context,
            context.getBean(NessyProperties.class))
        .afterSingletonsInstantiated();
  }

  @Test
  void the_embedders_line_names_every_embedder_and_never_a_key_or_a_value(
      CapturedOutput output) {
    runner
        .withPropertyValues(
            "openai.api-key=sk-super-secret",
            "voyage.api-key=pa-super-secret",
            "nessy.embedders.voyage.properties.voyage.truncation=false")
        .run(
            context -> {
              report(context);

              assertThat(output)
                  .contains(
                      "NESSY EMBEDDING: embedders: openai (openai, the vendor's own endpoint,"
                          + " vendor openai); voyage (voyage, https://api.voyageai.com/v1, vendor"
                          + " voyage, properties [voyage.truncation])")
                  .doesNotContain("sk-super-secret")
                  .doesNotContain("pa-super-secret")
                  .doesNotContain("truncation=false");
            });
  }

  @Test
  void the_default_line_names_the_pair_and_the_width(CapturedOutput output) {
    runner
        .withPropertyValues(
            "voyage.api-key=v-test",
            "nessy.embedder=voyage",
            "nessy.embedding-model=voyage-3.5",
            "nessy.embedding-dimension=1024")
        .run(
            context -> {
              report(context);

              assertThat(output).contains("NESSY EMBEDDING: default: voyage / voyage-3.5, 1024 wide");
            });
  }

  @Test
  void the_default_line_without_a_width_names_the_pair(CapturedOutput output) {
    runner
        .withPropertyValues(
            "voyage.api-key=v-test", "nessy.embedder=voyage", "nessy.embedding-model=voyage-3.5")
        .run(
            context -> {
              report(context);

              assertThat(output)
                  .contains("NESSY EMBEDDING: default: voyage / voyage-3.5")
                  .doesNotContain(" wide");
            });
  }

  @Test
  void with_nothing_registered_it_says_stores_rank_by_recency(CapturedOutput output) {
    runner.run(
        context -> {
          report(context);

          assertThat(output)
              .contains("NESSY EMBEDDING: no embedder is configured; stores rank by recency");
        });
  }

  @Test
  void with_embedders_and_no_default_it_says_every_store_names_its_own(CapturedOutput output) {
    runner
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context -> {
              report(context);

              assertThat(output)
                  .contains("NESSY EMBEDDING: no default embedder; every store names its own");
            });
  }

  @Test
  void an_application_bean_prints_the_vendor_it_reports(CapturedOutput output) {
    runner
        .withUserConfiguration(EmbeddingProvidersAutoConfigurationTest.AScriptedEmbedder.class)
        .run(
            context -> {
              report(context);

              assertThat(output).contains("NESSY EMBEDDING: embedders: scripted (vendor scripted)");
            });
  }

  @Test
  void a_custom_embedder_prints_the_vendor_its_bean_reports(CapturedOutput output) {
    runner
        .withPropertyValues(
            "nessy.embedders.local.wire=openai",
            "nessy.embedders.local.base-url=http://localhost:1234/v1",
            "nessy.embedders.local.api-key=lm-studio",
            "nessy.embedders.local.vendor=lmstudio")
        .run(
            context -> {
              report(context);

              assertThat(output)
                  .contains("local (openai, http://localhost:1234/v1, vendor lmstudio)");
            });
  }
}
```

- [ ] **Step 3: Run them to see them fail**

Run: `./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test -Dtest='EmbeddingProvidersAutoConfigurationTest,EmbeddingReportTest' -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: compilation failure (`cannot find symbol: class EmbeddingProvidersAutoConfiguration`, `EmbedderRegistrar`, `EmbeddingReport`); `exit=1`.

- [ ] **Step 4: The registrar**

`EmbedderRegistrar.java` (license header, then):

```java
package org.jwcarman.nessy.spring.boot.embedding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jwcarman.nessy.embedding.EmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns {@code nessy.embedders.*} into {@link EmbeddingProvider} beans, one per lit preset or custom
 * embedder, named {@code <id>Embeddings} -- the id itself is the inference registrar's bean when
 * the same key lights both -- and publishes {@link ResolvedEmbedders} so the factory registers each
 * under its id.
 *
 * <p>Not observed here: the factory wraps every embedder it mints, which is where the model and the
 * width are known. The container owns each provider's connection and closes it at shutdown.
 */
class EmbedderRegistrar
    implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, BeanFactoryAware {

  private static final Logger log = LoggerFactory.getLogger(EmbedderRegistrar.class);

  private Environment environment;
  private ConfigurableListableBeanFactory beanFactory;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void setBeanFactory(BeanFactory beanFactory) {
    this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
  }

  @Override
  public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
    Map<String, EmbedderSettings> settings =
        Binder.get(environment)
            .bind("nessy.embedders", Bindable.mapOf(String.class, EmbedderSettings.class))
            .orElse(Map.of());
    List<ResolvedEmbedder> resolved = EmbedderCatalogue.resolve(settings, environment::getProperty);

    List<ResolvedEmbedder> registered = new ArrayList<>();
    for (ResolvedEmbedder embedder : resolved) {
      String beanName = embedder.beanName();
      if (registry.containsBeanDefinition(beanName)) {
        throw new IllegalStateException(
            "a bean named '"
                + beanName
                + "' and the "
                + embedder.id()
                + " embedder would both be registered as '"
                + beanName
                + "'; rename the bean or unset the embedder's key");
      }
      if (!WireEmbedders.isPresent(embedder.wire(), beanFactory.getBeanClassLoader())) {
        log.info(
            "NESSY EMBEDDING: {} is configured but {} is not on the classpath; skipped",
            embedder.id(),
            WireEmbedders.artifactId(embedder.wire()));
        continue;
      }
      registry.registerBeanDefinition(beanName, providerDefinition(embedder));
      registered.add(embedder);
    }
    ResolvedEmbedders resolvedEmbedders = new ResolvedEmbedders(registered);
    registry.registerBeanDefinition(
        "nessyResolvedEmbedders",
        new RootBeanDefinition(ResolvedEmbedders.class, () -> resolvedEmbedders));
  }

  private RootBeanDefinition providerDefinition(ResolvedEmbedder embedder) {
    return new RootBeanDefinition(
        EmbeddingProvider.class,
        () -> {
          JsonMapper mapper = beanFactory.getBeanProvider(JsonMapper.class).getIfAvailable();
          return WireEmbedders.build(embedder, mapper, beanFactory.getBeanClassLoader())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "the " + embedder.id() + " embedder's adapter vanished"));
        });
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    // Nothing: every bean definition this class contributes is already registered by the time
    // this runs.
  }
}
```

- [ ] **Step 5: The report**

`EmbeddingReport.java` (license header, then):

```java
package org.jwcarman.nessy.spring.boot.embedding;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.jwcarman.nessy.embedding.EmbeddingProvider;
import org.jwcarman.nessy.spring.boot.NessyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Says, once, at startup, what will make the vectors: every registered {@link EmbeddingProvider} by
 * its registry id, and the factory's default -- a fact that exists only once the two properties are
 * read together.
 *
 * <p>A resolved preset or custom embedder prints its wire, endpoint, vendor and property names; an
 * application's own bean prints only what it can ask the bean for. The vendor printed is always the
 * bean's own {@link EmbeddingProvider#vendor()}, since the Gemini and Voyage wires ignore a vendor
 * override. Never the key, and never a property's value -- only its name. Nothing registered is
 * INFO, not WARN: an application without embeddings is not misconfigured, and a store that wanted
 * one says so itself.
 */
final class EmbeddingReport implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingReport.class);

  private final ObjectProvider<ResolvedEmbedders> resolvedEmbedders;
  private final ListableBeanFactory beans;
  private final NessyProperties properties;

  EmbeddingReport(
      ObjectProvider<ResolvedEmbedders> resolvedEmbedders,
      ListableBeanFactory beans,
      NessyProperties properties) {
    this.resolvedEmbedders = resolvedEmbedders;
    this.beans = beans;
    this.properties = properties;
  }

  @Override
  public void afterSingletonsInstantiated() {
    if (!log.isInfoEnabled()) {
      return;
    }
    Map<String, EmbeddingProvider> providers = beans.getBeansOfType(EmbeddingProvider.class);
    if (providers.isEmpty()) {
      log.info("NESSY EMBEDDING: no embedder is configured; stores rank by recency");
      return;
    }
    Map<String, ResolvedEmbedder> byBean = resolvedByBeanName();
    Map<String, String> lines = new TreeMap<>();
    providers.forEach(
        (beanName, provider) -> {
          ResolvedEmbedder resolved = byBean.get(beanName);
          String id = resolved != null ? resolved.id() : beanName;
          lines.put(id, describe(id, resolved, provider));
        });
    log.info("NESSY EMBEDDING: embedders: {}", String.join("; ", lines.values()));
    if (properties.embedder() == null) {
      log.info("NESSY EMBEDDING: no default embedder; every store names its own");
    } else {
      log.info(
          "NESSY EMBEDDING: default: {} / {}{}",
          properties.embedder(),
          properties.embeddingModel(),
          properties.embeddingDimension() == null
              ? ""
              : ", " + properties.embeddingDimension() + " wide");
    }
  }

  private Map<String, ResolvedEmbedder> resolvedByBeanName() {
    List<ResolvedEmbedder> resolved =
        resolvedEmbedders.getIfAvailable(() -> new ResolvedEmbedders(List.of())).embedders();
    return resolved.stream().collect(Collectors.toMap(ResolvedEmbedder::beanName, r -> r));
  }

  private static String describe(
      String id, @Nullable ResolvedEmbedder resolved, EmbeddingProvider provider) {
    if (resolved == null) {
      return id + " (vendor " + provider.vendor() + ")";
    }
    String endpoint = resolved.baseUrl() != null ? resolved.baseUrl() : "the vendor's own endpoint";
    return id
        + " ("
        + resolved.wire().propertyValue()
        + ", "
        + endpoint
        + ", vendor "
        + provider.vendor()
        + (resolved.properties().isEmpty()
            ? ""
            : ", properties " + new TreeSet<>(resolved.properties().keySet()))
        + ")";
  }
}
```

- [ ] **Step 6: The auto-configuration**

`EmbeddingProvidersAutoConfiguration.java` (license header, then):

```java
package org.jwcarman.nessy.spring.boot.embedding;

import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.embedding.EmbedderFactory;
import org.jwcarman.nessy.embedding.EmbeddingOptions;
import org.jwcarman.nessy.embedding.EmbeddingProvider;
import org.jwcarman.nessy.engine.embedding.DefaultEmbedderFactory;
import org.jwcarman.nessy.spring.boot.NessyProperties;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers every {@code nessy.embedders.<id>} preset (lit by a vendor key, when that vendor's
 * embedding adapter is on the classpath) and every custom embedder as an {@code EmbeddingProvider}
 * bean named {@code <id>Embeddings}, and contributes the one {@link EmbedderFactory} over them.
 *
 * <p>Every application-declared {@code EmbeddingProvider} bean joins the same registry beside the
 * presets, under its bean name. The factory's default is {@code nessy.embedder} and {@code
 * nessy.embedding-model}, set together or not at all, with {@code nessy.embedding-dimension}
 * optional beside them.
 */
@AutoConfiguration
@EnableConfigurationProperties(NessyProperties.class)
public class EmbeddingProvidersAutoConfiguration {

  @Bean
  static EmbedderRegistrar nessyEmbedderRegistrar() {
    return new EmbedderRegistrar();
  }

  @Bean
  // Against the INTERFACE, for the reason DirectHarnessAutoConfiguration gives: an application
  // declaring its own factory declares it as EmbedderFactory. Present whether or not anything is
  // registered -- @ConditionalOnBean cannot see definitions the registrar added -- and a factory
  // with nothing registered says so when a store asks it for an embedder.
  @ConditionalOnMissingBean(EmbedderFactory.class)
  public DefaultEmbedderFactory nessyEmbedderFactory(
      ObjectProvider<ResolvedEmbedders> resolvedEmbedders,
      ListableBeanFactory beans,
      NessyProperties properties,
      ObjectProvider<ObservationRegistry> observations) {
    requireDefaults(properties);
    Map<String, EmbeddingProvider> providers = beans.getBeansOfType(EmbeddingProvider.class);
    List<ResolvedEmbedder> resolved =
        resolvedEmbedders.getIfAvailable(() -> new ResolvedEmbedders(List.of())).embedders();
    Set<String> resolvedBeans =
        resolved.stream().map(ResolvedEmbedder::beanName).collect(Collectors.toSet());
    DefaultEmbedderFactory factory =
        DefaultEmbedderFactory.of(
            config -> {
              // Presets and custom embedders under their ids; everything else under its bean name.
              resolved.forEach(
                  embedder ->
                      config.provider(
                          ProviderId.of(embedder.id()), providers.get(embedder.beanName())));
              providers.forEach(
                  (name, provider) -> {
                    if (!resolvedBeans.contains(name)) {
                      config.provider(providerId(name), provider);
                    }
                  });
              config.observations(observations.getIfAvailable(() -> ObservationRegistry.NOOP));
              if (properties.embedder() != null) {
                OptionalInt width =
                    properties.embeddingDimension() == null
                        ? OptionalInt.empty()
                        : OptionalInt.of(properties.embeddingDimension());
                config.embedding(
                    ProviderId.of(properties.embedder()),
                    new EmbeddingOptions(properties.embeddingModel(), width));
              }
            });
    if (properties.embedder() != null) {
      // Once, here: a mistyped default fails at startup listing what is registered, rather than at
      // the first store that asks. The embedder is thrown away; nothing is called.
      factory.create(config -> {});
    }
    return factory;
  }

  /** Says what will make the vectors, before a single store asks. */
  @Bean
  @ConditionalOnMissingBean
  public EmbeddingReport nessyEmbeddingReport(
      ObjectProvider<ResolvedEmbedders> resolvedEmbedders,
      ListableBeanFactory beans,
      NessyProperties properties) {
    return new EmbeddingReport(resolvedEmbedders, beans, properties);
  }

  /**
   * A bean's Spring name, as a {@link ProviderId} -- so a bean whose name breaks the id rule fails
   * naming the bean, not with {@link ProviderId}'s own message alone.
   */
  private static ProviderId providerId(String beanName) {
    try {
      return ProviderId.of(beanName);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "the EmbeddingProvider bean '"
              + beanName
              + "' cannot be a provider id: "
              + e.getMessage(),
          e);
    }
  }

  /**
   * {@code nessy.embedder} and {@code nessy.embedding-model} are a pair: set both, or neither. The
   * width needs the pair. Blank is unset ({@link NessyProperties} has already said so).
   */
  private static void requireDefaults(NessyProperties properties) {
    String embedder = properties.embedder();
    String model = properties.embeddingModel();
    Integer dimension = properties.embeddingDimension();
    if ((embedder == null) != (model == null)) {
      throw new IllegalStateException(
          "nessy.embedder and nessy.embedding-model are a pair: set both, or neither and name them"
              + " on each store (nessy.embedder="
              + embedder
              + ", nessy.embedding-model="
              + model
              + ")");
    }
    if (dimension != null && embedder == null) {
      throw new IllegalStateException(
          "nessy.embedding-dimension is the default embedder's width: set it with nessy.embedder"
              + " and nessy.embedding-model");
    }
    if (dimension != null && dimension <= 0) {
      throw new IllegalStateException("nessy.embedding-dimension must be positive: " + dimension);
    }
  }
}
```

- [ ] **Step 7: Swap the auto-configurations**

`AutoConfiguration.imports`: replace the three lines

```
org.jwcarman.nessy.spring.boot.embedding.VoyageEmbeddingAutoConfiguration
org.jwcarman.nessy.spring.boot.embedding.OpenAiEmbeddingAutoConfiguration
org.jwcarman.nessy.spring.boot.embedding.GeminiEmbeddingAutoConfiguration
```

with the one line

```
org.jwcarman.nessy.spring.boot.embedding.EmbeddingProvidersAutoConfiguration
```

Then delete the old classes and their test:

```bash
cd /Users/jcarman/IdeaProjects/nessy-embedders
D=nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/embedding
git rm -q "$D/OpenAiEmbeddingAutoConfiguration.java" "$D/GeminiEmbeddingAutoConfiguration.java" \
  "$D/VoyageEmbeddingAutoConfiguration.java" "$D/ConditionalOnConfiguredProperty.java" \
  "$D/OnConfiguredProperty.java" "$D/OnNoOpenAiBaseUrl.java" "$D/EmbeddingModels.java" \
  nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/embedding/EmbeddingAutoConfigurationTest.java
git grep -n "nessy\.embedding\.\|EmbeddingModels\|OnNoOpenAiBaseUrl\|ConditionalOnConfiguredProperty\|VoyageEmbeddingAutoConfiguration\|OpenAiEmbeddingAutoConfiguration\|GeminiEmbeddingAutoConfiguration" -- '*.java' '*.imports' '*.yml' '*.yaml'
```

Expected from the last command: only `nessy-examples/chat-web` hits (`application.yml`, `EpisodesWiringTest.java`), which Step 9 removes.

- [ ] **Step 8: Run the Boot tests to see them pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test; echo exit=$?`
Expected: `exit=0`.

- [ ] **Step 9: chat-web says what it means, and the store's javadoc says where the provider is named**

`ChatConfiguration.java` -- replace the `episodes` bean and its javadoc (lines 110-121) with, and delete the `ObjectProvider` import if nothing else uses it (lines 136 and 165 still take `ObjectProvider<ObservationRegistry>`, so it stays):

```java
  /**
   * The store mints its own embedder, because the model the vectors were written with is a fact of
   * the store rather than of the application. This one takes the factory's default --
   * {@code nessy.embedder} and {@code nessy.embedding-model} -- when the application names one, and
   * ranks by recency when it does not.
   */
  @Bean
  public JdbcEpisodes episodes(
      DataSource dataSource, EmbedderFactory embedders, NessyProperties properties) {
    Embedder embedder = properties.embedder() == null ? null : embedders.create(c -> {});
    return JdbcEpisodes.of(c -> c.dataSource(dataSource).agentType(TYPE).embedder(embedder));
  }
```

`application.yml` -- replace lines 69-84 (the `embedding:` block under `nessy`, the comment above it, and the whole top-level `openai:` block with its comment) with, indented under `nessy:`:

```yaml
  # Optional: rank episodes by relevance to the turn being answered rather than by recency. Name
  # the local embedder below and a model its endpoint serves -- LM Studio serves e.g.
  # text-embedding-nomic-embed-text-v1.5 -- and every summary is embedded with it. Left unset,
  # both are blank, the application names no default embedder, and the most recent episodes are
  # shown instead.
  embedder: ${CHAT_EMBEDDER:}
  embedding-model: ${CHAT_EMBEDDING_MODEL:}
  embedders:
    # LM Studio's embeddings, as a custom embedder: a local server states its URL, its key and its
    # model in the open. It may answer any model name with whatever it has loaded, and the model a
    # store records is the one it asked for.
    local:
      wire: openai
      base-url: ${CHAT_MODEL_URL:http://localhost:1234/v1}
      api-key: not-needed
      vendor: lmstudio
```

`EpisodesWiringTest.java` -- replace the class javadoc, the `@SpringBootTest` properties and the test with:

```java
/**
 * Naming a default embedder makes the store rank by relevance: chat-web's own custom embedder,
 * {@code local}, at the chat model's endpoint, with the pair naming it. The store is built either
 * way.
 */
@SpringBootTest(
    properties = {
      "nessy.embedder=local",
      "nessy.embedding-model=text-embedding-nomic-embed-text-v1.5",
      "nessy.provider=scriptedModels"
    })
```

```java
  @Test
  void the_store_ranks_with_the_configured_embedding_model() {
    assertThat(embedders.create(c -> {}).model()).isEqualTo("text-embedding-nomic-embed-text-v1.5");
    // The store minted its own from the same factory, so it is keyed on that same model.
    assertThat(episodes.embedder())
        .map(Embedder::model)
        .contains("text-embedding-nomic-embed-text-v1.5");
    assertThat(episodes.embedder()).map(Embedder::vendor).contains("lmstudio");
  }
```

chat-web `README.md` -- replace lines 47-67 (from "The conversation is kept as episodes" through the OpenAI recipe's closing fence) with:

````markdown
The conversation is kept as episodes: the model calls `begin_episode` when
the subject changes, each closed episode is summarised in the background, and
the summaries that bear on the current turn are shown above the recent turns.
Name the `local` embedder and a model it serves, and "bear on" is measured by
embedding; leave them out and the most recent episodes are shown instead:

```bash
CHAT_EMBEDDER=local \
CHAT_EMBEDDING_MODEL=text-embedding-nomic-embed-text-v1.5 \
  ./mvnw -q -pl :nessy-example-chat-web -am spring-boot:run
```

`local` is a custom embedder in `application.yml`, at the same endpoint as the
chat model. A local server may answer any model name with whatever it has
loaded, and the model a store records is the one it asked for, so name the
model the server actually serves.

OpenAI itself works too: set its key, which lights the starter's `openai`
provider, and name that provider instead of the `lmstudio` default:

```bash
OPENAI_API_KEY=sk-… \
CHAT_PROVIDER=openai \
CHAT_MODEL_ID=gpt-4o-mini \
  ./mvnw -q -pl :nessy-example-chat-web -am spring-boot:run
```
````

`JdbcEpisodes.java` -- in `Config.embedder`'s javadoc (lines 130-134), replace "The embedding model belongs to the store: change it and the summaries embedded by the old one rank last until they are embedded again." with "The embedding model belongs to the store, and the provider it is minted from is named where the embedder is minted: change either and the summaries embedded by the old one rank last until they are embedded again."

- [ ] **Step 10: Full gate and commit**

Run: `git grep -n "nessy\.embedding\.\|openai\.base-url" -- nessy-examples` -- expected: nothing. Then `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?`
Expected: `exit=0` (the chat-web tests start PostgreSQL; Docker must be running).

```bash
cd /Users/jcarman/IdeaProjects/nessy-embedders
git add -A nessy-spring-boot/autoconfigure/src nessy-memory/episodic/src/main nessy-examples/chat-web
git commit -m "feat: nessy.embedders.<id> -- named embedders under Boot, a default pair, and a report

EmbeddingProvidersAutoConfiguration replaces the three vendor
auto-configurations: a vendor key lights an EmbeddingProvider bean named
<id>Embeddings only when its adapter jar is present, custom embedders state
wire, base-url and api-key, application EmbeddingProvider beans join under
their bean names, and one EmbedderFactory is always present.
nessy.embedder + nessy.embedding-model are the default, both or neither,
checked at startup. EmbeddingReport lists what lit. nessy.embedding.* is
gone. chat-web names its LM Studio embedder as a custom one.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 6: Live tests -- the four vendors on the new shape, and a custom embedder at LM Studio

Spec §11d, §12 step 4; Plan ruling 14; Open question 2. Every test here is `@Tag("live")` and skips cleanly with no key (the four adapter classes already carry the tag and an `assumeTrue` on their key): `clean verify` never runs them. **The controller does not run them**; James runs them with his keys after review, and the results go into the ledger. No catalogue row changes on their pass (§6c). Suggested implementer: Sonnet. Review: Sonnet.

**Files:**
- Test: `nessy-embedding/openai/src/test/java/org/jwcarman/nessy/embedding/openai/OpenAiEmbedderLiveTest.java`
- Test: `nessy-embedding/gemini/src/test/java/org/jwcarman/nessy/embedding/gemini/GeminiEmbedderLiveTest.java`
- Test: `nessy-embedding/bedrock/src/test/java/org/jwcarman/nessy/embedding/bedrock/BedrockEmbedderLiveTest.java`
- Test: `nessy-embedding/voyage/src/test/java/org/jwcarman/nessy/embedding/voyage/VoyageEmbedderLiveTest.java`
- Test (create): `nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/embedding/LocalEmbedderLiveTest.java`

**Interfaces:**
- Consumes: each live test's `embedderOver(...)` helper (Task 1), the configs' `fromEnv` (Task 2), `EmbedderConfig.property` (VP Task 2), the starter (Task 5).
- Produces: tests only.

- [ ] **Step 1: A query and a document, in each adapter's live test**

`OpenAiEmbedderLiveTest.java` -- add:

```java
  @Test
  void a_query_and_a_document_are_the_model_s_width_and_carry_the_model_asked_for() {
    assumeTrue(System.getenv("OPENAI_API_KEY") != null, "OPENAI_API_KEY is not set");
    try (OpenAiEmbeddingProvider provider =
        OpenAiEmbeddingProvider.of(OpenAiEmbedderConfig::fromEnv)) {
      Embedder embedder = embedderOver(provider, MODEL);

      Embedding document =
          embedder.embedDocument("The Loch Ness monster is said to live in a Scottish lake.");
      Embedding query = embedder.embedQuery("Where does Nessie live?");

      assertThat(document.dimension()).isPositive().isEqualTo(embedder.dimension());
      assertThat(query.dimension()).isEqualTo(document.dimension());
      assertThat(document.model()).isEqualTo(MODEL);
      assertThat(query.model()).isEqualTo(MODEL);
    }
  }
```

`GeminiEmbedderLiveTest.java` -- add the same test with the key check `System.getenv("GEMINI_API_KEY") != null || System.getenv("GOOGLE_API_KEY") != null` (message `"GEMINI_API_KEY is not set"`), the provider `GeminiEmbeddingProvider.of(GeminiEmbedderConfig::fromEnv)`, the embedder `embedderOver(provider, MODEL, 768)`, and one more assertion, `assertThat(document.dimension()).isEqualTo(768);`. Then add the measurement for Open question 2:

```java
  /**
   * Whether a pass-through reaches the call: the SDK sends a list through batchEmbedContents, and
   * extraBody merges into that call's top-level body. Records the answer rather than assuming it:
   * a failure naming {@code title} means per-request fields do not land where a user would expect.
   */
  @Test
  void a_pass_through_property_is_accepted_by_the_batch_call() {
    assumeTrue(
        System.getenv("GEMINI_API_KEY") != null || System.getenv("GOOGLE_API_KEY") != null,
        "GEMINI_API_KEY is not set");
    try (GeminiEmbeddingProvider provider =
        GeminiEmbeddingProvider.of(GeminiEmbedderConfig::fromEnv)) {
      Embedder embedder =
          DefaultEmbedderFactory.of(
                  f ->
                      f.provider(GEMINI, provider)
                          .embedding(GEMINI, new EmbeddingOptions(MODEL, OptionalInt.of(768))))
              .create(c -> c.property("gemini.title", "Loch Ness"));

      Embedding document = embedder.embedDocument("The Loch Ness monster lives in a lake.");

      assertThat(document.dimension()).isEqualTo(768);
    }
  }
```

`BedrockEmbedderLiveTest.java` -- add the same query/document test with its existing key check (`AWS_BEARER_TOKEN_BEDROCK` or `AWS_ACCESS_KEY_ID`, same message), `String model = System.getenv().getOrDefault("NESSY_EMBEDDING_MODEL", BedrockEmbedderConfig.DEFAULT_MODEL);`, the provider `BedrockEmbeddingProvider.of(BedrockEmbedderConfig::fromEnv)`, `embedderOver(provider, model)`, and `model` in place of `MODEL` in the two model assertions.

`VoyageEmbedderLiveTest.java` -- the same, with `assumeTrue(System.getenv("VOYAGE_API_KEY") != null, "VOYAGE_API_KEY is not set")`, `String model = System.getenv().getOrDefault("NESSY_EMBEDDING_MODEL", VoyageEmbedderConfig.DEFAULT_MODEL);`, `VoyageEmbeddingProvider.of(VoyageEmbedderConfig::fromEnv)`, `embedderOver(provider, model)`.

Written out for Voyage (Bedrock differs only in its key check, its provider class and config class):

```java
  @Test
  void a_query_and_a_document_are_the_model_s_width_and_carry_the_model_asked_for() {
    assumeTrue(System.getenv("VOYAGE_API_KEY") != null, "VOYAGE_API_KEY is not set");
    String model =
        System.getenv().getOrDefault("NESSY_EMBEDDING_MODEL", VoyageEmbedderConfig.DEFAULT_MODEL);
    try (VoyageEmbeddingProvider provider =
        VoyageEmbeddingProvider.of(VoyageEmbedderConfig::fromEnv)) {
      Embedder embedder = embedderOver(provider, model);

      Embedding document =
          embedder.embedDocument("The Loch Ness monster is said to live in a Scottish lake.");
      Embedding query = embedder.embedQuery("Where does Nessie live?");

      assertThat(document.dimension()).isPositive().isEqualTo(embedder.dimension());
      assertThat(query.dimension()).isEqualTo(document.dimension());
      assertThat(document.model()).isEqualTo(model);
      assertThat(query.model()).isEqualTo(model);
    }
  }
```

(Each live class already imports `Embedding`, `Embedder` and `assumeTrue`; Gemini's needs `org.jwcarman.nessy.api.ProviderId`, `org.jwcarman.nessy.embedding.EmbeddingOptions` and `java.util.OptionalInt`, which Task 1 added.)

- [ ] **Step 2: `LocalEmbedderLiveTest`**

`LocalEmbedderLiveTest.java` (license header, then):

```java
package org.jwcarman.nessy.spring.boot.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.EmbedderFactory;
import org.jwcarman.nessy.api.embedding.Embedding;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * A custom embedder, {@code local}, on the {@code openai} wire at LM Studio running on this machine
 * with {@code text-embedding-nomic-embed-text-v1.5} loaded, through the whole starter. It proves
 * the custom-embedder route the providers guide documents for local servers, and the width check
 * against a server measured to ignore the width it is asked for. It promotes nothing to a preset.
 *
 * <pre>{@code
 * ./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test -Dnessy.excludedGroups= -Dtest=LocalEmbedderLiveTest -Dsurefire.failIfNoSpecifiedTests=false
 * }</pre>
 */
@Tag("live")
@DisplayName("A custom embedder at LM Studio, through the starter")
class LocalEmbedderLiveTest {

  private static final String MODEL = "text-embedding-nomic-embed-text-v1.5";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(EmbeddingProvidersAutoConfiguration.class))
          .withPropertyValues(
              "nessy.embedders.local.wire=openai",
              "nessy.embedders.local.base-url=http://localhost:1234/v1",
              "nessy.embedders.local.api-key=lm-studio",
              "nessy.embedders.local.vendor=lmstudio",
              "nessy.embedder=local",
              "nessy.embedding-model=" + MODEL);

  @Test
  void the_default_embedder_learns_the_model_s_width_and_records_the_model_it_asked_for() {
    runner.run(
        context -> {
          Embedder embedder = context.getBean(EmbedderFactory.class).create(c -> {});
          assertThat(embedder.dimension()).isZero();

          Embedding document =
              embedder.embedDocument("The Loch Ness monster is said to live in a Scottish lake.");
          Embedding query = embedder.embedQuery("Where does Nessie live?");

          assertThat(document.dimension()).isEqualTo(768);
          assertThat(query.dimension()).isEqualTo(768);
          assertThat(embedder.dimension()).isEqualTo(768);
          assertThat(document.model()).isEqualTo(MODEL);
          assertThat(embedder.vendor()).isEqualTo("lmstudio");
        });
  }

  /** §5f measured on the wire: LM Studio answers 768 wide whatever width it is asked for. */
  @Test
  void a_width_the_server_ignores_fails_naming_both() {
    runner.run(
        context -> {
          Embedder narrow = context.getBean(EmbedderFactory.class).create(c -> c.dimension(256));

          assertThatThrownBy(() -> narrow.embedDocument("a lake monster"))
              .isInstanceOf(IllegalStateException.class)
              .hasMessage("asked for 256 coordinates, the model returned 768");
        });
  }
}
```

- [ ] **Step 3: Compile, gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?`
Expected: `exit=0` (every test added here is excluded by the `live` tag; this proves they compile).

```bash
cd /Users/jcarman/IdeaProjects/nessy-embedders
git add nessy-embedding nessy-spring-boot/autoconfigure/src/test
git commit -m "test: live embedding measurements on the named-embedder shape

Each vendor's live test gains a query and a document of the model's width
carrying the model asked for; Gemini's measures whether a pass-through
reaches the batch call. LocalEmbedderLiveTest drives a custom embedder at LM
Studio through the starter, including the width check against a server that
ignores the width asked for.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

**For James -- the live commands** (each skips cleanly when its key is absent; run from `/Users/jcarman/IdeaProjects/nessy-embedders`):

```bash
OPENAI_API_KEY=… ./mvnw -q -pl :nessy-embedding-openai -am test -Dnessy.excludedGroups= -Dtest=OpenAiEmbedderLiveTest -Dsurefire.failIfNoSpecifiedTests=false
GEMINI_API_KEY=… ./mvnw -q -pl :nessy-embedding-gemini -am test -Dnessy.excludedGroups= -Dtest=GeminiEmbedderLiveTest -Dsurefire.failIfNoSpecifiedTests=false
AWS_PROFILE=… AWS_REGION=us-east-1 ./mvnw -q -pl :nessy-embedding-bedrock -am test -Dnessy.excludedGroups= -Dtest=BedrockEmbedderLiveTest -Dsurefire.failIfNoSpecifiedTests=false
VOYAGE_API_KEY=… ./mvnw -q -pl :nessy-embedding-voyage -am test -Dnessy.excludedGroups= -Dtest=VoyageEmbedderLiveTest -Dsurefire.failIfNoSpecifiedTests=false
# LM Studio on :1234 with text-embedding-nomic-embed-text-v1.5 loaded:
./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test -Dnessy.excludedGroups= -Dtest=LocalEmbedderLiveTest -Dsurefire.failIfNoSpecifiedTests=false
```

(Bedrock's gate is `AWS_BEARER_TOKEN_BEDROCK` or `AWS_ACCESS_KEY_ID`; use a profile of James's own, never the `chirp` one.) The Gemini pass-through result answers Open question 2 and goes in the ledger either way.

---

### Task 7: Docs, examples' prose, the ROADMAP and the changelog

Spec §12 step 5 (ruled overnight: the stale documents are fixed here), §6h (the migration table), §5d (the deletions); Plan ruling 16. Describe what is -- no history, no roads not taken (`docs-describe-what-is`). Suggested implementer: `docs-writer` (Sonnet), reviewed by `task-reviewer` (Sonnet). The prose below is the content to land; the docs-writer may adjust wording to the guide's voice but not the facts, and must check every name, default, message and property against the code as Tasks 1-5 left it.

**Files:**
- Modify: `docs/guides/providers.md` (a new `## Embedders` section directly before `## Writing a provider`)
- Modify: `docs/guides/spring-boot.md` (the Properties table and the paragraph after it; the "Every bean backs off" tables)
- Modify: `docs/guides/observability.md` (lines 138-152)
- Modify: `docs/concepts/memory.md` (lines 157-158, 237-276)
- Modify: `README.md` (line 183), `docs/index.md` (line 142)
- Modify: `ROADMAP.md` (line 42; the "Vendor properties" entry, lines 150-157; the "Named embedding providers" entry, lines 158-169)
- Modify: `CHANGELOG.md` (`## [Unreleased]`)

**Interfaces:**
- Consumes: every name above; Task 6's recorded results only if James has run them (the Gemini pass-through answer changes one sentence, marked below).
- Produces: docs only.

- [ ] **Step 1: `providers.md` -- "Embedders"**

Insert, directly before `## Writing a provider`:

````markdown
## Embedders

Embeddings have a registry of their own, beside the inference one and with
the same mechanics: providers registered by name, presets lit by a key, custom
entries stated in full, application beans joining under their bean names, and
a report at startup. The namespace is `nessy.embedders.<id>`. An id may name
an inference provider and an embedder at once -- `openai`, lit by the one
`OPENAI_API_KEY` -- and the two are separate registrations.

A store names the embedder it wants, or takes the factory's default:

```java
Embedder embedder = embedders.create(c -> c.provider("voyage").model("voyage-3.5").dimension(1024));
Embedder theDefault = embedders.create(c -> {});
```

Both a provider and a model are required, from the store or from the default;
an unknown or missing provider fails when the embedder is made, listing what
is registered:

```
an embedder names provider 'cohere', which is not registered; registered: [openai, voyage]
```

### Presets

| id | wire | base URL | vendor | ingredient |
|---|---|---|---|---|
| `openai` | `openai` | the vendor's own; `openai.base-url` overrides | `openai` | `openai.api-key` (`OPENAI_API_KEY`) |
| `gemini` | `gemini` | the vendor's own | `gcp.gemini` | `gemini.api-key` or `google.api-key` (`GEMINI_API_KEY` / `GOOGLE_API_KEY`) |
| `voyage` | `voyage` | `https://api.voyageai.com/v1` | `voyage` | `voyage.api-key` (`VOYAGE_API_KEY`) |

A key lights a preset only when that vendor's embedding adapter is on the
classpath -- `nessy-embedding-openai`, `-gemini`, `-voyage`. Otherwise the
starter logs one line and registers nothing:

```
NESSY EMBEDDING: openai is configured but nessy-embedding-openai is not on the classpath; skipped
```

`nessy.embedders.<id>.api-key` works in place of the vendor's own variable;
from the environment it is `NESSY_EMBEDDERS_VOYAGE_APIKEY`.
`nessy.embedders.<id>.enabled: false` turns an embedder off whatever its
ingredient -- the way to say "a Gemini key is set for chat, and no Gemini
embeddings are wanted". No preset carries a default model: a store's vectors
are keyed on its model, so the model is always written down.

Bedrock ships no preset, for the reason it has none on the inference side; a
`BedrockEmbeddingProvider` bean joins the registry like any application bean.

### Custom embedders, and local servers

An id that is not a preset must state `wire` (`openai`, `gemini` or `voyage`),
`base-url` and `api-key`; `vendor` is optional, defaults to the wire's own,
and is honoured on the `openai` wire only. A local server is a custom
embedder -- no local preset ships:

```yaml
nessy:
  embedder: local
  embedding-model: text-embedding-nomic-embed-text-v1.5
  embedders:
    local:
      wire: openai
      base-url: http://localhost:1234/v1
      api-key: lm-studio
      vendor: lmstudio
```

What a local OpenAI-compatible server does, as measured against LM Studio:

- it answers `/v1/embeddings` in OpenAI's shape;
- it reports zero usage;
- it ignores `dimensions` and answers at the model's own width -- an embedder
  that asked for a width fails, naming both numbers
  (`asked for 256 coordinates, the model returned 768`);
- **it may answer any model name with whatever it has loaded.** The model a
  store records is the one it asked for, so name the model the server
  actually serves. The client cannot detect a mismatch.

Two providers can serve one model name; a row records the model, not the
provider, so vectors from the two are comparable only if both really run that
model.

### Application beans

An application's own `EmbeddingProvider` bean joins under its **bean name**.
A preset's or custom embedder's bean is named `<id>Embeddings`
(`openaiEmbeddings`), because the inference provider of the same id is
already the bean `openai`; its registry id is the id. An application bean
named `openaiEmbeddings` beside a lit `openai` preset fails startup, naming
both; one named like a lit preset's id (`voyage`) fails because two providers
would register under one id.

An application `EmbedderFactory` bean replaces the starter's factory; the
presets are still registered as `EmbeddingProvider` beans for it to use or
ignore.

### The default, and the report

`nessy.embedder` and `nessy.embedding-model` name the default a store gets
when it says nothing; set both or neither. `nessy.embedding-dimension` is its
width, optional, and only beside the pair. A default naming an unregistered
embedder fails at startup, listing what is registered. With no default, a
store names its own.

At startup the report lists every embedder -- id, wire, endpoint, vendor,
property names, never the key or a value -- and the default:

```
NESSY EMBEDDING: embedders: openai (openai, the vendor's own endpoint, vendor openai); voyage (voyage, https://api.voyageai.com/v1, vendor voyage, properties [voyage.truncation])
NESSY EMBEDDING: default: voyage / voyage-3.5, 1024 wide
```

With nothing registered it says `NESSY EMBEDDING: no embedder is configured;
stores rank by recency`; with no default, `NESSY EMBEDDING: no default
embedder; every store names its own`. Both are INFO.

### Vendor properties for embedders

`nessy.embedders.<id>.properties.<name>` and `EmbedderConfig.property(name,
value)` carry vendor properties, prefixed `openai.`, `gemini.`, `bedrock.` or
`voyage.`; an embedder's entries override its provider's by name. No
embedding adapter parses a name into a typed field: every entry under the
adapter's prefix passes through into the request body, and an entry under
another prefix is ignored. A name at or under a field the adapter writes
fails when the provider or the embedder is built:

| adapter | refused (and anything beneath them) |
|---|---|
| OpenAI | `model`, `input`, `dimensions` |
| Gemini | `model`, `contents`, `outputDimensionality`, `taskType` |
| Bedrock | `inputText`, `texts`, `dimensions`, `normalize`, `input_type`, `truncate` |
| Voyage | `model`, `input`, `output_dimension`, `input_type` |

Gemini's pass-through is sent as the request's extra body, at the top level
of the batch call.
````

(If Task 6's Gemini measurement showed a pass-through is rejected by the batch call, replace the last sentence with one that says so, in the present tense: "A Gemini pass-through lands at the top level of the batch call, which rejects per-request fields such as `title`.")

- [ ] **Step 2: `spring-boot.md`**

In the Properties table, after the `nessy.providers.<id>...` row, add:

```markdown
| `nessy.embedder` | none; paired with `nessy.embedding-model` | the `EmbedderFactory` bean, as the default embedding provider a store falls back on when it names none; blank is unset |
| `nessy.embedding-model` | none; paired with `nessy.embedder` | the same factory default: the model a store falls back on |
| `nessy.embedding-dimension` | none; only beside the pair | the same factory default's width |
| `nessy.embedders.<id>.api-key`, `.enabled`, `.wire`, `.base-url`, `.vendor`, `.properties.*` | none | turns an embedding preset on or declares a custom embedder, registered as an `EmbeddingProvider` bean named `<id>Embeddings`; see [Providers](providers.md#embedders) |
```

and add `voyage.api-key` to the last row's list of vendor keys, with "light the matching inference or embedding preset" in its "Read by" cell. After the `nessy.provider` / `nessy.model` pair paragraph add:

```markdown
`nessy.embedder` and `nessy.embedding-model` are the same kind of pair, for
embeddings: set both, or neither and name a provider and a model on every
store. The starter refuses to start if only one is set, and refuses a pair
naming an embedder that is not registered, listing the ones that are.
```

In "Every bean backs off", after the "Always present" table, add:

```markdown
**Embeddings** (`EmbeddingProvidersAutoConfiguration`, unconditional):

| Bean | What it is |
|---|---|
| `EmbedderFactory` | every registered embedding provider by id, the `nessy.embedder` default, every embedder observed; present with nothing registered, and says so when a store asks |
| `EmbeddingReport` | logs every registered embedder and the default once, at startup |
```

- [ ] **Step 3: `observability.md`**

Replace lines 138-152 (from "**Embedding calls** are spans once an embedder is wrapped:" through "...embedder they mint this way whenever a registry is present.") with:

````markdown
**Embedding calls** are spans. `DefaultEmbedderFactory` wraps every embedder
it mints in `ObservedEmbedder`, over the registry it was given:

```java
EmbedderFactory embedders = DefaultEmbedderFactory.of(f -> f
        .provider(ProviderId.of("openai"), OpenAiEmbeddingProvider.fromEnv())
        .embedding(ProviderId.of("openai"), EmbeddingOptions.of("text-embedding-3-small"))
        .observations(observationRegistry));
```

Without `observations(...)` the registry is the no-op one. The Boot
starter's factory is given the application's registry. An embedder made
another way is wrapped with `ObservedEmbedder.wrap(embedder, registry)`; an
already-wrapped embedder is returned as it is, so wrapping twice never
doubles the spans.
````

and leave the sentence that follows ("Each call is `embeddings <model>` with ...") as it is.

- [ ] **Step 4: `memory.md`**

Replace the two lines of the example at lines 157-158:

```java
EmbeddingProvider connection = OpenAiEmbeddingProvider.fromEnv();
Embedder embedder = new DefaultEmbedderFactory(connection).create(c -> c.dimension(512));
```

with:

```java
EmbedderFactory embedders = DefaultEmbedderFactory.of(f -> f
        .provider(ProviderId.of("openai"), OpenAiEmbeddingProvider.fromEnv())
        .embedding(ProviderId.of("openai"), EmbeddingOptions.of("text-embedding-3-small")));
Embedder embedder = embedders.create(c -> c.dimension(512));
```

In "## Embeddings": line 239's "`nessy-embedding-api` is the seam for that" becomes "`Embedder` (in `nessy-api`) and `EmbeddingProvider` (in `nessy-embedding-spi`) are the seam for that". Replace the listing at lines 244-251 with the interface as it is:

```java
public interface Embedder {
  String vendor();
  String model();
  int dimension();
  List<Embedding> embedDocuments(List<String> texts);
  Embedding embedDocument(String text);
  Embedding embedQuery(String query);
}
```

Replace "Four embedders ship, each one model at one dimension decided where it is built, because a store's index is sized by it:" with "Four embedding providers ship. Each holds a connection and nothing about a model; the model and its width are the store's, named when its embedder is made, because a store's index is sized by them:"; rename the table's last column from "Default model" to "`DEFAULT_MODEL`, to cite"; and replace the example and paragraph at lines 267-276 (from the ```` ```java ```` fence holding `new DefaultEmbedderFactory(connection, "text-embedding-3-small")` through "...when an embedding module and its API key are on the classpath.") with:

````markdown
```java
EmbedderFactory embedders = DefaultEmbedderFactory.of(f -> f
        .provider(ProviderId.of("openai"), OpenAiEmbeddingProvider.fromEnv())
        .provider(ProviderId.of("local"), OpenAiEmbeddingProvider.of(c -> c
                .apiKey("lm-studio").baseUrl("http://localhost:1234/v1").vendor("lmstudio")))
        .embedding(ProviderId.of("openai"), EmbeddingOptions.of("text-embedding-3-small")));

Embedder forEpisodes = embedders.create(c -> {});
Embedder forNotes = embedders.create(c -> c.provider("local").model("text-embedding-nomic-embed-text-v1.5"));
```

Each provider is a vendor connection, registered once under a name;
`EmbedderFactory` mints as many `Embedder`s over them as there are stores,
each naming its provider, model and width or taking the factory's default.
An embedder whose replies are not the width it asked for fails, naming both.
In a Boot application the starter contributes the `EmbedderFactory` bean,
with embedders registered from `nessy.embedders.<id>` and the default from
`nessy.embedder` and `nessy.embedding-model`; see
[Providers](../guides/providers.md#embedders).

Changing a store's embedder is a ranking consequence, not a loss: the next
summary is embedded by the new model and ranks; every earlier summary ranks
last until it is embedded again.
````

- [ ] **Step 5: README, index, ROADMAP**

`README.md` line 183 and `docs/index.md` line 142: `nessy-embedding-api` becomes `nessy-embedding-spi` (the row's other text stays).

`ROADMAP.md`:
- line 42: "The `Embedder` seam shipped 2026-09-16 as `nessy-embedding-api`," becomes "The `Embedder` seam shipped 2026-09-16 (`nessy-api`, with `nessy-embedding-spi` for providers),".
- the "Vendor properties" entry (lines 150-157): its status "*(next, after the Responses adapter)*" becomes "*(built for `0.3.0`)*", and its last sentence before the spec link gains: "The embedding adapters read theirs too, prefixed the same way, all as pass-through."
- the "Named embedding providers" entry (lines 158-169) becomes:

```markdown
- **Named embedders** *(built for `0.3.0`)* — the inference design mirrored
  for embeddings under its own namespace, `nessy.embedders.<id>`: presets for
  `openai`, `gemini` and `voyage` lit by a key when the adapter jar is
  present, custom embedders for local servers and gateways, application
  `EmbeddingProvider` beans joining under their bean names, a
  `nessy.embedder` + `nessy.embedding-model` default, and a startup report. A
  store chooses its embedder, never an agent type. See
  `docs/superpowers/specs/2026-09-30-named-embedders-design.md`.
```

- [ ] **Step 6: The changelog**

Under `## [Unreleased]`, add to `### Added` (vendor-properties created it):

```markdown
- **Named embedders.** `DefaultEmbedderFactory.of(...)` over
  `EmbedderFactoryConfig`: `provider(ProviderId, EmbeddingProvider)`
  registers an embedding provider by name, `embedding(ProviderId,
  EmbeddingOptions)` sets the default, `observations(...)` gives every
  embedder it mints its spans. `EmbedderConfig.provider(...)` names the
  provider a store wants; an unknown or missing one fails when the embedder
  is made, listing what is registered.
- **Spring Boot: `nessy.embedders.<id>`.** Presets `openai`, `gemini` and
  `voyage` light from a vendor key when the vendor's embedding module is on
  the classpath; custom embedders state `wire` (`openai`, `gemini`,
  `voyage`), `base-url` and `api-key`; application `EmbeddingProvider`
  beans join under their bean names; `nessy.embedder` +
  `nessy.embedding-model` (+ optional `nessy.embedding-dimension`) name the
  default; `EmbeddingReport` logs what lit. `VOYAGE_API_KEY` lights the
  `voyage` preset.
- **`OpenAiEmbedderConfig.vendor(String)`**, for a custom `openai`-wire
  embedder that is somebody else.
- **The embedding adapters read their vendor properties** (`openai.`,
  `gemini.`, `bedrock.`, `voyage.`), all as pass-through into the request
  body.
```

and to `### Breaking changes`:

```markdown
- **`DefaultEmbedderFactory`'s three constructors are gone**; build it with
  `DefaultEmbedderFactory.of(f -> f.provider(id, provider).embedding(id,
  EmbeddingOptions.of(model)))`. Every embedder it mints is now an
  `ObservedEmbedder`.
- **An embedding provider holds nothing about a model.** `model(...)` and
  `dimension(...)` are gone from `OpenAiEmbedderConfig`,
  `GeminiEmbedderConfig`, `BedrockEmbedderConfig` and `VoyageEmbedderConfig`,
  and `defaultModel()` / `defaultDimension()` from the four providers. The
  `DEFAULT_MODEL` constants stay, to cite: `EmbeddingOptions.of(
  OpenAiEmbedderConfig.DEFAULT_MODEL)`.
- **An embedder that asked for a width and got another fails**, naming both
  (`asked for 256 coordinates, the model returned 768`), on every call.
- **A Bedrock embedder over a model of neither family fails when it is
  made**, not at its first call.
- **`nessy.embedding.*` is replaced by `nessy.embedders.*`**, with no
  aliases:

  | old | new |
  |---|---|
  | `nessy.embedding.openai.model` | `nessy.embedder: openai` + `nessy.embedding-model: <model>` |
  | `nessy.embedding.openai.dimension` | `nessy.embedding-dimension: <n>` |
  | `openai.api-key` + `openai.base-url` + `nessy.embedding.openai.model` | a custom embedder (`nessy.embedders.local.wire: openai`, `.base-url`, `.api-key`, `.vendor`) plus the pair naming it; or the `openai` preset with `openai.base-url`, plus the pair |
  | `nessy.embedding.gemini.model` | `nessy.embedder: gemini` + `nessy.embedding-model: <model>` |
  | `nessy.embedding.gemini.dimension` | `nessy.embedding-dimension: <n>` |
  | `nessy.embedding.voyage.api-key` | `voyage.api-key` (`VOYAGE_API_KEY`), or `nessy.embedders.voyage.api-key` |
  | `nessy.embedding.voyage.model` | `nessy.embedder: voyage` + `nessy.embedding-model: <model>` |
  | `nessy.embedding.voyage.dimension` | `nessy.embedding-dimension: <n>` |
  | a key alone, with the vendor's default model | a key registers the provider; minting an embedder needs a model, from the store or the pair |

- **`VoyageEmbeddingAutoConfiguration`, `OpenAiEmbeddingAutoConfiguration`
  and `GeminiEmbeddingAutoConfiguration` are replaced by
  `EmbeddingProvidersAutoConfiguration`**; update any
  `spring.autoconfigure.exclude` naming them. Several keys set now register
  several embedders instead of the first in auto-configuration order.
- **The starter's `EmbedderFactory` bean is always present.** A store that
  decided "no embeddings" by the factory's absence now asks whether
  `nessy.embedder` is set. An application `EmbedderFactory` bean still
  replaces the starter's factory, and no longer switches the presets off.
```

- [ ] **Step 7: Check, gate and commit**

Run: `git grep -n "nessy-embedding-api\|nessy\.embedding\.\|new DefaultEmbedderFactory(" -- docs README.md ROADMAP.md ':!docs/superpowers'` -- expected: nothing. Then `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:check license:check && ./mvnw -q clean verify; echo exit=$?` -- expected `exit=0`.

```bash
cd /Users/jcarman/IdeaProjects/nessy-embedders
git add docs/guides/providers.md docs/guides/spring-boot.md docs/guides/observability.md \
  docs/concepts/memory.md docs/index.md README.md ROADMAP.md CHANGELOG.md
git commit -m "docs: named embedders

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

## Not in this plan

- **Re-embedding rows written by a previous embedder** -- ROADMAP (§8b).
- **Timeout setters on the OpenAI and Gemini embedder configs** (§10) -- a small follow-up.
- **The Open questions above** -- James's.
- **A Gemini per-request nesting of the pass-through**, if Task 6's measurement shows the batch call rejects it -- a follow-up commit.

## Self-review

- **Spec coverage.** §4a → Task 1 (`ProviderId` javadoc). §4b → nothing changes (model stays `String`). §4c → Task 4 (`EmbeddingWire`), Task 5 (`the_wire_value_openai_embeddings_does_not_exist`). §5a → Task 1. §5b/§5c → Task 1 (the three messages, both-required). §5d → Task 2. §5e → Task 1 (wrap in `create`), Task 5 (registry from the context). §5f → Task 1 (unit), Task 6 (on the wire). §5g → Task 5 (container owns preset beans; the factory closes nothing). §6a → Tasks 4-5. §6b → Task 4 (catalogue), Task 5 (skip line, `VOYAGE_API_KEY`, prefixed and env forms). §6c/§6d → Task 4 (three rows, none keyless), Task 5 (`lmstudio` row), Task 7 (the findings). §6e → Task 2 (`vendor`), Task 4 (custom rules). §6f → Tasks 4-5 (`<id>Embeddings`, application beans, both collisions). §6g → Task 5 (`EmbeddingReport`). §6h → Task 5 (deletions, blank-as-unset), Task 7 (the table). §6i → Task 5 (factory bean, pair, chat-web). §7a → Task 4 (overlay), Task 5 (binding rows). §7b → vendor-properties (the module exists). §7c → Task 3. §8a → Task 5 (`JdbcEpisodes` javadoc, chat-web). §8b → Task 7 (memory.md). §9 → Task 7 (memory.md example). §11a → Task 5 (every row; the blank-`enabled` row with the §15 (3) contingency). §11b → Task 1. §11c → Tasks 2-3. §11d → Task 6. §12 → task order, with ruling 9's reorder. §14 → every public name here is in its table.
- **Deviations, stated.** Plan rulings 1-17: the Responses names already present; no generified registry; `IllegalStateException` for every resolution failure; independent seeding; the trial `create` at startup; custom beans suffixed too; custom embedders need a key; no timeout; vendor properties before Boot; four package-private readers and clash roots; provider maps checked at build; blank pair halves normalised; report details; the live test ungated; the wire failure pinned by path; moved line numbers; chat-web's README recipe.
- **Type consistency.** `EmbedderFactoryConfig.provider(ProviderId, EmbeddingProvider)`, `embedding(ProviderId, EmbeddingOptions)`, `observations(ObservationRegistry)`; `DefaultEmbedderFactory.of(Customizer)`/`of(List)` (Tasks 1, 3-6). `EmbedderConfig.provider(ProviderId)`/`provider(String)` (Tasks 1, 3, 5, 6). `OpenAiEmbeddingProvider(OpenAIClient, boolean, String)` (Task 2) → `(OpenAIClient, boolean, String, Map)` (Task 3); `GeminiEmbeddingProvider(GeminiEmbeddingClient, String)` kept beside `(…, Map)`; `BedrockEmbeddingProvider(BedrockEmbeddingClient, String, JsonMapper)` kept beside `(…, Map)`. `XEmbeddingProperties.read(Map, Map, JsonMapper)` (Task 3). `EmbeddingWire`, `EmbedderPreset` (seven components), `EmbedderSettings` (six), `ResolvedEmbedder` (six, `beanName()`), `ResolvedEmbedders(embedders)`, `EmbedderCatalogue.resolve(Map, Function)`, `WireEmbedders.isPresent/artifactId/build` (Task 4 → Task 5). `NessyProperties` eleven components (Task 5). `EmbeddingReport(ObjectProvider<ResolvedEmbedders>, ListableBeanFactory, NessyProperties)` (Task 5).
- **Review Focus.** 1 → Task 3 (all four adapters). 2 → Task 5. 3 → Task 5 (two rows) and `NessyPropertiesTest`. 4 → Task 1. 5 → Task 3 (all four adapters).
