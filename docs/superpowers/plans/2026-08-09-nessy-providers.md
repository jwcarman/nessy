# Nessy Providers Implementation Plan (Plan 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `nessy-model-anthropic` (native SDK: streaming, thinking with signature round-trip, images, prompt caching, usage) and `nessy-model-openai` (Chat-Completions wire with configurable base URL: OpenAI, OpenRouter, Ollama, …), plus the pre-freeze grammar completions and the retry decorator they need — so a real key makes a real agent talk.

**Architecture:** Each provider is a leaf module implementing `ModelProvider` over its official SDK, translating between Nessy's grammar and the wire in two focused units per module: request assembly (`ModelRequest` → SDK params) and stream translation (SDK events → `ModelEvent`). Retry wraps any provider as a decorator in core, dogfooding the SPI. Everything is unit-testable offline; tests that spend tokens are `@Tag("live")` and excluded by default.

**Tech Stack:** Java 25; `com.anthropic:anthropic-java` 2.52.0 (known-good locally — `../agentic-agency` builds against it); `com.openai:openai-java` (newest stable — Task 3 discovers and records the version); existing core/testing modules.

**Reference-code policy (read this, implementers):** For SDK-facing code, this plan specifies exact *contracts, mapping tables, and our-side signatures*, and points you at **working reference code using the identical Anthropic SDK version**: `/Users/jcarman/IdeaProjects/agentic-agency/src/main/java/com/callibrity/ai/agency/` — `StreamingModelClient.java` (streaming event handling), `DirectModelClient.java` (request assembly), `Schemas.java` (ObjectNode → SDK `Tool.InputSchema`, including `$defs`). Derive SDK call chains from those files and the SDK sources in `~/.m2` — never from guesswork. Where this plan shows SDK-touching code sketches, treat names as intent and verify each against the real SDK before writing; our-side code blocks are verbatim as always.

**Source spec:** `docs/superpowers/specs/2026-08-09-nessy-agent-harness-design-v2.md` (§7 grammar, §10.2 model family, §13 ladder, §14 sequencing, §15 grammar-freeze risk).

## Global Constraints

- **Java 25.** groupId `org.jwcarman.nessy`; new modules `nessy-model-anthropic` (package `org.jwcarman.nessy.model.anthropic`) and `nessy-model-openai` (package `org.jwcarman.nessy.model.openai`); both get `Automatic-Module-Name` manifest entries mirroring their package name (house decision after the JPMS withdrawal).
- **`nessy-core` gains NO new dependencies** in this plan. SDK dependencies live only in their provider modules.
- **No star imports. No inline fully-qualified class names. No `@SuppressWarnings` of any kind.**
- **Apache header via `./mvnw license:format -Plicense`; Spotless via `./mvnw spotless:apply`** before every commit; 2-space Google style reformatting of this plan's samples is expected.
- **Tests read as prose**: `snake_case` sentences, `@Nested` `Capitalized_phrases`, generator already module-wide (new modules need their own `src/test/resources/junit-platform.properties`, copied verbatim from nessy-core's).
- **Keyless default build, forever**: `./mvnw -q clean verify` green with no API key and no model-provider network. Token-spending tests are `@Tag("live")` + JUnit `assumeTrue(key != null)` so they skip gracefully even when the exclusion is lifted without a key. Release profile must also stay green (`./mvnw -P release -DskipTests -Dgpg.skip=true verify`) — javadoc references are checked there.
- **Core sealed switches stay exhaustive with no `default` arm** — the compiler drives the grammar additions through every switch.
- **Never weaken an existing assertion.** Mechanical updates (new constructor args, renames this plan makes) are expected; assertion changes are not.
- Commit after every task.

---

## File Structure

```
nessy-core (modified)
  api/Event.java                    +ThinkingSigned, +RedactedThinkingArrived; StopReason +REFUSAL
  spi/model/ModelEvent.java         +ThinkingSigned, +RedactedThinkingEmitted
  spi/Reducer.java                  two new arms; REFUSAL halts like MAX_TOKENS
  spi/InProcessEngine.java          two new translate arms
  spi/model/RetryingModelProvider.java   (new) generic retry decorator
  spi/model/RetryPolicy.java             (new)
  spi/model/Sleeper.java                 (new, package-private test seam)
nessy-testing (modified)
  ScriptedModelProvider.java        +thinkingSigned(), +redactedThinking()
nessy-model-anthropic (new module)
  AnthropicModelProvider.java       builder, capabilities, stream() wiring, RETRYABLE predicate
  AnthropicRequests.java            ModelRequest → MessageCreateParams (assembly)
  AnthropicStream.java              SDK stream → ModelEvent translation (incl. tool-use assembly)
  AnthropicSchemas.java             ObjectNode → SDK Tool.InputSchema
nessy-model-openai (new module)
  OpenAiModelProvider.java          builder (baseUrl!), capabilities, stream(), RETRYABLE
  OpenAiRequests.java               ModelRequest → ChatCompletionCreateParams
  OpenAiStream.java                 chunk deltas → ModelEvent (indexed tool-call assembly)
```

---

### Task 1: Grammar completion II — thinking signatures, redacted thinking, REFUSAL

**Files:**
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/Event.java`, `api/StopReason.java`, `spi/model/ModelEvent.java`, `spi/Reducer.java`, `spi/InProcessEngine.java` (translate)
- Modify: `nessy-testing/src/main/java/org/jwcarman/nessy/testing/ScriptedModelProvider.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/spi/ReducerGrammarTest.java` (additions), `ReducerToolResultTest.java` (REFUSAL halt), `nessy-testing/src/test/java/org/jwcarman/nessy/testing/EndToEndTest.java` (signature round trip)

**Interfaces:**
- Consumes: the existing sealed families.
- Produces (Tasks 4–9 rely on these exactly):
  - `Event.ThinkingSigned(String signature)`; `Event.RedactedThinkingArrived(String data)` — both `requireNonNull` with the house message form
  - `ModelEvent.ThinkingSigned(String signature)`; `ModelEvent.RedactedThinkingEmitted(String data)`
  - `StopReason.REFUSAL`
  - `ScriptedModelProvider.Builder.thinkingSigned(String)`, `.redactedThinking(String)`
  - Reducer semantics: `ThinkingSigned` replaces a trailing `ThinkingBlock(text, "")` with `(text, signature)` (no-op when the trailing block is not a `ThinkingBlock` — the provider contract emits `ThinkingChunk` first; javadoc this); `RedactedThinkingArrived` appends `RedactedThinkingBlock(data)` to `pendingBlocks`; both emit no effects. `ModelTurnEnded` with `REFUSAL` takes the same halt shape as `MAX_TOKENS` — settle, `flushResults(abandonPendingCalls(...))`, `failureReason` "model refused to continue (REFUSAL)", `FAILED`, no effects.

- [ ] **Step 1: Write the failing tests**

Add to `ReducerGrammarTest`:

```java
@Test
void a_signature_lands_on_the_trailing_thinking_block() {
  SessionState state = reducer.reduce(initial, Event.UserSaid.of("hi")).state();
  state = reducer.reduce(state, new Event.ThinkingDelta("Let me think.")).state();
  state = reducer.reduce(state, new Event.ThinkingSigned("sig-abc")).state();

  assertThat(state.pendingBlocks()).containsExactly(new ThinkingBlock("Let me think.", "sig-abc"));
}

@Test
void a_signature_with_no_trailing_thinking_block_changes_nothing() {
  SessionState state = reducer.reduce(initial, Event.UserSaid.of("hi")).state();
  state = reducer.reduce(state, new Event.TextDelta("Answer.")).state();
  Step step = reducer.reduce(state, new Event.ThinkingSigned("sig-abc"));

  assertThat(step.state().pendingBlocks()).containsExactly(new TextBlock("Answer."));
  assertThat(step.effects()).isEmpty();
}

@Test
void redacted_thinking_appends_its_block_in_order() {
  SessionState state = reducer.reduce(initial, Event.UserSaid.of("hi")).state();
  state = reducer.reduce(state, new Event.RedactedThinkingArrived("opaque-bytes")).state();
  state = reducer.reduce(state, new Event.TextDelta("Answer.")).state();

  assertThat(state.pendingBlocks())
      .containsExactly(new RedactedThinkingBlock("opaque-bytes"), new TextBlock("Answer."));
}
```

Add to `ReducerToolResultTest` (beside the MAX_TOKENS halt test, same fixtures):

```java
@Test
void a_refusal_fails_loudly_and_still_answers_every_pending_tool_use() {
  ToolCall first = call("c1");
  ToolCall second = call("c2");
  SessionState state = awaitingApprovalWith(reducer, first, second);

  Step step =
      reducer.reduce(state, new Event.ModelTurnEnded(StopReason.REFUSAL, Usage.zero()));

  assertThat(step.state().status()).isEqualTo(SessionStatus.FAILED);
  assertThat(step.state().failureReason()).contains("REFUSAL");
  assertThat(step.state().pendingCalls()).isEmpty();
  assertThat(step.effects()).isEmpty();
}
```

(Note: `ModelTurnEnded` arriving while calls are pending settles first, exactly as the MAX_TOKENS test does — mirror its structure; if the existing MAX_TOKENS test builds its pending state differently, mirror *that*.)

Add to `EndToEndTest` (nessy-testing):

```java
@Test
void thinking_signatures_round_trip_through_the_final_state() {
  ScriptedModelProvider provider =
      ScriptedModelProvider.builder()
          .thinking("Let me think.")
          .thinkingSigned("sig-abc")
          .text("The answer is 4.")
          .endTurn()
          .build();
  Agent agent = Nessy.agent().provider(provider).model("fake-model").build();

  Reply reply = agent.converse().send("what is 2+2?");

  assertThat(reply.state().messages().getLast().content())
      .containsExactly(
          new ThinkingBlock("Let me think.", "sig-abc"), new TextBlock("The answer is 4."));
}
```

- [ ] **Step 2: Run to verify failure** — compilation errors on the new variants.

- [ ] **Step 3: Implement**

`Event` additions (validation in the house form):

```java
/** The provider finished a thinking block and delivered its signature. */
record ThinkingSigned(String signature) implements Event {
  public ThinkingSigned {
    Objects.requireNonNull(signature, "signature must not be null");
  }
}

/** A complete redacted-thinking block arrived; its contents are opaque by design. */
record RedactedThinkingArrived(String data) implements Event {
  public RedactedThinkingArrived {
    Objects.requireNonNull(data, "data must not be null");
  }
}
```

`ModelEvent` additions mirror: `ThinkingSigned(String signature)`, `RedactedThinkingEmitted(String data)`, same validation. `StopReason` gains `REFUSAL` with javadoc ("the model declined to continue; maps from Anthropic `refusal` and OpenAI `content_filter`").

`Reducer`: new arms in the exhaustive switch —

```java
case Event.ThinkingSigned(String signature) -> thinkingSigned(state, signature);
case Event.RedactedThinkingArrived(String data) ->
    Step.of(state.withPendingBlocks(appended(state.pendingBlocks(), new RedactedThinkingBlock(data))));
```

with `thinkingSigned` replacing the trailing block when it is a `ThinkingBlock` (pattern-match `getLast()`), else returning `Step.of(state)`; reuse the existing list-copy helper style. `modelTurnEnded`: generalize the token-ceiling branch —

```java
if (event.reason() == StopReason.MAX_TOKENS || event.reason() == StopReason.REFUSAL) {
  String reason =
      event.reason() == StopReason.MAX_TOKENS
          ? "model hit the token ceiling (MAX_TOKENS)"
          : "model refused to continue (REFUSAL)";
  return Step.of(
      flushResults(abandonPendingCalls(settled)).withFailureReason(reason).with(SessionStatus.FAILED));
}
```

`InProcessEngine.translate`: `case ModelEvent.ThinkingSigned(String signature) -> new Event.ThinkingSigned(signature);` and the redacted twin. `ScriptedModelProvider.Builder`: `thinkingSigned(String)` / `redactedThinking(String)` appending the new `ModelEvent`s to the current turn.

- [ ] **Step 4: Full verify** — `./mvnw -q clean verify` green (145 + 5 new), release profile green.
- [ ] **Step 5: Commit** — `feat(core): finish the thinking/refusal grammar before the freeze`

---

### Task 2: RetryingModelProvider — the dogfooded upgrade

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/model/RetryingModelProvider.java`, `RetryPolicy.java`, `Sleeper.java` (package-private)
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/spi/model/RetryingModelProviderTest.java`

**Interfaces:**
- Consumes: `ModelProvider`, `ModelStream`, `ModelRequest`.
- Produces (Tasks 6/9 rely on): `RetryingModelProvider.wrap(ModelProvider delegate, RetryPolicy policy, Predicate<RuntimeException> retryable)`; `RetryPolicy(int maxAttempts, Duration initialDelay, double multiplier)` with `RetryPolicy.defaults()` = `(3, Duration.ofMillis(500), 2.0)` and validation (attempts ≥ 1, delay positive, multiplier ≥ 1.0); capabilities delegate untouched. **Retry wraps the stream OPENING only** — once events flow, a mid-stream failure propagates (tokens already fed downstream cannot be un-fed); javadoc states this.

- [ ] **Step 1: Write the failing test**

```java
package org.jwcarman.nessy.spi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
// + imports for Message, ToolSpec, Capability, Set, Iterator per use below

class RetryingModelProviderTest {

  static final class FlakyProvider implements ModelProvider {
    int calls;
    final int failuresBeforeSuccess;
    final RuntimeException failure;

    FlakyProvider(int failuresBeforeSuccess, RuntimeException failure) {
      this.failuresBeforeSuccess = failuresBeforeSuccess;
      this.failure = failure;
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      calls++;
      if (calls <= failuresBeforeSuccess) {
        throw failure;
      }
      return new ModelStream() {
        @Override
        public java.util.Iterator<ModelEvent> iterator() {  // convert to explicit import
          return java.util.List.<ModelEvent>of().iterator();
        }

        @Override
        public void close() {}
      };
    }

    @Override
    public java.util.Set<Capability> capabilities() {
      return java.util.Set.of(Capability.THINKING);
    }
  }

  static final class RecordingSleeper implements Sleeper {
    final List<Duration> slept = new ArrayList<>();

    @Override
    public void sleep(Duration duration) {
      slept.add(duration);
    }
  }

  private static ModelRequest request() {
    return new ModelRequest(
        java.util.List.of(Message.user("hi")), "sys", "m", 100, java.util.List.of(), java.util.Set.of());
  }

  @Nested
  class Retrying {

    @Test
    void retries_retryable_failures_with_exponential_backoff() {
      FlakyProvider flaky = new FlakyProvider(2, new IllegalStateException("429"));
      RecordingSleeper sleeper = new RecordingSleeper();
      ModelProvider provider =
          new RetryingModelProvider(flaky, RetryPolicy.defaults(), e -> true, sleeper);

      provider.stream(request()).close();

      assertThat(flaky.calls).isEqualTo(3);
      assertThat(sleeper.slept)
          .containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000));
    }

    @Test
    void gives_up_after_max_attempts_and_rethrows_the_last_failure() {
      FlakyProvider flaky = new FlakyProvider(99, new IllegalStateException("still 429"));
      ModelProvider provider =
          new RetryingModelProvider(flaky, RetryPolicy.defaults(), e -> true, new RecordingSleeper());

      assertThatThrownBy(() -> provider.stream(request()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("still 429");
      assertThat(flaky.calls).isEqualTo(3);
    }

    @Test
    void non_retryable_failures_are_rethrown_immediately() {
      FlakyProvider flaky = new FlakyProvider(99, new IllegalArgumentException("bad request"));
      RecordingSleeper sleeper = new RecordingSleeper();
      ModelProvider provider =
          new RetryingModelProvider(flaky, RetryPolicy.defaults(), e -> false, sleeper);

      assertThatThrownBy(() -> provider.stream(request()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(flaky.calls).isEqualTo(1);
      assertThat(sleeper.slept).isEmpty();
    }
  }

  @Test
  void capabilities_pass_through_untouched() {
    ModelProvider provider =
        RetryingModelProvider.wrap(
            new FlakyProvider(0, new IllegalStateException("unused")),
            RetryPolicy.defaults(),
            e -> true);

    assertThat(provider.capabilities()).containsExactly(Capability.THINKING);
  }

  @Test
  void degenerate_policies_are_rejected() {
    assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofMillis(1), 2.0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RetryPolicy(3, Duration.ZERO, 2.0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofMillis(1), 0.5))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

(Tidy every inline-qualified reference into explicit imports when transcribing — house rule.)

- [ ] **Step 2: Verify failure** — `cannot find symbol: class RetryingModelProvider`.

- [ ] **Step 3: Implement**

`Sleeper.java` (package-private — a test seam, deliberately unadvertised):

```java
package org.jwcarman.nessy.spi.model;

import java.time.Duration;

/** Test seam: real time in production, recorded time in tests. Not part of the SPI. */
interface Sleeper {

  Sleeper REAL =
      duration -> {
        try {
          Thread.sleep(duration);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted while backing off", e);
        }
      };

  void sleep(Duration duration);
}
```

`RetryPolicy.java`: record with the validation above and `defaults()`. `RetryingModelProvider.java`:

```java
package org.jwcarman.nessy.spi.model;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Retries the OPENING of a model stream, with exponential backoff.
 *
 * <p>A decorator, not engine machinery — the upgrade path is {@code wrap(provider, …)} and nothing
 * else changes. Only the initial {@link ModelProvider#stream} call is retried: once events flow,
 * tokens have already been fed downstream and a mid-stream failure propagates, because a
 * transparent re-call would replay the turn from the top.
 *
 * <p>Which failures are retryable is provider-specific (a 429 is not an auth error), so each
 * provider module publishes its own predicate — see {@code AnthropicModelProvider#RETRYABLE} and
 * {@code OpenAiModelProvider#RETRYABLE}.
 */
public final class RetryingModelProvider implements ModelProvider {

  private final ModelProvider delegate;
  private final RetryPolicy policy;
  private final Predicate<RuntimeException> retryable;
  private final Sleeper sleeper;

  public static RetryingModelProvider wrap(
      ModelProvider delegate, RetryPolicy policy, Predicate<RuntimeException> retryable) {
    return new RetryingModelProvider(delegate, policy, retryable, Sleeper.REAL);
  }

  RetryingModelProvider(
      ModelProvider delegate,
      RetryPolicy policy,
      Predicate<RuntimeException> retryable,
      Sleeper sleeper) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    this.policy = Objects.requireNonNull(policy, "policy must not be null");
    this.retryable = Objects.requireNonNull(retryable, "retryable must not be null");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
  }

  @Override
  public ModelStream stream(ModelRequest request) {
    Duration delay = policy.initialDelay();
    for (int attempt = 1; ; attempt++) {
      try {
        return delegate.stream(request);
      } catch (RuntimeException e) {
        if (attempt >= policy.maxAttempts() || !retryable.test(e)) {
          throw e;
        }
        sleeper.sleep(delay);
        delay = Duration.ofNanos((long) (delay.toNanos() * policy.multiplier()));
      }
    }
  }

  @Override
  public Set<Capability> capabilities() {
    return delegate.capabilities();
  }
}
```

- [ ] **Step 4: Full verify green; release profile green.**
- [ ] **Step 5: Commit** — `feat(core): retrying model provider decorator`

---

### Task 3: Module scaffolding

**Files:**
- Modify: `pom.xml` (modules, `anthropic.version` = `2.52.0`, `openai.version` = discovered, dependencyManagement), `nessy-bom/pom.xml` (+2 artifacts)
- Create: `nessy-model-anthropic/pom.xml`, `nessy-model-openai/pom.xml`, each module's `src/test/resources/junit-platform.properties` (copied verbatim from nessy-core's), and one dependency smoke test per module
- Both module poms: dependency on `nessy-core` (+ their SDK), test deps on `nessy-testing`(? no — core test utilities not needed; JUnit/AssertJ inherited from parent), and maven-jar-plugin `Automatic-Module-Name` (`org.jwcarman.nessy.model.anthropic` / `.openai`)

**Interfaces:** Produces two empty-but-building modules; Tasks 4–9 fill them.

- [ ] **Step 1**: Discover the newest stable `com.openai:openai-java` (query search.maven.org or `mvn dependency:get` probes); record the version in your report and pin it as `openai.version`.
- [ ] **Step 2**: Write per-module smoke tests that fail to compile until the deps exist — Anthropic: construct `com.anthropic.client.okhttp.AnthropicOkHttpClient.builder()` type reference (do NOT build a client needing a key; assert the builder class loads); OpenAI: same shape against its client builder class. Name them `sdk_is_on_the_classpath`.
- [ ] **Step 3**: Wire poms; run `./mvnw -q clean verify` — whole reactor green, keyless.
- [ ] **Step 4**: license:format, spotless:apply, re-verify, commit — `build: scaffold provider modules`

---

### Task 4: Anthropic request assembly

**Files:**
- Create: `nessy-model-anthropic/src/main/java/org/jwcarman/nessy/model/anthropic/AnthropicRequests.java`, `AnthropicSchemas.java`
- Test: `.../anthropic/AnthropicRequestsTest.java`, `AnthropicSchemasTest.java`

**Interfaces:**
- Consumes: `ModelRequest` and the full `ContentBlock` grammar.
- Produces (Task 6 relies on): `static MessageCreateParams AnthropicRequests.toParams(ModelRequest request, ThinkingConfig thinking)` where `ThinkingConfig` is a small local record `(boolean enabled, int budgetTokens)`; `static Tool.InputSchema AnthropicSchemas.toInputSchema(ObjectNode schema)`.

**Mapping table (the contract — every row needs an assembly path and a test):**

| Nessy | Anthropic params |
|---|---|
| `systemPrompt` | `system` (as a system text block; with `cache_control: ephemeral` when `PROMPT_CACHING` requested) |
| `model`, `maxTokens` | `model`, `max_tokens` |
| `Message(USER, [TextBlock])` | user message, text block |
| `ImageBlock(mediaType, base64Data)` | user image block, base64 source |
| `Message(ASSISTANT, [TextBlock, ThinkingBlock, ToolUseBlock…])` | assistant message with text / **thinking(text, signature)** / tool_use blocks — the signature ROUND-TRIPS; an empty-string signature on replay means the transcript predates signing: send the thinking block only when its signature is non-empty, else drop it (Anthropic rejects unsigned thinking on replay) — javadoc this rule |
| `RedactedThinkingBlock(data)` | redacted_thinking block, data round-tripped |
| `ToolResultBlock(id, content, isError)` | user message tool_result block with `is_error` |
| `tools` (List<ToolSpec>) | `tools` via `AnthropicSchemas.toInputSchema` (properties + required + `$defs` — port `agentic-agency`'s `Schemas.of` conversion exactly; last tool gets `cache_control` when `PROMPT_CACHING` requested) |
| `requested THINKING` | `thinking = enabled(budgetTokens)`; **reject with `IllegalArgumentException` when `maxTokens <= budgetTokens`** (Anthropic requires headroom) |

TDD: tests construct `ModelRequest`s covering every row and assert against the built `MessageCreateParams` via its accessors (`params.system()`, `params.messages()`, `params.tools()`, `params.thinking()` — verify exact accessor names against the SDK; `DirectModelClient.java` in agentic-agency shows the params API in use). Offline, no client, no key. Include the negative test for the thinking-budget headroom rule and a test that an unsigned `ThinkingBlock` is dropped on replay while a signed one round-trips.

Commit — `feat(anthropic): request assembly`

---

### Task 5: Anthropic stream translation

**Files:**
- Create: `.../anthropic/AnthropicStream.java`
- Test: `.../anthropic/AnthropicStreamTest.java`

**Interfaces:**
- Consumes: the SDK's `RawMessageStreamEvent` family (verify exact names in the SDK; `StreamingModelClient.java` in agentic-agency handles the same stream).
- Produces (Task 6 relies on): `AnthropicStream implements ModelStream` wrapping the SDK's streaming response; constructor takes the SDK stream handle. Iteration translates lazily (do NOT buffer the whole turn).

**Translation table (the contract):**

| SDK event | ModelEvent |
|---|---|
| content_block_delta / text_delta | `TextChunk(text)` |
| content_block_delta / thinking_delta | `ThinkingChunk(text)` |
| content_block_delta / signature_delta (at thinking block end) | `ThinkingSigned(signature)` |
| content_block_start / redacted_thinking | `RedactedThinkingEmitted(data)` |
| content_block_start / tool_use → accumulate id+name; input_json_delta partials → accumulate JSON; content_block_stop | one `ToolUseEmitted(ToolCall(id, name, parsedArgs))` — parse the accumulated JSON with Jackson; an EMPTY accumulation parses as `{}` (zero-arg tools) |
| message_delta (stop_reason + output usage) + message_start (input usage) | one `TurnEnded(mappedReason, Usage(input, output))` emitted at stream end |
| stop_reason mapping | `end_turn`→END_TURN, `tool_use`→TOOL_USE, `max_tokens`→MAX_TOKENS, `stop_sequence`→END_TURN, `refusal`→REFUSAL; ANY unrecognized value → `IllegalStateException` naming it (fail loudly — this is the §14 wire audit made executable) |

TDD offline: build SDK event objects with their builders (they exist for every generated type; if a particular event resists construction, parse a JSON fixture using the SDK's own Jackson mapper — say which path you used in the report). Tests: a text-only turn; a thinking+signature turn; a two-tool turn with interleaved input_json_delta fragments; a zero-arg tool; usage arithmetic across message_start/message_delta; the unrecognized-stop-reason failure.

`close()` closes the SDK stream handle. Commit — `feat(anthropic): stream translation`

---

### Task 6: AnthropicModelProvider

**Files:**
- Create: `.../anthropic/AnthropicModelProvider.java`
- Test: `.../anthropic/AnthropicModelProviderTest.java` (offline), `.../anthropic/AnthropicLiveTest.java` (`@Tag("live")`)

**Interfaces:**
- Consumes: Tasks 2, 4, 5.
- Produces:
  - `AnthropicModelProvider.builder()` → `apiKey(String)` / `fromEnv()` (reads `ANTHROPIC_API_KEY`), `baseUrl(String)` (optional, for proxies), `thinkingBudget(int)` (default `8192`), `client(AnthropicClient)` (escape hatch for a preconfigured SDK client), `build()`
  - `capabilities()` = `{THINKING, PROMPT_CACHING, PARALLEL_TOOL_CALLS, IMAGE_INPUT}`
  - `stream(request)` = SDK streaming call through `AnthropicRequests.toParams(request, thinkingConfigFor(request))` wrapped in `AnthropicStream`
  - `public static final Predicate<RuntimeException> RETRYABLE` — true for the SDK's rate-limit and 5xx/overloaded exception types plus its IO-failure wrapper, false for auth/invalid-request (enumerate the SDK's exception hierarchy from its sources; test each classification)

Offline tests: builder validation (no key and no client → clear error; `fromEnv` missing env → clear error naming the variable), capabilities set, RETRYABLE classification per SDK exception type (construct the exceptions; the SDK exposes constructors/factories — verify).

Live tests (`@Tag("live")`, each starting `assumeTrue(System.getenv("ANTHROPIC_API_KEY") != null, "ANTHROPIC_API_KEY not set")`), using a cheap model (`claude-haiku-4-5-20251001`), small `maxTokens`:

```java
@Test
void a_real_conversation_answers() { /* Nessy.agent().provider(AnthropicModelProvider.builder().fromEnv().build()) … send("Reply with exactly: pong") → text contains "pong"; usage inputTokens > 0 */ }

@Test
void a_real_tool_call_round_trips() { /* AddTool from AgentFacadeTest shape; ask for 2+2 via the tool; assert reply text contains "4" and state has a ToolResultBlock */ }

@Test
void real_thinking_round_trips_with_a_signature() { /* requested capability THINKING via AgentBuilder.capabilities; assert final assistant message contains a ThinkingBlock with non-empty signature */ }
```

Write the live tests fully — they are the tinkering entry point; run them yourself only if the key is present in your environment (do not fail the task if absent; report which path occurred). Commit — `feat(anthropic): the provider`

---

### Task 7: OpenAI request assembly

**Files:**
- Create: `nessy-model-openai/src/main/java/org/jwcarman/nessy/model/openai/OpenAiRequests.java`
- Test: `.../openai/OpenAiRequestsTest.java`

**Interfaces:**
- Consumes: `ModelRequest`, grammar.
- Produces (Task 9): `static ChatCompletionCreateParams OpenAiRequests.toParams(ModelRequest request)` (verify the params type name against the discovered SDK version).

**Mapping table:**

| Nessy | Chat Completions |
|---|---|
| `systemPrompt` | leading `system` message |
| `Message(USER, [TextBlock])` | user message (string content) |
| `ImageBlock` | user content-part `image_url` with a `data:{mediaType};base64,{data}` URI |
| `Message(ASSISTANT, [TextBlock…, ToolUseBlock…])` | assistant message: concatenated text + `tool_calls[]` (id, function name, arguments as the raw JSON string) |
| `ThinkingBlock` / `RedactedThinkingBlock` | **dropped** — not representable on this wire; javadoc the rule |
| `ToolResultBlock(id, content, …)` | `tool` role message with `tool_call_id` (one message per result; error flag folds into the content — prefix `"ERROR: "` when `isError`) |
| `tools` | `tools[]` function specs, our ObjectNode schema passed as-is (**no `strict:true` in v1** — strict mode's all-required/nullable-union rules are a later, deliberate feature; javadoc) |
| streaming | always sets `stream_options.include_usage = true` so the final chunk carries usage |

TDD offline against params accessors. Include: multi-tool assistant turn; tool result ordering; image data-URI formation; thinking-drop. Commit — `feat(openai): request assembly`

---

### Task 8: OpenAI stream translation

**Files:**
- Create: `.../openai/OpenAiStream.java`
- Test: `.../openai/OpenAiStreamTest.java`

**Interfaces:**
- Consumes: the SDK's chat-completion chunk type.
- Produces (Task 9): `OpenAiStream implements ModelStream` over the SDK's streaming response; lazy translation.

**Translation table:**

| Chunk content | ModelEvent |
|---|---|
| `delta.content` text | `TextChunk` |
| `delta.tool_calls[i]` fragments (index-keyed: first fragment carries id+name; later ones append `arguments`) | accumulate per index; on finish emit `ToolUseEmitted` per index **in index order**, args parsed via Jackson (empty → `{}`) |
| `finish_reason` | `stop`→END_TURN, `length`→MAX_TOKENS, `tool_calls`→TOOL_USE, `content_filter`→REFUSAL; unrecognized → `IllegalStateException` naming it |
| final usage chunk (`stream_options.include_usage`) | folded into the single `TurnEnded(reason, Usage(prompt, completion))`; a stream that never delivers usage yields `Usage.zero()` (some OpenAI-compatible servers omit it — tolerate, javadoc) |

TDD offline with SDK-built chunk objects (or JSON fixtures through the SDK mapper — report which). Tests: text turn; two interleaved tool calls; missing-usage tolerance; each finish_reason; unrecognized-reason failure. Commit — `feat(openai): stream translation`

---

### Task 9: OpenAiModelProvider

**Files:**
- Create: `.../openai/OpenAiModelProvider.java`
- Test: offline `OpenAiModelProviderTest.java` + `OpenAiLiveTest.java` (`@Tag("live")`, key `OPENAI_API_KEY`)

**Interfaces:**
- Produces:
  - `OpenAiModelProvider.builder()` → `apiKey(String)` / `fromEnv()` (`OPENAI_API_KEY`), **`baseUrl(String)`** (the breadth feature: OpenRouter `https://openrouter.ai/api/v1`, Ollama `http://localhost:11434/v1`, …), `organization(String)` optional, `client(...)` escape hatch, `build()`
  - `capabilities()` = `{PARALLEL_TOOL_CALLS, IMAGE_INPUT}` — THINKING and PROMPT_CACHING deliberately absent (explicit degrade is the design; javadoc why: no request-side cache control on this wire; reasoning models not surfaced via chat-completions deltas)
  - `RETRYABLE` predicate per the SDK's exception hierarchy, classification-tested
- Offline tests mirror Task 6's (builder validation, capabilities, RETRYABLE).
- Live tests: `a_real_conversation_answers` (gpt-4o-mini or the SDK's current cheap default — pick and record), `a_real_tool_call_round_trips`. Add a `@Disabled("manual: point at a local or OpenRouter endpoint")` example test showing `baseUrl(...)` usage so tinkering has a template.

Commit — `feat(openai): the provider`

---

### Task 10: Documentation

**Files:** `README.md`, `CHANGELOG.md`, spec `§14` check-off note.

- README: Status section moves both providers to "built"; a "Try it" section documents the two example-app commands (Task 11 runs first); the five-minute example gets a sibling **real** variant (Anthropic, `fromEnv()`, with the "set ANTHROPIC_API_KEY" sentence and a cost warning); a short "Other endpoints" note showing `OpenAiModelProvider.builder().baseUrl("https://openrouter.ai/api/v1")`. Every new code block compile-verified via the scratch-file method (mandatory; delete scratch before commit).
- CHANGELOG Unreleased: providers, retry decorator, grammar completions (REFUSAL, thinking signatures, redacted thinking).
- Spec: add one line to §14 marking Plan 3 delivered and the `StopReason` wire audit performed (the fail-loudly mapping in Tasks 5/8 is the audit's executable form).
- Full reactor + release profile green; commit — `docs: providers are real`

---

### Task 11: Example apps — one agent, two providers (runs BEFORE Task 10)

**Files:**
- Create: `nessy-examples/pom.xml` (module: depends on nessy-core + both providers; `<maven.deploy.skip>true</maven.deploy.skip>` — examples are never published; NOT added to the BOM)
- Create: `nessy-examples/src/main/java/org/jwcarman/nessy/examples/DemoAgent.java`, `ConsoleApprover.java`, `AnthropicChat.java`, `OpenAiChat.java`
- Modify: parent `pom.xml` modules list

**Interfaces:**
- Consumes: `Nessy.agent()`, both provider builders, the hub, `Approver`.
- Produces: two runnable mains where the ONLY setup is an env var:
  - `ANTHROPIC_API_KEY=… ./mvnw -q -pl nessy-examples compile exec:java -Dexec.mainClass=org.jwcarman.nessy.examples.AnthropicChat`
  - `OPENAI_API_KEY=… ./mvnw -q -pl nessy-examples compile exec:java -Dexec.mainClass=org.jwcarman.nessy.examples.OpenAiChat`
  (add exec-maven-plugin to the module pom, version property matching agentic-agency's `3.6.3`)

**The shape (complete except for provider lines, which differ per main):**

- `DemoAgent.agentFor(ModelProvider provider, String model)` — the SHARED definition: system prompt ("You are Nessy's demo assistant…"), two tools (`AddTool` — record-schema arithmetic; `ClockTool` — returns `java.time.ZonedDateTime.now()` as text, demonstrating a side-effect-free tool), `ConsoleApprover`, and a hub subscription wired by the mains.
- `ConsoleApprover implements Approver`: prints the approval request's description, reads `y`/`n` from the console (`java.lang.IO.readln` — Java 25, matching agentic-agency's idiom), returns `Awaited.ready(Decision.allow())` or a `Deny("declined at the console")`. Set `AddTool.requiresApproval() = false`, `ClockTool.requiresApproval() = true` so every run demonstrates the gate at least once when the user asks the time.
- Each main: read its env var (missing → print one friendly line naming the variable and exit 1 — never a stack trace), build its provider (`AnthropicModelProvider.builder().fromEnv().build()` / `OpenAiModelProvider.builder().fromEnv().build()`), pick a cheap model (`claude-haiku-4-5-20251001` / the OpenAI cheap default recorded in Task 9), subscribe `SessionEvent` on `agent.events()` printing `TextDelta` text as it arrives (streaming to stdout, no newline until turn end) and a `⚙ tool: name` line on `ToolCallRequested`, then loop: `IO.readln("you> ")` → `conversation.send(...)` → newline; empty input or `/quit` exits. On a `failed()` reply, print the failure reason.
- No tests beyond compilation (mains are live-by-nature); the module inherits the prose-test properties file anyway for future use. The default build must stay keyless-green: `./mvnw -q clean verify` compiles the module with zero tests executed against a network.

Execution note: this task runs after Task 9 and BEFORE Task 10, so Task 10's README documents real, existing examples (a "Try it" section with the two commands above, compile-verified like all README code).

Commit — `feat: example chat apps for both providers`

---

## Self-Review

**Spec coverage:** §7 signature-delivery finalization → Task 1; §10.2 builder factories + capability honesty → Tasks 6/9; §13 retry decorator as ModelProvider upgrade → Task 2; §14 Plan-3 scope (both providers, retry+Sleeper, StopReason audit) → Tasks 3–9, audit as fail-loudly mappings + Task 10 note; grammar freeze risk §15 → all sealed additions land here, pre-freeze. Deliberately absent, per spec sequencing: Policy (Plan 2.5 → next), DurableEngine, compactor/ContextBuilder, starter, TUI, MCP.

**Placeholder scan:** the SDK-facing tasks specify contracts, tables, exact our-side signatures, and named working reference code rather than fabricated SDK chains — per the Reference-code policy in the header, which is this plan's honest form of "actual content the engineer needs." Our-side code is complete and verbatim.

**Type consistency:** `ThinkingSigned`/`RedactedThinkingEmitted`/`RedactedThinkingArrived`/`REFUSAL` (Task 1) are used identically in Tasks 5, 6, 8; `RetryingModelProvider.wrap`/`RetryPolicy.defaults()`/package-private `Sleeper` (Task 2) match Tasks 6/9's `RETRYABLE` usage; `AnthropicRequests.toParams(request, ThinkingConfig)`/`AnthropicSchemas.toInputSchema`/`AnthropicStream` (4/5) match Task 6's wiring; `OpenAiRequests.toParams`/`OpenAiStream` (7/8) match Task 9.
