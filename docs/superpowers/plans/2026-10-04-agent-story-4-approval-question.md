# The Agent's Story, Plan 4: Documents and the Approval Question — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `Payloads` keeps JSON documents beside message blocks, and every approval decision is recorded with the question it was made on.

**Architecture:** Part A adds a second kind of content to `Payloads`, in the same table, under the same content-hash references and the same storage codec. Part B stores the `ApprovalRequest` as a document after the approver returns, carries its reference on the outcome and the command, has the fold copy it onto the event it already writes, and adds one read to `StoryContent`.

**Tech Stack:** Java 25, Maven (`./mvnw`), JUnit 5, AssertJ, Awaitility, Testcontainers (PostgreSQL 18), Jackson 3 (`tools.jackson.*`).

**Spec:** `docs/superpowers/specs/2026-10-04-agent-story-design.md`. Read §8a, §9c and all of §10 before any task.

## Order of execution (plans 3, 4 and 5)

Speed comes from running independent work side by side, never from fewer gates or fewer reviews.

1. **Part A of this plan runs first, alone,** on a branch from `main` once plan 2 is merged. Plans 3 and 5 both need `putDocument`.
2. **Plan 3 and plan 5 then run at the same time**, each in its own git worktree (`isolation: "worktree"`), each from the `main` that holds Part A. One worktree has one Maven process.
3. **Part B of this plan runs after plan 3 is merged**: the deferral's question rides on plan 3's `DeferApproval`.
4. **Merging the second of two parallel branches** is its own step: merge `main` into the branch, resolve by hand (`AgentEvent`, `AgentState`, `AgentCommand`, `EffectOutcome`, `ValueTypeCodecTest` and `CHANGELOG.md` are the files both sides touch), run the full gate on the merged tree, and have the merge diff of `engine/core/` read by an Opus reviewer against both plans' fold lists before it lands.

## This plan touches the fold (Part B only)

Tasks 3 and 4 edit `nessy-engine/src/main/java/org/jwcarman/nessy/engine/core/AgentState.java`. **What a task may do there is exactly this, and nothing else:** add a component to a command's outcome, and copy it onto the event the existing code already writes for that command.

**No guard, no `when` clause, no phase check, no effect, no state transition, no `TurnPolicy` call and no `Decision.ignore()` changes.** A task that seems to need one stops and reports.

Stored shapes change. Databases are recreated; there is no migration.

## Resolutions this plan makes

Each is a choice the spec left open. They are listed so James can overrule one before it is built.

1. **A stored payload says its kind in its row.** `nessy_payload` gains a `kind` column (`BLOCKS` or `DOCUMENT`); James ruled this on 2026-10-04. `Payloads.Content(blocks)` stays as it is and a sibling `Payloads.Document(JsonNode document)` is encoded through the same codec factory, so the storage codec covers both. Asking for the wrong kind throws `IllegalStateException` naming the reference and the kind that is there, without decoding the row.
2. **`getDocument` on a reference that is not there throws `IllegalStateException`.** The spec's signature returns `JsonNode`, and a dangling reference is always a fault.
3. **When the approver throws, the handler stores the question and rethrows.** The exception it rethrows is engine-internal (`ApproverFailed`, carrying the cause and the reference). The dispatcher's retry of a failed ask is unchanged, because the handler still throws; `AskingTerms.failed` reads the reference from the exception.
4. **The question document is built field by field, in a fixed order,** never by serializing the record, so the reply token cannot leak and the same question gives the same reference.

## Global Constraints

- The fold rule above binds Tasks 3 and 4.
- **Red first.** Every task writes its tests first and sees them fail for the right reason. A fold task's test goes in `AgentStateTest`.
- **Nothing else moved.** `AgentStateTest`, `AgentStateRepeatedCallIdTest`, `UnattributableAnswerTest`, `RepeatedCallIdLateOutcomeTest`, `DispatcherFailureTest`, `GiveUpTest` and `HarnessLoopTest` change only where a constructor gained an argument. No assertion in them is removed or weakened. Each report lists the pre-existing test files it edited and why.
- **The stored shape is written out by hand.** Each task that changes a stored event adds or updates a `ValueTypeCodecTest` case with the event's JSON as a literal string holding a real value for the new field, asserted on the decoded event and in the encoded string. A literal without the new key is also read, and reads as absent.
- New public names are exactly: `Payloads.putDocument`, `Payloads.getDocument` (backend SPI), and `StoryContent.question` (`nessy-api`), all from spec §12 and §9c. A task that needs another stops and reports.
- No `@SuppressWarnings`, `// NOSONAR` or any other suppression. No star imports. No mocking library. Prose test names. One throwing call inside an exception-assertion lambda (S5778). Non-emptiness asserted before an all-match or none-match (S5841).
- `./mvnw spotless:apply license:format` before each commit. Scoped builds by artifactId with `-am`. One Maven process in a worktree. Before the gate: `./mvnw -B -q clean test-compile; echo $? > target/tc.exit` reads 0.
- A task's last step is the gate: `./mvnw -B -q clean verify -Dnessy.excludedGroups=live; echo $? > target/gate.exit`, and Maven's exit code is read from that file.
- **Review:** Tasks 3 and 4 get the high-risk review on Opus; the reviewer reads the `engine/core/` diff line by line against "This plan touches the fold". Tasks 1, 2 and 5 get the task review on Sonnet. The final whole-branch review of each part is on Opus.
- Docs describe what is, in plain American wording, with no history.

## Review Focus

1. **A document asked for as blocks, and blocks asked for as a document.** Each fails with an `IllegalStateException` that names the reference and says which kind is there; neither returns something half-read. (Task 1)
2. **A document under a storage codec.** With encryption configured, the stored bytes of a document are not readable JSON, and it still reads back. (Task 1)
3. **The reply token is never stored.** The question document for a request with a known token does not contain that token anywhere in its bytes. (Task 2)
4. **Facts the approver adds while deciding are in the document; facts added after are not.** The document is a copy taken when the approver returns. (Task 3)
5. **An approver that throws is still retried as today.** With a retry policy of two goes, the approver is asked twice and the failure that is finally recorded names the second question. (Task 4)

---

## Part A: documents (runs first, alone)

### Task 1: `Payloads` keeps documents

**Files:** `nessy-backend/spi/.../payload/Payloads.java`; `nessy-backend/inmemory/.../InMemoryPayloads.java`; `nessy-backend/jdbc/.../JdbcPayloads.java` (also delete the stale javadoc above its `jdbc` field); the two test-local implementations (`InferenceHandlerTest` anonymous fake, `DefaultDirectHarnessTest.CountingPayloads`); tests `JdbcPayloadsTest`, a new `InMemoryPayloadsTest` in the in-memory module, `StorageCodecTest`; `docs/concepts/events.md` or the page that describes payload storage; `CHANGELOG.md`.

**Interfaces:**
- `PayloadRef putDocument(JsonNode document);` and `JsonNode getDocument(PayloadRef ref);` on `Payloads`, abstract, with javadoc. `JsonNode` is `tools.jackson.databind.JsonNode`.
- `record Content(List<Block> blocks)` is unchanged; `record Document(JsonNode document)` is new beside it, null refused. Each store keeps the kind with the content: the `kind` column in JDBC, a field beside the bytes in memory. The reference stays `Payloads.reference(encodedBytes)`.
- `put` and `get` keep their signatures and `Resolved` keeps its two arms. `get` (single and batch) on a payload that holds a document throws `IllegalStateException("payload <ref> holds a document, not blocks")`. `getDocument` on blocks throws `IllegalStateException("payload <ref> holds blocks, not a document")`; on nothing, `IllegalStateException("no payload behind <ref>")`.
- `putDocument` is idempotent as `put` is: the same document is the same reference and one row.

- [ ] **Step 1: Tests, red.** In `JdbcPayloadsTest` (container) and the new `InMemoryPayloadsTest`: `a_document_round_trips`; `putting_the_same_document_twice_is_one_reference_and_one_copy` (JDBC: count rows); `a_document_asked_for_as_blocks_fails_by_name`; `blocks_asked_for_as_a_document_fail_by_name`; `a_document_that_is_not_there_is_a_fault`; `a_batch_read_that_meets_a_document_fails_by_name`; JDBC only: `agents_do_not_share_documents`. In `StorageCodecTest`: `a_document_is_covered_by_the_storage_codec` (the stored bytes do not start with `{`, and it reads back). Fix the stale `'['` assertion beside it to `'{'`.
- [ ] **Step 2:** Implement in both stores. Both encode through `codecs.create(Payloads.Content.class)`, so the configured storage codec applies.
- [ ] **Step 3:** The two test-local implementations gain the methods (`CountingPayloads` delegates; the `InferenceHandlerTest` fake throws `UnsupportedOperationException`, as its `get` does).
- [ ] **Step 4:** Docs, CHANGELOG (`### Added`, and a `### Breaking changes` line: stored payloads changed shape, recreate the database). Gate. Commit: `feat: payloads keep JSON documents beside message blocks`.

Part A ends with its own final review (Opus), then a merge to `main`.

---

## Part B: the question (runs after plan 3 is merged)

Plan 3 leaves these in place, and Part B builds on them: `ApprovalHandler` holds a `Payloads`; `ApprovalQuestions.document(ApprovalRequest)` builds the question document; `AgentEvent.ApprovalDeferred` carries `question`.

### Task 2: The question document, pinned

**Files:** `nessy-engine/.../effect/ApprovalQuestions.java` (created by plan 3); `ApprovalQuestionsTest`.

Plan 3 creates the builder with the tests it needs for a deferral. This task completes its contract.

**Interfaces:** `static JsonNode document(ApprovalRequest request)`. Fields, in this order: `agentType` (string), `agentId` (string), `turn` (number), `callId`, `idempotencyKey`, `toolName`, `arguments` (the parsed JSON, not text), `action`, `askedAt` and `deadline` (ISO-8601 strings), `facts` (a deep copy).

- [ ] **Step 1: Tests, red where they are new.** `the_document_is_written_out_field_by_field` (the whole document compared with a hand-written JSON literal); `the_reply_token_is_not_in_it` (the token's value is nowhere in the document's text); `a_fact_added_after_the_document_was_built_is_not_in_it`; `the_same_question_is_the_same_reference` (two builds, `putDocument` twice, one reference).
- [ ] **Step 2:** Complete the builder if any test is red. Gate. Commit: `test: the approval question document is pinned field by field`.

### Task 3: A decision made at once names its question

**Files:** `ApprovalHandler.java` (the `Ready` arm); `EffectOutcome.ToolApproved`, `ToolDenied` (gain `Optional<PayloadRef> question`; keep the short constructors); `EffectOutcomes.java` (the two arms); `AgentCommand.ApprovalOutcome.Approved`, `Denied`; `AgentState.java` (the two constructions in `approved`); `AgentEvent.ToolApproved`, `ToolDenied`; `DefaultReplies.java` (a later answer carries `Optional.empty()`); tests; docs.

**Interfaces:**
- `EffectOutcome.ToolApproved(CallId callId, Optional<String> decidedBy, Optional<PayloadRef> question)`; `ToolDenied(CallId callId, String reason, Optional<String> decidedBy, Optional<PayloadRef> question)`.
- `ApprovalOutcome.Approved(Optional<String> decidedBy, Optional<PayloadRef> question)`; `Denied(String reason, Optional<String> decidedBy, Optional<PayloadRef> question)`.
- `AgentEvent.ToolApproved(seq, turn, callId, decidedBy, question, idempotencyKey)`; `ToolDenied(seq, turn, callId, reason, decidedBy, question, idempotencyKey)`. A null `question` is read as empty (an old row, and the codec).
- The narration does not change: story events carry no content references (spec §13.3).

The handler stores the document **after `binding.approve(question)` returns**, with `payloads.forAgent(agentId).putDocument(ApprovalQuestions.document(question))`, and puts the reference on the outcome.

- [ ] **Step 1: Fold tests, red.** `an_approval_is_recorded_with_the_question_it_was_decided_on`, `a_denial_is_recorded_with_the_question_it_was_decided_on`, and `a_later_answer_carries_no_question` (empty in, empty on the event).
- [ ] **Step 2: Handler tests, red.** In `ApprovalHandlerTest` (give it an `InMemoryPayloads`): the outcome's `question` resolves to a document equal to the request's; `a_fact_the_approver_added_while_deciding_is_in_the_stored_question` (Review Focus 4). Existing whole-outcome equality assertions there are reshaped to compare the other components and the resolved document, not weakened.
- [ ] **Step 3:** Carry it. In `AgentState` two constructor calls gain one argument each.
- [ ] **Step 4:** `ValueTypeCodecTest` literals with `"question":"<ref>"` for both events, and one literal without the key. Queued-door test in `DeferredApprovalTest`'s neighbor (`ToolCallingTest` or a new `ApprovalQuestionTest`, container): an approval decided at once stores a `ToolApproved` whose question resolves through `engine.payloads()`. Docs (`authorization.md`: the question is kept for every decision; `events.md`), CHANGELOG. Gate. Commit: `feat: an approval decided at once is recorded with its question`.

### Task 4: An approver that threw leaves its question on the failure

**Files:** `ApprovalHandler.java` (a `try` around `binding.approve(question)` that stores the question and rethrows `ApproverFailed`); create `nessy-engine/.../effect/ApproverFailed.java` (package-private if `EffectTermsSource` is in the same package, else public and engine-internal); `EffectTermsSource.java` (`AskingTerms.failed` reads the reference when the cause is an `ApproverFailed`, and reports the original cause's message as today); `EffectOutcome.ToolFailed` (gains `Optional<PayloadRef> question`; keep a three-argument constructor); `EffectOutcomes.java`; `AgentCommand.ToolOutcome.Failed`; `AgentState.java` (the one `ToolFailed` construction in `ran`); `AgentEvent.ToolFailed`; tests; docs.

**Interfaces:**
- `EffectOutcome.ToolFailed(CallId callId, CallFailure kind, String message, Optional<PayloadRef> question)`.
- `ToolOutcome.Failed(CallFailure kind, String message, Optional<PayloadRef> question)`.
- `AgentEvent.ToolFailed(seq, turn, callId, kind, message, question, idempotencyKey)`.
- Every other producer of a tool failure (`ToolCallHandler`, the `FAILED` sites of `ApprovalHandler`, `CallTerms`, `AskingTerms.undispatchable`, `DefaultReplies`) passes `Optional.empty()`. An expired deferral carries none: the deferral's question stands (spec §8a).
- The message the model is told is unchanged: `"the call could not be authorised: " + <the approver's own message>`.

- [ ] **Step 1: Fold test, red.** `a_failed_call_is_recorded_with_the_question_that_stood`, and that a failure with none records none.
- [ ] **Step 2: Handler and terms tests, red.** `ApprovalHandlerTest`: `an_approver_that_throws_leaves_its_question_stored` (the thrown `ApproverFailed` names a reference that resolves). `EffectTermsSourceTest`: `a_failed_ask_names_its_question`, and that a plain `RuntimeException` gives none and the same message as today.
- [ ] **Step 3: Retry is unchanged, red-first as a pin.** A queued-door container test (Review Focus 5): an approver that throws twice under a two-go approval retry policy is asked twice; the stored `ToolFailed` is `NOT_AUTHORISED` and its question resolves to the second ask (its `askedAt` is the second one). A direct-door test in `DefaultDirectHarnessTest`: an approver that throws gives a `ToolFailed` with a question.
- [ ] **Step 4:** Carry it; one argument in `AgentState`. `ValueTypeCodecTest` literal for `tool-failed` with a question, and one without. Docs, CHANGELOG. Gate. Commit: `feat: an approver that failed leaves the question it was asked on the record`.

### Task 5: Reading the question back

**Files:** `nessy-api/.../StoryContent.java`; `nessy-engine/.../story/StoredContent.java`; `StoryContentTest`, `EventAgentStoriesTest`, `EventAgentStoriesQueuedTest`; `docs/guides/narration.md` (or the story guide's content section); CHANGELOG.

**Interfaces:** `Optional<JsonNode> question(IdempotencyKey key);` on `StoryContent`, javadoc from spec §9c: "The question the call's approval was decided on, or is waiting on."

How it reads: scan for the call's events by key. A decision's own `question` wins (`ToolApproved`, `ToolDenied`, or a `ToolFailed` that has one). Otherwise the call's `ApprovalDeferred.question`, whether the call is still waiting, was decided later, or expired. The scan stops at the call's decision or its end. A reference that does not resolve is a fault (`IllegalStateException`), as elsewhere in `StoredContent`.

- [ ] **Step 1: Tests, red,** in `StoryContentTest`: decided at once; deferred and still waiting; deferred then approved later (the deferral's question); deferred then expired; the approver threw; a call that needed no approval gives empty; an unknown key gives empty.
- [ ] **Step 2:** Implement. Queued-door container test: a deferred approval's question is readable while the call waits and after it is answered, and is the same document.
- [ ] **Step 3:** Docs, CHANGELOG (`### Added`). Gate. Commit: `feat: the question behind a call's approval can be read from the story`.

---

## After the last task of each part

The final whole-branch review (Opus) is given the spec, this plan and the branch diff. For Part B it is asked to: read the whole `engine/core/` diff line by line against "This plan touches the fold" and list every line that is not a carried field; confirm that the pre-existing fold and dispatcher tests lost no assertion; confirm that an approval retry behaves as before Task 4; and confirm that no document holds a reply token.
