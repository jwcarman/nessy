# chat-web on the Queued Door — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `nessy-examples/chat-web` runs on the queued door: a message is told and the request returns at once, the answer reaches the page over the agent's event stream, an approval is deferred and read from `AgentWork`, and a person's decision goes back through `Replies`.

**Architecture:** The message endpoint calls `QueuedHarness.tell` and returns `202`. The approver returns `Awaited.deferred()` and keeps nothing. The page's state read returns the transcript and the agent's waiting approvals from `AgentWork.status`. A decision is `Replies.approve(type, agent, idempotency key, result)`. The in-memory `ApprovalDesk`, the second event stream `ApprovalStreams`, and the direct door's lock bean are deleted. Nothing outside `nessy-examples/chat-web` and the docs changes.

**Tech Stack:** Java 25, Maven, Spring Boot 4, the Nessy starter (queued harness, JDBC backend, PostgreSQL), `nessy-narration-odyssey` (journaled SSE), JUnit 5, AssertJ, Awaitility, Testcontainers.

**Spec:** Ruled by James in conversation on 2026-10-05 ("We use SSE to stream the responses. Let's convert it."). The reads and replies it uses are specified in `docs/superpowers/specs/2026-10-04-agent-work-design.md`. chat-web was on the queued door until commit `65ad25168` (2026-09-25); `git show 65ad25168^:nessy-examples/chat-web/...` shows the earlier queued controller and configuration, which are a reference, not a thing to restore: they used a reply token and an in-memory desk.

## Global Constraints

- Only `nessy-examples/chat-web/**`, `docs/**` (not `docs/superpowers/**` except this plan), `README.md` and `CHANGELOG.md` change. No file in any `nessy-*` library module changes. A task that seems to need a library change stops and reports.
- No state in process across requests: no map of pending approvals, no future held for an answer. What is waiting is read from `AgentWork` on every request.
- The example uses the public API where one exists. Its transcript read uses the engine-internal `TurnHistories` today; that use stays as it is and is not widened.
- The page's endpoint does not authenticate anyone, as today; the README says the endpoint decides who may answer.
- No `@SuppressWarnings`, `// NOSONAR` or any other suppression. No star imports. No mocking library. Prose test names. One throwing call inside an exception-assertion lambda (S5778). Non-emptiness asserted before an all-match or none-match (S5841). Tests wait on latches or Awaitility, never a sleep.
- Scoped Maven runs select by artifactId and always pass `-am`. One Maven process in a worktree, in the foreground. `./mvnw spotless:apply license:format` before each commit.
- A task's last step is the gate: `./mvnw -B -q clean verify -Dnessy.excludedGroups=live > target-gate.log 2>&1; rc=$?; mkdir -p target; echo $rc > target/gate.exit`. The controller reads the file and the report totals itself.
- Do not start the application, a model server or Docker compose. Tests only (Testcontainers is fine).
- Docs describe what is, in plain American wording, with no history. An `ApprovalRequest` is an approval request.
- **Review:** each task gets the task review on Sonnet under the proving rule (a behaviour finding comes with a test, run in the reviewer's own scratch worktree). The final whole-branch review is on Opus.

## Review Focus

1. **A second message while a turn is in progress.** It is accepted with `202` and runs after the turn; the page is not told "busy" and loses nothing.
2. **An approval that outlives the page.** The page is closed and reopened, or the application restarts: the card is still there, because it is read from Nessy, and it can still be answered.
3. **Two tabs answer one card.** One is told it was applied; the other gets `409` and redraws.
4. **An answer after the approval's deadline.** `409`, the card goes, the transcript shows the call failed.
5. **A provider that does not stream.** The answer still appears on the page when the turn ends.

---

### Task 1: The server and the page

**Files:** `nessy-examples/chat-web/src/main/java/org/jwcarman/nessy/examples/chatweb/ChatConfiguration.java`, `ChatController.java`; delete `ApprovalDesk.java`, `ApprovalStreams.java`; `src/main/resources/static/app.js` (and `index.html` / `style.css` only if a card needs a new field); `src/main/resources/application.yml`; `pom.xml` only if a dependency becomes unused or needed; tests `ChatApprovalIntegrationTest`, `EndingIntegrationTest`, `ScriptedProvider`, `PostgresBacked`, and the others the compiler names.

**Interfaces (HTTP, under `/api/agents`):**
- `POST /{id}/messages` with `{"text": ...}` → `harness.tell(agent, text)`; `202`, empty body. A terminated agent's refusal (read `QueuedHarness.tell`'s contract for how it is reported) maps to `409` with a short reason. No `Busy`.
- `GET /{id}` → `{"transcript": [...], "approvals": [...]}`. `transcript` as today. `approvals` is `work.status(TYPE, agent).waitingApprovals()`, each as a card: `id` (the idempotency key's text), `tool`, `args` (the arguments, pretty-printed when they are JSON, as the desk did), `what` (the action line), `askedAt`, `deadline`.
- `POST /{id}/approvals/{key}` with `{"decision": "approve"|"deny", "note": ...}` → `replies.approve(TYPE, agent(id), key, result)`; `Applied` → `202`; `Ignored` → `409`. A key that is not a well-formed key is `400`. The result names who decided the way the example does today (`approved()` / `denied(note)`).
- `GET /{id}/events` unchanged. `GET /{id}/approvals/events` is removed.
- `DELETE /{id}` → `harness.terminate(agent)`; `202`.

**Server:**
- The harness bean is a `QueuedHarness<String>` made from the starter's queued factory bean, with exactly the tools, ambient sources, chapter policy, summariser and system prompt the direct one has today. Read how `nessy-examples/watchman/.../WatchmanConfiguration.java` gets and uses the factory.
- The email tool's approver is `request -> Awaited.deferred()`. The time a person has to answer stays five minutes: set it where the queued door takes an approval's term (read `ToolConfig` / the binding customizer and the watchman's `watchman.approval-term`), as a property `chat.approval-term` defaulting to `PT5M`.
- The `Locks` bean that exists only for the direct door is removed if the queued backend does not need it; say in the report what you found.
- `AgentWork` and `Replies` are injected beans (the starter provides both with a queued backend).

**Page (`app.js`):**
- A card's id is the idempotency key. `decide` posts to `/approvals/{key}`.
- The second `EventSource` and everything that feeds it goes. Cards come from the state read: after `load()`, and again whenever the agent's stream says an approval was deferred or a call was approved, denied, finished or failed (event names from `OdysseyNarrator`: `approval-deferred`, `call-approved`, `call-denied`, `call-finished`, `call-failed`), the page fetches `GET /{id}` and redraws the cards (adding new ones, removing ones no longer waiting).
- Sending no longer disables the input for the whole turn on the strength of a `409`; a message sent while a turn is in progress is accepted. The page still shows that the agent is working between `turn-started` and the turn's end.
- When a turn ends with an answer and nothing was streamed for it (a provider that does not stream), the page reads the answer from `GET /{id}` and draws it (Review Focus 5). Check what the `answered` event carries before deciding how.
- Comments in the file say what the page does.

- [ ] **Step 1: Tests, red** (SpringBootTest over PostgreSQL with the scripted provider, as the example's tests are today; rewrite `ChatApprovalIntegrationTest` and `EndingIntegrationTest` for the new flow, keeping every behaviour they pin that still applies):
  - `a_message_is_accepted_at_once_and_answered_on_the_stream_and_in_the_transcript`;
  - `a_second_message_during_a_turn_is_accepted_and_answered_after_it` (Review Focus 1);
  - `an_email_waits_for_a_person_and_the_state_lists_it_as_a_card` (the card's id is the idempotency key; tool, what, args, askedAt, deadline are right);
  - `a_waiting_card_is_still_there_for_a_second_application_context_on_the_same_database` (Review Focus 2: start a second context, or rebuild the controller's collaborators over the same database, and read the state);
  - `approving_a_card_sends_the_email_and_the_turn_answers`;
  - `denying_a_card_tells_the_model_and_nothing_is_sent`;
  - `a_second_answer_to_one_card_is_a_409_and_changes_nothing` (Review Focus 3);
  - `an_answer_after_the_term_is_a_409_and_the_call_is_recorded_as_failed` (Review Focus 4; a short `chat.approval-term` for this test's context);
  - `a_malformed_key_is_a_400`, `an_unknown_key_is_a_409`;
  - `ending_a_conversation_keeps_its_story_and_refuses_another_message`;
  - `no_approvals_stream_endpoint_remains` (the removed path is a 404).
- [ ] **Step 2:** Implement the server. `git grep -n -E "ApprovalDesk|ApprovalStreams|CompletableFuture|DirectHarness|harness\.ask|Outcome\." -- nessy-examples/chat-web/src` returns nothing.
- [ ] **Step 3:** Implement the page. There is no JavaScript test harness in this example; verify by reading: every endpoint the script calls exists with that method and shape, and every event name it listens for is one `OdysseyNarrator` emits. List both tables in the report.
- [ ] **Step 4:** Gate. Commit: `refactor: chat-web runs on the queued door, and its approvals are read from Nessy`.

### Task 2: The docs say what chat-web is

**Files:** `nessy-examples/chat-web/README.md`; `README.md` (the examples list near line 123); `docs/concepts/authorization.md` ("A desk on a page"); `docs/concepts/cost.md` near line 127 (it says chat-web's controller reads a turn's cost straight from the answer); `docs/concepts/twelve-factor-agents.md` near lines 292 and 413-417 (it lists chat-web as an HTTP endpoint that calls `ask`, and says what chat-web holds); `docs/guides/narration.md` near line 325 (two streams); `docs/guides/spring-boot.md` near line 269 (chat-web declares its own `DirectHarness`); `docs/guides/observability.md` if it describes chat-web's spans by door; `CHANGELOG.md` (`### Changed`).

- [ ] **Step 1:** For each passage, read the code at HEAD and write what is true. Where a page used chat-web as its example of `ask` behind HTTP or of reading cost from an `Outcome`, point at an example that still does that (`nessy-examples/chat-cli` and `nessy-examples/chapter-lab` are on the direct door: check what each shows before citing it), or describe the shape without naming an example. Never leave a sentence that names chat-web for something it does not do.
- [ ] **Step 2:** The chat-web README says: the door it uses and why (a message is told and the request returns; the answer and the narration arrive on one journaled stream; an approval waits in Nessy, not in the application, so it outlives the page and the process); how a card is listed and answered; that the page's endpoint is what decides who may answer; how to run it.
- [ ] **Step 3:** `python3 -m mkdocs build --strict`. `git grep -n -i "chat-web" -- docs/concepts docs/guides docs/index.md README.md nessy-examples` — every hit is true of the code. Commit: `docs: chat-web is the conversation on the queued door`.

---

## After the last task

The final whole-branch review (Opus, proving rule) is asked to: confirm no library module changed; confirm chat-web holds nothing in process between requests; run the five Review Focus behaviours against the real stack; confirm the page script and the server agree on every endpoint and event name; confirm every doc sentence naming chat-web is true; and say what, if anything, the repo no longer has an example of.
