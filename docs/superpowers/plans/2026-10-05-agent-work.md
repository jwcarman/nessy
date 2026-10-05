# Agent Work — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An application can ask whether an agent is busy, read the approvals waiting on a person as `ApprovalRequest`s, and answer a waiting call by its agent and idempotency key and be told whether the answer was applied or ignored. The reply token is removed.

**Architecture:** Everything here is a read of what is already stored, or a change to how a reply is addressed and reported. Status folds one turn of an agent's story and reads its backlog size and its live effect rows. Waiting approvals are the effect rows parked now, decoded in the engine, each rebuilt into an `ApprovalRequest` from its row and its agent's story. A reply finds the call's live row for a named agent by key, gives the answer to the fold as today, and reports `Applied` only when the fold took it. **No table, no column and no index is added, and nothing in the fold changes.**

**Tech Stack:** Java 25, Maven (`./mvnw`), JUnit 5, AssertJ, Awaitility, Testcontainers (PostgreSQL 18), Jackson 3, Spring Boot 4 (starter only).

**Spec:** `docs/superpowers/specs/2026-10-04-agent-work-design.md`. Its §9 rulings and §10 proposals are approved by James (2026-10-05, "Let's try it").

## This plan does not touch the fold

`AgentState`, `AgentCommand`, `AgentEvent`, `Decision`, `TurnTally` and `OutstandingAction` are not edited. **No task may change a file under `nessy-engine/src/main/java/org/jwcarman/nessy/engine/core/`.** A task that seems to need to stops and reports.

## Global Constraints

- The no-fold rule above binds every task.
- **Red first.** Every task writes its tests first and sees them fail for the right reason.
- **Nothing else moved.** `AgentStateTest`, `AgentStateDeferralTest`, `AgentStateFactsTest`, `AgentStateManifestTest`, `DispatcherFailureTest`, `GiveUpTest`, `HarnessLoopTest`, `JdbcEffectClaimTest`, `InMemoryEffectsParkTest`, `DeferredApprovalTest`, `DeferredToolTest`, `ApprovalFactsTest` lose no assertion, except that an answer by token becomes an answer by agent and key, `Settled` becomes `Applied`, and `NotAwaiting` becomes `Ignored`.
- **The effect table's statements do not change.** `INSERT`, `MARK_RUNNING`, `RUNNING_FOR`, `DELETE`, `RESCHEDULE`, `PARK` in `JdbcEffects` are byte-identical. No schema change to any `nessy_` table.
- No in-process memory across calls: status and waiting approvals are computed from stored data on every call.
- New public names in `nessy-api` are exactly: `AgentWork`, `AgentStatus`, `AgentStatus.Activity` (`IDLE`, `WORKING`, `WAITING`, `ENDED`), `ReplyOutcome.Applied`, `ReplyOutcome.Ignored`, and the two `Replies` methods that take an agent type, an agent id and a key. Removed: `ReplyToken`, the token methods on `Replies`, `replyToken()` on `ApprovalRequest` and `ToolCallRequest`, `ReplyOutcome.Settled`, `NotAwaiting`, `Unreadable`. In the backend SPI: `LiveEffect`, `Effects.liveFor`, `Effects.parkedNow`, `QueuedBackend.queued`. A task that needs another public or SPI name stops and reports.
- No `@SuppressWarnings`, `// NOSONAR` or any other suppression. No star imports. No mocking library. Prose test names. One throwing call inside an exception-assertion lambda (S5778). Non-emptiness asserted before an all-match or none-match (S5841). Tests wait on latches, Awaitility or a controllable clock, never a sleep.
- `./mvnw spotless:apply license:format` before each commit. Scoped builds by artifactId with `-am`. One Maven process in a worktree, in the foreground. Exit codes are captured as `rc=$?` immediately after Maven, written to a file, and read from the file after the command has finished.
- A task's last step is the gate: `./mvnw -B -q clean verify -Dnessy.excludedGroups=live; rc=$?; mkdir -p target; echo $rc > target/gate.exit`. The controller reads the file and the test-report totals itself before a task is marked complete.
- **Review:** Tasks 1, 2 and 4 get the high-risk review on Opus. The other tasks get the task review on Sonnet. The final whole-branch review is on Opus.
- Docs describe what is, in plain American wording, with no history. An `ApprovalRequest` is called an approval request.

## Order of execution

Tasks 1 to 3 (replies) and Tasks 4 to 6 (the reads) do not depend on each other and run at the same time in two worktrees, each told the exact commit to branch from. Task 7 needs both. Tasks 8 and 9 need Task 7. The second line to land merges the first by hand and gates the merged tree.

## Review Focus

1. **An accepted answer that emits no effect.** A denial, or a result, while other calls of the request are still outstanding: the fold writes an event and asks for nothing. The answer is `Applied`, and the dispatcher is nudged exactly as it is today. (Tasks 1 and 2)
2. **An answer racing the deadline, or another answer.** Whichever the fold takes first stands; the loser is told `Ignored` and never `Applied`. (Task 2)
3. **A row that is stale-parked.** A parked row re-claimed at its deadline still has `parked_at` set until it is deleted: it is not a waiting approval, and it does not make an agent `WAITING`. (Tasks 4, 5, 6)
4. **An effect row that cannot be decoded.** It is skipped with a WARN in both reads; it never fails the read. (Task 4)
5. **A call id repeated in a later request of the same turn.** The key, not the call id, finds the row and the story's entries. (Tasks 2 and 6)

---

### Task 1: The harness says whether the fold accepted an outcome

High-risk: the queued harness. Opus review.

**Files:** `nessy-engine/.../effect/AgentEffectCallback.java`; `nessy-engine/.../harness/queued/DefaultQueuedHarness.java` (`deliverOutcome`, and the private `fold` / `apply`); `nessy-engine/.../effect/EffectDispatcher.java` (its call sites ignore the returned value; no other change); `nessy-engine/.../tool/DefaultReplies.java` (the call site compiles; it does not use the value yet); test doubles `DispatcherFailureTest.Deliveries`, `MisroutedReplyTest.Deliveries`; tests.

**Interfaces:**
- `boolean deliverOutcome(AgentId agentId, Optional<TurnId> turn, Optional<Seq> request, EffectOutcome outcome, String traceContext, List<FailedAttempt> priorAttempts);` returns true exactly when the fold wrote at least one event for this outcome. It is false when the fold ignored it, and when the outcome named no turn or no request and was dropped before the fold.
- The dispatcher's nudge is unchanged: it still happens when the step emitted effects or started the next turn, and only then. Whether the fold accepted and whether to nudge are two separate values inside the harness. `apply` and `fold` are private; they may return a small private record, but every existing caller (`tell`, `terminate`, `driveIfIdle`) behaves exactly as today.

- [ ] **Step 1: Tests, red** (a new `QueuedHarnessAcceptanceTest`, container, through `EngineFixture`; follow `DeferredApprovalTest` for the fixture). One test for each delivery path, asserting BOTH the returned value and that the stored story is exactly what it is on `main`:
  - `an_answer_the_fold_takes_is_accepted` (an approval that emits a `CallTool`);
  - `a_denial_with_other_calls_outstanding_is_accepted_though_it_asks_for_nothing` (Review Focus 1);
  - `a_result_that_ends_the_turn_is_accepted`;
  - `a_second_answer_for_the_same_call_is_not_accepted_and_writes_nothing`;
  - `an_answer_for_another_turn_is_not_accepted`, `an_answer_for_another_request_is_not_accepted`;
  - `an_outcome_that_names_no_turn_is_not_accepted`;
  - `the_dispatcher_is_nudged_as_before` (for each case above, the nudge happens exactly when it does on `main`; use the means `NudgeTest` uses).
- [ ] **Step 2:** Implement. In `EffectDispatcher` the `callback.deliverOutcome(...)` statements stay statements; nothing reads the value there.
- [ ] **Step 3:** `DispatcherFailureTest`, `GiveUpTest`, `HarnessLoopTest`, `NudgeTest`, `MisroutedReplyTest` change only for the doubles' return type. Gate. Commit: `refactor: delivering an outcome says whether the fold took it`.

### Task 2: An answer names the agent and the key, and is applied or ignored

High-risk: `Replies`. Opus review.

**Files:** `nessy-api/.../tool/Replies.java`, `ReplyOutcome.java`; `nessy-engine/.../tool/DefaultReplies.java`; every caller of `Replies` in main code and tests (watchman `ApprovalsController`, `ApprovalsDesk`, `PendingApproval`, `PendingApprovalsRepository`, `watchman-schema.sql`; chat-web `ApprovalDesk` and its controller; the test classes the compiler names); a new `RepliesByKeyTest` (container). The `ReplyToken` type, `replyToken()` on the requests, and the token minting stay in place in this task, unused by `Replies`; Task 3 deletes them.

**Interfaces:**
- `Replies` has exactly two methods: `ReplyOutcome approve(AgentType type, AgentId id, IdempotencyKey key, ApprovalResult result);` and `ReplyOutcome complete(AgentType type, AgentId id, IdempotencyKey key, ToolResult result);`. Every argument is refused when null.
- `ReplyOutcome` is sealed with exactly `record Applied()` and `record Ignored()`.
- Settling: look up the agent type's registration (none is `Ignored`, logged at WARN as today); read the agent's running rows, decode each, and match the key and the kind the answer fits (`AgentEffect.Approve` for `approve`, `AgentEffect.CallTool` for `complete`; a row that cannot be decoded is not a match); none is `Ignored`, logged at INFO as today. Deliver the answer to the fold with the turn and the request the row's effect names, as today. The outcome is `Applied` exactly when `deliverOutcome` returned true, and `Ignored` otherwise. Then delete the row, fenced, as today, whether or not the fold took the answer; a lost fence is logged and does not change the outcome.
- `DefaultReplies` no longer takes or reads `ReplyTokens`. Its constructor and `DefaultQueuedHarnessFactory`'s use of it change to match; the factory still mints tokens for the requests until Task 3.
- The examples change by the minimum that compiles and behaves as today: where one stored a token, it stores the agent type, the agent id and the key. Their larger change is Tasks 8 and 9.

- [ ] **Step 1: Tests, red.** `RepliesByKeyTest` (container): `an_approval_by_key_runs_the_call`; `a_denial_by_key_is_applied`; `a_result_by_key_finishes_a_deferred_tool`; `a_second_answer_is_ignored_and_writes_nothing`; `two_answers_at_once_apply_once` (two threads: exactly one `Applied`, one `Ignored`, one decision event in the story; Review Focus 2); `an_answer_after_the_deadline_is_ignored_and_never_applied` (Review Focus 2); `approve_for_a_running_call_is_ignored`; `complete_for_a_call_not_yet_approved_is_ignored` (and the call is still waiting for its approval); `a_key_nobody_issued_is_ignored`; `the_right_key_for_another_agent_is_ignored`; `an_agent_type_not_served_here_is_ignored`; `a_call_id_repeated_in_a_later_request_is_told_apart_by_its_key` (Review Focus 5); `a_denial_with_other_calls_outstanding_is_applied` (Review Focus 1). The transaction (spec §6d): `an_answer_inside_a_transaction_that_rolls_back_leaves_the_call_waiting` (the story is unchanged and the row is still there) and `an_answer_inside_a_transaction_that_commits_answers_with_the_callers_own_writes`.
- [ ] **Step 2: Existing tests.** Each test that answered with a token answers with the agent and the key from the same request; `Settled` becomes `Applied`, `NotAwaiting` becomes `Ignored`; no other assertion changes. A test whose only subject is `Unreadable` or a forged token is deleted. List every changed and every deleted test in the report (file, test, what changed).
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** CHANGELOG (`### Breaking changes`: answers are by agent and key; `ReplyOutcome` is `Applied` or `Ignored`; `Applied` means the answer changed the agent's state). Gate. Commit: `feat: a call is answered by its agent and key, and the answer is applied or ignored`.

### Task 3: The reply token is gone

**Files:**
- API: delete `nessy-api/.../tool/ReplyToken.java`; `ApprovalRequest.java` and `ToolCallRequest.java` (the `replyToken` component and accessor go; both constructors of `ApprovalRequest` change); `Approver.java`, `Awaited.java`, `Replies.java`, `ReplyOutcome.java` (javadoc).
- Engine: delete `nessy-engine/.../tool/ReplyTokens.java` and its tests; `CallRequest.java`, `ToolBinding.java`, `effect/ApprovalHandler.java`, `effect/ToolCallHandler.java`, `harness/direct/DefaultDirectHarnessFactory.java`, `harness/queued/DefaultQueuedHarnessFactory.java`, `harness/queued/QueuedHarnessFactoryConfig.java`.
- Starter: `NessyProperties.java` (`replyTokenEncryptionKeys` removed), `NessyAutoConfiguration.java`, `QueuedHarnessAutoConfiguration.java`, and their tests.
- Policy: `nessy-approval/policy-opa/.../InputDocumentRenderer.java` (the token handling and its javadoc go).
- Examples: `application.yml` of watchman and chat-web (the key property goes); `nessy-examples/policy/README.md`; any remaining use the compiler names.
- Docs: `docs/concepts/authorization.md`, `durable-computation.md`, `tools.md`, `twelve-factor-agents.md`, `docs/guides/harness.md`, `docs/guides/spring-boot.md`, `docs/index.md`; `CHANGELOG.md`. Past specs and plans under `docs/superpowers/` are not edited.

**Interfaces:**
- `ApprovalRequest(AgentType agentType, AgentId agentId, TurnId turn, CallId callId, IdempotencyKey idempotencyKey, ToolName toolName, String arguments, String action, Instant askedAt, Instant deadline, ObjectNode facts)` and its shorter constructor without `facts`.
- Nothing mints, reads, configures or documents a reply token.

- [ ] **Step 1: Test, red.** A starter test: a context with `nessy.reply-token-encryption-keys` unset starts a queued harness factory (today it needs the property, if it does; assert what is true on `main` first and report it). Every other change in this task is a deletion the compiler and the gate check.
- [ ] **Step 2:** Delete and adapt. From the repo root, `git grep -i -E "ReplyToken|replyToken|reply-token|reply_token" -- '*.java' '*.yml' '*.yaml' '*.sql' '*.properties' docs/concepts docs/guides docs/index.md nessy-examples README.md` returns nothing. Paste the command's (empty) output in the report.
- [ ] **Step 3:** Docs: an answer names the agent type, the agent and the call's key; what `Applied` and `Ignored` mean; a reply joins the caller's transaction, and narration may be heard before the caller commits; Nessy does not authenticate the caller of `Replies`, so an application guards its answer endpoint. CHANGELOG (`### Breaking changes`: the reply token, its accessors and the `reply-token-encryption-keys` property are removed). Gate. Commit: `refactor: the reply token is gone`.

### Task 4: The store reads the work that is live

High-risk: the effect store. Opus review. No schema change; new `SELECT`s only.

**Files:** create `nessy-backend/spi/.../effect/LiveEffect.java`; `nessy-backend/spi/.../effect/Effects.java`; `nessy-backend/jdbc/.../JdbcEffects.java`; `nessy-backend/inmemory/.../InMemoryEffects.java` (its `Row` gains `createdAt` and `parkedAt`, set by `insert` and `park`); `nessy-backend/spi/.../QueuedBackend.java` and its two implementations (`queued`); the test wrappers that implement `Effects` or `QueuedBackend` (`ParkRefusingBackend`, `ProbedQueuedBackend`, others the compiler names); tests `JdbcEffectLiveTest` (container), `InMemoryEffectsLiveTest`, and tests of `queued` on both backends.

**Interfaces:**
- `public record LiveEffect(UUID effectId, AgentType agentType, AgentId agentId, AgentEffect effect, Instant createdAt, Optional<Instant> parkedAt, Instant deadline, int attemptsMade, boolean running)` with `public boolean parkedNow(Instant now)`: true when `parkedAt` is present, `running` is true and `deadline` is after `now`.
- On `Effects`: `List<LiveEffect> liveFor(AgentType type, AgentId agent);` every live row of one agent, pending and running, oldest first by `(created_at, effect_id)`.
- On `Effects`: `List<LiveEffect> parkedNow(Optional<AgentType> type, Instant now, Optional<LiveEffect> after, int limit);` rows with `parked_at IS NOT NULL AND status = 'RUNNING' AND deadline > now`, of one agent type when one is named and of every type otherwise, oldest first by `(created_at, effect_id)`, strictly after the given row's `(createdAt, effectId)` when one is given, at most `limit`. `limit` must be positive.
- In both reads a row whose payload cannot be decoded is skipped and logged at WARN with its effect id; it never fails the read (Review Focus 4).
- `int queued(AgentType type, AgentId agent);` on `QueuedBackend`: the number of inputs told and not yet started; 0 for an agent it has never heard of.
- Every existing statement in `JdbcEffects` is unchanged. The new statements only read.

- [ ] **Step 1: Tests, red,** on both stores: `an_agents_live_rows_come_oldest_first`; `a_pending_row_is_live_and_not_parked`; `a_parked_row_is_parked_now`; `a_parked_row_past_its_deadline_is_not_parked_now` (Review Focus 3); `a_parked_row_claimed_again_at_its_deadline_is_not_parked_now` (Review Focus 3); `parked_rows_of_every_type_when_no_type_is_named`; `parked_rows_of_one_type_when_one_is_named`; `a_read_continues_after_the_row_it_is_given` (rows with the same `created_at` are ordered by id; none is skipped or repeated); `a_finished_row_is_gone`; `a_row_that_cannot_be_decoded_is_skipped` (Review Focus 4); `the_count_of_inputs_told_and_not_started`; `an_agent_nobody_told_anything_has_nothing_queued`. `JdbcEffectClaimTest` and `InMemoryEffectsParkTest` pass unchanged.
- [ ] **Step 2:** Implement in both stores and both backends. Paste every `JdbcEffects` SQL constant before and after in the report; only new ones may differ.
- [ ] **Step 3:** Gate. Commit: `feat: the effect store reads the work that is live`.

### Task 5: An agent's status

**Files:** create `nessy-api/.../AgentWork.java`, `AgentStatus.java`; create `nessy-engine/.../work/StoredAgentWork.java`; `QueuedHarnessFactory` / `DefaultQueuedHarnessFactory` and `DirectHarnessFactory` / `DefaultDirectHarnessFactory` (`AgentWork work()` beside `replies()` / the story reads); `EngineFixture` (a `work()` accessor); tests `AgentStatusTest` (container, queued door), `AgentStatusDirectTest` (in-memory, direct door).

**Interfaces:**
- `public interface AgentWork { AgentStatus status(AgentType type, AgentId id); List<ApprovalRequest> waitingApprovals(); List<ApprovalRequest> waitingApprovals(AgentType type); }`. In this task both `waitingApprovals` forms return an empty list and `AgentStatus.waitingApprovals` is always empty; Task 6 fills them. Say so in a code comment that Task 6 removes.
- `public record AgentStatus(Activity activity, int queued, Optional<TurnId> turn, List<ApprovalRequest> waitingApprovals, int waitingToolCalls) { public enum Activity { IDLE, WORKING, WAITING, ENDED } }`. The list is copied; nulls are refused; counts below zero are refused.
- How it is read, on every call, from stored data: the agent's events from its last `TurnStarted` (`AgentEvents.sinceLastTurnStarted`) are folded to an `AgentState` the way `DefaultQueuedHarness.reconstitute` does. `Terminal` is `ENDED`. `Idle` is `IDLE` when nothing is queued and `WORKING` when something is. `Inferring` is `WORKING`. `AwaitingActions` is `WAITING` when the agent has at least one live row and every live row is parked now (`LiveEffect.parkedNow(clock.instant())`), and `WORKING` otherwise. `queued` is `QueuedBackend.queued` on the queued door and 0 on the direct door. `turn` is the turn in progress. `waitingToolCalls` is the number of the agent's live rows that are parked now and whose effect is a `CallTool`.
- The direct door has no effect rows: its `AwaitingActions` is always `WORKING`, and its counts are 0.
- An agent nobody has told anything is `IDLE` with nothing queued.
- It runs no transaction of its own and takes no lock.

- [ ] **Step 1: Tests, red,** for each activity (spec §11): nothing told; told and queued behind a turn; in a model call; one call running and one parked (`WORKING`); everything parked (`WAITING`); everything parked with input queued behind it (`WAITING`, with the count); a deferred tool call counted in `waitingToolCalls`; answered and finished (`IDLE`); terminated (`ENDED`); told to terminate while a turn is in progress (still `WORKING` or `WAITING` until the turn ends); a stale-parked row does not make it `WAITING` (Review Focus 3, with a controllable clock past the deadline before the dispatcher claims the row). Direct door: mid-`ask` is `WORKING`; finished is `IDLE`; terminated is `ENDED`; the counts are 0.
- [ ] **Step 2:** Implement. Gate. Commit: `feat: an agent says whether it is idle, working, waiting or ended`.

### Task 6: The approvals waiting on a person

**Files:** `nessy-engine/.../work/StoredAgentWork.java`; if the code that resolves a call's stored arguments and action is private to `ApprovalHandler` or its collaborators, extract it to one engine-internal class both use, with no change in behaviour; tests `WaitingApprovalsTest` (container), an in-memory twin.

**Interfaces:**
- `waitingApprovals()` and `waitingApprovals(AgentType type)` return one `ApprovalRequest` for each row that is parked now and whose effect is an `Approve`, oldest first, at most 500 (`StoredAgentWork.MAXIMUM_WAITING`). The store is read in pages of 500 with `Effects.parkedNow`, continuing after the last row read, until 500 approvals are held or a page comes back short.
- Each request is rebuilt (spec §5): `agentType`, `agentId` from the row; `turn`, `callId`, `idempotencyKey`, `toolName` from the row's effect; `action` from the call's entry, matched by `idempotencyKey`, in the `ActionsRequested` event at the effect's `requestSeq`; `arguments` from that event's stored request content, exactly as `ApprovalHandler` reads them; `facts` from the last `ApprovalDeferred` event with that `idempotencyKey` (a deep copy; an empty object when none is found); `askedAt` from the row's `parkedAt`; `deadline` from the row.
- The story read for an item is `AgentEvents.sinceLastTurnStarted` for its agent, read once for each agent in a call, not once for each item.
- A row whose agent's story does not hold the request (a store that cannot be read, a payload that is gone) is skipped and logged at WARN with the agent and the key. It never fails the read.
- `AgentStatus.waitingApprovals` is the same rebuild over that agent's live rows that are parked now. The Task 5 comment and empty lists go.
- A factory serving only the direct door returns empty lists.

- [ ] **Step 1: Tests, red:** `a_waiting_approval_is_the_request_the_approver_was_shown` (an approver that keeps the request it was given and defers; the rebuilt request equals it field for field, except `askedAt`, which equals the row's `parked_at`); `its_facts_are_the_ones_the_approver_left` (an enricher adds facts and the approver adds more before deferring); `waiting_approvals_come_oldest_first`; `only_one_agent_types_when_one_is_named`; `every_types_when_none_is_named`; `an_answered_approval_is_gone_from_the_next_read`; `an_approval_past_its_deadline_is_not_waiting` (Review Focus 3); `a_deferred_tool_call_is_not_listed`; `a_call_id_repeated_in_a_later_request_gets_its_own_action_and_arguments` (Review Focus 5); `no_more_than_the_cap_come_back_and_they_are_the_oldest` (with `MAXIMUM_WAITING` reachable from a test through a package-private constructor argument, not by creating 501 approvals); `an_agents_status_lists_its_waiting_approvals`; `a_direct_door_agent_has_none`.
- [ ] **Step 2:** Implement. Gate. Commit: `feat: the approvals waiting on a person can be read as approval requests`.

### Task 7: The starter bean, and the docs

**Files:** create `nessy-spring-boot/autoconfigure/.../AgentWorkAutoConfiguration.java` (modelled on `AgentStoriesAutoConfiguration`: `@Bean @ConditionalOnMissingBean`; register it in the auto-configuration imports file); its test; docs `docs/guides/spring-boot.md` (the bean table), a section "What is waiting, and answering it" in `docs/guides/harness.md`, `docs/concepts/durable-computation.md`, `docs/concepts/authorization.md`, `docs/concepts/storage.md`, `docs/index.md` if it lists the read APIs; `CHANGELOG.md` (`### Added`).

**Interfaces:**
- One `AgentWork` bean. With a queued backend it answers status for queued agents and returns waiting approvals. With a direct backend as well, status for an agent the queued store holds no events for is answered from the direct store, as `FirstStoreHoldingStories` chooses a store. With only a direct backend it answers status and returns no waiting approvals.

- [ ] **Step 1: Tests, red:** with a queued backend the context has an `AgentWork` bean that reports status and lists a waiting approval; with only a direct backend it reports status and lists nothing; an application's own `AgentWork` bean is used in its place.
- [ ] **Step 2:** Implement the bean. Write the docs: status and its four activities; reading waiting approvals and what each field of a rebuilt request is; the cap of 500; answering with the agent and the key and what `Applied` and `Ignored` mean; what the books do not hold (the direct door; requests an application makes itself; deferred tool calls beyond a count). Gate. Commit: `feat: the starter offers an agent's work, and the docs say how to use it`.

### Task 8: The watchman reads the books

**Files:** `nessy-examples/watchman` (`ApprovalsDesk`, `ApprovalsController`, `PendingApproval`, `PendingApprovalsRepository`, `watchman-schema.sql`, its page and tests, its README).

The watchman keeps no approvals table and no "is it still waiting" check of its own. Its approver defers and keeps nothing. Its page lists `work.waitingApprovals()`, shows each request's tool, action and facts, and answers with `replies.approve(request.agentType(), request.agentId(), request.idempotencyKey(), ...)`. On `Ignored` it tells the person the approval was no longer waiting.

- [ ] **Step 1: Tests, red:** the page lists a waiting approval with no table behind it; approving it runs the call; a second answer is told the approval was no longer waiting. The table `watchman_pending_approval` is gone from the schema.
- [ ] **Step 2:** Implement. Do not start the application or Docker compose; tests only. Gate. Commit: `refactor: the watchman reads what is waiting from Nessy`.

### Task 9: chat-web reads the books

**Files:** `nessy-examples/chat-web` (`ApprovalDesk`, its controller, `static/app.js`, tests, README).

The same change: the in-memory `ApprovalDesk` that holds approval requests goes; the page lists waiting approvals from `AgentWork`, and answers by agent and key.

- [ ] **Step 1: Tests, red,** as in Task 8, for chat-web's page and its SSE flow.
- [ ] **Step 2:** Implement. Tests only. Gate. Commit: `refactor: chat-web reads what is waiting from Nessy`.

---

## After the last task

The final whole-branch review (Opus) is given the spec, this plan and the branch diff, and is asked to:

- confirm no file under `engine/core/` changed, and that `AgentEvent` is unchanged;
- confirm every existing `JdbcEffects` statement is byte-identical and no `nessy_` table changed;
- confirm the dispatcher's behaviour is unchanged by `deliverOutcome` returning a value, and that the nudge happens exactly as before;
- confirm an answer the fold did not take is never reported as `Applied`, on every path;
- confirm no reply token, token key or token property remains in main code, configuration or the docs site;
- confirm nothing is remembered in process between calls;
- confirm a rebuilt `ApprovalRequest` can differ from the one the approver was shown only in `askedAt`;
- confirm the examples have no approvals store of their own.
