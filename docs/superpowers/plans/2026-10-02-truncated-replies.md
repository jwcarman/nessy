# Truncated Replies Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A reply the vendor cut off at the output-token limit is reported as what it is, so nothing stores or runs it as if it were complete.

**Architecture:** `InferenceResult` gains a fifth arm, `Truncated`, carrying the partial blocks and the usage. Each of the five adapters returns it when the vendor says the reply stopped at the output limit and the reply holds text; a reply cut off inside a tool call is a `Fault`, never `Truncated`. The engine delivers a truncated answer to an agent's turn as the answer it is, with a warning and the right finish reason in telemetry; the chapter summariser refuses one.

**Tech Stack:** Java 25, sealed interfaces and record patterns, the five vendor SDKs already in the build, JUnit 5 and AssertJ. No mocking library.

**Spec:** There is no separate spec. The rulings are James's, made in conversation on 2026-10-02, and are recorded in Global Constraints below. The design of record's conventions bind as always (CLAUDE.md, "Design of record").

## Global Constraints

- The arm is named `InferenceResult.Truncated` (James, 2026-10-02). It is a new arm, not a flag on `Answer` (James, 2026-10-02): every `switch` over `InferenceResult` must decide what to do with it, and the compiler says where.
- `Truncated(List<Block.AnswerContent> blocks, Usage usage)`: the same content type as `Answer`. Its blocks hold at least one `Block.Text`; a truncated reply with no text is not `Truncated` (see the next two lines).
- A reply cut off inside a tool call is `InferenceResult.Fault(new Failure.Permanent(...))`, never `Truncated` and never `Actions`. Measured 2026-10-02 on Sonnet 5.5: the vendor returns such a call with `{}` for its arguments, which parses and would run.
- A reply cut off with nothing but reasoning in it stays what it is today: the adapters' existing "model returned an empty answer" `Fault`, which already names the stop reason.
- An agent's own turn delivers a `Truncated` reply as its answer, logs a WARN, and reports `length` as the finish reason. No new `EffectOutcome`, `AgentEvent` or `TurnResult` arm: nothing in the fold changes.
- `ProseSummarizer` refuses a `Truncated` reply: it throws, the chapter stays unsummarised, and the message says the summary was cut off at the output limit.
- Continuing a truncated reply with a second call is OUT of scope (James, 2026-10-02: its own design, after the OpenAI and Gemini probes).
- Never suppress warnings. No star imports. No fully-qualified type names in code. Tests are snake_case in `@Nested` classes; one throwing invocation per exception-assertion lambda; assert non-empty before any all/none-match assertion. No mocking library.
- Docs describe what is, never history (CHANGELOG excepted). Every source file carries the Apache header: run `./mvnw spotless:apply license:format` before the last commit of a task.
- Build: scoped while iterating, `./mvnw -q -pl :<artifactId> -am test`; select by artifactId with the colon. Never two Maven processes in one worktree. Judge a build by its exit code.
- Do not make a live call to any vendor. No API key is available and none may be asked for.

## Review Focus

1. A tool call cut off mid-arguments reaching the engine as `Actions`. Expected: a `Fault`. Pinned in Tasks 2 to 5, one test per adapter.
2. A truncated reply with only reasoning in it becoming `Truncated` with no text. Expected: the existing empty-answer `Fault`. Pinned in Tasks 2 to 5.
3. A compatible server on the OpenAI chat wire that reports no finish reason, or `stop`, for a complete reply. Expected: `Answer`, exactly as today. Pinned in Task 3.
4. A streamed reply and a non-streamed reply disagreeing. Expected: the same arm from both paths, since both end in the same `read`. Pinned where an adapter has two paths (Tasks 2 and 3).
5. A stored `InferenceResult` written before this change being read back. Expected: `answer`, `refusal` and `fault` still deserialise; `truncated` round-trips. Pinned in Task 1.

---

### Task 1: The `Truncated` arm, and what the engine does with it

**Files:**
- Modify: `nessy-inference/spi/src/main/java/org/jwcarman/nessy/inference/InferenceResult.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/effect/InferenceHandler.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/observability/ObservedInferenceProvider.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/chapter/ProseSummarizer.java`
- Modify: `nessy-examples/chapter-lab/src/main/java/org/jwcarman/nessy/examples/chapterlab/Models.java`
- Test: `nessy-inference/spi/src/test/java/org/jwcarman/nessy/inference/InferenceTypesTest.java`, `InferenceResultUsageTest.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/effect/InferenceHandlerTest.java`
- Test: the existing test class for `ObservedInferenceProvider` (find it with `grep -rl finishReasonOf nessy-engine/src/test`)
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/chapter/ProseSummarizerTest.java`
- Test: `nessy-examples/chapter-lab/src/test/java/org/jwcarman/nessy/examples/chapterlab/ChapterLabTest.java`

**Interfaces:**
- Produces: `InferenceResult.Truncated(List<Block.AnswerContent> blocks, Usage usage)`, with a one-argument constructor `Truncated(List<Block.AnswerContent> blocks)` using `Usage.unreported()`, `withUsage(Usage)` returning `Truncated`, and the Jackson subtype name `"truncated"`. Tasks 2 to 5 return it.

- [ ] **Step 1: Write the failing tests for the arm.** In `InferenceTypesTest`, a `@Nested class A_truncated_reply` with: `holds_the_partial_blocks_and_the_usage`; `needs_at_least_one_text_block` (a list holding only a `Block.Provider` is refused with `IllegalArgumentException` whose message contains `at least one text block`; so is an empty list); `the_blocks_cannot_be_changed_afterwards`. In `InferenceResultUsageTest`, extend whatever it does for the other arms (`withUsage`, and the Jackson round trip if it has one) to `Truncated`, and add `a_result_stored_before_truncated_existed_still_reads` reading the literal JSON of an `answer`.
- [ ] **Step 2: Run them and see them fail to compile.** `./mvnw -q -pl :nessy-inference-spi -am test`. Expected: a compilation error naming `Truncated`.
- [ ] **Step 3: Add the arm.** Beside the other arms, following their shape exactly (null checks with the same messages, `List.copyOf`, the `USAGE_NOT_NULL` constant). Its javadoc says: the vendor stopped the reply at the output-token limit; the blocks are what was written before it stopped; it is not an answer, and a caller that keeps one must decide to. Add `@JsonSubTypes.Type(value = InferenceResult.Truncated.class, name = "truncated")`. Update the interface's own javadoc where it lists the arms.
- [ ] **Step 4: Run the SPI tests.** Expected: pass.
- [ ] **Step 5: Write the failing engine tests.** `InferenceHandlerTest`: `a_truncated_reply_is_delivered_as_the_answer_it_is` (the outcome is `EffectOutcome.InferenceAnswered` whose stored blocks are the truncated blocks, with the usage). The `ObservedInferenceProvider` test: `a_truncated_reply_finishes_for_length` (`finishReasonOf` returns `"length"`). `ProseSummarizerTest`: `refuses_a_summary_that_was_cut_off` (the scripted provider returns `Truncated`; `summarize` throws `IllegalStateException` whose message contains `cut off at the output limit` and the chapter's turn range). `ChapterLabTest`: a grading or answering call that returns `Truncated` is counted as no answer and is tried ONCE, not four times (count the calls).
- [ ] **Step 6: Make them pass.**
  - `InferenceHandler`: a `case InferenceResult.Truncated(var blocks, var usage)` that logs at WARN (`"model's answer to agent {} was cut off at the output limit after {} block(s)"`) and yields `new EffectOutcome.InferenceAnswered(payloads.forAgent(agentId).put(blocks), usage)`.
  - `ObservedInferenceProvider.finishReasonOf`: `case InferenceResult.Truncated _ -> "length"`.
  - `ProseSummarizer`: before the existing not-an-answer check, `if (result instanceof InferenceResult.Truncated)` throw `IllegalStateException("the summary of turns %s through %s was cut off at the output limit")`. Say in the class javadoc that a cut-off summary is refused and why (a summary is written once and kept).
  - `Models.text`: a `Truncated` result throws a distinct exception that `retrying` does not retry (the same request would be cut off again). The simplest shape: check for `Truncated` outside the retried lambda and throw `IllegalStateException("the reply was cut off at the output limit")`.
  - Fix any other `switch` or `instanceof` the compiler or the tests turn up; there should be none beyond these.
- [ ] **Step 7: Run** `./mvnw -q -pl :nessy-engine,:nessy-example-chapter-lab -am test`. Expected: pass.
- [ ] **Step 8: Gate and commit.** `./mvnw spotless:apply license:format`, then `./mvnw -q clean verify`, then commit: `feat: a reply cut off at the output limit is its own kind of result`.

### Task 2: Anthropic returns `Truncated`

**Files:**
- Modify: `nessy-inference/anthropic/src/main/java/org/jwcarman/nessy/inference/anthropic/AnthropicInferenceProvider.java` (`read(Message)`, about line 330)
- Test: `nessy-inference/anthropic/src/test/java/org/jwcarman/nessy/inference/anthropic/AnthropicInferenceProviderTest.java`

**Interfaces:**
- Consumes: `InferenceResult.Truncated` from Task 1.

- [ ] **Step 1: Write the failing tests,** following how that test class already scripts a vendor reply. In a `@Nested class A_reply_cut_off_at_the_output_limit`: `text_cut_off_is_truncated_and_carries_what_was_written` (`stop_reason` `max_tokens`, one text block: the result is `Truncated` with that text); `a_tool_call_cut_off_is_a_fault_and_is_not_run` (`max_tokens` with a text block and a `tool_use` block whose input is `{}`: the result is `Fault` with `Failure.Permanent` whose reason contains `cut off` and `tool call`); `reasoning_alone_cut_off_is_the_empty_answer_fault` (`max_tokens` with only a thinking block: the existing fault, its reason containing `stop_reason=max_tokens`); `a_complete_reply_is_still_an_answer` (`end_turn`); and, if the class tests the streamed path separately, the first of these again through it.
- [ ] **Step 2: Run** `./mvnw -q -pl :nessy-inference-anthropic -am test`. Expected: the new tests fail.
- [ ] **Step 3: Implement in `read`.** After the refusal check, compute `boolean cutOff = message.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()`. If `cutOff && asking`: return the tool-call `Fault` (`"the reply was cut off at the output limit inside a tool call (stop_reason=max_tokens)"`). In the not-asking branch, keep the empty check first, then return `Truncated` in place of `Answer` when `cutOff` and the blocks hold a `Block.Text`; if `cutOff` and there is no text block, return the existing empty-answer fault. Update the method's javadoc.
- [ ] **Step 4: Run the module's tests.** Expected: pass.
- [ ] **Step 5: Gate and commit.** `spotless:apply license:format`, `./mvnw -q clean verify`, commit: `feat: Anthropic says when a reply was cut off at the output limit`.

### Task 3: OpenAI chat and Responses return `Truncated`

**Files:**
- Modify: `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiChatInferenceProvider.java` (`read(ChatCompletion.Choice)`, about line 355)
- Modify: `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiResponsesInferenceProvider.java` (the method that ends `return new InferenceResult.Answer(answer)`, about line 235)
- Test: the two providers' existing test classes in `nessy-inference/openai/src/test/java/org/jwcarman/nessy/inference/openai/`

**Interfaces:**
- Consumes: `InferenceResult.Truncated` from Task 1.

- [ ] **Step 1: Write the failing tests,** one `@Nested class A_reply_cut_off_at_the_output_limit` per provider, with the same four cases as Task 2 in each wire's own terms. Chat: `finish_reason` `length`. Responses: `status` `incomplete` with `incomplete_details.reason` `max_output_tokens`. Add for chat: `a_server_that_reports_no_finish_reason_still_answers` and `a_finish_reason_of_stop_still_answers`. Add for Responses: `incomplete_for_another_reason_is_not_truncated` (for example `content_filter`: whatever the method returns today is unchanged).
- [ ] **Step 2: Run** `./mvnw -q -pl :nessy-inference-openai -am test`. Expected: the new tests fail.
- [ ] **Step 3: Implement.** Chat: `length` is trusted only when the server reports it; the existing javadoc explains why the shape is otherwise read from the content, and that stays true and is extended to say so. With `length` and tool calls present: the tool-call `Fault` (`finish_reason=length`). With `length`, no calls and non-blank text: `Truncated`. Blank text: the existing fault. Responses: the same three outcomes, keyed on `status=incomplete` and `reason=max_output_tokens`; the reasoning blocks the method keeps go into `Truncated` exactly as they go into `Answer`.
- [ ] **Step 4: Run the module's tests.** Expected: pass.
- [ ] **Step 5: Gate and commit:** `feat: the OpenAI adapters say when a reply was cut off at the output limit`.

### Task 4: Gemini returns `Truncated`

**Files:**
- Modify: `nessy-inference/gemini/src/main/java/org/jwcarman/nessy/inference/gemini/GeminiInferenceProvider.java` (about line 340)
- Test: `nessy-inference/gemini/src/test/java/org/jwcarman/nessy/inference/gemini/GeminiInferenceProviderTest.java`

**Interfaces:**
- Consumes: `InferenceResult.Truncated` from Task 1.

- [ ] **Step 1: Write the failing tests,** the same four cases; the finish reason is `MAX_TOKENS`. A thought part alone is the empty-answer fault.
- [ ] **Step 2: Run** `./mvnw -q -pl :nessy-inference-gemini -am test`. Expected: the new tests fail.
- [ ] **Step 3: Implement,** using the `finish` string the method already computes: `MAX_TOKENS` with a function call present is the tool-call `Fault`; with text, `Truncated`; with neither, the existing fault. Update the javadoc.
- [ ] **Step 4: Run the module's tests.** Expected: pass.
- [ ] **Step 5: Gate and commit:** `feat: Gemini says when a reply was cut off at the output limit`.

### Task 5: Bedrock returns `Truncated`

**Files:**
- Modify: `nessy-inference/bedrock/src/main/java/org/jwcarman/nessy/inference/bedrock/BedrockInferenceProvider.java` (`read(ConverseResponse)`, about line 358)
- Test: `nessy-inference/bedrock/src/test/java/org/jwcarman/nessy/inference/bedrock/BedrockInferenceProviderTest.java`

**Interfaces:**
- Consumes: `InferenceResult.Truncated` from Task 1.

- [ ] **Step 1: Write the failing tests,** the same four cases; the stop reason is `StopReason.MAX_TOKENS`.
- [ ] **Step 2: Run** `./mvnw -q -pl :nessy-inference-bedrock -am test`. Expected: the new tests fail.
- [ ] **Step 3: Implement** as in Task 2, on `response.stopReason() == StopReason.MAX_TOKENS`. Update the javadoc.
- [ ] **Step 4: Run the module's tests.** Expected: pass.
- [ ] **Step 5: Gate and commit:** `feat: Bedrock says when a reply was cut off at the output limit`.

### Task 6: Documentation

**Files:**
- Modify: `docs/guides/providers.md` (the section that describes what `infer` returns, and the "writing your own provider" section that lists the arms)
- Modify: `CHANGELOG.md` (`[Unreleased]`, under Added and, for the changed behaviour, Changed)
- Modify: any other page under `docs/` that lists the arms of `InferenceResult` (`grep -rn "InferenceResult" docs --include=*.md | grep -v superpowers`)

- [ ] **Step 1: Write the guide text.** What `Truncated` is; what each adapter treats as a cut-off (the five stop reasons, in a table); that a cut-off tool call is a fault; what the engine does with one in a turn (delivers it, WARN, `length`); that the chapter summariser refuses one, so a chapter whose summary does not fit the agent type's `maxTokens` stays unsummarised and is tried again at the next turn's end.
- [ ] **Step 2: Write the changelog entries.** Added: the arm. Changed: a reply cut off at the output limit was an `Answer` and is now `Truncated`; a tool call cut off at the limit was `Actions` and is now a `Fault`; a custom `InferenceProvider` or a `switch` over `InferenceResult` needs the new arm.
- [ ] **Step 3: Check and commit.** `./mvnw -q spotless:check license:check`, commit: `docs: what a truncated reply is and what each adapter and the engine do with one`.

## Open, for James

- **A summary that never fits is retried at every turn's end.** The keeper has no backoff (already on the open list), and each attempt now costs a full, cut-off summary. This plan makes the failure visible; it does not bound it. The fix that removes the cause is a length target in the default summary prompt, which the lab can test first.
- **Continuation** is its own design: probed on Anthropic (asking works, 19 clean joins of 20; ending on the partial reply is refused), unprobed on OpenAI and Gemini.
