# The Agent's Story, Plan 2: Stored Fields — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The story says which call each call event is about, who decided, why a call failed, that an answer was cut off, and what started a turn and when its input arrived.

**Architecture:** Each new fact is produced where it is known (a handler, the terms, a harness), carried on the outcome and the command, copied by the fold onto the stored event it writes, and told by the adapter. The fold decides nothing on any of them. One stored event is renamed.

**Tech Stack:** Java 25, Maven (`./mvnw`), JUnit 5, AssertJ, Awaitility, Testcontainers (PostgreSQL 18), Jackson 3.

**Spec:** `docs/superpowers/specs/2026-10-04-agent-story-design.md`. Read §3, §6 and all of §10 before any task. Plan 1 (`2026-10-04-agent-story-1-reading.md`) is merged; this plan builds on it.

## This plan touches the fold

Every task except Task 7 edits `nessy-engine/src/main/java/org/jwcarman/nessy/engine/core/AgentState.java`. **What a task may do there is exactly this, and nothing else:**

- add a component to a command, and copy it onto the event the existing code already writes for that command;
- copy `OutstandingAction.idempotencyKey()` onto an event the existing code already writes for that call;
- rename the stored policy event.

**No guard, no `when` clause, no phase check, no effect, no state transition, no `TurnPolicy` call and no `Decision.ignore()` changes.** A task that seems to need one stops and reports.

Stored shapes change. Databases are recreated; there is no migration.

## Global Constraints

- The fold rule above binds every task.
- **Red first, in the fold's own tests.** Each task adds its fold test to `AgentStateTest` and sees it fail before touching `AgentState`.
- **Nothing else moved.** `AgentStateTest`, `AgentStateRepeatedCallIdTest`, `UnattributableAnswerTest`, `RepeatedCallIdLateOutcomeTest`, `DispatcherFailureTest`, `GiveUpTest` and `HarnessLoopTest` change only where a constructor gained an argument. No assertion in them is removed or weakened. Each task's report lists the pre-existing test files it edited and why.
- **Replay equals live.** Each task that adds a stored field adds an assertion, to the story-equivalence tests plan 1 created (`EventAgentStoriesTest`, `EventAgentStoriesQueuedTest`), that the field a listener heard equals the field `replay` returns.
- **The stored shape is written out by hand.** Each task that changes a stored event adds or updates a `ValueTypeCodecTest` case with the event's JSON as a literal string, read and written.
- New public names in `nessy-api` are exactly those the task names, all from spec §12. A task that needs another stops and reports.
- No `@SuppressWarnings`, `// NOSONAR` or any other suppression. No star imports. No mocking library. Prose test names. One throwing call inside an exception-assertion lambda (S5778). Non-emptiness asserted before an all-match or none-match (S5841).
- `./mvnw spotless:apply license:format` before each commit. Scoped builds with `-am`. One Maven process at a time. Before the gate: `./mvnw -B -q clean test-compile; echo $?` is 0.
- A task's last step is the gate: `./mvnw -B -q clean verify -Dnessy.excludedGroups=live; echo $? > target/gate.exit`, and Maven's exit code is read from that file.
- **Review:** every task that edits `AgentState` gets the high-risk review on Opus. The reviewer is told to read the `AgentState` diff line by line against "This plan touches the fold" and report anything that is not on that list.
- Docs describe what is, in plain American wording, with no history. Each task updates `docs/guides/narration.md`'s event table, `docs/concepts/events.md` where it lists the stored event, and `CHANGELOG.md`.

## Review Focus

1. **A stale answer still names the right call.** A late `CompleteToolCall` for an earlier request with the same call id is ignored today; with the key on the event, the test asserts no event is written at all, so no key can be wrong. (Task 2)
2. **A failed call that never ran.** An approval that expires discharges its call as failed while the call is still `AWAITING_APPROVAL`; the event must carry `NOT_AUTHORISED` and the call's key. (Tasks 2 and 4)
3. **An undecodable effect row.** Its stored failure names neither turn nor request; the kind it carries must still reach the event. (Task 4)
4. **A truncated answer through the direct door** must be `truncated` in the story and still returned to the caller as today. (Task 5)
5. **An input whose label throws or returns blank.** The turn must still start, with the default label. (Task 6)

---

### Task 1: The stored policy event is `TurnStopped`

**Files:** `nessy-backend/spi/.../event/AgentEvent.java` (the record and its `@JsonSubTypes` name: `turn-failed` → `turn-stopped`); every reference: `AgentState.java` (lines 185, 446), `TurnTally.java` (57, 109), `DefaultDirectHarness.java` (895), `Transcript.java` (117, 142), `StoryEvents.java` (73), `StoredContent.java` (264); tests; `docs/concepts/events.md`, `docs/concepts/turn-policy.md`; `TurnDecision` javadoc.

**Interfaces:** Produces `AgentEvent.TurnStopped(Seq seq, TurnId turn, String reason)`, stored under the type name `turn-stopped`. `AgentEvent.TurnFailed` no longer exists.

A rename and nothing else: no component, no behaviour.

- [ ] **Step 1:** In `ValueTypeCodecTest` add `a_stored_policy_stop_is_written_as_turn_stopped`: the literal `{"type":"turn-stopped","seq":3,"turn":1,"reason":"too many calls"}` decodes to `new AgentEvent.TurnStopped(new Seq(3), new TurnId(1), "too many calls")` and encodes back to the same fields. Run it; it fails to compile.
- [ ] **Step 2:** Rename the record and its JSON name. Update every reference. In `AgentState` the two sites change name only.
- [ ] **Step 3:** `./mvnw -B -q clean test-compile; echo $?` → 0. `git diff --stat` on `AgentState.java` shows two changed lines.
- [ ] **Step 4:** Docs and CHANGELOG (`### Breaking changes`: "The stored event for a turn a policy stopped is `turn-stopped`; recreate the database."). Gate. Commit: `refactor: the stored event for a policy stop is TurnStopped, as the story tells it`.

---

### Task 2: Every call event carries the call's key

**Files:** `AgentEvent.java` (`ToolApproved`, `ToolDenied`, `ToolSucceeded`, `ToolFailed` each gain a trailing `IdempotencyKey idempotencyKey`, null refused); `AgentState.java` (the four construction sites at about lines 378, 383, 407, 409); `Narration.java` (`CallApproved`, `CallDenied`, `CallFinished`, `CallFailed` each gain `IdempotencyKey idempotencyKey` after `callId`); `StoryEvents.java`; `StoredContent.java` (match results by key where it matches by call id within a request); consumers of the four narration records (`ConsoleNarration`, watchman `ApprovalsDesk`, chat-web `app.js` if it reads them); tests; docs.

**Interfaces:**
- Produces stored: `ToolApproved(seq, turn, callId, reference, idempotencyKey)`, `ToolDenied(seq, turn, callId, reason, reference, idempotencyKey)`, `ToolSucceeded(seq, turn, callId, result, rendered, idempotencyKey)`, `ToolFailed(seq, turn, callId, message, idempotencyKey)`. (Task 3 replaces `reference`; Task 4 adds `kind`.)
- Produces narration: `CallApproved(CallId callId, IdempotencyKey idempotencyKey)`, `CallDenied(CallId callId, IdempotencyKey idempotencyKey, String reason)`, `CallFinished(CallId callId, IdempotencyKey idempotencyKey)`, `CallFailed(CallId callId, IdempotencyKey idempotencyKey, String message)`.

In the fold, `approved(...)` and `ran(...)` already hold `OutstandingAction call`; the key is `call.idempotencyKey()`. Nothing is looked up.

- [ ] **Step 1: Fold tests, red.** In `AgentStateTest`, for an agent awaiting one call whose `ActionRequest.ToolCall` was recorded with key `K`:
  - `an_approval_is_recorded_with_the_calls_key`: `CompleteApproval(Approved)` writes `ToolApproved` whose `idempotencyKey()` is `K`.
  - `a_denial_is_recorded_with_the_calls_key`
  - `a_result_is_recorded_with_the_calls_key`
  - `a_failure_is_recorded_with_the_calls_key`
  - `a_call_that_failed_before_it_was_approved_is_recorded_with_its_key` (Review Focus 2: `CompleteToolCall(Failed)` while `AWAITING_APPROVAL`)
  - `two_calls_in_one_request_are_each_recorded_with_their_own_key`
  - `a_late_answer_for_an_earlier_request_writes_nothing` (Review Focus 1: the existing ignore, asserted as `Decision.ignore()`)
- [ ] **Step 2:** Run them; they fail to compile (`idempotencyKey()` does not exist on the events).
- [ ] **Step 3:** Add the component to the four events and pass `call.idempotencyKey()` at the four sites. No other line of `AgentState` changes.
- [ ] **Step 4:** Narration, the adapter, and `StoredContent`'s matching by key. Consumers. `ValueTypeCodecTest` literals for the four events. The story-equivalence tests assert each call event's key heard live equals the replayed one, and equals the key on that call's `ActionsRequested.Call`.
- [ ] **Step 5:** Docs, CHANGELOG (`### Breaking changes`: the four narration records gain the key; stored call events change shape). Gate. Commit: `feat: every call event names its call by its idempotency key`.

---

### Task 3: `decidedBy` replaces `reference`

**Files:** `nessy-api/.../tool/ApprovalResult.java`; `EffectOutcome.java` (`ToolApproved`, `ToolDenied`); `AgentCommand.java` (`ApprovalOutcome.Approved`, `Denied`); `AgentEvent.java` (`ToolApproved`, `ToolDenied`); `AgentState.java` (378, 383: the accessor name); `EffectOutcomes.java` (123-130); `ApprovalHandler.java` (182, 190); `DefaultReplies.java` (105, 107); `Narration.java` (`CallApproved`, `CallDenied` gain `Optional<String> decidedBy`); `StoryEvents.java`; `nessy-approval/*` and the examples that call `approvedBy` / `deniedBy` or read `reference()`; tests; `docs/concepts/authorization.md`.

**Interfaces:**
- `ApprovalResult.Approved(Optional<String> decidedBy)`, `Denied(String reason, Optional<String> decidedBy)`; `ApprovalResult.decidedBy()`; `approvedBy(String decidedBy)`, `deniedBy(String reason, String decidedBy)`. `reference` is gone everywhere, under that name.
- Narration: `CallApproved(CallId callId, IdempotencyKey idempotencyKey, Optional<String> decidedBy)`, `CallDenied(CallId callId, IdempotencyKey idempotencyKey, String reason, Optional<String> decidedBy)`.

A rename of one carried value from end to end. The fold copies it as it copied `reference`.

- [ ] **Step 1: Fold tests, red.** `an_approval_records_who_decided`, `a_denial_records_who_decided`, `an_approval_nobody_is_named_for_records_no_one` in `AgentStateTest`. `ApprovalResultTest`: `approvedBy` and `deniedBy` carry `decidedBy`; a blank one is refused as a blank reference is today.
- [ ] **Step 2:** Rename through every layer. In `AgentState` only the accessor name at the two sites changes.
- [ ] **Step 3:** `git grep -n 'reference' -- '*.java' ':!docs'` finds no approval reference left (other uses of the word, such as payload references, stay).
- [ ] **Step 4:** Adapter, narration, `ValueTypeCodecTest` literals, story-equivalence assertion on `decidedBy`. Docs: `authorization.md` says what `decidedBy` is for, that Nessy never interprets it, and that the `IdempotencyKey` is the join to the application's own record. CHANGELOG breaking change. Gate. Commit: `feat: an approval records who decided, not a reference`.

---

### Task 4: Why a call failed

**Files:** create `nessy-api/.../CallFailure.java`; `EffectOutcome.ToolFailed`; `AgentCommand.ToolOutcome.Failed`; `AgentEvent.ToolFailed`; `AgentState.java` (409); `EffectOutcomes.java` (120-121); producers `EffectTermsSource.java` (145, 151, 167, 173), `ApprovalHandler.java` (110, 122, 138, 167), `ToolCallHandler.java` (113, 124, 173), `DefaultReplies.java` (123); `Narration.CallFailed`; `StoryEvents.java`; tests; docs.

**Interfaces:**
- `public enum CallFailure { FAILED, PAST_DEADLINE, NOT_AUTHORISED }` in `org.jwcarman.nessy.api`.
- `EffectOutcome.ToolFailed(CallId callId, CallFailure kind, String message)`; `AgentCommand.ToolOutcome.Failed(CallFailure kind, String message)`; `AgentEvent.ToolFailed(seq, turn, callId, kind, message, idempotencyKey)`; `Narration.CallFailed(CallId callId, IdempotencyKey idempotencyKey, CallFailure kind, String message)`.

Each producer says the kind it already says in prose (spec §6d):

| Producer | Kind |
|---|---|
| `EffectTermsSource`, the tool call's deadline passed (145) | `PAST_DEADLINE` |
| `EffectTermsSource`, the tool call threw (151) | `FAILED` |
| `EffectTermsSource`, the approval's deadline passed (167) or the approver threw (173) | `NOT_AUTHORISED` |
| `ApprovalHandler` 110, 122, 138: no such call, could not be described, arguments unreadable | `FAILED` |
| `ApprovalHandler` 167: the approver threw | `NOT_AUTHORISED` |
| `ToolCallHandler` 113, 124: no such tool, no such call; 173: the tool returned a failure | `FAILED` |
| `DefaultReplies` 123: a deferred tool answered with a failure | `FAILED` |

- [ ] **Step 1: Fold test, red.** `a_failed_call_is_recorded_with_why_it_failed`, parameterized over the three kinds, and `a_call_that_was_never_authorised_is_recorded_as_such_while_it_still_awaited_approval` (Review Focus 2).
- [ ] **Step 2: Producer tests, red:** in `EffectTermsSourceTest`, `ApprovalHandlerTest`, `ToolCallHandlerTest`, one assertion on `kind` for each row of the table. In `DispatcherFailureTest`, `an_undecodable_effect_delivers_its_stored_failure_with_its_kind` (Review Focus 3).
- [ ] **Step 3:** Add the enum and carry it. In `AgentState` one constructor call gains one argument.
- [ ] **Step 4:** Adapter, narration, literals, story-equivalence (a call that fails at its deadline reads `PAST_DEADLINE` live and on replay). Docs, CHANGELOG. Gate. Commit: `feat: a failed call says whether it failed, ran past its deadline, or was never authorised`.

---

### Task 5: An answer that was cut off says so

**Files:** `EffectOutcome.InferenceAnswered`; `AgentCommand.InferenceOutcome.Answered`; `AgentEvent.InferenceAnswered`; `AgentState.java` (250); `EffectOutcomes.java` (100-101); `InferenceHandler.java` (the `Answer` and `Truncated` arms, about lines 108-124); `Narration.Answered`; `StoryEvents.java`; tests; docs.

**Interfaces:** `EffectOutcome.InferenceAnswered(PayloadRef answer, boolean truncated, Usage usage)`; `InferenceOutcome.Answered(PayloadRef answer, boolean truncated, Usage usage)`; `AgentEvent.InferenceAnswered(seq, turn, answer, truncated, usage)`; `Narration.Answered(TurnId turn, boolean truncated, Usage usage)`.

- [ ] **Step 1: Fold test, red:** `a_truncated_answer_is_recorded_as_truncated` and `a_whole_answer_is_not`. Handler test in `InferenceHandlerTest`: `InferenceResult.Truncated` yields `truncated = true`; `Answer` yields `false`.
- [ ] **Step 2:** Carry it. The handler's `Truncated` arm passes `true`; its WARN stays.
- [ ] **Step 3:** Direct-door test (Review Focus 4): a truncated reply is returned to `ask`'s caller exactly as today, and the story's `Answered` is `truncated`.
- [ ] **Step 4:** Adapter, literal, story-equivalence, docs, CHANGELOG. Gate. Commit: `feat: the story says when an answer was cut off at the output limit`.

---

### Task 6: What started a turn, and when its input arrived

**Files:** `AgentCommand.StartTurn`; `AgentEvent.TurnStarted`; `AgentState.java` (157); `DirectHarnessConfig`, `QueuedHarnessConfig` and their engine implementations (`inputLabel`); `DefaultDirectHarness.java` (381), `DefaultQueuedHarness.java` (334); `Narration.TurnStarted`; `StoryEvents.java`; tests; `docs/guides/harness.md`, `narration.md`.

**Interfaces:**
- `StartTurn(PayloadRef input, String label, Instant arrivedAt, Instant at)`; `AgentEvent.TurnStarted(seq, turn, input, label, arrivedAt, startedAt)`; `Narration.TurnStarted(TurnId turn, String label, Instant arrivedAt)`.
- `DirectHarnessConfig<I> inputLabel(Stringifier<I> label)` and the same on `QueuedHarnessConfig<I>`.

The label is worked out by the harness, before the fold: the configured `Stringifier`, or the input's simple class name when none is configured, when the stringifier throws, or when it returns null or a blank string (Review Focus 5; a WARN names the agent type when a configured one failed). On the queued door `arrivedAt` is the taken `BacklogItem.arrivedAt()`; on the direct door it is the instant `ask` read from its clock for the turn.

- [ ] **Step 1: Fold test, red:** `a_turn_starts_with_the_label_and_arrival_it_was_given`.
- [ ] **Step 2: Harness tests, red,** on both doors: a configured label reaches the story; with none configured the label is the input's simple class name; a label that throws, and one that is blank, fall back to it and the turn still runs; on the queued door `arrivedAt` is when `tell` was called, earlier than the event's own time when the agent was busy.
- [ ] **Step 3:** Carry both. In `AgentState` one constructor call gains two arguments.
- [ ] **Step 4:** Adapter, literal, story-equivalence, docs (the harness guide shows `inputLabel`; the narration guide says how long an input waited is the event's time minus `arrivedAt`), CHANGELOG. Gate. Commit: `feat: a turn's start says what started it and when its input arrived`.

---

### Task 7: The deadline that is shown is the deadline that is kept

Not a fold task. **Files:** `EffectHandler` and `EffectHandlers` (the handler is handed the attempt's deadline), `EffectDispatcher.java` (passes `attempt.deadline()`), `DefaultDirectHarness.java` (passes the deadline it already computes for an inline effect), `ApprovalHandler.java`, `ToolCallHandler.java`, `ToolBinding.java` (`question(...)` and `call(...)` take the deadline instead of computing `askedAt + approvalTimeout` and `now + timeout`); tests; docs.

**Interfaces:** `EffectHandler<E>.handle(AgentId agentId, E effect, Instant deadline)`. `ApprovalRequest.deadline()`, `ToolCallRequest.deadline()`, and the `until` of the live `ApprovalDeferred` and `CallDeferred` are that instant.

- [ ] **Step 1: Tests, red.** In `ApprovalHandlerTest` and `ToolCallHandlerTest`: with an effect whose deadline is `T`, handled at a clock reading later than when it was written, the `ApprovalRequest.deadline()`, the `ToolCallRequest.deadline()` and each deferral's `until` equal `T`. In the queued-door test: an approval asked late, after its row waited in the queue, shows the row's deadline.
- [ ] **Step 2:** Thread the deadline through. No retry, expiry or fence logic in the dispatcher changes; it passes a value it already holds.
- [ ] **Step 3:** Docs (`authorization.md`, `tools.md`: the deadline shown is the one the call is held to), CHANGELOG `### Fixed`. Gate. Commit: `fix: the deadline an approver and a tool are shown is the one the call is held to`.

---

## After the last task

The final whole-branch review (Opus) is given the spec, this plan and the branch diff, and is asked to: read the whole `AgentState.java` diff line by line against "This plan touches the fold" and list every line that is not a carried field or the rename; confirm that the pre-existing fold and dispatcher tests lost no assertion; and confirm the story a listener hears equals the story `replay` returns for every changed event.
