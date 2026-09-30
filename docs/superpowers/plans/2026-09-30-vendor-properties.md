# Vendor Properties Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every agent type carries a map of vendor-prefixed, string-valued properties (`InferenceConfig.property("openai.reasoning.effort", "high")`, `nessy.providers.<id>.properties.*` under Boot); each adapter reads only its own prefix, parses the names it knows into typed SDK fields, passes the rest through into the request body as JSON literals, and refuses at harness build any property that names what a typed setting already decides.

**Architecture:** A one-class module, `nessy-vendor-properties` (`org.jwcarman.nessy.vendor.VendorProperties`), holds the reading every adapter shares: filter by prefix, merge provider under agent type, JSON-literal values, dotted-path nesting, the clash check, typed reads. Both SPIs depend on it. `InferenceOptions` and `EmbeddingOptions` gain a `properties` component (fixed at build, carried on every request, never written to the event log); `InferenceProvider.validate(InferenceOptions)` and `EmbeddingProvider.validate(EmbeddingOptions)` are new no-op defaults the factories call at build. Each inference adapter gets a package-private `XProperties` class that owns its prefix, known names and clash table; each `XProviderConfig` gains `property`/`properties` and checks its own map at `build()`. Boot binds `nessy.providers.<id>.properties` as a `Map<String, String>`, overlays it on a preset's defaults (`openai.tools.strict=true` on `openai`), and hands it to the config.

**Tech Stack:** Java 25, Maven reactor, Jackson 3 (`tools.jackson`) for the literal reading, openai-java 4.69.2, anthropic-java 2.65.0, google-genai 1.73.0, bedrockruntime 2.55.5, Spring Boot 4.1.1 (`Binder`, `ApplicationContextRunner`, `YamlPropertySourceLoader`), JUnit 5 + AssertJ, SLF4J.

**Spec:** `docs/superpowers/specs/2026-09-30-vendor-properties-design.md` -- the design of record, APPROVED. Read it whole before any task; section numbers below (§n) refer to it. Do not re-open its decisions. Every public name this plan introduces is in the spec's §15 table; the package-private helpers (`OpenAiProperties`, `AnthropicProperties`, `GeminiProperties`, `BedrockProperties`) are mechanical internals under the repo's design-authority rule and need no yes.

**Sequencing (spec §14).** Before Task 1, the branch is rebased (see "Before Task 1"). Task 1 is step 1 (module, helper, SPIs). Task 2 is step 2 (API and engine). Tasks 3-8 are step 3, one per adapter family (OpenAI chat, OpenAI Responses, Anthropic, Gemini, Bedrock, the embedder configs). Task 9 is step 4 (Boot). Task 10 is step 5 (live tests; written here, run by James). Task 11 is step 6 (docs and changelog). Each task ends green under `./mvnw -q clean verify` with no key and no network.

## Before Task 1: rebase onto `responses-api`

This branch was cut from `responses-api` at `3d6ca3bf2`; `responses-api` has since gained two commits this plan depends on: `d3d4fe30d` (the `openai-responses` wire builds `OpenAiResponsesInferenceProvider` in `WireProviders`, with `WireProvidersTest`) and `c54fd0e49` (`OpenAiResponsesLiveTest`, and the `wire` column in `PresetCandidatesLiveTest`). The three commits on top of it here are docs-only (`docs/superpowers/specs/`), so the rebase cannot conflict. The controller runs, once, before dispatching Task 1:

```bash
cd /Users/jcarman/IdeaProjects/nessy-props
git status --short            # expected: nothing (the plan commit is the only change, and it is committed)
git rebase responses-api
git log --oneline -6          # expected: this plan's commit, the three spec commits, then c54fd0e49, d3d4fe30d
```

If `responses-api` has moved beyond `c54fd0e49` by then, rebase onto its tip anyway and re-read `WireProviders.java` and `PresetCandidatesLiveTest.java` before Tasks 9 and 10: the code blocks there were written against `c54fd0e49`. Never push.

## Plan rulings

Where the code on this branch disagrees with the spec's names or leaves a mechanical choice open, this plan follows the code and records the choice here. None of these is a new public concept.

1. **The strict rewrite is not moved (spec §10).** It already lives in its own package-private class, `OpenAiResponsesSchemas` (`static Projected project(String json, JsonMapper mapper)`, `record Projected(Map<String, Object> schema, Optional<String> refusedKeyword)` with `strict()`), not inside `OpenAiResponsesRequests`. The chat projection calls it as it stands. The spec's name `OpenAiStrictSchemas` is not adopted; renaming a package-private class is a separate, optional commit for James.
2. **Line numbers.** Spec §5a's two factory lines are exact on this branch (`DefaultDirectHarnessFactory` line 294, `DefaultQueuedHarnessFactory` line 349). The README module table row for `nessy-inference-spi` is line 172, not 173. The report example in §6c prints `https://api.openai.com/v1`; the code prints `the vendor's own endpoint` for a preset with no URL, and the tests follow the code.
3. **`VendorProperties` signatures (spec §8e lists roles, not signatures).** `nest` and `refuseClashes` take the prefix as a first argument, so a message names the property as the user spelled it (`'openai.max_completion_tokens'`, not `'max_completion_tokens'`). `requireInteger`, `requireBoolean` and `requireString` take `(String name, String value)` with the full name. There is no `requireNumber`: Bedrock's two float names are read through `literal(...)` and an `instanceof Number` check in its own package-private class, so the helper grows no method the spec did not list.
4. **`InferenceOptions.properties` keeps insertion order.** The canonical constructor copies into an unmodifiable `LinkedHashMap` (null names and values refused) rather than `Map.copyOf`, whose iteration order changes between JVM runs and would make "the first clash named" nondeterministic. Same for `EmbeddingOptions`. Both records override `toString` to print property names only, never values (the §6c rule, applied wherever a value could reach a log).
5. **A clash message names the typed setting, not its value.** `property 'openai.max_completion_tokens' names what InferenceConfig.maxTokens already decides; remove the property` -- without §7b's `(4096)`, because the same table serves `XProviderConfig.build()`, where no agent type and no value exist yet. The engine prefixes `agent type 'chat': ` when it re-throws.
6. **Owned roots (the §7b raw-spelling rule, applied to paths).** When an adapter builds an object from a known name, any pass-through name at or under that object's path is a clash naming the known name, so a typed object and a pass-through object can never both claim one field: `reasoning` on the Responses wire (when `reasoning.effort` or `reasoning.summary` is set), `thinking` and `cache_control` on Anthropic, `generationConfig.thinkingConfig` on Gemini. The spec's listed raw spellings (`thinking`, `cache_control`) are the exact-name case of this rule. Flagged for James in Open questions.
7. **`openai.service_tier` is its own raw spelling.** The known name is spelled as the wire field, so there is no second spelling to add to either OpenAI clash table (spec §9a/§9b list it as if there were).
8. **Anthropic keeps its public surface exactly.** `AnthropicRequests` is a public class with a public `Features` record and a public `toParams(InferenceRequest, Features, JsonMapper)`; all three stay unchanged, so nothing breaks. The per-request parsed form is the package-private `AnthropicProperties.Read`, not `Features` (§9c's "Features becomes the parsed form" would have changed a public record's components). The config's setters become provider-level property entries at `build()`: `thinking(true)` is `anthropic.thinking.type=enabled` plus `anthropic.thinking.budget_tokens` (from `thinkingBudget(int)`, else from a budget property, else the default 1024); `promptCaching(FIVE_MINUTES)` / `ONE_HOUR` is `anthropic.cache_control.ttl=5m` / `1h`. `thinking(false)` and `promptCaching(OFF)` contribute no entry but still count as "set" for the both-set rule. `thinkingBudget(n)` without `thinking(true)` contributes nothing, exactly as today (a bare budget property would turn thinking on under §9c's "absent with a budget means enabled"; a bare setter must not).
9. **`anthropic.thinking.type`.** `disabled` sends no `thinking` object: §13a's test ("sends no `thinking` object over a config that turned it on") is the more specific statement than §9c's table cell (`ThinkingConfigDisabled`), and it matches today's `enabled_asks_for_a_budget_and_disabled_asks_for_nothing`. `adaptive` sends `ThinkingConfigAdaptive`. Any other value is checked for type only (a non-blank string) and sent as the raw object `{"type": <value>, "budget_tokens": <n>}` (budget only when set) through `putAdditionalBodyProperty`, so the vendor, not Nessy, judges the vocabulary (§8b). `anthropic.cache_control.ttl` goes through `CacheControlEphemeral.Ttl.of(value)` for the same reason (`5m` keeps today's marker with no explicit ttl; `1h` is `TTL_1H`).
10. **Provider-level checks at `build()` skip the headroom check.** `XProviderConfig.build()` checks prefix, clashes, known-name types and wire-cannot-carry names on the provider's own map; the thinking-budget-under-`maxTokens` check needs an agent type's `maxTokens` and runs at `validate`. A provider-level `anthropic.thinking.type=enabled` without a budget is refused at `build()` (a provider-level statement must stand alone).
11. **Where "config property reaches requests" is tested.** OpenAI and Anthropic: in `*InferenceProviderTest`, where the fake clients live, built through the config's `client(...)`. Gemini and Bedrock: their configs take a real SDK client, so the test is a `validate` on the built provider that fails on a clash between the config's entry and an agent type's entry -- which only happens if the config's map reached the provider.
12. **Boot failure rows need a key.** Spec §13b's `nessy.providers.openai.properties.openai.model=gpt-4o` and `nessy.providers.anthropic.properties.openai.reasoning.effort=high` rows light no provider without `openai.api-key` / `anthropic.api-key`; the tests set the key.
13. **Embedder configs carry and ignore (§9f).** `property`/`properties` store the map on the config and `build()` refuses an entry outside the embedder's prefix (`openai.`, `gemini.`, `bedrock.`, `voyage.`); the map is not handed to the embedding provider until the named-embedders item.
14. **The engine re-throws only `IllegalArgumentException`** from `validate`, prefixed `agent type '<type>': `, with the original as the cause. Anything else a `validate` throws is a bug and escapes as it is.
15. **The PresetCandidates baseline pins strict off.** The `openai` preset now defaults `openai.tools.strict=true`; the "as today" run of every chat-wire candidate passes `openai.tools.strict=false` so the two columns compare non-strict with strict.
16. **SLF4J is declared where it is used.** `nessy-inference-anthropic`, `-gemini` and `-bedrock` gain the `slf4j-api` dependency (managed version, as `nessy-inference-openai` declares it) for the `DEBUG` line naming ignored other-prefix properties; today they reach it only transitively through `nessy-api`.

## Open questions (for James; nothing below is decided by this plan)

1. **The harness report line cannot show the merged result without a new SPI method.** §7a says the report line "shows it by asking the adapter (§7c)", but §7c's only hook is `validate(InferenceOptions)`, which returns nothing. This plan prints the agent type's own property names (§6c's literal words); the provider's names are on Boot's provider line. Showing the merged set would need something like a method returning the effective names -- a new public concept, not added here.
2. **Summarisers never call `validate`.** `HeadSummarizer` and `EpisodeSummarizer` take an `InferenceOptions` and call `infer` directly; a bad property there is an `IllegalArgumentException` thrown from `infer` on the first summary, not at build. Should the memory modules call `provider.validate(options)` at construction? Not in the spec; not done here.
3. **Owned roots (ruling 6)**, **`disabled` sends nothing (ruling 9)** and **unknown `anthropic.thinking.type` values sent raw (ruling 9)** are this plan's readings of the spec where it was silent or said two things; confirm or overrule.
4. **Gemini's `extraBody` merge** (spec §16(5)) is still unmeasured; Task 10's Gemini case measures it and records the answer. If `extraBody` replaces the SDK's own `generationConfig`, the spec's contingency applies (the adapter nests its pass-through into the config it builds) and is a follow-up commit, not part of this plan.

## Global Constraints

- Full verification: `./mvnw -q clean verify` -- must pass with no API key and no model-provider network access. Run it ONCE per task, as the final gate before the task's last commit, never per step. Judge Maven by its exit code (`echo exit=$?`), never by grepping its output. Task 1 especially: only a CLEAN reactor build proves the new module's order; a warm build certifies classes against jars in `~/.m2`.
- While iterating use warm scoped builds, artifactId form with the colon, `-am` whenever an upstream module changed: `./mvnw -q -pl :nessy-vendor-properties test`, `./mvnw -q -pl :nessy-inference-spi,:nessy-embedding-spi -am test`, `./mvnw -q -pl :nessy-engine -am test`, `./mvnw -q -pl :nessy-inference-openai -am test`, `:nessy-inference-anthropic`, `:nessy-inference-gemini`, `:nessy-inference-bedrock`, `:nessy-embedding-openai`, `:nessy-embedding-gemini`, `:nessy-embedding-bedrock`, `:nessy-embedding-voyage`, `./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test`. Never the path form (`-pl nessy-inference/openai`).
- If a scoped run hangs or reports "cannot find symbol" after a signature change, suspect a stale jar in `~/.m2`: `./mvnw -q -pl :nessy-vendor-properties,:nessy-inference-spi,:nessy-embedding-spi -am install -DskipTests`, then retry.
- Maven runs in the FOREGROUND. Never two Maven processes at once in this worktree. Never poll with `pgrep -f` (it matches its own command line and never ends). Before a build, check once, in the same command, that no example app is running from this checkout: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q ...` -- the bracket keeps the pattern from matching itself; if it prints anything about nessy, stop and ask.
- `spotless:check` runs before compilation, so an unformatted file fails the build with no compiler output. Run `./mvnw -q spotless:apply license:format` before reading any build error and before every commit. `./mvnw -q spotless:check license:check` is what CI runs.
- Every new file carries the Apache license header (copy it from any file in the same module; `license:format` adds it to Java files, and the new `pom.xml` copies it from `nessy-inference/spi/pom.xml` lines 2-18). If `license:format` touches far more files than the task did, commit the headers alone first.
- Formatting is google-java-format (spotless enforces it). The code below is written close to it; `spotless:apply` settles the rest.
- No warning suppression of any kind (`@SuppressWarnings`, etc.) -- write code that raises no warning (no unchecked casts: walk JSON as `Map<?, ?>` / `List<?>` with `instanceof` patterns). No star imports, including static imports.
- Tests: prose-style snake_case method names in the module's voice; `junit-platform.properties` turns underscores into the display sentence. **No mocking library** -- SDKs are faked with JDK dynamic proxies and scripted clients as the existing tests do (`OpenAiChatInferenceProviderTest.fakeClient`, `ResponseStreams.client`, `AnthropicInferenceProviderTest.fakeClient`, `GeminiClient`, `BedrockClient`). AssertJ.
- Sonar S5778: an `assertThatThrownBy` lambda contains exactly ONE call that can throw; build configs, maps, options, requests and customizers outside it.
- Assert a collection is non-empty before any `allMatch` / `noneMatch` / `allSatisfy` on it.
- Javadoc: never put a second `/** */` above a declaration that already has one (the first is silently dropped). To add `@param`, edit the existing comment.
- XML comments may not contain `--` (the house em-dash style is a parse error in `pom.xml`).
- macOS: BSD `sed` has no `\b` -- use `perl -pi -e` for word-boundary replacements. zsh does not word-split unquoted variables.
- Docs describe what is -- never history or roads not taken. Dated records under `docs/superpowers/` and released changelog entries are history and stay as written.
- Every commit message ends with:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC
  ```
- Branch: `vendor-properties`, worktree `/Users/jcarman/IdeaProjects/nessy-props`. Commit to it; never push. Never touch `/Users/jcarman/IdeaProjects/nessy` or `/Users/jcarman/IdeaProjects/nessy-responses`.
- Model policy (repo `CLAUDE.md`): implementers default to Sonnet (Task 8 is transcription: Haiku). Per-task review on Sonnet, except **Task 1 and Task 5, reviewed with Opus**: Task 1's `VendorProperties` is the one reading all eight adapters inherit (a wrong literal or nesting rule is wrong everywhere at once), and Task 5 carries the precedence tiers (setter vs property vs agent type) against a public API that must not move. Scoped re-reviews of small fix diffs on Haiku. Final whole-branch review on Opus.

## Review Focus

The inputs the spec implies but no spec-listed test exercises, most likely to bite first. Each has its test in the owning task.

1. **A value that only starts like JSON** (`12abc`, `true story`, `{"a":1} trailing`): it must be the string it is, not a number or object read off its front. The literal reading turns on trailing-token failure explicitly rather than trusting the application mapper's defaults. → Task 1, `a_value_that_only_starts_like_json_is_a_string`.
2. **A dotted name with an empty segment** (`openai..seed`, `openai.seed.`): it must be refused naming the property, never sent as a `""` key the vendor will reject with a confusing message. → Task 1, `an_empty_segment_is_refused_naming_the_property`.
3. **A pass-through under an object the adapter builds from a known name** (`openai.reasoning.generate_summary=auto` beside `openai.reasoning.effort=high` on the Responses wire): without ruling 6, the typed `reasoning` object and the pass-through `reasoning` object both reach the body and one silently wins. It must be refused naming both. → Task 4, `a_pass_through_under_the_reasoning_object_is_refused_beside_a_known_reasoning_name`.
4. **Boot keys that carry the vendor's own spelling** -- an underscore (`anthropic.thinking.budget_tokens`) or camelCase (`gemini.generationConfig.thinkingConfig.thinkingBudget`): the binder must keep the key exactly, or the adapter reads a name that does not exist. Pinned from `withPropertyValues` and from YAML. → Task 9, `an_underscored_key_keeps_its_underscore` and `a_camel_case_key_keeps_its_case`.
5. **`thinkingBudget(n)` on the Anthropic config without `thinking(true)`**: today it does nothing; the property form's "a budget alone means enabled" must not leak into the setter and turn thinking on for an application that never asked. → Task 5, `a_budget_setter_without_thinking_on_still_asks_for_no_thinking`.

---

### Task 1: The module, the helper, and the SPIs

Spec §5, §7c, §8e, §14 step 1. No behaviour change: every adapter still ignores properties after this task. Suggested implementer: Sonnet. **Review: Opus** (see Global Constraints).

**Files:**
- Create: `nessy-vendor-properties/pom.xml`
- Create: `nessy-vendor-properties/src/main/java/org/jwcarman/nessy/vendor/package-info.java`
- Create: `nessy-vendor-properties/src/main/java/org/jwcarman/nessy/vendor/VendorProperties.java`
- Test: `nessy-vendor-properties/src/test/java/org/jwcarman/nessy/vendor/VendorPropertiesTest.java`
- Modify: `pom.xml` (the `<modules>` list, line 62; `<dependencyManagement>`, lines 382-391)
- Modify: `nessy-bom/pom.xml` (three rows), `nessy-coverage/pom.xml` (one row), `README.md` (module table, line 172)
- Modify: `nessy-inference/spi/pom.xml`, `nessy-embedding/spi/pom.xml` (one dependency each)
- Modify: `nessy-inference/spi/src/main/java/org/jwcarman/nessy/inference/InferenceOptions.java`, `InferenceProvider.java`
- Modify: `nessy-embedding/spi/src/main/java/org/jwcarman/nessy/embedding/EmbeddingOptions.java`, `EmbeddingProvider.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/observability/ObservedInferenceProvider.java`
- Test: `nessy-inference/spi/src/test/java/org/jwcarman/nessy/inference/InferenceTypesTest.java`, `InferenceProviderTest.java`
- Test (create): `nessy-embedding/spi/src/test/java/org/jwcarman/nessy/embedding/EmbeddingOptionsTest.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/ProviderRegistryTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces (public, `org.jwcarman.nessy.vendor.VendorProperties`, every later task uses these exact signatures):
  - `static Map<String, String> under(Map<String, String> merged, String prefix)` -- entries under `prefix` with it stripped, insertion order kept; a name with no prefix (no `.`, or a leading `.`) anywhere in `merged` is an `IllegalArgumentException`
  - `static Map<String, String> merge(Map<String, String> provider, Map<String, String> agentType)` -- `agentType` over `provider`, name by name
  - `static Object literal(String value, JsonMapper mapper)` -- a `Map`, `List`, `String`, `Number`, `Boolean` or `null` when `value` is JSON; `value` itself otherwise
  - `static Map<String, Object> nest(String prefix, Map<String, String> flat, JsonMapper mapper)` -- dotted names into one tree of plain maps and lists
  - `static void refuseClashes(String prefix, Map<String, String> underPrefix, Map<String, String> clashTable)` -- `clashTable` maps a stripped name to what decides it
  - `static int requireInteger(String name, String value)`, `static boolean requireBoolean(String name, String value)`, `static String requireString(String name, String value)` -- `name` is the full property name
- Produces (SPI): `InferenceOptions(String modelName, int maxTokens, Map<String, String> properties)` plus the unchanged `InferenceOptions(String, int)` and `of(String)`; `EmbeddingOptions(String modelName, OptionalInt dimension, Map<String, String> properties)` plus the unchanged `EmbeddingOptions(String, OptionalInt)` and `of(String)`; `default void InferenceProvider.validate(InferenceOptions options)`; `default void EmbeddingProvider.validate(EmbeddingOptions options)`; `ObservedInferenceProvider.validate` delegating.

- [ ] **Step 1: Create the module's pom**

`nessy-vendor-properties/pom.xml` -- the house shape, modelled on `nessy-inference/spi/pom.xml`. Copy that file's lines 1-18 (the XML declaration and the Apache header comment) verbatim as the top of this file, then:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-parent</artifactId>
    <version>0.3.0-SNAPSHOT</version>
  </parent>

  <artifactId>nessy-vendor-properties</artifactId>
  <name>Nessy Vendor Properties</name>
  <description>How an adapter reads the vendor-prefixed properties it owns: its prefix, the names it parses, and the rest passed through</description>

  <!--
    Beneath both SPIs and beside nessy-api, depending on nothing of Nessy's: an embedding SPI that
    depended on the inference SPI for one class would stop being its sibling, and a copy per SPI
    would drift. Jackson is here for the JsonMapper the literal reading takes.
  -->

  <dependencies>
    <dependency>
      <groupId>tools.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
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

- [ ] **Step 2: Wire the module into the reactor, the BOM, coverage and the README**

Root `pom.xml`, in `<modules>`: add `<module>nessy-vendor-properties</module>` on the line after `<module>nessy-api</module>` (line 62).

Root `pom.xml`, in `<dependencyManagement>`, directly above the `nessy-inference-spi` entry (line 382):

```xml
      <dependency>
        <groupId>org.jwcarman.nessy</groupId>
        <artifactId>nessy-vendor-properties</artifactId>
        <version>${project.version}</version>
      </dependency>
```

`nessy-bom/pom.xml`: after the `nessy-api` row add `nessy-vendor-properties`; directly before the `nessy-embedding-openai` row add `nessy-embedding-spi`; directly before the `nessy-inference-openai` row add `nessy-inference-spi` (the two SPIs are the artifacts the README tells adapter authors to depend on, and the BOM has omitted them -- ruled yes). Each row in the file's own shape:

```xml
            <dependency>
                <groupId>org.jwcarman.nessy</groupId>
                <artifactId>nessy-vendor-properties</artifactId>
                <version>${project.version}</version>
            </dependency>
```

`nessy-coverage/pom.xml`: after the `nessy-api` dependency add one `nessy-vendor-properties` dependency in that file's shape (`groupId`, `artifactId`, `<version>${project.version}</version>`, two-space indent, no `<scope>`). The file lists neither SPI today and this task does not add them.

Do **not** add the module to the release profile's `<excludeArtifacts>` (root `pom.xml` ~957-977): a library is published by not being named there.

`README.md`, module table: insert after the `nessy-inference-spi` row (line 172):

```markdown
| `nessy-vendor-properties` | adapter authors: `VendorProperties`, which reads the `openai.*`-style properties an adapter owns |
```

- [ ] **Step 3: The SPIs depend on it**

In both `nessy-inference/spi/pom.xml` and `nessy-embedding/spi/pom.xml`, directly after the `nessy-api` dependency:

```xml
    <dependency>
      <groupId>org.jwcarman.nessy</groupId>
      <artifactId>nessy-vendor-properties</artifactId>
      <version>${project.version}</version>
    </dependency>
```

- [ ] **Step 4: Write the failing helper test**

`nessy-vendor-properties/src/test/java/org/jwcarman/nessy/vendor/VendorPropertiesTest.java`:

```java
package org.jwcarman.nessy.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Vendor properties, as every adapter reads them")
class VendorPropertiesTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** Insertion order, which a Map.of would not keep. */
  private static Map<String, String> ordered(String... pairs) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put(pairs[i], pairs[i + 1]);
    }
    return map;
  }

  @Nested
  class UnderAPrefix {

    @Test
    void entries_come_back_with_the_prefix_stripped_and_other_prefixes_left_out() {
      Map<String, String> merged =
          ordered(
              "openai.reasoning.effort", "high",
              "anthropic.thinking.budget_tokens", "8192",
              "openai.seed", "42");

      assertThat(VendorProperties.under(merged, "openai."))
          .containsExactly(Map.entry("reasoning.effort", "high"), Map.entry("seed", "42"));
    }

    @Test
    void a_name_with_no_prefix_is_refused() {
      Map<String, String> merged = Map.of("temperature", "0.2");

      assertThatThrownBy(() -> VendorProperties.under(merged, "openai."))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'temperature' has no prefix");
    }

    @Test
    void a_name_starting_with_a_dot_is_refused() {
      Map<String, String> merged = Map.of(".seed", "1");

      assertThatThrownBy(() -> VendorProperties.under(merged, "openai."))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'.seed' has no prefix");
    }

    @Test
    void a_prefix_must_be_one_segment_ending_in_a_dot() {
      Map<String, String> merged = Map.of();

      assertThatThrownBy(() -> VendorProperties.under(merged, "gcp.gemini"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("gcp.gemini");
    }
  }

  @Test
  void agent_type_entries_override_provider_entries_by_name() {
    Map<String, String> provider = ordered("openai.seed", "1", "openai.store", "false");
    Map<String, String> agentType = Map.of("openai.seed", "2");

    assertThat(VendorProperties.merge(provider, agentType))
        .containsExactly(Map.entry("openai.seed", "2"), Map.entry("openai.store", "false"));
  }

  @Nested
  class ALiteral {

    @Test
    void a_whole_number_is_a_number() {
      assertThat(VendorProperties.literal("12000", MAPPER)).isEqualTo(12000);
    }

    @Test
    void a_decimal_is_a_number() {
      assertThat(VendorProperties.literal("0.2", MAPPER)).isEqualTo(0.2);
    }

    @Test
    void true_is_a_boolean() {
      assertThat(VendorProperties.literal("true", MAPPER)).isEqualTo(true);
    }

    @Test
    void null_is_null() {
      assertThat(VendorProperties.literal("null", MAPPER)).isNull();
    }

    @Test
    void braces_are_an_object() {
      assertThat(VendorProperties.literal("{\"type\":\"enabled\",\"budget_tokens\":4096}", MAPPER))
          .isEqualTo(Map.of("type", "enabled", "budget_tokens", 4096));
    }

    @Test
    void brackets_are_an_array() {
      assertThat(VendorProperties.literal("[\"\\n\\n\"]", MAPPER)).isEqualTo(List.of("\n\n"));
    }

    @Test
    void a_bare_word_is_a_string() {
      assertThat(VendorProperties.literal("high", MAPPER)).isEqualTo("high");
    }

    @Test
    void a_quoted_number_is_a_string() {
      assertThat(VendorProperties.literal("\"12345\"", MAPPER)).isEqualTo("12345");
    }

    /** Review Focus 1: the front of a value parsing as JSON does not make the value JSON. */
    @Test
    void a_value_that_only_starts_like_json_is_a_string() {
      assertThat(VendorProperties.literal("12abc", MAPPER)).isEqualTo("12abc");
      assertThat(VendorProperties.literal("true story", MAPPER)).isEqualTo("true story");
      assertThat(VendorProperties.literal("{\"a\":1} trailing", MAPPER))
          .isEqualTo("{\"a\":1} trailing");
    }
  }

  @Nested
  class Nesting {

    @Test
    void every_dot_after_the_prefix_nests_one_object() {
      Map<String, Object> tree =
          VendorProperties.nest("openai.", Map.of("reasoning.effort", "high"), MAPPER);

      assertThat(tree).isEqualTo(Map.of("reasoning", Map.of("effort", "high")));
    }

    @Test
    void two_paths_that_meet_are_deep_merged() {
      Map<String, Object> tree =
          VendorProperties.nest("x.", ordered("a.b", "1", "a.c", "2", "d", "true"), MAPPER);

      assertThat(tree).isEqualTo(Map.of("a", Map.of("b", 1, "c", 2), "d", true));
    }

    @Test
    void a_value_meeting_an_object_is_refused_naming_both() {
      Map<String, String> flat = ordered("a", "1", "a.b", "2");

      assertThatThrownBy(() -> VendorProperties.nest("x.", flat, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'x.a'")
          .hasMessageContaining("'x.a.b'");
    }

    @Test
    void an_object_meeting_a_later_value_is_refused_naming_both() {
      Map<String, String> flat = ordered("a.b", "2", "a", "1");

      assertThatThrownBy(() -> VendorProperties.nest("x.", flat, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'x.a'")
          .hasMessageContaining("'x.a.b'");
    }

    /** Review Focus 2. */
    @Test
    void an_empty_segment_is_refused_naming_the_property() {
      Map<String, String> doubled = Map.of(".seed", "1");
      Map<String, String> trailing = Map.of("seed.", "1");

      assertThatThrownBy(() -> VendorProperties.nest("openai.", doubled, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'openai..seed' has an empty segment");
      assertThatThrownBy(() -> VendorProperties.nest("openai.", trailing, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'openai.seed.' has an empty segment");
    }
  }

  @Test
  void a_clash_is_refused_naming_the_property_and_what_decides_it() {
    Map<String, String> under = Map.of("max_completion_tokens", "10");
    Map<String, String> table = Map.of("max_completion_tokens", "InferenceConfig.maxTokens");

    assertThatThrownBy(() -> VendorProperties.refuseClashes("openai.", under, table))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "property 'openai.max_completion_tokens' names what InferenceConfig.maxTokens"
                + " already decides; remove the property");
  }

  @Test
  void a_name_outside_the_clash_table_passes() {
    VendorProperties.refuseClashes(
        "openai.", Map.of("seed", "1"), Map.of("model", "InferenceConfig.model"));
  }

  @Nested
  class TypedReads {

    @Test
    void an_integer_is_read() {
      assertThat(VendorProperties.requireInteger("anthropic.thinking.budget_tokens", "8192"))
          .isEqualTo(8192);
    }

    @Test
    void a_bad_integer_is_refused_naming_the_property_and_the_value() {
      assertThatThrownBy(
              () -> VendorProperties.requireInteger("anthropic.thinking.budget_tokens", "lots"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property 'anthropic.thinking.budget_tokens' must be an integer, was 'lots'");
    }

    @Test
    void a_boolean_is_read() {
      assertThat(VendorProperties.requireBoolean("openai.tools.strict", "false")).isFalse();
    }

    @Test
    void a_bad_boolean_is_refused_naming_the_property_and_the_value() {
      assertThatThrownBy(() -> VendorProperties.requireBoolean("openai.tools.strict", "yes"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property 'openai.tools.strict' must be true or false, was 'yes'");
    }

    @Test
    void a_string_is_read() {
      assertThat(VendorProperties.requireString("openai.reasoning.effort", "xhigh"))
          .isEqualTo("xhigh");
    }

    @Test
    void a_blank_string_is_refused_naming_the_property() {
      assertThatThrownBy(() -> VendorProperties.requireString("openai.reasoning.effort", " "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property 'openai.reasoning.effort' must be a non-blank string, was ' '");
    }
  }
}
```

- [ ] **Step 5: Run it to see it fail**

Run: `./mvnw -q -pl :nessy-vendor-properties test; echo exit=$?`
Expected: compilation failure, `cannot find symbol ... VendorProperties`; `exit=1`.

- [ ] **Step 6: Write the helper**

`nessy-vendor-properties/src/main/java/org/jwcarman/nessy/vendor/package-info.java` (license header, then):

```java
/**
 * Vendor properties: the {@code openai.}-, {@code anthropic.}-style settings an adapter owns and
 * the neutral API does not name, read the same way by every adapter.
 */
package org.jwcarman.nessy.vendor;
```

`nessy-vendor-properties/src/main/java/org/jwcarman/nessy/vendor/VendorProperties.java` (license header, then):

```java
package org.jwcarman.nessy.vendor;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * How every adapter reads vendor properties: the entries under its own prefix, the provider's map
 * under the agent type's, each value as a JSON literal where it parses as one, dotted names as
 * nested objects, and the names a typed setting already decides refused.
 *
 * <p>One class so that eight adapters cannot come to disagree about what {@code true} means. An
 * adapter keeps its own prefix, its known names and its clash table; everything mechanical is here.
 * Every failure is an {@link IllegalArgumentException} naming the property as the user spelled it.
 */
public final class VendorProperties {

  private VendorProperties() {}

  /**
   * The entries under {@code prefix}, with it stripped, in the order given. Entries under other
   * prefixes are left out -- another adapter's settings, not a mistake -- but a name with no prefix
   * at all belongs to nobody and is refused.
   *
   * @param prefix one segment and a dot, as {@code openai.}
   */
  public static Map<String, String> under(Map<String, String> merged, String prefix) {
    Objects.requireNonNull(merged, "merged must not be null");
    requirePrefix(prefix);
    Map<String, String> under = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : merged.entrySet()) {
      String name = entry.getKey();
      if (name.indexOf('.') <= 0) {
        throw new IllegalArgumentException(
            "property '"
                + name
                + "' has no prefix; a vendor property is named for the adapter that reads it,"
                + " as in 'openai.temperature'");
      }
      if (name.startsWith(prefix)) {
        under.put(name.substring(prefix.length()), entry.getValue());
      }
    }
    return Collections.unmodifiableMap(under);
  }

  /** The provider's properties, overlaid name by name by the agent type's (spec §7a). */
  public static Map<String, String> merge(
      Map<String, String> provider, Map<String, String> agentType) {
    Map<String, String> merged = new LinkedHashMap<>(provider);
    merged.putAll(agentType);
    return Collections.unmodifiableMap(merged);
  }

  /**
   * The value as the JSON literal it spells -- a map, a list, a string, a number, a boolean or
   * {@code null} -- or, when it is not JSON, the text itself: {@code high} is the string {@code
   * "high"}. The whole value must parse, whatever the mapper was configured to tolerate: {@code
   * 12abc} is a string, not the number twelve.
   */
  public static Object literal(String value, JsonMapper mapper) {
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(mapper, "mapper must not be null");
    try {
      return mapper
          .readerFor(Object.class)
          .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .readValue(value);
    } catch (JacksonException notJson) {
      // Not JSON, so it is the text it says.
      return value;
    }
  }

  /**
   * The names (prefix already stripped) as one tree: every dot nests an object, two paths that meet
   * are merged, and a value where another name needs an object is refused naming both.
   *
   * @param prefix what was stripped, so a message names the property as the user spelled it
   */
  public static Map<String, Object> nest(
      String prefix, Map<String, String> flat, JsonMapper mapper) {
    requirePrefix(prefix);
    Map<String, Object> root = new LinkedHashMap<>();
    Map<String, Map<String, Object>> objects = new HashMap<>();
    Map<String, String> openedBy = new HashMap<>();
    Map<String, String> values = new HashMap<>();
    objects.put("", root);
    for (Map.Entry<String, String> entry : flat.entrySet()) {
      String name = entry.getKey();
      String property = prefix + name;
      String[] segments = name.split("\\.", -1);
      for (String segment : segments) {
        if (segment.isEmpty()) {
          throw new IllegalArgumentException("property '" + property + "' has an empty segment");
        }
      }
      String parent = "";
      for (int i = 0; i < segments.length - 1; i++) {
        String path = parent.isEmpty() ? segments[i] : parent + "." + segments[i];
        if (values.containsKey(path)) {
          throw meeting(values.get(path), property);
        }
        if (!objects.containsKey(path)) {
          Map<String, Object> child = new LinkedHashMap<>();
          objects.get(parent).put(segments[i], child);
          objects.put(path, child);
          openedBy.put(path, property);
        }
        parent = path;
      }
      if (objects.containsKey(name)) {
        throw meeting(property, openedBy.get(name));
      }
      objects.get(parent).put(segments[segments.length - 1], literal(entry.getValue(), mapper));
      values.put(name, property);
    }
    return root;
  }

  /**
   * Refuses any entry the adapter's table says a typed setting, or the adapter itself, already
   * decides (spec §7b).
   *
   * @param clashTable a stripped name, and what decides it
   */
  public static void refuseClashes(
      String prefix, Map<String, String> underPrefix, Map<String, String> clashTable) {
    for (String name : underPrefix.keySet()) {
      String decidedBy = clashTable.get(name);
      if (decidedBy != null) {
        throw new IllegalArgumentException(
            "property '"
                + prefix
                + name
                + "' names what "
                + decidedBy
                + " already decides; remove the property");
      }
    }
  }

  /** A known name that takes an integer (spec §8b). */
  public static int requireInteger(String name, String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "property '" + name + "' must be an integer, was '" + value + "'", e);
    }
  }

  /** A known name that takes a boolean, spelled as JSON spells one. */
  public static boolean requireBoolean(String name, String value) {
    if ("true".equals(value)) {
      return true;
    }
    if ("false".equals(value)) {
      return false;
    }
    throw new IllegalArgumentException(
        "property '" + name + "' must be true or false, was '" + value + "'");
  }

  /** A known name that takes a string: any non-blank text, because the vocabulary is the vendor's. */
  public static String requireString(String name, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "property '" + name + "' must be a non-blank string, was '" + value + "'");
    }
    return value;
  }

  private static void requirePrefix(String prefix) {
    Objects.requireNonNull(prefix, "prefix must not be null");
    if (prefix.length() < 2 || prefix.indexOf('.') != prefix.length() - 1) {
      throw new IllegalArgumentException(
          "a prefix is one segment and a dot, as 'openai.'; was '" + prefix + "'");
    }
  }

  private static IllegalArgumentException meeting(String value, String object) {
    return new IllegalArgumentException(
        "properties '"
            + value
            + "' and '"
            + object
            + "' cannot both be sent: the first sets a value where the second needs an object");
  }
}
```

- [ ] **Step 7: Run it to see it pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-vendor-properties test; echo exit=$?`
Expected: `exit=0`.

- [ ] **Step 8: Write the failing SPI tests**

`nessy-inference/spi/src/test/java/org/jwcarman/nessy/inference/InferenceTypesTest.java` -- add (imports `java.util.LinkedHashMap`, `java.util.Map`):

```java
  @Test
  void options_carry_properties_in_the_order_given_and_two_arguments_carry_none() {
    Map<String, String> given = new LinkedHashMap<>();
    given.put("openai.seed", "1");
    given.put("anthropic.top_k", "5");

    InferenceOptions options = new InferenceOptions("m", 10, given);
    given.put("openai.store", "false");

    assertThat(options.properties())
        .containsExactly(Map.entry("openai.seed", "1"), Map.entry("anthropic.top_k", "5"));
    assertThat(new InferenceOptions("m", 10).properties()).isEmpty();
    assertThat(InferenceOptions.of("m").properties()).isEmpty();
    assertThat(new InferenceOptions("m", 10)).isEqualTo(new InferenceOptions("m", 10, Map.of()));
  }

  @Test
  void options_refuse_a_null_property_value_and_never_print_one() {
    Map<String, String> nullValue = new LinkedHashMap<>();
    nullValue.put("openai.user", null);
    InferenceOptions options = new InferenceOptions("m", 10, Map.of("openai.user", "tenant-42"));

    assertThatThrownBy(() -> new InferenceOptions("m", 10, nullValue))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("openai.user");
    assertThat(options.toString()).contains("openai.user").doesNotContain("tenant-42");
  }
```

`nessy-inference/spi/src/test/java/org/jwcarman/nessy/inference/InferenceProviderTest.java` -- add:

```java
  /** A provider that reads no properties accepts every set of terms. */
  @Test
  void the_default_validate_accepts_everything() {
    InferenceProvider provider = (request, narrator) -> null;
    InferenceOptions options = new InferenceOptions("m", 10, Map.of("anything.at", "all"));

    provider.validate(options);

    assertThat(options.properties()).containsKey("anything.at");
  }
```

(import `java.util.Map` if absent.)

`nessy-embedding/spi/src/test/java/org/jwcarman/nessy/embedding/EmbeddingOptionsTest.java` (new, license header, then):

```java
package org.jwcarman.nessy.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.embedding.Embedding;

class EmbeddingOptionsTest {

  @Test
  void options_carry_properties_and_the_short_forms_carry_none() {
    EmbeddingOptions options =
        new EmbeddingOptions("m", OptionalInt.of(256), Map.of("voyage.truncation", "false"));

    assertThat(options.properties()).containsExactly(Map.entry("voyage.truncation", "false"));
    assertThat(new EmbeddingOptions("m", OptionalInt.empty()).properties()).isEmpty();
    assertThat(EmbeddingOptions.of("m").properties()).isEmpty();
    assertThat(options.toString()).contains("voyage.truncation").doesNotContain("false");
  }

  @Test
  void the_default_validate_accepts_everything() {
    EmbeddingProvider provider =
        new EmbeddingProvider() {
          @Override
          public List<Embedding> embedDocuments(List<String> texts, EmbeddingOptions options) {
            return List.of();
          }

          @Override
          public Embedding embedQuery(String query, EmbeddingOptions options) {
            return new Embedding("m", new float[] {1f});
          }

          @Override
          public String vendor() {
            return "test";
          }
        };
    EmbeddingOptions options =
        new EmbeddingOptions("m", OptionalInt.empty(), Map.of("anything.at", "all"));

    provider.validate(options);

    assertThat(options.properties()).containsKey("anything.at");
  }
}
```

`nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/ProviderRegistryTest.java` -- add (imports `java.util.ArrayList`, `java.util.List`, `java.util.Map`, `org.jwcarman.nessy.inference.InferenceNarrator`, `org.jwcarman.nessy.inference.InferenceOptions`, `org.jwcarman.nessy.inference.InferenceRequest`, `org.jwcarman.nessy.inference.InferenceResult`):

```java
  /** Remembers what it was asked to validate. */
  private static final class Validating implements InferenceProvider {
    final List<InferenceOptions> validated = new ArrayList<>();

    @Override
    public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
      return null;
    }

    @Override
    public void validate(InferenceOptions options) {
      validated.add(options);
    }
  }

  @Test
  void validate_reaches_the_provider_through_its_observer() {
    Validating validating = new Validating();
    ProviderRegistry registry = new ProviderRegistry();
    registry.register(OPENAI, validating);
    InferenceProvider observed = registry.observed(ObservationRegistry.NOOP).resolve(CHAT, OPENAI);
    InferenceOptions options = new InferenceOptions("m", 10, Map.of("openai.seed", "7"));

    observed.validate(options);

    assertThat(observed).isInstanceOf(ObservedInferenceProvider.class);
    assertThat(validating.validated).containsExactly(options);
  }
```

- [ ] **Step 9: Run them to see them fail**

Run: `./mvnw -q -pl :nessy-inference-spi,:nessy-embedding-spi,:nessy-engine -am test -Dtest='InferenceTypesTest,InferenceProviderTest,EmbeddingOptionsTest,ProviderRegistryTest' -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: compilation failure (`no suitable constructor`, `cannot find symbol: method validate`); `exit=1`.

- [ ] **Step 10: Implement the SPI changes**

`InferenceOptions.java` -- replace the record (edit its existing javadoc rather than adding a second one; the first paragraph's "Nothing here is a provider's feature" sentence goes, because properties are exactly that):

```java
package org.jwcarman.nessy.inference;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The terms an inference is asked on: which model, the longest answer to allow, and the vendor
 * properties the agent type carries.
 *
 * <p>Fixed when a harness is built and identical on every call, which is why properties live here
 * rather than on {@link InferenceRequest}: they are the agent type's configuration, like its model.
 * An adapter reads the entries under its own prefix and ignores the rest; see {@code
 * InferenceProvider#validate}. Never written to the event log.
 *
 * @param modelName the model, as the provider names it
 * @param maxTokens the longest answer to allow, or zero for the provider's default
 * @param properties vendor-prefixed settings the neutral API does not name ({@code
 *     openai.reasoning.effort}), in the order given; empty for none
 */
public record InferenceOptions(String modelName, int maxTokens, Map<String, String> properties) {

  public InferenceOptions {
    Objects.requireNonNull(modelName, "modelName must not be null");
    if (modelName.isBlank()) {
      throw new IllegalArgumentException("modelName must not be blank");
    }
    properties = copyOf(properties);
  }

  /** No properties. */
  public InferenceOptions(String modelName, int maxTokens) {
    this(modelName, maxTokens, Map.of());
  }

  public static InferenceOptions of(String modelName) {
    return new InferenceOptions(modelName, 0);
  }

  public boolean hasMaxTokens() {
    return maxTokens > 0;
  }

  /** Names only: a property's value may be sensitive, and this is what a log line prints. */
  @Override
  public String toString() {
    return "InferenceOptions[modelName="
        + modelName
        + ", maxTokens="
        + maxTokens
        + ", properties="
        + properties.keySet()
        + "]";
  }

  /** In the order given, which {@code Map.copyOf} would not keep. */
  private static Map<String, String> copyOf(Map<String, String> properties) {
    Objects.requireNonNull(properties, "properties must not be null");
    Map<String, String> copy = new LinkedHashMap<>();
    properties.forEach(
        (name, value) ->
            copy.put(
                Objects.requireNonNull(name, "a property name must not be null"),
                Objects.requireNonNull(value, () -> "property '" + name + "' has no value")));
    return Collections.unmodifiableMap(copy);
  }
}
```

`EmbeddingOptions.java` -- the same shape: component `Map<String, String> properties` after `dimension`, the same `copyOf` and names-only `toString` (`"EmbeddingOptions[modelName=" + modelName + ", dimension=" + dimension + ", properties=" + properties.keySet() + "]"`), a two-argument `EmbeddingOptions(String modelName, OptionalInt dimension)` passing `Map.of()`, `of(modelName)` unchanged. Add to the existing javadoc: `@param properties vendor-prefixed settings ({@code voyage.truncation}); read by the embedding adapters from the named-embedders item on, carried and ignored until then`.

`InferenceProvider.java` -- add after `infer(InferenceRequest)`:

```java
  /**
   * Refuses terms this provider cannot honour, before a harness is built on them: a property that
   * clashes with a typed setting, a known name with a value of the wrong type, a known name this
   * wire cannot carry. The default accepts everything, which is right for a provider that reads no
   * properties.
   *
   * @throws IllegalArgumentException naming the property, which the engine re-throws prefixed by
   *     the agent type
   */
  default void validate(InferenceOptions options) {}
```

`EmbeddingProvider.java` -- add after `embedQuery`:

```java
  /**
   * Refuses terms this provider cannot honour, before an embedder is built on them. The default
   * accepts everything, which is right for a provider that reads no properties.
   *
   * @throws IllegalArgumentException naming the property
   */
  default void validate(EmbeddingOptions options) {}
```

`ObservedInferenceProvider.java` -- add after `vendor()` (import `org.jwcarman.nessy.inference.InferenceOptions`):

```java
  /** Not observed: nothing is called, and a refusal is the build's failure, not a span's. */
  @Override
  public void validate(InferenceOptions options) {
    delegate.validate(options);
  }
```

- [ ] **Step 11: Run them to see them pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-inference-spi,:nessy-embedding-spi,:nessy-engine -am test -Dtest='InferenceTypesTest,InferenceProviderTest,EmbeddingOptionsTest,ProviderRegistryTest,VendorPropertiesTest' -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: `exit=0`.

- [ ] **Step 12: Full gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?`
Expected: `exit=0` (a CLEAN build is the proof of the reactor order; do not substitute a warm one).

```bash
cd /Users/jcarman/IdeaProjects/nessy-props
git add pom.xml README.md nessy-bom/pom.xml nessy-coverage/pom.xml nessy-vendor-properties \
  nessy-inference/spi nessy-embedding/spi \
  nessy-engine/src/main/java/org/jwcarman/nessy/engine/observability/ObservedInferenceProvider.java \
  nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/ProviderRegistryTest.java
git commit -m "feat: nessy-vendor-properties, and options that carry vendor properties

VendorProperties reads the prefixed properties an adapter owns: under a
prefix, provider under agent type, JSON literals, dotted paths, the clash
check, typed reads. InferenceOptions and EmbeddingOptions gain a properties
component; InferenceProvider and EmbeddingProvider gain a no-op validate.
The BOM lists the new module and both SPIs. No behaviour change.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 2: The API and the engine

Spec §4, §5a, §6c (the harness line), §7c (the factories call `validate`), §13c. Suggested implementer: Sonnet. Review: Sonnet.

**Files:**
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/InferenceConfig.java`
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/embedding/EmbedderConfig.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/harness/direct/DefaultDirectHarnessConfig.java` (`Inference`, lines 296-388)
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/harness/direct/DefaultDirectHarnessFactory.java` (`build`, lines 233-307)
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/harness/queued/DefaultQueuedHarnessConfig.java` (`Inference`, lines 385-452)
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/harness/queued/DefaultQueuedHarnessFactory.java` (lines 189-252)
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/embedding/DefaultEmbedderFactory.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/direct/DefaultDirectHarnessTest.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/queued/TerminationAndConfigurationTest.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/embedding/DefaultEmbedderFactoryTest.java`

**Interfaces:**
- Consumes: `InferenceOptions(String, int, Map<String, String>)`, `InferenceOptions.properties()`, `InferenceProvider.validate(InferenceOptions)`, `EmbeddingOptions(String, OptionalInt, Map<String, String>)`, `EmbeddingProvider.validate(EmbeddingOptions)` (Task 1).
- Produces (public, `nessy-api`): `InferenceConfig property(String name, String value)`, `EmbedderConfig property(String name, String value)`.
- Produces (engine, package-private): `static String DefaultDirectHarnessFactory.propertyNames(InferenceOptions options)` -- `""` for none, else `", properties [a, b]"` (sorted names, never values); the queued factory has its own copy.

- [ ] **Step 1: Write the failing direct-door tests**

`DefaultDirectHarnessTest.java` -- add imports `java.util.ArrayList`, `java.util.Map`, `org.jwcarman.nessy.api.DirectHarnessConfig`, `org.jwcarman.nessy.api.InferenceConfig`, then a nested class at the end of the outer class:

```java
  @Nested
  @DisplayName("vendor properties")
  class ItsVendorProperties {

    /** Answers once, remembers every set of terms it was asked to validate, refuses one name. */
    private static final class Judging implements InferenceProvider {
      final List<InferenceOptions> validated = new ArrayList<>();
      final Scripted answers = new Scripted().then(answering("ok"));

      @Override
      public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
        return answers.infer(request, narrator);
      }

      @Override
      public void validate(InferenceOptions options) {
        if (options.properties().containsKey("test.model")) {
          throw new IllegalArgumentException(
              "property 'test.model' names what InferenceConfig.model already decides;"
                  + " remove the property");
        }
        validated.add(options);
      }
    }

    private DirectHarnessFactory factory(InferenceProvider model, InferenceOptions defaults) {
      return DefaultDirectHarnessFactory.of(
          c ->
              c.backend(new FixedDirectBackend(new InMemoryLocks(), events, payloads))
                  .provider(ProviderId.of("test"), model)
                  .inference(ProviderId.of("test"), defaults)
                  .schemas(SCHEMAS)
                  .mapper(MAPPER)
                  .clock(clock));
    }

    private static Customizer<DirectHarnessConfig<String>> agentType(
        Customizer<InferenceConfig> inference) {
      return c ->
          c.systemPrompt("You are terse.")
              .inputRenderer(said -> List.of(new Block.Text(said)))
              .inference(inference);
    }

    @Test
    void an_agent_type_s_properties_reach_the_provider_with_every_request() {
      Scripted model = new Scripted().then(answering("ok"));

      factory(model, InferenceOptions.of("a-model"))
          .<String>create(TYPE, agentType(in -> in.property("openai.reasoning.effort", "high")))
          .ask(AgentId.random(), "hi");

      assertThat(model.seen).isNotEmpty();
      assertThat(model.seen.getFirst().options().properties())
          .containsExactly(Map.entry("openai.reasoning.effort", "high"));
    }

    @Test
    void factory_defaults_seed_an_agent_type_and_its_own_property_overrides_one_by_name() {
      Scripted model = new Scripted().then(answering("ok"));
      InferenceOptions defaults =
          new InferenceOptions("a-model", 0, Map.of("openai.seed", "1", "openai.store", "false"));

      factory(model, defaults)
          .<String>create(TYPE, agentType(in -> in.property("openai.seed", "2")))
          .ask(AgentId.random(), "hi");

      assertThat(model.seen.getFirst().options().properties())
          .containsOnly(Map.entry("openai.seed", "2"), Map.entry("openai.store", "false"));
    }

    @Test
    void the_provider_is_asked_to_validate_the_terms_when_the_harness_is_built() {
      Judging model = new Judging();

      factory(model, InferenceOptions.of("a-model"))
          .<String>create(TYPE, agentType(in -> in.property("openai.seed", "7")));

      assertThat(model.validated)
          .singleElement()
          .satisfies(options -> assertThat(options.properties()).containsKey("openai.seed"));
    }

    @Test
    void a_refusal_fails_the_build_naming_the_agent_type() {
      DirectHarnessFactory factory = factory(new Judging(), InferenceOptions.of("a-model"));
      Customizer<DirectHarnessConfig<String>> clashing =
          agentType(in -> in.property("test.model", "gpt-4o"));

      assertThatThrownBy(() -> factory.<String>create(TYPE, clashing))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "agent type 'chat': property 'test.model' names what InferenceConfig.model"
                  + " already decides; remove the property");
    }

    /** §5c made checkable: properties are configuration, and nothing about them is written. */
    @Test
    void no_event_carries_a_property() {
      AgentId agent = AgentId.random();

      factory(new Scripted().then(answering("ok")), InferenceOptions.of("a-model"))
          .<String>create(TYPE, agentType(in -> in.property("openai.user", "tenant-42-secret")))
          .ask(agent, "hi");

      assertThat(events.readAll(TYPE, agent)).isNotEmpty();
      assertThat(events.readAll(TYPE, agent).toString())
          .doesNotContain("tenant-42-secret")
          .doesNotContain("openai.user");
    }

    @Test
    void a_blank_name_or_value_is_refused_at_once() {
      DefaultDirectHarnessConfig<String> config =
          new DefaultDirectHarnessConfig<>(TYPE, ObservationRegistry.NOOP);
      Customizer<InferenceConfig> blankName = in -> in.property(" ", "v");
      Customizer<InferenceConfig> blankValue = in -> in.property("openai.seed", " ");

      assertThatThrownBy(() -> config.inference(blankName))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("name must not be blank");
      assertThatThrownBy(() -> config.inference(blankValue))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("value must not be blank");
    }

    /** The harness's report line names the properties and never prints a value (§6c). */
    @Test
    void the_report_line_names_properties_and_never_their_values() {
      InferenceOptions options =
          new InferenceOptions(
              "a-model", 10, Map.of("openai.user", "tenant-42", "anthropic.top_k", "5"));

      assertThat(DefaultDirectHarnessFactory.propertyNames(options))
          .isEqualTo(", properties [anthropic.top_k, openai.user]");
      assertThat(DefaultDirectHarnessFactory.propertyNames(InferenceOptions.of("a-model")))
          .isEmpty();
    }
  }
```

- [ ] **Step 2: Write the failing queued-door test**

`TerminationAndConfigurationTest.java` -- add imports `org.jwcarman.nessy.api.AgentType` (present), `org.jwcarman.nessy.inference.InferenceNarrator`, `org.jwcarman.nessy.inference.InferenceOptions`, `org.jwcarman.nessy.inference.InferenceProvider`, `org.jwcarman.nessy.inference.InferenceRequest`, then:

```java
  /** Remembers the terms of every call, and refuses one name the way a clash table would. */
  private static final class Judging implements InferenceProvider {
    final List<InferenceOptions> asked = new CopyOnWriteArrayList<>();

    @Override
    public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
      asked.add(request.options());
      return new InferenceResult.Answer(List.of(new Block.Text("a lake monster")));
    }

    @Override
    public void validate(InferenceOptions options) {
      if (options.properties().containsKey("test.model")) {
        throw new IllegalArgumentException(
            "property 'test.model' names what InferenceConfig.model already decides;"
                + " remove the property");
      }
    }
  }

  @Test
  void a_queued_agent_type_s_properties_reach_the_provider_and_a_clash_fails_the_build() {
    Judging provider = new Judging();
    EngineFixture judged = new EngineFixture(provider, (type, id, event) -> events.add(event));
    try {
      QueuedHarness<String> harness =
          judged
              .harnesses()
              .create(
                  new AgentType("chat-properties"),
                  config ->
                      config
                          .systemPrompt("You are a test assistant.")
                          .inference(in -> in.property("openai.seed", "7"))
                          .effects(e -> e.pollInterval(Duration.ofMillis(100))));
      harness.tell(new AgentId(UUID.randomUUID()), "hello");
      await().atMost(Duration.ofSeconds(20)).until(() -> !provider.asked.isEmpty());
      assertThat(provider.asked.getFirst().properties()).containsEntry("openai.seed", "7");

      var harnesses = judged.harnesses();
      Customizer<QueuedHarnessConfig<String>> clashing =
          config ->
              config
                  .systemPrompt("You are a test assistant.")
                  .inference(in -> in.property("test.model", "gpt-4o"));
      AgentType clashType = new AgentType("chat-properties-clash");
      assertThatThrownBy(() -> harnesses.create(clashType, clashing))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "agent type 'chat-properties-clash': property 'test.model' names what"
                  + " InferenceConfig.model already decides; remove the property");
    } finally {
      judged.close();
    }
  }
```

- [ ] **Step 3: Write the failing embedder tests**

`DefaultEmbedderFactoryTest.java` -- add imports `java.util.Map`, `static org.assertj.core.api.Assertions.assertThatThrownBy`, `org.jwcarman.nessy.api.Customizer`, `org.jwcarman.nessy.api.embedding.EmbedderConfig`; give `Asked` a validate that refuses one name:

```java
    @Override
    public void validate(EmbeddingOptions options) {
      if (options.properties().containsKey("test.model")) {
        throw new IllegalArgumentException("property 'test.model' is refused");
      }
    }
```

and add:

```java
  @Test
  @DisplayName("carry their properties to the provider")
  void carry_their_properties_to_the_provider() {
    Asked provider = new Asked();
    new DefaultEmbedderFactory(provider, "a-model")
        .create(c -> c.property("voyage.truncation", "false"))
        .embedDocument("anything");

    assertThat(provider.options.get().properties())
        .containsExactly(Map.entry("voyage.truncation", "false"));
  }

  @Test
  @DisplayName("are refused when the provider refuses their terms")
  void are_refused_when_the_provider_refuses_their_terms() {
    DefaultEmbedderFactory factory = new DefaultEmbedderFactory(new Asked(), "a-model");
    Customizer<EmbedderConfig> refused = c -> c.property("test.model", "x");

    assertThatThrownBy(() -> factory.create(refused))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("property 'test.model' is refused");
  }
```

- [ ] **Step 4: Run them to see them fail**

Run: `./mvnw -q -pl :nessy-engine -am test -Dtest='DefaultDirectHarnessTest,DefaultEmbedderFactoryTest' -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: compilation failure (`cannot find symbol: method property(String,String)`); `exit=1`.

- [ ] **Step 5: The API**

`InferenceConfig.java` -- add directly after `model(String)`:

```java
  /**
   * A setting the vendor understands and the neutral API does not name. The name is prefixed by
   * the adapter that reads it ({@code openai.reasoning.effort}); an adapter ignores every other
   * prefix, so an agent type may carry settings for several vendors at once. Repeatable; the last
   * value given for a name wins. Fixed when the harness is built, sent with every request.
   *
   * @throws IllegalArgumentException if either argument is blank
   */
  InferenceConfig property(String name, String value);
```

`EmbedderConfig.java` -- add after `dimension(int)`:

```java
  /**
   * A setting the vendor understands and this interface does not name, prefixed by the adapter
   * that reads it ({@code voyage.truncation}). Repeatable; the last value given for a name wins.
   *
   * @throws IllegalArgumentException if either argument is blank
   */
  EmbedderConfig property(String name, String value);
```

- [ ] **Step 6: The direct door**

`DefaultDirectHarnessConfig.Inference` -- add a field beside `maxTokens`, seed it in the constructor, implement the method, expose it (imports `java.util.Collections`, `java.util.LinkedHashMap`, `java.util.Map` if absent):

```java
    private final Map<String, String> properties = new LinkedHashMap<>();
```

In `Inference(@Nullable ProviderId provider, @Nullable InferenceOptions defaults)`, inside `if (defaults != null)`, after the `maxTokens` lines:

```java
        this.properties.putAll(defaults.properties());
```

After `maxTokens(int)`:

```java
    @Override
    public InferenceConfig property(String name, String value) {
      properties.put(requireNonBlank(name, "name"), requireNonBlank(value, "value"));
      return this;
    }
```

Beside `maxTokens()`:

```java
    Map<String, String> properties() {
      return Collections.unmodifiableMap(properties);
    }

    private static String requireNonBlank(String text, String argument) {
      Objects.requireNonNull(text, argument + " must not be null");
      if (text.isBlank()) {
        throw new IllegalArgumentException(argument + " must not be blank");
      }
      return text;
    }
```

`DefaultDirectHarnessFactory.build` -- replace the block from the `log.info(` call (line 239) through the end of the `log.info` statement with the options, the validation and the log line, and pass the resolved provider and the options into the handler (imports `java.util.TreeSet`, `org.jwcarman.nessy.inference.InferenceProvider`):

```java
    InferenceOptions options =
        new InferenceOptions(inference.modelName(), inference.maxTokens(), inference.properties());
    InferenceProvider provider = providers.resolve(agentType, providerId);
    validate(agentType, provider, options);
    log.info(
        "NESSY INFERENCE: agent type '{}' -> {} / {}, up to {} tokens{}",
        agentType.value(),
        providerId.value(),
        options.modelName(),
        options.maxTokens(),
        propertyNames(options));
```

and in the `new InferenceHandler(` arguments, `providers.resolve(agentType, providerId),` becomes `provider,` and `new InferenceOptions(inference.modelName(), inference.maxTokens()),` becomes `options,`. Add to the class:

```java
  /**
   * The adapter's say on the terms, before anything is built on them (spec §7c): a refusal fails
   * the build, named for the agent type, rather than the agent's first turn.
   */
  private static void validate(
      AgentType agentType, InferenceProvider provider, InferenceOptions options) {
    try {
      provider.validate(options);
    } catch (IllegalArgumentException refused) {
      throw new IllegalArgumentException(
          "agent type '" + agentType.value() + "': " + refused.getMessage(), refused);
    }
  }

  /** The report line's clause: names only, sorted, never a value (spec §6c). */
  static String propertyNames(InferenceOptions options) {
    return options.properties().isEmpty()
        ? ""
        : ", properties " + new TreeSet<>(options.properties().keySet());
  }
```

- [ ] **Step 7: The queued door**

`DefaultQueuedHarnessConfig.Inference` (imports `java.util.LinkedHashMap`, `java.util.Map`) -- the same field, the same seeding (inside `if (defaults.options() != null)`: `this.properties.putAll(defaults.options().properties());`), the same `property` override and `requireNonBlank` helper; `options()` becomes:

```java
    InferenceOptions options() {
      return new InferenceOptions(modelName, maxTokens, properties);
    }
```

`DefaultQueuedHarnessFactory` -- replace the `log.info(` statement at lines 197-202 with:

```java
    InferenceOptions options = inference.options();
    InferenceProvider provider = providers.resolve(agentType, providerId);
    validate(agentType, provider, options);
    log.info(
        "NESSY INFERENCE: agent type '{}' -> {} / {}, up to {} tokens{}",
        agentType.value(),
        providerId.value(),
        options.modelName(),
        options.maxTokens(),
        propertyNames(options));
```

in the `createInferenceHandler(` call pass `provider` where `providers.resolve(agentType, providerId)` is today, and add the same two private static helpers (`validate` and `propertyNames`) to this class, with the same javadoc (imports `java.util.TreeSet`, `org.jwcarman.nessy.inference.InferenceOptions` if absent).

- [ ] **Step 8: The embedder factory**

`DefaultEmbedderFactory` (imports `java.util.LinkedHashMap`, `java.util.Map`):

```java
  @Override
  public Embedder create(Customizer<EmbedderConfig> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    Settings settings = new Settings();
    customizer.customize(settings);
    EmbeddingOptions options = settings.options();
    provider.validate(options);
    return new DefaultEmbedder(provider, options);
  }
```

In `Settings`: a field `private final Map<String, String> properties = new LinkedHashMap<>();`, the method

```java
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
```

and `options()` passes `properties` as the third argument of `new EmbeddingOptions(...)`.

- [ ] **Step 9: Run them to see them pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-engine -am test -Dtest='DefaultDirectHarnessTest,DefaultEmbedderFactoryTest,TerminationAndConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: `exit=0`. (`TerminationAndConfigurationTest` starts a Postgres container through `EngineFixture`; Docker must be running.)

- [ ] **Step 10: Full gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?`
Expected: `exit=0`.

```bash
cd /Users/jcarman/IdeaProjects/nessy-props
git add nessy-api/src/main nessy-engine/src
git commit -m "feat: an agent type carries vendor properties, and its provider validates them at build

InferenceConfig.property and EmbedderConfig.property; both doors seed the
map from the factory defaults, send it in InferenceOptions, ask the provider
to validate it before building the handler, and name a refusal by agent type.
The harness line names the properties, never their values; no event does.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 3: OpenAI chat -- the `openai.` names, the pass-through, and `openai.tools.strict`

Spec §6a, §8, §9a, §10, §13a. Suggested implementer: Sonnet. Review: Sonnet.

**Files:**
- Create: `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiProperties.java`
- Modify: `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiChatRequests.java`
- Modify: `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiChatInferenceProvider.java`
- Modify: `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiChatProviderConfig.java`
- Test: `nessy-inference/openai/src/test/java/org/jwcarman/nessy/inference/openai/OpenAiChatRequestsTest.java`
- Test: `nessy-inference/openai/src/test/java/org/jwcarman/nessy/inference/openai/OpenAiChatInferenceProviderTest.java`
- Test: `nessy-inference/openai/src/test/java/org/jwcarman/nessy/inference/openai/OpenAiChatProviderConfigTest.java`

**Interfaces:**
- Consumes: `VendorProperties.*` (Task 1); `InferenceOptions.properties()`; `InferenceProvider.validate` (Task 1); `OpenAiResponsesSchemas.project(String, JsonMapper)` returning `Projected(Map<String, Object> schema, Optional<String> refusedKeyword)` with `strict()` (existing).
- Produces (package-private, Task 4 extends this class):
  - `OpenAiProperties.PREFIX = "openai."`, constants `EFFORT = "reasoning.effort"`, `SUMMARY = "reasoning.summary"`, `STRICT = "tools.strict"`, `SERVICE_TIER = "service_tier"`
  - `record OpenAiProperties.Read(Optional<String> effort, Optional<String> summary, boolean strict, Optional<String> serviceTier, Map<String, Object> passThrough)`
  - `static Read OpenAiProperties.chat(Map<String, String> merged, JsonMapper mapper)`
  - `static void OpenAiProperties.requireOwn(Map<String, String> properties)` -- a provider-level entry outside `openai.` is refused naming it and the prefix
  - `static void OpenAiProperties.logIgnored(Map<String, String> merged)` -- one `DEBUG` line naming entries under other prefixes
  - `static Read OpenAiProperties.read(Map<String, String> own, JsonMapper mapper)` -- the known-name reads and the pass-through tree, over an already-filtered map (used by `chat` here and `responses` in Task 4)
  - `static ChatCompletionCreateParams OpenAiChatRequests.toParams(InferenceRequest, Map<String, String> providerProperties, JsonMapper)` (the two-argument form stays, passing `Map.of()`)
- Produces (public): `OpenAiChatProviderConfig property(String name, String value)`, `OpenAiChatProviderConfig properties(Map<String, String> properties)`; `OpenAiChatInferenceProvider.validate(InferenceOptions)`.

- [ ] **Step 1: Write the failing projection tests**

`OpenAiChatRequestsTest.java` -- add imports `ch.qos.logback.classic.Level`, `ch.qos.logback.classic.Logger`, `ch.qos.logback.classic.spi.ILoggingEvent`, `ch.qos.logback.core.read.ListAppender`, `com.fasterxml.jackson.core.JsonProcessingException`, `com.openai.core.JsonValue`, `com.openai.core.ObjectMappers`, `com.openai.models.FunctionDefinition`, `com.openai.models.ReasoningEffort`, `java.io.UncheckedIOException`, `java.util.Map`, `org.slf4j.LoggerFactory`, `tools.jackson.core.type.TypeReference`, `static org.assertj.core.api.Assertions.assertThatThrownBy`; then add two nested classes at the end:

```java
  /** What the SDK would put on the wire for {@code value}, read back as plain maps and lists. */
  private static Map<String, Object> sent(Object value) {
    try {
      return MAPPER.readValue(
          ObjectMappers.jsonMapper().writeValueAsString(value), new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Nested
  class TheVendorProperties {

    private static InferenceRequest carrying(Map<String, String> properties) {
      return new InferenceRequest(
          SYSTEM,
          InferenceContext.of(List.of(open(1, "hi"))),
          Toolset.none(),
          new InferenceOptions("gpt-4o", 1024, properties));
    }

    private static ChatCompletionCreateParams paramsFor(Map<String, String> agentType) {
      return OpenAiChatRequests.toParams(carrying(agentType), MAPPER);
    }

    @Test
    void a_reasoning_effort_lands_in_the_flat_reasoning_effort_field() {
      ChatCompletionCreateParams params = paramsFor(Map.of("openai.reasoning.effort", "high"));

      assertThat(params.reasoningEffort().map(ReasoningEffort::asString)).contains("high");
      assertThat(params._additionalBodyProperties()).isEmpty();
    }

    /** The vocabulary is the vendor's: a level Nessy has never heard of is sent as written. */
    @Test
    void an_effort_level_nessy_does_not_know_is_sent_as_written() {
      ChatCompletionCreateParams params = paramsFor(Map.of("openai.reasoning.effort", "ultra"));

      assertThat(params.reasoningEffort().map(ReasoningEffort::asString)).contains("ultra");
    }

    @Test
    void a_service_tier_lands_in_its_typed_field() {
      ChatCompletionCreateParams params = paramsFor(Map.of("openai.service_tier", "flex"));

      assertThat(params.serviceTier().map(ChatCompletionCreateParams.ServiceTier::asString))
          .contains("flex");
    }

    @Test
    void an_unknown_name_passes_through_as_a_typed_literal_nested_by_path() {
      ChatCompletionCreateParams params =
          paramsFor(
              Map.of(
                  "openai.temperature", "0.2",
                  "openai.store", "false",
                  "openai.metadata.team", "billing"));

      Map<String, JsonValue> body = params._additionalBodyProperties();
      assertThat(body.get("temperature").asNumber().map(Number::doubleValue)).contains(0.2);
      assertThat(body.get("store").asBoolean()).contains(false);
      assertThat(body.get("metadata").asObject().orElseThrow().get("team").asString())
          .contains("billing");
    }

    /** A raw spelling alone is simply a pass-through: only beside its known name is it a clash. */
    @Test
    void the_raw_reasoning_effort_alone_passes_through() {
      ChatCompletionCreateParams params = paramsFor(Map.of("openai.reasoning_effort", "low"));

      assertThat(params._additionalBodyProperties().get("reasoning_effort").asString())
          .contains("low");
    }

    @Test
    void another_prefix_is_not_sent() {
      ChatCompletionCreateParams params = paramsFor(Map.of("anthropic.top_k", "5"));

      assertThat(params._additionalBodyProperties()).isEmpty();
      assertThat(params.reasoningEffort()).isEmpty();
    }

    @Test
    void an_agent_type_entry_overrides_the_same_name_given_to_the_provider() {
      ChatCompletionCreateParams params =
          OpenAiChatRequests.toParams(
              carrying(Map.of("openai.seed", "2")), Map.of("openai.seed", "1"), MAPPER);

      assertThat(params._additionalBodyProperties().get("seed").asNumber().map(Number::intValue))
          .contains(2);
    }

    @Test
    void a_clash_with_a_typed_setting_is_refused_naming_both() {
      InferenceRequest request = carrying(Map.of("openai.max_completion_tokens", "10"));

      assertThatThrownBy(() -> OpenAiChatRequests.toParams(request, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "property 'openai.max_completion_tokens' names what InferenceConfig.maxTokens"
                  + " already decides; remove the property");
    }

    @Test
    void the_model_is_the_first_entry_of_the_clash_table() {
      InferenceRequest request = carrying(Map.of("openai.model", "gpt-4o-mini"));

      assertThatThrownBy(() -> OpenAiChatRequests.toParams(request, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'openai.model'")
          .hasMessageContaining("InferenceConfig.model");
    }

    @Test
    void the_raw_spelling_beside_its_known_name_is_refused() {
      InferenceRequest request =
          carrying(Map.of("openai.reasoning.effort", "high", "openai.reasoning_effort", "low"));

      assertThatThrownBy(() -> OpenAiChatRequests.toParams(request, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'openai.reasoning_effort'")
          .hasMessageContaining("'openai.reasoning.effort'");
    }

    @Test
    void a_reasoning_summary_is_refused_because_this_wire_cannot_carry_one() {
      InferenceRequest request = carrying(Map.of("openai.reasoning.summary", "auto"));

      assertThatThrownBy(() -> OpenAiChatRequests.toParams(request, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'openai.reasoning.summary'")
          .hasMessageContaining("openai-responses");
    }

    @Test
    void a_non_boolean_strict_is_refused_naming_the_value() {
      InferenceRequest request = carrying(Map.of("openai.tools.strict", "yes"));

      assertThatThrownBy(() -> OpenAiChatRequests.toParams(request, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property 'openai.tools.strict' must be true or false, was 'yes'");
    }

    @Test
    void a_name_with_no_prefix_is_refused() {
      InferenceRequest request = carrying(Map.of("temperature", "0.2"));

      assertThatThrownBy(() -> OpenAiChatRequests.toParams(request, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'temperature' has no prefix");
    }
  }

  /** Spec §10: the Responses record's strict rewrite, on this wire behind openai.tools.strict. */
  @Nested
  class StrictTools {

    private static final String LOOKUP_SCHEMA =
        """
        {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
         "properties":{"q":{"type":"string"},"n":{"type":"integer"}},"required":["q"]}""";

    private static final String NESTED_SCHEMA =
        """
        {"type":"object","properties":{"where":{"$ref":"#/$defs/Place"}},"required":["where"],
         "$defs":{"Place":{"type":"object",
                           "properties":{"city":{"type":"string"},"zip":{"type":"string"}},
                           "required":["city"]}}}""";

    private static final String SEALED_SCHEMA =
        """
        {"oneOf":[{"type":"object","properties":{"type":{"const":"Restart"}},"required":["type"]},
                  {"type":"object","properties":{"type":{"const":"Shutdown"}},"required":["type"]}]}""";

    private static ToolOffer offer(String name, String schema) {
      return new ToolOffer(new ToolName(name), "does " + name, new JsonSchema(schema));
    }

    private static List<FunctionDefinition> functionsFor(
        List<ToolOffer> offers, Map<String, String> properties) {
      InferenceRequest request =
          new InferenceRequest(
              SYSTEM,
              InferenceContext.of(List.of(open(1, "hi"))),
              Toolset.of(offers),
              new InferenceOptions("gpt-4o", 1024, properties));
      return OpenAiChatRequests.toParams(request, MAPPER).tools().orElseThrow().stream()
          .map(tool -> tool.asFunction().function())
          .toList();
    }

    private static final Map<String, String> STRICT = Map.of("openai.tools.strict", "true");

    @Test
    void without_the_property_a_tool_goes_as_generated_and_says_nothing_about_strict() {
      FunctionDefinition function =
          functionsFor(List.of(offer("lookup", LOOKUP_SCHEMA)), Map.of()).getFirst();

      assertThat(function.strict()).isEmpty();
      assertThat(sent(function.parameters().orElseThrow()))
          .containsEntry("required", List.of("q"))
          .doesNotContainKey("additionalProperties");
    }

    @Test
    void with_it_a_tool_is_strict_over_the_rewritten_schema() {
      FunctionDefinition function =
          functionsFor(List.of(offer("lookup", LOOKUP_SCHEMA)), STRICT).getFirst();

      assertThat(function.strict()).contains(true);
      Map<String, Object> schema = sent(function.parameters().orElseThrow());
      assertThat(schema).containsEntry("required", List.of("q", "n"));
      assertThat(schema).containsEntry("additionalProperties", false);
    }

    @Test
    void an_optional_component_is_widened_to_admit_null() {
      Map<String, Object> schema =
          sent(
              functionsFor(List.of(offer("lookup", LOOKUP_SCHEMA)), STRICT)
                  .getFirst()
                  .parameters()
                  .orElseThrow());

      assertThat(schema)
          .extractingByKey("properties", InstanceOfAssertFactories.MAP)
          .extractingByKey("n")
          .isEqualTo(Map.of("type", List.of("integer", "null")));
    }

    @Test
    void definitions_are_walked_too() {
      Map<String, Object> schema =
          sent(
              functionsFor(List.of(offer("locate", NESTED_SCHEMA)), STRICT)
                  .getFirst()
                  .parameters()
                  .orElseThrow());

      assertThat(schema)
          .extractingByKey("$defs", InstanceOfAssertFactories.MAP)
          .extractingByKey("Place", InstanceOfAssertFactories.MAP)
          .containsEntry("required", List.of("city", "zip"))
          .containsEntry("additionalProperties", false);
    }

    @Test
    void a_schema_strict_mode_cannot_express_goes_as_generated_with_a_warning_and_its_neighbour_stays_strict() {
      Logger logger = (Logger) LoggerFactory.getLogger(OpenAiChatRequests.class);
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
      List<FunctionDefinition> functions;
      try {
        functions =
            functionsFor(
                List.of(offer("restart", SEALED_SCHEMA), offer("lookup", LOOKUP_SCHEMA)), STRICT);
      } finally {
        logger.detachAppender(appender);
      }

      assertThat(functions.get(0).strict()).contains(false);
      assertThat(sent(functions.get(0).parameters().orElseThrow())).containsKey("oneOf");
      assertThat(functions.get(1).strict()).contains(true);
      assertThat(appender.list)
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("restart").contains("oneOf");
              });
    }

    @Test
    void the_schema_the_offer_carries_is_unchanged_by_the_rewrite() {
      ToolOffer lookup = offer("lookup", LOOKUP_SCHEMA);

      functionsFor(List.of(lookup), STRICT);

      assertThat(lookup.schema().json()).isEqualTo(LOOKUP_SCHEMA);
    }
  }
```

(`InstanceOfAssertFactories.MAP` reads a nested schema object as a `MapAssert<Object, Object>`, with no raw type and no cast; add `org.assertj.core.api.InstanceOfAssertFactories` to the imports.)

- [ ] **Step 2: Write the failing provider and config tests**

`OpenAiChatInferenceProviderTest.java` -- add (imports `java.util.Map`, `static org.assertj.core.api.Assertions.assertThatThrownBy`, `org.jwcarman.nessy.inference.InferenceOptions` if absent) a nested class:

```java
  @Nested
  class ItsVendorProperties {

    @Test
    void a_property_on_the_config_reaches_every_request() {
      var captured = new ChatCompletionCreateParams[1];
      new OpenAiChatProviderConfig()
          .property("openai.seed", "42")
          .client(
              fakeClient(
                  params -> {
                    captured[0] = params;
                    return completionOf(
                        ChatCompletionMessage.builder()
                            .content("ok")
                            .refusal(Optional.<String>empty())
                            .build());
                  }))
          .build()
          .infer(REQUEST);

      assertThat(captured[0]._additionalBodyProperties().get("seed").asNumber())
          .map(Number::intValue)
          .contains(42);
    }

    @Test
    void validate_refuses_a_clash_between_the_agent_type_and_the_wire() {
      OpenAiChatInferenceProvider provider =
          new OpenAiChatProviderConfig().client(fakeClient(params -> null)).build();
      InferenceOptions options =
          new InferenceOptions("gpt-4o", 1024, Map.of("openai.messages", "[]"));

      assertThatThrownBy(() -> provider.validate(options))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'openai.messages'");
    }

    @Test
    void validate_accepts_other_vendors_properties() {
      OpenAiChatInferenceProvider provider =
          new OpenAiChatProviderConfig().client(fakeClient(params -> null)).build();
      InferenceOptions options =
          new InferenceOptions("gpt-4o", 1024, Map.of("anthropic.thinking.budget_tokens", "9"));

      provider.validate(options);

      assertThat(options.properties()).hasSize(1);
    }
  }
```

(`REQUEST` and `completionOf` are the test class's existing fixtures; check `REQUEST`'s name in the file and use it.)

`OpenAiChatProviderConfigTest.java` -- add (imports `java.util.Map`, `org.jwcarman.nessy.api.Customizer`):

```java
  @Test
  void a_property_under_another_prefix_is_refused_at_build_naming_the_prefix() {
    Customizer<OpenAiChatProviderConfig> customizer =
        c -> c.apiKey("test-key").property("anthropic.thinking.budget_tokens", "8192");

    assertThatThrownBy(() -> OpenAiChatInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'anthropic.thinking.budget_tokens'")
        .hasMessageContaining("'openai.'");
  }

  @Test
  void a_clash_is_refused_at_build() {
    Customizer<OpenAiChatProviderConfig> customizer =
        c -> c.apiKey("test-key").properties(Map.of("openai.model", "gpt-4o"));

    assertThatThrownBy(() -> OpenAiChatInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("InferenceConfig.model");
  }

  @Test
  void a_blank_value_is_refused_at_once_naming_the_property() {
    Customizer<OpenAiChatProviderConfig> customizer =
        c -> c.apiKey("test-key").properties(Map.of("openai.store", ""));

    assertThatThrownBy(() -> OpenAiChatInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.store'");
  }

  @Test
  void properties_under_its_own_prefix_build_a_provider() {
    OpenAiChatInferenceProvider provider =
        OpenAiChatInferenceProvider.of(
            c -> c.apiKey("test-key").property("openai.tools.strict", "true"));

    assertThatCode(provider::close).doesNotThrowAnyException();
  }
```

- [ ] **Step 3: Run them to see them fail**

Run: `./mvnw -q -pl :nessy-inference-openai -am test -Dtest='OpenAiChatRequestsTest,OpenAiChatInferenceProviderTest,OpenAiChatProviderConfigTest' -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: compilation failure (`cannot find symbol: method property`, `toParams(InferenceRequest,Map,JsonMapper)`); `exit=1`.

- [ ] **Step 4: Create `OpenAiProperties`**

`OpenAiProperties.java` (license header, then):

```java
package org.jwcarman.nessy.inference.openai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.vendor.VendorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code openai.} vendor properties (spec §9a, §9b): the names both OpenAI wires parse, each
 * wire's clash table, and everything else passed through into the request body. One reading for
 * both wires, so an agent type switching between them keeps its settings.
 */
final class OpenAiProperties {

  static final String PREFIX = "openai.";
  static final String EFFORT = "reasoning.effort";
  static final String SUMMARY = "reasoning.summary";
  static final String STRICT = "tools.strict";
  static final String SERVICE_TIER = "service_tier";

  static final Set<String> KNOWN = Set.of(EFFORT, SUMMARY, STRICT, SERVICE_TIER);

  static final String CONVERSATION = "the conversation the engine assembles";
  static final String TOOLS = "the tools the harness binds";
  static final String TOOL_CHOICE = "the tool choice the engine makes";
  static final String SHAPE = "the answer's shape the harness asks for";
  static final String STREAMING = "the adapter, which always streams";

  /** What the chat wire's typed settings, or the adapter itself, already decide (§9a). */
  private static final Map<String, String> CHAT_CLASHES =
      Map.of(
          "model", "InferenceConfig.model",
          "messages", CONVERSATION,
          "max_completion_tokens", "InferenceConfig.maxTokens",
          "max_tokens", "InferenceConfig.maxTokens",
          "tools", TOOLS,
          "tool_choice", TOOL_CHOICE,
          "response_format", SHAPE,
          "stream", STREAMING,
          "stream_options", "the adapter, which always asks for usage on the stream");

  private static final Logger log = LoggerFactory.getLogger(OpenAiProperties.class);

  private OpenAiProperties() {}

  /**
   * The {@code openai.} properties once read.
   *
   * @param strict whether function tools go out strict; always false on the chat wire unless
   *     asked for
   * @param passThrough every other name under the prefix, nested by path, values as JSON literals
   */
  record Read(
      Optional<String> effort,
      Optional<String> summary,
      boolean strict,
      Optional<String> serviceTier,
      Map<String, Object> passThrough) {}

  /** The chat wire's reading of the merged provider and agent-type map (§9a). */
  static Read chat(Map<String, String> merged, JsonMapper mapper) {
    Map<String, String> own = VendorProperties.under(merged, PREFIX);
    Map<String, String> clashes = new LinkedHashMap<>(CHAT_CLASHES);
    if (own.containsKey(EFFORT)) {
      clashes.put("reasoning_effort", "property '" + PREFIX + EFFORT + "'");
    }
    VendorProperties.refuseClashes(PREFIX, own, clashes);
    if (own.containsKey(SUMMARY)) {
      throw new IllegalArgumentException(
          "property '"
              + PREFIX
              + SUMMARY
              + "' cannot be sent on the openai-chat wire, which has no reasoning summary;"
              + " the openai-responses wire carries it");
    }
    return read(own, mapper);
  }

  /** The known names parsed, the rest nested, over a map already filtered to this prefix. */
  static Read read(Map<String, String> own, JsonMapper mapper) {
    Optional<String> effort = string(own, EFFORT);
    Optional<String> summary = string(own, SUMMARY);
    Optional<String> serviceTier = string(own, SERVICE_TIER);
    boolean strict =
        own.containsKey(STRICT) && VendorProperties.requireBoolean(PREFIX + STRICT, own.get(STRICT));
    Map<String, String> rest = new LinkedHashMap<>(own);
    rest.keySet().removeAll(KNOWN);
    return new Read(
        effort, summary, strict, serviceTier, VendorProperties.nest(PREFIX, rest, mapper));
  }

  /** A provider is one adapter: a provider-level entry under another prefix is a mistake (§6a). */
  static void requireOwn(Map<String, String> properties) {
    for (String name : properties.keySet()) {
      if (!name.startsWith(PREFIX)) {
        throw new IllegalArgumentException(
            "property '"
                + name
                + "' is not under '"
                + PREFIX
                + "'; a provider reads only its own prefix");
      }
    }
  }

  /** Another adapter's settings are the design working, so they are named at DEBUG and no louder. */
  static void logIgnored(Map<String, String> merged) {
    if (log.isDebugEnabled()) {
      List<String> others = merged.keySet().stream().filter(n -> !n.startsWith(PREFIX)).toList();
      if (!others.isEmpty()) {
        log.debug("NESSY INFERENCE: properties for other adapters, ignored here: {}", others);
      }
    }
  }

  private static Optional<String> string(Map<String, String> own, String name) {
    return Optional.ofNullable(own.get(name))
        .map(value -> VendorProperties.requireString(PREFIX + name, value));
  }
}
```

(`Map.of` takes at most ten pairs; `CHAT_CLASHES` has nine.)

- [ ] **Step 5: The chat projection reads them**

`OpenAiChatRequests.java` (imports `com.openai.models.ReasoningEffort`, `org.jwcarman.nessy.vendor.VendorProperties`, `org.slf4j.Logger`, `org.slf4j.LoggerFactory`):

Add the logger field below the constructor's class line:

```java
  private static final Logger log = LoggerFactory.getLogger(OpenAiChatRequests.class);
```

Replace `toParams(InferenceRequest request, JsonMapper mapper)` with the pair below (the existing javadoc stays on the three-argument form, with `@param providerProperties` added to it):

```java
  /** No provider-level properties: the agent type's alone. */
  static ChatCompletionCreateParams toParams(InferenceRequest request, JsonMapper mapper) {
    return toParams(request, Map.of(), mapper);
  }

  /**
   * @param providerProperties the provider's own {@code openai.} map, overlaid here by the agent
   *     type's (spec §7a)
   * @param mapper ... (the existing text)
   */
  static ChatCompletionCreateParams toParams(
      InferenceRequest request, Map<String, String> providerProperties, JsonMapper mapper) {
    InferenceOptions options = request.options();
    OpenAiProperties.Read read =
        OpenAiProperties.chat(
            VendorProperties.merge(providerProperties, options.properties()), mapper);

    // ... the existing body, unchanged down to the tools line, which becomes:
    request
        .toolset()
        .offers()
        .forEach(offer -> builder.addTool(toFunctionTool(offer, read.strict(), mapper)));
    chooseTool(builder, request.toolset().offers(), request.toolset().choice());
    request.outputSchema().ifPresent(schema -> constrainAnswer(builder, schema, mapper));
    read.effort().ifPresent(effort -> builder.reasoningEffort(ReasoningEffort.of(effort)));
    read.serviceTier()
        .ifPresent(tier -> builder.serviceTier(ChatCompletionCreateParams.ServiceTier.of(tier)));
    read.passThrough()
        .forEach((name, value) -> builder.putAdditionalBodyProperty(name, JsonValue.from(value)));
    return builder.build();
  }
```

(The `// ...` line stands for the existing statements between `InferenceOptions options = request.options();` and the tools line -- the messages, the builder, `streamOptions`, `maxCompletionTokens` -- kept exactly; do not re-declare `options`.)

Replace `toFunctionTool(ToolOffer offer, JsonMapper mapper)` (keep its javadoc, add a sentence) with:

```java
  /**
   * One tool, as this wire describes one. {@code function} is the only kind this API has ever had
   * for a described tool, and it is still required on every entry. Under {@code
   * openai.tools.strict=true} the schema goes out rewritten for strict mode (spec §10); a schema
   * strict mode cannot express goes as generated with {@code strict: false}, and says so.
   */
  private static ChatCompletionTool toFunctionTool(
      ToolOffer offer, boolean strict, JsonMapper mapper) {
    FunctionDefinition.Builder function =
        FunctionDefinition.builder().name(offer.name().value()).description(offer.description());
    if (strict) {
      OpenAiResponsesSchemas.Projected projected =
          OpenAiResponsesSchemas.project(offer.schema().json(), mapper);
      projected
          .refusedKeyword()
          .ifPresent(
              keyword ->
                  log.warn(
                      "Tool {} is offered without strict mode: its schema uses {}, which strict"
                          + " mode cannot express",
                      offer.name().value(),
                      keyword));
      FunctionParameters.Builder parameters = FunctionParameters.builder();
      projected
          .schema()
          .forEach((name, value) -> parameters.putAdditionalProperty(name, JsonValue.from(value)));
      function.parameters(parameters.build()).strict(projected.strict());
    } else {
      function.parameters(toFunctionParameters(offer.schema().json(), mapper));
    }
    return ChatCompletionTool.ofFunction(
        ChatCompletionFunctionTool.builder().function(function.build()).build());
  }
```

- [ ] **Step 6: The provider holds its own map and validates**

`OpenAiChatInferenceProvider.java` (imports `java.util.Map`, `org.jwcarman.nessy.inference.InferenceOptions`, `org.jwcarman.nessy.vendor.VendorProperties`):

```java
  /** The provider's own {@code openai.} properties, checked at build; the agent type's overlay them. */
  private final Map<String, String> properties;

  OpenAiChatInferenceProvider(
      OpenAIClient client, String vendor, boolean ownsClient, JsonMapper mapper) {
    this(client, vendor, ownsClient, mapper, Map.of());
  }

  OpenAiChatInferenceProvider(
      OpenAIClient client,
      String vendor,
      boolean ownsClient,
      JsonMapper mapper,
      Map<String, String> properties) {
    this.client = client;
    this.vendor = Objects.requireNonNull(vendor, "vendor must not be null");
    this.ownsClient = ownsClient;
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    this.properties = Map.copyOf(properties);
  }
```

(`Map.copyOf` is fine here: the provider's map is only ever merged, never iterated for a message. Replace the existing four-argument constructor body with the delegation shown.)

In `infer`, `OpenAiChatRequests.toParams(request, mapper)` becomes `OpenAiChatRequests.toParams(request, properties, mapper)`. Add:

```java
  /**
   * Reads the merged properties exactly as a request would, so a clash, a bad value or a name this
   * wire cannot carry fails the harness build rather than its first turn (spec §7c).
   */
  @Override
  public void validate(InferenceOptions options) {
    Map<String, String> merged = VendorProperties.merge(properties, options.properties());
    OpenAiProperties.chat(merged, mapper);
    OpenAiProperties.logIgnored(merged);
  }
```

- [ ] **Step 7: The config takes properties**

`OpenAiChatProviderConfig.java` (imports `java.util.Collections`, `java.util.LinkedHashMap`, `java.util.Map`, `org.jwcarman.nessy.vendor.VendorProperties`):

```java
  private final Map<String, String> properties = new LinkedHashMap<>();

  /**
   * A vendor property this provider sends with every request (spec §6a) -- {@code
   * openai.tools.strict}, {@code openai.reasoning.effort}, or any request field under {@code
   * openai.}, passed through. An agent type's own property of the same name overrides it.
   * Repeatable; the last value given for a name wins. A name under another prefix, or one that
   * names what a typed setting decides, fails at build.
   */
  public OpenAiChatProviderConfig property(String name, String value) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    properties.put(name, VendorProperties.requireString(name, value));
    return this;
  }

  /** {@link #property(String, String)} for each entry, as Boot binds them. */
  public OpenAiChatProviderConfig properties(Map<String, String> properties) {
    Objects.requireNonNull(properties, "properties must not be null");
    properties.forEach(this::property);
    return this;
  }
```

`build()` checks the map before any client is made, then hands it over:

```java
  OpenAiChatInferenceProvider build() {
    OpenAiProperties.requireOwn(properties);
    OpenAiProperties.chat(properties, mapper);
    Map<String, String> own = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    if (client != null) {
      return new OpenAiChatInferenceProvider(client, vendor, false, mapper, own);
    }
    return new OpenAiChatInferenceProvider(
        OpenAiClients.build(useEnv, apiKey, baseUrl, organization, timeout),
        vendor,
        true,
        mapper,
        own);
  }
```

- [ ] **Step 8: Run them to see them pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-inference-openai -am test; echo exit=$?`
Expected: `exit=0` (the whole module: `OpenAiCloseOwnershipTest` still uses the four-argument constructor).

- [ ] **Step 9: Full gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?`
Expected: `exit=0`.

```bash
cd /Users/jcarman/IdeaProjects/nessy-props
git add nessy-inference/openai/src
git commit -m "feat: the chat adapter reads openai. properties, and strict tools are one of them

openai.reasoning.effort and openai.service_tier land in their typed fields,
openai.tools.strict=true sends every function tool strict over the rewritten
schema, and every other openai. name passes through as a JSON literal. A
clash, a bad value or openai.reasoning.summary fails at build.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 4: OpenAI Responses -- the `reasoning` object, and a strictness it cannot turn off

Spec §6a, §9b, §13a; the Responses record's §5g made concrete. Suggested implementer: Sonnet. Review: Sonnet.

**Files:**
- Modify: `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiProperties.java`
- Modify: `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiResponsesRequests.java`
- Modify: `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiResponsesInferenceProvider.java`
- Modify: `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiResponsesProviderConfig.java`
- Test: `nessy-inference/openai/src/test/java/org/jwcarman/nessy/inference/openai/OpenAiResponsesRequestsTest.java`
- Test: `nessy-inference/openai/src/test/java/org/jwcarman/nessy/inference/openai/OpenAiResponsesProviderConfigTest.java`

**Interfaces:**
- Consumes: `OpenAiProperties.PREFIX`, `EFFORT`, `SUMMARY`, `STRICT`, `SERVICE_TIER`, `KNOWN`, the description constants, `Read`, `read(Map, JsonMapper)`, `requireOwn`, `logIgnored` (Task 3); `VendorProperties.*` (Task 1); `ResponseStreams.client(Function<ResponseCreateParams, List<ResponseStreamEvent>>)`, `ResponseStreams.eventsOf(Map)`, `ResponseStreams.completed(List)`, `ResponseStreams.message(String, String)` (existing test helpers).
- Produces (package-private): `static OpenAiProperties.Read OpenAiProperties.responses(Map<String, String> merged, JsonMapper mapper)`; `static ResponseCreateParams OpenAiResponsesRequests.toParams(InferenceRequest, String vendor, Map<String, String> providerProperties, JsonMapper)` (the three-argument form stays, passing `Map.of()`).
- Produces (public): `OpenAiResponsesProviderConfig property(String, String)`, `properties(Map<String, String>)`; `OpenAiResponsesInferenceProvider.validate(InferenceOptions)`.

- [ ] **Step 1: Write the failing projection tests**

`OpenAiResponsesRequestsTest.java` -- add imports `com.openai.core.JsonValue`, `com.openai.models.Reasoning`, `com.openai.models.ReasoningEffort`, `static org.assertj.core.api.Assertions.assertThatThrownBy` (as needed), and a nested class:

```java
  @Nested
  class TheVendorProperties {

    private static InferenceRequest carrying(Map<String, String> agentType) {
      return new InferenceRequest(
          SYSTEM,
          InferenceContext.of(List.of(open(1, "hi"))),
          Toolset.none(),
          new InferenceOptions("gpt-6-sol", 1024, agentType));
    }

    private static ResponseCreateParams paramsFor(Map<String, String> agentType) {
      return params(carrying(agentType));
    }

    @Test
    void an_effort_builds_the_reasoning_object() {
      Reasoning reasoning =
          paramsFor(Map.of("openai.reasoning.effort", "high")).reasoning().orElseThrow();

      assertThat(reasoning.effort().map(ReasoningEffort::asString)).contains("high");
      assertThat(reasoning.summary()).isEmpty();
    }

    @Test
    void a_summary_joins_the_same_reasoning_object() {
      Reasoning reasoning =
          paramsFor(Map.of("openai.reasoning.effort", "low", "openai.reasoning.summary", "auto"))
              .reasoning()
              .orElseThrow();

      assertThat(reasoning.effort().map(ReasoningEffort::asString)).contains("low");
      assertThat(reasoning.summary().map(Reasoning.Summary::asString)).contains("auto");
    }

    /** §5g: no reasoning object unless one of the two names asks for it. */
    @Test
    void without_either_reasoning_name_no_reasoning_object_is_sent() {
      assertThat(paramsFor(Map.of("openai.seed", "1")).reasoning()).isEmpty();
    }

    @Test
    void a_service_tier_lands_in_its_typed_field() {
      assertThat(
              paramsFor(Map.of("openai.service_tier", "flex"))
                  .serviceTier()
                  .map(ResponseCreateParams.ServiceTier::asString))
          .contains("flex");
    }

    /** Strict is what this wire always does, so asking for it is accepted and changes nothing. */
    @Test
    void strict_true_is_accepted_as_what_this_wire_already_does() {
      ResponseCreateParams params = paramsFor(Map.of("openai.tools.strict", "true"));

      assertThat(params._additionalBodyProperties()).isEmpty();
    }

    @Test
    void strict_false_is_refused_because_this_wire_is_strict_regardless() {
      InferenceRequest request = carrying(Map.of("openai.tools.strict", "false"));

      assertThatThrownBy(() -> OpenAiResponsesRequests.toParams(request, VENDOR, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'openai.tools.strict'")
          .hasMessageContaining("strict regardless");
    }

    @Test
    void an_unknown_name_passes_through_nested_by_path() {
      Map<String, JsonValue> body =
          paramsFor(Map.of("openai.metadata.team", "billing", "openai.top_p", "0.9"))
              ._additionalBodyProperties();

      assertThat(body.get("metadata").asObject().orElseThrow().get("team").asString())
          .contains("billing");
      assertThat(body.get("top_p").asNumber().map(Number::doubleValue)).contains(0.9);
    }

    @Test
    void another_prefix_is_not_sent() {
      assertThat(paramsFor(Map.of("anthropic.top_k", "5"))._additionalBodyProperties()).isEmpty();
    }

    @Test
    void an_agent_type_entry_overrides_the_same_name_given_to_the_provider() {
      ResponseCreateParams params =
          OpenAiResponsesRequests.toParams(
              carrying(Map.of("openai.reasoning.effort", "high")),
              VENDOR,
              Map.of("openai.reasoning.effort", "low"),
              MAPPER);

      assertThat(params.reasoning().orElseThrow().effort().map(ReasoningEffort::asString))
          .contains("high");
    }

    @Test
    void the_model_is_refused_as_what_a_typed_setting_decides() {
      InferenceRequest request = carrying(Map.of("openai.model", "gpt-4o"));

      assertThatThrownBy(() -> OpenAiResponsesRequests.toParams(request, VENDOR, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'openai.model'")
          .hasMessageContaining("InferenceConfig.model");
    }

    /** The fields the Responses record fixes on purpose are refused with its reason. */
    @Test
    void store_is_refused_because_the_event_log_is_the_only_conversation() {
      InferenceRequest request = carrying(Map.of("openai.store", "true"));

      assertThatThrownBy(() -> OpenAiResponsesRequests.toParams(request, VENDOR, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'openai.store'")
          .hasMessageContaining("the event log is the only conversation");
    }

    @Test
    void a_previous_response_is_refused_for_the_same_reason() {
      InferenceRequest request = carrying(Map.of("openai.previous_response_id", "resp_1"));

      assertThatThrownBy(() -> OpenAiResponsesRequests.toParams(request, VENDOR, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("the event log is the only conversation");
    }

    /** Review Focus 3: a typed reasoning object and a pass-through one cannot both be sent. */
    @Test
    void a_pass_through_under_the_reasoning_object_is_refused_beside_a_known_reasoning_name() {
      InferenceRequest request =
          carrying(
              Map.of(
                  "openai.reasoning.effort", "high",
                  "openai.reasoning.generate_summary", "auto"));

      assertThatThrownBy(() -> OpenAiResponsesRequests.toParams(request, VENDOR, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'openai.reasoning.generate_summary'")
          .hasMessageContaining("'openai.reasoning.effort'");
    }

    @Test
    void a_reasoning_pass_through_alone_is_sent() {
      Map<String, JsonValue> body =
          paramsFor(Map.of("openai.reasoning.generate_summary", "auto"))._additionalBodyProperties();

      assertThat(body.get("reasoning").asObject().orElseThrow().get("generate_summary").asString())
          .contains("auto");
    }
  }
```

`OpenAiResponsesProviderConfigTest.java` -- add (imports `java.util.List`, `java.util.Map`, `com.openai.models.ReasoningEffort`, `com.openai.models.responses.ResponseCreateParams`, `org.jwcarman.nessy.inference.InferenceOptions`):

```java
  @Test
  void a_property_on_the_config_reaches_every_request() {
    var captured = new ResponseCreateParams[1];
    OpenAiResponsesInferenceProvider provider =
        new OpenAiResponsesProviderConfig()
            .property("openai.reasoning.effort", "medium")
            .client(
                ResponseStreams.client(
                    params -> {
                      captured[0] = params;
                      return ResponseStreams.eventsOf(
                          ResponseStreams.completed(
                              List.of(ResponseStreams.message("msg_1", "ok"))));
                    }))
            .build();

    provider.infer(OpenAiResponsesInferenceProviderTest.REQUEST);

    assertThat(captured[0].reasoning().orElseThrow().effort().map(ReasoningEffort::asString))
        .contains("medium");
  }

  @Test
  void strict_false_on_the_provider_is_refused_at_build() {
    Customizer<OpenAiResponsesProviderConfig> customizer =
        c -> c.apiKey("test-key").property("openai.tools.strict", "false");

    assertThatThrownBy(() -> OpenAiResponsesInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.tools.strict'");
  }

  @Test
  void a_property_under_another_prefix_is_refused_at_build_naming_the_prefix() {
    Customizer<OpenAiResponsesProviderConfig> customizer =
        c -> c.apiKey("test-key").properties(Map.of("gemini.labels.team", "billing"));

    assertThatThrownBy(() -> OpenAiResponsesInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'gemini.labels.team'")
        .hasMessageContaining("'openai.'");
  }

  @Test
  void validate_refuses_the_fixed_fields_an_agent_type_names() {
    OpenAiResponsesInferenceProvider provider =
        new OpenAiResponsesProviderConfig()
            .client(ResponseStreams.client(params -> List.of()))
            .build();
    InferenceOptions options =
        new InferenceOptions("gpt-6-sol", 1024, Map.of("openai.background", "true"));

    assertThatThrownBy(() -> provider.validate(options))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.background'");
  }
```

(`OpenAiResponsesInferenceProviderTest.REQUEST` is package-visible `static final`; `ResponseStreams.message(String id, String text)` builds a completed assistant message.)

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -pl :nessy-inference-openai -am test -Dtest='OpenAiResponsesRequestsTest,OpenAiResponsesProviderConfigTest' -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: compilation failure; `exit=1`.

- [ ] **Step 3: The Responses reading**

`OpenAiProperties.java` -- add beside `CHAT_CLASHES`:

```java
  /** Why the Responses adapter fixes a field, quoted in the refusal (Responses record §5a). */
  private static final String STATELESS =
      "the Responses adapter, which is stateless because the event log is the only conversation";

  /** What the Responses wire's typed settings, or the adapter itself, already decide (§9b). */
  private static final Map<String, String> RESPONSES_CLASHES =
      Map.ofEntries(
          Map.entry("model", "InferenceConfig.model"),
          Map.entry("input", CONVERSATION),
          Map.entry("instructions", "the system prompt the harness sends"),
          Map.entry("max_output_tokens", "InferenceConfig.maxTokens"),
          Map.entry("tools", TOOLS),
          Map.entry("tool_choice", TOOL_CHOICE),
          Map.entry("text", SHAPE),
          Map.entry("stream", STREAMING),
          Map.entry("store", STATELESS),
          Map.entry("include", STATELESS),
          Map.entry("previous_response_id", STATELESS),
          Map.entry("conversation", STATELESS),
          Map.entry("background", STATELESS));

  /** The object the adapter builds when either reasoning name is set (plan ruling 6). */
  private static final String REASONING = "reasoning";
```

and after `chat(...)`:

```java
  /** The Responses wire's reading of the merged provider and agent-type map (§9b). */
  static Read responses(Map<String, String> merged, JsonMapper mapper) {
    Map<String, String> own = VendorProperties.under(merged, PREFIX);
    Map<String, String> clashes = new LinkedHashMap<>(RESPONSES_CLASHES);
    String known = null;
    if (own.containsKey(EFFORT)) {
      known = EFFORT;
    } else if (own.containsKey(SUMMARY)) {
      known = SUMMARY;
    }
    if (known != null) {
      for (String name : own.keySet()) {
        boolean underReasoning = name.equals(REASONING) || name.startsWith(REASONING + ".");
        if (underReasoning && !KNOWN.contains(name)) {
          clashes.put(name, "property '" + PREFIX + known + "'");
        }
      }
    }
    VendorProperties.refuseClashes(PREFIX, own, clashes);
    Read read = read(own, mapper);
    if (own.containsKey(STRICT) && !read.strict()) {
      throw new IllegalArgumentException(
          "property '"
              + PREFIX
              + STRICT
              + "' is false, and the openai-responses wire sends function tools strict"
              + " regardless; remove the property, or use the openai-chat wire");
    }
    return read;
  }
```

- [ ] **Step 4: The Responses projection reads them**

`OpenAiResponsesRequests.java` (imports `com.openai.models.Reasoning`, `com.openai.models.ReasoningEffort`, `org.jwcarman.nessy.vendor.VendorProperties`):

```java
  /** No provider-level properties: the agent type's alone. */
  static ResponseCreateParams toParams(InferenceRequest request, String vendor, JsonMapper mapper) {
    return toParams(request, vendor, Map.of(), mapper);
  }
```

The existing method becomes the four-argument form, with `@param providerProperties the provider's own {@code openai.} map, overlaid here by the agent type's (spec §7a)` added to its javadoc; its body starts:

```java
  static ResponseCreateParams toParams(
      InferenceRequest request,
      String vendor,
      Map<String, String> providerProperties,
      JsonMapper mapper) {
    InferenceOptions options = request.options();
    OpenAiProperties.Read read =
        OpenAiProperties.responses(
            VendorProperties.merge(providerProperties, options.properties()), mapper);
```

and, after `request.outputSchema().ifPresent(...)` and before `return builder.build();`:

```java
    // Built only when asked for: a reasoning object is a 400 on a model that does not reason,
    // and the adapter never guesses which kind it holds (Responses record §5g).
    if (read.effort().isPresent() || read.summary().isPresent()) {
      Reasoning.Builder reasoning = Reasoning.builder();
      read.effort().ifPresent(effort -> reasoning.effort(ReasoningEffort.of(effort)));
      read.summary().ifPresent(summary -> reasoning.summary(Reasoning.Summary.of(summary)));
      builder.reasoning(reasoning.build());
    }
    read.serviceTier()
        .ifPresent(tier -> builder.serviceTier(ResponseCreateParams.ServiceTier.of(tier)));
    read.passThrough()
        .forEach((name, value) -> builder.putAdditionalBodyProperty(name, JsonValue.from(value)));
```

- [ ] **Step 5: The provider and its config**

`OpenAiResponsesInferenceProvider.java` -- exactly the chat provider's change (Task 3 Step 6): a `Map<String, String> properties` field, the four-argument constructor delegating to a new five-argument one `(OpenAIClient client, String vendor, boolean ownsClient, JsonMapper mapper, Map<String, String> properties)` that stores `Map.copyOf(properties)`, `infer` calling `OpenAiResponsesRequests.toParams(request, vendor, properties, mapper)`, and:

```java
  /**
   * Reads the merged properties exactly as a request would, so a clash, a bad value or {@code
   * openai.tools.strict=false} fails the harness build rather than its first turn (spec §7c).
   */
  @Override
  public void validate(InferenceOptions options) {
    Map<String, String> merged = VendorProperties.merge(properties, options.properties());
    OpenAiProperties.responses(merged, mapper);
    OpenAiProperties.logIgnored(merged);
  }
```

`OpenAiResponsesProviderConfig.java` -- the chat config's `properties` field, `property(String, String)` and `properties(Map<String, String>)` (Task 3 Step 7, returning `OpenAiResponsesProviderConfig`, the javadoc naming `openai.reasoning.effort` and `openai.reasoning.summary`), and:

```java
  OpenAiResponsesInferenceProvider build() {
    OpenAiProperties.requireOwn(properties);
    OpenAiProperties.responses(properties, mapper);
    Map<String, String> own = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    if (client != null) {
      return new OpenAiResponsesInferenceProvider(client, vendor, false, mapper, own);
    }
    return new OpenAiResponsesInferenceProvider(
        OpenAiClients.build(useEnv, apiKey, baseUrl, organization, timeout),
        vendor,
        true,
        mapper,
        own);
  }
```

- [ ] **Step 6: Run them to see them pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-inference-openai -am test; echo exit=$?`
Expected: `exit=0` (the whole module; the existing `no_reasoning_object_is_sent` still holds).

- [ ] **Step 7: Full gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?`
Expected: `exit=0`.

```bash
cd /Users/jcarman/IdeaProjects/nessy-props
git add nessy-inference/openai/src
git commit -m "feat: the Responses adapter reads openai. properties, reasoning object included

openai.reasoning.effort and openai.reasoning.summary build the reasoning
object only when asked for; openai.service_tier lands typed; the rest passes
through. The fields the stateless adapter fixes are refused with its reason,
and openai.tools.strict=false is refused because this wire is strict.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 5: Anthropic -- thinking and caching as properties, and the setters in the same tier

Spec §6a, §7a (the tier rule), §9c, §13a. Plan rulings 8, 9 and 10 govern this task: `AnthropicRequests`, its public `Features` record and its public `toParams(InferenceRequest, Features, JsonMapper)` do not change shape. Suggested implementer: Sonnet. **HIGH-RISK -- review with Opus**: three layers of precedence (setter, provider property, agent-type property) over a public API that must not move, and today's thinking and caching behaviour must survive byte for byte.

**Files:**
- Create: `nessy-inference/anthropic/src/main/java/org/jwcarman/nessy/inference/anthropic/AnthropicProperties.java`
- Modify: `nessy-inference/anthropic/src/main/java/org/jwcarman/nessy/inference/anthropic/AnthropicRequests.java` (`toParams` lines 97-133, `cacheMarker` lines 136-143)
- Modify: `nessy-inference/anthropic/src/main/java/org/jwcarman/nessy/inference/anthropic/AnthropicInferenceProvider.java` (fields and constructor lines 81-101, `infer` line 139)
- Modify: `nessy-inference/anthropic/src/main/java/org/jwcarman/nessy/inference/anthropic/AnthropicProviderConfig.java`
- Modify: `nessy-inference/anthropic/pom.xml` (declare `slf4j-api`, ruling 16)
- Test: `nessy-inference/anthropic/src/test/java/org/jwcarman/nessy/inference/anthropic/AnthropicRequestsTest.java`
- Test: `nessy-inference/anthropic/src/test/java/org/jwcarman/nessy/inference/anthropic/AnthropicInferenceProviderTest.java`
- Test: `nessy-inference/anthropic/src/test/java/org/jwcarman/nessy/inference/anthropic/AnthropicProviderConfigTest.java`

**Interfaces:**
- Consumes: `VendorProperties.*` (Task 1); `InferenceProvider.validate` (Task 1).
- Produces (package-private):
  - `AnthropicProperties.PREFIX = "anthropic."`, `THINKING_TYPE = "thinking.type"`, `THINKING_BUDGET = "thinking.budget_tokens"`, `CACHE_TTL = "cache_control.ttl"`, `SERVICE_TIER = "service_tier"`, `ENABLED = "enabled"`, `ADAPTIVE = "adaptive"`
  - `record AnthropicProperties.Read(Optional<String> thinking, OptionalInt budget, Optional<String> cacheTtl, Optional<String> serviceTier, Map<String, Object> passThrough)` with `boolean enabled()` -- `thinking` is empty when off (absent or `disabled`), else `enabled`, `adaptive` or the raw value
  - `static Read AnthropicProperties.read(Map<String, String> merged, JsonMapper mapper)`, `static void requireHeadroom(Read read, InferenceOptions options)`, `static void requireOwn(Map<String, String>)`, `static void logIgnored(Map<String, String>)`, `static Map<String, String> of(AnthropicRequests.Features features)`
  - `static MessageCreateParams AnthropicRequests.toParams(InferenceRequest, Map<String, String> providerProperties, JsonMapper)`
  - `AnthropicInferenceProvider(AnthropicClient, Map<String, String> properties, boolean ownsClient, JsonMapper)` beside the unchanged `Features` constructor
- Produces (public): `AnthropicProviderConfig property(String, String)`, `properties(Map<String, String>)`; `AnthropicInferenceProvider.validate(InferenceOptions)`.

- [ ] **Step 1: Write the failing projection tests**

`AnthropicRequestsTest.java` -- add imports `com.anthropic.core.JsonValue`, `com.anthropic.models.messages.CacheControlEphemeral`, `com.anthropic.models.messages.MessageCreateParams` (present), `java.util.Map`; then a nested class. Its tests reach the package-private `toParams(InferenceRequest, Map, JsonMapper)`; a `Map` argument selects it over the public `Features` form.

```java
  @Nested
  class TheVendorProperties {

    private static InferenceRequest carrying(Map<String, String> agentType) {
      return new InferenceRequest(
          SYSTEM,
          InferenceContext.of(List.of(open(1, "hi"))),
          Toolset.none(),
          new InferenceOptions("claude-sonnet", 1024, agentType));
    }

    private static MessageCreateParams paramsFor(
        Map<String, String> provider, Map<String, String> agentType) {
      return AnthropicRequests.toParams(carrying(agentType), provider, MAPPER);
    }

    private static MessageCreateParams paramsFor(Map<String, String> agentType) {
      return paramsFor(Map.of(), agentType);
    }

    @Test
    void a_budget_alone_turns_thinking_on_with_that_budget() {
      MessageCreateParams params = paramsFor(Map.of("anthropic.thinking.budget_tokens", "512"));

      assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(512L);
    }

    @Test
    void enabled_with_a_budget_asks_for_that_budget() {
      MessageCreateParams params =
          paramsFor(
              Map.of(
                  "anthropic.thinking.type", "enabled", "anthropic.thinking.budget_tokens", "600"));

      assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(600L);
    }

    @Test
    void enabled_without_a_budget_is_refused_naming_both_properties() {
      InferenceRequest request = carrying(Map.of("anthropic.thinking.type", "enabled"));

      assertThatThrownBy(() -> AnthropicRequests.toParams(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'anthropic.thinking.type'")
          .hasMessageContaining("'anthropic.thinking.budget_tokens'");
    }

    /** Plan ruling 9: disabled over a provider that thinks sends no thinking object at all. */
    @Test
    void disabled_sends_no_thinking_object_over_a_provider_that_thinks() {
      MessageCreateParams params =
          paramsFor(
              Map.of(
                  "anthropic.thinking.type", "enabled", "anthropic.thinking.budget_tokens", "512"),
              Map.of("anthropic.thinking.type", "disabled"));

      assertThat(params.thinking()).isEmpty();
      assertThat(params._additionalBodyProperties()).isEmpty();
    }

    @Test
    void adaptive_sends_the_adaptive_config() {
      MessageCreateParams params = paramsFor(Map.of("anthropic.thinking.type", "adaptive"));

      assertThat(params.thinking().orElseThrow().isAdaptive()).isTrue();
    }

    /** Plan ruling 9: the type is checked, the vocabulary is the vendor's. */
    @Test
    void a_type_nessy_does_not_know_goes_as_a_raw_thinking_object() {
      MessageCreateParams params =
          paramsFor(
              Map.of(
                  "anthropic.thinking.type", "interleaved",
                  "anthropic.thinking.budget_tokens", "600"));

      assertThat(params.thinking()).isEmpty();
      Map<String, JsonValue> thinking =
          params._additionalBodyProperties().get("thinking").asObject().orElseThrow();
      assertThat(thinking.get("type").asString()).contains("interleaved");
      assertThat(thinking.get("budget_tokens").asNumber().map(Number::intValue)).contains(600);
    }

    @Test
    void a_budget_with_no_headroom_under_the_ceiling_is_refused() {
      InferenceRequest request = carrying(Map.of("anthropic.thinking.budget_tokens", "1024"));

      assertThatThrownBy(() -> AnthropicRequests.toParams(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("maxTokens (1024) must be greater than the thinking budget (1024)");
    }

    @Test
    void a_one_hour_ttl_marks_the_prefix_for_an_hour() {
      CacheControlEphemeral marker =
          paramsFor(Map.of("anthropic.cache_control.ttl", "1h"))
              .system()
              .orElseThrow()
              .asTextBlockParams()
              .getFirst()
              .cacheControl()
              .orElseThrow();

      assertThat(marker.ttl()).contains(CacheControlEphemeral.Ttl.TTL_1H);
    }

    @Test
    void a_five_minute_ttl_marks_the_prefix_as_today() {
      CacheControlEphemeral marker =
          paramsFor(Map.of("anthropic.cache_control.ttl", "5m"))
              .system()
              .orElseThrow()
              .asTextBlockParams()
              .getFirst()
              .cacheControl()
              .orElseThrow();

      assertThat(marker.ttl()).isEmpty();
    }

    @Test
    void a_service_tier_lands_in_its_typed_field() {
      assertThat(
              paramsFor(Map.of("anthropic.service_tier", "auto"))
                  .serviceTier()
                  .map(MessageCreateParams.ServiceTier::asString))
          .contains("auto");
    }

    @Test
    void an_unknown_name_passes_through_as_a_typed_literal_nested_by_path() {
      Map<String, JsonValue> body =
          paramsFor(
                  Map.of(
                      "anthropic.top_k", "5",
                      "anthropic.metadata.user_id", "u-1",
                      "anthropic.stop_sequences", "[\"\\n\\n\"]"))
              ._additionalBodyProperties();

      assertThat(body.get("top_k").asNumber().map(Number::intValue)).contains(5);
      assertThat(body.get("metadata").asObject().orElseThrow().get("user_id").asString())
          .contains("u-1");
      assertThat(body.get("stop_sequences").asArray().orElseThrow())
          .singleElement()
          .satisfies(value -> assertThat(value.asString()).contains("\n\n"));
    }

    @Test
    void another_prefix_is_not_sent() {
      MessageCreateParams params = paramsFor(Map.of("openai.reasoning.effort", "high"));

      assertThat(params._additionalBodyProperties()).isEmpty();
      assertThat(params.thinking()).isEmpty();
    }

    @Test
    void an_agent_type_budget_overrides_the_provider_s() {
      MessageCreateParams params =
          paramsFor(
              Map.of(
                  "anthropic.thinking.type", "enabled", "anthropic.thinking.budget_tokens", "512"),
              Map.of("anthropic.thinking.budget_tokens", "768"));

      assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(768L);
    }

    @Test
    void the_ceiling_is_refused_as_what_a_typed_setting_decides() {
      InferenceRequest request = carrying(Map.of("anthropic.max_tokens", "10"));

      assertThatThrownBy(() -> AnthropicRequests.toParams(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'anthropic.max_tokens'")
          .hasMessageContaining("InferenceConfig.maxTokens");
    }

    @Test
    void the_raw_thinking_object_beside_a_known_thinking_name_is_refused() {
      InferenceRequest request =
          carrying(
              Map.of(
                  "anthropic.thinking.budget_tokens", "512",
                  "anthropic.thinking", "{\"type\":\"enabled\"}"));

      assertThatThrownBy(() -> AnthropicRequests.toParams(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'anthropic.thinking'")
          .hasMessageContaining("'anthropic.thinking.budget_tokens'");
    }

    @Test
    void a_bad_budget_is_refused_naming_the_property_and_the_value() {
      InferenceRequest request = carrying(Map.of("anthropic.thinking.budget_tokens", "lots"));

      assertThatThrownBy(() -> AnthropicRequests.toParams(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property 'anthropic.thinking.budget_tokens' must be an integer, was 'lots'");
    }
  }
```

(The existing thinking cases -- `enabled_asks_for_a_budget_and_disabled_asks_for_nothing`, `a_budget_with_no_headroom_under_the_ceiling_is_refused_before_the_call` -- and every caching case stay exactly as written: they go through the public `Features` form, which must keep producing the same params.)

- [ ] **Step 2: Write the failing provider and config tests**

`AnthropicInferenceProviderTest.java` -- add (imports `java.util.Map`, `static org.assertj.core.api.Assertions.assertThatThrownBy` if absent):

```java
  @Nested
  class ItsVendorProperties {

    private static InferenceRequest carrying(Map<String, String> agentType) {
      return new InferenceRequest(
          REQUEST.systemPrompt(),
          REQUEST.context(),
          REQUEST.toolset(),
          new InferenceOptions("claude-sonnet", 20000, agentType));
    }

    private static MessageCreateParams sentBy(
        AnthropicProviderConfig config, InferenceRequest request) {
      var captured = new MessageCreateParams[1];
      config
          .client(
              fakeClient(
                  params -> {
                    captured[0] = params;
                    return reply().addContent(text("ok")).build();
                  }))
          .build()
          .infer(request);
      return captured[0];
    }

    /** §9c's tier rule: an agent type's property beats a setter, name by name. */
    @Test
    void an_agent_type_budget_overrides_the_setters() {
      MessageCreateParams params =
          sentBy(
              new AnthropicProviderConfig().thinking(true).thinkingBudget(1024),
              carrying(Map.of("anthropic.thinking.budget_tokens", "16000")));

      assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(16000L);
    }

    @Test
    void an_agent_type_that_disables_thinking_sends_none_over_a_config_that_turned_it_on() {
      MessageCreateParams params =
          sentBy(
              new AnthropicProviderConfig().thinking(true),
              carrying(Map.of("anthropic.thinking.type", "disabled")));

      assertThat(params.thinking()).isEmpty();
    }

    @Test
    void thinking_on_with_no_budget_anywhere_keeps_the_default_of_1024() {
      MessageCreateParams params =
          sentBy(new AnthropicProviderConfig().thinking(true), carrying(Map.of()));

      assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(1024L);
    }

    @Test
    void thinking_on_takes_a_budget_given_as_a_provider_property() {
      MessageCreateParams params =
          sentBy(
              new AnthropicProviderConfig()
                  .thinking(true)
                  .property("anthropic.thinking.budget_tokens", "2048"),
              carrying(Map.of()));

      assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(2048L);
    }

    /** Review Focus 5: a bare budget setter stays inert, as it is today. */
    @Test
    void a_budget_setter_without_thinking_on_still_asks_for_no_thinking() {
      MessageCreateParams params =
          sentBy(new AnthropicProviderConfig().thinkingBudget(4096), carrying(Map.of()));

      assertThat(params.thinking()).isEmpty();
    }

    @Test
    void a_provider_that_does_not_think_thinks_for_an_agent_type_that_asks() {
      MessageCreateParams params =
          sentBy(
              new AnthropicProviderConfig(),
              carrying(Map.of("anthropic.thinking.budget_tokens", "1024")));

      assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(1024L);
    }

    @Test
    void the_caching_setter_and_a_ttl_property_mean_the_same_marker() {
      MessageCreateParams bySetter =
          sentBy(
              new AnthropicProviderConfig().promptCaching(PromptCaching.ONE_HOUR),
              carrying(Map.of()));
      MessageCreateParams byProperty =
          sentBy(
              new AnthropicProviderConfig().property("anthropic.cache_control.ttl", "1h"),
              carrying(Map.of()));

      assertThat(bySetter.system()).isEqualTo(byProperty.system());
    }

    @Test
    void validate_refuses_a_budget_at_or_over_the_ceiling() {
      AnthropicInferenceProvider provider =
          new AnthropicProviderConfig().thinking(true).client(fakeClient(params -> null)).build();
      InferenceOptions options =
          new InferenceOptions(
              "claude-sonnet", 2048, Map.of("anthropic.thinking.budget_tokens", "4096"));

      assertThatThrownBy(() -> provider.validate(options))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("maxTokens (2048) must be greater than the thinking budget (4096)");
    }
  }
```

(`fakeClient`, `reply()`, `text(String)` and `REQUEST` are the class's existing fixtures. `REQUEST` has `maxTokens` 1024, below the 16000 budget, which is why `carrying` sets 20000.)

`AnthropicProviderConfigTest.java` -- add (imports `java.util.Map`, `org.jwcarman.nessy.api.Customizer`):

```java
  @Test
  void a_budget_setter_and_a_budget_property_fail_at_build_naming_both() {
    Customizer<AnthropicProviderConfig> customizer =
        c ->
            c.apiKey("test-key")
                .thinking(true)
                .thinkingBudget(4096)
                .property("anthropic.thinking.budget_tokens", "8192");

    assertThatThrownBy(() -> AnthropicInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("thinkingBudget(int)")
        .hasMessageContaining("'anthropic.thinking.budget_tokens'");
  }

  /** "Whatever the values": a setter set to off still says something about the same field. */
  @Test
  void thinking_off_and_a_thinking_type_property_fail_at_build_naming_both() {
    Customizer<AnthropicProviderConfig> customizer =
        c ->
            c.apiKey("test-key")
                .thinking(false)
                .properties(Map.of("anthropic.thinking.type", "adaptive"));

    assertThatThrownBy(() -> AnthropicInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("thinking(boolean)")
        .hasMessageContaining("'anthropic.thinking.type'");
  }

  @Test
  void a_caching_setter_and_a_ttl_property_fail_at_build_naming_both() {
    Customizer<AnthropicProviderConfig> customizer =
        c ->
            c.apiKey("test-key")
                .promptCaching(PromptCaching.OFF)
                .property("anthropic.cache_control.ttl", "5m");

    assertThatThrownBy(() -> AnthropicInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("promptCaching(PromptCaching)")
        .hasMessageContaining("'anthropic.cache_control.ttl'");
  }

  @Test
  void enabled_without_a_budget_at_the_provider_is_refused_at_build() {
    Customizer<AnthropicProviderConfig> customizer =
        c -> c.apiKey("test-key").property("anthropic.thinking.type", "enabled");

    assertThatThrownBy(() -> AnthropicInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'anthropic.thinking.budget_tokens'");
  }

  @Test
  void a_property_under_another_prefix_is_refused_at_build_naming_the_prefix() {
    Customizer<AnthropicProviderConfig> customizer =
        c -> c.apiKey("test-key").property("openai.reasoning.effort", "high");

    assertThatThrownBy(() -> AnthropicInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.reasoning.effort'")
        .hasMessageContaining("'anthropic.'");
  }
```

- [ ] **Step 3: Run them to see them fail**

Run: `./mvnw -q -pl :nessy-inference-anthropic -am test -Dtest='AnthropicRequestsTest,AnthropicInferenceProviderTest,AnthropicProviderConfigTest' -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: compilation failure; `exit=1`.

- [ ] **Step 4: Create `AnthropicProperties`**

`nessy-inference/anthropic/pom.xml`: add, beside the other compile dependencies,

```xml
    <!-- The DEBUG line naming other adapters' properties; the version is managed by the parent. -->
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
    </dependency>
```

`AnthropicProperties.java` (license header, then):

```java
package org.jwcarman.nessy.inference.anthropic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.vendor.VendorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code anthropic.} vendor properties (spec §9c): thinking, the cache marker's lifetime and
 * the service tier as known names, the clash table, and the rest passed through.
 */
final class AnthropicProperties {

  static final String PREFIX = "anthropic.";
  static final String THINKING_TYPE = "thinking.type";
  static final String THINKING_BUDGET = "thinking.budget_tokens";
  static final String CACHE_TTL = "cache_control.ttl";
  static final String SERVICE_TIER = "service_tier";

  static final String ENABLED = "enabled";
  static final String ADAPTIVE = "adaptive";
  private static final String DISABLED = "disabled";

  private static final Set<String> KNOWN =
      Set.of(THINKING_TYPE, THINKING_BUDGET, CACHE_TTL, SERVICE_TIER);

  /** What the typed settings, or the adapter itself, already decide (§9c). */
  private static final Map<String, String> CLASHES =
      Map.of(
          "model", "InferenceConfig.model",
          "max_tokens", "InferenceConfig.maxTokens",
          "messages", "the conversation the engine assembles",
          "system", "the system prompt the harness sends",
          "tools", "the tools the harness binds",
          "tool_choice", "the tool choice the engine makes",
          "output_config", "the answer's shape the harness asks for",
          "stream", "the adapter, which always streams");

  private static final Logger log = LoggerFactory.getLogger(AnthropicProperties.class);

  private AnthropicProperties() {}

  /**
   * The {@code anthropic.} properties once read.
   *
   * @param thinking empty when not thinking (absent, or {@code disabled}); otherwise {@code
   *     enabled}, {@code adaptive}, or a value this adapter sends as written
   * @param budget the thinking budget; always present when {@code thinking} is {@code enabled}
   */
  record Read(
      Optional<String> thinking,
      OptionalInt budget,
      Optional<String> cacheTtl,
      Optional<String> serviceTier,
      Map<String, Object> passThrough) {

    boolean enabled() {
      return thinking.filter(ENABLED::equals).isPresent();
    }
  }

  static Read read(Map<String, String> merged, JsonMapper mapper) {
    Map<String, String> own = VendorProperties.under(merged, PREFIX);
    Map<String, String> clashes = new LinkedHashMap<>(CLASHES);
    claimRoot(own, clashes, "thinking", THINKING_TYPE, THINKING_BUDGET);
    claimRoot(own, clashes, "cache_control", CACHE_TTL);
    VendorProperties.refuseClashes(PREFIX, own, clashes);

    OptionalInt budget =
        own.containsKey(THINKING_BUDGET)
            ? OptionalInt.of(
                VendorProperties.requireInteger(PREFIX + THINKING_BUDGET, own.get(THINKING_BUDGET)))
            : OptionalInt.empty();
    Optional<String> type = string(own, THINKING_TYPE);
    // Absent with a budget present means enabled (§9c).
    Optional<String> thinking =
        type.isPresent() ? type : budget.isPresent() ? Optional.of(ENABLED) : Optional.empty();
    if (thinking.filter(ENABLED::equals).isPresent() && budget.isEmpty()) {
      throw new IllegalArgumentException(
          "property '"
              + PREFIX
              + THINKING_TYPE
              + "' is enabled and '"
              + PREFIX
              + THINKING_BUDGET
              + "' is not set; the vendor requires a budget for enabled thinking");
    }
    Map<String, String> rest = new LinkedHashMap<>(own);
    rest.keySet().removeAll(KNOWN);
    return new Read(
        thinking.filter(value -> !DISABLED.equals(value)),
        budget,
        string(own, CACHE_TTL),
        string(own, SERVICE_TIER),
        VendorProperties.nest(PREFIX, rest, mapper));
  }

  /** The budget is spent out of maxTokens, so a ceiling at or below it leaves nothing to answer. */
  static void requireHeadroom(Read read, InferenceOptions options) {
    if (read.enabled() && options.maxTokens() <= read.budget().getAsInt()) {
      throw new IllegalArgumentException(
          "maxTokens (%d) must be greater than the thinking budget (%d)"
              .formatted(options.maxTokens(), read.budget().getAsInt()));
    }
  }

  /** A provider is one adapter: a provider-level entry under another prefix is a mistake (§6a). */
  static void requireOwn(Map<String, String> properties) {
    for (String name : properties.keySet()) {
      if (!name.startsWith(PREFIX)) {
        throw new IllegalArgumentException(
            "property '"
                + name
                + "' is not under '"
                + PREFIX
                + "'; a provider reads only its own prefix");
      }
    }
  }

  static void logIgnored(Map<String, String> merged) {
    if (log.isDebugEnabled()) {
      List<String> others = merged.keySet().stream().filter(n -> !n.startsWith(PREFIX)).toList();
      if (!others.isEmpty()) {
        log.debug("NESSY INFERENCE: properties for other adapters, ignored here: {}", others);
      }
    }
  }

  /** The public {@code Features} form, spelled as the properties it means (plan ruling 8). */
  static Map<String, String> of(AnthropicRequests.Features features) {
    Map<String, String> properties = new LinkedHashMap<>();
    if (features.thinking()) {
      properties.put(PREFIX + THINKING_TYPE, ENABLED);
      properties.put(PREFIX + THINKING_BUDGET, Integer.toString(features.thinkingBudget()));
    }
    switch (features.caching()) {
      case OFF -> {
        // No marker.
      }
      case FIVE_MINUTES -> properties.put(PREFIX + CACHE_TTL, "5m");
      case ONE_HOUR -> properties.put(PREFIX + CACHE_TTL, "1h");
    }
    return Collections.unmodifiableMap(properties);
  }

  /**
   * Plan ruling 6: while a known name builds {@code root}, a pass-through at or under it would be a
   * second statement about the same object, so it joins the clash table naming that known name.
   */
  private static void claimRoot(
      Map<String, String> own, Map<String, String> clashes, String root, String... known) {
    for (String name : known) {
      if (own.containsKey(name)) {
        for (String candidate : own.keySet()) {
          boolean underRoot = candidate.equals(root) || candidate.startsWith(root + ".");
          if (underRoot && !KNOWN.contains(candidate)) {
            clashes.put(candidate, "property '" + PREFIX + name + "'");
          }
        }
        return;
      }
    }
  }

  private static Optional<String> string(Map<String, String> own, String name) {
    return Optional.ofNullable(own.get(name))
        .map(value -> VendorProperties.requireString(PREFIX + name, value));
  }
}
```

(If Sonar flags the nested ternary for `thinking`, write it as `if`/`else if` -- same behaviour.)

- [ ] **Step 5: The projection reads them**

`AnthropicRequests.java` (imports `com.anthropic.models.messages.ThinkingConfigAdaptive`, `java.util.LinkedHashMap`, `org.jwcarman.nessy.vendor.VendorProperties`):

The public method keeps its signature and becomes a delegation (add to its javadoc: "The provider's features, read as the properties they mean.", keep the rest):

```java
  public static MessageCreateParams toParams(
      InferenceRequest request, Features features, JsonMapper mapper) {
    return toParams(request, AnthropicProperties.of(features), mapper);
  }
```

The new package-private form carries today's body with four changes -- the headroom check, the marker, the thinking block, and the two new tails:

```java
  /**
   * @param providerProperties the provider's own {@code anthropic.} map -- its setters already
   *     spelled as properties -- overlaid here by the agent type's (spec §7a)
   */
  static MessageCreateParams toParams(
      InferenceRequest request, Map<String, String> providerProperties, JsonMapper mapper) {
    InferenceOptions options = request.options();
    AnthropicProperties.Read read =
        AnthropicProperties.read(
            VendorProperties.merge(providerProperties, options.properties()), mapper);
    // Refused here as well as at validate, for a caller that never validated (a summariser).
    AnthropicProperties.requireHeadroom(read, options);

    Optional<CacheControlEphemeral> marker = read.cacheTtl().map(AnthropicRequests::cacheMarker);
    MessageCreateParams.Builder builder =
        MessageCreateParams.builder().model(options.modelName()).maxTokens(options.maxTokens());

    // ... unchanged: system blocks, addMessages, the answering/tools lines, askForShape ...

    if (read.enabled()) {
      builder.thinking(
          ThinkingConfigEnabled.builder().budgetTokens(read.budget().getAsInt()).build());
    } else if (read.thinking().filter(AnthropicProperties.ADAPTIVE::equals).isPresent()) {
      builder.thinking(ThinkingConfigAdaptive.builder().build());
    } else if (read.thinking().isPresent()) {
      // A type this adapter has no SDK class for: sent as written, for the vendor to judge.
      Map<String, Object> raw = new LinkedHashMap<>();
      raw.put("type", read.thinking().get());
      read.budget().ifPresent(budget -> raw.put("budget_tokens", budget));
      builder.putAdditionalBodyProperty("thinking", JsonValue.from(raw));
    }
    read.serviceTier()
        .ifPresent(tier -> builder.serviceTier(MessageCreateParams.ServiceTier.of(tier)));
    read.passThrough()
        .forEach((name, value) -> builder.putAdditionalBodyProperty(name, JsonValue.from(value)));
    return builder.build();
  }
```

(The `// ... unchanged` line stands for the existing statements from `List<TextBlockParam> system = systemBlocks(request, marker);` through `request.outputSchema().ifPresent(schema -> askForShape(builder, schema, mapper));`, kept exactly. The old `if (features.thinking() && ...)` check and the old `if (features.thinking()) { builder.thinking(...) }` block are the ones replaced. `read.thinking().get()` follows an `isPresent()` in the same condition chain; if Sonar objects, bind it with `orElseThrow()`.)

Replace `cacheMarker(PromptCaching caching)` with:

```java
  /**
   * The cache marker for a ttl, as the vendor spells ttls: {@code 5m} is today's default marker,
   * {@code 1h} the long one, and anything else is sent as written for the vendor to judge.
   */
  private static CacheControlEphemeral cacheMarker(String ttl) {
    return switch (ttl) {
      case "5m" -> CacheControlEphemeral.builder().build();
      case "1h" -> CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build();
      default -> CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.of(ttl)).build();
    };
  }
```

(`PromptCaching` is no longer referenced here; remove its now-unused import only if the compiler says it is unused -- `AnthropicProperties.of` still switches over it.)

- [ ] **Step 6: The provider holds the map and validates**

`AnthropicInferenceProvider.java` (imports `java.util.Map`, `org.jwcarman.nessy.inference.InferenceOptions`, `org.jwcarman.nessy.vendor.VendorProperties`): the `features` field is replaced by

```java
  /** The provider's own {@code anthropic.} properties, its setters already spelled as properties. */
  private final Map<String, String> properties;
```

and the constructor by the pair:

```java
  AnthropicInferenceProvider(
      AnthropicClient client,
      AnthropicRequests.Features features,
      boolean ownsClient,
      JsonMapper mapper) {
    this(client, AnthropicProperties.of(features), ownsClient, mapper);
  }

  AnthropicInferenceProvider(
      AnthropicClient client,
      Map<String, String> properties,
      boolean ownsClient,
      JsonMapper mapper) {
    this.client = client;
    this.properties = Map.copyOf(properties);
    this.ownsClient = ownsClient;
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }
```

In `infer`, `AnthropicRequests.toParams(request, features, mapper)` becomes `AnthropicRequests.toParams(request, properties, mapper)`. Add:

```java
  /**
   * Reads the merged properties exactly as a request would -- including the budget's headroom under
   * this agent type's {@code maxTokens} -- so a mistake fails the harness build, not a turn.
   */
  @Override
  public void validate(InferenceOptions options) {
    Map<String, String> merged = VendorProperties.merge(properties, options.properties());
    AnthropicProperties.requireHeadroom(AnthropicProperties.read(merged, mapper), options);
    AnthropicProperties.logIgnored(merged);
  }
```

- [ ] **Step 7: The config -- setters in the provider tier**

`AnthropicProviderConfig.java` (imports `java.util.Collections`, `java.util.LinkedHashMap`, `java.util.Map`, `org.jwcarman.nessy.vendor.VendorProperties`):

The three fields become nullable, so "set" is knowable (keep `DEFAULT_THINKING_BUDGET` and its comment):

```java
  private Boolean thinking;
  private Integer thinkingBudget;
  private PromptCaching promptCaching;
  private final Map<String, String> properties = new LinkedHashMap<>();
```

The setters keep their signatures and bodies (assigning the boxed fields); append to each existing javadoc one sentence -- `thinking`: "The same statement as {@code anthropic.thinking.type=enabled}; setting both on one provider fails at build, and an agent type's {@code anthropic.thinking.*} property overrides either."; `thinkingBudget`: "The same statement as {@code anthropic.thinking.budget_tokens}; setting both on one provider fails at build. Read only when {@link #thinking(boolean) thinking} is on."; `promptCaching`: "The same statement as {@code anthropic.cache_control.ttl} ({@code 5m}, {@code 1h}); setting both on one provider fails at build."

Delete `features()`. Add `property`/`properties` exactly as the chat config's (Task 3 Step 7, returning `AnthropicProviderConfig`, javadoc naming `anthropic.thinking.budget_tokens`), and:

```java
  /**
   * The provider's properties with its setters spelled as the properties they mean (plan ruling
   * 8), checked before any client is made. A setter and a property for one field are two
   * provider-level statements with no order between them, so both set is refused.
   */
  private Map<String, String> providerProperties() {
    AnthropicProperties.requireOwn(properties);
    refuseBoth(thinking != null, "thinking(boolean)", AnthropicProperties.THINKING_TYPE);
    refuseBoth(thinkingBudget != null, "thinkingBudget(int)", AnthropicProperties.THINKING_BUDGET);
    refuseBoth(
        promptCaching != null, "promptCaching(PromptCaching)", AnthropicProperties.CACHE_TTL);
    Map<String, String> merged = new LinkedHashMap<>(properties);
    String budgetName = AnthropicProperties.PREFIX + AnthropicProperties.THINKING_BUDGET;
    if (Boolean.TRUE.equals(thinking)) {
      merged.put(
          AnthropicProperties.PREFIX + AnthropicProperties.THINKING_TYPE,
          AnthropicProperties.ENABLED);
      if (thinkingBudget != null) {
        merged.put(budgetName, Integer.toString(thinkingBudget));
      } else if (!merged.containsKey(budgetName)) {
        merged.put(budgetName, Integer.toString(DEFAULT_THINKING_BUDGET));
      }
    }
    if (promptCaching == PromptCaching.FIVE_MINUTES) {
      merged.put(AnthropicProperties.PREFIX + AnthropicProperties.CACHE_TTL, "5m");
    } else if (promptCaching == PromptCaching.ONE_HOUR) {
      merged.put(AnthropicProperties.PREFIX + AnthropicProperties.CACHE_TTL, "1h");
    }
    AnthropicProperties.read(merged, mapper);
    return Collections.unmodifiableMap(merged);
  }

  private void refuseBoth(boolean setterCalled, String setter, String name) {
    if (setterCalled && properties.containsKey(AnthropicProperties.PREFIX + name)) {
      throw new IllegalArgumentException(
          setter
              + " and property '"
              + AnthropicProperties.PREFIX
              + name
              + "' both say how this provider "
              + (name.startsWith("thinking") ? "thinks" : "caches")
              + "; keep one");
    }
  }
```

`build()` computes `Map<String, String> own = providerProperties();` as its first line, and each of its three `new AnthropicInferenceProvider(..., features(), ...)` calls passes `own` instead of `features()`.

- [ ] **Step 8: Run them to see them pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-inference-anthropic -am test; echo exit=$?`
Expected: `exit=0` -- the whole module, so every existing thinking, caching, close-ownership and provider case proves today's behaviour survived.

- [ ] **Step 9: Full gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?`
Expected: `exit=0`.

```bash
cd /Users/jcarman/IdeaProjects/nessy-props
git add nessy-inference/anthropic
git commit -m "feat: Anthropic thinking and caching are properties too, and an agent type can set them

anthropic.thinking.type, anthropic.thinking.budget_tokens,
anthropic.cache_control.ttl and anthropic.service_tier are known names; the
rest passes through. The config's setters stay and sit in the provider tier:
a setter and a property for one field fail at build, and an agent type's
property overrides either. The public Features form is unchanged.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 6: Gemini -- `thinkingConfig` typed, the rest through `extraBody`

Spec §6a, §8d, §9d, §13a. Suggested implementer: Sonnet. Review: Sonnet.

**Files:**
- Create: `nessy-inference/gemini/src/main/java/org/jwcarman/nessy/inference/gemini/GeminiProperties.java`
- Modify: `nessy-inference/gemini/src/main/java/org/jwcarman/nessy/inference/gemini/GeminiRequests.java` (`toConfig`, lines 93-121)
- Modify: `nessy-inference/gemini/src/main/java/org/jwcarman/nessy/inference/gemini/GeminiInferenceProvider.java` (fields and constructor lines 106-112, `infer` line 151)
- Modify: `nessy-inference/gemini/src/main/java/org/jwcarman/nessy/inference/gemini/GeminiProviderConfig.java`
- Modify: `nessy-inference/gemini/pom.xml` (declare `slf4j-api`, ruling 16)
- Test: `nessy-inference/gemini/src/test/java/org/jwcarman/nessy/inference/gemini/GeminiRequestsTest.java`
- Test: `nessy-inference/gemini/src/test/java/org/jwcarman/nessy/inference/gemini/GeminiProviderConfigTest.java`

**Interfaces:**
- Consumes: `VendorProperties.*` (Task 1); `InferenceProvider.validate` (Task 1). SDK, verified on google-genai 1.73.0: `GenerateContentConfig.Builder.thinkingConfig(ThinkingConfig)`, `GenerateContentConfig.Builder.httpOptions(HttpOptions)`, `ThinkingConfig.builder()`, `.thinkingBudget(Integer)`, `.includeThoughts(boolean)`, `.thinkingLevel(String)`, `HttpOptions.builder().extraBody(Map<String, Object>)`; a request's `httpOptions` is overlaid on the client's by `ApiClient.mergeHttpOptions`, field by field, so an `HttpOptions` carrying only `extraBody` keeps the client's base URL and timeout.
- Produces (package-private): `GeminiProperties.PREFIX = "gemini."`, `THINKING_BUDGET`, `INCLUDE_THOUGHTS`, `THINKING_LEVEL` (the full paths under `generationConfig.thinkingConfig.`); `record GeminiProperties.Read(Optional<ThinkingConfig> thinking, Map<String, Object> passThrough)`; `static Read read(Map<String, String> merged, JsonMapper mapper)`; `requireOwn`, `logIgnored`; `static GenerateContentConfig GeminiRequests.toConfig(InferenceRequest, Map<String, String> providerProperties, JsonMapper)` beside the unchanged public two-argument form; `GeminiInferenceProvider(GeminiClient, JsonMapper, Map<String, String>)`.
- Produces (public): `GeminiProviderConfig property(String, String)`, `properties(Map<String, String>)`; `GeminiInferenceProvider.validate(InferenceOptions)`.

- [ ] **Step 1: Write the failing tests**

`GeminiRequestsTest.java` -- add imports `com.google.genai.types.GenerateContentConfig`, `com.google.genai.types.ThinkingConfig`, `java.util.Map`, `static org.assertj.core.api.Assertions.assertThatThrownBy`, `org.assertj.core.api.InstanceOfAssertFactories`, then:

```java
  @Nested
  class TheVendorProperties {

    private static final String THINKING = "gemini.generationConfig.thinkingConfig.";

    private static InferenceRequest carrying(Map<String, String> agentType) {
      return new InferenceRequest(
          SYSTEM,
          InferenceContext.of(List.of(open(1, "hi"))),
          Toolset.none(),
          new InferenceOptions("gemini-3.6-pro", 1024, agentType));
    }

    private static GenerateContentConfig configFor(Map<String, String> agentType) {
      return GeminiRequests.toConfig(carrying(agentType), Map.of(), MAPPER);
    }

    @Test
    void the_thinking_names_land_in_the_typed_thinking_config() {
      ThinkingConfig thinking =
          configFor(
                  Map.of(
                      THINKING + "thinkingBudget", "2048",
                      THINKING + "includeThoughts", "true",
                      THINKING + "thinkingLevel", "low"))
              .thinkingConfig()
              .orElseThrow();

      assertThat(thinking.thinkingBudget()).contains(2048);
      assertThat(thinking.includeThoughts()).contains(true);
      assertThat(thinking.thinkingLevel().orElseThrow().toString()).isEqualToIgnoringCase("low");
    }

    @Test
    void without_them_no_thinking_config_is_set() {
      assertThat(configFor(Map.of()).thinkingConfig()).isEmpty();
      assertThat(configFor(Map.of()).httpOptions()).isEmpty();
    }

    @Test
    void an_unknown_name_passes_through_in_the_extra_body_nested_by_path() {
      GenerateContentConfig config =
          configFor(
              Map.of(
                  "gemini.generationConfig.temperature", "0.2",
                  "gemini.labels.team", "billing"));

      Map<String, Object> body = config.httpOptions().orElseThrow().extraBody().orElseThrow();
      assertThat(body)
          .extractingByKey("generationConfig", InstanceOfAssertFactories.MAP)
          .containsEntry("temperature", 0.2);
      assertThat(body)
          .extractingByKey("labels", InstanceOfAssertFactories.MAP)
          .containsEntry("team", "billing");
    }

    @Test
    void another_prefix_is_not_sent() {
      assertThat(configFor(Map.of("openai.seed", "1")).httpOptions()).isEmpty();
    }

    @Test
    void an_agent_type_entry_overrides_the_same_name_given_to_the_provider() {
      GenerateContentConfig config =
          GeminiRequests.toConfig(
              carrying(Map.of(THINKING + "thinkingBudget", "512")),
              Map.of(THINKING + "thinkingBudget", "4096"),
              MAPPER);

      assertThat(config.thinkingConfig().orElseThrow().thinkingBudget()).contains(512);
    }

    @Test
    void the_conversation_is_refused_as_what_the_engine_assembles() {
      InferenceRequest request = carrying(Map.of("gemini.contents", "[]"));

      assertThatThrownBy(() -> GeminiRequests.toConfig(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'gemini.contents'");
    }

    @Test
    void the_ceiling_is_refused_under_its_generation_config_spelling() {
      InferenceRequest request = carrying(Map.of("gemini.generationConfig.maxOutputTokens", "9"));

      assertThatThrownBy(() -> GeminiRequests.toConfig(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("InferenceConfig.maxTokens");
    }

    @Test
    void a_pass_through_under_the_thinking_config_beside_a_known_name_is_refused() {
      InferenceRequest request =
          carrying(Map.of(THINKING + "thinkingBudget", "512", THINKING + "mode", "deep"));

      assertThatThrownBy(() -> GeminiRequests.toConfig(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'" + THINKING + "mode'")
          .hasMessageContaining("'" + THINKING + "thinkingBudget'");
    }

    @Test
    void a_bad_budget_is_refused_naming_the_property_and_the_value() {
      InferenceRequest request = carrying(Map.of(THINKING + "thinkingBudget", "lots"));

      assertThatThrownBy(() -> GeminiRequests.toConfig(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "property '" + THINKING + "thinkingBudget' must be an integer, was 'lots'");
    }
  }
```

`GeminiProviderConfigTest.java` -- add (imports `java.util.Map`, `org.jwcarman.nessy.api.Customizer`, `org.jwcarman.nessy.inference.InferenceOptions`):

```java
  @Test
  void a_property_under_another_prefix_is_refused_at_build_naming_the_prefix() {
    Customizer<GeminiProviderConfig> customizer =
        c -> c.apiKey("test-key").property("gcp.gemini.seed", "1");

    assertThatThrownBy(() -> GeminiInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'gcp.gemini.seed'")
        .hasMessageContaining("'gemini.'");
  }

  /**
   * The config's map reaches the provider: only a provider holding the config's thinking budget
   * can find an agent type's entry under the same thinking config to be a clash (plan ruling 11).
   */
  @Test
  void a_property_on_the_config_reaches_the_provider() {
    GeminiInferenceProvider provider =
        GeminiInferenceProvider.of(
            c ->
                c.apiKey("test-key")
                    .property("gemini.generationConfig.thinkingConfig.thinkingBudget", "1024"));
    InferenceOptions options =
        new InferenceOptions(
            "gemini-3.6-flash",
            4096,
            Map.of("gemini.generationConfig.thinkingConfig.mode", "deep"));
    try {
      assertThatThrownBy(() -> provider.validate(options))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("thinkingBudget");
    } finally {
      provider.close();
    }
  }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -pl :nessy-inference-gemini -am test -Dtest='GeminiRequestsTest,GeminiProviderConfigTest' -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: compilation failure; `exit=1`.

- [ ] **Step 3: Create `GeminiProperties`**

`nessy-inference/gemini/pom.xml`: add the `slf4j-api` dependency exactly as in Task 5 Step 4.

`GeminiProperties.java` (license header, then):

```java
package org.jwcarman.nessy.inference.gemini;

import com.google.genai.types.ThinkingConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.vendor.VendorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code gemini.} vendor properties (spec §9d). Names are spelled as the Gemini REST reference
 * spells them, {@code generationConfig} included, so a known name reads like the pass-through
 * beside it. The three thinking names go through the SDK's typed config, so they cannot depend on
 * how {@code extraBody} merges; everything else under the prefix is sent through {@code
 * extraBody}.
 */
final class GeminiProperties {

  static final String PREFIX = "gemini.";
  private static final String THINKING_CONFIG = "generationConfig.thinkingConfig";
  static final String THINKING_BUDGET = THINKING_CONFIG + ".thinkingBudget";
  static final String INCLUDE_THOUGHTS = THINKING_CONFIG + ".includeThoughts";
  static final String THINKING_LEVEL = THINKING_CONFIG + ".thinkingLevel";

  private static final Set<String> KNOWN = Set.of(THINKING_BUDGET, INCLUDE_THOUGHTS, THINKING_LEVEL);

  private static final String SHAPE = "the answer's shape the harness asks for";

  /** What the typed settings, or the adapter itself, already decide (§9d). */
  private static final Map<String, String> CLASHES =
      Map.of(
          "contents", "the conversation the engine assembles",
          "systemInstruction", "the system prompt the harness sends",
          "tools", "the tools the harness binds",
          "toolConfig", "the tool choice the engine makes",
          "generationConfig.maxOutputTokens", "InferenceConfig.maxTokens",
          "generationConfig.responseMimeType", SHAPE,
          "generationConfig.responseJsonSchema", SHAPE,
          "generationConfig.responseSchema", SHAPE);

  private static final Logger log = LoggerFactory.getLogger(GeminiProperties.class);

  private GeminiProperties() {}

  /** The {@code gemini.} properties once read: a typed thinking config, and the extra body. */
  record Read(Optional<ThinkingConfig> thinking, Map<String, Object> passThrough) {}

  static Read read(Map<String, String> merged, JsonMapper mapper) {
    Map<String, String> own = VendorProperties.under(merged, PREFIX);
    Map<String, String> clashes = new LinkedHashMap<>(CLASHES);
    String known =
        own.keySet().stream().filter(KNOWN::contains).findFirst().orElse(null);
    if (known != null) {
      // Plan ruling 6: the typed thinking config owns its object.
      for (String name : own.keySet()) {
        boolean under = name.equals(THINKING_CONFIG) || name.startsWith(THINKING_CONFIG + ".");
        if (under && !KNOWN.contains(name)) {
          clashes.put(name, "property '" + PREFIX + known + "'");
        }
      }
    }
    VendorProperties.refuseClashes(PREFIX, own, clashes);
    Optional<ThinkingConfig> thinking = Optional.empty();
    if (known != null) {
      ThinkingConfig.Builder builder = ThinkingConfig.builder();
      if (own.containsKey(THINKING_BUDGET)) {
        builder.thinkingBudget(
            VendorProperties.requireInteger(PREFIX + THINKING_BUDGET, own.get(THINKING_BUDGET)));
      }
      if (own.containsKey(INCLUDE_THOUGHTS)) {
        builder.includeThoughts(
            VendorProperties.requireBoolean(PREFIX + INCLUDE_THOUGHTS, own.get(INCLUDE_THOUGHTS)));
      }
      if (own.containsKey(THINKING_LEVEL)) {
        builder.thinkingLevel(
            VendorProperties.requireString(PREFIX + THINKING_LEVEL, own.get(THINKING_LEVEL)));
      }
      thinking = Optional.of(builder.build());
    }
    Map<String, String> rest = new LinkedHashMap<>(own);
    rest.keySet().removeAll(KNOWN);
    return new Read(thinking, VendorProperties.nest(PREFIX, rest, mapper));
  }

  /** A provider is one adapter: a provider-level entry under another prefix is a mistake (§6a). */
  static void requireOwn(Map<String, String> properties) {
    for (String name : properties.keySet()) {
      if (!name.startsWith(PREFIX)) {
        throw new IllegalArgumentException(
            "property '"
                + name
                + "' is not under '"
                + PREFIX
                + "'; a provider reads only its own prefix");
      }
    }
  }

  static void logIgnored(Map<String, String> merged) {
    if (log.isDebugEnabled()) {
      List<String> others = merged.keySet().stream().filter(n -> !n.startsWith(PREFIX)).toList();
      if (!others.isEmpty()) {
        log.debug("NESSY INFERENCE: properties for other adapters, ignored here: {}", others);
      }
    }
  }
}
```

(`own.keySet()` of `under`'s map keeps insertion order, so `known` is the first known name given.)

- [ ] **Step 4: The projection, the provider and the config**

`GeminiRequests.java` (imports `com.google.genai.types.HttpOptions`, `org.jwcarman.nessy.vendor.VendorProperties`): the public two-argument `toConfig` keeps its signature and javadoc and becomes `return toConfig(request, Map.of(), mapper);`. The body moves to:

```java
  /**
   * @param providerProperties the provider's own {@code gemini.} map, overlaid here by the agent
   *     type's (spec §7a)
   */
  static GenerateContentConfig toConfig(
      InferenceRequest request, Map<String, String> providerProperties, JsonMapper mapper) {
    GeminiProperties.Read read =
        GeminiProperties.read(
            VendorProperties.merge(providerProperties, request.options().properties()), mapper);
    GenerateContentConfig.Builder builder = GenerateContentConfig.builder();
    // ... unchanged, from the maxOutputTokens line through askForShape ...
    read.thinking().ifPresent(builder::thinkingConfig);
    if (!read.passThrough().isEmpty()) {
      // Per request, overlaid by the SDK on the client's own options field by field: the base URL
      // and timeout the client was built with stay (ApiClient.mergeHttpOptions).
      builder.httpOptions(HttpOptions.builder().extraBody(read.passThrough()).build());
    }
    return builder.build();
  }
```

`GeminiInferenceProvider.java` (imports `java.util.Map`, `org.jwcarman.nessy.inference.InferenceOptions`, `org.jwcarman.nessy.vendor.VendorProperties`):

```java
  /** The provider's own {@code gemini.} properties, checked at build; the agent type's overlay them. */
  private final Map<String, String> properties;

  GeminiInferenceProvider(GeminiClient client, JsonMapper mapper) {
    this(client, mapper, Map.of());
  }

  GeminiInferenceProvider(GeminiClient client, JsonMapper mapper, Map<String, String> properties) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    this.properties = Map.copyOf(properties);
  }

  /** Reads the merged properties exactly as a request would, so a mistake fails the build (§7c). */
  @Override
  public void validate(InferenceOptions options) {
    Map<String, String> merged = VendorProperties.merge(properties, options.properties());
    GeminiProperties.read(merged, mapper);
    GeminiProperties.logIgnored(merged);
  }
```

and in `infer`, `GeminiRequests.toConfig(request, mapper)` becomes `GeminiRequests.toConfig(request, properties, mapper)`.

`GeminiProviderConfig.java` (imports `java.util.Collections`, `java.util.LinkedHashMap`, `java.util.Map`, `org.jwcarman.nessy.vendor.VendorProperties`): the `properties` field, `property(String, String)` and `properties(Map<String, String>)` exactly as the chat config's (Task 3 Step 7, returning `GeminiProviderConfig`, javadoc naming `gemini.generationConfig.thinkingConfig.thinkingBudget`), and:

```java
  GeminiInferenceProvider build() {
    GeminiProperties.requireOwn(properties);
    GeminiProperties.read(properties, mapper);
    return new GeminiInferenceProvider(
        resolveClient(), mapper, Collections.unmodifiableMap(new LinkedHashMap<>(properties)));
  }
```

- [ ] **Step 5: Run them to see them pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-inference-gemini -am test; echo exit=$?`
Expected: `exit=0`.

- [ ] **Step 6: Full gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?`
Expected: `exit=0`.

```bash
cd /Users/jcarman/IdeaProjects/nessy-props
git add nessy-inference/gemini
git commit -m "feat: the Gemini adapter reads gemini. properties

The three generationConfig.thinkingConfig names land in the SDK's typed
ThinkingConfig; every other gemini. name is sent through the request's
HttpOptions extraBody, nested by path. Clashes and bad values fail at build.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 7: Bedrock -- three Converse fields typed, everything else into the model's document

Spec §6a, §8d, §9e, §13a. Suggested implementer: Sonnet. Review: Sonnet.

**Files:**
- Create: `nessy-inference/bedrock/src/main/java/org/jwcarman/nessy/inference/bedrock/BedrockProperties.java`
- Modify: `nessy-inference/bedrock/src/main/java/org/jwcarman/nessy/inference/bedrock/BedrockRequests.java` (`toRequest`, lines 76-116)
- Modify: `nessy-inference/bedrock/src/main/java/org/jwcarman/nessy/inference/bedrock/BedrockInferenceProvider.java` (fields and constructor lines 100-106, `infer` line 143)
- Modify: `nessy-inference/bedrock/src/main/java/org/jwcarman/nessy/inference/bedrock/BedrockProviderConfig.java`
- Modify: `nessy-inference/bedrock/pom.xml` (declare `slf4j-api`, ruling 16)
- Test: `nessy-inference/bedrock/src/test/java/org/jwcarman/nessy/inference/bedrock/BedrockRequestsTest.java`
- Test: `nessy-inference/bedrock/src/test/java/org/jwcarman/nessy/inference/bedrock/BedrockProviderConfigTest.java`

**Interfaces:**
- Consumes: `VendorProperties.*` (Task 1); `BedrockRequests.document(Object)` (existing, package-private: plain maps and lists into a `Document`). SDK, verified on bedrockruntime 2.55.5: `ConverseStreamRequest.Builder.additionalModelRequestFields(Document)`, `.inferenceConfig(Consumer<InferenceConfiguration.Builder>)`, `InferenceConfiguration.Builder.maxTokens(Integer)`, `.temperature(Float)`, `.topP(Float)`, `.stopSequences(Collection<String>)`.
- Produces (package-private): `BedrockProperties.PREFIX = "bedrock."`, `TEMPERATURE = "inferenceConfig.temperature"`, `TOP_P = "inferenceConfig.topP"`, `STOP_SEQUENCES = "inferenceConfig.stopSequences"`; `record BedrockProperties.Read(Optional<Float> temperature, Optional<Float> topP, Optional<List<String>> stopSequences, Map<String, Object> passThrough)`; `static Read read(Map<String, String>, JsonMapper)`; `requireOwn`, `logIgnored`; `static ConverseStreamRequest BedrockRequests.toRequest(InferenceRequest, Map<String, String> providerProperties, JsonMapper)` beside the unchanged public two-argument form; `BedrockInferenceProvider(BedrockClient, JsonMapper, Map<String, String>)`.
- Produces (public): `BedrockProviderConfig property(String, String)`, `properties(Map<String, String>)`; `BedrockInferenceProvider.validate(InferenceOptions)`.

- [ ] **Step 1: Write the failing tests**

`BedrockRequestsTest.java` -- add imports `java.util.Map`, `software.amazon.awssdk.core.document.Document`, `software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest` (if absent), `static org.assertj.core.api.Assertions.assertThatThrownBy`, then:

```java
  @Nested
  class TheVendorProperties {

    private static InferenceRequest carrying(Map<String, String> agentType) {
      return new InferenceRequest(
          SYSTEM,
          new InferenceContext(List.of(open(1, "hi")), List.of()),
          Toolset.none(),
          new InferenceOptions("us.anthropic.claude-haiku", 1024, agentType));
    }

    private static ConverseStreamRequest requestFor(Map<String, String> agentType) {
      return BedrockRequests.toRequest(carrying(agentType), Map.of(), MAPPER);
    }

    @Test
    void the_three_converse_names_land_in_the_typed_inference_config_beside_the_ceiling() {
      ConverseStreamRequest request =
          requestFor(
              Map.of(
                  "bedrock.inferenceConfig.temperature", "0.2",
                  "bedrock.inferenceConfig.topP", "0.9",
                  "bedrock.inferenceConfig.stopSequences", "[\"END\"]"));

      assertThat(request.inferenceConfig().temperature()).isEqualTo(0.2f);
      assertThat(request.inferenceConfig().topP()).isEqualTo(0.9f);
      assertThat(request.inferenceConfig().stopSequences()).containsExactly("END");
      assertThat(request.inferenceConfig().maxTokens()).isEqualTo(1024);
    }

    /** Claude's extended thinking on Bedrock is two properties and no code (§9e). */
    @Test
    void every_other_name_goes_into_the_model_s_own_document_nested_by_path() {
      ConverseStreamRequest request =
          requestFor(
              Map.of(
                  "bedrock.thinking.type", "enabled", "bedrock.thinking.budget_tokens", "4096"));

      Document thinking = request.additionalModelRequestFields().asMap().get("thinking");
      assertThat(thinking.asMap().get("type").asString()).isEqualTo("enabled");
      assertThat(thinking.asMap().get("budget_tokens").asNumber().intValue()).isEqualTo(4096);
    }

    @Test
    void without_properties_no_model_document_is_sent() {
      assertThat(requestFor(Map.of()).additionalModelRequestFields()).isNull();
    }

    @Test
    void another_prefix_is_not_sent() {
      assertThat(requestFor(Map.of("anthropic.top_k", "5")).additionalModelRequestFields())
          .isNull();
    }

    @Test
    void an_agent_type_entry_overrides_the_same_name_given_to_the_provider() {
      ConverseStreamRequest request =
          BedrockRequests.toRequest(
              carrying(Map.of("bedrock.inferenceConfig.temperature", "0.1")),
              Map.of("bedrock.inferenceConfig.temperature", "0.9"),
              MAPPER);

      assertThat(request.inferenceConfig().temperature()).isEqualTo(0.1f);
    }

    @Test
    void the_model_is_refused_under_its_converse_spelling() {
      InferenceRequest request = carrying(Map.of("bedrock.modelId", "x"));

      assertThatThrownBy(() -> BedrockRequests.toRequest(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'bedrock.modelId'")
          .hasMessageContaining("InferenceConfig.model");
    }

    @Test
    void the_ceiling_is_refused_under_its_converse_spelling() {
      InferenceRequest request = carrying(Map.of("bedrock.inferenceConfig.maxTokens", "9"));

      assertThatThrownBy(() -> BedrockRequests.toRequest(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("InferenceConfig.maxTokens");
    }

    @Test
    void a_temperature_that_is_not_a_number_is_refused_naming_the_value() {
      InferenceRequest request = carrying(Map.of("bedrock.inferenceConfig.temperature", "hot"));

      assertThatThrownBy(() -> BedrockRequests.toRequest(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property 'bedrock.inferenceConfig.temperature' must be a number, was 'hot'");
    }

    @Test
    void stop_sequences_that_are_not_an_array_of_strings_are_refused() {
      InferenceRequest request =
          carrying(Map.of("bedrock.inferenceConfig.stopSequences", "[1, 2]"));

      assertThatThrownBy(() -> BedrockRequests.toRequest(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must be a JSON array of strings");
    }
  }
```

`BedrockProviderConfigTest.java` -- add (imports `java.util.Map`, `org.jwcarman.nessy.api.Customizer`, `org.jwcarman.nessy.inference.InferenceOptions`):

```java
  @Test
  void a_property_under_another_prefix_is_refused_at_build_naming_the_prefix() {
    Customizer<BedrockProviderConfig> customizer =
        c ->
            c.region(Region.US_EAST_1)
                .credentialsProvider(CREDENTIALS)
                .property("aws.bedrock.thinking.type", "enabled");

    assertThatThrownBy(() -> BedrockInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'aws.bedrock.thinking.type'")
        .hasMessageContaining("'bedrock.'");
  }

  /**
   * The config's map reaches the provider: the config's pass-through {@code bedrock.thinking.type}
   * and an agent type's value at {@code bedrock.thinking} can only collide in the merged map, so
   * only a provider holding the config's entry refuses them (plan ruling 11).
   */
  @Test
  void a_property_on_the_config_reaches_the_provider() {
    BedrockInferenceProvider provider =
        BedrockInferenceProvider.of(
            c ->
                c.region(Region.US_EAST_1)
                    .credentialsProvider(CREDENTIALS)
                    .property("bedrock.thinking.type", "enabled"));
    InferenceOptions options = new InferenceOptions("m", 512, Map.of("bedrock.thinking", "true"));
    try {
      assertThatThrownBy(() -> provider.validate(options))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'bedrock.thinking'")
          .hasMessageContaining("'bedrock.thinking.type'");
    } finally {
      provider.close();
    }
  }

  @Test
  void a_bad_value_on_the_config_is_refused_at_build() {
    Customizer<BedrockProviderConfig> customizer =
        c ->
            c.region(Region.US_EAST_1)
                .credentialsProvider(CREDENTIALS)
                .property("bedrock.inferenceConfig.temperature", "hot");

    assertThatThrownBy(() -> BedrockInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'bedrock.inferenceConfig.temperature'");
  }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -pl :nessy-inference-bedrock -am test -Dtest='BedrockRequestsTest,BedrockProviderConfigTest' -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: compilation failure; `exit=1`.

- [ ] **Step 3: Create `BedrockProperties`**

`nessy-inference/bedrock/pom.xml`: add the `slf4j-api` dependency exactly as in Task 5 Step 4.

`BedrockProperties.java` (license header, then):

```java
package org.jwcarman.nessy.inference.bedrock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.vendor.VendorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code bedrock.} vendor properties (spec §9e). Converse's own fields are typed on a typed AWS
 * request and cannot be added arbitrarily, so three of them are known names; everything else under
 * the prefix is the model's own document, {@code additionalModelRequestFields}.
 */
final class BedrockProperties {

  static final String PREFIX = "bedrock.";
  static final String TEMPERATURE = "inferenceConfig.temperature";
  static final String TOP_P = "inferenceConfig.topP";
  static final String STOP_SEQUENCES = "inferenceConfig.stopSequences";

  private static final Set<String> KNOWN = Set.of(TEMPERATURE, TOP_P, STOP_SEQUENCES);

  /** What the typed settings, or the adapter itself, already decide (§9e). */
  private static final Map<String, String> CLASHES =
      Map.of(
          "modelId", "InferenceConfig.model",
          "messages", "the conversation the engine assembles",
          "system", "the system prompt the harness sends",
          "toolConfig", "the tools the harness binds",
          "inferenceConfig.maxTokens", "InferenceConfig.maxTokens");

  private static final Logger log = LoggerFactory.getLogger(BedrockProperties.class);

  private BedrockProperties() {}

  /** The {@code bedrock.} properties once read. */
  record Read(
      Optional<Float> temperature,
      Optional<Float> topP,
      Optional<List<String>> stopSequences,
      Map<String, Object> passThrough) {

    boolean tunesInference() {
      return temperature.isPresent() || topP.isPresent() || stopSequences.isPresent();
    }
  }

  static Read read(Map<String, String> merged, JsonMapper mapper) {
    Map<String, String> own = VendorProperties.under(merged, PREFIX);
    VendorProperties.refuseClashes(PREFIX, own, CLASHES);
    Optional<Float> temperature =
        Optional.ofNullable(own.get(TEMPERATURE)).map(v -> number(TEMPERATURE, v, mapper));
    Optional<Float> topP = Optional.ofNullable(own.get(TOP_P)).map(v -> number(TOP_P, v, mapper));
    Optional<List<String>> stop =
        Optional.ofNullable(own.get(STOP_SEQUENCES)).map(v -> strings(STOP_SEQUENCES, v, mapper));
    Map<String, String> rest = new LinkedHashMap<>(own);
    rest.keySet().removeAll(KNOWN);
    return new Read(temperature, topP, stop, VendorProperties.nest(PREFIX, rest, mapper));
  }

  /** A provider is one adapter: a provider-level entry under another prefix is a mistake (§6a). */
  static void requireOwn(Map<String, String> properties) {
    for (String name : properties.keySet()) {
      if (!name.startsWith(PREFIX)) {
        throw new IllegalArgumentException(
            "property '"
                + name
                + "' is not under '"
                + PREFIX
                + "'; a provider reads only its own prefix");
      }
    }
  }

  static void logIgnored(Map<String, String> merged) {
    if (log.isDebugEnabled()) {
      List<String> others = merged.keySet().stream().filter(n -> !n.startsWith(PREFIX)).toList();
      if (!others.isEmpty()) {
        log.debug("NESSY INFERENCE: properties for other adapters, ignored here: {}", others);
      }
    }
  }

  /** The type, never the range: whether 1.7 is a temperature is the vendor's to say. */
  private static float number(String name, String value, JsonMapper mapper) {
    if (VendorProperties.literal(value, mapper) instanceof Number number) {
      return number.floatValue();
    }
    throw new IllegalArgumentException(
        "property '" + PREFIX + name + "' must be a number, was '" + value + "'");
  }

  private static List<String> strings(String name, String value, JsonMapper mapper) {
    if (VendorProperties.literal(value, mapper) instanceof List<?> list
        && list.stream().allMatch(String.class::isInstance)) {
      return list.stream().map(String.class::cast).toList();
    }
    throw new IllegalArgumentException(
        "property '" + PREFIX + name + "' must be a JSON array of strings, was '" + value + "'");
  }
}
```

(An empty array passes `allMatch` and is sent as no stop sequences, which is what `[]` says.)

- [ ] **Step 4: The projection, the provider and the config**

`BedrockRequests.java` (import `org.jwcarman.nessy.vendor.VendorProperties`): the public `toRequest(InferenceRequest, JsonMapper)` keeps its signature and becomes `return toRequest(request, Map.of(), mapper);`. The body moves to a package-private three-argument form whose opening replaces today's `inferenceConfig` lines:

```java
  /**
   * @param providerProperties the provider's own {@code bedrock.} map, overlaid here by the agent
   *     type's (spec §7a)
   */
  static ConverseStreamRequest toRequest(
      InferenceRequest request, Map<String, String> providerProperties, JsonMapper mapper) {
    BedrockProperties.Read read =
        BedrockProperties.read(
            VendorProperties.merge(providerProperties, request.options().properties()), mapper);
    ConverseStreamRequest.Builder builder =
        ConverseStreamRequest.builder().modelId(request.options().modelName());
    if (request.options().hasMaxTokens() || read.tunesInference()) {
      builder.inferenceConfig(
          config -> {
            if (request.options().hasMaxTokens()) {
              config.maxTokens(request.options().maxTokens());
            }
            read.temperature().ifPresent(config::temperature);
            read.topP().ifPresent(config::topP);
            read.stopSequences().ifPresent(config::stopSequences);
          });
    }
    // ... unchanged: system, messages, tools, askForShape ...
    if (!read.passThrough().isEmpty()) {
      builder.additionalModelRequestFields(document(read.passThrough()));
    }
    return builder.build();
  }
```

`BedrockInferenceProvider.java` (imports `java.util.Map`, `org.jwcarman.nessy.inference.InferenceOptions`, `org.jwcarman.nessy.vendor.VendorProperties`) -- the same shape as Task 6's Gemini provider: a `properties` field, the two-argument constructor delegating to `BedrockInferenceProvider(BedrockClient client, JsonMapper mapper, Map<String, String> properties)` storing `Map.copyOf(properties)`, `infer` calling `BedrockRequests.toRequest(request, properties, mapper)`, and

```java
  /** Reads the merged properties exactly as a request would, so a mistake fails the build (§7c). */
  @Override
  public void validate(InferenceOptions options) {
    Map<String, String> merged = VendorProperties.merge(properties, options.properties());
    BedrockProperties.read(merged, mapper);
    BedrockProperties.logIgnored(merged);
  }
```

`BedrockProviderConfig.java` (imports `java.util.Collections`, `java.util.LinkedHashMap`, `java.util.Map`, `org.jwcarman.nessy.vendor.VendorProperties`): the `properties` field, `property(String, String)` and `properties(Map<String, String>)` exactly as the chat config's (Task 3 Step 7, returning `BedrockProviderConfig`, javadoc naming `bedrock.thinking.budget_tokens`), and:

```java
  BedrockInferenceProvider build() {
    BedrockProperties.requireOwn(properties);
    BedrockProperties.read(properties, mapper);
    return new BedrockInferenceProvider(
        resolveClient(), mapper, Collections.unmodifiableMap(new LinkedHashMap<>(properties)));
  }
```

- [ ] **Step 5: Run them to see them pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-inference-bedrock -am test; echo exit=$?`
Expected: `exit=0`.

- [ ] **Step 6: Full gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?`
Expected: `exit=0`.

```bash
cd /Users/jcarman/IdeaProjects/nessy-props
git add nessy-inference/bedrock
git commit -m "feat: the Bedrock adapter reads bedrock. properties

inferenceConfig.temperature, topP and stopSequences land in Converse's typed
inference config; every other bedrock. name goes into the model's own
additionalModelRequestFields document, so Claude's thinking on Bedrock is two
properties and no code. Clashes and bad values fail at build.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 8: The embedder configs carry properties, and ignore them

Spec §6a (the four embedder configs), §9f; plan ruling 13. Every code block below is complete: this is transcription. Suggested implementer: **Haiku** (transcription). Review: Sonnet.

**Files:**
- Modify: `nessy-embedding/openai/src/main/java/org/jwcarman/nessy/embedding/openai/OpenAiEmbedderConfig.java`
- Modify: `nessy-embedding/gemini/src/main/java/org/jwcarman/nessy/embedding/gemini/GeminiEmbedderConfig.java`
- Modify: `nessy-embedding/bedrock/src/main/java/org/jwcarman/nessy/embedding/bedrock/BedrockEmbedderConfig.java`
- Modify: `nessy-embedding/voyage/src/main/java/org/jwcarman/nessy/embedding/voyage/VoyageEmbedderConfig.java`
- Test (create): `nessy-embedding/openai/src/test/java/org/jwcarman/nessy/embedding/openai/OpenAiEmbedderConfigTest.java`, and the same for `gemini`, `bedrock`, `voyage` (`GeminiEmbedderConfigTest`, `BedrockEmbedderConfigTest`, `VoyageEmbedderConfigTest`)

**Interfaces:**
- Consumes: `VendorProperties.requireString(String, String)` (Task 1; reached through `nessy-embedding-spi`).
- Produces (public): on each of `OpenAiEmbedderConfig`, `GeminiEmbedderConfig`, `BedrockEmbedderConfig`, `VoyageEmbedderConfig`: `XEmbedderConfig property(String name, String value)` and `XEmbedderConfig properties(Map<String, String> properties)`. Prefixes: `openai.`, `gemini.`, `bedrock.`, `voyage.`.

- [ ] **Step 1: Write the failing tests**

`OpenAiEmbedderConfigTest.java` (license header, then):

```java
package org.jwcarman.nessy.embedding.openai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Customizer;

/** Properties are carried and not yet read (spec §9f); another prefix is still a mistake. */
class OpenAiEmbedderConfigTest {

  @Test
  void a_property_under_its_own_prefix_builds() {
    assertThatCode(
            () ->
                OpenAiEmbeddingProvider.of(
                        c -> c.apiKey("test-key").property("openai.user", "tenant-42"))
                    .close())
        .doesNotThrowAnyException();
  }

  @Test
  void a_property_under_another_prefix_is_refused_at_build_naming_the_prefix() {
    Customizer<OpenAiEmbedderConfig> customizer =
        c -> c.apiKey("test-key").properties(Map.of("voyage.truncation", "false"));

    assertThatThrownBy(() -> OpenAiEmbeddingProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'voyage.truncation'")
        .hasMessageContaining("'openai.'");
  }

  @Test
  void a_blank_value_is_refused_naming_the_property() {
    Customizer<OpenAiEmbedderConfig> customizer =
        c -> c.apiKey("test-key").property("openai.user", " ");

    assertThatThrownBy(() -> OpenAiEmbeddingProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.user'");
  }
}
```

`GeminiEmbedderConfigTest.java` -- the same three tests in package `org.jwcarman.nessy.embedding.gemini`, with `GeminiEmbeddingProvider.of(...)`, `GeminiEmbedderConfig`, the own-prefix property `gemini.labels.team` = `billing`, the other-prefix property `openai.user` = `tenant-42` (expecting `'openai.user'` and `'gemini.'`), and the blank value on `gemini.labels.team`.

`BedrockEmbedderConfigTest.java` -- the same three tests in package `org.jwcarman.nessy.embedding.bedrock`, with `BedrockEmbeddingProvider.of(...)`, `BedrockEmbedderConfig`, every customizer starting `c.region(Region.US_EAST_1).credentialsProvider(CREDENTIALS)` where

```java
  private static final StaticCredentialsProvider CREDENTIALS =
      StaticCredentialsProvider.create(AwsBasicCredentials.create("akid", "secret"));
```

(imports `software.amazon.awssdk.auth.credentials.AwsBasicCredentials`, `software.amazon.awssdk.auth.credentials.StaticCredentialsProvider`, `software.amazon.awssdk.regions.Region`), the own-prefix property `bedrock.truncate` = `END`, the other-prefix property `openai.user` = `tenant-42` (expecting `'openai.user'` and `'bedrock.'`), and the blank value on `bedrock.truncate`.

`VoyageEmbedderConfigTest.java` -- the same three tests in package `org.jwcarman.nessy.embedding.voyage`, with `VoyageEmbeddingProvider.of(...)`, `VoyageEmbedderConfig`, `c.apiKey("test-key")`, the own-prefix property `voyage.truncation` = `false`, the other-prefix property `openai.user` = `tenant-42` (expecting `'openai.user'` and `'voyage.'`), and the blank value on `voyage.truncation`.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -pl :nessy-embedding-openai,:nessy-embedding-gemini,:nessy-embedding-bedrock,:nessy-embedding-voyage -am test -Dtest='*EmbedderConfigTest' -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: compilation failure (`cannot find symbol: method property`); `exit=1`.

- [ ] **Step 3: The four configs**

In each config, add the imports `java.util.LinkedHashMap`, `java.util.Map`, `org.jwcarman.nessy.vendor.VendorProperties` (keep `java.util.Objects`, already imported), and this block after the last existing setter -- shown for `OpenAiEmbedderConfig`; in the other three replace the class name in the two return types and the prefix constant's value (`"gemini."`, `"bedrock."`, `"voyage."`):

```java
  /** The prefix this embedder's properties are named under. */
  private static final String PROPERTY_PREFIX = "openai.";

  private final Map<String, String> properties = new LinkedHashMap<>();

  /**
   * A vendor property for this embedder's requests, named under its prefix. Carried and not yet
   * read: the embedding adapters read their properties from the named-embedders item on (spec
   * §9f). Repeatable; the last value given for a name wins. A name under another prefix fails at
   * build.
   */
  public OpenAiEmbedderConfig property(String name, String value) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    properties.put(name, VendorProperties.requireString(name, value));
    return this;
  }

  /** {@link #property(String, String)} for each entry. */
  public OpenAiEmbedderConfig properties(Map<String, String> properties) {
    Objects.requireNonNull(properties, "properties must not be null");
    properties.forEach(this::property);
    return this;
  }

  /** An embedder is one adapter: a property under another prefix is a mistake. */
  private void requireOwnProperties() {
    for (String name : properties.keySet()) {
      if (!name.startsWith(PROPERTY_PREFIX)) {
        throw new IllegalArgumentException(
            "property '"
                + name
                + "' is not under '"
                + PROPERTY_PREFIX
                + "'; an embedder reads only its own prefix");
      }
    }
  }
```

(Move the `PROPERTY_PREFIX` constant up among the class's other `static final` fields if the file groups them; `spotless:apply` does not reorder members.)

Then make `requireOwnProperties();` the first statement of each config's `build()`: in `OpenAiEmbedderConfig.build()` above `if (client != null)`; in `GeminiEmbedderConfig.build()` above its `return`; in `BedrockEmbedderConfig.build()` above its `return`; in `VoyageEmbedderConfig.build()` above `String key = apiKey;`. Nothing else in `build()` changes: the map is not handed to the provider.

- [ ] **Step 4: Run them to see them pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-embedding-openai,:nessy-embedding-gemini,:nessy-embedding-bedrock,:nessy-embedding-voyage -am test; echo exit=$?`
Expected: `exit=0`.

- [ ] **Step 5: Full gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?`
Expected: `exit=0`.

```bash
cd /Users/jcarman/IdeaProjects/nessy-props
git add nessy-embedding/openai nessy-embedding/gemini nessy-embedding/bedrock nessy-embedding/voyage
git commit -m "feat: the embedder configs take vendor properties, carried until they are read

property and properties on the four embedder configs; an entry under another
adapter's prefix fails at build. The adapters read them with the named
embedders item.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 9: Boot -- `nessy.providers.<id>.properties`, the preset default, the report

Spec §6b, §6c, §11, §13b. Requires the rebase ("Before Task 1"): `WireProviders` has the `OpenAiResponses` builder. Suggested implementer: Sonnet. Review: Sonnet.

**Files:**
- Modify (all in `nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/inference/`): `ProviderSettings.java`, `ResolvedProvider.java`, `Preset.java`, `ProviderCatalogue.java`, `WireProviders.java`, `InferenceReport.java`
- Test (same package under `src/test/java`): `InferenceProvidersAutoConfigurationTest.java`, `ProviderCatalogueTest.java`, `ProviderSettingsTest.java`, `ResolvedProviderTest.java`, `InferenceReportTest.java`, `WireProvidersTest.java`
- Create: `nessy-spring-boot/autoconfigure/src/test/resources/vendor-properties.yaml`

**Interfaces:**
- Consumes: `OpenAiChatProviderConfig.properties(Map)` (Task 3), `OpenAiResponsesProviderConfig.properties(Map)` (Task 4), `AnthropicProviderConfig.properties(Map)` (Task 5), `GeminiProviderConfig.properties(Map)` (Task 6).
- Produces (package-private records): `ProviderSettings(@Nullable Wire wire, @Nullable String baseUrl, @Nullable String apiKey, @Nullable Boolean enabled, @Nullable String vendor, @Nullable Map<String, String> properties)`; `ResolvedProvider(String id, Wire wire, @Nullable String baseUrl, String vendor, @Nullable String apiKey, Map<String, String> properties)` plus a five-argument constructor passing `Map.of()`; `Preset(String id, Wire wire, @Nullable String baseUrl, String vendor, List<String> keyProperties, @Nullable String keylessApiKey, Map<String, String> defaultProperties)` plus a six-argument constructor passing `Map.of()`.

- [ ] **Step 1: Write the failing unit tests**

`ProviderSettingsTest.java` -- the existing construction gains a trailing `null`; add:

```java
  @Test
  void tostring_prints_property_names_never_values() {
    ProviderSettings settings =
        new ProviderSettings(
            Wire.OPENAI_CHAT, "https://g/v1", null, null, null, Map.of("openai.user", "tenant-42"));

    assertThat(settings.toString()).contains("openai.user").doesNotContain("tenant-42");
  }
```

`ResolvedProviderTest.java` -- add:

```java
  @Test
  void tostring_prints_property_names_never_values() {
    ResolvedProvider resolved =
        new ResolvedProvider(
            "openai", Wire.OPENAI_CHAT, null, "openai", "sk", Map.of("openai.user", "tenant-42"));

    assertThat(resolved.toString())
        .contains("openai.user")
        .doesNotContain("tenant-42")
        .contains("apiKey=***");
  }
```

(import `java.util.Map` in both.)

`ProviderCatalogueTest.java` -- every `new ProviderSettings(...)` gains a trailing `null` sixth argument:

```bash
cd /Users/jcarman/IdeaProjects/nessy-props
perl -0pi -e 's/new ProviderSettings\(((?:[^()]|\([^()]*\))*)\)/new ProviderSettings($1, null)/g' \
  nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/inference/ProviderCatalogueTest.java \
  nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/inference/ProviderSettingsTest.java
git grep -n "new ProviderSettings(" -- nessy-spring-boot/autoconfigure/src/test
```

Expected: every listed construction now has six arguments. (Run the perl line before adding the new six-argument test above, or it would gain a seventh.) The three expectations of an `openai` preset -- `an_openai_key_lights_openai` (line 46), `every_key_lights_its_own_preset` (line 115) and `the_openai_base_url_property_overrides_the_openai_preset` (lines 157-159) -- gain the preset's default as a sixth argument, `Map.of("openai.tools.strict", "true")`. Then add:

```java
  @Test
  void settings_overlay_a_preset_s_default_properties_name_by_name() {
    ProviderSettings openai =
        new ProviderSettings(
            null,
            null,
            null,
            null,
            null,
            Map.of("openai.tools.strict", "false", "openai.reasoning.effort", "high"));

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("openai", openai), Map.of("openai.api-key", "k")::get);

    assertThat(resolved)
        .singleElement()
        .extracting(ResolvedProvider::properties)
        .isEqualTo(Map.of("openai.tools.strict", "false", "openai.reasoning.effort", "high"));
  }

  @Test
  void a_preset_without_defaults_carries_no_properties() {
    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of(), Map.of("xai.api-key", "k")::get);

    assertThat(resolved).singleElement().extracting(ResolvedProvider::properties).isEqualTo(Map.of());
  }

  @Test
  void a_custom_provider_carries_only_its_own_properties() {
    ProviderSettings mine =
        new ProviderSettings(
            Wire.OPENAI_CHAT, "https://g/v1", "k", null, null, Map.of("openai.temperature", "0.2"));

    List<ResolvedProvider> resolved = ProviderCatalogue.resolve(Map.of("mine", mine), key -> null);

    assertThat(resolved)
        .singleElement()
        .extracting(ResolvedProvider::properties)
        .isEqualTo(Map.of("openai.temperature", "0.2"));
  }
```

`WireProvidersTest.java` (from the rebase) -- add (imports `java.util.Map`, `static org.assertj.core.api.Assertions.assertThatThrownBy`); each proves the resolved map reaches that wire's config, because only the config's own `build()` check can refuse it:

```java
  @Test
  void the_chat_wire_hands_its_properties_to_the_adapter() {
    ResolvedProvider resolved =
        new ResolvedProvider(
            "openai", Wire.OPENAI_CHAT, null, "openai", "k", Map.of("openai.model", "gpt-4o"));

    assertThatThrownBy(() -> WireProviders.build(resolved, null, LOADER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.model'");
  }

  @Test
  void the_responses_wire_hands_its_properties_to_the_adapter() {
    ResolvedProvider resolved =
        new ResolvedProvider(
            "mine",
            Wire.OPENAI_RESPONSES,
            "https://g/v1",
            "openai",
            "k",
            Map.of("openai.store", "true"));

    assertThatThrownBy(() -> WireProviders.build(resolved, null, LOADER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.store'");
  }

  @Test
  void the_anthropic_wire_hands_its_properties_to_the_adapter() {
    ResolvedProvider resolved =
        new ResolvedProvider(
            "anthropic",
            Wire.ANTHROPIC,
            null,
            "anthropic",
            "k",
            Map.of("anthropic.max_tokens", "9"));

    assertThatThrownBy(() -> WireProviders.build(resolved, null, LOADER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'anthropic.max_tokens'");
  }

  @Test
  void the_gemini_wire_hands_its_properties_to_the_adapter() {
    ResolvedProvider resolved =
        new ResolvedProvider(
            "gemini", Wire.GEMINI, null, "gcp.gemini", "k", Map.of("gemini.contents", "[]"));

    assertThatThrownBy(() -> WireProviders.build(resolved, null, LOADER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'gemini.contents'");
  }
```

- [ ] **Step 2: Write the failing context tests and the YAML resource**

`nessy-spring-boot/autoconfigure/src/test/resources/vendor-properties.yaml`:

```yaml
openai:
  api-key: sk-test
anthropic:
  api-key: sk-test
gemini:
  api-key: sk-test
nessy:
  providers:
    openai:
      properties:
        openai.tools.strict: "false"
        openai.reasoning.effort: high
    anthropic:
      properties:
        anthropic.thinking.budget_tokens: "2048"
    gemini:
      properties:
        gemini.generationConfig.thinkingConfig.thinkingBudget: "1024"
```

`InferenceProvidersAutoConfigurationTest.java` -- add imports `java.io.IOException`, `java.io.UncheckedIOException`, `org.junit.jupiter.api.Nested`, `org.springframework.boot.env.YamlPropertySourceLoader`, `org.springframework.boot.test.context.assertj.AssertableApplicationContext`, `org.springframework.core.io.ClassPathResource`, then a nested class:

```java
  @Nested
  @DisplayName("vendor properties")
  class TheVendorProperties {

    private static Map<String, String> propertiesOf(
        AssertableApplicationContext context, String id) {
      return context.getBean(ResolvedProviders.class).providers().stream()
          .filter(provider -> provider.id().equals(id))
          .findFirst()
          .orElseThrow()
          .properties();
    }

    @Test
    void the_openai_preset_carries_strict_tools_by_default() {
      runner
          .withPropertyValues("openai.api-key=sk-test")
          .run(
              context ->
                  assertThat(propertiesOf(context, "openai"))
                      .containsExactly(Map.entry("openai.tools.strict", "true")));
    }

    @Test
    void settings_override_the_preset_s_default() {
      runner
          .withPropertyValues(
              "openai.api-key=sk-test", "nessy.providers.openai.properties.openai.tools.strict=false")
          .run(
              context ->
                  assertThat(propertiesOf(context, "openai"))
                      .containsExactly(Map.entry("openai.tools.strict", "false")));
    }

    /** §6b's measurement pinned: a Map<String, String> keeps a dotted key whole. */
    @Test
    void a_dotted_key_binds_as_one_entry() {
      runner
          .withPropertyValues(
              "openai.api-key=sk-test",
              "nessy.providers.openai.properties.openai.reasoning.effort=high")
          .run(
              context ->
                  assertThat(propertiesOf(context, "openai"))
                      .containsOnly(
                          Map.entry("openai.tools.strict", "true"),
                          Map.entry("openai.reasoning.effort", "high")));
    }

    /** Review Focus 4: the vendor's own spelling survives the binder. */
    @Test
    void an_underscored_key_keeps_its_underscore() {
      runner
          .withPropertyValues(
              "anthropic.api-key=sk-test",
              "nessy.providers.anthropic.properties.anthropic.thinking.budget_tokens=2048")
          .run(
              context ->
                  assertThat(propertiesOf(context, "anthropic"))
                      .containsExactly(Map.entry("anthropic.thinking.budget_tokens", "2048")));
    }

    /** Review Focus 4. */
    @Test
    void a_camel_case_key_keeps_its_case() {
      runner
          .withPropertyValues(
              "gemini.api-key=sk-test",
              "nessy.providers.gemini.properties.gemini.generationConfig.thinkingConfig.thinkingBudget=1024")
          .run(
              context ->
                  assertThat(propertiesOf(context, "gemini"))
                      .containsExactly(
                          Map.entry(
                              "gemini.generationConfig.thinkingConfig.thinkingBudget", "1024")));
    }

    @Test
    void the_same_keys_given_as_yaml_bind_to_the_same_maps() {
      runner
          .withInitializer(
              context -> {
                try {
                  new YamlPropertySourceLoader()
                      .load("vendor-properties", new ClassPathResource("vendor-properties.yaml"))
                      .forEach(
                          source -> context.getEnvironment().getPropertySources().addFirst(source));
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              })
          .run(
              context -> {
                assertThat(propertiesOf(context, "openai"))
                    .containsOnly(
                        Map.entry("openai.tools.strict", "false"),
                        Map.entry("openai.reasoning.effort", "high"));
                assertThat(propertiesOf(context, "anthropic"))
                    .containsExactly(Map.entry("anthropic.thinking.budget_tokens", "2048"));
                assertThat(propertiesOf(context, "gemini"))
                    .containsExactly(
                        Map.entry("gemini.generationConfig.thinkingConfig.thinkingBudget", "1024"));
              });
    }

    @Test
    void a_clash_fails_startup_naming_the_property_and_the_typed_setting() {
      runner
          .withPropertyValues(
              "openai.api-key=sk-test", "nessy.providers.openai.properties.openai.model=gpt-4o")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("'openai.model'")
                    .hasMessageContaining("InferenceConfig.model");
              });
    }

    @Test
    void another_adapter_s_property_on_a_provider_fails_startup_naming_the_prefix() {
      runner
          .withPropertyValues(
              "anthropic.api-key=sk-test",
              "nessy.providers.anthropic.properties.openai.reasoning.effort=high")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("'openai.reasoning.effort'")
                    .hasMessageContaining("'anthropic.'");
              });
    }

    @Test
    void a_preset_without_defaults_carries_none() {
      runner
          .withPropertyValues("xai.api-key=xai-test")
          .run(context -> assertThat(propertiesOf(context, "xai")).isEmpty());
    }

    @Test
    void a_custom_provider_carries_its_one_entry() {
      runner
          .withPropertyValues(
              "nessy.providers.mine.wire=openai-chat",
              "nessy.providers.mine.base-url=https://g/v1",
              "nessy.providers.mine.api-key=k",
              "nessy.providers.mine.properties.openai.temperature=0.2")
          .run(
              context ->
                  assertThat(propertiesOf(context, "mine"))
                      .containsExactly(Map.entry("openai.temperature", "0.2")));
    }
  }
```

If `an_underscored_key_keeps_its_underscore`, `a_camel_case_key_keeps_its_case` or the YAML case fails because the binder changed the key's spelling, **stop and report**: that is a finding for James about the Boot binding (§6b), not something to fix by normalising keys here.

`InferenceReportTest.java` -- the first test's expected line gains the preset's clause:

```java
                  .contains(
                      "NESSY INFERENCE: providers: openai (openai-chat, the vendor's own"
                          + " endpoint, vendor openai, properties [openai.tools.strict]); xai"
                          + " (openai-chat, https://api.x.ai/v1, vendor x_ai)")
```

and add:

```java
  @Test
  void a_property_s_name_is_reported_and_its_value_never_is(CapturedOutput output) {
    runner
        .withPropertyValues(
            "openai.api-key=sk-super-secret",
            "nessy.providers.openai.properties.openai.user=tenant-42")
        .run(
            context -> {
              InferenceReport report =
                  new InferenceReport(context.getBeanProvider(ResolvedProviders.class), context);
              report.afterSingletonsInstantiated();

              assertThat(output)
                  .contains("properties [openai.tools.strict, openai.user]")
                  .doesNotContain("tenant-42")
                  .doesNotContain("sk-super-secret");
            });
  }
```

- [ ] **Step 3: Run them to see them fail**

Run: `./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test -Dtest='ProviderSettingsTest,ResolvedProviderTest,ProviderCatalogueTest,WireProvidersTest,InferenceProvidersAutoConfigurationTest,InferenceReportTest' -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: compilation failure (`no suitable constructor`, `cannot find symbol: method properties()`); `exit=1`.

- [ ] **Step 4: The records**

`ProviderSettings.java` (imports `java.util.Map`, `java.util.TreeSet`): add the component and print its names:

```java
record ProviderSettings(
    @Nullable Wire wire,
    @Nullable String baseUrl,
    @Nullable String apiKey,
    @Nullable Boolean enabled,
    @Nullable String vendor,
    @Nullable Map<String, String> properties) {

  /**
   * Redacts the key, and prints property names but never their values: the generated form would
   * otherwise print both in a log or a test failure, and a value may be sensitive (spec §6c).
   */
  @Override
  public String toString() {
    return "ProviderSettings[wire="
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

Add to the record's class javadoc: "{@code properties} are vendor properties for this provider (spec §6b), bound as {@code Map<String, String>} so a dotted key stays one entry; a preset's defaults are overlaid by them, name by name."

`ResolvedProvider.java` (imports `java.util.Map`, `java.util.TreeSet`):

```java
record ResolvedProvider(
    String id,
    Wire wire,
    @Nullable String baseUrl,
    String vendor,
    @Nullable String apiKey,
    Map<String, String> properties) {

  ResolvedProvider {
    properties = Map.copyOf(properties);
  }

  /** No properties. */
  ResolvedProvider(
      String id, Wire wire, @Nullable String baseUrl, String vendor, @Nullable String apiKey) {
    this(id, wire, baseUrl, vendor, apiKey, Map.of());
  }

  /** Redacts the key; prints property names, never values (spec §6c). */
  @Override
  public String toString() {
    return "ResolvedProvider[id="
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

`Preset.java` (import `java.util.Map`): add `Map<String, String> defaultProperties` as the last component, a six-argument constructor passing `Map.of()` (so the nine rows without defaults do not change), and change only the `openai` row:

```java
          new Preset(
              "openai",
              Wire.OPENAI_CHAT,
              null,
              "openai",
              List.of("openai.api-key"),
              null,
              // Measured to accept strict mode (spec §10); every other row waits for its own
              // measurement in PresetCandidatesLiveTest's strict column.
              Map.of("openai.tools.strict", "true")),
```

with the constructor:

```java
  /** No default properties -- every preset whose strict row has not been measured. */
  Preset(
      String id,
      Wire wire,
      @Nullable String baseUrl,
      String vendor,
      List<String> keyProperties,
      @Nullable String keylessApiKey) {
    this(id, wire, baseUrl, vendor, keyProperties, keylessApiKey, Map.of());
  }
```

- [ ] **Step 5: The overlay, the builders, the report**

`ProviderCatalogue.java` (imports `java.util.LinkedHashMap`): `EMPTY` gains a sixth `null`. In the preset loop, before `lit.add(`:

```java
      // The preset's defaults, overlaid name by name by what the application set (spec §11).
      Map<String, String> properties = new LinkedHashMap<>(preset.defaultProperties());
      if (own.properties() != null) {
        properties.putAll(own.properties());
      }
```

and the `new ResolvedProvider(` call there gains `properties` as its sixth argument. In `custom(...)`:

```java
    return new ResolvedProvider(
        id,
        own.wire(),
        own.baseUrl(),
        vendor,
        own.apiKey(),
        own.properties() != null ? own.properties() : Map.of());
```

`WireProviders.java` -- in each of the four nested builders (`OpenAiChat`, `OpenAiResponses`, `Anthropic`, `Gemini`), inside the customizer lambda after `c.timeout(TransportTimeouts.PROVIDER_TRANSPORT);`:

```java
            c.properties(resolved.properties());
```

`InferenceReport.java` (import `java.util.TreeSet`) -- in `describe(...)`, for a resolved provider, the closing `+ ")"` becomes:

```java
        + (resolved.properties().isEmpty()
            ? ""
            : ", properties " + new TreeSet<>(resolved.properties().keySet()))
        + ")";
```

and add to the class javadoc's last sentence: "Never the key, and never a property's value -- only its name."

- [ ] **Step 6: Run them to see them pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test; echo exit=$?`
Expected: `exit=0`.

- [ ] **Step 7: Full gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?`
Expected: `exit=0`.

```bash
cd /Users/jcarman/IdeaProjects/nessy-props
git add nessy-spring-boot/autoconfigure/src
git commit -m "feat: nessy.providers.<id>.properties, and the openai preset sends strict tools

Bound as Map<String, String> so a dotted key stays one entry; a preset's
default properties are overlaid by the settings name by name and handed to
the adapter's config. The openai preset defaults openai.tools.strict=true.
The report names each provider's properties and never prints a value.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 10: Live tests and the strict measurement

Spec §13d, §14 step 5. Every test here is `@Tag("live")` and skips cleanly with no key: `clean verify` never runs them. **The controller does not run them**; James runs them with his keys after the code is reviewed, and the results go into the ledger. The four adapter live classes that are not yet tagged (`OpenAiChatLiveTest`, `AnthropicLiveTest`, `GeminiLiveTest`, `BedrockLiveTest`) gain `@Tag("live")` on the class (import `org.junit.jupiter.api.Tag`), like `OpenAiResponsesLiveTest` and the Boot live tests already carry. Suggested implementer: Sonnet. Review: Sonnet.

**Files:**
- Modify: `nessy-inference/openai/src/test/java/org/jwcarman/nessy/inference/openai/OpenAiChatLiveTest.java`
- Modify: `nessy-inference/openai/src/test/java/org/jwcarman/nessy/inference/openai/OpenAiResponsesLiveTest.java`
- Modify: `nessy-inference/anthropic/src/test/java/org/jwcarman/nessy/inference/anthropic/AnthropicLiveTest.java`
- Modify: `nessy-inference/gemini/src/test/java/org/jwcarman/nessy/inference/gemini/GeminiLiveTest.java`
- Modify: `nessy-inference/bedrock/src/test/java/org/jwcarman/nessy/inference/bedrock/BedrockLiveTest.java`
- Modify: `nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/inference/PresetCandidatesLiveTest.java`

**Interfaces:**
- Consumes: `InferenceOptions(String, int, Map<String, String>)` (Task 1); every adapter's property reading (Tasks 3-7); `nessy.providers.<id>.properties.*` (Task 9); `Preset.CATALOGUE` and `Wire` (package-visible to the Boot test).
- Produces: new live cases; `target/preset-measurements.md` with a `strict` column; ledger entries: the Gemini `extraBody` merge answer (§16(5)), and each chat-wire vendor's strict result.

- [ ] **Step 1: `OpenAiChatLiveTest`**

Add `@Tag("live")` on the class; imports `ch.qos.logback.classic.Logger`, `ch.qos.logback.classic.spi.ILoggingEvent`, `ch.qos.logback.core.read.ListAppender`, `java.util.Map`, `org.junit.jupiter.api.Tag`, `org.jwcarman.nessy.inference.Failure`, `org.slf4j.LoggerFactory`, `tools.jackson.core.type.TypeReference`; then:

```java
  /** A model that reasons, for the effort case; the Responses live test's default. */
  private static final String REASONING_MODEL =
      System.getenv().getOrDefault("NESSY_LIVE_REASONING_MODEL", "gpt-6-sol");

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final Map<String, String> STRICT = Map.of("openai.tools.strict", "true");

  private static InferenceRequest carrying(
      String question,
      List<ToolOffer> tools,
      ToolChoice choice,
      String model,
      Map<String, String> properties) {
    return new InferenceRequest(
        new SystemPrompt("You are a terse assistant. Answer in one short sentence."),
        InferenceContext.of(
            List.of(
                new Turn(
                    new TurnId(1),
                    new Input(new Seq(1), List.of(new Block.Text(question))),
                    List.of(),
                    null,
                    0))),
        new Toolset(tools, choice),
        new InferenceOptions(model, 1024, properties));
  }

  record LakeQuery(String name, Optional<String> unit) {}

  /** §10 on a real wire: under strict mode the optional component is written, and still binds. */
  @Test
  void under_strict_tools_an_optional_component_is_written_and_binds() {
    ToolOffer lookup =
        new ToolOffer(
            new ToolName("lake_depth"),
            "returns the maximum depth of a named lake",
            new JsonSchema(
                """
                {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                 "properties":{"name":{"type":"string"},"unit":{"type":["string","null"]}},
                 "required":["name"]}"""));

    try (OpenAiChatInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              carrying(
                  "How deep is Loch Ness?", List.of(lookup), ToolChoice.auto(), MODEL, STRICT));

      assertThat(calledIn(result)).isEqualTo(new ToolName("lake_depth"));
      String arguments =
          ((InferenceResult.Actions) result)
              .blocks().stream()
                  .filter(Block.ToolCall.class::isInstance)
                  .map(Block.ToolCall.class::cast)
                  .findFirst()
                  .orElseThrow()
                  .arguments();
      Map<String, Object> written = MAPPER.readValue(arguments, new TypeReference<>() {});
      assertThat(written).as("strict mode requires every property").containsKey("unit");
      assertThat(MAPPER.readValue(arguments, LakeQuery.class).name()).containsIgnoringCase("Ness");
    }
  }

  /** A sealed vocabulary's oneOf falls back per tool, says so, and the call still goes through. */
  @Test
  void under_strict_tools_a_sealed_vocabulary_falls_back_and_is_still_called() {
    ToolOffer command =
        new ToolOffer(
            new ToolName("server_command"),
            "restarts a host or shuts down, as asked",
            new JsonSchema(
                """
                {"$schema":"https://json-schema.org/draft/2020-12/schema",
                 "oneOf":[{"type":"object","properties":{"host":{"type":"string"},
                                                         "type":{"const":"Restart"}},
                           "required":["host","type"]},
                          {"type":"object","properties":{"reason":{"type":["string","null"]},
                                                         "type":{"const":"Shutdown"}},
                           "required":["type"]}]}"""));
    Logger logger = (Logger) LoggerFactory.getLogger(OpenAiChatRequests.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    try (OpenAiChatInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              carrying(
                  "Restart the host called web-1.",
                  List.of(command),
                  new ToolChoice.Any(),
                  MODEL,
                  STRICT));

      assertThat(calledIn(result)).isEqualTo(new ToolName("server_command"));
      assertThat(appender.list).as("the fallback fired and said so").isNotEmpty();
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void a_reasoning_effort_reaches_a_reasoning_model() {
    try (OpenAiChatInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              carrying(
                  "What is 17 times 23?",
                  List.of(),
                  ToolChoice.auto(),
                  REASONING_MODEL,
                  Map.of("openai.reasoning.effort", "low")));

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
    }
  }

  /** §8c's promise made checkable once: a misspelled pass-through is the vendor's own 400. */
  @Test
  void a_misspelled_pass_through_is_the_vendor_s_own_refusal() {
    try (OpenAiChatInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              carrying(
                  "Say hello.",
                  List.of(),
                  ToolChoice.auto(),
                  MODEL,
                  Map.of("openai.temperatur", "0.2")));

      assertThat(result)
          .isInstanceOfSatisfying(
              InferenceResult.Fault.class,
              fault -> assertThat(fault.failure().reason()).containsIgnoringCase("temperatur"));
    }
  }
```

(`provider()`, `MODEL` and `calledIn` are the class's existing helpers. `ToolChoice.Any` is a record with no components, as `OpenAiResponsesLiveTest` constructs it.)

- [ ] **Step 2: `OpenAiResponsesLiveTest`**

Add (the class already has `REASONING_MODEL`, `provider()`, the `Narration` helper in the package, and `Map` imported):

```java
  /** §13d: the case the Responses record could not run -- a summary asked for, and narrated. */
  @Test
  void a_reasoning_summary_is_narrated_as_thinking() {
    InferenceRequest request =
        new InferenceRequest(
            new SystemPrompt("You are a careful assistant."),
            InferenceContext.of(List.of(turn("What is 17 times 23? Work it out.", List.of()))),
            Toolset.none(),
            new InferenceOptions(
                REASONING_MODEL,
                4096,
                Map.of("openai.reasoning.effort", "medium", "openai.reasoning.summary", "auto")));

    try (OpenAiResponsesInferenceProvider provider = provider()) {
      Narration narrated = new Narration();
      InferenceResult result = provider.infer(request, narrated);

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
      assertThat(narrated.fragments())
          .as("the summary deltas arrive as thinking")
          .anyMatch(fragment -> "thinking".equals(fragment.kind()));
    }
  }
```

- [ ] **Step 3: `AnthropicLiveTest`**

Add `@Tag("live")` on the class; imports `java.util.Map`, `org.junit.jupiter.api.Tag`, `org.jwcarman.nessy.api.Tokens`; then:

```java
  private static InferenceRequest carrying(
      SystemPrompt system, List<Turn> turns, Map<String, String> properties) {
    return new InferenceRequest(
        system, InferenceContext.of(turns), Toolset.none(), new InferenceOptions(MODEL, 2048, properties));
  }

  /** §9c's tier rule on the wire: a provider that does not think, an agent type that asks it to. */
  @Test
  void an_agent_type_s_budget_makes_a_provider_that_does_not_think_think() {
    try (AnthropicInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              carrying(
                  SYSTEM,
                  List.of(open(1, "Think about it, then say how many continents there are.")),
                  Map.of("anthropic.thinking.budget_tokens", "1024")));

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
      assertThat(((InferenceResult.Answer) result).blocks())
          .as("thinking was asked for, so the reply carries this vendor's own state")
          .anyMatch(Block.Provider.class::isInstance);
    }
  }

  /** A prefix long enough to cache, asked twice: a write, then a read, is reported. */
  @Test
  void a_ttl_property_caches_the_prefix() {
    SystemPrompt longPrompt =
        new SystemPrompt(
            "You are a terse assistant. "
                + "Background you may ignore: the loch is deep and cold. ".repeat(300));
    Map<String, String> cached = Map.of("anthropic.cache_control.ttl", "5m");

    try (AnthropicInferenceProvider provider = provider()) {
      InferenceResult first =
          provider.infer(carrying(longPrompt, List.of(open(1, "Say hello.")), cached));
      InferenceResult second =
          provider.infer(carrying(longPrompt, List.of(open(1, "Say hello.")), cached));

      assertThat(List.of(first.usage().cacheWriteTokens(), second.usage().cacheReadTokens()))
          .as("the prefix was written to the cache, or read back from it")
          .anyMatch(count -> count instanceof Tokens.Counted(int value) && value > 0);
    }
  }
```

(`SYSTEM`, `MODEL`, `open(...)` and `provider()` are the class's existing helpers; `InferenceResult.usage()` and `Usage.cacheWriteTokens()` / `cacheReadTokens()` return `Tokens`. The existing `reasoning_is_replayed_intact_on_the_next_turn` and `a_cached_request_is_accepted` stay unchanged.)

- [ ] **Step 4: `GeminiLiveTest` -- the `extraBody` measurement (§16(5))**

Add `@Tag("live")` on the class; imports `java.util.Map`, `org.junit.jupiter.api.Tag`; then:

```java
  /**
   * The one thing §8d could not settle from bytecode: whether a pass-through under {@code
   * generationConfig} merges into the SDK's own {@code generationConfig} or replaces it. The typed
   * thinking config lives in that same object on the wire, so thoughts narrated means it survived,
   * and an answer cut at the stop sequence means the pass-through arrived. Both: a merge. Record
   * which held in the ledger.
   */
  @Test
  void a_typed_thinking_config_and_a_generation_config_pass_through_both_arrive() {
    InferenceRequest request =
        new InferenceRequest(
            new SystemPrompt("You are a terse assistant."),
            InferenceContext.of(
                List.of(
                    new Turn(
                        new TurnId(1),
                        new Input(
                            new Seq(1),
                            List.of(
                                new Block.Text(
                                    "Think briefly, then reply with exactly: alpha beta gamma"))),
                        List.of(),
                        null,
                        0))),
            Toolset.none(),
            new InferenceOptions(
                MODEL,
                2048,
                Map.of(
                    "gemini.generationConfig.thinkingConfig.includeThoughts", "true",
                    "gemini.generationConfig.thinkingConfig.thinkingBudget", "512",
                    "gemini.generationConfig.stopSequences", "[\"beta\"]")));

    try (GeminiInferenceProvider provider = provider()) {
      Narration narrated = new Narration();
      String answer = text(provider.infer(request, narrated));

      assertThat(narrated.fragments())
          .as("the typed thinking config survived: thoughts were narrated")
          .anyMatch(fragment -> "thinking".equals(fragment.kind()));
      assertThat(answer)
          .as("the pass-through arrived: the answer stops before the stop sequence")
          .containsIgnoringCase("alpha")
          .doesNotContainIgnoringCase("gamma");
    }
  }
```

If the first assertion fails and the second holds, `extraBody` replaced the SDK's `generationConfig`: record it, and do not change code here -- the spec's contingency (the adapter nests its pass-through into the config it builds) is a follow-up commit for James to approve.

- [ ] **Step 5: `BedrockLiveTest`**

Add `@Tag("live")` on the class; imports `java.util.Map`, `org.junit.jupiter.api.Tag`; then:

```java
  /**
   * Claude's extended thinking on Bedrock, as two properties and no code (§9e). Needs a Claude
   * model id this account can call; skipped without one rather than guessed.
   */
  @Test
  void claude_thinks_on_bedrock_through_two_properties() {
    String claude = System.getenv("NESSY_LIVE_BEDROCK_THINKING_MODEL");
    assumeTrue(claude != null, "NESSY_LIVE_BEDROCK_THINKING_MODEL is not set");
    InferenceRequest request =
        new InferenceRequest(
            new SystemPrompt("You are a terse assistant."),
            InferenceContext.of(
                List.of(
                    new Turn(
                        new TurnId(1),
                        new Input(
                            new Seq(1),
                            List.of(new Block.Text("Think, then say how many continents there are."))),
                        List.of(),
                        null,
                        0))),
            Toolset.none(),
            new InferenceOptions(
                claude,
                2048,
                Map.of("bedrock.thinking.type", "enabled", "bedrock.thinking.budget_tokens", "1024")));

    try (BedrockInferenceProvider provider = provider()) {
      InferenceResult result = provider.infer(request);

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
      assertThat(((InferenceResult.Answer) result).blocks())
          .as("a signed reasoning block comes back")
          .anyMatch(Block.Provider.class::isInstance);
    }
  }
```

- [ ] **Step 6: `PresetCandidatesLiveTest` -- the strict column**

Every chat-wire row is run twice: as today with `openai.tools.strict=false` pinned (ruling 15), and with `openai.tools.strict=true`; `target/preset-measurements.md` gains a `strict` column -- `OK`, or the vendor's message. A preset's default property is written from that column and nothing else (§10); that change is not part of this task.

1. `Candidate` gains a method:

```java
    /** A candidate spoken to over Chat Completions, by its own wire or by its preset's. */
    boolean chatWire() {
      if (wire != null) {
        return "openai-chat".equals(wire);
      }
      return Preset.CATALOGUE.stream()
          .anyMatch(preset -> preset.id().equals(id) && preset.wire() == Wire.OPENAI_CHAT);
    }
```

2. A second results map beside `ROWS`: `private static final Map<String, String> STRICT = new ConcurrentHashMap<>();`

3. `provider(Candidate candidate, String key)` becomes `provider(Candidate candidate, String key, String... extra)`: build the property array as today, then append every `extra` entry (`Stream.concat(Arrays.stream(base), Arrays.stream(extra)).toArray(String[]::new)`, import `java.util.Arrays`) before `withPropertyValues(...)`. `harness(...)` gains the same trailing `String... extra`, passed through to `provider(...)`.

4. The baseline pins strict off for chat-wire candidates -- in `toolCallCheck` and `forcedAnswerCheck`, the `harness(...)` call passes `strictOff(candidate)`:

```java
  /** The baseline column is the non-strict wire, whatever a preset now defaults (ruling 15). */
  private static String[] strictOff(Candidate candidate) {
    return candidate.chatWire()
        ? new String[] {
          "nessy.providers." + candidate.id() + ".properties.openai.tools.strict=false"
        }
        : new String[0];
  }
```

5. In `containerFor`, the returned container's test list gains, for `candidate.chatWire()` only, a third dynamic test `"under openai.tools.strict=true, the tool is still called"` running:

```java
  private void strictCheck(Candidate candidate, String key, String model) {
    try {
      CountingDaysUntilTool tool = new CountingDaysUntilTool();
      DirectHarness<String, String> harness =
          harness(
              candidate,
              key,
              model,
              tool,
              c -> {},
              "nessy.providers." + candidate.id() + ".properties.openai.tools.strict=true");

      Outcome<String> outcome =
          harness.ask(
              AgentId.random(),
              "How many whole days from today until 2030-01-01? Use the days_until tool.");

      assertThat(outcome).isInstanceOf(Outcome.Answered.class);
      assertThat(tool.callCount()).isGreaterThanOrEqualTo(1);
      STRICT.put(candidate.id(), "OK");
    } catch (Throwable t) {
      STRICT.put(candidate.id(), truncate(t.getClass().getName() + ": " + t.getMessage(), 300));
      throw t;
    }
  }
```

(Build the container's list as a `new ArrayList<DynamicTest>(List.of(...the two existing...))` and `add` the third when `candidate.chatWire()`; import `java.util.ArrayList`.)

6. `write_results_file` writes the new column: the header becomes `| id | model | base url | wire | result | strict | detail |` with `|---|---|---|---|---|---|---|`, and each row appends, between `row.status()` and `row.detail()`, `STRICT.getOrDefault(row.id(), candidate.chatWire() ? "not run" : "n/a")`.

- [ ] **Step 7: Compile, gate and commit**

These tests are never run here. Prove only that they compile and that nothing else moved:

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?`
Expected: `exit=0` (the default `nessy.excludedGroups=live,container` keeps every case above out of the build).

```bash
cd /Users/jcarman/IdeaProjects/nessy-props
git add nessy-inference nessy-spring-boot/autoconfigure/src/test
git commit -m "test: live cases for vendor properties, and a strict column for the candidates

Strict tools and a reasoning effort on the chat wire, a misspelled
pass-through as the vendor's own 400, reasoning summaries narrated on the
Responses wire, Anthropic thinking and caching as agent-type properties,
Gemini's extraBody merge measured, Claude thinking on Bedrock through two
properties. Every chat-wire candidate is measured with and without strict.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

After review, the controller hands James the run commands (he exports his keys):

```bash
cd /Users/jcarman/IdeaProjects/nessy-props
./mvnw -q -pl :nessy-inference-openai,:nessy-inference-anthropic,:nessy-inference-gemini,:nessy-inference-bedrock -am test \
  -Dnessy.excludedGroups= -Dtest='*LiveTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test -Dnessy.excludedGroups= -Dtest=PresetCandidatesLiveTest
cat nessy-spring-boot/autoconfigure/target/preset-measurements.md
```

and records in the ledger: which cases passed; the Gemini merge answer; each chat-wire row's `strict` cell. A FAIL row with the vendor's message is a result, not a failure of this task.

---

### Task 11: Docs and the changelog

Spec §14 step 6. Describe what is -- no history, no roads not taken (`docs-describe-what-is`). Suggested implementer: `docs-writer` (Sonnet), reviewed by `task-reviewer` (Sonnet). The prose below is the content to land; the docs-writer may adjust wording to the guide's voice but not the facts, and must check every name, default and message against the code as Tasks 1-9 left it.

**Files:**
- Modify: `docs/guides/providers.md` (the paragraph at lines 107-110; "Anthropic features", lines 140-154; "The report", lines 254-270; "Writing a provider", lines 390-429; a new section)
- Modify: `docs/guides/spring-boot.md` (the Properties table, lines 88-103, and a paragraph after it)
- Modify: `CHANGELOG.md` (`## [Unreleased]`)

**Interfaces:**
- Consumes: every name above; Task 10's recorded results only if James has run them (the Gemini merge answer changes one sentence, marked below).
- Produces: docs only.

- [ ] **Step 1: `providers.md` -- retire the "two providers" paragraph and add "Vendor properties"**

Replace the paragraph at lines 107-110 ("Provider-level features such as thinking and prompt caching are settings on the provider, not requests a harness makes. Two agent types that need the provider configured differently get two providers, registered under two names, on the same factory.") with:

```markdown
Settings a vendor has and the neutral API does not name -- OpenAI's
reasoning effort, Anthropic's thinking budget, Gemini's thinking config --
are [vendor properties](#vendor-properties), set per agent type, so two agent
types that want the same provider configured differently share one provider.
```

Insert a new section after "Naming providers" and before "Building a provider":

````markdown
## Vendor properties

An agent type carries string-named, string-valued properties that the
adapter answering it reads:

```java
config.inference(in -> in
        .provider("openai")
        .model("gpt-6-sol")
        .property("openai.reasoning.effort", "high")
        .property("anthropic.thinking.budget_tokens", "8192"));
```

Each adapter owns a prefix -- `openai.` (both OpenAI adapters, whatever
vendor they report), `anthropic.`, `gemini.`, `bedrock.` -- reads the
entries under it and ignores the rest, so the agent type above runs on
either provider and each reads its own. A property with no prefix at all is
refused when the harness is built. Properties are fixed when the harness is
built, sent with every request, and never written to the event log.

Under its prefix an adapter does one of two things with a name:

- **A known name is parsed** into the SDK's typed field, and a value of the
  wrong type fails the build naming the property and the value
  (`property 'anthropic.thinking.budget_tokens' must be an integer, was
  'lots'`). The adapter checks the type, never the vocabulary: an effort
  level OpenAI adds tomorrow works today.
- **Any other name passes through** into the request body. Every dot after
  the prefix nests an object (`openai.metadata.team` is
  `{"metadata": {"team": ...}}`), and the value is read as a JSON literal
  where it parses as one: `12000` is a number, `true` a boolean, `high` a
  string, `["\n\n"]` an array. A value that must be a string but looks like
  a number is quoted: `"12345"`. Names are spelled exactly as the vendor's
  REST reference spells the field, because they are sent verbatim -- a typo
  is the vendor's own 400.

A property that names something a typed setting already decides -- the
model, the ceiling, the tools and their choice, the answer's shape, the
conversation, or a field the adapter fixes on purpose -- fails the build
naming both: `agent type 'chat': property 'openai.max_completion_tokens'
names what InferenceConfig.maxTokens already decides; remove the property`.
So does a known name the wire cannot carry, and a pass-through under an
object the adapter builds from a known name.

A provider carries properties of its own, set on its config
(`OpenAiChatInferenceProvider.of(c -> c.apiKey(key).property("openai.tools.strict", "true"))`)
or under Boot with `nessy.providers.<id>.properties.*`. An agent type's
property overrides the provider's of the same name. A provider's property
under another adapter's prefix fails at startup.

### OpenAI

| name | type | chat wire | Responses wire |
|---|---|---|---|
| `openai.reasoning.effort` | string | `reasoning_effort` | `reasoning.effort` |
| `openai.reasoning.summary` | string | refused: the wire has no summary | `reasoning.summary`, narrated as thinking |
| `openai.tools.strict` | boolean | `true` sends every function tool strict over a rewritten schema | always strict; `false` is refused |
| `openai.service_tier` | string | `service_tier` | `service_tier` |

The `reasoning` object is sent only when one of the two reasoning names is
set: it is a 400 on a model that does not reason. Refused on the chat wire:
`model`, `messages`, `max_completion_tokens`, `max_tokens`, `tools`,
`tool_choice`, `response_format`, `stream`, `stream_options`, and
`reasoning_effort` beside `openai.reasoning.effort`. Refused on the
Responses wire: `model`, `input`, `instructions`, `max_output_tokens`,
`tools`, `tool_choice`, `text`, `stream`, and -- because the adapter is
stateless and the event log is the only conversation -- `store`, `include`,
`previous_response_id`, `conversation` and `background`.

Under `openai.tools.strict=true` a tool whose schema strict mode cannot
express (a sealed type's `oneOf`, a map) goes out as generated with
`strict: false`, and the adapter logs a warning naming the tool and the
keyword; the other tools stay strict.

### Anthropic

| name | type | lands in |
|---|---|---|
| `anthropic.thinking.type` | string | `enabled` with a budget; `adaptive`; `disabled` sends nothing; any other value is sent as written |
| `anthropic.thinking.budget_tokens` | integer | the thinking budget; alone, it turns thinking on. Must be below the agent type's `maxTokens` |
| `anthropic.cache_control.ttl` | string | the cache markers on the system prompt and the tools: `5m`, `1h` |
| `anthropic.service_tier` | string | `service_tier` |

Refused: `model`, `max_tokens`, `messages`, `system`, `tools`,
`tool_choice`, `output_config`, `stream`, and a raw `thinking` or
`cache_control` beside the known names that build them.

### Gemini

| name | type | lands in |
|---|---|---|
| `gemini.generationConfig.thinkingConfig.thinkingBudget` | integer | the SDK's `ThinkingConfig` |
| `gemini.generationConfig.thinkingConfig.includeThoughts` | boolean | the same; thought summaries are then narrated as thinking |
| `gemini.generationConfig.thinkingConfig.thinkingLevel` | string | the same |

Every other `gemini.` name goes into the request body through the SDK's
`extraBody`. Refused: `contents`, `systemInstruction`, `tools`,
`toolConfig`, `generationConfig.maxOutputTokens`,
`generationConfig.responseMimeType`, `generationConfig.responseJsonSchema`,
`generationConfig.responseSchema`.

(**If Task 10 recorded that `extraBody` replaces the SDK's own
`generationConfig`**, add: "A pass-through under `generationConfig` is not
yet supported alongside the three thinking names." Otherwise add nothing.)

### Bedrock

`bedrock.inferenceConfig.temperature` and `bedrock.inferenceConfig.topP`
(numbers) and `bedrock.inferenceConfig.stopSequences` (a JSON array of
strings) land in Converse's typed inference config. Every other `bedrock.`
name goes into `additionalModelRequestFields`, the model's own document, so
Claude's extended thinking on Bedrock is two properties:

```java
in.property("bedrock.thinking.type", "enabled")
  .property("bedrock.thinking.budget_tokens", "4096")
```

Refused: `modelId`, `messages`, `system`, `toolConfig`,
`inferenceConfig.maxTokens`. Converse's other top-level fields
(`guardrailConfig`, `performanceConfig`, `serviceTier`, `requestMetadata`)
are typed on the AWS request and not reachable as properties.

### Embedders

`EmbedderConfig.property(name, value)` and the four embedder configs'
`property`/`properties` (prefixes `openai.`, `gemini.`, `bedrock.`,
`voyage.`) are accepted and carried; the embedding adapters do not read
them yet. A property under another prefix fails at build.
````

- [ ] **Step 2: `providers.md` -- "Anthropic features", "The report", "Writing a provider"**

"Anthropic features": after the existing paragraph, add:

```markdown
The same three settings are vendor properties:
`anthropic.thinking.type=enabled` and `anthropic.thinking.budget_tokens`
for `thinking(true)` and `thinkingBudget(...)`, `anthropic.cache_control.ttl`
(`5m`, `1h`) for `promptCaching(...)`. A setter is a provider-level default,
at the same level as a provider property: setting both for one field on one
provider fails at build, naming both. An agent type's property overrides
either, so one provider built with `thinking(true)` serves an agent type
that asks for `anthropic.thinking.budget_tokens=16000` and one that asks for
`anthropic.thinking.type=disabled`.
```

"The report": after the providers-line example, add "A provider with vendor properties names them, never their values: `openai (openai-chat, the vendor's own endpoint, vendor openai, properties [openai.tools.strict])`." After the harness-line example, add "and ends `, properties [openai.reasoning.effort]` when the agent type carries any -- names only."

"Writing a provider": add a bullet before the `InferenceNarrator` bullet:

```markdown
- Own a prefix and read vendor properties through `VendorProperties`
  (`nessy-vendor-properties`): merge the provider's map under
  `request.options().properties()`, take the entries `under` your prefix,
  refuse the names a typed setting decides with `refuseClashes`, parse your
  known names with `requireInteger` / `requireBoolean` / `requireString`,
  and send the rest as `nest(...)` builds them. Override
  `InferenceProvider.validate(InferenceOptions)` to run the same reading, so
  a mistake fails the harness build rather than its first turn.
```

- [ ] **Step 3: `spring-boot.md`**

In the Properties table, after the `nessy.providers.<id>.api-key, ...` row, add:

```markdown
| `nessy.providers.<id>.properties.<name>` | the preset's defaults (`openai.tools.strict=true` on `openai`) | the provider's [vendor properties](providers.md#vendor-properties); overlaid on the preset's by name, and overridden by an agent type's own |
```

After the paragraph on `nessy.provider` and `nessy.model`, add:

````markdown
Vendor properties bind as a map of strings, so a dotted name stays one key:

```yaml
nessy:
  providers:
    openai:
      properties:
        openai.reasoning.effort: high
        openai.tools.strict: "false"
    anthropic:
      properties:
        anthropic.thinking.budget_tokens: "8192"
```

They are a configuration-file setting. An environment variable cannot name
one: relaxed binding turns every `_` into `.` and lower-cases the rest, which
loses the underscore in `budget_tokens` and the case in `thinkingBudget`. To
take a value from the environment, name the property in the file and let
the environment supply the value: `openai.user: ${TENANT_ID}`.
````

- [ ] **Step 4: The changelog**

Under `## [Unreleased]`, add to `### Breaking changes`:

```markdown
- **`InferenceOptions` and `EmbeddingOptions` gain a `properties`
  component.** Their existing constructors and `of(...)` still work;
  a record pattern over either (`InferenceOptions(var model, var max)`)
  needs the third component.
```

and add (above `### Breaking changes` if there is no `### Added` yet, or into it):

```markdown
### Added

- **Vendor properties.** `InferenceConfig.property(name, value)` sets a
  vendor-prefixed setting on an agent type (`openai.reasoning.effort`,
  `anthropic.thinking.budget_tokens`, `gemini.generationConfig.thinkingConfig.thinkingBudget`,
  `bedrock.thinking.type`); each adapter parses the names it knows, passes
  the rest through into the request body as JSON literals, and ignores
  other prefixes. A property that names what a typed setting decides fails
  when the harness is built. Every provider config and `EmbedderConfig`
  take properties too.
- **`InferenceProvider.validate(InferenceOptions)`** and
  **`EmbeddingProvider.validate(EmbeddingOptions)`**, default no-ops, which
  the factories call when a harness or embedder is built.
- **`nessy-vendor-properties`**, the module adapters read properties
  through. The BOM now also lists `nessy-inference-spi` and
  `nessy-embedding-spi`.
- **`openai.tools.strict`** on the chat adapter: function tools go out in
  strict mode over a rewritten schema.
- **Spring Boot: `nessy.providers.<id>.properties.*`.**

### Changed

- **The `openai` preset sends strict function tools**
  (`openai.tools.strict=true` by default). Set
  `nessy.providers.openai.properties.openai.tools.strict: "false"` to turn
  it off, for instance when `openai.base-url` points at a server that
  rejects strict mode.
- Anthropic's `thinking`, `thinkingBudget` and `promptCaching` setters and
  their `anthropic.*` properties are one setting: setting both for one
  field on one provider fails at build.
```

- [ ] **Step 5: Check, gate and commit**

Run: `git grep -n "two providers, registered under two names" -- docs README.md ':!docs/superpowers'` -- expected: nothing. Then `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:check license:check && ./mvnw -q clean verify; echo exit=$?` -- expected `exit=0`.

```bash
cd /Users/jcarman/IdeaProjects/nessy-props
git add docs/guides/providers.md docs/guides/spring-boot.md CHANGELOG.md
git commit -m "docs: vendor properties

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

## Not in this plan

- **A preset default from the strict column (spec §14 step 5).** Only the `openai` row carries `openai.tools.strict=true`. After Task 10's run, a row whose `strict` cell is `OK` gets its default in a commit of its own, with James's go-ahead.
- **The embedding adapters reading their properties (§9f)** and **`nessy.embedders.<id>.properties.*` (§6d)**: the named-embedders item.
- **Gemini's contingency** if `extraBody` replaces `generationConfig` (§16(5)): a follow-up commit after Task 10's measurement.
- **Retiring the Anthropic setters (§16(4))**, **Bedrock's Converse-level fields as known names (§16(6))**, and **the Open questions above**: James's.

## Self-review

- **Spec coverage.** §4a/§4b → Task 2. §5a/§5b → Task 1 (records), Task 2 (callers). §5c → Task 2 (`no_event_carries_a_property`). §6a → Tasks 3-8. §6b → Task 9 (dotted, underscored, camelCase, YAML rows). §6c → Task 9 (provider line, `toString`s), Task 2 (harness line). §6d → Not in this plan. §7a → Tasks 3-7 (agent type over provider), Task 5 (setter tier). §7b → Tasks 3-7 (clash tables, raw spellings). §7c → Task 1 (SPI, observed delegation), Task 2 (factories re-throw). §8a → Tasks 3-7 (other prefixes ignored, `DEBUG`), Task 1 (no-prefix refusal). §8b/§8c → Task 1 (helper), Tasks 3-7. §8d → Tasks 3-7 (the slot per SDK), Task 10 (Gemini merge). §8e → Task 1. §9a-§9e → Tasks 3-7. §9f → Task 8 (configs), Task 1/2 (door, carrier, hook). §10 → Task 3. §11 → Task 9. §13a → Tasks 1, 3-7. §13b → Task 9. §13c → Task 2. §13d → Task 10. §14 → task order. §16(3)'s BOM recommendation → Task 1.
- **Deviations, stated.** The plan rulings above, every one: the strict rewrite stays `OpenAiResponsesSchemas`; helper signatures take the prefix; insertion-ordered option maps with names-only `toString`; valueless clash messages; owned roots; Anthropic's public surface frozen and setters converted to entries; `disabled` sends nothing and unknown thinking types go raw; the headroom check at `validate`; the config-reaches-provider tests' locality; Boot failure rows with keys; embedders carry and ignore; `IllegalArgumentException` only is re-thrown; the strict baseline pinned off; SLF4J declared.
- **Type consistency.** `VendorProperties.under(Map, String)`, `merge(Map, Map)`, `literal(String, JsonMapper)`, `nest(String, Map, JsonMapper)`, `refuseClashes(String, Map, Map)`, `requireInteger/requireBoolean/requireString(String, String)` (Tasks 1, 3-8). `InferenceOptions(String, int, Map)`, `EmbeddingOptions(String, OptionalInt, Map)` (Tasks 1, 2, 3-10). `OpenAiProperties.Read(effort, summary, strict, serviceTier, passThrough)`, `chat(Map, JsonMapper)`, `responses(Map, JsonMapper)`, `read(Map, JsonMapper)` (Tasks 3, 4). `AnthropicProperties.Read(thinking, budget, cacheTtl, serviceTier, passThrough)` with `enabled()` (Task 5). `GeminiProperties.Read(thinking, passThrough)` (Task 6). `BedrockProperties.Read(temperature, topP, stopSequences, passThrough)` with `tunesInference()` (Task 7). `ProviderSettings` six components, `ResolvedProvider` six plus a five-argument constructor, `Preset` seven plus a six-argument constructor (Task 9, consumed by Task 10).
