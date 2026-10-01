# Anthropic: Drop Mismatched Thinking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Anthropic request that replays thinking is answered, not rejected with a 400, when the content before that thinking has changed since it was produced.

**Architecture:** Whenever the Anthropic adapter turns thinking on, it also sends `thinking.block_binding.prefix_mismatch_behavior = "drop_block"` and the beta header that setting needs. Anthropic then drops only the thinking blocks whose prefix changed and answers normally. Nothing about how Nessy lays out a request changes. The reply names each dropped block, and the adapter logs how many. A projection test pins that a turn growing by one exchange renders as an extension of the previous request.

**Tech Stack:** Java 25, Maven reactor, anthropic-java 2.65.0 (`com.anthropic.models.messages`), JUnit 5, AssertJ.

**Spec:** None; this is a bug fix. The evidence is the probe record at <https://claude.ai/code/artifact/5af58745-9bb1-4f4d-9655-89bb00d739ff> (sections "Anthropic thinking: what the prefix check rejects" and "The `drop_block` setting"), with scripts and raw results in `~/IdeaProjects/nessy-context-probes/`. James approved the beta-header dependency and the behaviour change for older accounts on 2026-10-01.

## Why

On Claude Fable 5.1, Opus 5.5 and Sonnet 5.5, a replayed thinking block is valid only while the system prompt, the tools and every earlier message are unchanged. Accounts created on or after 2026-08-31 get `400 invalid_request_error` on a mismatch. Nessy changes that prefix routinely: every ambient section is a system block, so a plan or notebook update changes the system prompt, and the tail slides by one turn once it is full. Reproduced on all three models on 2026-10-01.

Measured on the same day with `drop_block` set: every one of those requests returned 200, the API dropped only the mismatched blocks and named each in `input_transformations`, and requests with an unchanged prefix dropped nothing. The setting plus the header was accepted on Fable 5.1, Opus 5.5, Sonnet 5.5, Opus 5 and Sonnet 5 with adaptive thinking, and on Haiku 4.5 with budgeted thinking. Without the header the setting is itself a 400 (`Extra inputs are not permitted`). On a streamed reply, which is the only kind Nessy asks for, the report arrives on the `message_start` event, as `message.input_transformations`: a list of `{type: "thinking_dropped", path, reason: "prefix_binding_mismatch"}`.

## Global Constraints

- Never suppress a warning: no `@SuppressWarnings`, no equivalent. Fix the cause.
- No star imports, regular or static. No fully-qualified type names in code; add an import.
- Tests use snake_case method names. `@Nested` classes in `AnthropicRequestsTest` are PascalCase; match that file.
- An exception-assertion lambda contains exactly one invocation that can throw.
- Assert a collection is not empty before any all-match or none-match assertion on it.
- No mocking library.
- Every source file carries the Apache header; formatting is google-java-format. Run `./mvnw spotless:apply license:format` before the final gate.
- Select modules by artifactId with the colon: `-pl :nessy-inference-anthropic`. Pass `-am`.
- Never run two Maven processes at once in one worktree.
- Run `pgrep -fl nessy-example` in the same command as every Maven build. If it prints anything, stop and report; do not build.
- The full gate is `./mvnw -q clean verify`, run once, before the last commit. It must pass with no API key and no network.
- Check Maven's exit code. Do not grep its output for success.
- Work only in the worktree `/Users/jcarman/IdeaProjects/nessy-drop-block`, on branch `fix/anthropic-drop-mismatched-thinking`. Never touch `/Users/jcarman/IdeaProjects/nessy`. Do not push.
- End every commit message with: `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`

## Not in this plan

- A metric for dropped thinking. This plan logs it; counting it belongs with the observability work.
- Moving ambient out of the system prompt, stepped trimming, cache breakpoint placement. Those belong to the context-layout work.
- Any change to the Bedrock adapter.

## Review Focus

- **Thinking turned off by an agent type over a provider that thinks.** Neither the setting nor the header may be sent; a request without thinking must go out exactly as it does today. Pinned in Task 1.
- **Budgeted thinking.** The setting must ride on the `enabled` config as well as the `adaptive` one; Haiku 4.5 only accepts the former. Pinned in Task 1.
- **A deployment that already sends its own `anthropic-beta` header.** Ours must be added beside it, not replace it. At the params level this is `putAdditionalHeader`, which appends; pinned in Task 1 by asserting the header's values contain ours.
- **A request built for a summariser, which never calls `validate`.** It goes through the same `toParams`, so it is covered by Task 1's tests on `toParams`.
- **A tool loop.** The next call of a turn must render as the previous call's messages plus the new exchange, or even an unchanged prefix would count as edited. Pinned in Task 3.
- **A reply that reports a transformation of another kind** (`thinking_mismatch_allowed`, which older accounts can return). It must not be counted as a drop. Pinned in Task 2.

---

### Task 1: Thinking requests ask for mismatched blocks to be dropped

**Files:**
- Modify: `nessy-inference/anthropic/src/main/java/org/jwcarman/nessy/inference/anthropic/AnthropicRequests.java` (the thinking block in `toParams`, lines 117-122, and new constants beside `LOOKBACK_BLOCKS`)
- Test: `nessy-inference/anthropic/src/test/java/org/jwcarman/nessy/inference/anthropic/AnthropicRequestsTest.java` (a new `@Nested` class after `ExtendedThinking`)
- Modify: `CHANGELOG.md` (under `## [Unreleased]`)

**Interfaces:**
- Consumes: `AnthropicRequests.toParams(InferenceRequest, Map<String, String>, JsonMapper)`, unchanged in signature.
- Produces: nothing new for later tasks. The observable result is on `MessageCreateParams`: the thinking config's `_additionalProperties()` holds `block_binding`, and `_additionalHeaders().values("anthropic-beta")` holds the beta name.

- [ ] **Step 1: Confirm the worktree and branch**

```bash
cd /Users/jcarman/IdeaProjects/nessy-drop-block
git status --short            # expected: nothing
git branch --show-current     # expected: fix/anthropic-drop-mismatched-thinking
```

- [ ] **Step 2: Write the failing tests**

In `AnthropicRequestsTest.java`, add this import beside the other `com.anthropic` imports:

```java
import com.anthropic.core.JsonValue;
```

Add this nested class immediately after the closing brace of `class ExtendedThinking`:

```java
  /**
   * Anthropic binds a thinking block to everything that came before it, and rejects the request
   * when that prefix has changed. Asked to, it drops the block instead and answers.
   */
  @Nested
  class WhenThePrefixChangesUnderReplayedThinking {

    private static final JsonValue DROP_MISMATCHED =
        JsonValue.from(Map.of("prefix_mismatch_behavior", "drop_block"));

    private static MessageCreateParams thinkingWith(Map<String, String> properties) {
      return AnthropicRequests.toParams(
          new InferenceRequest(
              SYSTEM,
              InferenceContext.of(List.of(open(1, "hi"))),
              Toolset.none(),
              new InferenceOptions("claude-sonnet", 2048, properties)),
          NONE,
          MAPPER);
    }

    @Test
    void adaptive_thinking_asks_for_mismatched_blocks_to_be_dropped() {
      MessageCreateParams params = thinkingWith(Map.of("anthropic.thinking.type", "adaptive"));

      assertThat(params.thinking().orElseThrow().asAdaptive()._additionalProperties())
          .containsEntry("block_binding", DROP_MISMATCHED);
    }

    /** Haiku 4.5 takes a budget and refuses adaptive, so the setting has to ride on both. */
    @Test
    void budgeted_thinking_asks_for_the_same() {
      MessageCreateParams params =
          thinkingWith(Map.of("anthropic.thinking.budget_tokens", "512"));

      assertThat(params.thinking().orElseThrow().asEnabled()._additionalProperties())
          .containsEntry("block_binding", DROP_MISMATCHED);
      assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(512L);
    }

    /** Measured 2026-10-01: without the header the setting is itself a 400. */
    @ParameterizedTest
    @ValueSource(
        strings = {"anthropic.thinking.type=adaptive", "anthropic.thinking.budget_tokens=512"})
    void thinking_brings_the_beta_header_the_setting_needs(String property) {
      String[] pair = property.split("=");

      MessageCreateParams params = thinkingWith(Map.of(pair[0], pair[1]));

      assertThat(params._additionalHeaders().values("anthropic-beta"))
          .contains("thinking-binding-controls-2026-08-01");
    }

    @Test
    void a_request_that_does_not_think_sends_neither() {
      MessageCreateParams params = thinkingWith(Map.of());

      assertThat(params.thinking()).isEmpty();
      assertThat(params._additionalHeaders().names()).doesNotContain("anthropic-beta");
    }

    @Test
    void thinking_switched_off_by_the_agent_type_sends_neither() {
      MessageCreateParams params =
          AnthropicRequests.toParams(
              new InferenceRequest(
                  SYSTEM,
                  InferenceContext.of(List.of(open(1, "hi"))),
                  Toolset.none(),
                  new InferenceOptions(
                      "claude-sonnet", 2048, Map.of("anthropic.thinking.type", "disabled"))),
              Map.of("anthropic.thinking.type", "adaptive"),
              MAPPER);

      assertThat(params.thinking()).isEmpty();
      assertThat(params._additionalHeaders().names()).doesNotContain("anthropic-beta");
    }
  }
```

- [ ] **Step 3: Run the tests and see them fail**

```bash
cd /Users/jcarman/IdeaProjects/nessy-drop-block
./mvnw -q spotless:apply license:format
pgrep -fl nessy-example; ./mvnw -q -pl :nessy-inference-anthropic -am test -Dtest=AnthropicRequestsTest -Dsurefire.failIfNoSpecifiedTests=false; echo "exit=$?"
```

Expected: `exit=1`. `adaptive_thinking_asks_for_mismatched_blocks_to_be_dropped`, `budgeted_thinking_asks_for_the_same` and both cases of `thinking_brings_the_beta_header_the_setting_needs` fail (the map has no `block_binding`; the header has no values). The two "sends neither" tests pass already. (`spotless:check` runs before compilation, which is why the format step comes first: an unformatted file fails the build with no compiler output.)

- [ ] **Step 4: Implement**

In `AnthropicRequests.java`, add these constants immediately after the `LOOKBACK_BLOCKS` declaration:

```java
  /**
   * What makes a changed prefix survivable.
   *
   * <p>On Fable 5.1, Opus 5.5 and Sonnet 5.5 a thinking block is bound to the system prompt, the
   * tools and every message before it, and a request that replays one after any of those changed is
   * rejected -- by default on accounts created since 2026-08-31. Nessy changes them as a matter of
   * course: background is part of the system prompt, and the tail slides. Asked this way, the
   * vendor drops the blocks that no longer fit and answers; a block whose prefix is intact is kept.
   * Measured 2026-10-01 on all three models.
   */
  private static final String BLOCK_BINDING = "block_binding";

  private static final JsonValue DROP_MISMATCHED =
      JsonValue.from(Map.of("prefix_mismatch_behavior", "drop_block"));

  /** The setting is refused outright without this: "Extra inputs are not permitted". */
  private static final String BETA_HEADER = "anthropic-beta";

  private static final String THINKING_BINDING_BETA = "thinking-binding-controls-2026-08-01";
```

Replace this block in `toParams`:

```java
    if (read.enabled()) {
      builder.thinking(
          ThinkingConfigEnabled.builder().budgetTokens(read.budget().getAsInt()).build());
    } else if (read.thinking().filter(AnthropicThinkingType.ADAPTIVE::equals).isPresent()) {
      builder.thinking(ThinkingConfigAdaptive.builder().build());
    }
```

with:

```java
    if (read.enabled()) {
      builder.thinking(
          ThinkingConfigEnabled.builder()
              .budgetTokens(read.budget().getAsInt())
              .putAdditionalProperty(BLOCK_BINDING, DROP_MISMATCHED)
              .build());
      builder.putAdditionalHeader(BETA_HEADER, THINKING_BINDING_BETA);
    } else if (read.thinking().filter(AnthropicThinkingType.ADAPTIVE::equals).isPresent()) {
      builder.thinking(
          ThinkingConfigAdaptive.builder()
              .putAdditionalProperty(BLOCK_BINDING, DROP_MISMATCHED)
              .build());
      builder.putAdditionalHeader(BETA_HEADER, THINKING_BINDING_BETA);
    }
```

`JsonValue` and `Map` are already imported in this file. Do not touch anything else in it.

- [ ] **Step 5: Run the module's tests and see them pass**

```bash
cd /Users/jcarman/IdeaProjects/nessy-drop-block
./mvnw -q spotless:apply license:format
pgrep -fl nessy-example; ./mvnw -q -pl :nessy-inference-anthropic -am test; echo "exit=$?"
```

Expected: `exit=0`. In particular the existing `disabled_sends_no_thinking_object_over_a_provider_that_thinks` still passes: it asserts `_additionalBodyProperties()` is empty, and this change adds a property to the thinking config, not to the request body.

- [ ] **Step 6: Add the changelog entry**

In `CHANGELOG.md`, under `## [Unreleased]`, add a `### Fixed` section if there is none (after `### Added` and any `### Changed`), and put this entry in it:

```markdown
- **Anthropic: a changed prefix no longer rejects a request that replays
  thinking.** With thinking on, the adapter now sends
  `thinking.block_binding.prefix_mismatch_behavior: drop_block` and the
  `thinking-binding-controls-2026-08-01` beta header. Claude Fable 5.1,
  Opus 5.5 and Sonnet 5.5 bind a thinking block to everything before it, and
  accounts created since 2026-08-31 got a 400 whenever background changed the
  system prompt or the tail moved. The vendor now drops the thinking blocks
  that no longer fit and answers. On older accounts, where such blocks used
  to reach the model unchanged, they are dropped too.
```

- [ ] **Step 7: Commit**

```bash
cd /Users/jcarman/IdeaProjects/nessy-drop-block
./mvnw -q spotless:apply license:format
git add nessy-inference/anthropic/src/main/java/org/jwcarman/nessy/inference/anthropic/AnthropicRequests.java \
        nessy-inference/anthropic/src/test/java/org/jwcarman/nessy/inference/anthropic/AnthropicRequestsTest.java \
        CHANGELOG.md
git commit -m "fix: Anthropic drops thinking whose prefix changed instead of rejecting the request

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Dropped thinking is logged

**Files:**
- Modify: `nessy-inference/anthropic/src/main/java/org/jwcarman/nessy/inference/anthropic/AnthropicInferenceProvider.java` (a logger, two constants, two private methods, and one line in `infer`'s event loop)
- Test: `nessy-inference/anthropic/src/test/java/org/jwcarman/nessy/inference/anthropic/AnthropicInferenceProviderTest.java` (a new `@Nested` class, added as the last one in the file)
- Modify: `CHANGELOG.md` (one sentence appended to Task 1's entry)

**Interfaces:**
- Consumes: the test helpers already in `AnthropicInferenceProviderTest`: `reply()`, `text(String)`, `inferAnswering(Message)`, and `LogCapture.during(Class<?>, Runnable)`.
- Produces: nothing for later tasks.

The report rides on the first stream event, not on the folded message, so it is read there. The pinned SDK has no typed accessor for it; it is an additional property on the `Message` inside `message_start`.

DEBUG is deliberate. Until background leaves the system prompt, an agent with a plan or a notebook will have thinking dropped on most calls, and a line per call at INFO would be noise.

- [ ] **Step 1: Write the failing tests**

In `AnthropicInferenceProviderTest.java`, add this import beside the other `ch.qos.logback` import:

```java
import ch.qos.logback.classic.Level;
```

Add this nested class as the last nested class in the file, immediately before the closing brace of `class AnthropicInferenceProviderTest`:

```java
  /**
   * Asked to, the vendor drops thinking whose prefix changed and says so on the reply. That is the
   * model losing reasoning it did earlier, which whoever is reading a turn's log will want to see.
   */
  @Nested
  class WhenTheVendorDropsThinking {

    private static Map<String, String> transformation(String type, String path) {
      return Map.of("type", type, "path", path, "reason", "prefix_binding_mismatch");
    }

    private static Message reporting(List<Map<String, String>> transformations) {
      return reply()
          .addContent(text("ok"))
          .putAdditionalProperty("input_transformations", JsonValue.from(transformations))
          .build();
    }

    private static List<ILoggingEvent> loggedWhileAnswering(Message message) {
      return LogCapture.during(AnthropicInferenceProvider.class, () -> inferAnswering(message));
    }

    @Test
    void the_number_dropped_is_logged_at_debug() {
      Message message =
          reporting(
              List.of(
                  transformation("thinking_dropped", "messages.1.content.0"),
                  transformation("thinking_dropped", "messages.3.content.0")));

      List<ILoggingEvent> logged = loggedWhileAnswering(message);

      assertThat(logged).hasSize(1);
      assertThat(logged.getFirst().getLevel()).isEqualTo(Level.DEBUG);
      assertThat(logged.getFirst().getFormattedMessage()).contains("dropped 2 thinking block(s)");
    }

    @Test
    void a_reply_that_reports_nothing_logs_nothing() {
      List<ILoggingEvent> logged =
          loggedWhileAnswering(reply().addContent(text("ok")).build());

      assertThat(logged).isEmpty();
    }

    /** An older account can report a mismatch it let through; that is not a drop. */
    @Test
    void a_transformation_of_another_kind_is_not_counted() {
      Message message =
          reporting(
              List.of(transformation("thinking_mismatch_allowed", "messages.1.content.0")));

      assertThat(loggedWhileAnswering(message)).isEmpty();
    }

    @Test
    void the_answer_is_unaffected_by_the_report() {
      Message message =
          reporting(List.of(transformation("thinking_dropped", "messages.1.content.0")));

      assertThat(inferAnswering(message)).isInstanceOf(InferenceResult.Answer.class);
    }
  }
```

- [ ] **Step 2: Run the tests and see the first one fail**

```bash
cd /Users/jcarman/IdeaProjects/nessy-drop-block
./mvnw -q spotless:apply license:format
pgrep -fl nessy-example; ./mvnw -q -pl :nessy-inference-anthropic -am test -Dtest=AnthropicInferenceProviderTest -Dsurefire.failIfNoSpecifiedTests=false; echo "exit=$?"
```

Expected: `exit=1`, with `the_number_dropped_is_logged_at_debug` failing on `hasSize(1)` (nothing is logged yet). The other three pass already.

- [ ] **Step 3: Implement**

In `AnthropicInferenceProvider.java`, add these imports in their sorted places:

```java
import com.anthropic.core.JsonArray;
import com.anthropic.core.JsonObject;
import com.anthropic.core.JsonString;
import com.anthropic.core.JsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

Add these fields immediately after the `VENDOR` constant:

```java
  private static final Logger log = LoggerFactory.getLogger(AnthropicInferenceProvider.class);

  /**
   * Where the vendor says what it did to the request before the model saw it. Not a field the
   * pinned SDK knows, so it is read as an additional property.
   */
  private static final String INPUT_TRANSFORMATIONS = "input_transformations";

  private static final String THINKING_DROPPED = "thinking_dropped";
```

In `infer`, replace:

```java
              event -> {
                any[0] = true;
                accumulator.accumulate(event);
                narrate(event, narrator);
              });
```

with:

```java
              event -> {
                any[0] = true;
                accumulator.accumulate(event);
                narrate(event, narrator);
                reportDroppedThinking(event);
              });
```

Add these two methods immediately after `narrate`:

```java
  /**
   * Says so when the vendor dropped thinking whose prefix had changed since it was produced.
   *
   * <p>{@link AnthropicRequests} asks for exactly that in place of a rejection, and the price is
   * reasoning the model did earlier and no longer has. The report rides on the first event of the
   * stream, not on the folded message. At debug, because until background leaves the system prompt
   * this happens on most calls of an agent that has any.
   */
  private static void reportDroppedThinking(RawMessageStreamEvent event) {
    if (!event.isMessageStart()) {
      return;
    }
    JsonValue reported =
        event.asMessageStart().message()._additionalProperties().get(INPUT_TRANSFORMATIONS);
    if (!(reported instanceof JsonArray transformations)) {
      return;
    }
    long dropped =
        transformations.values().stream()
            .filter(AnthropicInferenceProvider::isDroppedThinking)
            .count();
    if (dropped > 0) {
      log.debug(
          "NESSY INFERENCE: Anthropic dropped {} thinking block(s) whose prefix had changed"
              + " since they were produced",
          dropped);
    }
  }

  private static boolean isDroppedThinking(JsonValue transformation) {
    return transformation instanceof JsonObject object
        && object.values().get("type") instanceof JsonString type
        && THINKING_DROPPED.equals(type.value());
  }
```

- [ ] **Step 4: Run the module's tests and see them pass**

```bash
cd /Users/jcarman/IdeaProjects/nessy-drop-block
./mvnw -q spotless:apply license:format
pgrep -fl nessy-example; ./mvnw -q -pl :nessy-inference-anthropic -am test; echo "exit=$?"
```

Expected: `exit=0`.

- [ ] **Step 5: Extend the changelog entry**

In `CHANGELOG.md`, append this sentence to the end of the entry Task 1 added (after "they are dropped too."):

```markdown
  The adapter logs how many blocks were dropped, at DEBUG.
```

- [ ] **Step 6: Commit**

```bash
cd /Users/jcarman/IdeaProjects/nessy-drop-block
git add nessy-inference/anthropic/src/main/java/org/jwcarman/nessy/inference/anthropic/AnthropicInferenceProvider.java \
        nessy-inference/anthropic/src/test/java/org/jwcarman/nessy/inference/anthropic/AnthropicInferenceProviderTest.java \
        CHANGELOG.md
git commit -m "feat: the Anthropic adapter logs thinking the vendor dropped for a changed prefix

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: A turn that grows renders as an extension of the last request

**Files:**
- Test: `nessy-inference/anthropic/src/test/java/org/jwcarman/nessy/inference/anthropic/AnthropicRequestsTest.java` (a new `@Nested` class after `WhenThePrefixChangesUnderReplayedThinking`, which Task 1 adds)

**Interfaces:**
- Consumes: the test helpers already in `AnthropicRequestsTest`: `asked(long, String)`, `thinking(String, String)`, `params(List<Turn>)`.
- Produces: nothing for later tasks.

This task adds a test only. It passes against the code as it stands; its job is to fail the day a change makes the adapter re-render earlier messages inside a turn, which Anthropic would count as an edit under every thinking block that follows.

- [ ] **Step 1: Write the test**

Add this nested class immediately after the closing brace of `class WhenThePrefixChangesUnderReplayedThinking`:

```java
  /**
   * Inside one turn, each call must be the last one plus what happened since. Anything else is an
   * edit to the prefix, and an edit costs the model the reasoning it did earlier in the turn.
   */
  @Nested
  class ATurnThatGrows {

    private static final Block.ToolCall LOOKUP =
        new Block.ToolCall(new CallId("call_1"), new ToolName("lookup"), "{\"q\":\"loch ness\"}");

    private static Turn asking() {
      return new Turn(new TurnId(1), asked(1, "how deep is it?"), List.of(), null, 0);
    }

    private static Turn afterOneLookup() {
      return new Turn(
          new TurnId(1),
          asked(1, "how deep is it?"),
          List.of(
              new Exchange(
                  new Seq(2),
                  List.of(thinking("check the survey first", "sig-1"), LOOKUP),
                  List.of(
                      new ToolOutcome.Succeeded(
                          new CallId("call_1"), List.of(new Block.Text("1412 metres")))))),
          null,
          0);
    }

    @Test
    void the_next_call_starts_with_every_message_of_the_last_one() {
      List<MessageParam> before = params(List.of(asking())).messages();
      List<MessageParam> after = params(List.of(afterOneLookup())).messages();

      assertThat(before).isNotEmpty();
      assertThat(after).hasSizeGreaterThan(before.size());
      assertThat(after.subList(0, before.size())).isEqualTo(before);
    }

    @Test
    void the_turn_in_flight_keeps_the_reasoning_it_did_before_the_call() {
      List<ContentBlockParam> asked =
          params(List.of(afterOneLookup())).messages().get(1).content().asBlockParams();

      assertThat(asked).hasSize(2);
      assertThat(asked.getFirst().asThinking().signature()).isEqualTo("sig-1");
      assertThat(asked.getLast().asToolUse().id()).isEqualTo("call_1");
    }

    /** An earlier turn is history by then, and must not be re-rendered either. */
    @Test
    void a_finished_turn_before_it_is_rendered_the_same_on_both_calls() {
      Turn earlier = answered(1, "what is the loch called?", "Loch Ness");
      Turn open = new Turn(new TurnId(2), asked(3, "how deep is it?"), List.of(), null, 0);
      Turn grown =
          new Turn(
              new TurnId(2),
              asked(3, "how deep is it?"),
              List.of(
                  new Exchange(
                      new Seq(4),
                      List.of(LOOKUP),
                      List.of(
                          new ToolOutcome.Succeeded(
                              new CallId("call_1"), List.of(new Block.Text("1412 metres")))))),
              null,
              0);

      List<MessageParam> before = params(List.of(earlier, open)).messages();
      List<MessageParam> after = params(List.of(earlier, grown)).messages();

      assertThat(before).hasSize(3);
      assertThat(after.subList(0, before.size())).isEqualTo(before);
    }
  }
```

- [ ] **Step 2: Run it and see it pass**

```bash
cd /Users/jcarman/IdeaProjects/nessy-drop-block
./mvnw -q spotless:apply license:format
pgrep -fl nessy-example; ./mvnw -q -pl :nessy-inference-anthropic -am test -Dtest=AnthropicRequestsTest -Dsurefire.failIfNoSpecifiedTests=false; echo "exit=$?"
```

Expected: `exit=0`. If `the_next_call_starts_with_every_message_of_the_last_one` or `a_finished_turn_before_it_is_rendered_the_same_on_both_calls` fails, do not change the test to make it pass: the adapter is re-rendering earlier messages, which is a finding. Stop and report it with the assertion output.

- [ ] **Step 3: Prove the test can fail**

Temporarily change `params(List.of(asking()))` in the first test to `params(List.of(answered(1, "something else", "an answer")))`, run the command from Step 2, and confirm `exit=1` with a failure on `isEqualTo(before)`. Then restore the line and confirm `exit=0` again.

- [ ] **Step 4: Commit**

```bash
cd /Users/jcarman/IdeaProjects/nessy-drop-block
./mvnw -q spotless:apply license:format
git add nessy-inference/anthropic/src/test/java/org/jwcarman/nessy/inference/anthropic/AnthropicRequestsTest.java
git commit -m "test: a turn that grows renders on Anthropic's wire as the last request plus one exchange

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: The vendor accepts it, live, and the full gate

**Files:**
- Test: `nessy-inference/anthropic/src/test/java/org/jwcarman/nessy/inference/anthropic/AnthropicLiveTest.java` (one new test, after `reasoning_is_replayed_intact_on_the_next_turn`)

**Interfaces:**
- Consumes: the helpers already in `AnthropicLiveTest`: `provider()`, `open(long, String)`.
- Produces: nothing.

The existing live tests that turn thinking on (`reasoning_is_replayed_intact_on_the_next_turn`, `an_agent_type_s_budget_makes_a_provider_that_does_not_think_think`) now send the setting and the header in budgeted mode on `claude-sonnet-4-5`, so they already prove that pairing is accepted. This task adds the case this plan exists for: adaptive thinking on a model that enforces the check, with the system prompt changed under replayed thinking.

It cannot fail first on an account created before 2026-08-31, where the check is not enforced without the setting. It proves the vendor accepts what the adapter now sends; the 400 it prevents shows only on newer accounts. Say so in the test's comment, as below.

- [ ] **Step 1: Write the live test**

Add this test immediately after the closing brace of `reasoning_is_replayed_intact_on_the_next_turn`:

```java
  /**
   * Background changes Nessy's system prompt between calls, and on the models that bind thinking to
   * its prefix that used to be a 400 for accounts created since 2026-08-31. With the adapter asking
   * for mismatched blocks to be dropped, the vendor answers.
   *
   * <p>On an older account the check is not enforced without the setting, so this could not have
   * failed there before the fix. What it proves anywhere is that the setting and its beta header
   * are accepted on a model that enforces the check, with thinking replayed under a changed prompt.
   */
  @Test
  void a_changed_system_prompt_under_replayed_thinking_is_still_answered() {
    String question =
        "Privately work out 17 x 23 + 41 x 19 and check it twice. Reply with the number only.";
    InferenceOptions thinking =
        new InferenceOptions(
            "claude-sonnet-5-5", 4096, Map.of("anthropic.thinking.type", "adaptive"));
    try (AnthropicInferenceProvider provider = provider()) {
      InferenceResult first =
          provider.infer(
              new InferenceRequest(
                  SYSTEM, InferenceContext.of(List.of(open(1, question))), Toolset.none(), thinking));

      assertThat(first).isInstanceOf(InferenceResult.Answer.class);
      List<Block.AnswerContent> answered = ((InferenceResult.Answer) first).blocks();
      assumeTrue(
          answered.stream().anyMatch(Block.Provider.class::isInstance),
          "adaptive thinking chose not to think, so there is nothing to replay");

      Turn done =
          new Turn(
              new TurnId(1),
              new Input(new Seq(1), List.of(new Block.Text(question))),
              List.of(),
              new TurnResult.Answered(answered),
              0);
      SystemPrompt changed =
          new SystemPrompt(SYSTEM.value() + "\n<plan>\nStep 2 of 3: report the total.\n</plan>");

      InferenceResult second =
          provider.infer(
              new InferenceRequest(
                  changed,
                  InferenceContext.of(List.of(done, open(3, "And what is half of that?"))),
                  Toolset.none(),
                  thinking));

      assertThat(second)
          .as("a prefix that changed under replayed thinking is dropped by the vendor, not refused")
          .isInstanceOf(InferenceResult.Answer.class);
    }
  }
```

Every type used is already imported in `AnthropicLiveTest`.

- [ ] **Step 2: Confirm it compiles and is skipped without a key**

```bash
cd /Users/jcarman/IdeaProjects/nessy-drop-block
./mvnw -q spotless:apply license:format
pgrep -fl nessy-example; ./mvnw -q -pl :nessy-inference-anthropic -am test; echo "exit=$?"
```

Expected: `exit=0`. Live tests are excluded by default (`nessy.excludedGroups` is `live,container`), so this only proves the new test compiles.

- [ ] **Step 3: Run the live tests, if a key is available**

Only if `ANTHROPIC_API_KEY` is set in this shell. If it is not, skip this step and say so in the report; James runs it.

```bash
cd /Users/jcarman/IdeaProjects/nessy-drop-block
./mvnw -q spotless:apply license:format
pgrep -fl nessy-example; ./mvnw -q -pl :nessy-inference-anthropic -am test -Dnessy.excludedGroups= -Dtest=AnthropicLiveTest -Dsurefire.failIfNoSpecifiedTests=false; echo "exit=$?"
```

Expected: `exit=0`. A failure in `reasoning_is_replayed_intact_on_the_next_turn` or `an_agent_type_s_budget_makes_a_provider_that_does_not_think_think` with a message about `block_binding` or `anthropic-beta` means `claude-sonnet-4-5` rejects the setting in budgeted mode, which the probes did not cover (they covered `claude-haiku-4-5`). Stop and report it; do not work around it.

- [ ] **Step 4: Run the full gate**

```bash
cd /Users/jcarman/IdeaProjects/nessy-drop-block
./mvnw -q spotless:apply license:format
pgrep -fl nessy-example; ./mvnw -q clean verify; echo "exit=$?"
```

Expected: `exit=0`, with no API key and no network. If `pgrep` printed a running example, do not run the build; stop and report.

- [ ] **Step 5: Commit**

```bash
cd /Users/jcarman/IdeaProjects/nessy-drop-block
git add nessy-inference/anthropic/src/test/java/org/jwcarman/nessy/inference/anthropic/AnthropicLiveTest.java
git commit -m "test: Anthropic answers when the system prompt changed under replayed thinking, live

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
git log --oneline -4
```
