# Task Label Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every `nessy_agent_turn` row carries the turn's label, now defined as a category, so trajectories can be grouped by the kind of work.

**Architecture:** `TurnTrajectory.State` keeps `TurnStarted.label`, as it already keeps `arrivedAt`. `TurnRecorder.summarise` writes the label, sanitised for Postgres, into a new `AgentTurn.label` component. The JDBC store writes that into a new `label` column. The `inputLabel` contract on both harness configs changes to "a category, never content".

**Tech Stack:** Java 25, Spring `JdbcClient`, Postgres, JUnit 5 + AssertJ, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-07-trajectory-fingerprinting-design.md`. The amendment is §4.7 and ruling 10, plus the label lines in §6.2, §8 and §9.

## Global Constraints

- **The label is not part of the fingerprint.** `canonical`, `fingerprint`, `json` and every existing hash and JSON test stay unchanged.
- **Fold.** `TurnTrajectory.State` gains `String label`, placed right after `arrivedAt`. `State.opened(Instant arrivedAt, String label)` replaces `opened(Instant)`. `Idle.accept(TurnStarted)` passes `started.label()`.
- **Row type.** `AgentTurn` gains `String label` as the 8th component, right after `trajectoryJson`. It is not null and not blank.
- **Column.** `label VARCHAR(1000) NOT NULL`, right after `trajectory`. It is stored plain.
- **Sanitising.** The row's label has every U+0000 and every unpaired surrogate replaced by U+FFFD. This happens in `TurnRecorder.summarise` through a private helper. The event's label is never altered.
- **Contract.** The `inputLabel` javadoc, on both `DirectHarnessConfig` and `QueuedHarnessConfig`, says:
  - A label names the kind of work an input starts, from a small set of values, for example `rounds` or `invoice:PRICE_VARIANCE`.
  - It is stored plain, unencrypted, beside the trajectory.
  - It must not carry the input's content.
  - The default remains the input's simple class name.
- **No migration.** An existing `nessy_agent_turn` table is dropped and recreated.
- **House rules.**
  - Apache header on new files.
  - No star imports.
  - No `@SuppressWarnings`.
  - No method named `record`.
  - Prose test names.
  - Exactly one throwing call per `assertThatThrownBy` lambda.
  - Assert non-empty before any `allSatisfy`, `allMatch` or `noneMatch`.
  - Run `./mvnw -q spotless:apply license:format` before each commit.
  - Run the full gate once: `./mvnw -q clean verify -Dnessy.excludedGroups=live`.

## Review Focus

1. **A label with NUL must insert, never roll back an ending.** Pinned in Task 1 by a container test and a recorder test.
2. **Same behaviour, different labels, same hash.** Pinned in Task 1 by a recorder test.
3. **The default label is the class name.** On the direct door, with no `inputLabel` set, the row says the input's simple class name, `String` for a `String` input. Pinned in Task 1.
4. **Replay reproduces the label.** The existing stored-slice refold test must still compare whole rows, including the label. Task 1 checks that it does.
5. **The content-column guard.** chat-web's `ContentColumnsTest` must list `nessy_agent_turn.label`. Pinned in Task 1, Step 5.

---

### Task 1: The label through the fold, onto the row

**Files:**
- `nessy-engine/.../core/TurnTrajectory.java`: `State` gets `label` and `opened(Instant, String)`. `retried`, `settled` and `roundClosed` carry `label` through.
- `nessy-engine/.../core/AgentState.java`: `Idle.accept(TurnStarted)`.
- `nessy-engine/.../core/TurnRecorder.java`: `summarise` passes the sanitised label. Add a private static `columnSafe(String)`.
- `nessy-backend/spi/.../turn/AgentTurn.java`: the new component, its null and blank checks, and its `@param`.
- `nessy-backend/jdbc/src/main/resources/nessy-schema.sql`: the column, plus one line in the table comment.
- `nessy-backend/jdbc/.../JdbcAgentTurns.java`: INSERT, SELECT and row mapper.
- `nessy-api/.../DirectHarnessConfig.java` and `QueuedHarnessConfig.java`: the `inputLabel` javadoc.
- Tests updated for the new signatures: every `State.opened(` call (17 sites across `AgentStateTest`, `AgentStateDeferralTest`, `TurnTrajectoryTest` and `TurnTrajectoryJsonTest`) and every `new AgentTurn(` call (in `InMemoryAgentTurnsTest`, `JdbcAgentTurnsTest`, `AgentTurnTest` and the `withJson` helper). Pass any fixed label, such as `"Q"`, where the test does not care.
- `nessy-examples/chat-web/src/test/.../ContentColumnsTest.java`: add `nessy_agent_turn.label` to `NOT_CONTENT`, with the comment "the task label: a category by contract, not content".

**Tests to add (prose names):**
- `TurnRecorderTest.the_row_carries_the_label_the_turn_started_with`: `TurnStarted` has label `"invoice:PRICE_VARIANCE"`, so the row's label is the same string.
- `TurnRecorderTest.the_same_behavior_under_two_labels_is_one_trajectory`: two turns with identical events except their labels give equal `trajectory()` and `trajectoryJson()`, but different `label()`.
- `TurnRecorderTest.a_label_the_database_cannot_hold_is_made_safe_on_the_row`: the label `"a\u0000b\uD800c"` produces the row label `"a�b�c"`.
- `DirectHarnessTrajectoryTest.a_harness_with_no_input_label_records_the_input_class_name`: the existing harness uses `String` input with no `inputLabel`, so the row's label is `"String"`.
- `DirectHarnessTrajectoryTest.a_configured_input_label_is_what_the_row_records`: build a harness with `.inputLabel(input -> "lookup")`. Copy the harness construction the class already uses and add the call. The row's label is `"lookup"`.
- `JdbcAgentTurnsTest.a_label_with_a_replacement_character_round_trips`: a row whose label is `"a�b"` reads back equal.
- `JdbcAgentTurnsTest.turns_group_by_label`: three rows. Two are labelled `"x"`, and both share one trajectory hash. One is labelled `"y"`. SQL `SELECT label, COUNT(*) ... GROUP BY label ORDER BY label` returns `x=2`, `y=1`.
- Check that `a_stored_row_is_reproduced_by_refolding_the_stored_slice_of_its_turn` (in `DirectHarnessTrajectoryTest`) still compares whole rows with `isEqualTo`, so the label is covered. If it ignores fields, add the label back into the comparison.

**Steps:**
- [ ] Write the new tests, then run them and see them fail. The new signatures cause compile errors, which counts as RED.
- [ ] Implement in this order: fold (`State`, `AgentState`), `AgentTurn`, recorder, schema and JDBC, contract javadoc, guard.
- [ ] Run these scoped tests, and expect exit 0:
  ```
  ./mvnw -q -pl :nessy-engine,:nessy-backend-jdbc,:nessy-backend-inmemory,:nessy-backend-spi -am test -Dnessy.excludedGroups=live
  ```
- [ ] Run chat-web's guard, and expect exit 0:
  ```
  ./mvnw -q -pl :nessy-example-chat-web -am test -Dnessy.excludedGroups=live -Dtest=ContentColumnsTest -Dsurefire.failIfNoSpecifiedTests=false
  ```
- [ ] Format, then commit with the message `feat: every turn row carries its task label, and a label is a category`, followed by the two trailer lines.

---

### Task 2: Docs, changelog, gate

**Files:**
- `docs/concepts/trajectories.md`:
  - Add a `label` row to the column table.
  - Add a short section, `## Trajectories by task`. It explains that the label is the kind of work and is not part of the fingerprint. It shows the per-label query from spec §4.7, then explains that a label with one trajectory is a workflow candidate and a label with many is where judgment lives. It also shows the reverse question, which paths are shared by different kinds of work:
  ```sql
  SELECT trajectory_hash, array_agg(DISTINCT label) AS labels
  FROM nessy_agent_turn WHERE trajectory_version = 1
  GROUP BY trajectory_hash HAVING COUNT(DISTINCT label) > 1;
  ```
- Every docs page that explains `inputLabel` (`grep -rn "inputLabel" docs README.md`). State the category contract there in one or two sentences.
- `CHANGELOG.md`, under `## [Unreleased]`:
  - `### Added`: **The task label on every turn row.** The label an application gives an input is stored in `nessy_agent_turn.label`, so trajectories group by the kind of work.
  - `### Changed`: **A label is a category.** `inputLabel` names the kind of work an input starts, from a small set of values, and is stored plain, unencrypted. An application whose label carries input content must change it. A label containing NUL, or an unpaired surrogate, is stored with U+FFFD in that character's place.
- Spec status line: `**Amendment, 2026-10-07: the task label (§4.7, ruling 10). APPROVED by James (option 1: the label is a category). BUILT.**`

**Steps:**
- [ ] Edit the Markdown with the file-editing tool, never `sed`.
- [ ] Build the docs and expect exit 0: `mkdocs build --strict`, using the scratchpad venv.
- [ ] Run the full gate and expect exit 0: `./mvnw -q spotless:apply license:format && ./mvnw -q clean verify -Dnessy.excludedGroups=live`.
- [ ] Commit with the message `docs: the task label`, followed by the trailers.
