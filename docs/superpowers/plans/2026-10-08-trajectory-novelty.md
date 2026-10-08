# Trajectory Novelty Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every completed turn says whether its trajectory, for its agent type and label, had ever been seen before: a `novel` bit on `AgentTurn`, on the `nessy_agent_turn` row, and on the turn span, decided by a write-once `nessy_known_trajectory` table.

**Architecture:** `TurnRecorder.recordEnding` asks the store `firstSighting(type, label, trajectory, at)` before it builds the row; JDBC answers with `INSERT ... ON CONFLICT DO NOTHING` on the step's ambient transaction (1 row = novel), in-memory answers from a synchronized `Set`. The row carries the answer, and the span gets `nessy.trajectory.novel`.

**Tech Stack:** Java 25, Spring `JdbcClient`, PostgreSQL 18 (Testcontainers), JUnit 5 + AssertJ, Micrometer Observation.

**Spec:** `docs/superpowers/specs/2026-10-08-trajectory-novelty-design.md` (amends `2026-10-07-trajectory-fingerprinting-design.md`). Executors read the spec's §2, §4, §5, §9.

## Global Constraints

- Approved names, exactly: table `nessy_known_trajectory`; record component and column `novel`; span key `nessy.trajectory.novel`; SPI method `AgentTurns.firstSighting(AgentType type, String label, Trajectory trajectory, Instant at)` returning `boolean`. No other new public type, method or vocabulary word.
- `nessy_known_trajectory` has exactly the columns `agent_type, label, trajectory_version, trajectory_hash, first_seen` and primary key `(agent_type, label, trajectory_version, trajectory_hash)`. No count, no `last_seen`, no agent id, no foreign key.
- The sighting is `INSERT ... ON CONFLICT (...) DO NOTHING`, never `DO UPDATE`, never a `SELECT` pre-check. It runs on the caller's ambient transaction; `JdbcAgentTurns` opens none.
- `novel` is a high-cardinality key value on the observation, never low-cardinality.
- `novel` column is `BOOLEAN NOT NULL`, placed after `label` in `nessy_agent_turn`. `AgentTurn`'s `boolean novel` component is placed after `label`.
- No metric, no per-type switch, no listener-visible event, no migration or in-code `ALTER` (no-backward-compatibility rule).
- Tests: prose names, no mocking library; an exception-assertion lambda holds exactly one throwing call; assert non-emptiness before any all/none-match predicate.
- No `@SuppressWarnings`, no star imports. Every new source file carries the Apache header (`./mvnw license:format`), formatted by `./mvnw spotless:apply`.
- Builds: iterate with `./mvnw -q -pl :<artifactId> -am test` (artifactId with colon, `-am` because the SPI changes); never two Maven processes at once. Final gate per task as stated in the task.

## Review Focus

1. Two agents of one type ending on the same new path at the same moment: exactly one row says `novel`, on JDBC (two real transactions) and in memory. — Task 2 (JDBC), Task 1 (in-memory door test).
2. A step that rolls back after the sighting (the row insert throws): no known-trajectory row survives, and the next turn on that path is novel. — Task 2, `DurableDirectHarnessTest`.
3. The longest label the engine can produce (1,000 UTF-16 units of *incompressible* 3-byte characters, about 3,000 bytes) in the primary key: the turn must still commit. — Task 2 probe, with the byte cap of spec §2.7 if it fails.
4. A label the engine made column-safe (U+0000 → U+FFFD) is sighted under the same string the row stores, so the two tables join. — Task 1 `TurnRecorderTest`.
5. Replaying a turn's events through a fresh recorder with a fresh store still reproduces the row (novel on both sides), so the existing replay-equality tests keep holding. — Task 1, existing `the_live_fold_and_a_replay_of_the_same_events_agree` and `a_stored_row_is_reproduced_by_refolding...` must stay green unchanged in their assertions.

---

### Task 1: The novelty bit, from the store to the row and the span

**Model:** Sonnet (`implementer`); review Sonnet (`task-reviewer`).

**Files:**
- Modify: `nessy-backend/spi/src/main/java/org/jwcarman/nessy/backend/turn/AgentTurn.java` (component + javadoc)
- Modify: `nessy-backend/spi/src/main/java/org/jwcarman/nessy/backend/turn/AgentTurns.java` (method + javadoc)
- Modify: `nessy-backend/inmemory/src/main/java/org/jwcarman/nessy/backend/inmemory/InMemoryAgentTurns.java`
- Modify: `nessy-backend/jdbc/src/main/java/org/jwcarman/nessy/backend/jdbc/JdbcAgentTurns.java`
- Modify: `nessy-backend/jdbc/src/main/resources/nessy-schema.sql` (around lines 262-288)
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/core/TurnRecorder.java`
- Modify (tests, constructor call sites and fakes): `nessy-backend/spi/src/test/java/org/jwcarman/nessy/backend/turn/AgentTurnTest.java`, `nessy-backend/inmemory/src/test/java/org/jwcarman/nessy/backend/inmemory/InMemoryAgentTurnsTest.java`, `nessy-backend/jdbc/src/test/java/org/jwcarman/nessy/backend/jdbc/JdbcAgentTurnsTest.java` (constructor sites only in this task), `nessy-engine/src/test/java/org/jwcarman/nessy/engine/core/TurnRecorderTest.java`, `nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/direct/DirectHarnessTrajectoryTest.java`, `nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/queued/QueuedHarnessTrajectoryTest.java` (fake at ~line 255), `nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/direct/DurableDirectHarnessTest.java` (fake at ~line 319)
- Modify: `nessy-examples/chat-web/src/test/java/org/jwcarman/nessy/examples/chatweb/ContentColumnsTest.java` (allowlist)

**Interfaces:**
- Produces: `AgentTurn(TurnId turn, Seq endingSeq, Instant arrivedAt, Instant startedAt, Instant endedAt, Trajectory trajectory, String trajectoryJson, String label, boolean novel, TurnOutcome outcome, int rounds, int toolCalls, int toolSuccesses, int toolFailures, int toolDenials, int inferenceCalls, int inferenceRetries)`; `boolean AgentTurns.firstSighting(AgentType type, String label, Trajectory trajectory, Instant at)`; `TurnRecorder.NOVEL = "nessy.trajectory.novel"` (package-private constant, like `HASH`).

- [ ] **Step 1: Write the failing in-memory store tests**

Add to `InMemoryAgentTurnsTest` (fix its `turn(long)` helper to pass `true` for `novel` after `"Q"`):

```java
  private static final Instant AT = Instant.parse("2026-10-08T12:00:00Z");

  @Test
  void the_first_sighting_of_a_trajectory_is_novel_and_the_second_is_not() {
    assertThat(turns.firstSighting(TYPE, "Q", TRAJECTORY, AT)).isTrue();
    assertThat(turns.firstSighting(TYPE, "Q", TRAJECTORY, AT.plusSeconds(1))).isFalse();
  }

  @Test
  void the_same_trajectory_under_another_label_type_or_version_is_novel_again() {
    turns.firstSighting(TYPE, "Q", TRAJECTORY, AT);
    assertThat(turns.firstSighting(TYPE, "R", TRAJECTORY, AT)).isTrue();
    assertThat(turns.firstSighting(new AgentType("other"), "Q", TRAJECTORY, AT)).isTrue();
    assertThat(
            turns.firstSighting(
                TYPE, "Q", new Trajectory((short) 2, TRAJECTORY.hash()), AT))
        .isTrue();
  }

  @Test
  void a_row_keeps_the_novelty_it_was_appended_with() {
    AgentTurn known = withNovel(turn(10), false);
    turns.append(TYPE, AGENT, known);
    assertThat(turns.of(TYPE, AGENT)).singleElement().extracting(AgentTurn::novel).isEqualTo(false);
  }
```

with a helper that rebuilds the record with `novel` replaced (copy every component of `turn` explicitly, `novel` from the argument).

- [ ] **Step 2: Run, expect compile failure**

Run: `./mvnw -q -pl :nessy-backend-inmemory -am test`
Expected: FAIL — `firstSighting` / `novel` do not exist.

- [ ] **Step 3: Add the component and the SPI method**

`AgentTurn`: add `boolean novel` after `String label`; javadoc `@param novel whether this was the first turn of its agent type and label to take its trajectory under its version, as the store answered at {@link AgentTurns#firstSighting}`. No new check in the compact constructor.

`AgentTurns`, before `append`:

```java
  /**
   * Records that a turn of this agent type, with this label, ended on this trajectory, and says
   * whether that had ever happened before. Called before {@link #append}, in the same unit of
   * work, so the row can carry the answer. Write-once: a repeat changes nothing.
   *
   * @param at when the turn ended; kept as the first time only on a first sighting
   * @return true if this is the first time: the turn is novel
   */
  boolean firstSighting(AgentType type, String label, Trajectory trajectory, Instant at);
```

Extend the interface javadoc's last paragraph: `{@link #firstSighting} and {@link #append} are both called inside the same unit of work as the append of the turn-ending event, ...`.

`InMemoryAgentTurns`:

```java
  private record Known(AgentType type, String label, Trajectory trajectory) {}

  private final Set<Known> known = new HashSet<>();

  @Override
  public synchronized boolean firstSighting(
      AgentType type, String label, Trajectory trajectory, Instant at) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(label, "label must not be null");
    Objects.requireNonNull(trajectory, "trajectory must not be null");
    Objects.requireNonNull(at, "at must not be null");
    return known.add(new Known(type, label, trajectory));
  }
```

Class javadoc: add `Known trajectories are kept for the life of this instance and never forgotten, as a table nothing deletes from would keep them; nothing rolls a sighting back.`

- [ ] **Step 4: JDBC write path and schema**

In `nessy-schema.sql`, add `novel BOOLEAN NOT NULL,` after `label` in `nessy_agent_turn`, add to that table's comment the line from spec §4.2, and append after the turn table's index the exact `nessy_known_trajectory` DDL and comment from spec §4.2 (copy verbatim).

`JdbcAgentTurns`: add `novel` to `INSERT` column list (after `label`) with one more `?`, pass `turn.novel()` after `turn.label()`; add `novel` to `SELECT` and `rs.getBoolean("novel")` in `read` after label. Add:

```java
  private static final String SIGHT =
      """
      INSERT INTO nessy_known_trajectory
             (agent_type, label, trajectory_version, trajectory_hash, first_seen)
      VALUES (?, ?, ?, ?, ?)
          ON CONFLICT (agent_type, label, trajectory_version, trajectory_hash) DO NOTHING
      """;

  @Override
  public boolean firstSighting(AgentType type, String label, Trajectory trajectory, Instant at) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(label, "label must not be null");
    Objects.requireNonNull(trajectory, "trajectory must not be null");
    Objects.requireNonNull(at, "at must not be null");
    return jdbc.sql(SIGHT)
            .params(type.value(), label, trajectory.version(), trajectory.hash(), timestamp(at))
            .update()
        == 1;
  }
```

Class javadoc: one sentence — the sighting is write-once (`DO NOTHING`, never `DO UPDATE`, which would lock a common path's row under every concurrent commit) and rides the same ambient transaction.

Fix the constructor call in `JdbcAgentTurnsTest.turn(...)` (pass `true` after `"Q"`) and in `AgentTurnTest`. Add to `ContentColumnsTest.NOT_CONTENT`, with a comment `// The known-trajectory key: the same type, label and hash the turn row holds.`: `"nessy_known_trajectory.agent_type"`, `"nessy_known_trajectory.label"`, `"nessy_known_trajectory.trajectory_hash"`.

- [ ] **Step 5: Run the store tests**

Run: `./mvnw -q -pl :nessy-backend-inmemory -am test` → PASS.

- [ ] **Step 6: Write the failing engine tests**

In `TurnRecorderTest` add a fake store field (no mocking library):

```java
  /** A store that answers a scripted bit and remembers what it was asked, and in what order. */
  private static final class ScriptedTurns implements AgentTurns {
    private final boolean answer;
    private final InMemoryAgentTurns rows = new InMemoryAgentTurns();
    final List<String> calls = new ArrayList<>();

    ScriptedTurns(boolean answer) {
      this.answer = answer;
    }

    @Override
    public boolean firstSighting(AgentType type, String label, Trajectory trajectory, Instant at) {
      calls.add("sight " + label + " " + trajectory.hash() + " " + at);
      return answer;
    }

    @Override
    public void append(AgentType type, AgentId agent, AgentTurn turn) {
      calls.add("append " + turn.turn().value());
      rows.append(type, agent, turn);
    }

    @Override
    public List<AgentTurn> of(AgentType type, AgentId agent) {
      return rows.of(type, agent);
    }
  }
```

Tests:

```java
  @Test
  void the_row_carries_the_novelty_the_store_answered() {
    for (boolean answer : List.of(true, false)) {
      TurnRecorder recorder = new TurnRecorder(TYPE, new ScriptedTurns(answer), ObservationRegistry.NOOP);
      AgentTurn row =
          recorder
              .recordEnding(AGENT, AgentState.idle(Seq.NONE), oneRoundThenAnswer(false), ENDED)
              .orElseThrow();
      assertThat(row.novel()).isEqualTo(answer);
    }
  }

  @Test
  void the_store_is_asked_once_before_the_row_with_the_rows_label_trajectory_and_end() {
    ScriptedTurns store = new ScriptedTurns(true);
    TurnRecorder recorder = new TurnRecorder(TYPE, store, ObservationRegistry.NOOP);
    AgentTurn row =
        recorder
            .recordEnding(AGENT, AgentState.idle(Seq.NONE), oneRoundThenAnswer(false, "a\u0000b"), ENDED)
            .orElseThrow();
    assertThat(row.label()).isEqualTo("a�b");
    assertThat(store.calls)
        .containsExactly(
            "sight a�b " + row.trajectory().hash() + " " + ENDED,
            "append " + row.turn().value());
  }

  @Test
  void the_first_turn_on_a_path_is_novel_and_the_next_is_not() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    AgentTurn first =
        recorder.recordEnding(AGENT, AgentState.idle(Seq.NONE), oneRoundThenAnswer(false), ENDED).orElseThrow();
    AgentTurn second =
        recorder
            .recordEnding(new AgentId(UUID.randomUUID()), AgentState.idle(Seq.NONE), oneRoundThenAnswer(false), ENDED)
            .orElseThrow();
    assertThat(first.novel()).isTrue();
    assertThat(second.novel()).isFalse();
  }
```

Extend `events_that_end_no_turn_record_nothing` (or add a sibling) so a `ScriptedTurns` store's `calls` is empty after events that end no turn. Extend `the_current_observation_is_tagged_with_the_trajectory` with `assertThat(context.getHighCardinalityKeyValue("nessy.trajectory.novel").getValue()).isEqualTo("true");` and add a sibling using `new ScriptedTurns(false)` that asserts `"false"`.

- [ ] **Step 7: Run, expect failure**

Run: `./mvnw -q -pl :nessy-engine -am test -Dtest=TurnRecorderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL (compile: `summarise` / `novel`).

- [ ] **Step 8: Implement in `TurnRecorder`**

In `recordEnding`, replace `AgentTurn row = summarise(inferring, event, ending.get(), at);` with:

```java
          TurnTrajectory.State trajectory = inferring.trajectory();
          boolean novel =
              turns.firstSighting(
                  type,
                  columnSafe(trajectory.label()),
                  TurnTrajectory.fingerprint(trajectory, ending.get()),
                  at);
          AgentTurn row = summarise(inferring, event, ending.get(), at, novel);
```

`summarise` gains `boolean novel` as last parameter and passes it after `columnSafe(trajectory.label())`. Fix every other caller of `summarise` (grep the engine's tests: `DirectHarnessTrajectoryTest.a_stored_row_is_reproduced_by_refolding...` goes through `recordEnding`, so only direct callers need it). Add `static final String NOVEL = "nessy.trajectory.novel";` and in `tag` `.highCardinalityKeyValue(NOVEL, Boolean.toString(row.novel()))` after `VERSION_KEY`. Class javadoc: one paragraph — the store is asked first whether the path is new, with the row's column-safe label, so the known table and the row agree; the novelty tag is as best-effort as the hash.

Engine fakes: the anonymous `AgentTurns` in `QueuedHarnessTrajectoryTest` (~255) and `DurableDirectHarnessTest` (~319) gain

```java
        @Override
        public boolean firstSighting(
            AgentType type, String label, Trajectory trajectory, Instant at) {
          return real.firstSighting(type, label, trajectory, at);
        }
```

- [ ] **Step 9: Door tests (in-memory)**

`DirectHarnessTrajectoryTest`: in `two_asks_with_different_inputs_and_the_same_path_share_a_trajectory` add `assertThat(rows.get(0).novel()).isTrue(); assertThat(rows.get(1).novel()).isFalse();`. Add:

```java
  @Test
  void two_agents_of_one_type_on_one_path_make_one_novel_row_between_them() {
    DirectHarness<String, String> harness = harness(ObservationRegistry.NOOP, c -> {});
    AgentId first = new AgentId(UUID.randomUUID());
    AgentId second = new AgentId(UUID.randomUUID());
    harness.ask(first, "look up 7");
    harness.ask(second, "look up 8");
    assertThat(turns.of(TYPE, first)).singleElement().extracting(AgentTurn::novel).isEqualTo(true);
    assertThat(turns.of(TYPE, second)).singleElement().extracting(AgentTurn::novel).isEqualTo(false);
  }
```

In `the_invoke_agent_span_carries_the_trajectory` add `assertThat(turnSpans.getFirst().getHighCardinalityKeyValue("nessy.trajectory.novel").getValue()).isEqualTo("true");`.

`QueuedHarnessTrajectoryTest`: add a test that tells two agents (fresh `AgentId`s) the same input, waits for both rows with `tellAndAwaitItsRow`, and asserts exactly one of the two rows is novel (`assertThat(List.of(a.novel(), b.novel())).containsExactlyInAnyOrder(true, false)`); in `the_effect_span_carries_the_trajectory_hash_the_row_holds` assert the span's `nessy.trajectory.novel` equals `Boolean.toString(row.novel())`.

- [ ] **Step 10: Run engine tests**

Run: `./mvnw -q -pl :nessy-engine -am test` → PASS (exit code 0; never grep Maven output for success).

- [ ] **Step 11: Final gate and commit**

Run: `./mvnw spotless:apply license:format` then `./mvnw -q clean verify` → exit 0.

```bash
git add -A nessy-backend nessy-engine nessy-examples/chat-web
git commit -m "feat: a turn is novel when its trajectory was never seen before"
```

---

### Task 2: Certify the JDBC sighting, and probe the label in the key

**Model:** Sonnet (`implementer`); review Opus (`task-reviewer` override — the case is transaction concurrency).

**Files:**
- Modify: `nessy-backend/jdbc/src/test/java/org/jwcarman/nessy/backend/jdbc/JdbcAgentTurnsTest.java`
- Modify: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/direct/DurableDirectHarnessTest.java` (`a_row_that_cannot_be_written_rolls_the_ending_back_on_the_direct_door`, ~line 273)
- Conditionally modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/core/TurnRecorder.java` (`columnSafe`) and `TurnRecorderTest` — Step 5 only.

**Interfaces:**
- Consumes: everything Task 1 produced.

- [ ] **Step 1: Store cases on Postgres**

Add to `JdbcAgentTurnsTest` (each test uses a fresh `AgentType` like `new AgentType("t" + UUID.randomUUID().toString().substring(0, 8))` so tests sharing the container never see each other's known rows):

- `the_first_sighting_of_a_trajectory_is_novel_and_the_second_is_not`
- `the_same_trajectory_under_another_label_type_or_version_is_novel_again`
- `a_sighting_keeps_the_first_time_and_a_repeat_does_not_move_it` — sight at `t`, again at `t+60s`; `SELECT first_seen FROM nessy_known_trajectory WHERE agent_type = ?` read as `OffsetDateTime` equals `t` (truncate `t` to micros).
- `a_row_reads_back_the_novelty_it_was_written_with` — append one row `novel=false`, one `true` (different turn ids), read back both values.

- [ ] **Step 2: Rollback and concurrency on two real transactions**

Use `DataSourceTransactionManager` + `TransactionTemplate` over the test's `DataSource` (two templates, two threads, a `CountDownLatch` to hold the first transaction open after its sighting). Cases:

- `a_sighting_in_a_rolled_back_transaction_leaves_nothing_and_the_next_is_novel` — inside `template.execute`, sight then `status.setRollbackOnly()`; afterwards `firstSighting` is `true` and the table has one row for the key.
- `two_transactions_sighting_one_new_path_at_once_make_exactly_one_novel` — thread A sights inside a transaction and waits on a latch; thread B starts its sighting (it blocks on A's index entry); after a short check that B has not returned (B's future not done after 200 ms), release A to commit; B returns `false`. Assert A=`true`, B=`false`, and `SELECT COUNT(*)` for the key is 1.
- `when_the_first_of_two_rolls_back_the_second_is_novel` — same as above but A rolls back; B returns `true`.

Use `ExecutorService` with two platform threads, `Future.get(30, SECONDS)` so a hang fails rather than stalls.

- [ ] **Step 3: Rollback through the door**

In `DurableDirectHarnessTest.a_row_that_cannot_be_written_rolls_the_ending_back_on_the_direct_door` add after the existing assertions:

```java
    assertThat(
            JdbcClient.create(database)
                .sql("SELECT COUNT(*) FROM nessy_known_trajectory WHERE agent_type = ?")
                .params(TYPE.value())
                .query(Long.class)
                .single())
        .isZero();
```

If `TYPE` is shared with other tests in the class that commit turns, use a type unique to this test instead (check before asserting zero; the assertion must be about this test's sighting only).

- [ ] **Step 4: The label probe**

```java
  @Test
  void the_longest_label_the_engine_writes_can_key_a_known_trajectory() {
    // 1,000 UTF-16 units (ToolConfig.LINE_CAP, the engine's cap) of 3-byte characters: ~3,000
    // bytes, the worst case. Pseudo-random so Postgres cannot compress the index entry below its
    // limit; a repeated character would pass for the wrong reason.
    Random random = new Random(20261008L);
    StringBuilder label = new StringBuilder();
    while (label.length() < 1000) {
      int c = 0x0800 + random.nextInt(0xD7FF - 0x0800);
      label.append((char) c);
    }
    AgentType type = new AgentType("long-label");
    assertThat(turns.firstSighting(type, label.toString(), new Trajectory((short) 1, "ab".repeat(32)), Instant.now()))
        .isTrue();
  }
```

Run: `./mvnw -q -pl :nessy-backend-jdbc -am test -Dnessy.excludedGroups=live -Dtest=JdbcAgentTurnsTest -Dsurefire.failIfNoSpecifiedTests=false`

Record in the report whether this test PASSED or FAILED (with the Postgres error text).

- [ ] **Step 5: Only if Step 4 FAILED — the byte cap (spec §2.7)**

Keep the probe as the red test. In `TurnRecorder.columnSafe`, after the U+FFFD replacement, cut the result to at most `LABEL_BYTES = 1000` UTF-8 bytes at a code-point boundary (private constant, javadoc: the most a key column can hold with room to spare under Postgres's btree entry limit). Add a `TurnRecorderTest` case `a_label_longer_than_the_column_key_allows_is_cut_on_a_character_boundary` (input 1,000 × `'一'`, expect 333 characters, `getBytes(UTF_8).length <= 1000`). Change the probe to build its label through the same rule (the backend cannot call the engine: assert instead that a 1,000-byte label of random 3-byte characters inserts, and keep a second case that the 3,000-byte one is refused, documenting why the cap exists). Update `AgentTurn`'s `label` javadoc: "cut to 1,000 bytes".

If Step 4 PASSED: no code change; rename nothing; the probe stays as the proof.

- [ ] **Step 6: Gate and commit**

Run: `./mvnw spotless:apply license:format` then `./mvnw -q clean verify -Dnessy.excludedGroups=live` → exit 0.

```bash
git add -A nessy-backend nessy-engine
git commit -m "test: the known-trajectory sighting holds under rollback and two concurrent first sightings"
```

---

### Task 3: Docs

**Model:** Sonnet (`docs-writer`); review Sonnet (`task-reviewer`).

**Files:**
- Modify: `docs/concepts/trajectories.md`
- Modify: `docs/concepts/storage.md`

- [ ] **Step 1: Write the doc changes of spec §11**, exactly:
  - turn record table (≈ lines 190-215) gains `novel`; the "two groups" paragraph says it is behavioral, decided at turn end;
  - "On the trace" (≈ 283-287) lists eight attributes, adding `nessy.trajectory.novel`;
  - "Anomalies" (≈ 350-361): "a trajectory never seen before" points at `novel`;
  - a new "Novelty" section: what the bit means (per agent type, label and version), that it survives the turns that produced it in `nessy_known_trajectory`, the warm-up (fresh install, upgrade, version bump all start empty — arm alerts after paths repeat), the upgrade step (`DROP TABLE nessy_agent_turn;` then start), and the optional seed statement from spec §10.3, labelled optional and run by the operator before the drop;
  - replace the "Turns whose trajectory first appeared in the last day" starter query with the two queries in spec §11;
  - `storage.md`: a row for `nessy_known_trajectory` in the table of tables (retention: kept, not removed with an agent), and `nessy_known_trajectory.label` in the list of columns stored plain (≈ line 222).
  Docs describe what is: no history, no roads not taken. If the line numbers moved, find the sections by heading.

- [ ] **Step 2: Prove the queries.** Run each new SQL statement against a Postgres 18 container with the schema applied (e.g. `docker run --rm -d -p 55432:5432 -e POSTGRES_PASSWORD=x postgres:18-alpine`, apply `nessy-backend/jdbc/src/main/resources/nessy-schema.sql` with `psql`, run each query; stop the container). Each must parse and run. Report the commands and output.

- [ ] **Step 3: Commit**

```bash
git add docs/concepts/trajectories.md docs/concepts/storage.md
git commit -m "docs: novelty, the known-trajectories table, and the queries that read them"
```
