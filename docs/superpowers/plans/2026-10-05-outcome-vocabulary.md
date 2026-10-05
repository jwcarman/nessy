# Outcome Vocabulary — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each harness operation reports with a type named for it, and an agent that has been terminated is called terminated everywhere: `AskOutcome`, `TellOutcome`, a `Terminated` arm on both, and no public name that says "ended".

**Architecture:** Three changes to public names and one to behaviour on each door. The direct door's `ask` returns `AskOutcome<O>`, with a `Terminated` arm in place of `Refused("terminated", …)`. The queued door's `tell` returns `TellOutcome` (`Accepted` or `Terminated`) in place of nothing. `TerminationOutcome`'s arms and `AgentStatus.Activity` take the same word. The fold is not touched.

**Tech Stack:** Java 25, Maven (`./mvnw`), JUnit 5, AssertJ, Awaitility, Testcontainers, Spring Boot 4 (starter and examples).

**Spec:** James's rulings in conversation on 2026-10-05 (recorded here; there is no separate spec document):
1. `Outcome<O>` becomes `AskOutcome<O>`.
2. `AskOutcome` gains `Terminated<T>()`, with no stats, for an agent that has been terminated: no turn ran. `Refused` means only that the model declined.
3. `QueuedHarness.tell` returns `TellOutcome`: `Accepted()` (the input is stored and will be given to the agent) or `Terminated()` (the agent has been terminated; the input was dropped).
4. `TerminationOutcome.Ended` becomes `Terminated`; `AlreadyEnded` becomes `AlreadyTerminated`.
5. `AgentStatus.Activity.ENDED` becomes `TERMINATED`.
6. "I want the verbiage to be consistent": the operation is `terminate`, so an agent is terminated, never ended, in names, javadoc and docs.

Not approved, so not built: an arm for an input a backlog policy discarded; an outcome from the queued door's `terminate` (it stays `void`).

## Global Constraints

- **The fold is not edited.** No file under `nessy-engine/src/main/java/org/jwcarman/nessy/engine/core/` changes. A task that seems to need to stops and reports.
- New public names in `nessy-api` are exactly: `AskOutcome` (with `Answered`, `Refused`, `Failed`, `Busy`, `Terminated`), `TellOutcome` (with `Accepted`, `Terminated`), `TerminationOutcome.Terminated`, `TerminationOutcome.AlreadyTerminated`, `AgentStatus.Activity.TERMINATED`. Removed: `Outcome`, `TerminationOutcome.Ended`, `TerminationOutcome.AlreadyEnded`, `AgentStatus.Activity.ENDED`. A task that needs another public or SPI name stops and reports.
- Stored shapes do not change. The story event `Terminated`, its JSON name `terminated` and the stream event name `terminated` are already the right word and are not touched.
- Red first, where behaviour changes (Tasks 1 and 2). A pure rename is proven by the compiler and the gate.
- No `@SuppressWarnings`, `// NOSONAR` or any other suppression. No star imports. No mocking library. Prose test names. One throwing call inside an exception-assertion lambda (S5778). Non-emptiness asserted before an all-match or none-match (S5841). Tests wait on latches or Awaitility, never a sleep.
- Scoped Maven runs select by artifactId and always pass `-am`. One Maven process in a worktree, in the foreground. `./mvnw spotless:apply license:format` before each commit.
- A task's last step is the gate: `./mvnw -B -q clean verify -Dnessy.excludedGroups=live > target-gate.log 2>&1; rc=$?; mkdir -p target; echo $rc > target/gate.exit`, run after the last edit; the controller reads the file and the report totals itself.
- Do not start an application, a model server or Docker compose. Tests only.
- Docs describe what is, in plain American wording, with no history (the CHANGELOG is where a change is said). Javadoc is edited in place: one block per member.
- **Review:** Task 2 (the queued harness) gets the high-risk review on Opus. Tasks 1 and 3 get the task review on Sonnet. Every review follows the proving rule: a finding about behaviour or a thin test comes with the test, run in the reviewer's own scratch worktree. The final whole-branch review is on Opus.

## Review Focus

1. **A terminated agent that is asked.** `AskOutcome.Terminated`, no turn opened, nothing appended to the story, nothing spent; never `Refused`.
2. **A terminated agent that is told.** `TellOutcome.Terminated`, nothing stored in the backlog, nothing appended, no nudge; and an agent terminated while a turn is in progress answers `Terminated` to a `tell` at once, though its status is still working until that turn ends.
3. **A told input that a policy merges or replaces.** Still `Accepted`: the outcome says the agent took input, not what the backlog policy then did with it.
4. **`tell` inside a caller's transaction.** The outcome is the same, and a rollback leaves nothing stored.
5. **Every word.** No public name, javadoc sentence or docs sentence calls a terminated agent "ended".

---

### Task 1: `ask` returns `AskOutcome`, and a terminated agent is `Terminated`

**Files:** rename `nessy-api/.../api/Outcome.java` to `AskOutcome.java`; `nessy-api/.../api/DirectHarness.java`; `nessy-engine/.../harness/direct/DefaultDirectHarness.java` (the return near line 380, and every use of the type); every other user of `Outcome` in main code (17 files), tests (26 files) and docs (11 files) that the compiler and `git grep` name: `nessy-console`, `nessy-examples/chat-cli`, `nessy-examples/chapter-lab`, the starter, `docs/concepts`, `docs/guides`, `README.md`; `CHANGELOG.md`.

**Interfaces:**
- `public sealed interface AskOutcome<T>` with `Answered<T>(T value, TurnStats stats)`, `Refused<T>(String category, TurnStats stats)`, `Failed<T>(String reason, TurnStats stats)`, `Busy<T>()`, `Terminated<T>()`.
- `AskOutcome<O> ask(AgentId agent, I input);` on `DirectHarness`.
- `Refused`'s javadoc keeps its meaning: the model declined. `Terminated`'s javadoc: the agent has been terminated, no turn ran, and so, like `Busy`, it carries no tally.
- Every exhaustive `switch` over the type gains a `Terminated` arm with a deliberate meaning (the console says the conversation has been terminated; an example prints it). No `default` arm is added to avoid one.

- [ ] **Step 1: Tests, red.** In `DefaultDirectHarnessTest` and `DurableDirectHarnessTest`, the two assertions that a terminated agent's `ask` equals `new Outcome.Refused<>("terminated", …)` become `isEqualTo(new AskOutcome.Terminated<>())`, plus: the story is unchanged by the ask, and no payload is written. Add `a_model_refusal_is_still_refused_and_is_not_terminated`.
- [ ] **Step 2:** Rename the type (`git mv`), add the arm, change the harness. `git grep -n -E "\bOutcome<|Outcome\.(Answered|Refused|Failed|Busy)|api\.Outcome\b" -- '*.java' docs README.md nessy-examples` returns nothing that means the old type (list any hit that is another type, such as `ReplyOutcome.`).
- [ ] **Step 3:** Docs and javadoc use the new name; CHANGELOG `### Breaking changes`: `Outcome` is `AskOutcome`; asking a terminated agent returns `Terminated`, not `Refused` with the category `terminated`. Gate. Commit: `refactor: ask returns AskOutcome, and a terminated agent is Terminated`.

### Task 2: `tell` returns `TellOutcome`

High-risk: the queued harness. Opus review.

**Files:** create `nessy-api/.../api/TellOutcome.java`; `nessy-api/.../api/QueuedHarness.java`; `nessy-engine/.../harness/queued/DefaultQueuedHarness.java` (`tell`, near line 157); test doubles of `QueuedHarness` the compiler names; callers `nessy-examples/watchman/.../WatchmanRounds.java` and `nessy-examples/chat-web/.../ChatController.java` (the minimum to compile here; their real change is Task 3); tests; `CHANGELOG.md`.

**Interfaces:**
- `public sealed interface TellOutcome { record Accepted() implements TellOutcome {} record Terminated() implements TellOutcome {} }`
- `TellOutcome tell(AgentId agentId, I input);` on `QueuedHarness`.
- `Accepted` exactly when the input was handed to the backlog policy inside the locked step (whatever the policy then did with it, and whether or not a turn started at once). `Terminated` exactly when the agent had been terminated, including while its last turn is still in progress: nothing is stored, nothing is appended, the dispatcher is not nudged. Today that path returns silently with a debug log; the log stays.
- Nothing else about `tell` changes: the lock, the transaction it joins, the narration, the nudge.

- [ ] **Step 1: Tests, red** (container, through `EngineFixture`): `an_input_told_to_an_idle_agent_is_accepted`; `an_input_told_during_a_turn_is_accepted_and_waits`; `an_input_a_policy_replaces_is_still_accepted` (a `keepLatest` harness: two tells during a turn, both `Accepted`, one waits; Review Focus 3); `an_input_told_to_a_terminated_agent_is_terminated_and_nothing_is_stored` (backlog count 0, story unchanged, no nudge; Review Focus 2); `an_agent_terminated_during_a_turn_answers_terminated_at_once` (a turn parked on an approval; terminate; tell → `Terminated` while `AgentWork.status` is still `WAITING`); `tell_inside_a_transaction_that_rolls_back_stores_nothing_and_was_accepted` and the committing twin (Review Focus 4).
- [ ] **Step 2:** Implement. The existing tests of `tell` pass unchanged apart from doubles' return types.
- [ ] **Step 3:** CHANGELOG (`### Breaking changes`: `tell` returns `TellOutcome`; an implementation of `QueuedHarness` must return one). Gate. Commit: `feat: tell says whether the agent took the input or has been terminated`.

### Task 3: One word for a terminated agent, and the examples use what `tell` says

**Files:** `nessy-api/.../api/TerminationOutcome.java`, `AgentStatus.java`; `nessy-engine/.../harness/direct/DefaultDirectHarness.java`, `.../work/StoredAgentWork.java`; `nessy-console` (its fake harness and anything that reads the outcome); the starter; every test that names the old arms or `ENDED`; `nessy-examples/chat-web` (`ChatController`, its tests, `README.md`, `static/app.js` only if it names the state); `nessy-examples/watchman` (`WatchmanRounds`); `docs/concepts`, `docs/guides`, `docs/index.md`, `README.md`, example READMEs; `CHANGELOG.md`.

**Interfaces:**
- `TerminationOutcome`: `Terminated()` (this call terminated the agent), `AlreadyTerminated()`, `Busy()`.
- `AgentStatus.Activity`: `IDLE`, `WORKING`, `WAITING`, `TERMINATED`.
- chat-web: `POST /{id}/messages` answers `202` for `TellOutcome.Accepted` and `409` for `TellOutcome.Terminated`, by an exhaustive `switch`; the status read before the tell is removed. The test that pinned "a message sent after the end, while the last turn is still in progress, is accepted with 202 and dropped" now asserts `409`, at once, and that nothing is queued. The `say` javadoc and the README paragraph that explained the 202 window are replaced by what is true.
- watchman: `WatchmanRounds` logs at WARN when its nudge is answered `Terminated`.

- [ ] **Step 1: Tests, red:** chat-web's renamed 409 test (it fails until the controller switches on the outcome); a watchman unit test for the WARN if one is cheap with its existing test style, otherwise say why not.
- [ ] **Step 2:** Rename the arms and the enum value; adopt the outcome in both examples.
- [ ] **Step 3: The word.** In javadoc and docs, a terminated agent is "terminated"; the operation is "terminate". Run `git grep -n -i -E "\bended\b|\bending\b|\bends\b|\bend\b" -- nessy-api/src/main nessy-engine/src/main nessy-spring-boot nessy-console/src/main docs/concepts docs/guides docs/index.md README.md 'nessy-examples/*/README.md' 'nessy-examples/*/src/main'` and judge every hit: one that speaks of an agent or a conversation that has been terminated is reworded; one that speaks of a turn ending, a stream ending, a sentence ending, or `TurnEnding` (a different, correct name) is left. List every hit and the decision in the report. In the chat-web page and README, the person-facing words for the action ("New chat") stay; where the README names the engine's state it says "terminated".
- [ ] **Step 4:** `git grep -n -E "TerminationOutcome\.(Ended|AlreadyEnded)|\bAlreadyEnded\b|Activity\.ENDED|\bENDED\b" -- . ':!docs/superpowers' ':!CHANGELOG.md'` returns nothing. CHANGELOG (`### Breaking changes`: the two arms and the enum value renamed; `### Changed`: chat-web answers 409 as soon as a conversation is terminated). `python3 -m mkdocs build --strict`. Gate. Commit: `refactor: a terminated agent is called terminated, and the examples act on what tell says`.

---

## After the last task

The final whole-branch review (Opus, proving rule) is asked to: confirm no file under `engine/core/` changed and no stored shape or event name changed; confirm the five Review Focus behaviours with tests; confirm `tell`'s lock, transaction, narration and nudge are otherwise as before; search the public API, the javadoc and the docs for any remaining "ended" that means a terminated agent; and confirm the CHANGELOG's breaking changes are complete for someone upgrading from 0.4.0.
