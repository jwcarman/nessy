# The Agent's Story, Plan 3: Deferrals on the Record — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When an approver or a tool defers, the story says so when it happens: a stored event, a story narration, and a mark on the effect row.

**Architecture:** A handler that defers hands the dispatcher what it knows (for an approval, the stored question). The dispatcher runs **the park step**: one locked step on the queued harness that gives the fold a `DeferApproval` or `DeferToolCall` command and, in the same transaction, marks the effect row `parked_at` with `actionable_at = deadline`. The fold writes `ApprovalDeferred` or `ToolDeferred`, emits no effect and changes no outstanding call. The adapter tells each as one story event.

**Tech Stack:** Java 25, Maven (`./mvnw`), JUnit 5, AssertJ, Awaitility, Testcontainers (PostgreSQL 18), Jackson 3.

**Spec:** `docs/superpowers/specs/2026-10-04-agent-story-design.md`. Read §3, §7 and all of §10 before any task. Plans 1 and 2 are merged, and so is Part A of plan 4 (`Payloads.putDocument`).

**Order of execution:** see "Order of execution" in `2026-10-04-agent-story-4-approval-question.md`. This plan runs in its own worktree, at the same time as plan 5.

## This plan changes what the fold does

This is the one plan of the five that adds behaviour to `AgentState`. **The whole of what it may add is spec §10a(1), and nothing else:**

| State | `DeferApproval` | `DeferToolCall` |
|---|---|---|
| `Idle`, `Inferring`, `Terminal` | ignore | ignore |
| `AwaitingActions`, another turn or another `requestSeq` | ignore | ignore |
| `AwaitingActions`, the call is not outstanding | ignore | ignore |
| `AwaitingActions`, the call is `AWAITING_APPROVAL` | write `ApprovalDeferred`; **no effects** | ignore |
| `AwaitingActions`, the call is `RUNNING` | ignore | write `ToolDeferred`; **no effects** |

- The guards are the ones `CompleteApproval` and `CompleteToolCall` already apply, in the same order: turn, then request, then the call and its phase.
- `AwaitingActions.accept` takes `ApprovalDeferred` and `ToolDeferred` and returns the same state with the new `seq`: the same `outstanding` map (each call in the same phase with the same `since`), the same `requestSeq`, the same `TurnStats`. Every other state rejects them through its existing `default -> throw unexpected(...)`.
- Neither command consults the `TurnPolicy`, calls `continuing(...)`, or emits an effect.

**What must not change (spec §10b):** every existing guard; what each existing command writes and which effects it emits; `OutstandingAction`, its two phases and what `since` means; when a turn ends; that a state rebuilt from stored events equals the state that wrote them. `Idle`, `Inferring` and `Terminal` are not edited at all: their `execute` already ignores an unknown command and their `accept` already rejects an unknown event.

Stored shapes change, and the effect table gains a column. Databases are recreated; there is no migration.

## Resolutions this plan makes

Each is a choice the spec left open. They are listed so James can overrule one before it is built.

1. **The two stored deferral events carry the call's `IdempotencyKey`,** copied by the fold from the call's `OutstandingAction`, as the four call events do. Spec §6a lists them without it, but the story narrations of §3 (`ApprovalDeferred(callId, idempotencyKey, until)`, `CallDeferred(callId, idempotencyKey, until)`) need it, and the adapter maps one stored event to one narration with nothing else to read.
2. **Handlers return an engine-internal `Handled`, not `Awaited<EffectOutcome>`.** `Awaited.Deferred` is public API and carries nothing, so a deferral's question has no way to the dispatcher. `Handled` is `Settled(EffectOutcome outcome)` or `Deferred(Optional<PayloadRef> question)`. It is public only because the direct harness is in another package; nothing in `nessy-api` names it.
3. **The effect SPI gains `Effects.park(UUID effectId, int attemptsMade, Instant at)`,** returning whether the row was marked.
4. **The direct door no longer narrates a deferral.** It cannot defer: a deferral there is a failed call, as today. As a story event, a deferral is heard only if it was stored (spec §13.6), so the live narration the handlers send today goes away on both doors.
5. **Between Task 2 and Task 5 of this plan, a deferral is not narrated at all.** Task 2 turns the two narrations into story events and removes the handlers' live sends; Task 5 lands the park step that tells them. The assertions on the live narration (named in Task 2) are removed in Task 2 and replaced in Task 5; the final review checks each has its replacement.
6. **`ApprovalHandler` gains a `Payloads` and stores the question when the approver defers.** The builder (`ApprovalQuestions.document`) lands here because the deferral needs it; plan 4 Part B pins it further and uses it for decisions made at once.

## Global Constraints

- The fold table above binds Task 3, and "what must not change" binds every task.
- **Red first.** Every task writes its tests first and sees them fail for the right reason.
- **Each cell has a test (Task 3).** Every cell of the table has a test that fails before the change, and so does each "ignore": a deferral for another turn, for another request, for a discharged call, and for a call in the other phase. A test walks every state against both new commands, and every state against both new events.
- **Replay equals live.** For each scripted scenario with a deferral, the state reconstituted from the stored events equals the state held when they were written; and the story a listener heard equals what `replay` returns (same events, same `seq`, same `at`), on the queued door against PostgreSQL.
- **Nothing else moved.** `AgentStateTest`, `AgentStateRepeatedCallIdTest`, `UnattributableAnswerTest`, `RepeatedCallIdLateOutcomeTest`, `DispatcherFailureTest`, `GiveUpTest` and `HarnessLoopTest` lose no assertion. Tests that pin a deferred story by position (`DeferredApprovalTest`, `DeferredToolTest`, `ToolCallingTest`) change only by the inserted deferral event: each shifted index and `Seq` is changed to the value the new event forces, the inserted event is itself asserted, and the report lists every such line.
- **The effect table and the dispatcher took a long time to get right.** The claim, both fences (`status`, `attempts_made`), retire, reschedule, expiry and retry are unchanged. The only new statement is the park `UPDATE`; the only new code path in the dispatcher is the `Deferred` arm.
- **The stored shape is written out by hand:** `ValueTypeCodecTest` literals for both new events, read and written, with real values.
- New names are exactly those the tasks give. In `nessy-api`: the reshaped `Narration.ApprovalDeferred` and `Narration.CallDeferred` (spec §3). In the backend SPI: `AgentEvent.ApprovalDeferred`, `AgentEvent.ToolDeferred`, `Effects.park`, the `parked_at` column. Engine-internal: `AgentCommand.DeferApproval`, `AgentCommand.DeferToolCall`, `Handled`, `ApprovalQuestions`, `AgentEffectCallback.park`, `Outbox.park`. A task that needs another stops and reports.
- No `@SuppressWarnings`, `// NOSONAR` or any other suppression. No star imports. No mocking library. Prose test names. One throwing call inside an exception-assertion lambda (S5778). Non-emptiness asserted before an all-match or none-match (S5841). Tests wait on latches, Awaitility or a controllable clock, never a sleep.
- `./mvnw spotless:apply license:format` before each commit. Scoped builds by artifactId with `-am`. One Maven process in a worktree. Before the gate: `./mvnw -B -q clean test-compile; echo $? > target/tc.exit` reads 0.
- A task's last step is the gate: `./mvnw -B -q clean verify -Dnessy.excludedGroups=live; echo $? > target/gate.exit`, and Maven's exit code is read from that file.
- **Review:** Tasks 1, 3 and 5 get the high-risk review on Opus (the effect table, the fold, the dispatcher). Tasks 2, 4 and 6 get the task review on Sonnet. The final whole-branch review is on Opus.
- Docs describe what is, in plain American wording, with no history.

## Review Focus

1. **An answer that lands before the park step.** A reply settles the call first; the park step's command is ignored, no event is written, and the row (already gone) is not marked. Zero rows from the park `UPDATE` is not an error. (Tasks 3 and 5)
2. **A park step that throws.** The row is left exactly as it was claimed, the call is not failed, the approver is not asked again, and a WARN is logged. The exception must not reach the dispatcher's `catch`, which would fail or retry the call. (Task 5)
3. **A claimer whose clock is behind the writer's.** Today a claimed row's `actionable_at` is `LEAST(now + timeout, deadline)`; with a slow clock it comes due before its deadline and the approver is asked twice. After the park, `actionable_at = deadline`, and no claim before the deadline takes it. (Task 1)
4. **A deferral for a call in the other phase.** `DeferApproval` for a `RUNNING` call and `DeferToolCall` for a call `AWAITING_APPROVAL` are ignored. (Task 3)
5. **A redelivered deferral.** The same `DeferApproval` twice writes two events only if the fold accepts twice; the second park finds the row already parked. Decide by test what the fold does (the table says: the call is still `AWAITING_APPROVAL`, so it writes again) and make the dispatcher never send it twice for one attempt: a parked row is not performed again before its deadline. (Tasks 3 and 5)

---

### Task 1: A parked row is marked, and comes due only at its deadline

Not a fold task. High-risk: the effect table.

**Files:** `nessy-backend/jdbc/src/main/resources/nessy-schema.sql` (`parked_at TIMESTAMP WITH TIME ZONE` after `updated_at`, nullable, with a comment; update the `actionable_at` comment); `nessy-backend/spi/.../effect/Effects.java`; `nessy-backend/jdbc/.../JdbcEffects.java`; `nessy-backend/inmemory/.../InMemoryEffects.java` (a `parkedAt` field on `Row`); `nessy-engine/.../store/Outbox.java`; the test fakes that extend `Outbox` or implement `Effects` (`DispatcherFailureTest.Effects`, `MisroutedReplyTest.Rows`, `ProbedQueuedBackend`); tests `JdbcEffectClaimTest` (container), an in-memory twin; `docs/concepts/durable-computation.md`.

**Interfaces:**
- `boolean park(UUID effectId, int attemptsMade, Instant at);` on `Effects`: marks a `RUNNING` row of that attempt as parked at `at`, and makes it due at its deadline. False when no such row is there (it was settled, or another attempt holds it).
- JDBC, exactly:
  ```sql
  UPDATE nessy_agent_effect
     SET parked_at = ?, actionable_at = deadline, updated_at = ?
   WHERE effect_id = ? AND status = 'RUNNING' AND attempts_made = ?
  ```
- `Outbox.park(UUID effectId, int attemptsMade, Instant at)` delegates.
- `markRunning`, `runningFor`, `complete`, `reschedule`, `insert` and the `Attempt` record are not changed. There is no new status.

- [ ] **Step 1: Tests, red.** `a_parked_row_is_due_at_its_deadline` (after `park`, `actionable_at` equals `deadline` and `parked_at` equals `at`, read with raw SQL); `a_parked_row_is_not_claimed_before_its_deadline_when_the_claimers_clock_is_behind` (Review Focus 3: claim with a `now` where `now + timeout < deadline`, park, then claim at any `now` before the deadline takes nothing; before the fix the second claim takes it); `a_parked_row_is_claimed_at_its_deadline`; `parking_a_row_that_was_settled_marks_nothing`; `parking_under_another_attempts_number_marks_nothing`; `a_parked_row_is_still_found_running` (`runningFor` returns it, so a later reply still finds it); `a_parked_row_is_retired_as_any_other` (`complete` deletes it). The same cases against `InMemoryEffects`.
- [ ] **Step 2:** Implement in both stores and `Outbox`; the fakes gain the method.
- [ ] **Step 3:** Docs. Gate. Commit: `feat: an effect row can be parked, and a parked row comes due only at its deadline`.

### Task 2: The deferral events exist, and every reader names them

Not a fold-behaviour task: `AgentState` is not edited. The new events are rejected by every state until Task 3.

**Files:** `nessy-backend/spi/.../event/AgentEvent.java` (two records, two `@JsonSubTypes` names, the class javadoc); `nessy-api/.../Narration.java` (`ApprovalDeferred` and `CallDeferred` become `Story` with new components; javadoc rewritten: they are stored); `nessy-engine/.../narration/StoryEvents.java`; `nessy-engine/.../core/TurnTally.java` (`after`: the `-> stats` group; `inTurn`: `turn().equals(turn)`); `nessy-engine/.../history/Transcript.java` (both switches: nothing to transcribe, nothing referenced except `ApprovalDeferred.question`, which is not message content and is not listed); `nessy-engine/.../story/StoredContent.java` (`startOfTurnHolding`); `ApprovalHandler.java` and `ToolCallHandler.java` (remove the live `narrator.narrate(... ApprovalDeferred/CallDeferred ...)`; nothing else); consumers `OdysseyNarrator`, `ConsoleNarration`, `NarrationListenerConfig`, `Envelopes`; tests `ToolEventKeyTest`, `ValueTypeCodecTest`, `StoryEventsTest` (`everyKind()`), `NarrationCoverageTest` (the two names move from "said by the engine" to the story list), `NarrationListenerConfigTest`, `ConsoleNarrationTest`, `OdysseyNarratorTest`; docs `narration.md`, `events.md`.

**Interfaces:**
- `AgentEvent.ApprovalDeferred(Seq seq, TurnId turn, CallId callId, Instant until, PayloadRef question, IdempotencyKey idempotencyKey)`, stored as `approval-deferred`. `until`, `question` and `idempotencyKey` are refused when null.
- `AgentEvent.ToolDeferred(Seq seq, TurnId turn, CallId callId, Instant until, IdempotencyKey idempotencyKey)`, stored as `tool-deferred`. `until` and `idempotencyKey` are refused when null.
- `Narration.ApprovalDeferred(CallId callId, IdempotencyKey idempotencyKey, Instant until) implements Story`; `Narration.CallDeferred(CallId callId, IdempotencyKey idempotencyKey, Instant until) implements Story`. The `action` and `toolName` components are gone: a watcher joins on the key to the `ActionsRequested.Call` that has them.
- `StoryEvents.of`: `ApprovalDeferred` → `Narration.ApprovalDeferred`; `ToolDeferred` → `Narration.CallDeferred`.
- Neither event adds to a turn's exchanges, tally or usage.

**Assertions removed here and replaced in Task 5** (list them in the report; the final review checks each): `NarrationTest` (about lines 227-269: the live `ApprovalDeferred` with its call, action and `until`), `ApprovalHandlerTest` (about 280-288: a deferral is narrated), `ToolCallHandlerTest` (about 418-422). In the two handler tests the replacement lands in Task 4 (the handler returns the deferral); in `NarrationTest` it lands in Task 5.

- [ ] **Step 1: Tests, red.** `ToolEventKeyTest`: null refusals for both events. `ValueTypeCodecTest`: `anApprovalDeferralIsStoredWithItsDeadlineItsQuestionAndItsKey` and `aToolDeferralIsStoredWithItsDeadlineAndItsKey`, each a hand-written literal read and written. `StoryEventsTest`: one case per event, and `everyKind()` holds both. `TurnTally` tests: a turn's stats are the same with a deferral in its events. `EventStreamHistoryTest`: a turn with a deferral in it transcribes exactly as the same turn without one. `StoryContentTest`: `results` after a position inside a turn that holds a deferral starts at that turn.
- [ ] **Step 2:** Add the events and name them in every exhaustive switch. Turn the narrations into story events; update the consumers (Odyssey keeps the SSE names `approval-deferred` and `call-deferred`).
- [ ] **Step 3:** `./mvnw -B -q clean test-compile` on the whole reactor: examples and adapters compile.
- [ ] **Step 4:** Docs, CHANGELOG (`### Breaking changes`: the two narrations changed shape and are story events). Gate. Commit: `feat: a deferral is a stored event and a story event`.

### Task 3: The fold records a deferral

**The fold task.** High-risk review on Opus.

**Files:** `nessy-engine/.../core/AgentCommand.java` (two commands; the "Five of them" javadoc); `nessy-engine/.../core/AgentState.java` (`AwaitingActions.execute` and `AwaitingActions.accept` only); `AgentStateTest`; a new `AgentStateDeferralTest` if `AgentStateTest` is the wrong home for the table walk.

**Interfaces:**
- `AgentCommand.DeferApproval(TurnId turn, Seq requestSeq, CallId callId, Instant until, PayloadRef question)`.
- `AgentCommand.DeferToolCall(TurnId turn, Seq requestSeq, CallId callId, Instant until)`.
- In `AwaitingActions.execute`, beside the existing guards and in the same order: another turn → ignore; another `requestSeq` → ignore; then, for `DeferApproval`: the call is outstanding and `AWAITING_APPROVAL` → `Decision.of(List.of(new AgentEvent.ApprovalDeferred(seq.next(), turn, callId, until, question, call.idempotencyKey())), List.of())`, else ignore; for `DeferToolCall`: the call is outstanding and `RUNNING` → the `ToolDeferred` likewise, else ignore.
- In `AwaitingActions.accept`: both events → `new AwaitingActions(event.seq(), turn, requestSeq, outstanding, stats)`.

- [ ] **Step 1: Tests, red, one per cell and one per ignore.** In a nested class `Deferring`:
  - `an_approval_that_is_deferred_is_recorded_and_nothing_else_happens` (one event, no effects; the event's `until`, `question` and key).
  - `a_tool_call_that_is_deferred_is_recorded_and_nothing_else_happens`.
  - `a_deferral_changes_no_outstanding_call` (the state after equals the state before but for `seq`: same map, same phases, same `since`, same `requestSeq`, same stats).
  - `an_approval_deferred_for_another_turn_is_ignored`, `..._for_another_request_is_ignored`, `..._for_a_call_that_was_settled_is_ignored`, `..._for_a_call_that_is_already_running_is_ignored`.
  - `a_tool_deferral_for_another_turn_is_ignored`, `..._for_another_request_is_ignored`, `..._for_a_call_that_was_settled_is_ignored`, `..._for_a_call_still_awaiting_approval_is_ignored`.
  - `a_deferral_does_not_ask_the_turn_policy` (a policy that throws if consulted).
  - `an_approval_after_its_deferral_runs_the_call_as_before` and `a_denial_after_its_deferral_ends_the_call_as_before`, `a_result_after_a_tool_deferral_ends_the_call_as_before`, `a_failure_after_a_deferral_ends_the_call_as_before`: the events and effects are exactly those of the same scenario without the deferral.
  - `with_other_calls_outstanding_a_deferral_leaves_them_as_they_were` (three calls in mixed phases).
- [ ] **Step 2: The whole table, red.** `every_state_against_both_deferral_commands`: `Idle`, `Inferring`, `AwaitingActions` (each row of the table) and `Terminal`, asserting ignore or the one event. `every_state_against_both_deferral_events`: `AwaitingActions` accepts; `Idle`, `Inferring` and `Terminal` throw as they do for any event that cannot happen there.
- [ ] **Step 3: Replay equals live, red.** For each scripted scenario (deferred approval then approved; then denied; then expired into a failure; deferred tool then result; then failure; two calls, one deferred), `AgentState.idle(...).applyAll(storedEvents)` equals the state that wrote them.
- [ ] **Step 4:** Implement: the two commands, the arms in `AwaitingActions.execute`, the arm in `AwaitingActions.accept`. Nothing else in the file changes.
- [ ] **Step 5:** `AgentStateTest`, `AgentStateRepeatedCallIdTest` and the late-outcome tests pass unchanged. Gate. Commit: `feat: the fold records that an approval or a tool call was deferred`.

### Task 4: A handler that defers says what it knows

Not a fold task.

**Files:** create `nessy-engine/.../effect/Handled.java` and `nessy-engine/.../effect/ApprovalQuestions.java`; `EffectHandler.java`, `EffectHandlers.java` (return `Handled`); `InferenceHandler.java`, `ToolCallHandler.java`, `ApprovalHandler.java` (gains `Payloads`; three construction sites: `DefaultQueuedHarnessFactory`, `DefaultDirectHarnessFactory`, `ApprovalHandlerTest`); `EffectDispatcher.java` and `DefaultDirectHarness.java` (**pattern shape only**: `Awaited.Ready(outcome)` becomes `Handled.Settled(outcome)`, `Awaited.Deferred` becomes `Handled.Deferred`; every arm does exactly what it does today); test doubles of `EffectHandler` in `DispatcherFailureTest`; tests.

**Interfaces:**
- `public sealed interface Handled { record Settled(EffectOutcome outcome) implements Handled {}  record Deferred(Optional<PayloadRef> question) implements Handled {} }`, with `static Handled settled(EffectOutcome)` and `static Handled deferred()`.
- `EffectHandler<E>.handle(AgentId agentId, E effect, Instant deadline)` returns `Handled`. `EffectHandlers.perform` returns `Handled`.
- `ApprovalHandler`, when the approver defers: stores `ApprovalQuestions.document(question)` with `payloads.forAgent(agentId).putDocument(...)` and returns `new Handled.Deferred(Optional.of(ref))`. `ToolCallHandler`, when the tool defers: `Handled.deferred()` (no question).
- `ApprovalQuestions.document(ApprovalRequest)`: a `JsonNode` built field by field in this order: `agentType`, `agentId`, `turn`, `callId`, `idempotencyKey`, `toolName`, `arguments` (parsed JSON), `action`, `askedAt`, `deadline`, `facts` (a deep copy). Never the reply token.
- The dispatcher's `Deferred` arm still only logs, and the direct harness still turns a deferral into the same failed call. Task 5 changes the dispatcher's arm.

- [ ] **Step 1: Tests, red.** `ApprovalHandlerTest` (give it an `InMemoryPayloads`): `a_deferred_approval_hands_back_the_question_it_stored` (the reference resolves to a document equal to the request's fields; this replaces the narration assertion removed in Task 2); `the_stored_question_does_not_hold_the_reply_token`. `ToolCallHandlerTest`: `a_deferred_tool_call_hands_back_a_deferral` (replaces the one removed in Task 2). `ApprovalQuestionsTest`: `the_document_names_the_agent_the_call_and_the_question`, `the_reply_token_is_not_in_it`.
- [ ] **Step 2:** Implement. In `EffectDispatcher` and `DefaultDirectHarness` only the patterns change.
- [ ] **Step 3:** `DispatcherFailureTest`, `GiveUpTest`, `DefaultDirectHarnessTest` change only for the return type of their handler doubles. Gate. Commit: `refactor: a handler that defers hands back what it knows`.

### Task 5: The park step

High-risk: the dispatcher. Opus review.

**Files:** `nessy-engine/.../effect/AgentEffectCallback.java` (one method); `nessy-engine/.../effect/EffectDispatcher.java` (the `Deferred` arm, and the park call placed outside the `try`); `nessy-engine/.../harness/queued/DefaultQueuedHarness.java` (the locked park step); the two test doubles of `AgentEffectCallback` (`DispatcherFailureTest.Deliveries`, `MisroutedReplyTest.Deliveries`); tests `DispatcherFailureTest`, `DeferredApprovalTest`, `DeferredToolTest`, `ToolCallingTest`, `NarrationTest`; `docs/concepts/durable-computation.md`, `authorization.md`, `tools.md`.

**Interfaces:**
- `void park(Attempt attempt, AgentEffect effect, Optional<PayloadRef> question);` on `AgentEffectCallback`.
- `DefaultQueuedHarness.park`: one `narrator.locked(...)` step that
  1. builds the command from the effect: `AgentEffect.Approve` → `DeferApproval(turn, requestSeq, callId, attempt.deadline(), question.orElseThrow())`; `AgentEffect.CallTool` → `DeferToolCall(turn, requestSeq, callId, attempt.deadline())`; `AgentEffect.Infer` → nothing to do (an inference cannot defer; log at WARN and return);
  2. gives it to the fold as any command is given (reconstitute, `execute`, append, narrate the story event after commit);
  3. **only if the fold wrote the event,** calls `effects.park(attempt.effectId(), attempt.attemptsMade(), at)` in the same locked transaction. A false return is not an error.
  It starts no inference, drives no backlog and nudges no dispatch. The existing `apply` keeps its behaviour for every existing command; if the park needs to know that the fold advanced, it uses a private sibling, not a changed return value that existing callers read.
- `EffectDispatcher.performInTrace`: the `Deferred` arm records that the attempt deferred and with which question; **after the `try`/`catch` block has ended**, the dispatcher calls `callback.park(attempt, effect, question)` inside its own `try` that catches `RuntimeException`, logs at WARN with the effect id, the agent and the exception, and does nothing else. No path from the park reaches `settle`, `retire`, `reschedule` or `expired`.

- [ ] **Step 1: Dispatcher tests, red** (`DispatcherFailureTest`, hand fakes): `a_deferred_attempt_is_parked` (the callback is handed the attempt, the effect and the question; nothing is delivered, retired or rescheduled); `a_park_that_throws_fails_no_call` (Review Focus 2: with a callback whose `park` throws, nothing is delivered, retired or rescheduled, and the handler was performed once); `a_handler_that_throws_is_not_parked`.
- [ ] **Step 2: Engine tests, red** (container, `EngineFixture`):
  - `DeferredApprovalTest`: `a_deferred_approval_is_on_the_record_when_it_happens` (the story holds `ApprovalDeferred` right after the request, before any answer, with `until` equal to the row's `deadline` column, a question that resolves, and the call's key); `a_parked_row_is_marked_and_due_at_its_deadline` (raw SQL: `parked_at` is set, `actionable_at = deadline`, `status = 'RUNNING'`); `the_event_and_the_mark_are_one_transaction` (with a `ProbedQueuedBackend` whose `park` throws, the story holds no `ApprovalDeferred` and the row is unmarked); `an_answer_that_lands_first_leaves_the_park_writing_nothing` (Review Focus 1: an approver that answers through `Replies` before it returns `Awaited.deferred()`; the story holds the decision and no deferral).
  - `DeferredToolTest`: the same four for `ToolDeferred`, without a question.
  - `a_parked_approval_is_asked_once` (Review Focus 5: the approver's ask count is 1 from the deferral to the deadline, with a short poll interval).
  - `NarrationTest`: `a_deferral_is_heard_as_a_story_event_with_its_position` (replaces the live assertion removed in Task 2: the listener hears `Narration.ApprovalDeferred` with the call, the key, `until`, and a `Position`).
- [ ] **Step 3:** Implement the callback method, the harness step and the dispatcher arm.
- [ ] **Step 4: Positional tests.** `DeferredApprovalTest`, `DeferredToolTest` and `ToolCallingTest.aDeferredApprovalParksTheCallAndExpiresIntoAFailure` pin stories by index and `Seq`; each shifts by the one inserted event. Change each index and `Seq` to its new value and assert the inserted event. List every changed line in the report.
- [ ] **Step 5:** Docs (a deferral is recorded when it happens; a parked row is marked and waits for its deadline or an answer). CHANGELOG. Gate. Commit: `feat: a deferral is recorded and its row parked in one step`.

### Task 6: The story with a deferral in it reads the same live and replayed

Not a fold task.

**Files:** `EventAgentStoriesQueuedTest` (container), `EventAgentStoriesTest`, `StoryEventsTest`; `nessy-examples` that show approvals (`watchman` `ApprovalsDesk`, `chat-web` page and its `app.js`, if they read the deferral narrations); `docs/guides/narration.md` (the event table and the live/story lists), `docs/concepts/authorization.md`, `docs/concepts/tools.md`, `docs/concepts/durable-computation.md`, `docs/concepts/events.md`; `CHANGELOG.md`.

- [ ] **Step 1: Tests, red where new.** `EventAgentStoriesQueuedTest`: `a_deferred_approval_reads_the_same_heard_live_and_replayed` (a deferral, then an approval through `Replies`, then the answer: heard equals replay, and the heard `ApprovalDeferred` has the requested key and the row's deadline); `a_deferred_tool_call_reads_the_same_heard_live_and_replayed`; the existing `a_call_that_fails_at_its_deadline_says_so_heard_live_and_replayed` now holds a `CallDeferred` before its `CallFailed`. Spec §11's scripted turn: a retry, a gated call, a deferral and an answer, heard equal to replayed, same `seq` and same `at`.
- [ ] **Step 2:** `DefaultDirectHarnessTest.a_deferred_approval_is_failed` also asserts that no deferral is stored and none is heard on the direct door.
- [ ] **Step 3:** Examples and docs. The Odyssey SSE names are unchanged. Gate. Commit: `docs: a deferral is part of the story, heard live and on replay`.

---

## After the last task

The final whole-branch review (Opus) is given the spec, this plan and the branch diff, and is asked to:

- read the whole `engine/core/` diff line by line against "This plan changes what the fold does" and spec §10a, and list every line that is not on the table;
- confirm `Idle`, `Inferring`, `Terminal`, `OutstandingAction`, `continuing(...)`, `approved(...)` and `ran(...)` are byte-for-byte unchanged;
- confirm the pre-existing fold and dispatcher tests lost no assertion, and that each assertion removed in Task 2 has its replacement in Task 4 or Task 5;
- read the dispatcher diff and confirm no path from the park step reaches `settle`, `retire`, `reschedule` or `expired`, and that the claim, the fences, retry and expiry are unchanged;
- confirm the story a listener hears equals the story `replay` returns with deferrals in it, on PostgreSQL;
- confirm every positional assertion that shifted moved by exactly the inserted event.
