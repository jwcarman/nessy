# Recorded Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** What a tool call would do, and what it returned, are each written down once as a short line, stored in the agent's events, and shown to the chapter summariser in place of the call and its result.

**Architecture:** A new `Stringifier<T>` in the API turns a value into a line of text and can wrap itself in a dropper (`dropTail`, `dropHead`, `dropMiddle`) built on a `Truncator`. A tool's binding holds two of them, one for the action and one for the result, always wrapped so no line passes 1,000 characters. The engine stores the action on the call in the `actions-requested` event and the result line on the `tool-succeeded` event; `Exchange` hands both over by call id; `Transcripts.render` writes one line per finished call and `ProseSummarizer` sends that text as a single message with no tool blocks.

**Tech Stack:** Java 25 (records, sealed interfaces, pattern switches), Jackson 3 (`tools.jackson`), JUnit 5, AssertJ. No mocking library.

**Spec:** `docs/superpowers/specs/2026-10-02-recorded-actions-design.md`. Read it first; it is the authority and this plan argues from it.

## Global Constraints

- The names ruled by James, to be used exactly: `Stringifier<T>` with `stringify(T)`; `Truncator`; `dropTail(int limit)`, `dropHead(int limit)`, `dropMiddle(int limit)`; `action` on `ActionRequest.ToolCall`; `rendered` on `ToolSucceeded`; `actions` and `results` on `Exchange`, read with `actionOf(CallId)` and `resultOf(CallId)`; the transcript line `assistant did: <action> -- succeeded: <result>`.
- `ActionRenderer` is removed, not deprecated. A `Stringifier<I>` takes its place.
- The hard cap on a line is 1,000 characters (`ToolConfig.LINE_CAP`). With no stringifier named, a line is cut at 255 (`ToolConfig.DEFAULT_LINE_LIMIT`): an action keeps its start, a result drops its middle.
- A binding's stringifier is always wrapped in a dropper. A wrapper asked to drop to a limit it is already within returns itself. There is no `bounded()` method.
- The default stringifier is `String.valueOf`. `Stringifier.json(JsonMapper)` is supplied and is not the default.
- No backward compatibility. No nullable "missing in old data" fields, no fallback for events stored before this change, no constructor kept for old callers. A record that gains a component gains it everywhere it is built, tests included.
- The turn being answered is untouched: the adapters still receive every call and every result whole. Nothing in `nessy-inference/*` changes.
- Stored lines are fixed when written and never worked out again.
- A stringifier that throws, or returns null, never fails a turn. The table in spec §6.1 says what is stored instead; a result line that cannot be made is the empty string.
- Never suppress warnings. No star imports. No fully-qualified type names in code. Tests are snake_case in `@Nested` classes; one throwing invocation per exception-assertion lambda; assert non-empty before any all/none-match assertion. No mocking library.
- Docs describe what is, never history (CHANGELOG excepted). Every source file carries the Apache header: run `./mvnw spotless:apply license:format` before a task's last commit.
- Build: scoped while iterating, `./mvnw -q -pl :<artifactId> -am test`, selecting by artifactId with the colon. Never two Maven processes in one worktree. Judge a build by its exit code, never by grepping its output. The gate for each task is named in the task.
- Make no live call to any vendor except in Task 8's test, which is tagged `live` and excluded from every build here.

## Review Focus

1. **A deferred tool result.** A tool that answers later, through `Replies.complete`, is recorded by `DefaultReplies`, not by `ToolCallHandler`. Its success must carry a `rendered` line made by the same binding's result stringifier. Pinned in Task 5.
2. **A stringifier that misbehaves at request time.** It throws, returns null, or returns blank while the model's request is being recorded. The turn must go on and the call must still have an action. Pinned in Tasks 2 and 3.
3. **Wrapping.** A binding's own dropper above the cap is cut to the cap; one at or below it is left exactly as given, even when it cuts a different way from the line's own dropper; whitespace is collapsed before the cut, not after. Pinned in Tasks 1 and 2.
4. **The cut itself.** A two-code-unit character at the boundary; a limit smaller than the marker; text exactly at the limit. Pinned in Task 1.
5. **A summary request with a tool block in it.** After Task 7 none may reach a provider from `ProseSummarizer`, on either door, for a chapter whose turns called tools. Pinned in Task 7 with a scripted provider that fails such a request.

---

### Task 1: `Truncator` and `Stringifier`

**Files:**
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/Truncator.java`
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/Stringifier.java`
- Create (package-private): `nessy-api/src/main/java/org/jwcarman/nessy/api/TruncatingStringifier.java`
- Test: `nessy-api/src/test/java/org/jwcarman/nessy/api/TruncatorTest.java`
- Test: `nessy-api/src/test/java/org/jwcarman/nessy/api/StringifierTest.java`

**Interfaces:**
- Produces, exactly:

```java
@FunctionalInterface
public interface Truncator {
  String truncate(String text, int limit);
  static Truncator dropTail();
  static Truncator dropHead();
  static Truncator dropMiddle();
}

@FunctionalInterface
public interface Stringifier<T> {
  String stringify(T value);
  default Stringifier<T> dropTail(int limit);     // truncated(Truncator.dropTail(), limit)
  default Stringifier<T> dropHead(int limit);     // truncated(Truncator.dropHead(), limit)
  default Stringifier<T> dropMiddle(int limit);   // truncated(Truncator.dropMiddle(), limit)
  default Stringifier<T> truncated(Truncator truncator, int limit);
  static <T> Stringifier<T> byToString();         // String::valueOf
  static <T> Stringifier<T> json(JsonMapper mapper);
}
```

- [ ] **Step 1: Write `TruncatorTest`, failing.** One `@Nested` class per supplied truncator and one for what they share. Cases, each for all three unless it names one:
  - `text_within_the_limit_comes_back_as_given` (shorter than, and exactly at, the limit; assert the same string).
  - `text_over_the_limit_comes_back_at_exactly_the_limit`.
  - `dropTail`: `"abcdefghij"` at 8 is `"abcde..."`. `dropHead`: `"abcdefghij"` at 8 is `"...fghij"`. `dropMiddle`: `"abcdefghij"` at 8 is `"abc...ij"` (five characters left after the marker; the odd one goes to the start).
  - `a_character_of_two_code_units_is_kept_or_dropped_whole`: text containing `"😀"` placed so the cut would fall inside it; assert the result holds no lone surrogate (`Character.isSurrogate` on each `char` is paired) and is no longer than the limit.
  - `a_limit_too_small_for_the_marker_is_a_plain_cut`: at limit 3 and at limit 4, `"abcdefghij"` gives its first three (or four) characters for `dropTail` and `dropMiddle`, and its last for `dropHead`, with no `...`.
  - `a_limit_below_one_is_refused`: `IllegalArgumentException` whose message contains `limit`.
  - `null_text_is_refused`.
- [ ] **Step 2: Run** `./mvnw -q -pl :nessy-api test -Dtest=TruncatorTest`. Expected: does not compile.
- [ ] **Step 3: Write `Truncator`.** The marker is the constant `"..."`. The three statics return shared instances. Count and cut in code points (`String.codePointCount`, `offsetByCodePoints`), so a limit is a number of characters as a reader counts them and a surrogate pair is never split. "Too small for the marker" means the limit is less than the marker's length plus one kept character per kept side (4 for `dropTail` and `dropHead`, 5 for `dropMiddle`); then cut plainly to the limit, keeping the start for `dropTail` and `dropMiddle` and the end for `dropHead`. Javadoc says what each keeps, where the marker goes, and the promises in spec §4.
- [ ] **Step 4: Run the test.** Expected: pass.
- [ ] **Step 5: Write `StringifierTest`, failing.**
  - `by_to_string_is_string_value_of`: a record, a string, and `null` (gives `"null"`).
  - `json_writes_the_value_as_the_mapper_does`: a record `Query(String q, int n)` gives `{"q":"lake","n":3}`; `a_value_the_mapper_cannot_write_makes_it_throw` (a class with a self-reference, or one Jackson refuses; assert it throws and do not pin the exception type beyond `RuntimeException`).
  - `a_dropper_makes_the_text_one_line_before_it_cuts`: `"a\n\n  b\tc  "` through `dropTail(100)` is `"a b c"`.
  - `drop_tail_keeps_the_start`, `drop_head_keeps_the_end`, `drop_middle_keeps_both_ends`: each at a limit shorter than the text, asserting the exact string.
  - `truncated_uses_the_truncator_given`: a truncator that returns `"X"`.
  - `a_limit_below_one_is_refused_when_the_wrapper_is_made`.
  - `a_truncator_that_returns_more_than_the_limit_is_cut_to_it`: a truncator that returns its text unchanged; the result is the first `limit` characters.
  - `a_wrapper_asked_for_a_limit_at_or_above_its_own_returns_itself`: `s.dropMiddle(200).dropTail(1000)` is the same instance as `s.dropMiddle(200)`; so is `.dropTail(200)`; so is `.truncated(anyTruncator, 500)`.
  - `a_wrapper_asked_for_a_smaller_limit_cuts_again`: `s.dropMiddle(200).dropTail(50)` is a different instance and its output is at most 50 characters.
  - `a_plain_stringifier_asked_to_drop_is_wrapped`: a lambda's `dropTail(10)` is not the lambda.
  - `a_null_from_the_wrapped_stringifier_is_an_empty_line`: wrapping a stringifier that returns null gives `""`, not an exception.
- [ ] **Step 6: Run** `./mvnw -q -pl :nessy-api test -Dtest=StringifierTest`. Expected: does not compile.
- [ ] **Step 7: Write `Stringifier` and `TruncatingStringifier`.** `TruncatingStringifier<T>` is a package-private final class holding the wrapped stringifier, the truncator and the limit. Its `stringify` runs the wrapped one, treats null as `""`, collapses every run of whitespace to one space and trims (`strip` semantics, so non-ASCII whitespace counts), calls the truncator, and if what comes back is longer than the limit cuts it with `Truncator.dropTail()` semantics but no marker and logs a WARN naming the limit and the length returned (slf4j is already a dependency of `nessy-api`). It overrides `truncated(Truncator, int)`: when the limit asked for is at or above its own it returns `this`; otherwise it returns a new wrapper around itself. The three `drop` defaults go through `truncated`, so they inherit that. `Stringifier`'s own `truncated` default refuses a null truncator and a limit below 1, and returns a new `TruncatingStringifier`.
- [ ] **Step 8: Run** `./mvnw -q -pl :nessy-api test`. Expected: pass.
- [ ] **Step 9: Gate and commit.** `./mvnw spotless:apply license:format`, then `./mvnw -q clean verify`. Commit: `feat: Stringifier says a thing in a line of text, and can cut itself to a limit`.

### Task 2: A binding holds two stringifiers; `ActionRenderer` goes

**Files:**
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/ToolConfig.java`
- Delete: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/ActionRenderer.java`
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/ApprovalRequest.java` (javadoc that links `ActionRenderer`)
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/tool/ToolBinding.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/harness/direct/DefaultDirectHarnessConfig.java` (its `Binding`, about line 254) and `.../queued/DefaultQueuedHarnessConfig.java` (about line 319)
- Modify: `nessy-approval/policy-opa/src/main/java/org/jwcarman/nessy/approval/policy/opa/InputDocumentRenderer.java` (javadoc mention only)
- Modify: every test that names `ActionRenderer` (`grep -rln ActionRenderer --include=*.java .`; twelve files at the time of writing)
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/tool/ToolBindingLinesTest.java` (new)

**Interfaces:**
- Consumes: `Stringifier`, `Truncator` from Task 1.
- Produces, on `ToolConfig<I>`:

```java
int LINE_CAP = 1000;
int DEFAULT_LINE_LIMIT = 255;
ToolConfig<I> action(Stringifier<I> action);
ToolConfig<I> result(Stringifier<ToolResult.Success> result);
static Stringifier<ToolResult.Success> resultText();   // the result's text blocks, joined by a space
```

- Produces, on `ToolBinding<I>` (engine):

```java
/** What this call would do; never throws. */
public String describe(String arguments);
/** What this call returned; never throws, and may be empty. */
public String rendered(ToolResult.Success result);
```

  and a static for a call whose tool is not bound, used by Task 3: `ToolBinding.unbound(ToolName name)` returning `"<name> (no such tool)"`.

- [ ] **Step 1: Write `ToolBindingLinesTest`, failing.** Build bindings the way `ToolInputSchemaTest` does. Cases:
  - `with_no_stringifier_named_an_action_is_the_inputs_to_string_cut_at_255_keeping_its_start`.
  - `with_no_stringifier_named_a_result_is_its_text_cut_at_255_dropping_its_middle`.
  - `a_named_stringifier_is_cut_at_1000`: an action lambda returning 3,000 characters gives 1,000 ending in `...`; a result one gives 1,000 with `...` in the middle.
  - `a_named_stringifier_already_dropping_below_the_cap_is_used_as_given`: an action stringifier `dropMiddle(200)` gives a 200-character line with `...` in the middle, not at the end.
  - `a_named_stringifier_dropping_above_the_cap_is_cut_to_the_cap`.
  - Each row of spec §6.1's table through `describe`: text; null; blank; arguments that do not read into the input type (give it `"not json"` and `{"wrong":true}` for a record with a required different shape); a stringifier that throws. Assert the exact fallback strings `<tool name>` and `<tool name> (its arguments could not be read)`.
  - `rendered`: a stringifier that throws gives `""`; one that returns null gives `""`.
  - `ToolBinding.unbound(new ToolName("nope"))` is `"nope (no such tool)"`.
- [ ] **Step 2: Run** `./mvnw -q -pl :nessy-engine -am test -Dtest=ToolBindingLinesTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: does not compile.
- [ ] **Step 3: Change the API.** In `ToolConfig`: the two constants; `action(Stringifier<I>)` carrying everything `ActionRenderer`'s javadoc said that is still true (the sentence is written on the binding, by the application, never by the tool; who reads it, now including the summarising model; the warning that the default prints every component, extended to say a bounded copy is stored in the event and may be quoted in a summary; that a gated tool should have a sentence short enough not to be cut or name `dropTail` itself); `result(Stringifier<ToolResult.Success>)`; `resultText()`. Delete `ActionRenderer.java`. Fix the javadoc links to it in `ApprovalRequest` and `InputDocumentRenderer`.
- [ ] **Step 4: Change the engine.** `ToolBinding`'s constructor takes `Stringifier<I> action` and `Stringifier<ToolResult.Success> result` (both already wrapped by the config; `ToolBinding` does not wrap). Add `describe`, `rendered` and `unbound`. `question(...)` keeps rendering for now, through `describe`'s inner step so the logic is in one place; Task 4 changes it. In both harness configs' `Binding`: hold an optional action and an optional result; when the `ToolBinding` is built, settle each as spec §5's table says:

  | The binding | Action | Result |
  |---|---|---|
  | names none | `Stringifier.<I>byToString().dropTail(ToolConfig.DEFAULT_LINE_LIMIT)` | `ToolConfig.resultText().dropMiddle(ToolConfig.DEFAULT_LINE_LIMIT)` |
  | names one | `named.dropTail(ToolConfig.LINE_CAP)` | `named.dropMiddle(ToolConfig.LINE_CAP)` |

  The settling is the same code on both doors: put it in one place the two configs share (a small package-private helper in `org.jwcarman.nessy.engine.tool`), not written twice.
- [ ] **Step 5: Fix what no longer compiles.** Tests that passed `ActionRenderer.byToString()` to `ToolBinding` pass the two settled stringifiers; tests and examples that call `.action(lambda)` need no change (a lambda is a `Stringifier`); `Researcher::asking` in the MCP example still fits.
- [ ] **Step 6: Run** `./mvnw -q -pl :nessy-engine -am test`, then the examples that bind tools: `./mvnw -q -pl :nessy-example-chat-cli,:nessy-example-chat-web,:nessy-example-mcp,:nessy-example-watchman -am test`. Expected: pass.
- [ ] **Step 7: Gate and commit.** `spotless:apply license:format`, `./mvnw -q clean verify`. Commit: `feat: a tool's binding says what a call does and what it returned, each as a bounded line`.

### Task 3: The action is stored on the call

**Files:**
- Modify: `nessy-backend/spi/src/main/java/org/jwcarman/nessy/backend/event/ActionRequest.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/effect/InferenceHandler.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/harness/direct/DefaultDirectHarnessFactory.java`, `.../queued/DefaultQueuedHarnessFactory.java` (where `InferenceHandler` is built)
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/harness/direct/DefaultDirectHarness.java` (the record pattern `ActionRequest.ToolCall(var id, var name)` about line 640)
- Modify: every test that builds `ActionRequest.ToolCall` (21 uses in 6 files at the time of writing)
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/effect/InferenceHandlerTest.java`
- Test: the backend SPI's event serialisation test (find it: `grep -rln "actions-requested\|ActionsRequested" --include=*Test.java nessy-backend`); add one if there is none

**Interfaces:**
- Consumes: `ToolBinding.describe`, `ToolBinding.unbound`, `Tools.find` (Task 2).
- Produces: `record ToolCall(CallId id, ToolName name, String action) implements ActionRequest`, refusing a null or blank `action` with `IllegalArgumentException("action must not be blank")`.

- [ ] **Step 1: Write the failing tests.**
  - SPI: `a_tool_call_refuses_a_null_or_blank_action` (two assertions, each its own lambda); `an_actions_requested_event_reads_back_with_its_actions` (round-trip through the same mapper the backends use; assert the JSON contains `"action":"..."` and the event read back equals the one written).
  - `InferenceHandlerTest`, in a new `@Nested class Recording_what_each_call_would_do`: `each_requested_call_carries_its_action` (two calls to two bound tools; assert each `ActionRequest.ToolCall.action()`); `a_call_to_a_tool_that_is_not_bound_says_so` (`"nope (no such tool)"`); `arguments_that_cannot_be_read_say_so`; `a_stringifier_that_throws_does_not_fail_the_request` (the outcome is still `InferenceRequestedActions` and the action is the fallback).
- [ ] **Step 2: Run** `./mvnw -q -pl :nessy-engine -am test -Dtest=InferenceHandlerTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: does not compile.
- [ ] **Step 3: Implement.** Add the component and its check. `InferenceHandler` takes the agent type's `Tools` as a constructor argument; `requested(blocks)` becomes an instance method that, for each `Block.ToolCall`, finds the binding and stores `binding.describe(call.arguments())`, or `ToolBinding.unbound(call.name())`. Pass `Tools` at both factories' construction sites. Update the record pattern in `DefaultDirectHarness` to three components. The fold (`AgentState`), `OutstandingAction`, `AgentCommand` and `EffectOutcome` carry the record as it is and need no logic change.
- [ ] **Step 4: Fix the tests that build `ActionRequest.ToolCall`** by giving each a plain action string.
- [ ] **Step 5: Run** `./mvnw -q -pl :nessy-engine -am test`. Expected: pass.
- [ ] **Step 6: Gate and commit.** `spotless:apply license:format`, then, because the backend SPI's stored event changed, `./mvnw -q clean verify -Dnessy.excludedGroups=live`. Commit: `feat: what a tool call would do is stored with the call when it is requested`.

### Task 4: Approval reads the stored action

**Files:**
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/effect/ApprovalHandler.java` (about line 122)
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/tool/ToolBinding.java` (`question`)
- Modify: whatever resolves the outstanding call for the handler (`ToolCalls.ResolvedCall`, found through `calls.find(...)`), so the stored action reaches it
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/effect/ApprovalHandlerTest.java`

**Interfaces:**
- Consumes: `ActionRequest.ToolCall.action()` from Task 3.
- Produces: `ToolBinding.question(agentType, agentId, turn, callId, arguments, action, askedAt, replyToken)`, with `action` the stored sentence.

- [ ] **Step 1: Write the failing tests** in `ApprovalHandlerTest`: `the_question_carries_the_action_stored_with_the_call` (store an action the binding's own stringifier would never produce, such as `"stored at request time"`, and assert `ApprovalRequest.action()` is exactly that); `a_call_whose_arguments_cannot_be_read_is_still_discharged_without_asking` (unchanged behaviour: the outcome and its message to the model are what they are today; read the existing test for it and keep its assertions).
- [ ] **Step 2: Run** `./mvnw -q -pl :nessy-engine -am test -Dtest=ApprovalHandlerTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: the first fails.
- [ ] **Step 3: Implement.** `question` takes the action and stops rendering one; it still reads the arguments into the input type first and lets that throw, exactly as today, since that throw is what discharges an unreadable call. Find where the handler learns of the call (`calls.find(agentId, effect.requestSeq(), callId)`) and carry the stored action through from the `actions-requested` event. If the cleanest source is the agent's outstanding action rather than the story, use that; say which in the report.
- [ ] **Step 4: Run** `./mvnw -q -pl :nessy-engine -am test`, and the examples whose tests assert on an approval's action: `./mvnw -q -pl :nessy-example-watchman,:nessy-example-chat-web -am test`. Expected: pass.
- [ ] **Step 5: Gate and commit.** `spotless:apply license:format`, `./mvnw -q clean verify -Dnessy.excludedGroups=live`. Commit: `feat: an approver is shown the action stored with the call`.

### Task 5: The result line is stored on the success

**Files:**
- Modify: `nessy-backend/spi/src/main/java/org/jwcarman/nessy/backend/effect/EffectOutcome.java` (`ToolSucceeded`)
- Modify: `nessy-backend/spi/src/main/java/org/jwcarman/nessy/backend/event/AgentEvent.java` (`ToolSucceeded`)
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/core/AgentCommand.java` (`ToolOutcome.Succeeded`), `AgentState.java` (about line 397), `nessy-engine/.../effect/EffectOutcomes.java` (about line 61)
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/effect/ToolCallHandler.java` (about line 164)
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/tool/DefaultReplies.java` (`complete`, about line 100)
- Modify: every test that builds any of the three `Succeeded` records (12 files name `ToolSucceeded(`)
- Test: `ToolCallHandlerTest`, `nessy-engine/src/test/java/org/jwcarman/nessy/engine/tool/DeferredToolTest.java`, the SPI serialisation test from Task 3

**Interfaces:**
- Consumes: `ToolBinding.rendered(ToolResult.Success)` (Task 2).
- Produces: `EffectOutcome.ToolSucceeded(CallId callId, PayloadRef result, String rendered)`; `AgentCommand.ToolOutcome.Succeeded(PayloadRef result, String rendered)`; `AgentEvent.ToolSucceeded(Seq seq, TurnId turn, CallId callId, PayloadRef result, String rendered)`. `rendered` is never null and may be empty; each record refuses null.

- [ ] **Step 1: Write the failing tests.**
  - SPI: `a_tool_succeeded_event_reads_back_with_its_rendered_line`; `a_null_rendered_line_is_refused`; an empty one is accepted.
  - `ToolCallHandlerTest`: `a_success_carries_what_the_binding_says_it_returned` (a result stringifier that returns `"80 days"`); `a_result_stringifier_that_throws_leaves_an_empty_line_and_the_call_still_succeeds`.
  - `DeferredToolTest`: `a_result_that_arrives_later_carries_its_rendered_line_too`: a tool that defers, then `Replies.complete(token, ToolResult.ok(...))`; the stored `tool-succeeded` event's `rendered` is what the same binding's result stringifier gives.
- [ ] **Step 2: Run** `./mvnw -q -pl :nessy-engine -am test -Dtest='ToolCallHandlerTest,DeferredToolTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: does not compile.
- [ ] **Step 3: Implement.** Add the component to the three records and carry it through `EffectOutcomes` and `AgentState`. `ToolCallHandler` has the binding in hand: `binding.rendered(success)`. `DefaultReplies.complete` does not: its settlement is given the call id and the attempt. Give it what it needs to find the binding by the effect's tool name (`AgentEffect.CallTool.toolName()`), most simply the agent type's `Tools`; when the tool is no longer bound, use `ToolConfig.resultText().dropMiddle(ToolConfig.DEFAULT_LINE_LIMIT)`, which is what an unconfigured binding would have used. Say in the report how `DefaultReplies` reaches the binding.
- [ ] **Step 4: Fix the tests that build the changed records** by giving each a `rendered` string.
- [ ] **Step 5: Run** `./mvnw -q -pl :nessy-engine -am test`. Expected: pass.
- [ ] **Step 6: Gate and commit.** `spotless:apply license:format`, `./mvnw -q clean verify -Dnessy.excludedGroups=live`. Commit: `feat: what a tool call returned is stored as a line beside its result`.

### Task 6: A turn hands the lines over

**Files:**
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/turn/Exchange.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/history/Transcript.java` (the `ActionsRequested` and `ToolSucceeded` cases about lines 67-75, and `Open`)
- Modify: every test that builds an `Exchange` (30 uses in 11 files at the time of writing)
- Test: the API's turn tests under `nessy-api/src/test/java/org/jwcarman/nessy/api/turn/`, and `nessy-engine/src/test/java/org/jwcarman/nessy/engine/history/EventStreamHistoryTest.java`

**Interfaces:**
- Consumes: the stored `action` (Task 3) and `rendered` (Task 5).
- Produces:

```java
public record Exchange(
    Seq seq,
    List<Block.ActionRequestContent> request,
    List<ToolOutcome> outcomes,
    Map<CallId, String> actions,
    Map<CallId, String> results) {
  public List<Block.ToolCall> calls();             // as today
  public String actionOf(CallId id);
  public Optional<String> resultOf(CallId id);
}
```

- [ ] **Step 1: Write the failing tests.**
  - API: `an_exchange_refuses_a_call_with_no_action` (message names the call id); `action_of_gives_the_calls_action`; `action_of_refuses_an_id_that_is_not_one_of_its_calls`; `result_of_is_empty_for_a_call_that_has_not_succeeded`; `result_of_gives_the_line_of_a_call_that_succeeded`; `the_maps_cannot_be_changed_afterwards`; `a_result_for_an_id_that_is_not_one_of_its_calls_is_refused`.
  - `EventStreamHistoryTest`: `an_exchange_built_from_events_carries_each_calls_action_and_result`: events for a turn with two calls, one succeeded and one failed; assert `actionOf` for both and `resultOf` present for the first and empty for the second.
- [ ] **Step 2: Run** `./mvnw -q -pl :nessy-engine -am test -Dtest=EventStreamHistoryTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: does not compile.
- [ ] **Step 3: Implement.** The compact constructor copies both maps (`Map.copyOf`), refuses a call in `request` with no entry in `actions`, and refuses an entry in `results` whose id is not one of its calls. There is no shorter constructor. In `Transcript`, `Open.ask` takes the event's actions and keeps them by call id; the `ToolSucceeded` case records `done.rendered()` by call id as well as the outcome; `flush` builds the five-argument `Exchange`.
- [ ] **Step 4: Fix the tests that build an `Exchange`.** Give each call an action. Where a test file builds several, a small private helper in that file is fine; do not add a helper to production code.
- [ ] **Step 5: Run** `./mvnw -q clean verify -Dnessy.excludedGroups=live` (`Exchange` is used across modules, so the whole reactor is the honest check). Expected: exit 0.
- [ ] **Step 6: Commit** after `spotless:apply license:format`: `feat: an exchange says what each of its calls did and returned`.

### Task 7: The summariser is shown the lines

**Files:**
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/chapter/Transcripts.java` (`render`, and `ask` if it is no longer used)
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/chapter/ProseSummarizer.java`
- Modify: `nessy-examples/chapter-lab/src/main/java/org/jwcarman/nessy/examples/chapterlab/LabPolicies.java` only if `render`'s signature changes
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/chapter/ProseSummarizerTest.java`; a `TranscriptsTest` beside it (create if absent)
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/DirectHarnessChaptersTest.java` and `QueuedHarnessChaptersTest.java` (find their exact paths with `find . -name "*HarnessChaptersTest.java"`)

**Interfaces:**
- Consumes: `Exchange.actionOf`, `Exchange.resultOf` (Task 6); `Truncator.dropMiddle()`, `ToolConfig.DEFAULT_LINE_LIMIT`.

- [ ] **Step 1: Write the failing tests.**
  - `TranscriptsTest`, one test per line shape in spec §9, asserting the exact line:
    - `assistant did: <action> -- succeeded: <result>`
    - `assistant did: <action> -- succeeded` (empty result line)
    - `assistant did: <action> -- failed: <message>`
    - `assistant did: <action> -- denied: <reason>`
    - `assistant did: <action> -- no outcome recorded`
    - a failure message of 2,000 characters with line breaks is one line of at most 255 with `...` in its middle
    - commentary beside a call stays `assistant: <text>`; `user:` and the final `assistant:` lines are as today
    - the raw arguments and the raw result text of a successful call do not appear anywhere in the output
  - `ProseSummarizerTest`: `a_chapter_is_sent_as_one_message_of_text`: for a chapter with a tool turn, the request's `context().summaries()` and `context().tail()` are empty, its active turn has no exchanges, and its input is one `Block.Text` equal to `Transcripts.render(turns)` + a blank line + `"Write the record of everything above now."`; `no_tool_call_tool_outcome_or_reasoning_block_is_sent` (walk every block in the request); it is still a one-off. Replace the existing test `sends_exactly_the_chapters_turns_followed_by_the_turn_that_asks`, which pins the old shape.
  - Both chapter-harness tests: `a_chapter_whose_turns_called_a_tool_is_summarised`: a harness with one bound tool, a scripted agent provider that calls it and then answers, enough turns to close a chapter, and a summarising provider that returns a `Fault` for any request holding a `Block.ToolCall` or any exchange, and a summary otherwise. Assert the chapter ends up summarised and the summary request's text contains `assistant did:`.
- [ ] **Step 2: Run** `./mvnw -q -pl :nessy-engine -am test -Dtest='TranscriptsTest,ProseSummarizerTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: fail.
- [ ] **Step 3: Implement.** `Transcripts.render`: for each exchange, any commentary as today, then one line per call from `exchange.calls()`: `"assistant did: " + exchange.actionOf(id) + " -- " + ending`, where the ending comes from that call's outcome in `exchange.outcomes()` (matched by call id, not by position): succeeded with `resultOf(id)` (omit `": "` and the line when it is empty), failed, denied, or `no outcome recorded`. A failure's message and a denial's reason are made one line and cut with `Truncator.dropMiddle()` at `ToolConfig.DEFAULT_LINE_LIMIT`; reuse a `Stringifier` wrapper for that so the whitespace rule is the same one. `ProseSummarizer.summarize`: build one turn whose input is the rendered text, a blank line, and the ask; send `new InferenceContext(List.of(), List.of(), List.of(), List.of(), thatTurn, List.of())`; keep `Toolset.none()`, the options and `.asOneOff()`. Update the class javadoc: what is sent, and why a chapter is sent as text (a model given tool history and no tools may answer nothing: measured 2026-10-02 on claude-sonnet-4-5, five empty replies of five). Remove `Transcripts.ask` if nothing uses it.
- [ ] **Step 4: Run** `./mvnw -q -pl :nessy-engine,:nessy-example-chapter-lab -am test`. Expected: pass.
- [ ] **Step 5: Gate and commit.** `spotless:apply license:format`, `./mvnw -q clean verify -Dnessy.excludedGroups=live`. Commit: `fix: a chapter is summarised from a transcript of text, so one with tool calls can be`.

### Task 8: A live test that a chapter with tool calls is summarised

**Files:**
- Modify: `nessy-inference/anthropic/src/test/java/org/jwcarman/nessy/inference/anthropic/AnthropicLiveTest.java`

**Interfaces:**
- Consumes: nothing new from the engine. `nessy-inference-anthropic` does not depend on `nessy-engine`, so this test builds the request `ProseSummarizer` now sends by hand.

- [ ] **Step 1: Read `AnthropicLiveTest`** for how it builds a provider, skips without a key, and names its model.
- [ ] **Step 2: Add** `a_chapter_written_out_as_text_with_tool_lines_is_summarised`: system prompt is the text of `ProseSummarizer.PROMPT` copied into the test as a constant (say so in a comment, with the class name); one user message holding this transcript and the ask:

```text
user: my birthday is on 12/21
assistant did: DaysUntilRequest[date=2025-12-21] -- succeeded: -285 days
assistant did: remember the birthday -- succeeded: noted
assistant: Got it! Your birthday was about 9.5 months ago.
user: how about my upcoming birthday in 2026
assistant did: DaysUntilRequest[date=2026-12-21] -- succeeded: 80 days
assistant: Your birthday is in 80 days.

Write the record of everything above now.
```

  no tools, `maxTokens` 1024, sent as a one-off. Assert the result is an `InferenceResult.Answer` with non-blank text containing `80`. Javadoc: on 2026-10-02 the same chapter sent as messages with tool blocks and no tools declared came back empty five times of five on claude-sonnet-4-5; this is what is sent instead.
- [ ] **Step 3: Verify it compiles and is skipped by default.** `./mvnw spotless:apply license:format -pl :nessy-inference-anthropic`, `./mvnw -q -pl :nessy-inference-anthropic -am clean verify`. Do not run it live; there is no key.
- [ ] **Step 4: Commit:** `test: live, a chapter with tool calls in it is summarised from its transcript`.

### Task 9: Documentation

**Files:**
- Modify: `docs/concepts/tools.md`, `docs/concepts/authorization.md`, `docs/guides/harness.md`, `docs/guides/getting-started.md`, `docs/guides/mcp-clients.md`, `docs/index.md`, `README.md` (each names `ActionRenderer` or `.action(` today)
- Modify: the guide that describes chapters and the summariser (`grep -rln "ProseSummarizer\|ChapterPolicy" docs --include=*.md | grep -v superpowers`)
- Modify: `CHANGELOG.md`, `[Unreleased]`

- [ ] **Step 1: Read the code** the docs describe, so every statement is true of it: `Stringifier`, `Truncator`, `ToolConfig`, `ToolBinding.describe` and `rendered`, `Exchange`, `Transcripts.render`, `ProseSummarizer`.
- [ ] **Step 2: Write.** What a `Stringifier` is and its three droppers, with one short example; the two lines a binding writes and when each is stored; the default (255, action keeping its start, result dropping its middle) and the cap (1,000); that overriding `toString()` on a tool's input record is the simplest way to a good action line, and that a gated tool the application did not write should have its sentence set on the binding; what an approver is shown; what the summariser is shown, with the line shapes, and that the turn being answered still gets every call and result whole. Replace every mention of `ActionRenderer`.
- [ ] **Step 3: Changelog.** Added: `Stringifier`, `Truncator`, `ToolConfig.result`, the stored lines, `Exchange.actionOf` and `resultOf`. Changed: `ToolConfig.action` takes a `Stringifier`; a chapter is summarised from text; `Exchange`, `ActionRequest.ToolCall` and `ToolSucceeded` each gained components; a database written by an earlier build cannot be read and must be recreated. Removed: `ActionRenderer`. Fixed: a chapter whose turns called a tool could not be summarised on Anthropic.
- [ ] **Step 4: Check and commit.** `./mvnw -q spotless:check license:check`; build the docs the way the repository does if it has a docs build (`mkdocs build --strict`). Commit: `docs: what a tool call did is written down in words, and what reads it`.

## After the last task

Run `chat-web` as on 2026-10-02 (README, the Anthropic command with `CHAT_CHAPTER_TURNS=4`), on a recreated database, and hold a conversation whose second chapter calls tools. The chapter that failed that day must be summarised. This is James's to run; it needs his key.
