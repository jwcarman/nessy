# OpenAI Responses Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A second OpenAI adapter, `OpenAiResponsesInferenceProvider`, that speaks the Responses API statelessly (whole context every call, `store: false`), stores every encrypted reasoning item as a `Block.Provider` block and replays the ones the API needs, sends function tools in strict mode through an adapter-side schema rewrite, and is selectable under Boot as the `openai-responses` wire -- beside the renamed `openai-chat` wire and `OpenAiChatInferenceProvider`.

**Architecture:** One module (`nessy-inference-openai`), two adapters. The chat adapter is renamed and its client construction, failure classification and text rendering move into neutral package-private helpers (`OpenAiClients`, `OpenAiFailures`, `OpenAiRendering`) that both adapters call. The Responses adapter mirrors the chat one: a config builds it, `infer` streams `client.responses().createStreaming(OpenAiResponsesRequests.toParams(...))`, every event goes to the SDK's `ResponseAccumulator` (which keeps only the terminal event's whole `Response`) and three delta kinds are narrated; the result is read from that `Response`. The strict rewrite is a private projection (`OpenAiResponsesSchemas`); the generator, the `JsonSchema` a tool carries and every other wire are untouched. Boot gains a `Wire.OPENAI_RESPONSES` arm in `WireProviders`.

**Tech Stack:** Java 25, Maven reactor, openai-java 4.69.2 (already the version in use), Jackson 3 (`tools.jackson`) for Nessy's own JSON and the SDK's Jackson 2 only at the SDK boundary, SLF4J (logback in tests), JUnit 5 + AssertJ, Spring Boot 4 `ApplicationContextRunner`.

**Spec:** `docs/superpowers/specs/2026-09-29-openai-responses-design.md` -- binding, fully approved. Read it before any task; section numbers below (§n) refer to it. Every public name this plan introduces is in the spec's §11 table; nothing here is a new concept needing a yes. The package-private helpers (`OpenAiClients`, `OpenAiFailures`, `OpenAiRendering`, `OpenAiChatRequests`, `OpenAiResponsesRequests`, `OpenAiResponsesSchemas`) are named under §4b's rule and are mechanical internals.

**Sequencing (spec §10).** Task 1 is step 1 (the rename, its own commit, green alone). Tasks 2-5 are step 2 (the adapter and its replay tests). Tasks 6-7 are step 3 (Boot wiring, then live tests and measurement). Task 8 is step 5 (docs). **Step 4 -- the `openai` preset's default wire moving to `openai-responses` (§7c) -- is NOT in this plan.** It waits for Task 7's live pass and James's go-ahead, and it is one catalogue row, its test and a changelog line in a later commit. No task below changes `Preset.CATALOGUE`'s `openai` row away from `Wire.OPENAI_CHAT`.

**Measured while planning (2026-09-29, probes against `openai-java-core-4.69.2` and the `0.3.0-SNAPSHOT` engine jar):**
- A `ResponseStreamEvent` parsed from JSON by `com.openai.core.ObjectMappers.jsonMapper()` round-trips cleanly: `response.completed` with a full `Response`, `response.output_text.delta` without `logprobs`, an unknown `type` (lands in the `_json` arm), an `error` event, and a `response.failed` whose `error` carries `code`/`message`. The accumulator ignores the unknown event and keeps the terminal `Response`. The replay tests below build every event this way.
- `ResponseUsage` with no `input_tokens_details`: `_inputTokensDetails().asKnown()` is `Optional.empty`, and `inputTokensDetails()` throws `OpenAIInvalidDataException` -- §5i confirmed.
- `"model":"gpt-6-sol"` deserialises into `ResponsesModel`'s **chat** arm (`isChat()`), not its string arm, so the model name has to be read across all three arms.
- `ResponseInputItem.FunctionCallOutput.callId()` is `Optional<String>` in this SDK.
- The SDK's SSE handler throws `SseException` (an `OpenAIException`) for any event whose JSON has a top-level `error` key; the Responses `error` event (`{"type":"error","code":...,"message":...}`) has none and is delivered as an event.
- **The generator already widens an `Optional` component:** `Optional<String>` generates `{"type":["string","null"]}` (and is left out of `required`), and `Optional<SomeRecord>` generates `{"oneOf":[{"type":"null"},{"$ref":"#/$defs/SomeRecord"}]}`. So the rewrite's null-widening must be idempotent, and -- because `oneOf` is outside the strict subset (§5d) -- **every tool with an `Optional<Record>` component or a sealed vocabulary falls back to non-strict** under the spec as written. The fixtures below are copied from the real generator output. Raised with the caller as a spec gap; this plan implements the spec.

## Global Constraints

- Full verification: `./mvnw -q clean verify` -- must pass with no API key and no model-provider network access. Run it ONCE per task, as the final gate before the task's last commit, never per step. Judge Maven by its exit code (`echo exit=$?`), never by grepping its output.
- While iterating use warm scoped builds, artifactId form with the colon: `./mvnw -q -pl :nessy-inference-openai -am test`, `./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test`. Never the path form (`-pl nessy-inference/openai`).
- If a scoped run hangs after a signature change, suspect a stale jar in `~/.m2`: `./mvnw -q -pl :nessy-inference-openai,:nessy-spring-boot-autoconfigure -am install -DskipTests`, then retry.
- Maven runs in the FOREGROUND. Never two Maven processes at once in this worktree. Never poll with `pgrep -f` (it matches its own command line and never ends). Before a build, check once, in the same command, that no example app is running from this checkout: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q ...` -- the bracket keeps the pattern from matching itself; if it prints anything about nessy, stop and ask.
- `spotless:check` runs before compilation, so an unformatted file fails the build with no compiler output. Run `./mvnw -q spotless:apply license:format` before reading any build error and before every commit. `./mvnw -q spotless:check license:check` is what CI runs.
- Every new file carries the Apache license header (copy it from any file in the same module; `license:format` adds it). If `license:format` touches far more files than the task did, commit the headers alone first.
- Formatting is google-java-format (spotless enforces it). The code below is written close to it; `spotless:apply` settles the rest.
- No warning suppression of any kind (`@SuppressWarnings`, etc.) -- write code that raises no warning (no unchecked casts: walk JSON as `Map<?, ?>` / `List<?>` with `instanceof` patterns). No star imports, including static imports.
- Tests: prose-style snake_case method names in the module's voice (`plain_prose_is_an_answer`); `junit-platform.properties` turns underscores into the display sentence. **No mocking library** -- the SDK is faked with JDK dynamic proxies (`Proxy.newProxyInstance`) over its service interfaces, as `OpenAiChatInferenceProviderTest` does. AssertJ.
- Sonar S5778: an `assertThatThrownBy` lambda contains exactly ONE call that can throw; build configs, requests and fixtures outside it.
- Assert a collection is non-empty before any `allMatch` / `noneMatch` / `allSatisfy` on it.
- Javadoc: never put a second `/** */` above a declaration that already has one (the first is silently dropped). To add `@param`, edit the existing comment.
- XML comments may not contain `--` (the house em-dash style is a parse error in `pom.xml`).
- macOS: BSD `sed` has no `\b` -- use `perl -pi -e` for word-boundary replacements. zsh does not word-split unquoted variables.
- Docs describe what is -- never history or roads not taken. Dated records under `docs/superpowers/` and the `0.2.0` changelog entry are history and stay as written.
- Every commit message ends with:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC
  ```
- Branch: `responses-api`, worktree `/Users/jcarman/IdeaProjects/nessy-responses`. Commit to it; never push. Never touch `/Users/jcarman/IdeaProjects/nessy`.
- Model policy (repo `CLAUDE.md`): implementers default to Sonnet; review Tasks 3 and 4 with Opus (they carry the transcript invariant: reasoning items stored in arrival order and replayed only by the adapter's rule); scoped re-reviews of small fix diffs on Haiku.

## Review Focus

The inputs the spec implies but no spec-listed test exercises, most likely to bite first. Each has its test in the owning task.

1. **An optional enum component** (`Optional<Colour>` generates `{"type":["string","null"],"enum":["RED","GREEN"]}`): widening the type is not enough -- strict mode checks `enum` too, so `null` must join the enum's values or the model can never say "nothing". → Task 2, `an_optional_enum_admits_null_among_its_values`.
2. **A map-valued component** (`Map<String, String>` generates `additionalProperties` as a schema, not `false`): strict mode cannot express it; the tool must fall back to non-strict with the WARN, not go out strict and earn a 400. → Task 2, `an_open_map_s_additional_properties_is_refused`.
3. **A stored `openai` block this build did not write** (hand-edited, or from a server whose reasoning item carried no `encrypted_content`): replay must drop it, never send a half reasoning item. → Task 3, `an_openai_block_without_encrypted_content_is_not_replayed`.
4. **A compatible server that omits `input_tokens` from `usage`**: reading the counts through the plain accessors would throw inside `infer` and turn a good answer into a `Permanent` fault. The counts are read through `asKnown()` too. → Task 4, `a_server_that_omits_the_input_count_still_answers`.
5. **A reasoning item with no `encrypted_content`** (a non-OpenAI server, or a summary-only item): nothing to replay, so nothing is stored, and the answer beside it is unaffected. → Task 4, `a_reasoning_item_with_nothing_encrypted_is_not_kept`.

---

### Task 1: The rename -- `openai-chat`, the chat classes, and the shared helpers

Mechanical; no behaviour change (spec §4, §10 step 1). One commit, green on its own. Suggested implementer: Sonnet.

**Files:**
- Rename (`git mv`) in `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/`:
  - `OpenAiInferenceProvider.java` → `OpenAiChatInferenceProvider.java`
  - `OpenAiProviderConfig.java` → `OpenAiChatProviderConfig.java`
  - `OpenAiRequests.java` → `OpenAiChatRequests.java` (and drop `public` from the class and from `toParams`)
- Create (same package): `OpenAiClients.java`, `OpenAiFailures.java`, `OpenAiRendering.java`
- Rename (`git mv`) in `nessy-inference/openai/src/test/java/org/jwcarman/nessy/inference/openai/`:
  - `OpenAiInferenceProviderTest.java` → `OpenAiChatInferenceProviderTest.java`
  - `OpenAiLiveTest.java` → `OpenAiChatLiveTest.java`
  - `OpenAiProviderConfigTest.java` → `OpenAiChatProviderConfigTest.java`
  - `OpenAiRequestsTest.java` → `OpenAiChatRequestsTest.java`
- Modify: `OpenAiCloseOwnershipTest.java`, `OpenAiVendorTest.java` (names only)
- Modify: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/direct/DirectHarnessLiveTest.java` (import, line 89)
- Modify in `nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/inference/`: `Wire.java`, `Preset.java`, `WireProviders.java`, `ProviderSettings.java` (javadoc)
- Modify in `nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/inference/`: `ProviderCatalogueTest.java`, `ResolvedProviderTest.java`, `ProviderSettingsTest.java`, `InferenceProvidersAutoConfigurationTest.java`, `InferenceReportTest.java`, `PresetCandidatesLiveTest.java`
- Modify: `docs/guides/providers.md` (lines 75, 119, 177-186, 227, 233-236, 260, 344), `CHANGELOG.md` (`[Unreleased]`)

**Interfaces:**
- Produces (public): `OpenAiChatInferenceProvider` (every member of today's `OpenAiInferenceProvider`, same signatures, types renamed); `OpenAiChatProviderConfig` (same setters).
- Produces (package-private, used by Tasks 3-5):
  - `static OpenAIClient OpenAiClients.build(boolean useEnv, String apiKey, String baseUrl, String organization, Duration timeout)` -- any of the four strings/duration may be null
  - `static Duration OpenAiClients.requirePositive(Duration timeout)`
  - `static Failure OpenAiFailures.classify(OpenAIException e)`
  - `static String OpenAiRendering.system(InferenceRequest request)`, `static String OpenAiRendering.summary(Summary summary)`, `static String OpenAiRendering.text(List<? extends Block> blocks)`, `static String OpenAiRendering.outcome(ToolOutcome outcome)`, constants `OpenAiRendering.FAILED_TURN`, `OpenAiRendering.REFUSED_TURN`
  - `static ChatCompletionCreateParams OpenAiChatRequests.toParams(InferenceRequest, JsonMapper)` (now package-private)
- Produces (Boot, package-private): `Wire.OPENAI_CHAT("openai")`, `Wire.OPENAI_RESPONSES("openai")`. `WireProviders` fails loudly (`IllegalStateException`) if `OPENAI_RESPONSES` is selected, until Task 6.

- [ ] **Step 1: Move the files**

```bash
cd /Users/jcarman/IdeaProjects/nessy-responses
M=nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai
T=nessy-inference/openai/src/test/java/org/jwcarman/nessy/inference/openai
git mv "$M/OpenAiInferenceProvider.java" "$M/OpenAiChatInferenceProvider.java"
git mv "$M/OpenAiProviderConfig.java" "$M/OpenAiChatProviderConfig.java"
git mv "$M/OpenAiRequests.java" "$M/OpenAiChatRequests.java"
git mv "$T/OpenAiInferenceProviderTest.java" "$T/OpenAiChatInferenceProviderTest.java"
git mv "$T/OpenAiLiveTest.java" "$T/OpenAiChatLiveTest.java"
git mv "$T/OpenAiProviderConfigTest.java" "$T/OpenAiChatProviderConfigTest.java"
git mv "$T/OpenAiRequestsTest.java" "$T/OpenAiChatRequestsTest.java"
```

- [ ] **Step 2: Rename the identifiers**

Word-boundary replacement with perl (BSD `sed` has no `\b`). Scope: the module's Java files, `WireProviders.java`, `DirectHarnessLiveTest.java`, and the guide -- never `docs/superpowers/` or `CHANGELOG.md`.

```bash
cd /Users/jcarman/IdeaProjects/nessy-responses
FILES=$(git ls-files 'nessy-inference/openai/src/*.java' \
  'nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/inference/WireProviders.java' \
  'nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/direct/DirectHarnessLiveTest.java' \
  'docs/guides/providers.md')
perl -pi -e 's/\bOpenAiInferenceProvider/OpenAiChatInferenceProvider/g; s/\bOpenAiProviderConfig/OpenAiChatProviderConfig/g; s/\bOpenAiRequests/OpenAiChatRequests/g; s/\bOpenAiLiveTest\b/OpenAiChatLiveTest/g' ${=FILES}
git grep -nw -e OpenAiInferenceProvider -e OpenAiProviderConfig -e OpenAiRequests -- ':!docs/superpowers' ':!CHANGELOG.md'
```

Expected: the final `git grep` prints nothing. (`${=FILES}` is zsh's explicit word-split; in bash use `$FILES`.)

- [ ] **Step 3: Demote `OpenAiChatRequests` and fix its two orphaned javadocs**

In `OpenAiChatRequests.java`: `public final class OpenAiChatRequests` → `final class OpenAiChatRequests`; `public static ChatCompletionCreateParams toParams(` → `static ChatCompletionCreateParams toParams(`. Its class javadoc's first line becomes `Turn-speak into Chat Completions shape.`

The file today has two javadoc blocks stacked above one declaration each (the "One turn, as this provider wants to be asked" block sits above `summary(...)`'s own javadoc, and the "One tool, as this wire describes one" block sits above `chooseTool(...)`'s), so the first of each pair binds to nothing. Move "One turn, ..." to sit directly above `toMessages(Turn turn)` and "One tool, ..." directly above `toFunctionTool(ToolOffer offer, JsonMapper mapper)`.

- [ ] **Step 4: Create `OpenAiRendering` and point `OpenAiChatRequests` at it**

The text both OpenAI wires send is the same; it moves out of the chat projection under a neutral name (§4b's naming rule).

```java
package org.jwcarman.nessy.inference.openai;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.inference.InferenceRequest;

/**
 * The words both OpenAI wires send for the parts of a conversation that are text: the system
 * prompt with its ambient sections, a summary, a tool outcome, and the lines that stand where a
 * turn produced nothing. Each wire decides where these go; this class decides only what they say,
 * so the two projections cannot drift apart.
 */
final class OpenAiRendering {

  /** Stands where a failed turn's answer would be. "Did not complete", because the call may never have been made. */
  static final String FAILED_TURN = "The previous attempt to answer did not complete.";

  /**
   * Stands where a withdrawn question stood. Saying nothing would leave two user turns adjacent;
   * saying what it was would put back the very content this exists to remove.
   */
  static final String REFUSED_TURN =
      "A previous message was withdrawn from this conversation and is no longer available.";

  private OpenAiRendering() {}

  // Move here, verbatim with its javadoc, OpenAiChatRequests.system(InferenceRequest) -- made
  // package-private (`static String system(InferenceRequest request)`).

  /** A summary's text, tagged with the turn range it stands for. */
  static String summary(Summary summary) {
    return "<summary from=\"%d\" through=\"%d\">\n%s\n</summary>"
        .formatted(summary.from().value(), summary.through().value(), text(summary.content()));
  }

  /**
   * One tool outcome as text. All three flatten to a string on both wires, because neither has a
   * field for "denied" or an error flag on a result.
   */
  static String outcome(ToolOutcome outcome) {
    return switch (outcome) {
      case ToolOutcome.Succeeded(CallId _, var blocks) -> text(blocks);
      case ToolOutcome.Failed(CallId _, String message) -> "Error: " + message;
      case ToolOutcome.Denied(CallId _, String reason) ->
          "This call was not run because it was not permitted: " + reason;
    };
  }

  // Move here, verbatim with their javadocs, OpenAiChatRequests.text(List<? extends Block>)
  // (package-private, as today) and its private helper readable(Block).
}
```

The two `// Move here` comments are instructions for this step, not code to keep: move those three methods out of `OpenAiChatRequests` (bodies unchanged; `system` loses `private`; `text` keeps its package-private `static`), then delete the comments. `Ambient`, `Optional` and `Collectors` are the imports those moved methods need.

In `OpenAiChatRequests`: `system(request)` in `toParams` becomes `OpenAiRendering.system(request)`; the body of `summary(Summary)` becomes `return user(OpenAiRendering.summary(summary));`; `answering(ToolOutcome)`'s `switch` is replaced by `String content = OpenAiRendering.outcome(outcome);`; the two `system("...")` literals in `toMessages` become `system(OpenAiRendering.FAILED_TURN)` and `system(OpenAiRendering.REFUSED_TURN)` (keep the explanatory comments beside them); every remaining `text(...)` call becomes `OpenAiRendering.text(...)`. Remove imports this leaves unused.

- [ ] **Step 5: Create `OpenAiFailures` and point the chat adapter at it**

```java
package org.jwcarman.nessy.inference.openai;

import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.RateLimitException;
import org.jwcarman.nessy.inference.Failure;

/** What a failed OpenAI call means, for both adapters: the one thing this module knows and nothing above it does. */
final class OpenAiFailures {

  private OpenAiFailures() {}

  // Move here, verbatim with its javadoc, OpenAiChatInferenceProvider.classify(OpenAIException),
  // made package-private: `static Failure classify(OpenAIException e)`.
}
```

In `OpenAiChatInferenceProvider`: delete `classify` and the five exception imports it alone used; the catch becomes `return new InferenceResult.Fault(OpenAiFailures.classify(e));`.

- [ ] **Step 6: Create `OpenAiClients` and slim `OpenAiChatProviderConfig.build()`**

```java
package org.jwcarman.nessy.inference.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.Timeout;
import java.time.Duration;
import java.util.Objects;

/**
 * Builds the SDK client both OpenAI configs hand their adapter: the environment layering, the
 * explicit-override precedence, the missing-credential message and the request timeout, once.
 */
final class OpenAiClients {

  static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";

  private OpenAiClients() {}

  /**
   * A client from settings. {@code useEnv} reads the SDK's own environment table first and lays
   * any explicit value over it; otherwise {@code apiKey} is required. Any argument but {@code
   * useEnv} may be null.
   */
  static OpenAIClient build(
      boolean useEnv, String apiKey, String baseUrl, String organization, Duration timeout) {
    if (useEnv) {
      return buildFromEnv(apiKey, baseUrl, organization, timeout);
    }
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "an API key is required: call apiKey(...) or fromEnv(), or provide a preconfigured"
              + " client via client(...)");
    }
    var clientBuilder = OpenAIOkHttpClient.builder().apiKey(apiKey);
    if (baseUrl != null) {
      clientBuilder.baseUrl(baseUrl);
    }
    if (organization != null) {
      clientBuilder.organization(organization);
    }
    if (timeout != null) {
      clientBuilder.timeout(Timeout.builder().request(timeout).build());
    }
    return clientBuilder.build();
  }

  // Move here, verbatim with its javadoc, OpenAiChatProviderConfig.buildFromEnv(), turned static
  // and taking (String apiKey, String baseUrl, String organization, Duration timeout) in place of
  // the fields it read; and missingEnvCredentials(). Replace the config's {@value
  // #API_KEY_ENV_VAR} references with this class's constant.

  static Duration requirePositive(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive, was " + timeout);
    }
    return timeout;
  }
}
```

`OpenAiChatProviderConfig` keeps every setter and its javadoc (the `fromEnv()` javadoc's `{@value #API_KEY_ENV_VAR}` becomes `{@code OPENAI_API_KEY}`); its `API_KEY_ENV_VAR`, `buildFromEnv`, `missingEnvCredentials` and `requirePositive` go; `timeout(...)` calls `OpenAiClients.requirePositive(timeout)`; and `build()` becomes:

```java
  OpenAiChatInferenceProvider build() {
    if (client != null) {
      return new OpenAiChatInferenceProvider(client, vendor, false, mapper);
    }
    return new OpenAiChatInferenceProvider(
        OpenAiClients.build(useEnv, apiKey, baseUrl, organization, timeout), vendor, true, mapper);
  }
```

Its javadoc's stale `{@link OpenAiChatInferenceProvider#create(...)}` becomes `{@link OpenAiChatInferenceProvider#of(Customizer)}`.

- [ ] **Step 7: Update the chat adapter's javadoc**

In `OpenAiChatInferenceProvider`'s class javadoc: `<p>Speaks the {@code openai} wire, ...` → `<p>Speaks Chat Completions -- the {@code openai-chat} wire under Boot -- which xAI and any OpenAI-compatible endpoint (such as LM Studio) also answer to.` The "Images are not sent" paragraph's `{@code OpenAiRequests}` became `{@code OpenAiChatRequests}` in Step 2; leave it.

- [ ] **Step 8: Run the module's tests**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-inference-openai -am test; echo exit=$?`
Expected: `exit=0`. Every existing test passes unchanged in behaviour; this proves the extraction.

- [ ] **Step 9: Rename the Boot wire**

`Wire.java`:

```java
/**
 * The API shape a provider speaks, named for the vendor that defined it: {@code openai-chat} is
 * OpenAI's Chat Completions shape, which other vendors (xAI, LM Studio, and any OpenAI-compatible
 * endpoint) also serve, and {@code openai-responses} is OpenAI's Responses shape. Bound from {@code
 * nessy.providers.<id>.wire}'s property values ({@code openai-chat}, {@code openai-responses},
 * {@code anthropic}, {@code gemini}) by Boot's relaxed binding, so a typo is a binding error naming
 * the allowed values rather than a provider that silently fails to exist.
 *
 * <p>Package-private and not in the SPI: nothing outside the starter depends on it.
 */
enum Wire {
  OPENAI_CHAT("openai"),
  OPENAI_RESPONSES("openai"),
  ANTHROPIC("anthropic"),
  GEMINI("gcp.gemini");
```

(the rest of the file unchanged). `ProviderSettings.java` javadoc: `only the {@code openai} wire (shared by more than one vendor)` → `only the two OpenAI wires (shared by more than one vendor)`.

Then replace the constant everywhere in Boot main and test:

```bash
cd /Users/jcarman/IdeaProjects/nessy-responses
perl -pi -e 's/\bWire\.OPENAI\b(?!_)/Wire.OPENAI_CHAT/g' $(git ls-files 'nessy-spring-boot/autoconfigure/src/*.java')
```

Expected: `Preset.java`'s eight chat-shaped rows (`openai`, `xai`, `openrouter`, `nvidia`, `groq`, `mistral`, `lmstudio`, `ollama`) and the test files now say `Wire.OPENAI_CHAT`.

- [ ] **Step 10: Make `WireProviders` fail loudly for the unbuilt wire**

```java
  private static final String OPENAI_CHAT_CLASS =
      "org.jwcarman.nessy.inference.openai.OpenAiChatInferenceProvider";
```
(replacing `OPENAI_CLASS`), and:

```java
  static String artifactId(Wire wire) {
    return switch (wire) {
      case OPENAI_CHAT, OPENAI_RESPONSES -> "nessy-inference-openai";
      case ANTHROPIC -> "nessy-inference-anthropic";
      case GEMINI -> "nessy-inference-gemini";
    };
  }

  private static String adapterClassName(Wire wire) {
    return switch (wire) {
      case OPENAI_CHAT -> OPENAI_CHAT_CLASS;
      case OPENAI_RESPONSES -> throw unbuilt();
      case ANTHROPIC -> ANTHROPIC_CLASS;
      case GEMINI -> GEMINI_CLASS;
    };
  }

  /** The Responses adapter lands in the next commit; selecting its wire before then is an error, not a skip. */
  private static IllegalStateException unbuilt() {
    return new IllegalStateException("the openai-responses wire has no adapter yet");
  }
```

In `build`: `case OPENAI_CHAT -> OpenAiChat.build(resolved, mapper);` and `case OPENAI_RESPONSES -> throw unbuilt();`. Rename the nested class `OpenAi` to `OpenAiChat`.

- [ ] **Step 11: Update the Boot tests' wire values and add the retired-value test**

`InferenceProvidersAutoConfigurationTest` line 216: `"nessy.providers.mine.wire=openai"` → `"nessy.providers.mine.wire=openai-chat"`. `PresetCandidatesLiveTest` line 323: `".wire=openai"` → `".wire=openai-chat"`, and its class javadoc's `{@code nessy.providers.<id>.wire =openai}` → `=openai-chat` and `a wire other than {@code openai}` → `a wire other than {@code openai-chat}` (twice, class javadoc and `Candidate`'s). `InferenceReportTest` lines 76-77: `openai (openai,` → `openai (openai-chat,` and `xai (openai,` → `xai (openai-chat,`.

Add to `InferenceProvidersAutoConfigurationTest`, after `a_custom_provider_with_a_wire_url_and_key_is_registered_with_the_wires_own_vendor`:

```java
  /**
   * The {@code 0.2.0} wire value is gone with no alias. The enum's binding error is the migration
   * guide: at a real startup Boot's failure analyzer prints the four valid values beside it.
   */
  @Test
  void the_retired_openai_wire_value_fails_to_start_naming_the_property() {
    runner
        .withPropertyValues(
            "nessy.providers.mine.wire=openai",
            "nessy.providers.mine.base-url=https://g/v1",
            "nessy.providers.mine.api-key=k")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("nessy.providers.mine.wire");
            });
  }
```

- [ ] **Step 12: Update the guide and the changelog**

`docs/guides/providers.md`: lines 75, 119 and 344 already say `OpenAiChatInferenceProvider` (Step 2). In the presets table (lines 177-186) the `wire` column's `openai` becomes `openai-chat` on the eight chat-shaped rows. Line 227 `wire: openai` → `wire: openai-chat`. Lines 233-236 become:

```markdown
`wire` is one of `openai-chat`, `openai-responses`, `anthropic` or `gemini` —
a typo is a binding error naming the allowed values, not a provider that
silently fails to exist. `vendor` defaults to the wire's own (`openai` for
both OpenAI wires). Missing `wire` or `base-url` fails startup, naming the
id and the field.
```

Line 260's report example: `openai (openai, ...` → `openai (openai-chat, ...` and `xai (openai, ...` → `xai (openai-chat, ...`. (`openai-responses` is documented in Task 8, once its adapter exists; until Task 6 it fails at startup.)

`CHANGELOG.md`, under `## [Unreleased]`:

```markdown
### Breaking changes

- **The `openai` wire is now `openai-chat`.** A custom provider with
  `nessy.providers.<id>.wire: openai` fails at startup; write `openai-chat`.
  Presets are unaffected, and the startup report now prints `openai-chat`.
- **`OpenAiInferenceProvider` is now `OpenAiChatInferenceProvider`, and
  `OpenAiProviderConfig` is now `OpenAiChatProviderConfig`.** Same factories,
  same setters, same behaviour.
- **`OpenAiRequests` is no longer public.** It was the chat adapter's
  internal projection and is now the package-private `OpenAiChatRequests`.
```

- [ ] **Step 13: Full gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?`
Expected: `exit=0`.

```bash
git add -A
git commit -m "refactor: the chat adapter is named for its wire, and openai becomes openai-chat

OpenAiInferenceProvider -> OpenAiChatInferenceProvider, OpenAiProviderConfig
-> OpenAiChatProviderConfig, OpenAiRequests -> package-private
OpenAiChatRequests. Client construction, failure classification and text
rendering move to OpenAiClients, OpenAiFailures and OpenAiRendering for the
Responses adapter to share. Wire.OPENAI -> OPENAI_CHAT; OPENAI_RESPONSES is
declared and fails loudly until its adapter lands. No behaviour change.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 2: The strict schema rewrite -- `OpenAiResponsesSchemas`

Spec §5d. A pure function over parsed JSON: no SDK, no network. Suggested implementer: Sonnet.

**Files:**
- Create: `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiResponsesSchemas.java`
- Test: `nessy-inference/openai/src/test/java/org/jwcarman/nessy/inference/openai/OpenAiResponsesSchemasTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces (package-private, used by Task 3):
  - `record OpenAiResponsesSchemas.Projected(Map<String, Object> schema, Optional<String> refusedKeyword)` with `boolean strict()` (true when `refusedKeyword` is empty)
  - `static Projected OpenAiResponsesSchemas.project(String json, JsonMapper mapper)` -- the strict rewrite when every keyword is in the strict subset; the schema as generated, with the first disqualifying keyword named, otherwise.
  - `static final Set<String> OpenAiResponsesSchemas.STRICT_KEYWORDS`

- [ ] **Step 1: Write the failing tests**

```java
package org.jwcarman.nessy.inference.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The strict-mode projection of a tool's schema.
 *
 * <p>The fixtures are written by hand in the shape {@code VictoolsJsonSchemaGenerator} emits --
 * copied from its real output on 2026-09-29 -- because the generator lives in {@code nessy-engine},
 * which already depends on this module, so it cannot be a test dependency here without a reactor
 * cycle. {@code VictoolsJsonSchemaGeneratorTest} pins the generator's side: an {@code Optional}
 * component left out of {@code required}, a sealed vocabulary as a {@code oneOf} with a {@code
 * const} discriminator, shared records under {@code $defs}.
 */
class OpenAiResponsesSchemasTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static Map<String, Object> parse(String json) {
    return MAPPER.readValue(json, new TypeReference<>() {});
  }

  private static OpenAiResponsesSchemas.Projected project(String json) {
    return OpenAiResponsesSchemas.project(json, MAPPER);
  }

  /** {@code record Lookup(String q, Optional<String> reason, int limit)}, as generated. */
  private static final String LOOKUP =
      """
      {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
       "properties":{"limit":{"type":"integer"},"q":{"type":"string"},
                     "reason":{"type":["string","null"]}},
       "required":["limit","q"]}""";

  @Nested
  class ARewrittenSchema {

    @Test
    void lists_every_property_as_required_and_forbids_any_other() {
      OpenAiResponsesSchemas.Projected projected = project(LOOKUP);

      assertThat(projected.strict()).isTrue();
      assertThat(projected.schema())
          .isEqualTo(
              parse(
                  """
                  {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                   "properties":{"limit":{"type":"integer"},"q":{"type":"string"},
                                 "reason":{"type":["string","null"]}},
                   "required":["limit","q","reason"],
                   "additionalProperties":false}"""));
    }

    /** The generator already widened it; widening again would write {@code "null"} twice. */
    @Test
    void leaves_an_optional_that_already_admits_null_as_it_was() {
      Object reason =
          ((Map<?, ?>) project(LOOKUP).schema().get("properties")).get("reason");

      assertThat(reason).isEqualTo(parse("{\"type\":[\"string\",\"null\"]}"));
    }

    @Test
    void widens_a_plain_optional_type_to_admit_null() {
      OpenAiResponsesSchemas.Projected projected =
          project(
              """
              {"type":"object","properties":{"q":{"type":"string"},"note":{"type":"string"}},
               "required":["q"]}""");

      assertThat(projected.schema())
          .isEqualTo(
              parse(
                  """
                  {"type":"object",
                   "properties":{"q":{"type":"string"},"note":{"type":["string","null"]}},
                   "required":["q","note"],"additionalProperties":false}"""));
    }

    @Test
    void wraps_an_optional_reference_in_an_any_of_with_null() {
      OpenAiResponsesSchemas.Projected projected =
          project(
              """
              {"$defs":{"Nested":{"type":"object","properties":{"host":{"type":"string"}},
                                  "required":["host"]}},
               "type":"object",
               "properties":{"first":{"$ref":"#/$defs/Nested"},
                             "second":{"$ref":"#/$defs/Nested"}},
               "required":["first"]}""");

      assertThat(projected.schema())
          .isEqualTo(
              parse(
                  """
                  {"$defs":{"Nested":{"type":"object","properties":{"host":{"type":"string"}},
                                      "required":["host"],"additionalProperties":false}},
                   "type":"object",
                   "properties":{"first":{"$ref":"#/$defs/Nested"},
                                 "second":{"anyOf":[{"$ref":"#/$defs/Nested"},{"type":"null"}]}},
                   "required":["first","second"],"additionalProperties":false}"""));
    }

    /** Review Focus 1: strict mode checks {@code enum} as well as {@code type}. */
    @Test
    void an_optional_enum_admits_null_among_its_values() {
      OpenAiResponsesSchemas.Projected projected =
          project(
              """
              {"type":"object",
               "properties":{"colour":{"type":["string","null"],"enum":["RED","GREEN"]}},
               "required":[]}""");

      assertThat(projected.schema())
          .isEqualTo(
              parse(
                  """
                  {"type":"object",
                   "properties":{"colour":{"type":["string","null"],
                                           "enum":["RED","GREEN",null]}},
                   "required":["colour"],"additionalProperties":false}"""));
    }

    @Test
    void reaches_objects_nested_in_items_and_in_any_of_branches() {
      OpenAiResponsesSchemas.Projected projected =
          project(
              """
              {"type":"object",
               "properties":{"hosts":{"type":"array","items":{"type":"object",
                  "properties":{"name":{"type":"string"}},"required":["name"]}},
                             "either":{"anyOf":[{"type":"object","properties":{},"required":[]},
                                                {"type":"string"}]}},
               "required":["hosts","either"]}""");

      assertThat(projected.schema())
          .isEqualTo(
              parse(
                  """
                  {"type":"object",
                   "properties":{"hosts":{"type":"array","items":{"type":"object",
                      "properties":{"name":{"type":"string"}},"required":["name"],
                      "additionalProperties":false}},
                                 "either":{"anyOf":[{"type":"object","properties":{},
                                                     "required":[],"additionalProperties":false},
                                                    {"type":"string"}]}},
                   "required":["hosts","either"],"additionalProperties":false}"""));
    }

    /** {@code record Empty()}, as generated. */
    @Test
    void an_empty_record_is_strict_with_nothing_required() {
      OpenAiResponsesSchemas.Projected projected =
          project(
              """
              {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
               "properties":{}}""");

      assertThat(projected.strict()).isTrue();
      assertThat(projected.schema())
          .isEqualTo(
              parse(
                  """
                  {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                   "properties":{},"required":[],"additionalProperties":false}"""));
    }
  }

  @Nested
  class ASchemaStrictModeCannotExpress {

    /**
     * A sealed {@code Command} with {@code Restart(String host)} and {@code Shutdown(Optional<String>
     * reason)}, as generated: {@code oneOf} is not in the strict subset (§5d).
     */
    private static final String SEALED =
        """
        {"$schema":"https://json-schema.org/draft/2020-12/schema",
         "oneOf":[{"type":"object","properties":{"host":{"type":"string"},
                                                 "type":{"const":"Restart"}},
                   "required":["host","type"]},
                  {"type":"object","properties":{"reason":{"type":["string","null"]},
                                                 "type":{"const":"Shutdown"}},
                   "required":["type"]}]}""";

    @Test
    void a_sealed_vocabulary_goes_as_generated_naming_one_of() {
      OpenAiResponsesSchemas.Projected projected = project(SEALED);

      assertThat(projected.strict()).isFalse();
      assertThat(projected.refusedKeyword()).contains("oneOf");
      assertThat(projected.schema()).isEqualTo(parse(SEALED));
    }

    /**
     * {@code record Holder(Nested first, Optional<Nested> second, List<String> tags)}, as
     * generated: the generator writes an optional record as a {@code oneOf} with null, so this
     * falls back too.
     */
    @Test
    void an_optional_record_component_goes_as_generated_naming_one_of() {
      OpenAiResponsesSchemas.Projected projected =
          project(
              """
              {"$schema":"https://json-schema.org/draft/2020-12/schema",
               "$defs":{"Nested":{"type":"object","properties":{"host":{"type":"string"}},
                                  "required":["host"]}},
               "type":"object",
               "properties":{"first":{"$ref":"#/$defs/Nested"},
                             "second":{"oneOf":[{"type":"null"},{"$ref":"#/$defs/Nested"}]},
                             "tags":{"type":"array","items":{"type":"string"}}},
               "required":["first","tags"]}""");

      assertThat(projected.refusedKeyword()).contains("oneOf");
    }

    /** Review Focus 2: {@code Map<String, String>} is an open object strict mode cannot describe. */
    @Test
    void an_open_map_s_additional_properties_is_refused() {
      String json =
          """
          {"type":"object",
           "properties":{"labels":{"type":"object","additionalProperties":{"type":"string"}}},
           "required":["labels"]}""";

      OpenAiResponsesSchemas.Projected projected = project(json);

      assertThat(projected.refusedKeyword()).contains("additionalProperties");
      assertThat(projected.schema()).isEqualTo(parse(json));
    }

    @Test
    void a_keyword_outside_the_subset_is_named_wherever_it_sits() {
      OpenAiResponsesSchemas.Projected projected =
          project(
              """
              {"type":"object",
               "properties":{"name":{"type":"string","minLength":1}},
               "required":["name"]}""");

      assertThat(projected.refusedKeyword()).contains("minLength");
    }
  }
}
```

The one cast in `leaves_an_optional_that_already_admits_null_as_it_was` is to `Map<?, ?>`, which raises no unchecked warning.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl :nessy-inference-openai -am test -Dtest=OpenAiResponsesSchemasTest -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: compilation failure, `cannot find symbol: class OpenAiResponsesSchemas`.

- [ ] **Step 3: Write the implementation**

```java
package org.jwcarman.nessy.inference.openai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A tool's schema as the Responses wire's strict mode needs it: every property listed in {@code
 * required}, the ones that were optional widened to admit {@code null}, and {@code
 * additionalProperties: false} on every object, {@code $defs} included.
 *
 * <p><b>A private projection of this adapter's own.</b> The generator, the {@code JsonSchema} a
 * tool carries and every other wire are untouched; this reads the JSON text afresh and returns a
 * new document. It is safe because of what binding already does: Jackson turns a JSON {@code null}
 * for an {@code Optional} component into {@code Optional.empty()}, exactly what an omitted property
 * bound to before.
 *
 * <p><b>A schema strict mode cannot express goes as generated.</b> Strict mode accepts a documented
 * subset of JSON Schema; a schema carrying any keyword outside {@link #STRICT_KEYWORDS}, or an
 * {@code additionalProperties} that is not {@code false}, is returned unchanged with that keyword
 * named, and the caller sends it with {@code strict: false}. The walk covers {@code $defs} and
 * every nested object and branch, so one document never speaks two dialects.
 */
final class OpenAiResponsesSchemas {

  /**
   * What strict mode is documented to accept. {@code oneOf} is absent on purpose: the strict-mode
   * documentation lists {@code anyOf} and not {@code oneOf}; whether OpenAI accepts it anyway is
   * measured live, and this set is the one place that measurement would change.
   */
  static final Set<String> STRICT_KEYWORDS =
      Set.of(
          "$schema",
          "$defs",
          "$ref",
          "type",
          "properties",
          "required",
          "additionalProperties",
          "items",
          "anyOf",
          "enum",
          "const",
          "description",
          "title",
          "format",
          "pattern",
          "minimum",
          "maximum",
          "exclusiveMinimum",
          "exclusiveMaximum",
          "multipleOf",
          "minItems",
          "maxItems");

  private static final String NULL = "null";

  private OpenAiResponsesSchemas() {}

  /** The schema this wire sends, and whether it may be sent strict. */
  record Projected(Map<String, Object> schema, Optional<String> refusedKeyword) {

    boolean strict() {
      return refusedKeyword.isEmpty();
    }
  }

  static Projected project(String json, JsonMapper mapper) {
    Map<String, Object> generated = mapper.readValue(json, new TypeReference<>() {});
    Optional<String> refused = refused(generated);
    return refused.isPresent()
        ? new Projected(generated, refused)
        : new Projected(strict(generated), Optional.empty());
  }

  // ---- the check ------------------------------------------------------------------------

  private static Optional<String> refused(Map<?, ?> schema) {
    for (Map.Entry<?, ?> entry : schema.entrySet()) {
      String keyword = String.valueOf(entry.getKey());
      Object value = entry.getValue();
      if (!STRICT_KEYWORDS.contains(keyword)
          || ("additionalProperties".equals(keyword) && !Boolean.FALSE.equals(value))) {
        return Optional.of(keyword);
      }
      Optional<String> nested =
          switch (keyword) {
            case "properties", "$defs" ->
                value instanceof Map<?, ?> named ? firstRefused(named.values()) : Optional.empty();
            case "anyOf" ->
                value instanceof List<?> branches ? firstRefused(branches) : Optional.empty();
            case "items" -> value instanceof Map<?, ?> item ? refused(item) : Optional.empty();
            default -> Optional.empty();
          };
      if (nested.isPresent()) {
        return nested;
      }
    }
    return Optional.empty();
  }

  private static Optional<String> firstRefused(Iterable<?> schemas) {
    for (Object schema : schemas) {
      if (schema instanceof Map<?, ?> map) {
        Optional<String> refused = refused(map);
        if (refused.isPresent()) {
          return refused;
        }
      }
    }
    return Optional.empty();
  }

  // ---- the rewrite ----------------------------------------------------------------------

  private static Map<String, Object> strict(Map<?, ?> schema) {
    Map<String, Object> out = new LinkedHashMap<>();
    schema.forEach(
        (key, value) ->
            out.put(
                String.valueOf(key),
                switch (String.valueOf(key)) {
                  case "$defs" -> eachStrict(value);
                  case "items" -> strictOrSelf(value);
                  case "anyOf" ->
                      value instanceof List<?> branches
                          ? branches.stream().map(OpenAiResponsesSchemas::strictOrSelf).toList()
                          : value;
                  default -> value;
                }));
    if (schema.get("properties") instanceof Map<?, ?> properties) {
      Set<String> required = names(schema.get("required"));
      Map<String, Object> rewritten = new LinkedHashMap<>();
      properties.forEach(
          (name, property) -> {
            Object strict = strictOrSelf(property);
            rewritten.put(
                String.valueOf(name),
                required.contains(String.valueOf(name)) ? strict : admittingNull(strict));
          });
      out.put("properties", rewritten);
      out.put("required", new ArrayList<>(rewritten.keySet()));
      out.put("additionalProperties", false);
    } else if (isObject(schema)) {
      out.put("additionalProperties", false);
    }
    return out;
  }

  private static Object strictOrSelf(Object schema) {
    return schema instanceof Map<?, ?> map ? strict(map) : schema;
  }

  private static Object eachStrict(Object named) {
    if (!(named instanceof Map<?, ?> map)) {
      return named;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    map.forEach((name, schema) -> out.put(String.valueOf(name), strictOrSelf(schema)));
    return out;
  }

  private static boolean isObject(Map<?, ?> schema) {
    Object type = schema.get("type");
    return "object".equals(type) || (type instanceof List<?> types && types.contains("object"));
  }

  private static Set<String> names(Object required) {
    Set<String> names = new LinkedHashSet<>();
    if (required instanceof List<?> list) {
      list.forEach(name -> names.add(String.valueOf(name)));
    }
    return names;
  }

  /**
   * An optional property, widened so the model can say "nothing" for it: an {@code enum} gains
   * {@code null} among its values (and its type widens with it), a plain {@code type} gains
   * {@code "null"}, and anything else -- a {@code $ref}, a combinator, a {@code const} -- becomes an
   * {@code anyOf} of itself and {@code {"type": "null"}}. A schema that already admits null is left
   * as it was.
   */
  private static Object admittingNull(Object schema) {
    if (!(schema instanceof Map<?, ?> map) || admitsNull(map)) {
      return schema;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    map.forEach((key, value) -> out.put(String.valueOf(key), value));
    if (map.get("enum") instanceof List<?> values) {
      List<Object> widened = new ArrayList<>(values);
      if (!values.contains(null)) {
        widened.add(null);
      }
      out.put("enum", widened);
      widenType(out);
      return out;
    }
    if (!map.containsKey("const") && !map.containsKey("$ref") && widenType(out)) {
      return out;
    }
    return Map.of("anyOf", List.of(schema, Map.of("type", NULL)));
  }

  /** Adds {@code "null"} to a {@code type}; false when there was no type to widen. */
  private static boolean widenType(Map<String, Object> schema) {
    Object type = schema.get("type");
    if (type instanceof String single) {
      schema.put("type", NULL.equals(single) ? single : List.of(single, NULL));
      return true;
    }
    if (type instanceof List<?> types) {
      List<Object> widened = new ArrayList<>(types);
      if (!types.contains(NULL)) {
        widened.add(NULL);
      }
      schema.put("type", widened);
      return true;
    }
    return false;
  }

  private static boolean admitsNull(Map<?, ?> schema) {
    if (schema.get("anyOf") instanceof List<?> branches) {
      return branches.stream()
          .anyMatch(branch -> branch instanceof Map<?, ?> map && NULL.equals(map.get("type")));
    }
    Object type = schema.get("type");
    boolean typeAdmits =
        NULL.equals(type) || (type instanceof List<?> types && types.contains(NULL));
    boolean enumAdmits = !(schema.get("enum") instanceof List<?> values) || values.contains(null);
    return typeAdmits && enumAdmits && !schema.containsKey("const");
  }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-inference-openai -am test -Dtest=OpenAiResponsesSchemasTest -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: `exit=0`, 11 tests pass.

- [ ] **Step 5: Full gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?` -- expected `exit=0`.

```bash
git add nessy-inference/openai/src
git commit -m "feat: a strict-mode projection of a tool's schema for the Responses wire

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 3: The Responses request projection -- `OpenAiResponsesRequests`

Spec §5a-§5f, §5j (replay). Pure translation; no network. Suggested implementer: Sonnet. **Review: Opus** (reasoning replay is a transcript invariant).

**Files:**
- Create: `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiResponsesRequests.java`
- Modify: `nessy-inference/openai/pom.xml` (declare `slf4j-api`, which the module now logs through)
- Test: `nessy-inference/openai/src/test/java/org/jwcarman/nessy/inference/openai/OpenAiResponsesRequestsTest.java`

**Interfaces:**
- Consumes: `OpenAiRendering.system/summary/text/outcome/FAILED_TURN/REFUSED_TURN` (Task 1); `OpenAiResponsesSchemas.project(String, JsonMapper)` and `Projected` (Task 2).
- Produces (package-private, used by Task 4): `static ResponseCreateParams OpenAiResponsesRequests.toParams(InferenceRequest request, String vendor, JsonMapper mapper)`. `vendor` is the provider's configured vendor: only `Block.Provider` blocks tagged with it are replayed.

**The replay rule, made concrete** (§5j): the turn in flight is the last turn in the context when its `result()` is null. A `Block.Provider` block tagged with this vendor is replayed as a reasoning item only when it sits in the **last exchange of the turn in flight** -- the exchange whose outcomes are being sent back for the first time -- in stored order, so it precedes the call it arrived with. Every other `Provider` block (an earlier exchange of the same turn, an earlier turn's exchanges, any answer) is not sent. Widening to "every exchange of the turn in flight" is one boolean in `replays(...)` and is Task 7's call on measurement.

- [ ] **Step 1: Declare the logging API**

In `nessy-inference/openai/pom.xml`, after the `openai-java` dependency:

```xml
    <!-- The strict-mode fallback is logged at WARN; the version is managed by the parent. -->
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
    </dependency>
```

- [ ] **Step 2: Write the failing tests**

```java
package org.jwcarman.nessy.inference.openai;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ToolChoiceOptions;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.ToolChoice;
import org.jwcarman.nessy.inference.ToolOffer;
import org.jwcarman.nessy.inference.Toolset;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The projection onto the Responses wire, with no network anywhere near it.
 *
 * <p>This is where the adapter's judgement lives -- the prompt in {@code instructions}, one input
 * item per stored block, which reasoning items travel back, the strict rewrite -- so it is asserted
 * on the built params rather than only on what a live call happens to accept.
 */
class OpenAiResponsesRequestsTest {

  private static final SystemPrompt SYSTEM = new SystemPrompt("you are a helpful assistant");
  private static final InferenceOptions OPTIONS = new InferenceOptions("gpt-4o", 1024);
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String VENDOR = "openai";

  /** An encrypted reasoning item as the provider stores it (Task 4 writes this shape). */
  private static final String REASONING =
      "{\"id\":\"rs_1\",\"encrypted_content\":\"AAAA\","
          + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"weighing it\"}]}";

  private static InferenceRequest request(List<Turn> turns) {
    return new InferenceRequest(SYSTEM, InferenceContext.of(turns), Toolset.none(), OPTIONS);
  }

  private static ResponseCreateParams params(InferenceRequest request) {
    return OpenAiResponsesRequests.toParams(request, VENDOR, MAPPER);
  }

  private static List<ResponseInputItem> itemsOf(List<Turn> turns) {
    return params(request(turns)).input().orElseThrow().asResponse();
  }

  private static Input asked(long seq, String text) {
    return new Input(new Seq(seq), List.of(new Block.Text(text)));
  }

  private static Turn answered(long id, String question, String answer) {
    return new Turn(
        new TurnId(id),
        asked(id, question),
        List.of(),
        new TurnResult.Answered(List.of(new Block.Text(answer))),
        0);
  }

  private static Turn open(long id, String question) {
    return new Turn(new TurnId(id), asked(id, question), List.of(), null, 0);
  }

  private static Turn inFlight(List<Exchange> exchanges) {
    return new Turn(new TurnId(1), asked(1, "look it up"), exchanges, null, 0);
  }

  private static Exchange exchange(long seq, List<Block.ActionRequestContent> request, String callId) {
    return new Exchange(
        new Seq(seq),
        request,
        List.of(new ToolOutcome.Succeeded(new CallId(callId), List.of(new Block.Text("ok")))));
  }

  private static Block.ToolCall call(String id) {
    return new Block.ToolCall(new CallId(id), new ToolName("lookup"), "{\"q\":\"a\"}");
  }

  private static EasyInputMessage message(ResponseInputItem item) {
    return item.asEasyInputMessage();
  }

  private static ToolOffer offer(String name, String schema) {
    return new ToolOffer(new ToolName(name), "does " + name, new JsonSchema(schema));
  }

  private static final String LOOKUP_SCHEMA =
      """
      {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
       "properties":{"q":{"type":"string"},"reason":{"type":["string","null"]}},
       "required":["q"]}""";

  private static final String SEALED_SCHEMA =
      """
      {"oneOf":[{"type":"object","properties":{"type":{"const":"Restart"}},"required":["type"]},
                {"type":"object","properties":{"type":{"const":"Shutdown"}},"required":["type"]}]}""";

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
  class TheInstructions {

    @Test
    void carry_the_system_prompt_and_nothing_else_leads_the_input() {
      ResponseCreateParams params = params(request(List.of(open(1, "hello"))));

      assertThat(params.instructions()).contains("you are a helpful assistant");
      assertThat(params.input().orElseThrow().asResponse().getFirst().asEasyInputMessage().role())
          .isEqualTo(EasyInputMessage.Role.USER);
    }

    @Test
    void carry_ambient_background_in_labelled_sections() {
      InferenceRequest request =
          new InferenceRequest(
              SYSTEM,
              new InferenceContext(
                  List.of(open(1, "hello")), List.of(Ambient.text("clock", "it is Tuesday"))),
              Toolset.none(),
              OPTIONS);

      assertThat(params(request).instructions().orElseThrow())
          .isEqualTo("you are a helpful assistant\n\n<clock>\nit is Tuesday\n</clock>");
    }
  }

  @Nested
  class TheModelAndCeiling {

    @Test
    void come_from_the_options_rather_than_from_the_adapter() {
      ResponseCreateParams params = params(request(List.of(open(1, "hi"))));

      assertThat(params.model().orElseThrow().asString()).isEqualTo("gpt-4o");
      assertThat(params.maxOutputTokens()).contains(1024L);
    }

    @Test
    void the_ceiling_is_omitted_entirely_when_none_was_asked_for() {
      InferenceRequest request =
          new InferenceRequest(
              SYSTEM,
              InferenceContext.of(List.of(open(1, "hi"))),
              Toolset.none(),
              InferenceOptions.of("gpt-4o"));

      assertThat(params(request).maxOutputTokens()).isEmpty();
    }
  }

  @Nested
  class ATurn {

    @Test
    void becomes_a_user_item_and_the_assistant_item_that_followed_it() {
      List<ResponseInputItem> items = itemsOf(List.of(answered(1, "hello", "hi there")));

      assertThat(items).hasSize(2);
      assertThat(message(items.get(0)).role()).isEqualTo(EasyInputMessage.Role.USER);
      assertThat(message(items.get(0)).content().asTextInput()).isEqualTo("hello");
      assertThat(message(items.get(1)).role()).isEqualTo(EasyInputMessage.Role.ASSISTANT);
      assertThat(message(items.get(1)).content().asTextInput()).isEqualTo("hi there");
    }

    @Test
    void still_in_flight_is_sent_with_no_answer_after_it() {
      assertThat(itemsOf(List.of(open(1, "hello")))).hasSize(1);
    }

    @Test
    void that_failed_is_explained_by_a_system_item_rather_than_left_silent() {
      Turn failed =
          new Turn(new TurnId(1), asked(1, "hello"), List.of(), new TurnResult.Failed(), 0);

      List<ResponseInputItem> items = itemsOf(List.of(failed, open(2, "again")));

      assertThat(message(items.get(1)).role()).isEqualTo(EasyInputMessage.Role.SYSTEM);
      assertThat(message(items.get(1)).content().asTextInput())
          .isEqualTo("The previous attempt to answer did not complete.");
    }

    @Test
    void that_was_refused_drops_its_question_and_says_so_in_its_place() {
      Turn refused =
          new Turn(
              new TurnId(1),
              asked(1, "something disallowed"),
              List.of(),
              new TurnResult.Refused(),
              0);

      List<ResponseInputItem> items = itemsOf(List.of(refused, open(2, "next")));

      assertThat(items).hasSize(2);
      assertThat(message(items.get(0)).role()).isEqualTo(EasyInputMessage.Role.SYSTEM);
      assertThat(message(items.get(0)).content().asTextInput())
          .isEqualTo(
              "A previous message was withdrawn from this conversation and is no longer available.");
    }
  }

  @Test
  void a_summary_leads_the_input_as_a_tagged_user_item() {
    InferenceRequest request =
        new InferenceRequest(
            SYSTEM,
            new InferenceContext(
                List.of(Summary.text(new TurnId(1), new TurnId(3), "they met")),
                List.of(open(4, "hi")),
                List.of()),
            Toolset.none(),
            OPTIONS);

    ResponseInputItem first = params(request).input().orElseThrow().asResponse().getFirst();

    assertThat(message(first).role()).isEqualTo(EasyInputMessage.Role.USER);
    assertThat(message(first).content().asTextInput())
        .isEqualTo("<summary from=\"1\" through=\"3\">\nthey met\n</summary>");
  }

  @Nested
  class AnExchange {

    @Test
    void becomes_one_item_per_block_in_stored_order_then_one_output_per_call() {
      Exchange exchange =
          new Exchange(
              new Seq(2),
              List.of(new Block.Commentary("Let me look."), call("call_1"), call("call_2")),
              List.of(
                  new ToolOutcome.Succeeded(new CallId("call_1"), List.of(new Block.Text("first"))),
                  new ToolOutcome.Succeeded(
                      new CallId("call_2"), List.of(new Block.Text("second")))));

      List<ResponseInputItem> items = itemsOf(List.of(inFlight(List.of(exchange))));

      assertThat(items).hasSize(6);
      assertThat(message(items.get(1)).role()).isEqualTo(EasyInputMessage.Role.ASSISTANT);
      assertThat(message(items.get(1)).content().asTextInput()).isEqualTo("Let me look.");
      assertThat(items.get(2).asFunctionCall().callId()).isEqualTo("call_1");
      assertThat(items.get(2).asFunctionCall().name()).isEqualTo("lookup");
      assertThat(items.get(2).asFunctionCall().arguments()).isEqualTo("{\"q\":\"a\"}");
      assertThat(items.get(3).asFunctionCall().callId()).isEqualTo("call_2");
      assertThat(items.get(4).asFunctionCallOutput().callId()).contains("call_1");
      assertThat(items.get(4).asFunctionCallOutput().output().asString()).isEqualTo("first");
      assertThat(items.get(5).asFunctionCallOutput().callId()).contains("call_2");
    }

    @Test
    void reports_a_failed_call_in_words() {
      Exchange exchange =
          new Exchange(
              new Seq(2),
              List.of(call("call_1")),
              List.of(new ToolOutcome.Failed(new CallId("call_1"), "the service was down")));

      List<ResponseInputItem> items = itemsOf(List.of(inFlight(List.of(exchange))));

      assertThat(items.get(2).asFunctionCallOutput().output().asString())
          .isEqualTo("Error: the service was down");
    }

    @Test
    void tells_the_model_a_denied_call_was_not_permitted_rather_than_that_it_broke() {
      Exchange exchange =
          new Exchange(
              new Seq(2),
              List.of(call("call_1")),
              List.of(new ToolOutcome.Denied(new CallId("call_1"), "out of hours")));

      List<ResponseInputItem> items = itemsOf(List.of(inFlight(List.of(exchange))));

      assertThat(items.get(2).asFunctionCallOutput().output().asString())
          .isEqualTo("This call was not run because it was not permitted: out of hours");
    }
  }

  @Nested
  class AReasoningItem {

    @Test
    void in_the_exchange_being_answered_is_replayed_ahead_of_the_call_it_arrived_with() {
      Exchange exchange =
          exchange(2, List.of(new Block.Provider(VENDOR, REASONING), call("call_1")), "call_1");

      List<ResponseInputItem> items = itemsOf(List.of(inFlight(List.of(exchange))));

      assertThat(items).hasSize(4);
      assertThat(items.get(1).isReasoning()).isTrue();
      assertThat(items.get(1).asReasoning().id()).isEqualTo("rs_1");
      assertThat(items.get(1).asReasoning().encryptedContent()).contains("AAAA");
      assertThat(items.get(1).asReasoning().summary())
          .extracting(part -> part.text())
          .containsExactly("weighing it");
      assertThat(items.get(2).asFunctionCall().callId()).isEqualTo("call_1");
    }

    @Test
    void in_an_earlier_exchange_of_the_same_turn_is_not_replayed_under_the_starting_rule() {
      Exchange first =
          exchange(2, List.of(new Block.Provider(VENDOR, REASONING), call("call_1")), "call_1");
      Exchange second = exchange(3, List.of(call("call_2")), "call_2");

      List<ResponseInputItem> items = itemsOf(List.of(inFlight(List.of(first, second))));

      assertThat(items).isNotEmpty().noneMatch(ResponseInputItem::isReasoning);
    }

    @Test
    void in_an_earlier_turn_s_exchange_is_not_replayed() {
      Turn earlier =
          new Turn(
              new TurnId(1),
              asked(1, "look it up"),
              List.of(
                  exchange(
                      2, List.of(new Block.Provider(VENDOR, REASONING), call("call_1")), "call_1")),
              new TurnResult.Answered(List.of(new Block.Text("done"))),
              0);

      List<ResponseInputItem> items = itemsOf(List.of(earlier, open(3, "and now?")));

      assertThat(items).isNotEmpty().noneMatch(ResponseInputItem::isReasoning);
      assertThat(items).anyMatch(ResponseInputItem::isFunctionCall);
    }

    @Test
    void beside_an_earlier_turn_s_answer_is_not_replayed() {
      Turn earlier =
          new Turn(
              new TurnId(1),
              asked(1, "hello"),
              List.of(),
              new TurnResult.Answered(
                  List.of(new Block.Provider(VENDOR, REASONING), new Block.Text("the answer"))),
              0);

      List<ResponseInputItem> items = itemsOf(List.of(earlier, open(2, "and now?")));

      assertThat(items).isNotEmpty().noneMatch(ResponseInputItem::isReasoning);
      assertThat(message(items.get(1)).content().asTextInput()).isEqualTo("the answer");
    }

    @Test
    void tagged_by_another_vendor_is_dropped_leaving_its_siblings_in_order() {
      Exchange exchange =
          exchange(
              2,
              List.of(new Block.Provider("anthropic", "{\"type\":\"thinking\"}"), call("call_1")),
              "call_1");

      List<ResponseInputItem> items = itemsOf(List.of(inFlight(List.of(exchange))));

      assertThat(items).hasSize(3);
      assertThat(items.get(1).asFunctionCall().callId()).isEqualTo("call_1");
    }

    @Test
    void tagged_openai_is_not_replayed_by_a_provider_answering_for_x_ai() {
      Exchange exchange =
          exchange(2, List.of(new Block.Provider(VENDOR, REASONING), call("call_1")), "call_1");

      List<ResponseInputItem> items =
          OpenAiResponsesRequests.toParams(request(List.of(inFlight(List.of(exchange)))), "x_ai", MAPPER)
              .input()
              .orElseThrow()
              .asResponse();

      assertThat(items).isNotEmpty().noneMatch(ResponseInputItem::isReasoning);
    }

    /** Review Focus 3: half a reasoning item is worse than none. */
    @Test
    void an_openai_block_without_encrypted_content_is_not_replayed() {
      Exchange exchange =
          exchange(
              2,
              List.of(new Block.Provider(VENDOR, "{\"id\":\"rs_1\",\"summary\":[]}"), call("call_1")),
              "call_1");

      List<ResponseInputItem> items = itemsOf(List.of(inFlight(List.of(exchange))));

      assertThat(items).isNotEmpty().noneMatch(ResponseInputItem::isReasoning);
    }
  }

  @Nested
  class TheStatelessContract {

    @Test
    void every_request_says_store_false_and_asks_for_encrypted_reasoning() {
      ResponseCreateParams params = params(request(List.of(open(1, "hi"))));

      assertThat(params.store()).contains(false);
      assertThat(params.include().orElseThrow())
          .containsExactly(ResponseIncludable.REASONING_ENCRYPTED_CONTENT);
    }

    @Test
    void no_request_names_a_previous_response_or_a_conversation() {
      ResponseCreateParams params = params(request(List.of(open(1, "hi"))));

      assertThat(params.previousResponseId()).isEmpty();
      assertThat(params.conversation()).isEmpty();
    }

    /** Until vendor properties ask for one (§5g), a reasoning object is a 400 on a model that does not reason. */
    @Test
    void no_reasoning_object_is_sent() {
      assertThat(params(request(List.of(open(1, "hi")))).reasoning()).isEmpty();
    }
  }

  @Nested
  class ABoundTool {

    private static InferenceRequest offering(List<ToolOffer> offers) {
      return new InferenceRequest(
          SYSTEM, InferenceContext.of(List.of(open(1, "hi"))), Toolset.of(offers), OPTIONS);
    }

    @Test
    void becomes_a_strict_function_tool_carrying_the_rewritten_schema() {
      FunctionTool tool =
          params(offering(List.of(offer("lookup", LOOKUP_SCHEMA))))
              .tools()
              .orElseThrow()
              .getFirst()
              .asFunction();

      assertThat(tool.name()).isEqualTo("lookup");
      assertThat(tool.description()).contains("does lookup");
      assertThat(tool.strict()).contains(true);
      Map<String, Object> schema = sent(tool.parameters().orElseThrow());
      assertThat(schema).containsEntry("required", List.of("q", "reason"));
      assertThat(schema).containsEntry("additionalProperties", false);
    }

    @Test
    void the_schema_the_offer_carries_is_unchanged_by_the_rewrite() {
      ToolOffer lookup = offer("lookup", LOOKUP_SCHEMA);

      params(offering(List.of(lookup)));

      assertThat(lookup.schema().json()).isEqualTo(LOOKUP_SCHEMA);
    }

    @Test
    void a_schema_strict_mode_cannot_express_goes_as_generated_with_a_warning_and_its_neighbour_stays_strict() {
      Logger logger = (Logger) LoggerFactory.getLogger(OpenAiResponsesRequests.class);
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
      List<FunctionTool> tools;
      try {
        tools =
            params(offering(List.of(offer("restart", SEALED_SCHEMA), offer("lookup", LOOKUP_SCHEMA))))
                .tools()
                .orElseThrow()
                .stream()
                .map(tool -> tool.asFunction())
                .toList();
      } finally {
        logger.detachAppender(appender);
      }

      assertThat(tools.get(0).strict()).contains(false);
      assertThat(sent(tools.get(0).parameters().orElseThrow())).containsKey("oneOf");
      assertThat(tools.get(1).strict()).contains(true);
      assertThat(appender.list)
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("restart").contains("oneOf");
              });
    }

    @Test
    void is_absent_entirely_when_none_were_bound() {
      assertThat(params(request(List.of(open(1, "hi")))).tools()).isEmpty();
    }
  }

  @Nested
  class ChoosingATool {

    private static ResponseCreateParams choosing(ToolChoice choice) {
      return params(
          new InferenceRequest(
              SYSTEM,
              InferenceContext.of(List.of(open(1, "hi"))),
              new Toolset(List.of(offer("lookup", LOOKUP_SCHEMA)), choice),
              OPTIONS));
    }

    @Test
    void by_default_nothing_is_said_about_choosing() {
      assertThat(choosing(ToolChoice.auto()).toolChoice()).isEmpty();
    }

    @Test
    void a_ban_is_sent_as_none() {
      assertThat(choosing(new ToolChoice.None()).toolChoice().orElseThrow().options())
          .contains(ToolChoiceOptions.NONE);
    }

    /** Emulated, as on the chat wire: "none" is documented as "generate a message instead". */
    @Test
    void answering_now_is_sent_as_none_with_the_offers_left_in_place() {
      ResponseCreateParams params = choosing(new ToolChoice.Answer());

      assertThat(params.toolChoice().orElseThrow().options()).contains(ToolChoiceOptions.NONE);
      assertThat(params.tools().orElseThrow()).hasSize(1);
    }

    @Test
    void requiring_some_tool_is_sent_as_required() {
      assertThat(choosing(new ToolChoice.Any()).toolChoice().orElseThrow().options())
          .contains(ToolChoiceOptions.REQUIRED);
    }

    @Test
    void requiring_one_tool_names_it() {
      assertThat(
              choosing(new ToolChoice.Named(new ToolName("lookup")))
                  .toolChoice()
                  .orElseThrow()
                  .function()
                  .orElseThrow()
                  .name())
          .isEqualTo("lookup");
    }

    @Test
    void nothing_is_said_when_nothing_is_on_offer() {
      assertThat(params(request(List.of(open(1, "hi")))).toolChoice()).isEmpty();
    }
  }

  @Test
  void an_answer_s_shape_becomes_a_strict_text_format_named_answer() {
    InferenceRequest request =
        new InferenceRequest(
            SYSTEM,
            InferenceContext.of(List.of(open(1, "hi"))),
            Toolset.none(),
            OPTIONS,
            Optional.of(new JsonSchema(LOOKUP_SCHEMA)));

    var format = params(request).text().orElseThrow().format().orElseThrow().asJsonSchema();

    assertThat(format.name()).isEqualTo("answer");
    assertThat(format.strict()).contains(true);
    assertThat(sent(format.schema())).containsEntry("required", List.of("q", "reason"));
  }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q -pl :nessy-inference-openai -am test -Dtest=OpenAiResponsesRequestsTest -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: compilation failure, `cannot find symbol: class OpenAiResponsesRequests`.

- [ ] **Step 4: Write the implementation**

```java
package org.jwcarman.nessy.inference.openai;

import com.openai.core.JsonValue;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolChoiceFunction;
import com.openai.models.responses.ToolChoiceOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.ToolChoice;
import org.jwcarman.nessy.inference.ToolOffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turn-speak into Responses shape.
 *
 * <p>Pure translation; nothing here touches the network. <b>Stateless by construction</b>: every
 * request carries the whole context and {@code store: false}, and nothing here can name a previous
 * response or a conversation -- Nessy's event log is the only conversation there is.
 */
final class OpenAiResponsesRequests {

  private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesRequests.class);

  private OpenAiResponsesRequests() {}

  /**
   * @param vendor the provider's own vendor tag; only {@code Block.Provider} blocks carrying it are
   *     replayed
   * @param mapper reads a tool's schema and a stored reasoning item; supplied, never made here
   */
  static ResponseCreateParams toParams(InferenceRequest request, String vendor, JsonMapper mapper) {
    InferenceOptions options = request.options();
    List<ResponseInputItem> input = new ArrayList<>();
    request
        .context()
        .summaries()
        .forEach(
            summary -> input.add(message(EasyInputMessage.Role.USER, OpenAiRendering.summary(summary))));
    List<Turn> turns = request.context().turns();
    for (int i = 0; i < turns.size(); i++) {
      Turn turn = turns.get(i);
      boolean inFlight = i == turns.size() - 1 && turn.result() == null;
      input.addAll(items(turn, inFlight, vendor, mapper));
    }

    // store is sent explicitly: the API's default is true, and a default is a thing that changes.
    ResponseCreateParams.Builder builder =
        ResponseCreateParams.builder()
            .model(options.modelName())
            .instructions(OpenAiRendering.system(request))
            .inputOfResponse(input)
            .store(false)
            .addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT);
    if (options.hasMaxTokens()) {
      builder.maxOutputTokens(options.maxTokens());
    }
    request.toolset().offers().forEach(offer -> builder.addTool(toFunctionTool(offer, mapper)));
    chooseTool(builder, request.toolset().offers(), request.toolset().choice());
    request.outputSchema().ifPresent(schema -> constrainAnswer(builder, schema, mapper));
    return builder.build();
  }

  /**
   * One turn as input items. An exchange is one item per stored block, in stored order -- a
   * reasoning item must precede the call it led to -- followed by one output per call.
   */
  private static List<ResponseInputItem> items(
      Turn turn, boolean inFlight, String vendor, JsonMapper mapper) {
    List<ResponseInputItem> items = new ArrayList<>();
    if (!(turn.result() instanceof TurnResult.Refused)) {
      items.add(message(EasyInputMessage.Role.USER, OpenAiRendering.text(turn.input().blocks())));
    }
    List<Exchange> exchanges = turn.exchanges();
    for (int i = 0; i < exchanges.size(); i++) {
      Exchange exchange = exchanges.get(i);
      boolean replaying = replays(inFlight, i == exchanges.size() - 1);
      for (Block.ActionRequestContent block : exchange.request()) {
        switch (block) {
          case Block.Commentary(String said) ->
              items.add(message(EasyInputMessage.Role.ASSISTANT, said));
          case Block.ToolCall call -> items.add(functionCall(call));
          case Block.Provider provider -> {
            if (replaying) {
              ours(provider, vendor, mapper).ifPresent(items::add);
            }
          }
        }
      }
      exchange
          .outcomes()
          .forEach(
              outcome ->
                  items.add(
                      ResponseInputItem.ofFunctionCallOutput(
                          ResponseInputItem.FunctionCallOutput.builder()
                              .callId(outcome.callId().value())
                              .output(OpenAiRendering.outcome(outcome))
                              .build())));
    }
    switch (turn.result()) {
      case null -> {
        // In flight: nothing after the question yet.
      }
      case TurnResult.Answered(var blocks) ->
          items.add(message(EasyInputMessage.Role.ASSISTANT, OpenAiRendering.text(blocks)));
      case TurnResult.Failed _ ->
          items.add(message(EasyInputMessage.Role.SYSTEM, OpenAiRendering.FAILED_TURN));
      case TurnResult.Refused _ ->
          items.add(message(EasyInputMessage.Role.SYSTEM, OpenAiRendering.REFUSED_TURN));
    }
    return items;
  }

  /**
   * The replay rule (§5j), in one place: a reasoning item travels with the tool results it led to
   * -- the last exchange of the turn in flight -- and never across turns. Widening it to every
   * exchange of the turn in flight is this method answering {@code inFlight} alone.
   */
  private static boolean replays(boolean inFlight, boolean lastExchange) {
    return inFlight && lastExchange;
  }

  /**
   * This provider's own reasoning item, built back from what the adapter stored; anything else --
   * another vendor's state, or an item with nothing encrypted to hand back -- is not sent.
   */
  private static Optional<ResponseInputItem> ours(
      Block.Provider block, String vendor, JsonMapper mapper) {
    if (!vendor.equals(block.vendor())) {
      return Optional.empty();
    }
    Map<String, Object> data = mapper.readValue(block.payload(), new TypeReference<>() {});
    if (!(data.get("id") instanceof String id)
        || !(data.get("encrypted_content") instanceof String encrypted)) {
      return Optional.empty();
    }
    List<ResponseReasoningItem.Summary> summary = new ArrayList<>();
    if (data.get("summary") instanceof List<?> parts) {
      for (Object part : parts) {
        if (part instanceof Map<?, ?> fields && fields.get("text") instanceof String text) {
          summary.add(ResponseReasoningItem.Summary.builder().text(text).build());
        }
      }
    }
    return Optional.of(
        ResponseInputItem.ofReasoning(
            ResponseReasoningItem.builder()
                .id(id)
                .encryptedContent(encrypted)
                .summary(summary)
                .build()));
  }

  /** The {@code call_id} is the one a function_call_output quotes; the {@code fc_} item id is not kept. */
  private static ResponseInputItem functionCall(Block.ToolCall call) {
    return ResponseInputItem.ofFunctionCall(
        ResponseFunctionToolCall.builder()
            .callId(call.id().value())
            .name(call.name().value())
            .arguments(call.arguments())
            .build());
  }

  private static ResponseInputItem message(EasyInputMessage.Role role, String text) {
    return ResponseInputItem.ofEasyInputMessage(
        EasyInputMessage.builder().role(role).content(text).build());
  }

  /**
   * One function tool, strict when the rewrite could express its schema (§5d). A schema it could
   * not is sent as generated with {@code strict: false}, and says so, per tool.
   */
  private static Tool toFunctionTool(ToolOffer offer, JsonMapper mapper) {
    OpenAiResponsesSchemas.Projected projected =
        OpenAiResponsesSchemas.project(offer.schema().json(), mapper);
    projected
        .refusedKeyword()
        .ifPresent(
            keyword ->
                log.warn(
                    "Tool {} is offered without strict mode: its schema uses {}, which strict mode"
                        + " cannot express",
                    offer.name().value(),
                    keyword));
    FunctionTool.Parameters.Builder parameters = FunctionTool.Parameters.builder();
    projected
        .schema()
        .forEach((name, value) -> parameters.putAdditionalProperty(name, JsonValue.from(value)));
    return Tool.ofFunction(
        FunctionTool.builder()
            .name(offer.name().value())
            .description(offer.description())
            .parameters(parameters.build())
            .strict(projected.strict())
            .build());
  }

  /**
   * The answer's shape as {@code text.format}, rewritten as a tool's schema is: strict mode needs
   * the same shape here. The name is a label the wire requires and nothing reads.
   */
  private static void constrainAnswer(
      ResponseCreateParams.Builder builder, JsonSchema schema, JsonMapper mapper) {
    OpenAiResponsesSchemas.Projected projected =
        OpenAiResponsesSchemas.project(schema.json(), mapper);
    projected
        .refusedKeyword()
        .ifPresent(
            keyword ->
                log.warn(
                    "The answer's shape is asked for without strict mode: its schema uses {},"
                        + " which strict mode cannot express",
                    keyword));
    ResponseFormatTextJsonSchemaConfig.Schema.Builder shape =
        ResponseFormatTextJsonSchemaConfig.Schema.builder();
    projected
        .schema()
        .forEach((name, value) -> shape.putAdditionalProperty(name, JsonValue.from(value)));
    builder.text(
        ResponseTextConfig.builder()
            .format(
                ResponseFormatTextJsonSchemaConfig.builder()
                    .name("answer")
                    .schema(shape.build())
                    .strict(projected.strict())
                    .build())
            .build());
  }

  /**
   * The chat adapter's mapping on this wire's vocabulary. {@code Answer} is emulated as {@code
   * none}, the offers left in place so a cached prefix is not disturbed; if a model ever answers
   * empty under it, the fallback is to send no tools at all, at the cost of the cache.
   */
  private static void chooseTool(
      ResponseCreateParams.Builder builder, List<ToolOffer> tools, ToolChoice choice) {
    if (tools.isEmpty()) {
      return;
    }
    switch (choice) {
      case ToolChoice.Auto _ -> {
        // What the absent field already means.
      }
      case ToolChoice.None _, ToolChoice.Answer _ -> builder.toolChoice(ToolChoiceOptions.NONE);
      case ToolChoice.Any _ -> builder.toolChoice(ToolChoiceOptions.REQUIRED);
      case ToolChoice.Named(ToolName name) ->
          builder.toolChoice(ToolChoiceFunction.builder().name(name.value()).build());
    }
  }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-inference-openai -am test -Dtest=OpenAiResponsesRequestsTest -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: `exit=0`.

- [ ] **Step 6: Full gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?` -- expected `exit=0`.

```bash
git add nessy-inference/openai
git commit -m "feat: the Responses projection -- stateless, one item per block, strict tools

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 4: The Responses provider -- config, stream, read, usage, reasoning items stored

Spec §4b, §5 (intro), §5h, §5i, §5j (storage), §5k. Suggested implementer: Sonnet. **Review: Opus** (what is stored, and in what order, is the transcript's contract).

**Files:**
- Create: `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiResponsesProviderConfig.java`
- Create: `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiResponsesInferenceProvider.java`
- Modify: `OpenAiChatInferenceProvider.java` (`NAME` becomes package-private)
- Modify: `OpenAiFailures.java` (add `classify(ResponseError)`)
- Test (create): `ResponseStreams.java` (fixture: the fake client and the event builders), `OpenAiResponsesInferenceProviderTest.java`, `OpenAiResponsesProviderConfigTest.java` -- all under `nessy-inference/openai/src/test/java/org/jwcarman/nessy/inference/openai/`

**Interfaces:**
- Consumes: `OpenAiClients.build(...)`, `OpenAiClients.requirePositive(...)`, `OpenAiFailures.classify(OpenAIException)` (Task 1); `OpenAiResponsesRequests.toParams(InferenceRequest, String, JsonMapper)` (Task 3); `OpenAiChatInferenceProvider.VENDOR`, `.NAME`.
- Produces (public, spec §11): `OpenAiResponsesInferenceProvider implements InferenceProvider, AutoCloseable` with `static fromEnv()`, `static of(Customizer<OpenAiResponsesProviderConfig>)`, `static of(List<Customizer<OpenAiResponsesProviderConfig>>)`, `infer(InferenceRequest, InferenceNarrator)`, `close()`, `String name()` (`"OpenAI"`), `String vendor()`. `OpenAiResponsesProviderConfig` with `apiKey`, `fromEnv`, `baseUrl`, `organization`, `client`, `mapper`, `timeout`, `vendor` -- each returning the config.
- Produces (package-private, used by Task 5): `OpenAiResponsesInferenceProvider(OpenAIClient, String vendor, boolean ownsClient, JsonMapper)`; `OpenAiResponsesProviderConfig()` and `OpenAiResponsesInferenceProvider build()`; `static Failure OpenAiFailures.classify(ResponseError error)`. Test fixture `ResponseStreams` (below).
- The stored payload of a reasoning item (what Task 3's `ours` reads): `{"id": <item id>, "encrypted_content": <string>, "summary": [{"type": "summary_text", "text": <string>}, ...]}`, written by the configured `JsonMapper`, tagged with the provider's `vendor()`.

- [ ] **Step 1: Write the test fixture `ResponseStreams`**

Events are built as JSON and parsed by the SDK's own mapper, which is how the stream reaches the adapter in production (measured while planning: every event kind used here round-trips).

```java
package org.jwcarman.nessy.inference.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.openai.client.OpenAIClient;
import com.openai.core.ObjectMappers;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.services.blocking.ResponseService;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Responses server in a box: finished responses written as the JSON the API sends, cut into the
 * events it would have streamed them as, behind a fake {@link OpenAIClient}.
 *
 * <p>The client is a JDK dynamic proxy -- not a mocking library, just {@link
 * Proxy#newProxyInstance} -- answering {@code responses().createStreaming(...)} and throwing
 * {@link UnsupportedOperationException} for everything else. A proxy intercepts every interface
 * method itself, default ones included, so it matches on the method name alone.
 */
final class ResponseStreams {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ResponseStreams() {}

  static OpenAIClient client(Function<ResponseCreateParams, List<ResponseStreamEvent>> answer) {
    ResponseService responses =
        (ResponseService)
            Proxy.newProxyInstance(
                ResponseService.class.getClassLoader(),
                new Class<?>[] {ResponseService.class},
                (proxy, method, args) -> {
                  if ("createStreaming".equals(method.getName())) {
                    List<ResponseStreamEvent> events = answer.apply((ResponseCreateParams) args[0]);
                    return new StreamResponse<ResponseStreamEvent>() {
                      @Override
                      public Stream<ResponseStreamEvent> stream() {
                        return events.stream();
                      }

                      @Override
                      public void close() {
                        // Nothing held open.
                      }
                    };
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    return (OpenAIClient)
        Proxy.newProxyInstance(
            OpenAIClient.class.getClassLoader(),
            new Class<?>[] {OpenAIClient.class},
            (proxy, method, args) -> {
              if ("responses".equals(method.getName())) {
                return responses;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  /** A client that streams {@code response} cut into events, whatever it is asked. */
  static OpenAIClient replying(Map<String, Object> response) {
    return client(params -> eventsOf(response));
  }

  // ---- JSON, as the API writes it ----------------------------------------------------------

  static Map<String, Object> fields(Object... pairs) {
    Map<String, Object> fields = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      fields.put((String) pairs[i], pairs[i + 1]);
    }
    return fields;
  }

  static Map<String, Object> message(String id, String text) {
    return fields(
        "type", "message", "id", id, "role", "assistant", "status", "completed",
        "content", List.of(fields("type", "output_text", "text", text, "annotations", List.of())));
  }

  static Map<String, Object> refusal(String text) {
    return fields(
        "type", "message", "id", "msg_r", "role", "assistant", "status", "completed",
        "content", List.of(fields("type", "refusal", "refusal", text)));
  }

  static Map<String, Object> functionCall(String callId, String name, String arguments) {
    return fields(
        "type", "function_call", "id", "fc_" + callId, "call_id", callId,
        "name", name, "arguments", arguments, "status", "completed");
  }

  static Map<String, Object> reasoning(String id, String encrypted, String summary) {
    Map<String, Object> item =
        fields(
            "type", "reasoning", "id", id,
            "summary", List.of(fields("type", "summary_text", "text", summary)));
    if (encrypted != null) {
      item.put("encrypted_content", encrypted);
    }
    return item;
  }

  static Map<String, Object> webSearchCall() {
    return fields(
        "type", "web_search_call", "id", "ws_1", "status", "completed",
        "action", fields("type", "search", "query", "loch ness"));
  }

  static Map<String, Object> usage(long input, long output) {
    return fields("input_tokens", input, "output_tokens", output, "total_tokens", input + output);
  }

  /** A finished response; {@code usage} may be null, and then the key is absent. */
  static Map<String, Object> response(
      String status, List<Map<String, Object>> output, Map<String, Object> usage) {
    Map<String, Object> response =
        fields(
            "id", "resp_1", "object", "response", "created_at", 0, "model", "gpt-4o",
            "status", status, "output", output, "parallel_tool_calls", true,
            "tool_choice", "auto", "tools", List.of(), "error", null,
            "incomplete_details", null, "instructions", null, "metadata", Map.of(),
            "temperature", 1, "top_p", 1);
    if (usage != null) {
      response.put("usage", usage);
    }
    return response;
  }

  static Map<String, Object> completed(List<Map<String, Object>> output) {
    return response("completed", output, null);
  }

  // ---- the stream ------------------------------------------------------------------------

  /**
   * {@code response} cut into what a server streams: {@code response.created}, then per output
   * item an {@code output_item.added} and its text (or arguments) five characters at a time, and
   * last the terminal event named for the status, carrying the whole response.
   */
  static List<ResponseStreamEvent> eventsOf(Map<String, Object> response) {
    List<ResponseStreamEvent> events = new ArrayList<>();
    int seq = 0;
    Map<String, Object> started = new LinkedHashMap<>(response);
    started.put("status", "in_progress");
    started.put("output", List.of());
    events.add(event(fields("type", "response.created", "sequence_number", seq++, "response", started)));
    List<?> output = (List<?>) response.get("output");
    for (int index = 0; index < output.size(); index++) {
      Map<?, ?> item = (Map<?, ?>) output.get(index);
      events.add(
          event(
              fields(
                  "type", "response.output_item.added", "sequence_number", seq++,
                  "output_index", index, "item", item)));
      if ("message".equals(item.get("type"))) {
        for (Object part : (List<?>) item.get("content")) {
          Map<?, ?> content = (Map<?, ?>) part;
          if ("output_text".equals(content.get("type"))) {
            for (String piece : pieces((String) content.get("text"))) {
              events.add(textDelta(seq++, String.valueOf(item.get("id")), index, piece));
            }
          }
        }
      } else if ("function_call".equals(item.get("type"))) {
        for (String piece : pieces((String) item.get("arguments"))) {
          events.add(
              event(
                  fields(
                      "type", "response.function_call_arguments.delta", "sequence_number", seq++,
                      "item_id", item.get("id"), "output_index", index, "delta", piece)));
        }
      }
    }
    events.add(
        event(
            fields(
                "type", "response." + response.get("status"), "sequence_number", seq,
                "response", response)));
    return events;
  }

  static ResponseStreamEvent textDelta(int seq, String itemId, int index, String delta) {
    return event(
        fields(
            "type", "response.output_text.delta", "sequence_number", seq, "item_id", itemId,
            "output_index", index, "content_index", 0, "delta", delta, "logprobs", List.of()));
  }

  /** Any event, from the JSON a server would send. */
  static ResponseStreamEvent event(Map<String, Object> fields) {
    try {
      return ObjectMappers.jsonMapper()
          .readValue(JSON.writeValueAsString(fields), ResponseStreamEvent.class);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Five characters at a time, so a stream of them is several events. */
  static List<String> pieces(String text) {
    List<String> pieces = new ArrayList<>();
    for (int i = 0; i < text.length(); i += 5) {
      pieces.add(text.substring(i, Math.min(text.length(), i + 5)));
    }
    return pieces;
  }
}
```

The casts are to wildcard types (`List<?>`, `Map<?, ?>`) or to `String`, none unchecked.

- [ ] **Step 2: Write the failing provider tests**

`OpenAiResponsesInferenceProviderTest.java`:

```java
package org.jwcarman.nessy.inference.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.completed;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.fields;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.functionCall;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.message;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.reasoning;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.refusal;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.replying;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.response;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.usage;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.webSearchCall;

import com.openai.client.OpenAIClient;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnexpectedStatusCodeException;
import com.openai.errors.UnprocessableEntityException;
import com.openai.models.responses.ResponseCreateParams;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.Toolset;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class OpenAiResponsesInferenceProviderTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  static final InferenceRequest REQUEST =
      new InferenceRequest(
          new SystemPrompt("you are a helpful assistant"),
          InferenceContext.of(
              List.of(
                  new Turn(
                      new TurnId(1),
                      new Input(new Seq(1), List.of(new Block.Text("hello"))),
                      List.of(),
                      null,
                      0))),
          Toolset.none(),
          InferenceOptions.of("gpt-4o"));

  static OpenAiResponsesInferenceProvider provider(OpenAIClient client) {
    return new OpenAiResponsesProviderConfig().client(client).build();
  }

  private static InferenceResult inferReplying(Map<String, Object> response) {
    return provider(replying(response)).infer(REQUEST);
  }

  private static Failure inferFailing(RuntimeException failure) {
    InferenceResult result =
        provider(
                ResponseStreams.client(
                    params -> {
                      throw failure;
                    }))
            .infer(REQUEST);
    assertThat(result).isInstanceOf(InferenceResult.Fault.class);
    return ((InferenceResult.Fault) result).failure();
  }

  private static Headers emptyHeaders() {
    return Headers.builder().build();
  }

  private static Map<String, Object> parse(String json) {
    return MAPPER.readValue(json, new TypeReference<>() {});
  }

  @Nested
  class WhatItCost {

    @Test
    void the_usage_on_the_completed_event_is_the_results() {
      InferenceResult result =
          inferReplying(response("completed", List.of(message("msg_1", "a lake monster")), usage(3, 5)));

      assertThat(result.usage()).isEqualTo(Usage.of("gpt-4o", 3, 5));
      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
    }

    @Test
    void a_server_that_does_not_count_leaves_the_cost_unknown() {
      assertThat(inferReplying(completed(List.of(message("msg_1", "ok")))).usage())
          .isEqualTo(Usage.unreported("gpt-4o"));
    }

    @Test
    void the_detail_counts_are_the_cache_and_reasoning_breakdown() {
      Map<String, Object> counted = usage(30, 50);
      counted.put("input_tokens_details", fields("cached_tokens", 10));
      counted.put("output_tokens_details", fields("reasoning_tokens", 20));

      InferenceResult result =
          inferReplying(response("completed", List.of(message("msg_1", "ok")), counted));

      assertThat(result.usage()).isEqualTo(new Usage("gpt-4o", 30, 50, 10, null, 20));
    }

    /** §5i: the plain accessors would throw here; asKnown() reads "not said" as null. */
    @Test
    void a_server_that_omits_the_detail_objects_reports_input_and_output_and_no_cache_counts() {
      InferenceResult result =
          inferReplying(response("completed", List.of(message("msg_1", "ok")), usage(3, 5)));

      assertThat(result.usage().cacheReadTokens().counted()).isFalse();
      assertThat(result.usage().reasoningTokens().counted()).isFalse();
      assertThat(result.usage()).isEqualTo(Usage.of("gpt-4o", 3, 5));
    }

    /** Review Focus 4. */
    @Test
    void a_server_that_omits_the_input_count_still_answers() {
      InferenceResult result =
          inferReplying(
              response("completed", List.of(message("msg_1", "ok")), fields("output_tokens", 5)));

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
      assertThat(result.usage()).isEqualTo(new Usage("gpt-4o", null, 5, null, null, null));
    }
  }

  @Nested
  class WhatComesBack {

    /** A reasoning model that spent its whole budget thinking says so, as finish_reason=length does on the chat wire. */
    @Test
    void an_empty_answer_is_a_fault_that_names_the_status_and_the_reason() {
      Map<String, Object> spent = response("incomplete", List.of(), null);
      spent.put("incomplete_details", fields("reason", "max_output_tokens"));

      InferenceResult result = inferReplying(spent);

      assertThat(result)
          .isInstanceOfSatisfying(
              InferenceResult.Fault.class,
              fault -> {
                assertThat(fault.failure()).isInstanceOf(Failure.Permanent.class);
                assertThat(fault.failure().reason())
                    .contains("empty")
                    .contains("incomplete")
                    .contains("max_output_tokens");
              });
    }

    @Test
    void plain_prose_is_an_answer() {
      assertThat(inferReplying(completed(List.of(message("msg_1", "1412 metres")))))
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Answer(List.of(new Block.Text("1412 metres"))));
    }

    @Test
    void calls_are_a_request_for_actions_carrying_the_arguments_the_model_wrote() {
      InferenceResult result =
          inferReplying(completed(List.of(functionCall("call_1", "lookup", "{\"q\":\"loch ness\"}"))));

      assertThat(result)
          .isEqualTo(
              new InferenceResult.Actions(
                      List.of(
                          new Block.ToolCall(
                              new CallId("call_1"), new ToolName("lookup"), "{\"q\":\"loch ness\"}")))
                  .withUsage(Usage.unreported("gpt-4o")));
    }

    @Test
    void prose_beside_calls_is_kept_as_commentary() {
      InferenceResult result =
          inferReplying(
              completed(
                  List.of(message("msg_1", "Let me look."), functionCall("call_1", "lookup", "{}"))));

      assertThat(((InferenceResult.Actions) result).blocks().getFirst())
          .isEqualTo(new Block.Commentary("Let me look."));
    }

    @Test
    void whitespace_beside_calls_is_not_kept_as_commentary() {
      InferenceResult result =
          inferReplying(
              completed(List.of(message("msg_1", "\n\n"), functionCall("call_1", "lookup", "{}"))));

      assertThat(((InferenceResult.Actions) result).blocks())
          .isNotEmpty()
          .noneMatch(Block.Commentary.class::isInstance);
    }

    @Test
    void a_refusal_is_a_refusal_rather_than_an_answer_that_happens_to_say_no() {
      assertThat(inferReplying(completed(List.of(refusal("I cannot help with that")))))
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Refusal("I cannot help with that"));
    }

    @Test
    void a_failed_response_for_a_rate_limit_is_worth_another_attempt() {
      Map<String, Object> failed = response("failed", List.of(), null);
      failed.put("error", fields("code", "rate_limit_exceeded", "message", "slow down"));

      InferenceResult result = inferReplying(failed);

      assertThat(result)
          .isInstanceOfSatisfying(
              InferenceResult.Fault.class,
              fault -> {
                assertThat(fault.failure()).isInstanceOf(Failure.Transient.class);
                assertThat(fault.failure().reason()).contains("slow down");
              });
    }

    @Test
    void a_failed_response_for_a_bad_prompt_is_permanent() {
      Map<String, Object> failed = response("failed", List.of(), null);
      failed.put("error", fields("code", "invalid_prompt", "message", "no"));

      assertThat(((InferenceResult.Fault) inferReplying(failed)).failure())
          .isInstanceOf(Failure.Permanent.class);
    }

    /** §8: nothing here offered a hosted tool, so its output is dropped, as the chat wire drops custom calls. */
    @Test
    void a_hosted_tool_item_is_dropped_leaving_its_siblings_in_order() {
      InferenceResult result =
          inferReplying(
              completed(
                  List.of(
                      message("msg_1", "Looking."),
                      webSearchCall(),
                      functionCall("call_1", "lookup", "{}"))));

      assertThat(((InferenceResult.Actions) result).blocks())
          .containsExactly(
              new Block.Commentary("Looking."),
              new Block.ToolCall(new CallId("call_1"), new ToolName("lookup"), "{}"));
    }

    @Test
    void a_stream_with_no_terminal_event_is_a_fault_that_says_it_ended_early() {
      InferenceResult result = provider(ResponseStreams.client(params -> List.of())).infer(REQUEST);

      assertThat(result)
          .isInstanceOfSatisfying(
              InferenceResult.Fault.class,
              fault -> {
                assertThat(fault.failure()).isInstanceOf(Failure.Permanent.class);
                assertThat(fault.failure().reason()).contains("ended before");
              });
    }

    @Test
    void the_model_asked_for_is_the_one_in_the_options() {
      ResponseCreateParams[] captured = new ResponseCreateParams[1];
      provider(
              ResponseStreams.client(
                  params -> {
                    captured[0] = params;
                    return ResponseStreams.eventsOf(completed(List.of(message("msg_1", "ok"))));
                  }))
          .infer(REQUEST);

      assertThat(captured[0].model().orElseThrow().asString()).isEqualTo("gpt-4o");
    }
  }

  @Nested
  class ReasoningItems {

    @Test
    void one_beside_calls_is_kept_as_a_provider_block_in_its_arrival_position() {
      InferenceResult result =
          inferReplying(
              completed(
                  List.of(
                      reasoning("rs_1", "AAAA", "weighing it"),
                      functionCall("call_1", "lookup", "{}"))));

      List<Block.ActionRequestContent> blocks = ((InferenceResult.Actions) result).blocks();
      assertThat(blocks).hasSize(2);
      assertThat(blocks.get(0))
          .isInstanceOfSatisfying(
              Block.Provider.class,
              provider -> {
                assertThat(provider.vendor()).isEqualTo("openai");
                assertThat(parse(provider.payload()))
                    .isEqualTo(
                        parse(
                            "{\"id\":\"rs_1\",\"encrypted_content\":\"AAAA\",\"summary\":"
                                + "[{\"type\":\"summary_text\",\"text\":\"weighing it\"}]}"));
              });
      assertThat(blocks.get(1)).isInstanceOf(Block.ToolCall.class);
    }

    @Test
    void one_beside_a_final_answer_is_stored_beside_the_answer_s_text() {
      InferenceResult result =
          inferReplying(
              completed(List.of(reasoning("rs_1", "AAAA", "weighing it"), message("msg_1", "Paris"))));

      List<Block.AnswerContent> blocks = ((InferenceResult.Answer) result).blocks();
      assertThat(blocks).hasSize(2);
      assertThat(blocks.get(0)).isInstanceOf(Block.Provider.class);
      assertThat(blocks.get(1)).isEqualTo(new Block.Text("Paris"));
    }

    /** Review Focus 5: nothing encrypted is nothing to hand back. */
    @Test
    void a_reasoning_item_with_nothing_encrypted_is_not_kept() {
      InferenceResult result =
          inferReplying(completed(List.of(reasoning("rs_1", null, "hmm"), message("msg_1", "Paris"))));

      assertThat(result)
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Answer(List.of(new Block.Text("Paris"))));
    }

    @Test
    void a_provider_answering_for_another_vendor_tags_its_items_with_that_vendor() {
      InferenceResult result =
          new OpenAiResponsesProviderConfig()
              .client(
                  replying(
                      completed(
                          List.of(
                              reasoning("rs_1", "AAAA", "hm"),
                              functionCall("call_1", "lookup", "{}")))))
              .vendor("x_ai")
              .build()
              .infer(REQUEST);

      assertThat(((InferenceResult.Actions) result).blocks().getFirst())
          .isInstanceOfSatisfying(
              Block.Provider.class, provider -> assertThat(provider.vendor()).isEqualTo("x_ai"));
    }
  }

  @Nested
  class Identity {

    @Test
    void reports_openai_as_its_name_and_its_configured_vendor() {
      OpenAiResponsesInferenceProvider provider =
          new OpenAiResponsesProviderConfig().apiKey("sk-test").vendor("perplexity").build();

      assertThat(provider.name()).isEqualTo("OpenAI");
      assertThat(provider.vendor()).isEqualTo("perplexity");
      provider.close();
    }

    @Test
    void the_default_vendor_is_openai() {
      OpenAiResponsesInferenceProvider provider = OpenAiResponsesInferenceProvider.of(c -> c.apiKey("sk-test"));

      assertThat(provider.vendor()).isEqualTo("openai");
      provider.close();
    }
  }

  /** The chat adapter's classification, shared through OpenAiFailures, reached through this adapter's infer. */
  @Nested
  class WhatAFailureMeans {

    @Test
    void a_rate_limit_is_worth_another_attempt() {
      assertThat(inferFailing(RateLimitException.builder().headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Transient.class);
    }

    @Test
    void so_is_an_internal_server_error() {
      assertThat(
              inferFailing(
                  InternalServerException.builder().statusCode(500).headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Transient.class);
    }

    @Test
    void and_a_gateway_error_reported_as_a_5xx() {
      assertThat(
              inferFailing(
                  InternalServerException.builder().statusCode(503).headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Transient.class);
    }

    @Test
    void and_the_sdk_s_own_transient_marker() {
      assertThat(inferFailing(new OpenAIRetryableException("transient failure")))
          .isInstanceOf(Failure.Transient.class);
    }

    @Test
    void a_transport_failure_is_unknown_rather_than_transient() {
      assertThat(inferFailing(new OpenAIIoException("connection reset")))
          .isInstanceOf(Failure.Unknown.class);
    }

    @Test
    void a_bad_request_is_permanent() {
      assertThat(inferFailing(BadRequestException.builder().headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void so_is_an_unauthorized_call() {
      assertThat(inferFailing(UnauthorizedException.builder().headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_a_permission_denied() {
      assertThat(inferFailing(PermissionDeniedException.builder().headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_a_not_found() {
      assertThat(inferFailing(NotFoundException.builder().headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_an_unprocessable_entity() {
      assertThat(inferFailing(UnprocessableEntityException.builder().headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_a_status_code_the_sdk_does_not_recognise() {
      assertThat(
              inferFailing(
                  UnexpectedStatusCodeException.builder()
                      .statusCode(409)
                      .headers(emptyHeaders())
                      .build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_a_response_that_would_not_decode() {
      assertThat(inferFailing(new OpenAIInvalidDataException("unrecognized enum value")))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_never_a_rejection_of_the_content_itself() {
      assertThat(inferFailing(BadRequestException.builder().headers(emptyHeaders()).build()))
          .isNotInstanceOf(Failure.Rejected.class);
    }

    @Test
    void a_bug_in_the_adapter_is_not_dressed_up_as_the_model_failing() {
      IllegalStateException bug = new IllegalStateException("a bug in here");
      assertThatThrownBy(() -> inferFailing(bug))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("a bug in here");
    }
  }
}
```

`OpenAiResponsesProviderConfigTest.java` -- the six construction tests of `OpenAiChatProviderConfigTest`, on this config:

```java
package org.jwcarman.nessy.inference.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.client.OpenAIClient;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Customizer;

/** Building a Responses provider needs no network: the SDK client is constructed, never used. */
@DisplayName("The OpenAI Responses provider config")
class OpenAiResponsesProviderConfigTest {

  @Test
  void a_null_timeout_is_rejected() {
    Customizer<OpenAiResponsesProviderConfig> customizer = c -> c.apiKey("test-key").timeout(null);
    assertThatThrownBy(() -> OpenAiResponsesInferenceProvider.of(customizer))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void a_zero_timeout_is_rejected() {
    Customizer<OpenAiResponsesProviderConfig> customizer =
        c -> c.apiKey("test-key").timeout(Duration.ZERO);
    assertThatThrownBy(() -> OpenAiResponsesInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void neither_a_key_nor_a_client_is_rejected_at_build() {
    OpenAiResponsesProviderConfig config = new OpenAiResponsesProviderConfig();
    assertThatThrownBy(config::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("API key");
  }

  @Test
  void a_positive_timeout_builds_a_provider_that_closes_its_own_client() {
    OpenAiResponsesInferenceProvider provider =
        OpenAiResponsesInferenceProvider.of(c -> c.apiKey("test-key").timeout(Duration.ofMinutes(6)));

    assertThat(provider.name()).isEqualTo("OpenAI");
    assertThatCode(provider::close).doesNotThrowAnyException();
  }

  @Test
  void a_timeout_applies_on_the_from_env_build_path_too() {
    Customizer<OpenAiResponsesProviderConfig> customizer =
        c -> c.fromEnv().apiKey("explicit").timeout(Duration.ofMinutes(6));
    assertThatCode(() -> OpenAiResponsesInferenceProvider.of(customizer).close())
        .doesNotThrowAnyException();
  }

  @Test
  void a_supplied_client_is_never_closed_by_the_provider() {
    AtomicInteger closes = new AtomicInteger();
    OpenAIClient supplied = recordingClient(closes);

    OpenAiResponsesInferenceProvider provider =
        OpenAiResponsesInferenceProvider.of(c -> c.client(supplied).timeout(Duration.ofMinutes(6)));
    provider.close();

    assertThat(closes).hasValue(0);
  }

  private static OpenAIClient recordingClient(AtomicInteger closes) {
    return (OpenAIClient)
        Proxy.newProxyInstance(
            OpenAIClient.class.getClassLoader(),
            new Class<?>[] {OpenAIClient.class},
            (proxy, method, args) -> {
              if ("close".equals(method.getName())) {
                closes.incrementAndGet();
                return null;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q -pl :nessy-inference-openai -am test -Dtest='OpenAiResponses*Test' -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: compilation failure, `cannot find symbol: class OpenAiResponsesProviderConfig`.

- [ ] **Step 4: Share `NAME` and add the terminal-error classification**

`OpenAiChatInferenceProvider`: `private static final String NAME = "OpenAI";` → `static final String NAME = "OpenAI";`.

`OpenAiFailures`, add (with imports `com.openai.models.responses.ResponseError`):

```java
  /**
   * A {@code response.failed} terminal event: an error inside a 200 stream rather than a thrown
   * exception, classified by the same rule on its code -- worth another attempt only when the code
   * names a rate limit or a server-side failure, and never {@link Failure.Rejected}.
   */
  static Failure classify(ResponseError error) {
    String code = error._code().asKnown().map(ResponseError.Code::asString).orElse("unknown");
    String reason = "model call failed: " + code + ": " + error._message().asKnown().orElse("");
    if (ResponseError.Code.RATE_LIMIT_EXCEEDED.asString().equals(code)
        || ResponseError.Code.SERVER_ERROR.asString().equals(code)) {
      return new Failure.Transient(reason);
    }
    return new Failure.Permanent(reason);
  }
```

- [ ] **Step 5: Write `OpenAiResponsesProviderConfig`**

```java
package org.jwcarman.nessy.inference.openai;

import com.openai.client.OpenAIClient;
import java.time.Duration;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link OpenAiResponsesInferenceProvider#of(org.jwcarman.nessy.api.Customizer)} hands a
 * customizer: a config, not a builder -- fluent setters, no public {@code build()}. The same
 * setters as {@link OpenAiChatProviderConfig}, because every one of them is about the client and
 * the vendor tag, not the wire; their full contracts are documented there.
 */
public final class OpenAiResponsesProviderConfig {

  private String apiKey;
  private String baseUrl;
  private String organization;
  private OpenAIClient client;
  private boolean useEnv;
  private String vendor = OpenAiChatInferenceProvider.VENDOR;
  private Duration timeout;
  private JsonMapper mapper = JsonMapper.builder().build();

  OpenAiResponsesProviderConfig() {}

  public OpenAiResponsesProviderConfig apiKey(String apiKey) {
    this.apiKey = apiKey;
    return this;
  }

  /** Reads the SDK's own environment table at build time; anything set explicitly here wins. */
  public OpenAiResponsesProviderConfig fromEnv() {
    this.useEnv = true;
    return this;
  }

  /** Any server that speaks the Responses API, with the {@code /v1} suffix. */
  public OpenAiResponsesProviderConfig baseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
    return this;
  }

  public OpenAiResponsesProviderConfig organization(String organization) {
    this.organization = organization;
    return this;
  }

  /** A preconfigured client, which stays the caller's: the provider never closes it. */
  public OpenAiResponsesProviderConfig client(OpenAIClient client) {
    this.client = client;
    return this;
  }

  public OpenAiResponsesProviderConfig mapper(JsonMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    return this;
  }

  /**
   * The bound on the SDK's HTTP request; ignored for a supplied client.
   *
   * @throws IllegalArgumentException if {@code timeout} is zero or negative
   */
  public OpenAiResponsesProviderConfig timeout(Duration timeout) {
    this.timeout = OpenAiClients.requirePositive(timeout);
    return this;
  }

  /** The vendor this provider reports ({@code gen_ai.provider.name}) and tags its reasoning items with. */
  public OpenAiResponsesProviderConfig vendor(String vendor) {
    this.vendor = Objects.requireNonNull(vendor, "vendor must not be null");
    return this;
  }

  OpenAiResponsesInferenceProvider build() {
    if (client != null) {
      return new OpenAiResponsesInferenceProvider(client, vendor, false, mapper);
    }
    return new OpenAiResponsesInferenceProvider(
        OpenAiClients.build(useEnv, apiKey, baseUrl, organization, timeout), vendor, true, mapper);
  }
}
```

- [ ] **Step 6: Write `OpenAiResponsesInferenceProvider`**

```java
package org.jwcarman.nessy.inference.openai;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonField;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIException;
import com.openai.helpers.ResponseAccumulator;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputRefusal;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseStreamEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI's Responses API, through the vendor's own SDK -- the {@code openai-responses} wire under
 * Boot.
 *
 * <p><b>Stateless.</b> Every call sends the whole context with {@code store: false}; nothing here
 * can name a previous response or a conversation, so Nessy's event log stays the only story there
 * is (the projection is {@link OpenAiResponsesRequests}).
 *
 * <p><b>Reasoning items are kept whole.</b> Every encrypted reasoning item that comes back --
 * beside calls or beside the answer -- is stored as a {@link Block.Provider} tagged with {@link
 * #vendor()}, in its arrival position. Which ones travel back is the projection's rule, not this
 * class's.
 *
 * <p><b>The SDK's accumulator folds nothing.</b> It keeps the whole {@code Response} the terminal
 * event carries ({@code completed}, {@code failed} or {@code incomplete}) and ignores everything
 * else, trailing events and unknown types included; the answer is read from that {@code Response}
 * in {@link #read}, and the deltas exist for narration.
 *
 * <p>Holds no model name: the model travels in {@link InferenceOptions}.
 */
public final class OpenAiResponsesInferenceProvider implements InferenceProvider, AutoCloseable {

  private final OpenAIClient client;
  private final String vendor;
  private final JsonMapper mapper;
  private final boolean ownsClient;

  OpenAiResponsesInferenceProvider(
      OpenAIClient client, String vendor, boolean ownsClient, JsonMapper mapper) {
    this.client = client;
    this.vendor = Objects.requireNonNull(vendor, "vendor must not be null");
    this.ownsClient = ownsClient;
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  /** Equivalent to {@code of(OpenAiResponsesProviderConfig::fromEnv)}. */
  public static OpenAiResponsesInferenceProvider fromEnv() {
    return of(OpenAiResponsesProviderConfig::fromEnv);
  }

  public static OpenAiResponsesInferenceProvider of(
      List<Customizer<OpenAiResponsesProviderConfig>> customizers) {
    Objects.requireNonNull(customizers, "customizers must not be null");
    OpenAiResponsesProviderConfig config = new OpenAiResponsesProviderConfig();
    customizers.forEach(customizer -> customizer.customize(config));
    return config.build();
  }

  public static OpenAiResponsesInferenceProvider of(
      Customizer<OpenAiResponsesProviderConfig> customizer) {
    return of(List.of(Objects.requireNonNull(customizer, "customizer must not be null")));
  }

  /**
   * Total for anything the provider can do to us, and narrow for everything else: {@link
   * OpenAIException} is the root of what the SDK throws; anything outside it is a bug here and
   * escapes rather than being recorded as the model's fault.
   */
  @Override
  public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
    Objects.requireNonNull(narrator, "narrator must not be null");
    try (StreamResponse<ResponseStreamEvent> stream =
        client.responses().createStreaming(OpenAiResponsesRequests.toParams(request, vendor, mapper))) {
      ResponseAccumulator accumulator = ResponseAccumulator.create();
      stream.stream().forEach(accumulator::accumulate);
      return read(accumulator, request.options().modelName());
    } catch (OpenAIException e) {
      return new InferenceResult.Fault(OpenAiFailures.classify(e));
    }
  }

  private InferenceResult read(ResponseAccumulator accumulator, String asked) {
    Response response;
    try {
      response = accumulator.response();
    } catch (IllegalStateException incomplete) {
      return new InferenceResult.Fault(
          new Failure.Permanent(
              "the stream ended before the answer was complete: " + incomplete.getMessage()));
    }
    Usage usage = usageOf(response, modelOf(response, asked));
    if (response.error().isPresent()) {
      return new InferenceResult.Fault(OpenAiFailures.classify(response.error().get()))
          .withUsage(usage);
    }
    return read(response).withUsage(usage);
  }

  /**
   * The output items, walked in order. A refusal is checked first, because this wire reports it in
   * a part of its own. Each message's text is commentary beside calls or the answer without them;
   * each function call is a call; each encrypted reasoning item is kept where it arrived; every
   * other kind -- web search, file search, code interpreter, MCP and the rest -- is dropped,
   * because nothing here offered it.
   */
  private InferenceResult read(Response response) {
    Optional<String> refusal =
        response.output().stream()
            .filter(ResponseOutputItem::isMessage)
            .flatMap(item -> item.asMessage().content().stream())
            .flatMap(content -> content.refusal().stream())
            .map(ResponseOutputRefusal::refusal)
            .findFirst();
    if (refusal.isPresent()) {
      return new InferenceResult.Refusal(refusal.get());
    }
    List<Block.ActionRequestContent> inOrder = new ArrayList<>();
    List<Block.AnswerContent> reasoning = new ArrayList<>();
    StringBuilder said = new StringBuilder();
    boolean called = false;
    for (ResponseOutputItem item : response.output()) {
      if (item.isMessage()) {
        String text = textOf(item.asMessage());
        said.append(text);
        if (!text.isBlank()) {
          inOrder.add(new Block.Commentary(text));
        }
      } else if (item.isFunctionCall()) {
        ResponseFunctionToolCall call = item.asFunctionCall();
        inOrder.add(new Block.ToolCall(call.callId(), call.name(), call.arguments()));
        called = true;
      } else if (item.isReasoning()) {
        Optional<Block.Provider> kept = kept(item.asReasoning());
        kept.ifPresent(inOrder::add);
        kept.ifPresent(reasoning::add);
      }
    }
    if (called) {
      return new InferenceResult.Actions(inOrder);
    }
    if (said.toString().isBlank()) {
      return new InferenceResult.Fault(
          new Failure.Permanent(
              "model returned an empty answer (status="
                  + response.status().map(ResponseStatus::asString).orElse("unknown")
                  + ", reason="
                  + response
                      .incompleteDetails()
                      .flatMap(Response.IncompleteDetails::reason)
                      .map(Response.IncompleteDetails.Reason::asString)
                      .orElse("none")
                  + ")"));
    }
    List<Block.AnswerContent> answer = new ArrayList<>(reasoning);
    answer.add(new Block.Text(said.toString()));
    return new InferenceResult.Answer(answer);
  }

  private static String textOf(ResponseOutputMessage message) {
    return message.content().stream()
        .flatMap(content -> content.outputText().stream())
        .map(ResponseOutputText::text)
        .collect(Collectors.joining());
  }

  /**
   * An encrypted reasoning item as a {@code Provider} block: its own fields as one JSON object,
   * written by the configured mapper. Safe to re-serialise because the signed bytes are the {@code
   * encrypted_content} string itself. An item with nothing encrypted has nothing to hand back.
   */
  private Optional<Block.Provider> kept(ResponseReasoningItem item) {
    return item.encryptedContent()
        .map(
            encrypted -> {
              Map<String, Object> payload = new LinkedHashMap<>();
              payload.put("id", item.id());
              payload.put("encrypted_content", encrypted);
              payload.put(
                  "summary",
                  item.summary().stream()
                      .map(
                          part -> {
                            Map<String, Object> summary = new LinkedHashMap<>();
                            summary.put("type", "summary_text");
                            summary.put("text", part.text());
                            return summary;
                          })
                      .toList());
              return new Block.Provider(vendor, mapper.writeValueAsString(payload));
            });
  }

  /**
   * The model that answered, across the SDK's three model arms (a name the SDK knows deserialises
   * into the chat arm, not the string one), or the one asked for when the server said none.
   */
  private static String modelOf(Response response, String asked) {
    return response
        ._model()
        .asKnown()
        .map(OpenAiResponsesInferenceProvider::nameOf)
        .filter(name -> !name.isBlank())
        .orElse(asked);
  }

  private static String nameOf(ResponsesModel model) {
    if (model.isString()) {
      return model.asString();
    }
    if (model.isChat()) {
      return model.asChat().asString();
    }
    return model.asOnly().asString();
  }

  /**
   * What the call cost. {@code input_tokens} already includes cached input, so nothing is summed.
   * Every count is read through {@code asKnown()}: the SDK marks the detail objects required and
   * throws on a server that omits them, and a server that says nothing about caching has not said
   * zero.
   */
  private static Usage usageOf(Response response, String model) {
    return response
        .usage()
        .map(
            counted ->
                new Usage(
                    model,
                    count(counted._inputTokens()),
                    count(counted._outputTokens()),
                    counted
                        ._inputTokensDetails()
                        .asKnown()
                        .map(details -> count(details._cachedTokens()))
                        .orElse(null),
                    counted
                        ._inputTokensDetails()
                        .asKnown()
                        .map(details -> count(details._cacheWriteTokens()))
                        .orElse(null),
                    counted
                        ._outputTokensDetails()
                        .asKnown()
                        .map(details -> count(details._reasoningTokens()))
                        .orElse(null)))
        .orElseGet(() -> Usage.unreported(model));
  }

  private static Integer count(JsonField<Long> field) {
    return field.asKnown().map(Long::intValue).orElse(null);
  }

  /** Closes the client this provider built; a supplied one stays the caller's. Idempotent. */
  @Override
  public void close() {
    if (ownsClient) {
      client.close();
    }
  }

  /** This vendor, by name. */
  public String name() {
    return OpenAiChatInferenceProvider.NAME;
  }

  @Override
  public String vendor() {
    return vendor;
  }
}
```

(`Optional.map(details -> count(...))` returning null yields an empty `Optional`, so `.orElse(null)` is right in all three.)

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-inference-openai -am test; echo exit=$?`
Expected: `exit=0`, the whole module green (chat tests included).

- [ ] **Step 8: Full gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?` -- expected `exit=0`.

```bash
git add nessy-inference/openai
git commit -m "feat: OpenAiResponsesInferenceProvider -- reads the terminal response, keeps reasoning items

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 5: Narration, and tolerance of deviating servers

Spec §5g, §5h (the mid-stream `error` event), §6. Suggested implementer: Sonnet.

**Files:**
- Modify: `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiResponsesInferenceProvider.java` (`infer`, `read(ResponseAccumulator, ...)`, new `narrate`)
- Test: `nessy-inference/openai/src/test/java/org/jwcarman/nessy/inference/openai/OpenAiResponsesStreamingTest.java`

**Interfaces:**
- Consumes: `ResponseStreams.client/eventsOf/event/textDelta/fields/completed/message/functionCall` (Task 4); `OpenAiResponsesProviderConfig` (Task 4); `Narration` (existing test class).
- Produces: nothing new on any surface.

- [ ] **Step 1: Write the failing tests**

```java
package org.jwcarman.nessy.inference.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.completed;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.event;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.eventsOf;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.fields;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.functionCall;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.message;
import static org.jwcarman.nessy.inference.openai.ResponseStreams.textDelta;

import com.openai.models.responses.ResponseStreamEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceResult;

/** What is narrated as a Responses stream arrives, and what a server that bends the protocol costs. */
class OpenAiResponsesStreamingTest {

  private final Narration narrated = new Narration();

  private InferenceResult inferNarrating(List<ResponseStreamEvent> events) {
    return OpenAiResponsesInferenceProviderTest.provider(ResponseStreams.client(params -> events))
        .infer(OpenAiResponsesInferenceProviderTest.REQUEST, narrated);
  }

  private static ResponseStreamEvent summaryDelta(int seq, String delta) {
    return event(
        fields(
            "type", "response.reasoning_summary_text.delta", "sequence_number", seq,
            "item_id", "rs_1", "output_index", 0, "summary_index", 0, "delta", delta));
  }

  private static ResponseStreamEvent reasoningTextDelta(int seq, String delta) {
    return event(
        fields(
            "type", "response.reasoning_text.delta", "sequence_number", seq,
            "item_id", "rs_1", "output_index", 0, "content_index", 0, "delta", delta));
  }

  @Nested
  class WhatIsNarrated {

    @Test
    void the_text_is_narrated_piece_by_piece_as_it_arrives_and_answered_whole() {
      InferenceResult result = inferNarrating(eventsOf(completed(List.of(message("msg_1", "a lake monster")))));

      assertThat(narrated.fragments())
          .extracting(Narration.Fragment::text)
          .containsExactly("a lak", "e mon", "ster");
      assertThat(result)
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Answer(List.of(new Block.Text("a lake monster"))));
    }

    @Test
    void a_reasoning_summary_is_narrated_as_thinking_before_the_text() {
      List<ResponseStreamEvent> events = new ArrayList<>();
      events.add(summaryDelta(1, "weighing it"));
      events.addAll(eventsOf(completed(List.of(message("msg_1", "ok")))));

      inferNarrating(events);

      assertThat(narrated.fragments())
          .containsExactly(Narration.Fragment.thinking("weighing it"), Narration.Fragment.text("ok"));
    }

    /** What a compatible Responses server may stream unasked, as compatible chat servers send reasoning_content. */
    @Test
    void raw_reasoning_text_is_narrated_as_thinking() {
      List<ResponseStreamEvent> events = new ArrayList<>();
      events.add(reasoningTextDelta(1, "hmm"));
      events.addAll(eventsOf(completed(List.of(message("msg_1", "ok")))));

      inferNarrating(events);

      assertThat(narrated.fragments())
          .containsExactly(Narration.Fragment.thinking("hmm"), Narration.Fragment.text("ok"));
    }

    @Test
    void empty_deltas_are_not_narrated() {
      List<ResponseStreamEvent> events = new ArrayList<>();
      events.add(textDelta(1, "msg_1", 0, ""));
      events.add(summaryDelta(2, ""));
      events.addAll(eventsOf(completed(List.of(message("msg_1", "ok")))));

      inferNarrating(events);

      assertThat(narrated.fragments()).containsExactly(Narration.Fragment.text("ok"));
    }

    /** Half a JSON argument is not something anybody can watch. */
    @Test
    void function_call_argument_fragments_are_not_narrated() {
      InferenceResult result =
          inferNarrating(
              eventsOf(completed(List.of(functionCall("call_1", "days_until", "{\"date\":\"2026-12-25\"}")))));

      assertThat(narrated.fragments()).isEmpty();
      assertThat(result).isInstanceOf(InferenceResult.Actions.class);
    }
  }

  @Nested
  class DeviatingServers {

    @Test
    void events_after_the_terminal_one_change_nothing_and_are_not_narrated() {
      List<ResponseStreamEvent> events =
          new ArrayList<>(eventsOf(completed(List.of(message("msg_1", "ok")))));
      events.add(textDelta(99, "msg_2", 1, "trailing"));
      events.add(event(fields("type", "response.rate_limits.updated", "sequence_number", 100)));

      InferenceResult result = inferNarrating(events);

      assertThat(narrated.fragments()).containsExactly(Narration.Fragment.text("ok"));
      assertThat(result)
          .usingRecursiveComparison()
          .ignoringFields("usage")
          .isEqualTo(new InferenceResult.Answer(List.of(new Block.Text("ok"))));
    }

    @Test
    void an_unknown_event_type_is_ignored() {
      List<ResponseStreamEvent> events = new ArrayList<>();
      events.add(event(fields("type", "response.something_new", "sequence_number", 1, "x", 1)));
      events.addAll(eventsOf(completed(List.of(message("msg_1", "ok")))));

      assertThat(inferNarrating(events)).isInstanceOf(InferenceResult.Answer.class);
    }

    @Test
    void a_mid_stream_error_with_no_terminal_event_is_a_fault_naming_the_error() {
      List<ResponseStreamEvent> events =
          List.of(
              textDelta(1, "msg_1", 0, "Par"),
              event(
                  fields(
                      "type", "error", "sequence_number", 2, "code", "server_error",
                      "message", "the model fell over", "param", null)));

      InferenceResult result = inferNarrating(events);

      assertThat(result)
          .isInstanceOfSatisfying(
              InferenceResult.Fault.class,
              fault -> {
                assertThat(fault.failure()).isInstanceOf(Failure.Permanent.class);
                assertThat(fault.failure().reason())
                    .contains("ended before")
                    .contains("the model fell over");
              });
    }
  }
}
```

(`OpenAiResponsesInferenceProviderTest.provider(...)` and `.REQUEST` are package-private statics from Task 4.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl :nessy-inference-openai -am test -Dtest=OpenAiResponsesStreamingTest -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: failures in `WhatIsNarrated` (nothing narrated), `events_after_the_terminal_one...` (passes or fails on narration), and `a_mid_stream_error...` (reason lacks "the model fell over").

- [ ] **Step 3: Implement narration and the remembered error**

In `OpenAiResponsesInferenceProvider`, replace the body of the `try` in `infer` and the first `read`:

```java
      ResponseAccumulator accumulator = ResponseAccumulator.create();
      boolean[] ended = {false};
      String[] error = {null};
      stream.stream()
          .forEach(
              event -> {
                accumulator.accumulate(event);
                if (!ended[0]) {
                  narrate(event, narrator);
                }
                event.error().ifPresent(e -> error[0] = e.message());
                ended[0] |= event.isCompleted() || event.isFailed() || event.isIncomplete();
              });
      return read(accumulator, error[0], request.options().modelName());
```

```java
  /**
   * The terminal event's response, read -- or the fault a stream that closed without one is. A
   * mid-stream {@code error} event, which the accumulator ignores, is remembered so the fault can
   * say what the server said rather than only that the stream ended.
   */
  private InferenceResult read(ResponseAccumulator accumulator, String error, String asked) {
    Response response;
    try {
      response = accumulator.response();
    } catch (IllegalStateException incomplete) {
      return new InferenceResult.Fault(
          new Failure.Permanent(
              "the stream ended before the answer was complete: "
                  + (error != null ? error : incomplete.getMessage())));
    }
    // ... the rest unchanged from Task 4
  }

  /**
   * What a person watching is told as the stream lands: the answer's text, and thinking -- a
   * reasoning summary, or raw reasoning text a compatible server streams unasked. Function-call
   * argument fragments and refusal deltas are not narrated; empty deltas are skipped.
   */
  private static void narrate(ResponseStreamEvent event, InferenceNarrator narrator) {
    event.outputTextDelta()
        .map(delta -> delta.delta())
        .filter(text -> !text.isEmpty())
        .ifPresent(narrator::text);
    event.reasoningSummaryTextDelta()
        .map(delta -> delta.delta())
        .filter(text -> !text.isEmpty())
        .ifPresent(narrator::thinking);
    event.reasoningTextDelta()
        .map(delta -> delta.delta())
        .filter(text -> !text.isEmpty())
        .ifPresent(narrator::thinking);
  }
```

Replace the `// ... the rest unchanged from Task 4` line with the remainder of Task 4's `read(ResponseAccumulator, String)` body (from `Usage usage = ...` to the end), unchanged.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-inference-openai -am test; echo exit=$?`
Expected: `exit=0`.

- [ ] **Step 5: Full gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?` -- expected `exit=0`.

```bash
git add nessy-inference/openai
git commit -m "feat: the Responses adapter narrates text and thinking, and tolerates a bent stream

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 6: Boot wiring -- the `openai-responses` wire builds the Responses adapter

Spec §7a, §7d, §9b. Suggested implementer: Haiku (the brief is complete code).

**Files:**
- Modify: `nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/inference/WireProviders.java`
- Create: `nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/inference/WireProvidersTest.java`
- Modify: `nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/inference/InferenceProvidersAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `OpenAiResponsesInferenceProvider.of(Customizer<OpenAiResponsesProviderConfig>)` and its setters `apiKey`, `baseUrl`, `vendor`, `timeout`, `mapper` (Task 4); `Wire.OPENAI_RESPONSES` (Task 1).
- Produces: `WireProviders` maps `OPENAI_RESPONSES` to `org.jwcarman.nessy.inference.openai.OpenAiResponsesInferenceProvider`.

- [ ] **Step 1: Write the failing tests**

`WireProvidersTest.java`:

```java
package org.jwcarman.nessy.spring.boot.inference;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.openai.OpenAiChatInferenceProvider;
import org.jwcarman.nessy.inference.openai.OpenAiResponsesInferenceProvider;

/** Which adapter class each OpenAI wire builds -- asked before the observation wrapper goes on. */
class WireProvidersTest {

  private static final ClassLoader LOADER = WireProvidersTest.class.getClassLoader();

  @Test
  void the_openai_responses_wire_builds_the_responses_adapter() {
    ResolvedProvider resolved =
        new ResolvedProvider("mine", Wire.OPENAI_RESPONSES, "https://g/v1", "openai", "k");

    Optional<InferenceProvider> built = WireProviders.build(resolved, null, LOADER);

    assertThat(built)
        .get()
        .isInstanceOfSatisfying(
            OpenAiResponsesInferenceProvider.class, OpenAiResponsesInferenceProvider::close);
  }

  @Test
  void the_openai_chat_wire_builds_the_chat_adapter() {
    ResolvedProvider resolved =
        new ResolvedProvider("openai", Wire.OPENAI_CHAT, null, "openai", "k");

    Optional<InferenceProvider> built = WireProviders.build(resolved, null, LOADER);

    assertThat(built)
        .get()
        .isInstanceOfSatisfying(
            OpenAiChatInferenceProvider.class, OpenAiChatInferenceProvider::close);
  }

  @Test
  void both_openai_wires_need_the_one_openai_module() {
    assertThat(WireProviders.artifactId(Wire.OPENAI_RESPONSES)).isEqualTo("nessy-inference-openai");
    assertThat(WireProviders.artifactId(Wire.OPENAI_CHAT)).isEqualTo("nessy-inference-openai");
  }

  @Test
  void the_responses_wire_s_vendor_is_the_resolved_one() {
    ResolvedProvider resolved =
        new ResolvedProvider("pplx", Wire.OPENAI_RESPONSES, "https://g/v1", "perplexity", "k");

    InferenceProvider built = WireProviders.build(resolved, null, LOADER).orElseThrow();

    assertThat(built.vendor()).isEqualTo("perplexity");
    ((OpenAiResponsesInferenceProvider) built).close();
  }
}
```

Add to `InferenceProvidersAutoConfigurationTest` (after `the_retired_openai_wire_value_fails_to_start_naming_the_property`):

```java
  @Test
  void a_custom_provider_on_the_responses_wire_is_registered_and_observed() {
    runner
        .withPropertyValues(
            "nessy.providers.mine.wire=openai-responses",
            "nessy.providers.mine.base-url=https://g/v1",
            "nessy.providers.mine.api-key=k")
        .run(
            context -> {
              Map<String, InferenceProvider> providers =
                  context.getBeansOfType(InferenceProvider.class);
              assertThat(providers).containsOnlyKeys("mine");
              assertThat(providers.values()).isNotEmpty().allSatisfy(this::isObserved);
              assertThat(providers.get("mine").vendor()).isEqualTo("openai");
            });
  }

  /** The override §7c documents for a preset pointed at a Chat Completions server. */
  @Test
  void the_openai_preset_can_be_told_its_wire() {
    runner
        .withPropertyValues(
            "openai.api-key=sk-test",
            "openai.base-url=http://localhost:1234/v1",
            "nessy.providers.openai.wire=openai-chat")
        .run(
            context ->
                assertThat(context.getBeansOfType(InferenceProvider.class))
                    .containsOnlyKeys("openai"));
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test -Dtest='WireProvidersTest,InferenceProvidersAutoConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: `the_openai_responses_wire_builds_the_responses_adapter` and `a_custom_provider_on_the_responses_wire...` fail with `IllegalStateException: the openai-responses wire has no adapter yet`.

- [ ] **Step 3: Map the wire**

In `WireProviders.java`: add the import `org.jwcarman.nessy.inference.openai.OpenAiResponsesInferenceProvider`; add

```java
  private static final String OPENAI_RESPONSES_CLASS =
      "org.jwcarman.nessy.inference.openai.OpenAiResponsesInferenceProvider";
```

`adapterClassName`: `case OPENAI_RESPONSES -> OPENAI_RESPONSES_CLASS;`. `build`: `case OPENAI_RESPONSES -> OpenAiResponses.build(resolved, mapper);`. Delete `unbuilt()`. Add beside `OpenAiChat`:

```java
  private static final class OpenAiResponses {

    private OpenAiResponses() {}

    static InferenceProvider build(ResolvedProvider resolved, @Nullable JsonMapper mapper) {
      return OpenAiResponsesInferenceProvider.of(
          c -> {
            c.apiKey(resolved.apiKey());
            if (resolved.baseUrl() != null) {
              c.baseUrl(resolved.baseUrl());
            }
            c.vendor(resolved.vendor());
            c.timeout(TransportTimeouts.PROVIDER_TRANSPORT);
            if (mapper != null) {
              c.mapper(mapper);
            }
          });
    }
  }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test; echo exit=$?`
Expected: `exit=0`.

- [ ] **Step 5: Full gate and commit**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && ./mvnw -q clean verify; echo exit=$?` -- expected `exit=0`.

```bash
git add nessy-spring-boot/autoconfigure
git commit -m "feat: the openai-responses wire builds the Responses adapter under Boot

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

### Task 7: Live tests and the measurement

Spec §9c, §5j (the replay rule confirmed or widened), §12(1). Every test here is `@Tag("live")` and skips cleanly with no key: `clean verify` never runs them. Suggested implementer: Sonnet. Running them spends money: the controller runs them once, with James's keys exported, after the code is reviewed.

**Files:**
- Create: `nessy-inference/openai/src/test/java/org/jwcarman/nessy/inference/openai/OpenAiResponsesLiveTest.java`
- Modify: `nessy-spring-boot/autoconfigure/src/test/java/org/jwcarman/nessy/spring/boot/inference/PresetCandidatesLiveTest.java`

**Interfaces:**
- Consumes: `OpenAiResponsesInferenceProvider.fromEnv()`, `infer(...)`, `close()` (Task 4); `Narration` (existing); the `openai-responses` wire (Task 6).
- Produces: `target/preset-measurements.md` with a `wire` column; a recorded decision on §5j's replay rule.

- [ ] **Step 1: Write `OpenAiResponsesLiveTest`**

```java
package org.jwcarman.nessy.inference.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.ToolChoice;
import org.jwcarman.nessy.inference.ToolOffer;
import org.jwcarman.nessy.inference.Toolset;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Whether OpenAI's Responses API accepts what this adapter builds -- the chat live test's nine
 * cases through the new wire, plus what this wire adds: reasoning items carried across a tool call
 * and across two, strict mode with an optional component, and the strict fallback.
 *
 * <p><b>Skipped, not failed, without {@code OPENAI_API_KEY}</b>, and tagged {@code live} so a build
 * never spends money. Run it deliberately:
 *
 * <pre>{@code
 * OPENAI_API_KEY=sk-... ./mvnw -q -pl :nessy-inference-openai test -Dnessy.excludedGroups= -Dtest=OpenAiResponsesLiveTest
 * }</pre>
 */
@Tag("live")
class OpenAiResponsesLiveTest {

  private static final String MODEL = System.getenv().getOrDefault("NESSY_LIVE_MODEL", "gpt-4o-mini");

  /** A model that reasons, and calls tools only over this wire (spec §1). */
  private static final String REASONING_MODEL =
      System.getenv().getOrDefault("NESSY_LIVE_REASONING_MODEL", "gpt-6-sol");

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final ToolOffer LAKE_DEPTH =
      new ToolOffer(
          new ToolName("lake_depth"),
          "returns the maximum depth of a named lake, in metres",
          new JsonSchema(
              """
              {"type":"object","properties":{"name":{"type":"string",\
              "description":"the lake to look up"}},"required":["name"]}"""));

  private static final ToolOffer WEATHER =
      new ToolOffer(
          new ToolName("weather"),
          "returns today's weather for a named place",
          new JsonSchema(
              """
              {"type":"object","properties":{"place":{"type":"string"}},"required":["place"]}"""));

  private static final ToolOffer TO_FEET =
      new ToolOffer(
          new ToolName("to_feet"),
          "converts a length in metres to feet",
          new JsonSchema(
              """
              {"type":"object","properties":{"metres":{"type":"number"}},"required":["metres"]}"""));

  private static OpenAiResponsesInferenceProvider provider() {
    assumeTrue(System.getenv("OPENAI_API_KEY") != null, "OPENAI_API_KEY is not set");
    return OpenAiResponsesInferenceProvider.fromEnv();
  }

  private static Turn turn(String question, List<Exchange> exchanges) {
    return new Turn(
        new TurnId(1), new Input(new Seq(1), List.of(new Block.Text(question))), exchanges, null, 0);
  }

  private static InferenceRequest asking(
      String question, List<ToolOffer> tools, ToolChoice choice, String model) {
    return new InferenceRequest(
        new SystemPrompt("You are a terse assistant. Answer in one short sentence."),
        InferenceContext.of(List.of(turn(question, List.of()))),
        new Toolset(tools, choice),
        InferenceOptions.of(model));
  }

  private static InferenceRequest asking(String question, List<ToolOffer> tools) {
    return asking(question, tools, ToolChoice.auto(), MODEL);
  }

  private static InferenceRequest askingFor(String question, JsonSchema shape) {
    return new InferenceRequest(
        new SystemPrompt("You are a terse assistant."),
        InferenceContext.of(List.of(turn(question, List.of()))),
        Toolset.none(),
        InferenceOptions.of(MODEL),
        Optional.of(shape));
  }

  private static String textOf(InferenceResult result) {
    assertThat(result).isInstanceOf(InferenceResult.Answer.class);
    return ((InferenceResult.Answer) result)
        .blocks().stream()
            .filter(Block.Text.class::isInstance)
            .map(Block.Text.class::cast)
            .map(Block.Text::text)
            .collect(Collectors.joining());
  }

  private static ToolName calledIn(InferenceResult result) {
    assertThat(result).isInstanceOf(InferenceResult.Actions.class);
    return ((InferenceResult.Actions) result)
        .blocks().stream()
            .filter(Block.ToolCall.class::isInstance)
            .map(Block.ToolCall.class::cast)
            .findFirst()
            .orElseThrow()
            .name();
  }

  /** Answers every call in {@code actions} the way the real tools would. */
  private static List<ToolOutcome> outcomesFor(InferenceResult.Actions actions) {
    return actions.blocks().stream()
        .filter(Block.ToolCall.class::isInstance)
        .map(Block.ToolCall.class::cast)
        .map(
            call ->
                (ToolOutcome)
                    new ToolOutcome.Succeeded(
                        call.id(),
                        List.of(
                            new Block.Text(
                                switch (call.name().value()) {
                                  case "lake_depth" -> "230 metres";
                                  case "to_feet" -> "754.6 feet";
                                  default -> "no such tool";
                                }))))
        .toList();
  }

  // ---- the chat live test's nine, through this wire --------------------------------------

  @Test
  void a_real_question_gets_a_real_answer() {
    try (OpenAiResponsesInferenceProvider provider = provider()) {
      assertThat(textOf(provider.infer(asking("What is the capital of France?", List.of()))))
          .containsIgnoringCase("Paris");
    }
  }

  @Test
  void the_answer_is_narrated_as_it_streams() {
    try (OpenAiResponsesInferenceProvider provider = provider()) {
      Narration narrated = new Narration();

      InferenceResult result =
          provider.infer(asking("List the seven days of the week, one per line.", List.of()), narrated);

      assertThat(narrated.text()).as("a real stream arrives in more than one piece").hasSizeGreaterThan(1);
      assertThat(String.join("", narrated.text())).isEqualTo(textOf(result));
    }
  }

  @Test
  void a_tool_offer_is_accepted_strict_and_called_with_arguments_that_fit_its_schema() {
    try (OpenAiResponsesInferenceProvider provider = provider()) {
      InferenceResult result = provider.infer(asking("How deep is Loch Ness?", List.of(LAKE_DEPTH)));

      assertThat(calledIn(result)).isEqualTo(new ToolName("lake_depth"));
      assertThat(((InferenceResult.Actions) result).blocks())
          .filteredOn(Block.ToolCall.class::isInstance)
          .singleElement()
          .isInstanceOfSatisfying(
              Block.ToolCall.class, call -> assertThat(call.arguments()).contains("Loch Ness"));
    }
  }

  @Test
  void requiring_one_tool_by_name_overrides_what_the_model_would_have_picked() {
    try (OpenAiResponsesInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              asking(
                  "How deep is Loch Ness?",
                  List.of(LAKE_DEPTH, WEATHER),
                  new ToolChoice.Named(new ToolName("weather")),
                  MODEL));

      assertThat(calledIn(result)).isEqualTo(new ToolName("weather"));
    }
  }

  @Test
  void requiring_some_tool_leaves_no_room_for_an_answer() {
    try (OpenAiResponsesInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(asking("Say hello.", List.of(LAKE_DEPTH, WEATHER), new ToolChoice.Any(), MODEL));

      assertThat(result).isInstanceOf(InferenceResult.Actions.class);
    }
  }

  /** §5e: emulated as tool_choice none; the answer itself is the contract. */
  @Test
  void answering_now_produces_prose_with_the_tools_still_on_offer() {
    try (OpenAiResponsesInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              asking("How deep is Loch Ness?", List.of(LAKE_DEPTH, WEATHER), new ToolChoice.Answer(), MODEL));

      assertThat(textOf(result)).isNotBlank();
      assertThat(result.usage().counted()).isTrue();
    }
  }

  @Test
  void forbidding_tools_means_no_call_is_made() {
    try (OpenAiResponsesInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              asking("How deep is Loch Ness?", List.of(LAKE_DEPTH, WEATHER), new ToolChoice.None(), MODEL));

      assertThat(result.usage().counted()).isTrue();
      assertThat(result).isNotInstanceOf(InferenceResult.Actions.class);
    }
  }

  @Test
  void an_answer_can_be_asked_for_in_a_shape() {
    JsonSchema shape =
        new JsonSchema(
            """
            {"type":"object",
             "properties":{"city":{"type":"string"},"country":{"type":"string"}},
             "required":["city","country"]}""");

    try (OpenAiResponsesInferenceProvider provider = provider()) {
      JsonNode parsed =
          MAPPER.readTree(textOf(provider.infer(askingFor("What is the capital of France?", shape))));

      assertThat(parsed.get("city").asString()).containsIgnoringCase("Paris");
      assertThat(parsed.get("country").asString()).containsIgnoringCase("France");
    }
  }

  @Test
  void the_shape_is_honoured_even_when_the_question_fits_it_badly() {
    JsonSchema shape =
        new JsonSchema(
            """
            {"type":"object",
             "properties":{"answer":{"type":"string"},"confident":{"type":"boolean"}},
             "required":["answer","confident"]}""");

    try (OpenAiResponsesInferenceProvider provider = provider()) {
      JsonNode parsed = MAPPER.readTree(textOf(provider.infer(askingFor("Tell me a joke.", shape))));

      assertThat(parsed.has("answer")).isTrue();
      assertThat(parsed.has("confident")).isTrue();
    }
  }

  // ---- what this wire adds -----------------------------------------------------------------

  /** Spec §1's measurement, and the proof §7c waits for. */
  @Test
  void a_reasoning_model_calls_a_tool_and_answers_once_the_result_goes_back_with_its_reasoning() {
    try (OpenAiResponsesInferenceProvider provider = provider()) {
      String question = "How deep is Loch Ness? Use the lake_depth tool.";
      InferenceResult first =
          provider.infer(asking(question, List.of(LAKE_DEPTH), ToolChoice.auto(), REASONING_MODEL));

      assertThat(first).isInstanceOf(InferenceResult.Actions.class);
      InferenceResult.Actions actions = (InferenceResult.Actions) first;
      assertThat(actions.blocks())
          .as("the reasoning item that led to the call is kept")
          .anyMatch(Block.Provider.class::isInstance);

      InferenceResult second =
          provider.infer(
              new InferenceRequest(
                  new SystemPrompt("You are a terse assistant. Answer in one short sentence."),
                  InferenceContext.of(
                      List.of(
                          turn(
                              question,
                              List.of(new Exchange(new Seq(2), actions.blocks(), outcomesFor(actions)))))),
                  Toolset.of(List.of(LAKE_DEPTH)),
                  InferenceOptions.of(REASONING_MODEL)));

      assertThat(textOf(second)).contains("230");
    }
  }

  /**
   * §5j's open decision: two calls in sequence under the starting replay rule (only the last
   * exchange's reasoning goes back). If the API rejects the third request or the answer degrades,
   * widen the rule (Step 4) and record which rule held.
   */
  @Test
  void a_reasoning_model_finishes_a_turn_that_needs_two_calls_in_sequence() {
    try (OpenAiResponsesInferenceProvider provider = provider()) {
      String question =
          "How deep is Loch Ness in feet? Look the depth up in metres with lake_depth first,"
              + " then convert that number with to_feet.";
      List<Exchange> exchanges = new ArrayList<>();
      InferenceResult result = null;
      for (int step = 0; step < 4; step++) {
        result =
            provider.infer(
                new InferenceRequest(
                    new SystemPrompt("You are a terse assistant. Use the tools, one at a time."),
                    InferenceContext.of(List.of(turn(question, exchanges))),
                    Toolset.of(List.of(LAKE_DEPTH, TO_FEET)),
                    InferenceOptions.of(REASONING_MODEL)));
        if (!(result instanceof InferenceResult.Actions actions)) {
          break;
        }
        exchanges.add(new Exchange(new Seq(exchanges.size() + 2L), actions.blocks(), outcomesFor(actions)));
      }

      assertThat(exchanges).as("the calls came in sequence, one step each").hasSizeGreaterThanOrEqualTo(2);
      assertThat(textOf(result)).contains("754");
    }
  }

  /** §5d: under strict, the model writes the optional component -- null or a value -- rather than leaving it out. */
  @Test
  void an_optional_component_is_written_under_strict_mode_and_binds() {
    ToolOffer lookup =
        new ToolOffer(
            new ToolName("lake_depth"),
            "returns the maximum depth of a named lake",
            new JsonSchema(
                """
                {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                 "properties":{"name":{"type":"string"},"unit":{"type":["string","null"]}},
                 "required":["name"]}"""));

    try (OpenAiResponsesInferenceProvider provider = provider()) {
      InferenceResult result = provider.infer(asking("How deep is Loch Ness?", List.of(lookup)));

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
      LakeQuery bound = MAPPER.readValue(arguments, LakeQuery.class);
      assertThat(bound.name()).containsIgnoringCase("Ness");
    }
  }

  record LakeQuery(String name, Optional<String> unit) {}

  /** §5d: a sealed vocabulary's oneOf falls back to non-strict, and the call still goes through. */
  @Test
  void a_sealed_vocabulary_falls_back_to_non_strict_and_is_still_called() {
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
    Logger logger = (Logger) LoggerFactory.getLogger(OpenAiResponsesRequests.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    try (OpenAiResponsesInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              asking("Restart the host called web-1.", List.of(command), new ToolChoice.Any(), MODEL));

      assertThat(calledIn(result)).isEqualTo(new ToolName("server_command"));
      assertThat(appender.list).as("the fallback fired and said so").isNotEmpty();
    } finally {
      logger.detachAppender(appender);
    }
  }
}
```

- [ ] **Step 2: Give `PresetCandidatesLiveTest` a wire column and the two rows**

`Candidate` gains a `wire` component (null for a preset reached by its key):

```java
  private record Candidate(
      String id,
      @Nullable String baseUrl,
      String keyEnvVar,
      @Nullable String defaultModel,
      String vendor,
      @Nullable String wire) {

    static Candidate preset(String id, String keyEnvVar, String vendor) {
      return new Candidate(id, null, keyEnvVar, null, vendor, null);
    }

    String shownUrl() {
      return baseUrl == null ? "(the " + id + " preset)" : baseUrl;
    }

    String shownWire() {
      return wire == null ? "(preset)" : wire;
    }

    String modelEnvVar() {
      return id.toUpperCase(Locale.ROOT).replace('-', '_') + "_MODEL";
    }
  }
```

Every existing `new Candidate(...)` row gains a last argument `"openai-chat"`. Add two rows at the end of `CANDIDATES`:

```java
          new Candidate(
              "openai-responses",
              "https://api.openai.com/v1",
              "OPENAI_API_KEY",
              null,
              "openai",
              "openai-responses"),
          new Candidate(
              "perplexity",
              System.getenv().getOrDefault("PERPLEXITY_BASE_URL", "https://api.perplexity.ai/v1"),
              "PERPLEXITY_API_KEY",
              null,
              "perplexity",
              "openai-responses"));
```

(The Perplexity host is the one its 403 pointed at, with `/v1` because the SDK appends only `/responses`; `PERPLEXITY_BASE_URL` overrides it when the vendor's docs say otherwise. Neither new row has a default model: `OPENAI_RESPONSES_MODEL` / `PERPLEXITY_MODEL` must be set, or the row is skipped.)

In `provider(...)`: `".wire=openai-chat"` becomes `".wire=" + candidate.wire()`. `Row` gains a `wire` component after `baseUrl`; every `new Row(...)` passes `candidate.shownWire()` there; `write_results_file` writes the header `| id | model | base url | wire | result | detail |`, the separator `|---|---|---|---|---|---|`, and appends `row.wire()` after `row.baseUrl()`. The class javadoc's "`nessy.providers.<id>.wire =openai-chat` plus a base URL" becomes "`nessy.providers.<id>.wire` (`openai-chat` or `openai-responses`) plus a base URL", and "Results land in" gains "with the wire each vendor was asked over".

- [ ] **Step 3: Prove the live tests skip cleanly with no keys**

Run: `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:apply license:format && env -u OPENAI_API_KEY -u PERPLEXITY_API_KEY ./mvnw -q -pl :nessy-inference-openai,:nessy-spring-boot-autoconfigure -am test -Dnessy.excludedGroups= -Dtest='OpenAiResponsesLiveTest,PresetCandidatesLiveTest' -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?`
Expected: `exit=0`; every test in both classes skipped (other candidates' keys may be set in this shell -- unset any you do not want spent).

Then `./mvnw -q clean verify; echo exit=$?` -- expected `exit=0` (the `live` tag keeps them out).

Commit:

```bash
git add nessy-inference/openai nessy-spring-boot/autoconfigure
git commit -m "test: live measurement of the Responses wire, and a wire column for the candidates

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

- [ ] **Step 4: Run live, with keys (controller, with James's go-ahead -- this spends money)**

```bash
cd /Users/jcarman/IdeaProjects/nessy-responses
./mvnw -q -pl :nessy-inference-openai test -Dnessy.excludedGroups= -Dtest=OpenAiResponsesLiveTest; echo exit=$?
OPENAI_RESPONSES_MODEL=gpt-4o-mini OPENAI_MODEL=gpt-4o-mini ./mvnw -q -pl :nessy-inference-openai,:nessy-spring-boot-autoconfigure -am test -Dnessy.excludedGroups= -Dtest=PresetCandidatesLiveTest -Dsurefire.failIfNoSpecifiedTests=false; echo exit=$?
cat nessy-spring-boot/autoconfigure/target/preset-measurements.md
```

(`PERPLEXITY_MODEL` must be set to a model the Agent API documents for the Perplexity row to run.)

Record in the ledger, per row: which cases passed; for `a_reasoning_model_finishes_a_turn_that_needs_two_calls_in_sequence`, which replay rule held. **If it fails under the starting rule** with a rejection or a degraded answer: change `OpenAiResponsesRequests.replays` to `return inFlight;`, rename Task 3's test `in_an_earlier_exchange_of_the_same_turn_is_not_replayed_under_the_starting_rule` to `in_an_earlier_exchange_of_the_same_turn_is_replayed_too` asserting `anyMatch(ResponseInputItem::isReasoning)`, update `replays`'s javadoc to state the rule that holds, re-run this test live, then the full gate, and commit `fix: reasoning items travel back for every step of the turn in flight` with the measured error message in the body. If `a_sealed_vocabulary_falls_back...` shows the call failing even non-strict, stop and report -- that is a finding for James, not a code change here. A FAIL row for Perplexity with the vendor's message is a result, not a failure of this task.

---

### Task 8: Docs and the changelog

Spec §10 step 5. Describe what is. Suggested implementer: `docs-writer` (Sonnet), reviewed by `task-reviewer`.

**Files:**
- Modify: `docs/guides/providers.md`, `docs/guides/getting-started.md` (lines 72-73), `docs/index.md` (line 137), `README.md` (lines 93, 178, 208), `CHANGELOG.md` (`[Unreleased]`)

**Interfaces:**
- Consumes: every name above; Task 7's measurement file.
- Produces: docs only.

- [ ] **Step 1: `providers.md` -- the adapter count and "Building a provider"**

Lines 21-26 become:

```markdown
Four adapter modules ship: `nessy-inference-anthropic` on Anthropic's Java
SDK, `nessy-inference-openai` on OpenAI's, `nessy-inference-gemini` on
Google's java-genai SDK, and `nessy-inference-bedrock` on the AWS SDK's
Converse API. The OpenAI module holds two adapters, one per shape OpenAI
defined: `OpenAiChatInferenceProvider` for Chat Completions, which every
service in [the OpenAI-compatible universe](#the-openai-compatible-universe)
also speaks, and `OpenAiResponsesInferenceProvider` for the
[Responses API](#openai-responses).
```

In "Building a provider", after the `OpenAiChatInferenceProvider` line of the code block, add:

```java
InferenceProvider responses = OpenAiResponsesInferenceProvider.of(c -> c.apiKey(key));
```

- [ ] **Step 2: `providers.md` -- the custom-provider example and a Responses section**

Under "### Custom providers", after the paragraph on `wire` values, add:

```markdown
The `openai` preset speaks `openai-chat`. To reach OpenAI over the Responses
API instead, tell the preset its wire:

```yaml
nessy:
  providers:
    openai:
      wire: openai-responses
```
```

Add a new `## OpenAI Responses` section before `## The OpenAI-compatible universe`:

```markdown
## OpenAI Responses

`OpenAiResponsesInferenceProvider` speaks OpenAI's Responses API, the
`openai-responses` wire under Boot. A reasoning model such as GPT-6 calls
function tools only over this API.

It is stateless. Every call sends the whole context with `store: false`, and
nothing in the adapter can name a previous response or a conversation, so
Nessy's event log stays the only record of the conversation: replay,
summarisers, turn policy and switching providers all work from it. The
system prompt and ambient background go in `instructions`.

A reasoning model's encrypted reasoning items are asked for on every call
and each one is stored in the transcript as a `Block.Provider` block tagged
with the provider's vendor, in the position it arrived. The adapter sends
back the ones that led to the tool results it is returning, within the turn
in flight; another vendor's blocks, and any from an earlier turn, are not
sent. An encrypted item is kilobytes, one per inference step.

Function tools go out in strict mode: the adapter rewrites each tool's
schema so every property is required, an optional one admits `null`, and
every object forbids properties it does not list. A tool whose schema uses
something strict mode cannot express — a sealed type's `oneOf`, an
`Optional` record, a map — is sent as generated with `strict: false`, and
the adapter logs a warning naming the tool and the keyword. The other tools
in the request stay strict. A structured answer's schema is rewritten the
same way and sent as `text.format`.

Only function tools are offered. OpenAI's hosted tools (web search, file
search, code interpreter, remote MCP) run where Nessy cannot approve or
record them, and any hosted-tool output a server sends back is dropped.

Usage reads `input_tokens`, `output_tokens` and the cache and reasoning
details; a count a server leaves out is null.
```

(Add one more paragraph only if Task 7's Perplexity row passed: "Perplexity's Agent API answers over this wire: set `wire: openai-responses`, `base-url: <the measured URL>` and `vendor: perplexity` on a custom provider." If it failed, add nothing about Perplexity.)

- [ ] **Step 3: `providers.md` -- the compatible universe**

Line 334's "Every service below speaks the same openai wire" becomes "Every service below speaks Chat Completions, the `openai-chat` wire". The `grok` example already says `OpenAiChatInferenceProvider`.

- [ ] **Step 4: The one-liners elsewhere**

- `docs/guides/getting-started.md` lines 72-73: "The OpenAI one also speaks to anything with OpenAI's wire protocol" → "The OpenAI one speaks both Chat Completions and the Responses API, and reaches anything that speaks OpenAI's Chat Completions protocol".
- `docs/index.md` line 137 and `README.md` line 178, the module table cell: "the provider adapters; the OpenAI one reaches every OpenAI-compatible endpoint" → "the provider adapters; the OpenAI module speaks Chat Completions and the Responses API, and reaches every OpenAI-compatible endpoint".
- `README.md` line 208: "Providers: four adapters, every OpenAI-compatible endpoint, and thinking as a provider setting" → "Providers: four adapter modules, both OpenAI shapes, every OpenAI-compatible endpoint, and thinking as a provider setting". Read line 93 in context and change it only if it names the `openai` wire.

- [ ] **Step 5: The changelog's Added entry**

Under `## [Unreleased]`, above `### Breaking changes`:

```markdown
### Added

- **`OpenAiResponsesInferenceProvider`**, an adapter for OpenAI's Responses
  API, in `nessy-inference-openai` beside the Chat Completions one. It is
  stateless (`store: false`, the whole context on every call), keeps each
  encrypted reasoning item as a `Block.Provider` block, and sends function
  tools in strict mode, falling back per tool with a warning when a schema
  cannot be expressed strictly. Reasoning models such as GPT-6 call tools
  through it.
- **Spring Boot: the `openai-responses` wire.** A custom provider, or the
  `openai` preset with `nessy.providers.openai.wire: openai-responses`,
  builds the Responses adapter.
```

- [ ] **Step 6: Check, gate and commit**

Run: `git grep -nE -e 'wire: openai( |$)' -e 'wire=openai([^-]|$)' -e OpenAiInferenceProvider -e OpenAiProviderConfig -- docs README.md ':!docs/superpowers'` -- expected: nothing. Then `pgrep -fl '[n]essy-examples|[s]pring-boot:run'; ./mvnw -q spotless:check license:check && ./mvnw -q clean verify; echo exit=$?` -- expected `exit=0`.

```bash
git add docs README.md CHANGELOG.md
git commit -m "docs: the Responses adapter, and the two OpenAI wires

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E7XfAmjhbzF5rmtGigWXKC"
```

---

## Not in this plan

- **The `openai` preset's default wire (§7c, §10 step 4).** Stays `Wire.OPENAI_CHAT`. After Task 7's live pass and James's go-ahead, a later commit changes that one `Preset.CATALOGUE` row to `Wire.OPENAI_RESPONSES`, flips `ProviderCatalogueTest`'s `openai` expectation, adds the test that `openai.api-key` with `openai.base-url=http://localhost:1234/v1` still builds one provider, and adds the changelog line.
- **Vendor properties** (`openai.reasoning.summary`, `openai.reasoning.effort`, `openai.tools.strict`): the next `0.3.0` item, with its own record. Until then no `reasoning` object is sent and OpenAI's summary deltas do not arrive; the narration path is built and replay-tested.
- **A Perplexity preset**: only with a measured row (Task 7) and its own commit.

## Self-review

- **Spec coverage.** §4a/§4b/§4c → Task 1 (every inventoried file; dated records and the `0.2.0` entry untouched). §5a/§5b/§5c/§5e/§5f → Task 3. §5d → Tasks 2-3. §5g → Task 5 (no `reasoning` object: Task 3). §5h → Tasks 4-5. §5i → Task 4. §5j storage → Task 4, replay → Task 3, widening → Task 7 Step 4. §5k → Tasks 1 and 4. §6 (1)-(5) → Task 5 (trailing, unknown), Task 4 (usage detail, single `read`), Task 3 (per-tool fallback). §7a/§7d → Task 6 (§7d's report needs no code). §7b → Task 1. §7c → deliberately excluded. §8 → Task 4 (hosted items dropped), Task 3 (nothing reachable). §9a → Tasks 2-5; §9b → Tasks 1 and 6; §9c → Task 7. §10 order → Tasks 1 / 2-5 / 6-7 / 8.
- **Deviations, stated.** (1) The replay rule's "exchange whose outcomes are being sent back" is made concrete as the last exchange of the turn in flight, so that widening (§5j) means something. (2) The mid-stream `error` event's fault stays `Permanent` as §5h says; only a `response.failed` is classified by code (§5k). (3) The generator already widens `Optional` components and writes `Optional<Record>` as `oneOf`, so the rewrite is idempotent and those tools fall back to non-strict under the spec's subset.
- **Type consistency.** `toParams(InferenceRequest, String, JsonMapper)` (Tasks 3, 4); `Projected(schema, refusedKeyword)` / `strict()` (Tasks 2, 3); `OpenAiClients.build(boolean, String, String, String, Duration)` (Tasks 1, 4); `OpenAiFailures.classify(OpenAIException)` / `classify(ResponseError)` (Tasks 1, 4); the payload keys `id` / `encrypted_content` / `summary` written in Task 4 and read in Task 3; `ResponseStreams` members used in Tasks 4-5 are all defined in Task 4 Step 1.
