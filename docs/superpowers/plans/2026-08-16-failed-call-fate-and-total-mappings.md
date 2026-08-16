# Failed-Call Fate and Total Mappings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps
> use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A failed model call folds `ModelCallFailed` instead of leaking a zombie
`AWAITING_MODEL` turn, and the OpenAI/Gemini mappings translate mixed
tool-result+text USER messages instead of crashing on them.

**Architecture:** Two independent fronts. Core: `ProviderModelCallExecutor` broadens
its catch so any provider `RuntimeException` becomes `ModelCallFailed` (reason
`<ClassName>: <message>`, full ERROR log; `Error`s still propagate). Mappings:
`OpenAiRequests` partitions a USER message's blocks (tool results → tool-role
messages first, remaining blocks → one user message via the existing
`toUserMessageParam`); `GeminiRequests` maps per block into one user Content
(functionResponse parts + text parts together). The reducer is untouched.

**Tech Stack:** Java 21, JUnit 5 + AssertJ, ScriptedModelProvider (no mocking
libraries, prose test names).

**Spec:** `docs/superpowers/specs/2026-08-16-failed-call-fate-and-total-mappings-design.md`

## Global Constraints

- `./mvnw -q clean verify` green with no API key and no network, always.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`.
- No mocking libraries; prose test method names; no star imports; no suppressions.
- Exception-assertion lambdas contain exactly ONE throwing invocation (S5778).
- Parameterize same-shape tests (S5976); bundle S107 risks at add time.
- The reducer (`ConversationState`) must not change in any way.

---

### Task 1: Failed calls fold — executor catch broadening

**Files:**
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/spi/execute/ProviderModelCallExecutor.java`
- Test: the executor's existing test class + a loop-level regression test beside the
  existing end-to-end/loop tests in nessy-core (or nessy-testing, wherever scripted
  loop tests live — follow the existing pattern).

**Interfaces:**
- Consumes: existing `ConversationEvent.ModelCallFailed(String reason)` fold (already
  handles `ContextOverflowException`).
- Produces: no API change — behavioral only.

**Steps:**
- [ ] Write the failing loop-level regression test: a provider whose first call
  throws `new RuntimeException("403: no credits")` (a tiny inline ModelProvider or a
  delegating wrapper around ScriptedModelProvider — whichever the existing seams make
  cleanest) → drive a tell → assert conversation status FAILED and failureReason
  contains "RuntimeException" and "403: no credits" (the live-trace regression). Then
  a second tell → scripted normal response → turn completes; no
  ClassCastException, no zombie.
- [ ] Executor-level test: stream iteration throwing mid-stream folds
  `ModelCallFailed` the same way.
- [ ] Verify both fail (exception propagates today, status stays AWAITING_MODEL).
- [ ] Broaden the catch in `ProviderModelCallExecutor` around the provider call and
  stream consumption: `catch (RuntimeException e)` → fold
  `ModelCallFailed(e.getClass().getSimpleName() + ": " + e.getMessage())` and
  `LOGGER.error(...)` with the exception. Keep the `ContextOverflowException` arm
  first (its existing reason/behavior unchanged — S1193-safe ordering: the specific
  catch before the general). `Error` untouched.
- [ ] Confirm the `ContextOverflowException` tests still pass unchanged.
- [ ] `./mvnw -q clean verify`; license + spotless; commit.

### Task 2: Total mappings — OpenAI partitions, Gemini goes per-block

**Files:**
- Modify: `nessy-model-openai/src/main/java/org/jwcarman/nessy/model/openai/OpenAiRequests.java` (~:101-115)
- Modify: `nessy-model-gemini/src/main/java/org/jwcarman/nessy/model/gemini/GeminiRequests.java` (~:172-184)
- Tests: `OpenAiRequestsTest`, `GeminiRequestsTest`

**Interfaces:**
- Consumes: `Message.user(List<ContentBlock>)`, `Message.toolResults(...)`,
  `ToolResultBlock(String id, String content, boolean isError)`.
- Produces: no API change — mapping behavior only.

**Steps:**
- [ ] Failing tests first. OpenAI: a USER message of
  `[ToolResultBlock("c1", "13", false), TextBlock("try again")]` maps to exactly two
  params — one tool-role message for c1, THEN one user-role message "try again", in
  that order; two results + one text → two tool messages then one user message;
  pure-results and pure-text messages pinned unchanged (parameterize the same-shape
  cases). Gemini: the same mixed message maps to ONE user Content whose parts are
  `functionResponse(c1)` and `text("try again")`; pure cases pinned unchanged.
- [ ] Verify they fail with today's `ClassCastException`.
- [ ] OpenAI: rewrite `toUserRoleMessageParams` to partition —
  `content.stream().filter(ToolResultBlock.class::isInstance)` → tool message params
  first; the non-tool-result remainder, if non-empty, appended as one user param via
  the existing `toUserMessageParam(remainder)`. Delete the "never both" sentence from
  the javadoc; state the real contract (tool results become tool messages; any other
  blocks follow as one user message).
- [ ] Gemini: rewrite `toUserContent` to map per block into one Content's part list
  (ToolResultBlock → functionResponse part, TextBlock → text part, keeping whatever
  the current per-kind conversions are), Anthropic-style; drop the all-or-nothing
  cast. Update its javadoc likewise.
- [ ] `./mvnw -q clean verify`; license + spotless; commit.
