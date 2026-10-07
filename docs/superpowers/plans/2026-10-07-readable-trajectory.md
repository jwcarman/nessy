# Readable Trajectory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every `nessy_agent_turn` row carries its trajectory as JSON, the canonical structure the hash encodes, so SQL can read and query behaviour without refolding events.

**Architecture:** `TurnTrajectory` gains a pure `json(State, TurnOutcome)` rendering that walks the same sorted rounds `canonical` encodes, escaping names `jsonb` cannot hold. `TurnRecorder.summarise` puts the rendering on `AgentTurn`, a new `String trajectoryJson` component. The JDBC store writes it into a new `trajectory JSONB` column, and the in-memory store keeps the string.

**Tech Stack:** Java 25, Jackson 3 (`tools.jackson`) tree nodes, Spring `JdbcClient`, Postgres `jsonb`, JUnit 5 + AssertJ, Testcontainers (`@Tag("container")`).

**Spec:** `docs/superpowers/specs/2026-10-07-trajectory-fingerprinting-design.md`. Only the amendment applies: §4.6, ruling 9, and the amendment lines in §6.1, §6.2, §8, §9 and §10.

## Global Constraints

- **JSON shape:** `{"rounds":[[{"tool":"<name>","outcome":"SUCCESS|FAILED|DENIED"}, ...], ...],"outcome":"<TurnOutcome name>"}`. Keys appear in that order and with no whitespace. A turn with no tool calls has `"rounds":[]`.
- **Ordering:** rounds are in the order they happened. Entries within a round are in exactly the sorted order `canonical` encodes, with duplicates kept.
- **Escaping:** a tool name that is not well-formed UTF-16, or that contains U+0000, has each unpaired surrogate and each NUL replaced by the six ASCII characters `\uXXXX` (uppercase hex). Its entry gains `"escaped":true` as a third key. Well-formed names are written unchanged.
- **The hash is not touched.** `canonical` and `fingerprint` do not change, and every existing `TurnTrajectoryTest` passes unmodified.
- **Column:** `trajectory JSONB NOT NULL`, placed after `trajectory_hash`. It is stored plain, not through the codec. JDBC writes it with `CAST(? AS JSONB)` and reads it back with `trajectory::text`.
- **Row type:** `AgentTurn` gains `String trajectoryJson` as its 7th component, right after `trajectory`. It is not null and not blank.
- **No migration.** An existing `nessy_agent_turn` table is dropped and recreated (the no-backward-compatibility rule). No `ALTER TABLE` goes in the schema file.
- **Code rules:**
  - Apache header on every new file.
  - No star imports.
  - No `@SuppressWarnings`.
  - Prose test names with `@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)`.
  - One throwing call per `assertThatThrownBy` lambda.
  - Assert a collection is not empty before any `allSatisfy`, `allMatch` or `noneMatch` on it.
  - No method named exactly `record` (Sonar S6213).
- **Build:** scoped runs while iterating (`./mvnw -q -pl :<artifactId> -am test`). Container tests run with `-Dnessy.excludedGroups=live`. The full gate `./mvnw -q clean verify -Dnessy.excludedGroups=live` runs once, at the end of the last task. Run `./mvnw -q spotless:apply license:format` before every commit. Check Maven exit codes, never log text.

## Review Focus

1. **Postgres normalises `jsonb`.** A row read back has the key order and spacing Postgres chooses, not the engine's string. Round-trip tests must compare parsed JSON, not strings. Pinned in Task 2's `a_recorded_turn_reads_back_exactly`.
2. **A malformed name must insert, never roll back the ending.** A row with a lone surrogate and a NUL in its tool names must insert into real Postgres. Pinned in Task 2's `a_row_whose_tool_names_jsonb_cannot_hold_still_inserts`.
3. **The rendering is one-to-one.** A real name that contains the literal characters `\uD800` must render differently from an escaped lone surrogate. Pinned in Task 1's `a_name_that_merely_spells_an_escape_is_not_flagged`.
4. **Equal JSON exactly when equal hash.** Over every trajectory shape the hash tests use, equal renderings must coincide with equal fingerprints. Pinned in Task 1's `two_renderings_are_equal_exactly_when_their_hashes_are`.
5. **The content-column guard fails the build.** chat-web's `ContentColumnsTest` lists every text and `jsonb` column. A new `jsonb` column it does not know fails the build. Fixed and checked in Task 2, Step 6.

---

### Task 1: `TurnTrajectory.json`, the readable rendering

**Files:**
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/core/TurnTrajectory.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/core/TurnTrajectoryJsonTest.java` (new)

**Interfaces:**
- Consumes these existing members: `TurnTrajectory.State` (`opened(Instant)`, `settled(ToolName, CallOutcome)`, `roundClosed()`, `completed()`), `Round.entries()`, `Entry.tool()`/`outcome()`, `CallOutcome`, `fingerprint(State, TurnOutcome)`, and `TurnOutcome`.
- Produces `public static String json(State state, TurnOutcome outcome)` on `TurnTrajectory`.

- [ ] **Step 1: Write the failing tests**

```java
package org.jwcarman.nessy.engine.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.engine.core.TurnTrajectory.CallOutcome;
import org.jwcarman.nessy.engine.core.TurnTrajectory.State;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TurnTrajectoryJsonTest {

  private static final ToolName A = new ToolName("a");
  private static final ToolName B = new ToolName("b");
  private static final ToolName C = new ToolName("c");

  private static State opened() {
    return State.opened(Instant.EPOCH);
  }

  @Test
  void a_turn_that_called_no_tool_has_no_rounds() {
    assertThat(TurnTrajectory.json(opened(), TurnOutcome.ANSWERED))
        .isEqualTo("{\"rounds\":[],\"outcome\":\"ANSWERED\"}");
  }

  @Test
  void a_round_is_written_in_the_order_the_hash_encodes_it_with_duplicates_kept() {
    State state =
        opened()
            .settled(B, CallOutcome.SUCCESS)
            .settled(A, CallOutcome.FAILED)
            .settled(A, CallOutcome.SUCCESS)
            .roundClosed();
    assertThat(TurnTrajectory.json(state, TurnOutcome.STOPPED))
        .isEqualTo(
            "{\"rounds\":[[{\"tool\":\"a\",\"outcome\":\"SUCCESS\"},"
                + "{\"tool\":\"a\",\"outcome\":\"FAILED\"},"
                + "{\"tool\":\"b\",\"outcome\":\"SUCCESS\"}]],\"outcome\":\"STOPPED\"}");
  }

  @Test
  void rounds_are_written_in_the_order_they_happened() {
    State state =
        opened()
            .settled(C, CallOutcome.SUCCESS)
            .roundClosed()
            .settled(A, CallOutcome.DENIED)
            .roundClosed();
    assertThat(TurnTrajectory.json(state, TurnOutcome.TRUNCATED))
        .isEqualTo(
            "{\"rounds\":[[{\"tool\":\"c\",\"outcome\":\"SUCCESS\"}],"
                + "[{\"tool\":\"a\",\"outcome\":\"DENIED\"}]],\"outcome\":\"TRUNCATED\"}");
  }

  @Test
  void every_turn_outcome_is_written_by_name() {
    for (TurnOutcome outcome : TurnOutcome.values()) {
      assertThat(TurnTrajectory.json(opened(), outcome))
          .endsWith("\"outcome\":\"" + outcome.name() + "\"}");
    }
  }

  @Test
  void a_lone_surrogate_is_written_as_escape_text_and_flagged() {
    State state = opened().settled(new ToolName("x\uD800y"), CallOutcome.FAILED).roundClosed();
    assertThat(TurnTrajectory.json(state, TurnOutcome.ANSWERED))
        .isEqualTo(
            "{\"rounds\":[[{\"tool\":\"x\\\\uD800y\",\"outcome\":\"FAILED\",\"escaped\":true}]],"
                + "\"outcome\":\"ANSWERED\"}");
  }

  @Test
  void a_nul_is_written_as_escape_text_and_flagged() {
    State state = opened().settled(new ToolName("x\u0000y"), CallOutcome.FAILED).roundClosed();
    assertThat(TurnTrajectory.json(state, TurnOutcome.ANSWERED))
        .contains("\"tool\":\"x\\\\u0000y\"")
        .contains("\"escaped\":true");
  }

  @Test
  void a_well_formed_surrogate_pair_is_written_as_it_is() {
    State state = opened().settled(new ToolName("😀"), CallOutcome.SUCCESS).roundClosed();
    assertThat(TurnTrajectory.json(state, TurnOutcome.ANSWERED))
        .contains("\"tool\":\"😀\"")
        .doesNotContain("escaped");
  }

  @Test
  void a_name_that_merely_spells_an_escape_is_not_flagged() {
    State spelled = opened().settled(new ToolName("x\\uD800y"), CallOutcome.FAILED).roundClosed();
    State lone = opened().settled(new ToolName("x\uD800y"), CallOutcome.FAILED).roundClosed();
    assertThat(TurnTrajectory.json(spelled, TurnOutcome.ANSWERED))
        .doesNotContain("escaped")
        .isNotEqualTo(TurnTrajectory.json(lone, TurnOutcome.ANSWERED));
  }

  @Test
  void two_renderings_are_equal_exactly_when_their_hashes_are() {
    List<State> states = new ArrayList<>();
    states.add(opened());
    states.add(opened().settled(A, CallOutcome.SUCCESS).roundClosed());
    states.add(opened().settled(A, CallOutcome.SUCCESS).settled(B, CallOutcome.SUCCESS).roundClosed());
    states.add(opened().settled(B, CallOutcome.SUCCESS).settled(A, CallOutcome.SUCCESS).roundClosed());
    states.add(
        opened()
            .settled(A, CallOutcome.SUCCESS)
            .settled(A, CallOutcome.SUCCESS)
            .settled(B, CallOutcome.SUCCESS)
            .roundClosed());
    states.add(
        opened()
            .settled(A, CallOutcome.SUCCESS)
            .settled(B, CallOutcome.SUCCESS)
            .roundClosed()
            .settled(C, CallOutcome.SUCCESS)
            .roundClosed());
    states.add(
        opened()
            .settled(A, CallOutcome.SUCCESS)
            .roundClosed()
            .settled(B, CallOutcome.SUCCESS)
            .settled(C, CallOutcome.SUCCESS)
            .roundClosed());
    states.add(opened().settled(A, CallOutcome.FAILED).roundClosed());
    states.add(opened().settled(A, CallOutcome.DENIED).roundClosed());
    states.add(opened().settled(new ToolName("x\uD800"), CallOutcome.FAILED).roundClosed());
    states.add(opened().settled(new ToolName("x\\uD800"), CallOutcome.FAILED).roundClosed());
    assertThat(states).isNotEmpty();
    for (State left : states) {
      for (State right : states) {
        for (TurnOutcome lo : TurnOutcome.values()) {
          for (TurnOutcome ro : TurnOutcome.values()) {
            boolean sameJson = TurnTrajectory.json(left, lo).equals(TurnTrajectory.json(right, ro));
            boolean sameHash =
                TurnTrajectory.fingerprint(left, lo).equals(TurnTrajectory.fingerprint(right, ro));
            assertThat(sameJson).as("%s/%s vs %s/%s", left, lo, right, ro).isEqualTo(sameHash);
          }
        }
      }
    }
  }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./mvnw -q -pl :nessy-engine -am test -Dtest=TurnTrajectoryJsonTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: a compilation failure, `cannot find symbol: method json`.

- [ ] **Step 3: Add the rendering to `TurnTrajectory`**

Add these imports: `tools.jackson.databind.node.ArrayNode`, `tools.jackson.databind.node.JsonNodeFactory` and `tools.jackson.databind.node.ObjectNode`. Add these members after `fingerprint`:

```java
  /**
   * The trajectory as JSON, for the row: the same rounds in the same order, each round's entries in
   * the order {@link #canonical} encodes them, and the turn's outcome. Compact, keys in a fixed
   * order. Not the bytes hashed -- a database may reorder keys -- but one-to-one with them: under one
   * version, two renderings are equal exactly when the two fingerprints are.
   *
   * <p>A name Postgres {@code jsonb} cannot hold (an unpaired surrogate, a NUL) would make the row
   * refuse to insert and roll back the turn's ending on every retry. Such a name is written with
   * each offending character as the six characters {@code \}{@code uXXXX}, and its entry is flagged
   * {@code "escaped": true} so it never equals a real name that happens to spell the same text.
   */
  public static String json(State state, TurnOutcome outcome) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(outcome, "outcome must not be null");
    JsonNodeFactory nodes = JsonNodeFactory.instance;
    ObjectNode root = nodes.objectNode();
    ArrayNode rounds = root.putArray("rounds");
    for (Round round : state.completed()) {
      ArrayNode entries = rounds.addArray();
      for (Entry entry : round.entries()) {
        ObjectNode written = entries.addObject();
        String name = entry.tool().value();
        String safe = jsonSafe(name);
        written.put("tool", safe);
        written.put("outcome", entry.outcome().name());
        if (!safe.equals(name)) {
          written.put("escaped", true);
        }
      }
    }
    root.put("outcome", outcome.name());
    return root.toString();
  }

  /** The name with each unpaired surrogate and each NUL written as {@code \}{@code uXXXX}. */
  private static String jsonSafe(String name) {
    StringBuilder out = new StringBuilder(name.length());
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      boolean pairedHigh =
          Character.isHighSurrogate(c)
              && i + 1 < name.length()
              && Character.isLowSurrogate(name.charAt(i + 1));
      if (pairedHigh) {
        out.append(c).append(name.charAt(i + 1));
        i++;
      } else if (c == '\u0000' || Character.isSurrogate(c)) {
        out.append(String.format("\\u%04X", (int) c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
```

`toString()` on a Jackson 3 `JsonNode` writes compact JSON, and `ObjectNode` keeps insertion order. If `toString()` does not give the exact compact form the tests expect, write the tree with `tools.jackson.databind.json.JsonMapper.shared().writeValueAsString(root)` instead, and say so in the report. A `for` loop that advances its own index with `i++` may trip Sonar S127. If the build or the IDE flags it, rewrite the loop as a `while` loop with an explicit index, without changing behaviour.

- [ ] **Step 4: Run the new tests and the existing hash tests**

Run: `./mvnw -q -pl :nessy-engine -am test -Dtest='TurnTrajectoryJsonTest,TurnTrajectoryTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: both classes PASS, with `TurnTrajectoryTest` unmodified.

- [ ] **Step 5: Format and commit**

```bash
./mvnw -q spotless:apply license:format
git add nessy-engine
git commit -m "feat(engine): TurnTrajectory renders the readable trajectory as JSON" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011J7jUvSHRDoVnSiocuAsAo"
```

---

### Task 2: The row carries it: `AgentTurn`, the column, both stores, the recorder

**Files:**
- Modify: `nessy-backend/spi/src/main/java/org/jwcarman/nessy/backend/turn/AgentTurn.java`
- Modify: `nessy-backend/jdbc/src/main/resources/nessy-schema.sql` (the `nessy_agent_turn` table and its comment)
- Modify: `nessy-backend/jdbc/src/main/java/org/jwcarman/nessy/backend/jdbc/JdbcAgentTurns.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/core/TurnRecorder.java` (`summarise`)
- Modify (constructor call sites): `nessy-backend/spi/src/test/java/org/jwcarman/nessy/backend/turn/AgentTurnTest.java`, `nessy-backend/inmemory/src/test/java/org/jwcarman/nessy/backend/inmemory/InMemoryAgentTurnsTest.java`, `nessy-backend/jdbc/src/test/java/org/jwcarman/nessy/backend/jdbc/JdbcAgentTurnsTest.java`
- Modify: `nessy-examples/chat-web/src/test/java/org/jwcarman/nessy/examples/chatweb/ContentColumnsTest.java` (`NOT_CONTENT`)
- Test: `JdbcAgentTurnsTest` (new cases); `nessy-engine/src/test/java/org/jwcarman/nessy/engine/core/TurnRecorderTest.java` (one new case)

**Interfaces:**
- Consumes: `TurnTrajectory.json(State, TurnOutcome)` (Task 1).
- Produces: `AgentTurn(TurnId turn, Seq endingSeq, Instant arrivedAt, Instant startedAt, Instant endedAt, Trajectory trajectory, String trajectoryJson, TurnOutcome outcome, int rounds, int toolCalls, int toolSuccesses, int toolFailures, int toolDenials, int inferenceCalls, int inferenceRetries)`.

- [ ] **Step 1: Write the failing tests**

In `JdbcAgentTurnsTest`, change the `turn(long, TurnOutcome)` helper so it passes a JSON string as the 7th argument. Use this constant, which describes a turn with 2 rounds and 4 calls (3 succeeded, 1 failed), so it matches the helper's counts:

```java
  private static final String JSON =
      "{\"rounds\":[[{\"tool\":\"read\",\"outcome\":\"FAILED\"},"
          + "{\"tool\":\"search\",\"outcome\":\"SUCCESS\"},{\"tool\":\"search\",\"outcome\":\"SUCCESS\"}],"
          + "[{\"tool\":\"fetch\",\"outcome\":\"SUCCESS\"}]],\"outcome\":\"ANSWERED\"}";
```

Postgres normalises `jsonb`, so `a_recorded_turn_reads_back_exactly` now compares every component except the JSON exactly, and compares the JSON parsed:

```java
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void a_recorded_turn_reads_back_exactly() {
    AgentTurn recorded = turn(418, TurnOutcome.ANSWERED);
    turns.append(TYPE, agent, recorded);
    assertThat(turns.of(TYPE, agent))
        .singleElement()
        .satisfies(
            read -> {
              assertThat(read)
                  .usingRecursiveComparison()
                  .ignoringFields("trajectoryJson")
                  .isEqualTo(recorded);
              assertThat(MAPPER.readTree(read.trajectoryJson()))
                  .isEqualTo(MAPPER.readTree(recorded.trajectoryJson()));
            });
  }

  @Test
  void a_row_whose_tool_names_jsonb_cannot_hold_still_inserts() {
    // Exactly what TurnTrajectory.json renders for one round of `x\uD800` FAILED and `y\u0000`
    // FAILED, ending ANSWERED. A literal, because the backend modules do not depend on the engine.
    String escaped =
        "{\"rounds\":[[{\"tool\":\"x\\\\uD800\",\"outcome\":\"FAILED\",\"escaped\":true},"
            + "{\"tool\":\"y\\\\u0000\",\"outcome\":\"FAILED\",\"escaped\":true}]],"
            + "\"outcome\":\"ANSWERED\"}";
    turns.append(TYPE, agent, withJson(turn(7, TurnOutcome.ANSWERED), escaped, 1, 0, 2, 0));
    assertThat(turns.of(TYPE, agent))
        .singleElement()
        .extracting(AgentTurn::trajectoryJson)
        .asString()
        .contains("escaped");
  }

  @Test
  void a_denied_call_is_found_by_containment() {
    String denied =
        "{\"rounds\":[[{\"tool\":\"prune_images\",\"outcome\":\"DENIED\"}]],\"outcome\":\"ANSWERED\"}";
    turns.append(TYPE, agent, withJson(turn(1, TurnOutcome.ANSWERED), denied, 1, 0, 0, 1));
    turns.append(TYPE, agent, turn(2, TurnOutcome.ANSWERED));
    List<Long> found =
        JdbcClient.create(database)
            .sql(
                "SELECT turn_id FROM nessy_agent_turn WHERE agent_id = ? AND trajectory @> "
                    + "'{\"rounds\": [[{\"tool\": \"prune_images\", \"outcome\": \"DENIED\"}]]}'")
            .params(agent.value())
            .query(Long.class)
            .list();
    assertThat(found).containsExactly(1L);
  }

  /** The same row with a different trajectory and the counts that trajectory implies. */
  private static AgentTurn withJson(
      AgentTurn row, String json, int rounds, int ok, int fail, int denied) {
    return new AgentTurn(
        row.turn(), row.endingSeq(), row.arrivedAt(), row.startedAt(), row.endedAt(),
        row.trajectory(), json, row.outcome(), rounds, ok + fail + denied, ok, fail, denied,
        row.inferenceCalls(), row.inferenceRetries());
  }
```

Imports to add: `java.util.List`, `tools.jackson.databind.json.JsonMapper`.

In `TurnRecorderTest`, add:

```java
  @Test
  void the_row_carries_the_readable_trajectory_of_its_turn() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    AgentTurn row =
        recorder.recordEnding(AGENT, AgentState.idle(Seq.NONE), oneRoundThenAnswer(false), ENDED).orElseThrow();
    assertThat(row.trajectoryJson())
        .isEqualTo(
            "{\"rounds\":[[{\"tool\":\"search\",\"outcome\":\"SUCCESS\"}]],\"outcome\":\"ANSWERED\"}");
  }
```

Use the helper the class already has. It is named `oneRoundThenAnswer` in the original plan, so check the current file and adapt if it was renamed.

- [ ] **Step 2: Run them and watch them fail**

Run: `./mvnw -q -pl :nessy-backend-jdbc,:nessy-engine -am test -Dnessy.excludedGroups=live -Dtest='JdbcAgentTurnsTest,TurnRecorderTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failures, because `AgentTurn` has no `trajectoryJson`.

- [ ] **Step 3: Add the component to `AgentTurn`**

Insert `String trajectoryJson` after `Trajectory trajectory`. In the compact constructor, add:

```java
    Objects.requireNonNull(trajectoryJson, "trajectoryJson must not be null");
    if (trajectoryJson.isBlank()) {
      throw new IllegalArgumentException("trajectoryJson must not be blank");
    }
```

Add a `@param trajectoryJson` line to the javadoc: "the trajectory as JSON (spec §4.6): readable, and one-to-one with the hash under its version".

Then update every `new AgentTurn(` call site:
- `TurnRecorder.summarise` passes `TurnTrajectory.json(trajectory, outcome)` right after `fingerprint`.
- `AgentTurnTest` and `InMemoryAgentTurnsTest` pass any non-blank JSON literal, such as `"{\"rounds\":[],\"outcome\":\"ANSWERED\"}"`. Their counts are not checked against it.
- `JdbcAgentTurns.of` passes the string it reads, as described in Step 4.

- [ ] **Step 4: Schema and JDBC**

In `nessy-schema.sql`, add `trajectory JSONB NOT NULL,` right after `trajectory_hash`. Extend the table's comment with one sentence:

```sql
-- trajectory is the same behaviour as JSON (rounds of {tool, outcome}, then the outcome), stored
-- plain because tool names are not content: what a query reads, where the hash only compares.
```

In `JdbcAgentTurns`:
- **INSERT:** add `trajectory` to the column list after `trajectory_hash`, add `CAST(? AS JSONB)` in the matching value position, and pass `turn.trajectoryJson()` in the matching parameter position.
- **SELECT:** add `trajectory::text AS trajectory` after `trajectory_hash`.
- **Row mapper:** pass `rs.getString("trajectory")` as the 7th constructor argument.

- [ ] **Step 5: Run the tests**

Run: `./mvnw -q -pl :nessy-backend-spi,:nessy-backend-inmemory,:nessy-backend-jdbc,:nessy-engine -am test -Dnessy.excludedGroups=live`
Expected: exit 0. Every `JdbcAgentTurnsTest` case passes against Postgres, including the escaped insert and the containment query.

- [ ] **Step 6: The content-column guard**

chat-web's `ContentColumnsTest` fails on any `jsonb` column it does not know. Add `"nessy_agent_turn.trajectory",` to `NOT_CONTENT`, beside `nessy_agent_turn.trajectory_hash`, with a comment in the style of its neighbours: "tool names and outcome words: the trajectory's structure, not content".

Run: `./mvnw -q -pl :nessy-example-chat-web -am test -Dnessy.excludedGroups=live -Dtest=ContentColumnsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 7: Format and commit**

```bash
./mvnw -q spotless:apply license:format
git add nessy-backend nessy-engine nessy-examples
git commit -m "feat: every turn row carries its readable trajectory as JSONB" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011J7jUvSHRDoVnSiocuAsAo"
```

---

### Task 3: Docs, changelog and the final gate

**Files:**
- Modify: `docs/concepts/trajectories.md` (the column table near line 91, plus a new short section after the starter queries)
- Modify: `CHANGELOG.md` (`## [Unreleased]` → `### Added`)
- Modify: `docs/superpowers/specs/2026-10-07-trajectory-fingerprinting-design.md` (status line only)

**Interfaces:** none.

- [ ] **Step 1: Docs**

In the column table of `docs/concepts/trajectories.md`, add a row after `trajectory_hash`:

```markdown
| `trajectory` | the same behaviour as JSON: rounds of `{tool, outcome}`, then the outcome |
```

After the starter queries, add a section titled `## Reading a trajectory`. It describes what is, not history. It has four parts:
1. The JSON shape with one example, the watchman round of `containers` and `disk_usage`.
2. One sentence: two rows have equal `trajectory` exactly when they have equal `trajectory_hash` under one version.
3. The escape rule in one sentence.
4. The containment query that finds every turn where `prune_images` was denied.

Use the exact SQL from spec §4.6.

- [ ] **Step 2: Changelog**

Under `### Added`, append:

```markdown
- **The readable trajectory.** Each `nessy_agent_turn` row now carries its trajectory as JSONB
  beside the hash: the rounds of tool calls with their outcomes, then how the turn ended. A query
  reads behaviour directly, and `@>` finds turns by what they did. An existing `nessy_agent_turn`
  table must be dropped so the schema recreates it with the new column.
```

- [ ] **Step 3: Spec status**

In the spec, change the amendment status line to:

```
**Amendment, 2026-10-07: the readable trajectory (§4.6, ruling 9). APPROVED by James. BUILT.**
```

- [ ] **Step 4: The full gate**

Run: `./mvnw -q spotless:apply license:format` then `./mvnw -q clean verify -Dnessy.excludedGroups=live` and then `echo $?`
Expected: `0`. Before running, confirm that nothing is running from this checkout's `target/` (`pgrep -f "ChatWebApplication|WatchmanApplication"` prints nothing).

- [ ] **Step 5: Commit**

```bash
git add docs CHANGELOG.md
git commit -m "docs: the readable trajectory" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011J7jUvSHRDoVnSiocuAsAo"
```
