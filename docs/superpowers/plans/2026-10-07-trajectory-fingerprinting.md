# Trajectory Fingerprinting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every completed turn gets a deterministic, versioned trajectory fingerprint computed in the fold and written to `nessy_agent_turn` in the same transaction as the event that ends the turn.

**Architecture:** The fold (`AgentState`) carries a `TurnTrajectory.State` beside `TurnStats` that accumulates completed rounds of (tool name, outcome). A harness-side `TurnRecorder` folds each decision's events after the append, builds an `AgentTurn` at the turn-ending event, writes it through the new `AgentTurns` backend SPI, and tags the current observation. JDBC and in-memory backends both implement `AgentTurns`.

**Tech Stack:** Java 25, Maven reactor, JUnit 5 + AssertJ (prose test names, no mocking library), Spring `JdbcClient`, Testcontainers Postgres (`@Tag("container")`), Micrometer Observation.

**Spec:** `docs/superpowers/specs/2026-10-07-trajectory-fingerprinting-design.md`

## Global Constraints

- Every source file carries the Apache header (copy it from `nessy-engine/src/main/java/org/jwcarman/nessy/engine/core/TurnTally.java` lines 1-15). Run `./mvnw spotless:apply license:format` before every commit.
- No star imports. No `@SuppressWarnings`.
- Prose test names with `@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)`; no mocking library; one throwing call inside any `assertThatThrownBy` lambda.
- Scoped builds while iterating: `./mvnw -q -pl :<artifactId> -am test` (no `clean`). Full gate once at the end: `./mvnw -q clean verify -Dnessy.excludedGroups=live`. Never run two Maven processes at once.
- Database tests are `@Tag("container")` and run only with `-Dnessy.excludedGroups=live`.
- Tool outcomes are three: SUCCESS, FAILED, DENIED (spec §4.1). Terminal outcomes are five: ANSWERED, TRUNCATED, REFUSED, FAILED, STOPPED (spec §4.2).
- Hash encoding is spec §4.4 exactly: `NESSY_TRAJECTORY`, u16 version 1, u32 round count, per round u32 entry count, per entry u16-length-prefixed UTF-8 name then u8 outcome (1,2,3), then 0xFF then u8 terminal outcome (1..5). SHA-256, stored as 64 lowercase hex chars.
- Nothing new is public in `nessy-api` except `TurnOutcome` and `Trajectory`. The three-way call outcome is engine-private.
- Commit messages end with the two attribution lines from the session (Co-Authored-By and Claude-Session).

## Review Focus

1. A tool name with a non-ASCII character sorts by UTF-8 bytes, not by Java `String.compareTo`. Pinned in Task 4.
2. A turn whose last round is followed immediately by `TurnStopped` (policy stop) still counts that round and gets outcome STOPPED. Pinned in Task 4 and Task 6.
3. `InferenceFailed` increments `TurnStats.failedAttempts`, so `inferenceRetries` must come from the accumulator's own `InferenceAttempted` count, not from `failedAttempts`. Pinned in Task 6.
4. The row's `inferenceCalls` must include the terminal call: the fold drops stats to `Idle` before `TurnTally.after` sees the terminal event, so the recorder applies it itself. Pinned in Task 6.
5. A backend whose `record` throws must roll back the terminal event too, on the queued door. Pinned in Task 7.

---

### Task 1: `TurnOutcome` and `Trajectory` in `nessy-api`

**Files:**
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/TurnOutcome.java`
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/Trajectory.java`
- Test: `nessy-api/src/test/java/org/jwcarman/nessy/api/TrajectoryTest.java`

**Interfaces:**
- Produces: `enum TurnOutcome { ANSWERED, TRUNCATED, REFUSED, FAILED, STOPPED }` each with `byte tag()`; `record Trajectory(short version, String hash)`.

- [ ] **Step 1: Write the failing test**

```java
package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TrajectoryTest {

  private static final String HASH = "a".repeat(64);

  @Test
  void a_trajectory_is_a_version_and_sixty_four_lowercase_hex_characters() {
    Trajectory trajectory = new Trajectory((short) 1, HASH);
    assertThat(trajectory.version()).isEqualTo((short) 1);
    assertThat(trajectory.hash()).isEqualTo(HASH);
  }

  @Test
  void a_hash_that_is_not_sixty_four_lowercase_hex_characters_is_refused() {
    assertThatThrownBy(() -> new Trajectory((short) 1, "A".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_hash_of_the_wrong_length_is_refused() {
    assertThatThrownBy(() -> new Trajectory((short) 1, "ab"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void every_turn_outcome_has_a_distinct_tag() {
    assertThat(TurnOutcome.values()).extracting(TurnOutcome::tag).doesNotHaveDuplicates();
    assertThat(TurnOutcome.ANSWERED.tag()).isEqualTo((byte) 1);
    assertThat(TurnOutcome.STOPPED.tag()).isEqualTo((byte) 5);
  }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -pl :nessy-api test -Dtest=TrajectoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `TurnOutcome` and `Trajectory` do not exist.

- [ ] **Step 3: Write the two types**

`TurnOutcome.java`:

```java
package org.jwcarman.nessy.api;

/**
 * How a turn ended, as the trajectory fingerprint classifies it.
 *
 * <p>Not {@link AskOutcome}: that is what a caller gets back, and this is what the turn did. The
 * two differ where it matters for behaviour: a policy stop and an inference failure both surface
 * to a caller as failed, and are two different ways for a turn to go. A truncated answer is its
 * own class because the caller got a stump and the cause is the output limit, not the provider.
 *
 * <p>The tag is the byte the canonical trajectory encoding writes; it never changes once assigned.
 */
public enum TurnOutcome {
  /** The model answered in full. */
  ANSWERED((byte) 1),
  /** The model answered and was cut off at its output limit. */
  TRUNCATED((byte) 2),
  /** The model refused. */
  REFUSED((byte) 3),
  /** Inference failed, after whatever retries the application asked for. */
  FAILED((byte) 4),
  /** The turn policy stopped the turn. */
  STOPPED((byte) 5);

  private final byte tag;

  TurnOutcome(byte tag) {
    this.tag = tag;
  }

  /** The byte that stands for this outcome in the canonical trajectory encoding. */
  public byte tag() {
    return tag;
  }
}
```

`Trajectory.java`:

```java
package org.jwcarman.nessy.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The behavioural identity of a completed turn: a versioned digest of the rounds of tool calls it
 * made, what each came to, and how the turn ended, with every execution-specific value left out.
 *
 * <p>Two turns with the same trajectory followed the same abstract path: the same tools in the
 * same rounds with the same outcomes, ending the same way. Which customer was looked up, what the
 * tools returned, how long it took and what it cost are not part of it.
 *
 * <p>The digest is meaningful only under its version: a later version may count or encode
 * differently, and a comparison across versions says nothing.
 *
 * @param version the canonicalisation version the hash was computed under
 * @param hash the digest as 64 lowercase hexadecimal characters, the same string the row stores
 *     and the span carries
 */
public record Trajectory(short version, String hash) {

  private static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");

  public Trajectory {
    Objects.requireNonNull(hash, "hash must not be null");
    if (!HEX_64.matcher(hash).matches()) {
      throw new IllegalArgumentException(
          "a trajectory hash is 64 lowercase hex characters, not '" + hash + "'");
    }
  }
}
```

- [ ] **Step 4: Run the test to see it pass**

Run: `./mvnw -q -pl :nessy-api test -Dtest=TrajectoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./mvnw -q spotless:apply license:format
git add nessy-api
git commit -m "feat(api): TurnOutcome and Trajectory, the vocabulary of a turn's behavioural identity"
```

---

### Task 2: `AgentTurn`, `AgentTurns`, the in-memory store, and every backend exposes `turns()`

**Files:**
- Create: `nessy-backend/spi/src/main/java/org/jwcarman/nessy/backend/turn/AgentTurn.java`
- Create: `nessy-backend/spi/src/main/java/org/jwcarman/nessy/backend/turn/AgentTurns.java`
- Modify: `nessy-backend/spi/src/main/java/org/jwcarman/nessy/backend/DirectBackend.java` (add `AgentTurns turns();`)
- Modify: `nessy-backend/spi/src/main/java/org/jwcarman/nessy/backend/QueuedBackend.java` (add `AgentTurns turns();`)
- Create: `nessy-backend/inmemory/src/main/java/org/jwcarman/nessy/backend/inmemory/InMemoryAgentTurns.java`
- Modify: `nessy-backend/inmemory/src/main/java/org/jwcarman/nessy/backend/inmemory/InMemoryDirectBackend.java`, `InMemoryQueuedBackend.java` (field, construction, accessor)
- Modify: `nessy-backend/jdbc/src/main/java/org/jwcarman/nessy/backend/jdbc/JdbcDirectBackend.java`, `JdbcQueuedBackend.java` — add `turns()` returning a `JdbcAgentTurns` (created in Task 3; in THIS task, make them compile by returning `new InMemoryAgentTurns()` is NOT allowed since jdbc does not depend on inmemory. Instead, do Task 3's `JdbcAgentTurns` class in this task as a minimal class whose two methods throw `UnsupportedOperationException("Task 3")`, and Task 3 replaces the bodies).
- Modify (test implementers of the two interfaces, each gains `turns()` delegating or holding an `InMemoryAgentTurns`):
  - `nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/direct/FixedDirectBackend.java` (record: add component `AgentTurns turns`; the 3-arg convenience constructor passes `new InMemoryAgentTurns()`)
  - `nessy-engine/src/test/java/org/jwcarman/nessy/engine/narration/ProbedDirectBackend.java` and `ProbedQueuedBackend.java` (delegate: `return backend.turns();`)
  - `nessy-engine/src/test/java/org/jwcarman/nessy/engine/ParkRefusingBackend.java` (delegate)
  - `nessy-engine/src/test/java/org/jwcarman/nessy/engine/work/AgentStatusDirectTest.java` line 68 record, `StatusStoryReadsTest.java` line 274, `StoryReadBoundTest.java` line 111, `StatusReadAcrossAStepTest.java` line 190, `nessy-engine/src/test/java/org/jwcarman/nessy/engine/story/EventAgentStoriesTest.java` line 462 (records gain a component or delegating classes gain the method; follow the shape each already uses for `chapters()`)
- Test: `nessy-backend/inmemory/src/test/java/org/jwcarman/nessy/backend/inmemory/InMemoryAgentTurnsTest.java`

**Interfaces:**
- Consumes: `TurnOutcome`, `Trajectory` (Task 1).
- Produces:

```java
public record AgentTurn(
    TurnId turn, Seq endingSeq,
    Instant arrivedAt, Instant startedAt, Instant endedAt,
    Trajectory trajectory, TurnOutcome outcome,
    int rounds, int toolCalls, int toolSuccesses, int toolFailures, int toolDenials,
    int inferenceCalls, int inferenceRetries)

public interface AgentTurns {
  void record(AgentType type, AgentId agent, AgentTurn turn);
  List<AgentTurn> of(AgentType type, AgentId agent);
}
```

- [ ] **Step 1: Write the failing in-memory test**

```java
package org.jwcarman.nessy.backend.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Trajectory;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.backend.turn.AgentTurn;
import org.jwcarman.nessy.backend.turn.AgentTurns;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InMemoryAgentTurnsTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final Trajectory TRAJECTORY = new Trajectory((short) 1, "0".repeat(64));

  private static AgentTurn turn(long id) {
    Instant t = Instant.EPOCH.plusSeconds(id);
    return new AgentTurn(
        new TurnId(id), new Seq(id + 5), t, t, t, TRAJECTORY, TurnOutcome.ANSWERED,
        1, 2, 2, 0, 0, 2, 0);
  }

  private final AgentTurns turns = new InMemoryAgentTurns();

  @Test
  void a_recorded_turn_is_read_back_for_its_agent_oldest_first() {
    turns.record(TYPE, AGENT, turn(10));
    turns.record(TYPE, AGENT, turn(20));
    assertThat(turns.of(TYPE, AGENT)).containsExactly(turn(10), turn(20));
  }

  @Test
  void an_agent_with_no_turns_reads_back_empty() {
    assertThat(turns.of(TYPE, AGENT)).isEmpty();
  }

  @Test
  void another_agents_turns_are_not_this_ones() {
    turns.record(TYPE, AGENT, turn(10));
    assertThat(turns.of(TYPE, new AgentId(UUID.randomUUID()))).isEmpty();
  }

  @Test
  void a_turn_recorded_twice_is_refused() {
    turns.record(TYPE, AGENT, turn(10));
    assertThatThrownBy(() -> turns.record(TYPE, AGENT, turn(10)))
        .isInstanceOf(IllegalStateException.class);
  }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -pl :nessy-backend-inmemory -am test -Dtest=InMemoryAgentTurnsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 3: Write the SPI types**

`AgentTurn.java`:

```java
package org.jwcarman.nessy.backend.turn;

import java.time.Instant;
import java.util.Objects;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Trajectory;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnOutcome;

/**
 * One completed turn, summarised: its trajectory, how it ended, and the counts a question about
 * behaviour asks first. A projection of the turn's events, written when the turn ends.
 *
 * <p>{@code turn} is the seq of {@code TurnStarted} and {@code endingSeq} the seq of the event that
 * ended the turn, so the two bound the turn's slice of the event stream, inclusive. Re-folding that
 * slice reproduces the trajectory, which is how a stored hash is audited.
 *
 * @param arrivedAt when the input reached the harness
 * @param startedAt when the turn opened
 * @param endedAt when the ending event was written
 * @param inferenceCalls every model call made, retries included
 * @param inferenceRetries the attempts that failed and were tried again
 */
public record AgentTurn(
    TurnId turn,
    Seq endingSeq,
    Instant arrivedAt,
    Instant startedAt,
    Instant endedAt,
    Trajectory trajectory,
    TurnOutcome outcome,
    int rounds,
    int toolCalls,
    int toolSuccesses,
    int toolFailures,
    int toolDenials,
    int inferenceCalls,
    int inferenceRetries) {

  public AgentTurn {
    Objects.requireNonNull(turn, "turn must not be null");
    Objects.requireNonNull(endingSeq, "endingSeq must not be null");
    Objects.requireNonNull(arrivedAt, "arrivedAt must not be null");
    Objects.requireNonNull(startedAt, "startedAt must not be null");
    Objects.requireNonNull(endedAt, "endedAt must not be null");
    Objects.requireNonNull(trajectory, "trajectory must not be null");
    Objects.requireNonNull(outcome, "outcome must not be null");
    if (toolCalls != toolSuccesses + toolFailures + toolDenials) {
      throw new IllegalArgumentException("every tool call succeeded, failed or was denied");
    }
  }
}
```

`AgentTurns.java`:

```java
package org.jwcarman.nessy.backend.turn;

import java.util.List;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * The completed turns of every agent, one row each, written as each turn ends.
 *
 * <p>A materialised projection of the event stream, not a second source of truth: every row can be
 * rebuilt from the turn's events. It exists so that questions about behaviour across many turns
 * (how many distinct trajectories, which are common, which are new) are a query rather than a
 * fold over every event ever written.
 *
 * <p>{@link #record} is called inside the same unit of work as the append of the turn-ending event,
 * so a committed ending always has its row and a rolled-back one never does.
 */
public interface AgentTurns {

  /**
   * Writes the row for a turn that has just ended.
   *
   * @throws IllegalStateException if this agent already has a row for this turn
   */
  void record(AgentType type, AgentId agent, AgentTurn turn);

  /** This agent's completed turns, oldest first. For tests and audit; analytics use SQL. */
  List<AgentTurn> of(AgentType type, AgentId agent);
}
```

Add to both `DirectBackend` and `QueuedBackend`, after `chapters()`:

```java
  AgentTurns turns();
```

with the import `org.jwcarman.nessy.backend.turn.AgentTurns`. Update each interface's class javadoc list of stores ("events, payloads, locks, chapters and leases") to include turns.

- [ ] **Step 4: Write the in-memory store**

```java
package org.jwcarman.nessy.backend.inmemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.backend.turn.AgentTurn;
import org.jwcarman.nessy.backend.turn.AgentTurns;

/** Completed turns held in this process and nowhere else. */
public final class InMemoryAgentTurns implements AgentTurns {

  private record Key(AgentType type, AgentId agent) {}

  private final Map<Key, List<AgentTurn>> turns = new ConcurrentHashMap<>();

  @Override
  public synchronized void record(AgentType type, AgentId agent, AgentTurn turn) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    Objects.requireNonNull(turn, "turn must not be null");
    List<AgentTurn> rows = turns.computeIfAbsent(new Key(type, agent), _ -> new ArrayList<>());
    if (rows.stream().anyMatch(row -> row.turn().equals(turn.turn()))) {
      throw new IllegalStateException(
          "agent " + agent.value() + " already has a row for turn " + turn.turn().value());
    }
    rows.add(turn);
  }

  @Override
  public synchronized List<AgentTurn> of(AgentType type, AgentId agent) {
    List<AgentTurn> rows = turns.get(new Key(type, agent));
    return rows == null ? List.of() : List.copyOf(rows);
  }
}
```

Wire into `InMemoryDirectBackend` and `InMemoryQueuedBackend`: a `private final AgentTurns turns = new InMemoryAgentTurns();` field and a `public AgentTurns turns() { return turns; }` accessor, matching how `leases` is done.

- [ ] **Step 5: Stub the JDBC side so the reactor compiles**

Create `nessy-backend/jdbc/src/main/java/org/jwcarman/nessy/backend/jdbc/JdbcAgentTurns.java`:

```java
package org.jwcarman.nessy.backend.jdbc;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.backend.turn.AgentTurn;
import org.jwcarman.nessy.backend.turn.AgentTurns;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link AgentTurns} over rows in {@code nessy_agent_turn}. */
public final class JdbcAgentTurns implements AgentTurns {

  private final JdbcClient jdbc;

  public JdbcAgentTurns(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public void record(AgentType type, AgentId agent, AgentTurn turn) {
    throw new UnsupportedOperationException("written in the next task");
  }

  @Override
  public List<AgentTurn> of(AgentType type, AgentId agent) {
    throw new UnsupportedOperationException("written in the next task");
  }
}
```

In `JdbcDirectBackend` and `JdbcQueuedBackend`: field `private final AgentTurns turns;`, construct `this.turns = new JdbcAgentTurns(jdbc);` beside `leases`, accessor `public AgentTurns turns()`.

- [ ] **Step 6: Update the nine test implementers**

For each file listed under Files, add `turns()`. Records that hold stores (`FixedDirectBackend`, the `Backend` records in `AgentStatusDirectTest` and `EventAgentStoriesTest`) gain a component `AgentTurns turns`, and every construction site of that record passes `new InMemoryAgentTurns()`. Delegating wrappers (`Probed*Backend`, `ParkRefusingBackend`, the inner classes in the three `work` tests) add:

```java
  @Override
  public AgentTurns turns() {
    return backend.turns();
  }
```

`FixedDirectBackend` becomes:

```java
record FixedDirectBackend(
    Locks locks,
    AgentEvents events,
    Payloads payloads,
    Chapters chapters,
    Leases leases,
    AgentTurns turns)
    implements DirectBackend {

  FixedDirectBackend(Locks locks, AgentEvents events, Payloads payloads) {
    this(
        locks,
        events,
        payloads,
        new InMemoryChapters(new JacksonCodecFactory(JsonMapper.builder().build())),
        new InMemoryLeases(),
        new InMemoryAgentTurns());
  }
}
```

- [ ] **Step 7: Compile the whole reactor and run the in-memory test**

Run: `./mvnw -q -pl :nessy-engine -am test-compile` then `./mvnw -q -pl :nessy-backend-inmemory -am test -Dtest=InMemoryAgentTurnsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: both exit 0. Also run `./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test-compile` to be sure nothing in Boot implements the interfaces (grep found none, but the compile is the proof).

- [ ] **Step 8: Format and commit**

```bash
./mvnw -q spotless:apply license:format
git add -A nessy-backend nessy-engine/src/test
git commit -m "feat(backend): AgentTurns, the per-turn projection, with an in-memory store and both backends exposing it"
```

---

### Task 3: `nessy_agent_turn` schema and `JdbcAgentTurns`

**Files:**
- Modify: `nessy-backend/jdbc/src/main/resources/nessy-schema.sql` (append after the `nessy_chapter` block)
- Modify: `nessy-backend/jdbc/src/main/java/org/jwcarman/nessy/backend/jdbc/JdbcAgentTurns.java` (replace the stub bodies)
- Test: `nessy-backend/jdbc/src/test/java/org/jwcarman/nessy/backend/jdbc/JdbcAgentTurnsTest.java`

**Interfaces:**
- Consumes: `AgentTurns`, `AgentTurn` (Task 2).
- Produces: a working `JdbcAgentTurns(JdbcClient)`.

- [ ] **Step 1: Write the failing container test**

Model it on `JdbcChaptersTest` (same `PostgreSQLContainer("postgres:18-alpine")` static, `Schemas.initialize`, `@Tag("container")`).

```java
package org.jwcarman.nessy.backend.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Trajectory;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.backend.turn.AgentTurn;
import org.jwcarman.nessy.backend.turn.AgentTurns;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("container")
@DisplayName("Completed turns kept in a database")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JdbcAgentTurnsTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static DataSource database() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    return database;
  }

  private final DataSource database = database();
  private final AgentTurns turns = new JdbcAgentTurns(JdbcClient.create(database));
  private final AgentId agent = new AgentId(UUID.randomUUID());

  private static AgentTurn turn(long id, TurnOutcome outcome) {
    Instant t = Instant.parse("2026-10-07T14:02:01.220Z").plusSeconds(id).truncatedTo(ChronoUnit.MICROS);
    return new AgentTurn(
        new TurnId(id), new Seq(id + 13), t, t.plusMillis(11), t.plusSeconds(3),
        new Trajectory((short) 1, "8f".repeat(32)), outcome, 2, 4, 3, 1, 0, 3, 0);
  }

  @Test
  void a_recorded_turn_reads_back_exactly() {
    AgentTurn recorded = turn(418, TurnOutcome.ANSWERED);
    turns.record(TYPE, agent, recorded);
    assertThat(turns.of(TYPE, agent)).containsExactly(recorded);
  }

  @Test
  void turns_read_back_oldest_first_whatever_order_they_were_written() {
    turns.record(TYPE, agent, turn(30, TurnOutcome.STOPPED));
    turns.record(TYPE, agent, turn(10, TurnOutcome.TRUNCATED));
    assertThat(turns.of(TYPE, agent)).extracting(AgentTurn::turn)
        .containsExactly(new TurnId(10), new TurnId(30));
  }

  @Test
  void every_outcome_round_trips() {
    for (TurnOutcome outcome : TurnOutcome.values()) {
      turns.record(TYPE, agent, turn(outcome.ordinal() + 1, outcome));
    }
    assertThat(turns.of(TYPE, agent)).extracting(AgentTurn::outcome)
        .containsExactly(TurnOutcome.values());
  }

  @Test
  void a_turn_recorded_twice_is_refused() {
    turns.record(TYPE, agent, turn(1, TurnOutcome.ANSWERED));
    AgentTurn again = turn(1, TurnOutcome.FAILED);
    assertThatThrownBy(() -> turns.record(TYPE, agent, again))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void another_agent_of_the_same_type_has_its_own_turns() {
    turns.record(TYPE, agent, turn(1, TurnOutcome.ANSWERED));
    assertThat(turns.of(TYPE, new AgentId(UUID.randomUUID()))).isEmpty();
  }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -pl :nessy-backend-jdbc -am test -Dtest=JdbcAgentTurnsTest -Dnessy.excludedGroups=live -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL with `UnsupportedOperationException` (or a missing-table error once the stub is replaced before the schema).

- [ ] **Step 3: Append the table to the schema**

After the `nessy_chapter` block in `nessy-schema.sql`:

```sql
-- One row per completed turn: its trajectory (the versioned digest of which tools ran in which
-- rounds with which outcomes, and how the turn ended) and the counts a question about behaviour
-- asks first. A projection of nessy_agent_event, written in the same transaction as the event that
-- ended the turn, so a committed ending always has its row. turn_id is the seq of TurnStarted and
-- ending_seq the seq of the ending event: between them, inclusive, is the turn's slice of events,
-- and re-folding that slice reproduces trajectory_hash. The hash is 64 lowercase hex characters,
-- readable in a query and the same string the trace carries.
CREATE TABLE IF NOT EXISTS nessy_agent_turn
(
    agent_type            VARCHAR(64)              NOT NULL,
    agent_id              UUID                     NOT NULL,
    turn_id               BIGINT                   NOT NULL,
    ending_seq            BIGINT                   NOT NULL,
    arrived_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    trajectory_version    SMALLINT                 NOT NULL,
    trajectory_hash       CHAR(64)                 NOT NULL,
    outcome               VARCHAR(16)              NOT NULL,
    round_count           INTEGER                  NOT NULL,
    tool_call_count       INTEGER                  NOT NULL,
    tool_success_count    INTEGER                  NOT NULL,
    tool_failure_count    INTEGER                  NOT NULL,
    tool_denied_count     INTEGER                  NOT NULL,
    inference_call_count  INTEGER                  NOT NULL,
    inference_retry_count INTEGER                  NOT NULL,
    PRIMARY KEY (agent_type, agent_id, turn_id)
);

-- Which turns, of one agent type, followed one trajectory: the question every analysis starts from.
CREATE INDEX IF NOT EXISTS ix_nessy_agent_turn_trajectory
    ON nessy_agent_turn (agent_type, trajectory_version, trajectory_hash);
```

- [ ] **Step 4: Write the JDBC store**

Replace the stub bodies. Pass instants the way `JdbcAgentEvents.append` passes `written_at` (read that method and copy its parameter conversion; read timestamps back with `rs.getObject(col, OffsetDateTime.class).toInstant()` as `JdbcAgentEvents.writtenAt` does). A `DuplicateKeyException` on insert becomes `IllegalStateException`.

```java
  private static final String INSERT =
      """
      INSERT INTO nessy_agent_turn
             (agent_type, agent_id, turn_id, ending_seq, arrived_at, started_at, ended_at,
              trajectory_version, trajectory_hash, outcome, round_count, tool_call_count,
              tool_success_count, tool_failure_count, tool_denied_count,
              inference_call_count, inference_retry_count)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String SELECT =
      """
      SELECT turn_id, ending_seq, arrived_at, started_at, ended_at, trajectory_version,
             trajectory_hash, outcome, round_count, tool_call_count, tool_success_count,
             tool_failure_count, tool_denied_count, inference_call_count, inference_retry_count
        FROM nessy_agent_turn
       WHERE agent_type = ? AND agent_id = ?
       ORDER BY turn_id
      """;

  @Override
  public void record(AgentType type, AgentId agent, AgentTurn turn) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    Objects.requireNonNull(turn, "turn must not be null");
    try {
      jdbc.sql(INSERT)
          .params(
              type.value(), agent.value(), turn.turn().value(), turn.endingSeq().value(),
              timestamp(turn.arrivedAt()), timestamp(turn.startedAt()), timestamp(turn.endedAt()),
              turn.trajectory().version(), turn.trajectory().hash(), turn.outcome().name(),
              turn.rounds(), turn.toolCalls(), turn.toolSuccesses(), turn.toolFailures(),
              turn.toolDenials(), turn.inferenceCalls(), turn.inferenceRetries())
          .update();
    } catch (DuplicateKeyException e) {
      throw new IllegalStateException(
          "agent " + agent.value() + " already has a row for turn " + turn.turn().value(), e);
    }
  }

  @Override
  public List<AgentTurn> of(AgentType type, AgentId agent) {
    return jdbc.sql(SELECT)
        .params(type.value(), agent.value())
        .query(
            (rs, _) ->
                new AgentTurn(
                    new TurnId(rs.getLong("turn_id")),
                    new Seq(rs.getLong("ending_seq")),
                    instant(rs, "arrived_at"),
                    instant(rs, "started_at"),
                    instant(rs, "ended_at"),
                    new Trajectory(rs.getShort("trajectory_version"), rs.getString("trajectory_hash")),
                    TurnOutcome.valueOf(rs.getString("outcome")),
                    rs.getInt("round_count"),
                    rs.getInt("tool_call_count"),
                    rs.getInt("tool_success_count"),
                    rs.getInt("tool_failure_count"),
                    rs.getInt("tool_denied_count"),
                    rs.getInt("inference_call_count"),
                    rs.getInt("inference_retry_count")))
        .list();
  }
```

where `timestamp(Instant)` converts exactly as `JdbcAgentEvents` does for `written_at`, and `instant(rs, col)` is `rs.getObject(col, OffsetDateTime.class).toInstant()`.

- [ ] **Step 5: Run the test to see it pass**

Run: `./mvnw -q -pl :nessy-backend-jdbc -am test -Dtest=JdbcAgentTurnsTest -Dnessy.excludedGroups=live -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. Then the whole module: `./mvnw -q -pl :nessy-backend-jdbc -am test -Dnessy.excludedGroups=live` exit 0 (there may be a schema-listing test that enumerates tables; if it fails because a new table appeared, add `nessy_agent_turn` to its expectation).

- [ ] **Step 6: Format and commit**

```bash
./mvnw -q spotless:apply license:format
git add nessy-backend/jdbc
git commit -m "feat(jdbc): nessy_agent_turn and JdbcAgentTurns"
```

---

### Task 4: `TurnTrajectory`: the accumulator and the canonical fingerprint

**Files:**
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/core/TurnTrajectory.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/core/TurnTrajectoryTest.java`

**Interfaces:**
- Consumes: `TurnOutcome`, `Trajectory` (Task 1); `AgentEvent` arms; `ToolName`.
- Produces (all nested in `TurnTrajectory`, engine-internal):

```java
public final class TurnTrajectory {
  public static final short VERSION = 1;
  public enum CallOutcome { SUCCESS, FAILED, DENIED }           // tag() 1,2,3
  public record Entry(ToolName tool, CallOutcome outcome)
  public record Round(List<Entry> entries)                      // sorted, duplicates kept
  public record State(Instant arrivedAt, List<Round> completed, List<Entry> current, int retries) {
    public static State opened(Instant arrivedAt)
    public State retried()
    public State settled(ToolName tool, CallOutcome outcome)
    public State roundClosed()
    public int toolCalls(); public int count(CallOutcome)
  }
  public static CallOutcome outcomeOf(AgentEvent event)         // ToolSucceeded/ToolFailed/ToolDenied only, else IllegalArgumentException
  public static Optional<TurnOutcome> endingOf(AgentEvent event) // the five, else empty
  public static Trajectory fingerprint(State state, TurnOutcome outcome)
  public static byte[] canonical(State state, TurnOutcome outcome)  // the bytes hashed; for tests
}
```

- [ ] **Step 1: Write the failing tests**

```java
package org.jwcarman.nessy.engine.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Trajectory;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.core.TurnTrajectory.CallOutcome;
import org.jwcarman.nessy.engine.core.TurnTrajectory.State;
import tools.jackson.databind.node.JsonNodeFactory;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TurnTrajectoryTest {

  private static final ToolName A = new ToolName("a");
  private static final ToolName B = new ToolName("b");
  private static final ToolName C = new ToolName("c");
  private static final TurnId TURN = new TurnId(1);
  private static final CallId CALL = new CallId("c1");
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));
  private static final Usage USAGE = Usage.of("a-model", 1, 1);

  private static State opened() {
    return State.opened(Instant.EPOCH);
  }

  private static Trajectory of(State state) {
    return TurnTrajectory.fingerprint(state, TurnOutcome.ANSWERED);
  }

  @Test
  void order_within_a_round_does_not_matter() {
    State ab = opened().settled(A, CallOutcome.SUCCESS).settled(B, CallOutcome.SUCCESS).roundClosed();
    State ba = opened().settled(B, CallOutcome.SUCCESS).settled(A, CallOutcome.SUCCESS).roundClosed();
    assertThat(of(ab)).isEqualTo(of(ba));
  }

  @Test
  void how_many_times_a_tool_ran_in_a_round_does_matter() {
    State aab =
        opened()
            .settled(A, CallOutcome.SUCCESS)
            .settled(A, CallOutcome.SUCCESS)
            .settled(B, CallOutcome.SUCCESS)
            .roundClosed();
    State ab = opened().settled(A, CallOutcome.SUCCESS).settled(B, CallOutcome.SUCCESS).roundClosed();
    assertThat(of(aab)).isNotEqualTo(of(ab));
  }

  @Test
  void round_boundaries_matter() {
    State ab_c =
        opened()
            .settled(A, CallOutcome.SUCCESS)
            .settled(B, CallOutcome.SUCCESS)
            .roundClosed()
            .settled(C, CallOutcome.SUCCESS)
            .roundClosed();
    State a_bc =
        opened()
            .settled(A, CallOutcome.SUCCESS)
            .roundClosed()
            .settled(B, CallOutcome.SUCCESS)
            .settled(C, CallOutcome.SUCCESS)
            .roundClosed();
    assertThat(of(ab_c)).isNotEqualTo(of(a_bc));
  }

  @Test
  void the_same_rounds_ending_five_ways_are_five_trajectories() {
    State state = opened().settled(A, CallOutcome.SUCCESS).roundClosed();
    List<Trajectory> all =
        List.of(
            TurnTrajectory.fingerprint(state, TurnOutcome.ANSWERED),
            TurnTrajectory.fingerprint(state, TurnOutcome.TRUNCATED),
            TurnTrajectory.fingerprint(state, TurnOutcome.REFUSED),
            TurnTrajectory.fingerprint(state, TurnOutcome.FAILED),
            TurnTrajectory.fingerprint(state, TurnOutcome.STOPPED));
    assertThat(all).doesNotHaveDuplicates();
  }

  @Test
  void the_outcome_of_a_call_is_part_of_its_entry() {
    State ok = opened().settled(A, CallOutcome.SUCCESS).roundClosed();
    State failed = opened().settled(A, CallOutcome.FAILED).roundClosed();
    State denied = opened().settled(A, CallOutcome.DENIED).roundClosed();
    assertThat(List.of(of(ok), of(failed), of(denied))).doesNotHaveDuplicates();
  }

  @Test
  void a_retry_changes_the_count_and_not_the_trajectory() {
    State plain = opened().settled(A, CallOutcome.SUCCESS).roundClosed();
    State retried = opened().retried().settled(A, CallOutcome.SUCCESS).roundClosed();
    assertThat(of(retried)).isEqualTo(of(plain));
    assertThat(retried.retries()).isEqualTo(1);
  }

  @Test
  void a_turn_with_no_rounds_has_a_trajectory_of_its_own() {
    assertThat(of(opened())).isNotEqualTo(of(opened().settled(A, CallOutcome.SUCCESS).roundClosed()));
    assertThat(of(opened()).hash()).hasSize(64);
  }

  @Test
  void entries_sort_by_utf8_bytes_not_by_utf16_code_units() {
    // U+FF21 (fullwidth A) is one UTF-16 unit, 0xFF21, which sorts AFTER U+1F600 (two units,
    // 0xD83D 0xDE00) by String.compareTo; by UTF-8 bytes (EF BC A1 vs F0 9F 98 80) it sorts BEFORE.
    ToolName fullwidth = new ToolName("Ａ");
    ToolName emoji = new ToolName("😀");
    State state =
        opened().settled(emoji, CallOutcome.SUCCESS).settled(fullwidth, CallOutcome.SUCCESS).roundClosed();
    byte[] canonical = TurnTrajectory.canonical(state, TurnOutcome.ANSWERED);
    int first = indexOf(canonical, fullwidth.value().getBytes(StandardCharsets.UTF_8));
    int second = indexOf(canonical, emoji.value().getBytes(StandardCharsets.UTF_8));
    assertThat(first).isLessThan(second);
  }

  @Test
  void the_canonical_bytes_are_the_documented_framing() {
    State state = opened().settled(A, CallOutcome.FAILED).roundClosed();
    byte[] canonical = TurnTrajectory.canonical(state, TurnOutcome.STOPPED);
    byte[] expected = {
      'N', 'E', 'S', 'S', 'Y', '_', 'T', 'R', 'A', 'J', 'E', 'C', 'T', 'O', 'R', 'Y',
      0, 1,            // version
      0, 0, 0, 1,      // one round
      0, 0, 0, 1,      // one entry
      0, 1, 'a',       // name "a"
      2,               // FAILED
      (byte) 0xFF,     // terminal marker
      5                // STOPPED
    };
    assertThat(canonical).isEqualTo(expected);
  }

  @Test
  void a_settled_call_outside_a_round_is_counted_only_when_the_round_closes() {
    State open = opened().settled(A, CallOutcome.SUCCESS);
    assertThat(open.completed()).isEmpty();
    assertThat(open.toolCalls()).isEqualTo(1);
    assertThat(open.roundClosed().completed()).hasSize(1);
  }

  @Test
  void closing_an_empty_round_is_refused() {
    State state = opened();
    assertThatThrownBy(state::roundClosed).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void the_counts_add_up() {
    State state =
        opened()
            .settled(A, CallOutcome.SUCCESS)
            .settled(B, CallOutcome.FAILED)
            .settled(C, CallOutcome.DENIED)
            .settled(A, CallOutcome.SUCCESS)
            .roundClosed();
    assertThat(state.toolCalls()).isEqualTo(4);
    assertThat(state.count(CallOutcome.SUCCESS)).isEqualTo(2);
    assertThat(state.count(CallOutcome.FAILED)).isEqualTo(1);
    assertThat(state.count(CallOutcome.DENIED)).isEqualTo(1);
  }

  @Test
  void the_five_ways_a_call_settles_are_three_outcomes() {
    var facts = JsonNodeFactory.instance.objectNode();
    assertThat(TurnTrajectory.outcomeOf(
            new AgentEvent.ToolSucceeded(new Seq(3), TURN, CALL, PayloadRef.of("r"), "r", KEY)))
        .isEqualTo(CallOutcome.SUCCESS);
    assertThat(TurnTrajectory.outcomeOf(
            new AgentEvent.ToolFailed(new Seq(3), TURN, CALL, CallFailure.FAILED, "m", facts, KEY)))
        .isEqualTo(CallOutcome.FAILED);
    assertThat(TurnTrajectory.outcomeOf(
            new AgentEvent.ToolFailed(new Seq(3), TURN, CALL, CallFailure.PAST_DEADLINE, "m", facts, KEY)))
        .isEqualTo(CallOutcome.FAILED);
    assertThat(TurnTrajectory.outcomeOf(
            new AgentEvent.ToolFailed(new Seq(3), TURN, CALL, CallFailure.NOT_AUTHORISED, "m", facts, KEY)))
        .isEqualTo(CallOutcome.DENIED);
    assertThat(TurnTrajectory.outcomeOf(
            new AgentEvent.ToolDenied(new Seq(3), TURN, CALL, "no", Optional.empty(), facts, KEY)))
        .isEqualTo(CallOutcome.DENIED);
  }

  @Test
  void the_four_ending_events_are_five_outcomes_and_nothing_else_ends_a_turn() {
    assertThat(TurnTrajectory.endingOf(
            new AgentEvent.InferenceAnswered(new Seq(2), TURN, PayloadRef.of("a"), false, USAGE, Optional.empty())))
        .contains(TurnOutcome.ANSWERED);
    assertThat(TurnTrajectory.endingOf(
            new AgentEvent.InferenceAnswered(new Seq(2), TURN, PayloadRef.of("a"), true, USAGE, Optional.empty())))
        .contains(TurnOutcome.TRUNCATED);
    assertThat(TurnTrajectory.endingOf(
            new AgentEvent.InferenceRefused(new Seq(2), TURN, "safety", USAGE, Optional.empty())))
        .contains(TurnOutcome.REFUSED);
    assertThat(TurnTrajectory.endingOf(new AgentEvent.TurnStopped(new Seq(2), TURN, "enough")))
        .contains(TurnOutcome.STOPPED);
    assertThat(TurnTrajectory.endingOf(
            new AgentEvent.TurnStarted(new Seq(1), TURN, PayloadRef.of("p"), "Q", Instant.EPOCH, Instant.EPOCH)))
        .isEmpty();
    assertThat(TurnTrajectory.endingOf(new AgentEvent.Terminated(new Seq(9)))).isEmpty();
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }
}
```

For the `InferenceFailed` arm of `endingOf`, add an assertion constructing `new AgentEvent.InferenceFailed(new Seq(2), TURN, <a Failure>, USAGE, Optional.empty())` → `FAILED`; look at `nessy-inference/spi/.../Failure.java` for how to construct a `Failure.Permanent("reason")` (or whichever factory it offers) and use that.

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -pl :nessy-engine -am test -Dtest=TurnTrajectoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 3: Write `TurnTrajectory`**

```java
package org.jwcarman.nessy.engine.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.Trajectory;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.event.AgentEvent;

/**
 * The shape of a turn's behaviour, worked out from what it did: which tools ran in which rounds,
 * what each came to, and how the turn ended, with every execution-specific value left out.
 *
 * <p>The fold keeps a {@link State} in the busy states beside the tally and moves it on as calls
 * settle; a harness hashes it once, at the event that ends the turn, into a {@link Trajectory}.
 * Nothing here is incremental: the state holds every completed round, and a turn has few enough
 * for that to be nothing.
 *
 * <p><b>Version 1 canonical bytes</b>, hashed with SHA-256. Big-endian, unsigned, strings UTF-8
 * with a 16-bit length prefix, so that no two distinct trajectories serialise the same:
 *
 * <pre>
 * "NESSY_TRAJECTORY"  u16 version  u32 rounds
 *   per round: u32 entries, per entry (sorted by name bytes then outcome): u16 len, name, u8 outcome
 * 0xFF  u8 terminal outcome
 * </pre>
 *
 * The bytes up to the marker are a complete encoding of the path alone, so a rounds-only
 * fingerprint, if ever wanted, is a hash of that prefix under the same version.
 *
 * <p>Not public API. {@link Trajectory} is what anyone outside reads; this is how it is made.
 */
public final class TurnTrajectory {

  public static final short VERSION = 1;

  private static final byte[] DOMAIN = "NESSY_TRAJECTORY".getBytes(StandardCharsets.US_ASCII);
  private static final byte TERMINAL_MARKER = (byte) 0xFF;

  private TurnTrajectory() {}

  /**
   * What a settled call came to, as far as the model can tell. Five ways to settle collapse to
   * three because the rule is "keep a distinction only if it changes what the model can reasonably
   * do next": a call that timed out and one that threw both read as "try again or not"; a call a
   * person refused and one whose approval never came both read as "you may not".
   */
  public enum CallOutcome {
    SUCCESS((byte) 1),
    FAILED((byte) 2),
    DENIED((byte) 3);

    private final byte tag;

    CallOutcome(byte tag) {
      this.tag = tag;
    }

    public byte tag() {
      return tag;
    }
  }

  /** One settled call: the tool and what it came to. Compared by name bytes, then outcome. */
  public record Entry(ToolName tool, CallOutcome outcome) implements Comparable<Entry> {

    public Entry {
      Objects.requireNonNull(tool, "tool must not be null");
      Objects.requireNonNull(outcome, "outcome must not be null");
    }

    @Override
    public int compareTo(Entry other) {
      int byName =
          Arrays.compareUnsigned(
              tool.value().getBytes(StandardCharsets.UTF_8),
              other.tool.value().getBytes(StandardCharsets.UTF_8));
      return byName != 0 ? byName : Byte.compare(outcome.tag, other.outcome.tag);
    }
  }

  /** One completed round: every call the model asked for at once, sorted, duplicates kept. */
  public record Round(List<Entry> entries) {
    public Round {
      entries = List.copyOf(entries);
    }
  }

  /**
   * Where a turn's trajectory stands: the rounds done, the round in progress, and how many model
   * attempts failed and were retried (counted here because the tally's failures also count the
   * failure that ends a turn, and a retry is not that).
   */
  public record State(Instant arrivedAt, List<Round> completed, List<Entry> current, int retries) {

    public State {
      Objects.requireNonNull(arrivedAt, "arrivedAt must not be null");
      completed = List.copyOf(completed);
      current = List.copyOf(current);
    }

    public static State opened(Instant arrivedAt) {
      return new State(arrivedAt, List.of(), List.of(), 0);
    }

    public State retried() {
      return new State(arrivedAt, completed, current, retries + 1);
    }

    public State settled(ToolName tool, CallOutcome outcome) {
      List<Entry> next = new ArrayList<>(current);
      next.add(new Entry(tool, outcome));
      return new State(arrivedAt, completed, next, retries);
    }

    /** The last call of the round has settled: sort what it held and keep it. */
    public State roundClosed() {
      if (current.isEmpty()) {
        throw new IllegalStateException("no round is open");
      }
      List<Entry> sorted = new ArrayList<>(current);
      sorted.sort(null);
      List<Round> next = new ArrayList<>(completed);
      next.add(new Round(sorted));
      return new State(arrivedAt, next, List.of(), retries);
    }

    public int toolCalls() {
      return completed.stream().mapToInt(round -> round.entries().size()).sum() + current.size();
    }

    public int count(CallOutcome outcome) {
      return (int)
          (completed.stream().flatMap(round -> round.entries().stream())
                  .filter(entry -> entry.outcome() == outcome)
                  .count()
              + current.stream().filter(entry -> entry.outcome() == outcome).count());
    }
  }

  /** What a settling event says the call came to. */
  public static CallOutcome outcomeOf(AgentEvent event) {
    return switch (event) {
      case AgentEvent.ToolSucceeded _ -> CallOutcome.SUCCESS;
      case AgentEvent.ToolDenied _ -> CallOutcome.DENIED;
      case AgentEvent.ToolFailed failed ->
          failed.kind() == CallFailure.NOT_AUTHORISED ? CallOutcome.DENIED : CallOutcome.FAILED;
      default -> throw new IllegalArgumentException(event.getClass().getSimpleName() + " settles nothing");
    };
  }

  /** How this event ends a turn, or empty because it does not. */
  public static Optional<TurnOutcome> endingOf(AgentEvent event) {
    return switch (event) {
      case AgentEvent.InferenceAnswered answered ->
          Optional.of(answered.truncated() ? TurnOutcome.TRUNCATED : TurnOutcome.ANSWERED);
      case AgentEvent.InferenceRefused _ -> Optional.of(TurnOutcome.REFUSED);
      case AgentEvent.InferenceFailed _ -> Optional.of(TurnOutcome.FAILED);
      case AgentEvent.TurnStopped _ -> Optional.of(TurnOutcome.STOPPED);
      default -> Optional.empty();
    };
  }

  public static Trajectory fingerprint(State state, TurnOutcome outcome) {
    return new Trajectory(VERSION, HexFormat.of().formatHex(sha256(canonical(state, outcome))));
  }

  /** The exact bytes hashed. Exposed so a test can pin the framing. */
  public static byte[] canonical(State state, TurnOutcome outcome) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(outcome, "outcome must not be null");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(DOMAIN);
    u16(out, VERSION);
    u32(out, state.completed().size());
    for (Round round : state.completed()) {
      u32(out, round.entries().size());
      for (Entry entry : round.entries()) {
        byte[] name = entry.tool().value().getBytes(StandardCharsets.UTF_8);
        u16(out, name.length);
        out.writeBytes(name);
        out.write(entry.outcome().tag());
      }
    }
    out.write(TERMINAL_MARKER);
    out.write(outcome.tag());
    return out.toByteArray();
  }

  private static void u16(ByteArrayOutputStream out, int value) {
    if (value < 0 || value > 0xFFFF) {
      throw new IllegalArgumentException("does not fit in 16 bits: " + value);
    }
    out.write((value >>> 8) & 0xFF);
    out.write(value & 0xFF);
  }

  private static void u32(ByteArrayOutputStream out, int value) {
    out.write((value >>> 24) & 0xFF);
    out.write((value >>> 16) & 0xFF);
    out.write((value >>> 8) & 0xFF);
    out.write(value & 0xFF);
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every JVM ships SHA-256", e);
    }
  }
}
```

- [ ] **Step 4: Run the test to see it pass**

Run: `./mvnw -q -pl :nessy-engine -am test -Dtest=TurnTrajectoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./mvnw -q spotless:apply license:format
git add nessy-engine
git commit -m "feat(engine): TurnTrajectory, the canonical encoding and fingerprint of a turn's behaviour"
```

---

### Task 5: The fold carries the trajectory

**Files:**
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/core/AgentState.java` (`Inferring`, `AwaitingActions`)
- Modify: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/core/AgentStateTest.java`, `AgentStateDeferralTest.java` (constructor call sites gain the new last argument)
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/core/AgentStateTrajectoryTest.java`

**Interfaces:**
- Consumes: `TurnTrajectory.State`, `outcomeOf` (Task 4).
- Produces: `Inferring(Seq seq, TurnId turn, TurnStats stats, TurnTrajectory.State trajectory)`, `AwaitingActions(Seq seq, TurnId turn, Seq requestSeq, Map<CallId, OutstandingAction> outstanding, TurnStats stats, TurnTrajectory.State trajectory)`.

- [ ] **Step 1: Write the failing test**

Drive the fold with events only (no commands), as `TurnTallyTest` does, and read the trajectory off the state before the ending event.

```java
package org.jwcarman.nessy.engine.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Trajectory;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.core.TurnTrajectory.CallOutcome;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AgentStateTrajectoryTest {

  private static final TurnId TURN = new TurnId(1);
  private static final ToolName SEARCH = new ToolName("search");
  private static final ToolName READ = new ToolName("read");
  private static final CallId C1 = new CallId("c1");
  private static final CallId C2 = new CallId("c2");
  private static final CallId C3 = new CallId("c3");
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));
  private static final Usage USAGE = Usage.of("a-model", 10, 2);
  private static final Instant ARRIVED = Instant.parse("2026-10-07T14:02:01.220Z");
  private static final Instant STARTED = Instant.parse("2026-10-07T14:02:01.231Z");

  private static ObjectNode none() {
    return JsonNodeFactory.instance.objectNode();
  }

  private static AgentEvent.TurnStarted started() {
    return new AgentEvent.TurnStarted(new Seq(1), TURN, PayloadRef.of("p"), "Q", ARRIVED, STARTED);
  }

  private static AgentEvent.ActionsRequested requested(long seq, CallId... calls) {
    return new AgentEvent.ActionsRequested(
        new Seq(seq), TURN, PayloadRef.of("r"),
        java.util.Arrays.stream(calls)
            .map(c -> new ActionRequest.ToolCall(c, c.equals(C2) ? READ : SEARCH, "does it", KEY))
            .toList(),
        USAGE, Optional.empty());
  }

  private static AgentEvent succeeded(long seq, CallId call) {
    return new AgentEvent.ToolSucceeded(new Seq(seq), TURN, call, PayloadRef.of("ok"), "ok", KEY);
  }

  private static AgentEvent failed(long seq, CallId call) {
    return new AgentEvent.ToolFailed(new Seq(seq), TURN, call, CallFailure.FAILED, "boom", none(), KEY);
  }

  private static TurnTrajectory.State trajectoryOf(AgentState state) {
    return switch (state) {
      case AgentState.Inferring inferring -> inferring.trajectory();
      case AgentState.AwaitingActions awaiting -> awaiting.trajectory();
      default -> throw new AssertionError("not mid-turn: " + state);
    };
  }

  @Test
  void a_turn_opens_with_an_empty_trajectory_that_remembers_when_the_input_arrived() {
    AgentState state = AgentState.idle(Seq.NONE).apply(started());
    TurnTrajectory.State trajectory = trajectoryOf(state);
    assertThat(trajectory.completed()).isEmpty();
    assertThat(trajectory.arrivedAt()).isEqualTo(ARRIVED);
  }

  @Test
  void a_round_closes_when_its_last_call_settles_whatever_order_they_settle_in() {
    AgentState base = AgentState.idle(Seq.NONE).apply(started()).apply(requested(2, C1, C2, C3));
    AgentState oneWay = base.apply(succeeded(3, C1)).apply(failed(4, C2)).apply(succeeded(5, C3));
    AgentState other = base.apply(succeeded(3, C3)).apply(succeeded(4, C1)).apply(failed(5, C2));
    Trajectory a = TurnTrajectory.fingerprint(trajectoryOf(oneWay), TurnOutcome.ANSWERED);
    Trajectory b = TurnTrajectory.fingerprint(trajectoryOf(other), TurnOutcome.ANSWERED);
    assertThat(a).isEqualTo(b);
    assertThat(trajectoryOf(oneWay).completed()).hasSize(1);
    assertThat(trajectoryOf(oneWay).completed().getFirst().entries())
        .containsExactly(
            new TurnTrajectory.Entry(READ, CallOutcome.FAILED),
            new TurnTrajectory.Entry(SEARCH, CallOutcome.SUCCESS),
            new TurnTrajectory.Entry(SEARCH, CallOutcome.SUCCESS));
  }

  @Test
  void a_round_still_open_has_not_been_counted_as_a_round() {
    AgentState state =
        AgentState.idle(Seq.NONE).apply(started()).apply(requested(2, C1, C2)).apply(succeeded(3, C1));
    assertThat(trajectoryOf(state).completed()).isEmpty();
    assertThat(trajectoryOf(state).current()).hasSize(1);
  }

  @Test
  void a_retried_attempt_counts_as_a_retry_and_leaves_the_rounds_alone() {
    AgentState state =
        AgentState.idle(Seq.NONE)
            .apply(started())
            .apply(
                new AgentEvent.InferenceAttempted(
                    new Seq(2), TURN, failure(), USAGE, Optional.empty()));
    assertThat(trajectoryOf(state).retries()).isEqualTo(1);
    assertThat(trajectoryOf(state).completed()).isEmpty();
  }

  @Test
  void deferrals_and_approvals_do_not_touch_the_trajectory() {
    AgentState base = AgentState.idle(Seq.NONE).apply(started()).apply(requested(2, C1));
    AgentState plain = base.apply(succeeded(3, C1));
    AgentState slow =
        base.apply(new AgentEvent.ApprovalDeferred(new Seq(3), TURN, C1, Instant.EPOCH, none(), KEY))
            .apply(new AgentEvent.ToolApproved(new Seq(4), TURN, C1, Optional.empty(), none(), KEY))
            .apply(new AgentEvent.ToolDeferred(new Seq(5), TURN, C1, Instant.EPOCH, KEY))
            .apply(succeeded(6, C1));
    assertThat(trajectoryOf(slow)).isEqualTo(trajectoryOf(plain));
  }

  @Test
  void the_trajectory_survives_a_second_round() {
    AgentState state =
        AgentState.idle(Seq.NONE)
            .apply(started())
            .apply(requested(2, C1))
            .apply(succeeded(3, C1))
            .apply(requested(4, C1))
            .apply(succeeded(5, C1));
    assertThat(trajectoryOf(state).completed()).hasSize(2);
  }

  private static org.jwcarman.nessy.inference.Failure failure() {
    // Use whichever factory Failure offers for a transient failure; see
    // nessy-inference/spi/src/main/java/org/jwcarman/nessy/inference/Failure.java.
    return new org.jwcarman.nessy.inference.Failure.Transient("rate limited");
  }
}
```

(Check `Failure.Transient`'s constructor signature in `nessy-inference/spi` and adjust `failure()` to match; it is a sealed arm carrying a reason.)

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -pl :nessy-engine -am test -Dtest=AgentStateTrajectoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, no `trajectory()` accessor.

- [ ] **Step 3: Add the field to both busy states**

In `AgentState.java`:

- `Inferring` becomes `record Inferring(Seq seq, TurnId turn, TurnStats stats, TurnTrajectory.State trajectory)`.
- `AwaitingActions` becomes `record AwaitingActions(Seq seq, TurnId turn, Seq requestSeq, Map<CallId, OutstandingAction> outstanding, TurnStats stats, TurnTrajectory.State trajectory)`.
- `Idle.accept(TurnStarted)`:
  ```java
  new Inferring(
      started.seq(), started.turn(),
      TurnStats.opened(started.startedAt()),
      TurnTrajectory.State.opened(started.arrivedAt()))
  ```
- `Inferring.accept(InferenceAttempted)`: `new Inferring(attempted.seq(), turn, TurnTally.after(stats, attempted), trajectory.retried())`.
- `Inferring.accept(ActionsRequested)`: `AwaitingActions.opening(requested, TurnTally.after(stats, requested), trajectory)`; `opening` takes and passes the trajectory.
- `AwaitingActions`: `running` and both deferral arms carry `trajectory` unchanged. `discharge` becomes:
  ```java
  private AgentState discharge(Seq at, AgentEvent settling, CallId callId) {
    OutstandingAction call = outstanding.get(callId);
    if (call == null) {
      throw new IllegalArgumentException("no outstanding call " + callId);
    }
    Map<CallId, OutstandingAction> next = new LinkedHashMap<>(outstanding);
    next.remove(callId);
    TurnTrajectory.State settled =
        trajectory.settled(call.toolName(), TurnTrajectory.outcomeOf(settling));
    return next.isEmpty()
        ? new Inferring(at, turn, stats, settled.roundClosed())
        : new AwaitingActions(at, turn, requestSeq, next, stats, settled);
  }
  ```
  and the three `accept` arms pass the event: `discharge(denied.seq(), denied, denied.callId())` etc. Keep the existing comment about the tally travelling with the turn and add one line: the trajectory likewise, and a round closes with its last call.
- Every other `new Inferring(` / `new AwaitingActions(` inside `AgentState.java` (search for them; `continuing`, `approvalDeferred`, `toolDeferred` and the `Inferring` returned by `closing` paths that stay mid-turn) passes `trajectory` through.
- Check the `null` guard: `discharge` previously did not check for a missing call; the fold replay is only ever given events it wrote, so the added guard is a safety net, and the message mirrors `running`'s.

Then fix the two test files' constructor calls: wherever `AgentStateTest` or `AgentStateDeferralTest` writes `new AgentState.Inferring(seq, turn, stats)` add `, TurnTrajectory.State.opened(Instant.EPOCH)`; wherever it writes `new AgentState.AwaitingActions(..., stats)` add the same last argument.

- [ ] **Step 4: Run the new test and the whole core package**

Run: `./mvnw -q -pl :nessy-engine -am test -Dtest='org.jwcarman.nessy.engine.core.*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, including `AgentStateTest`, `AgentStateDeferralTest`, `AgentStateRepeatedCallIdTest`, `AgentStateFactsTest`, `AgentStateManifestTest`.

- [ ] **Step 5: Run the whole engine module**

Run: `./mvnw -q -pl :nessy-engine -am test`
Expected: exit 0. `StoredAgentWork` and both harnesses only pattern-match on the states by type, so they compile unchanged; if anything else constructs a busy state, fix it the same way.

- [ ] **Step 6: Format and commit**

```bash
./mvnw -q spotless:apply license:format
git add nessy-engine
git commit -m "feat(engine): the fold carries each turn's trajectory beside its tally"
```

---

### Task 6: `TurnRecorder`: the row and the span at the terminal fold, on both doors

**Files:**
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/core/TurnRecorder.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/harness/direct/DefaultDirectHarness.java` (`executeStep` ~line 406, `recoverToIdle` ~line 571; a `TurnRecorder` field built in the constructor from `backend.turns()`, `agentType` and `observations`)
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/harness/queued/DefaultQueuedHarness.java` (`apply` ~line 455; a `TurnRecorder` field) and `DefaultQueuedHarnessFactory.java` (constructs it from `backend.turns()`, the type and `observations`, and passes it in; the factory already holds `observations`, line 116)
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/core/TurnRecorderTest.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/direct/DirectHarnessTrajectoryTest.java`

**Interfaces:**
- Consumes: `AgentTurns`, `AgentTurn` (Task 2); `TurnTrajectory` (Task 4); state fields (Task 5).
- Produces:

```java
public final class TurnRecorder {
  public TurnRecorder(AgentType type, AgentTurns turns, ObservationRegistry observations)
  /** Folds events onto before; at a turn-ending event writes the row and tags the current observation. Returns the recorded row, if a turn ended. */
  public Optional<AgentTurn> record(AgentId agent, AgentState before, List<AgentEvent> events, Instant at)
  static AgentTurn summarise(AgentState.Inferring ending, AgentEvent event, TurnOutcome outcome, Instant at)
}
```

- [ ] **Step 1: Write the failing unit test for the recorder**

```java
package org.jwcarman.nessy.engine.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentTurns;
import org.jwcarman.nessy.backend.turn.AgentTurn;
import org.jwcarman.nessy.backend.turn.AgentTurns;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TurnRecorderTest {

  private static final AgentType TYPE = new AgentType("research");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final TurnId TURN = new TurnId(1);
  private static final CallId C1 = new CallId("c1");
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));
  private static final Usage USAGE = Usage.of("a-model", 10, 2);
  private static final Instant ARRIVED = Instant.parse("2026-10-07T14:02:01.220Z");
  private static final Instant STARTED = Instant.parse("2026-10-07T14:02:01.231Z");
  private static final Instant ENDED = Instant.parse("2026-10-07T14:02:04.018Z");

  private final AgentTurns turns = new InMemoryAgentTurns();

  private static List<AgentEvent> oneRoundThenAnswer(boolean withRetry) {
    List<AgentEvent> events = new java.util.ArrayList<>();
    events.add(new AgentEvent.TurnStarted(new Seq(1), TURN, PayloadRef.of("p"), "Q", ARRIVED, STARTED));
    long seq = 2;
    if (withRetry) {
      events.add(new AgentEvent.InferenceAttempted(new Seq(seq++), TURN, new org.jwcarman.nessy.inference.Failure.Transient("busy"), USAGE, Optional.empty()));
    }
    events.add(new AgentEvent.ActionsRequested(new Seq(seq++), TURN, PayloadRef.of("r"),
        List.of(new ActionRequest.ToolCall(C1, new ToolName("search"), "does it", KEY)), USAGE, Optional.empty()));
    events.add(new AgentEvent.ToolSucceeded(new Seq(seq++), TURN, C1, PayloadRef.of("ok"), "ok", KEY));
    events.add(new AgentEvent.InferenceAnswered(new Seq(seq), TURN, PayloadRef.of("a"), false, USAGE, Optional.empty()));
    return events;
  }

  @Test
  void a_turn_that_ends_gets_one_row_bounded_by_its_first_and_last_seq() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    List<AgentEvent> events = oneRoundThenAnswer(false);
    Optional<AgentTurn> recorded = recorder.record(AGENT, AgentState.idle(Seq.NONE), events, ENDED);
    assertThat(recorded).isPresent();
    AgentTurn row = recorded.get();
    assertThat(row.turn()).isEqualTo(TURN);
    assertThat(row.endingSeq()).isEqualTo(new Seq(4));
    assertThat(row.arrivedAt()).isEqualTo(ARRIVED);
    assertThat(row.startedAt()).isEqualTo(STARTED);
    assertThat(row.endedAt()).isEqualTo(ENDED);
    assertThat(row.outcome()).isEqualTo(TurnOutcome.ANSWERED);
    assertThat(row.rounds()).isEqualTo(1);
    assertThat(row.toolCalls()).isEqualTo(1);
    assertThat(row.toolSuccesses()).isEqualTo(1);
    assertThat(row.inferenceCalls()).isEqualTo(2); // the request and the answer
    assertThat(row.inferenceRetries()).isZero();
    assertThat(turns.of(TYPE, AGENT)).containsExactly(row);
  }

  @Test
  void a_retry_is_counted_as_a_call_and_a_retry_and_does_not_change_the_trajectory() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    AgentTurn plain = recorder.record(AGENT, AgentState.idle(Seq.NONE), oneRoundThenAnswer(false), ENDED).orElseThrow();
    AgentTurn retried = recorder.record(new AgentId(UUID.randomUUID()), AgentState.idle(Seq.NONE), oneRoundThenAnswer(true), ENDED).orElseThrow();
    assertThat(retried.trajectory()).isEqualTo(plain.trajectory());
    assertThat(retried.inferenceCalls()).isEqualTo(3);
    assertThat(retried.inferenceRetries()).isEqualTo(1);
  }

  @Test
  void events_that_end_no_turn_record_nothing() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    List<AgentEvent> events = oneRoundThenAnswer(false).subList(0, 3);
    assertThat(recorder.record(AGENT, AgentState.idle(Seq.NONE), events, ENDED)).isEmpty();
    assertThat(turns.of(TYPE, AGENT)).isEmpty();
  }

  @Test
  void a_policy_stop_after_the_last_call_counts_the_round_and_ends_stopped() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    List<AgentEvent> events = new java.util.ArrayList<>(oneRoundThenAnswer(false).subList(0, 3));
    events.add(new AgentEvent.TurnStopped(new Seq(4), TURN, "enough"));
    AgentTurn row = recorder.record(AGENT, AgentState.idle(Seq.NONE), events, ENDED).orElseThrow();
    assertThat(row.outcome()).isEqualTo(TurnOutcome.STOPPED);
    assertThat(row.rounds()).isEqualTo(1);
    assertThat(row.inferenceCalls()).isEqualTo(1);
  }

  @Test
  void a_failed_turn_counts_the_failing_call_and_no_retry() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.NOOP);
    List<AgentEvent> events = new java.util.ArrayList<>(oneRoundThenAnswer(false).subList(0, 3));
    events.add(new AgentEvent.InferenceFailed(new Seq(4), TURN, new org.jwcarman.nessy.inference.Failure.Permanent("no"), USAGE, Optional.empty()));
    AgentTurn row = recorder.record(AGENT, AgentState.idle(Seq.NONE), events, ENDED).orElseThrow();
    assertThat(row.outcome()).isEqualTo(TurnOutcome.FAILED);
    assertThat(row.inferenceCalls()).isEqualTo(2);
    assertThat(row.inferenceRetries()).isZero();
  }

  @Test
  void the_live_fold_and_a_replay_of_the_same_events_agree() {
    List<AgentEvent> events = oneRoundThenAnswer(true);
    TurnRecorder live = new TurnRecorder(TYPE, new InMemoryAgentTurns(), ObservationRegistry.NOOP);
    // Live: the events arrive in two decisions, each folded onto the state the last left behind.
    AgentState after = AgentState.idle(Seq.NONE).applyAll(events.subList(0, 3));
    AgentTurn liveRow = live.record(AGENT, after, events.subList(3, events.size()), ENDED).orElseThrow();
    // Replay: the whole slice from idle, as reconstitute would fold it.
    TurnRecorder replay = new TurnRecorder(TYPE, new InMemoryAgentTurns(), ObservationRegistry.NOOP);
    AgentTurn replayRow = replay.record(AGENT, AgentState.idle(Seq.NONE), events, ENDED).orElseThrow();
    assertThat(replayRow).isEqualTo(liveRow);
  }

  @Test
  void the_current_observation_is_tagged_with_the_trajectory() {
    List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
    ObservationRegistry registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
      @Override
      public boolean supportsContext(Observation.Context context) { return true; }
      @Override
      public void onStop(Observation.Context context) { stopped.add(context); }
    });
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, registry);
    AgentTurn row =
        Observation.createNotStarted("invoke_agent", registry)
            .observe(() -> recorder.record(AGENT, AgentState.idle(Seq.NONE), oneRoundThenAnswer(false), ENDED).orElseThrow());
    assertThat(stopped).hasSize(1);
    Observation.Context context = stopped.getFirst();
    assertThat(context.getHighCardinalityKeyValue("nessy.trajectory.hash").getValue()).isEqualTo(row.trajectory().hash());
    assertThat(context.getHighCardinalityKeyValue("nessy.trajectory.version").getValue()).isEqualTo("1");
    assertThat(context.getHighCardinalityKeyValue("nessy.turn.outcome").getValue()).isEqualTo("ANSWERED");
    assertThat(context.getHighCardinalityKeyValue("nessy.turn.rounds").getValue()).isEqualTo("1");
    assertThat(context.getHighCardinalityKeyValue("nessy.turn.tool_calls").getValue()).isEqualTo("1");
    assertThat(context.getHighCardinalityKeyValue("nessy.turn.tool_failures").getValue()).isEqualTo("0");
    assertThat(context.getHighCardinalityKeyValue("nessy.turn.tool_denials").getValue()).isEqualTo("0");
  }

  @Test
  void with_no_observation_in_force_nothing_is_tagged_and_the_row_is_still_written() {
    TurnRecorder recorder = new TurnRecorder(TYPE, turns, ObservationRegistry.create());
    assertThat(recorder.record(AGENT, AgentState.idle(Seq.NONE), oneRoundThenAnswer(false), ENDED)).isPresent();
  }
}
```

(Adjust the two `Failure` constructors to the arms' real signatures in `nessy-inference/spi`.) Note `nessy-engine` already depends on `nessy-backend-inmemory` in test scope (its tests use `InMemoryChapters`); confirm in `nessy-engine/pom.xml` and add the test-scoped dependency if it is missing.

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q -pl :nessy-engine -am test -Dtest=TurnRecorderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 3: Write `TurnRecorder`**

```java
package org.jwcarman.nessy.engine.core;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Trajectory;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.api.TurnStats;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.turn.AgentTurn;
import org.jwcarman.nessy.backend.turn.AgentTurns;
import org.jwcarman.nessy.engine.core.TurnTrajectory.CallOutcome;

/**
 * Writes a turn's row the moment the turn ends, from the state the fold held just before.
 *
 * <p>Every event that ends a turn is accepted by {@link AgentState.Inferring} and leaves {@link
 * AgentState.Idle} behind, which carries nothing. So the summary is taken from the inferring state
 * the ending event is applied to, plus the event itself, and that is the only moment it can be.
 * The harness calls this after the append and inside the same locked transaction, so the row and
 * the ending commit together or not at all.
 *
 * <p><b>The tally is moved on by the ending event here.</b> The fold's own transition to idle does
 * not count the final call (idle has no tally to count it into), and a row that said a turn made
 * one call fewer than it did would be wrong for every turn. The retry count comes from the
 * trajectory state rather than from {@link TurnStats#failedAttempts()}, because that figure also
 * counts the failure that ends a turn, and an ending is not a retry.
 */
public final class TurnRecorder {

  static final String HASH = "nessy.trajectory.hash";
  static final String VERSION = "nessy.trajectory.version";
  static final String OUTCOME = "nessy.turn.outcome";
  static final String ROUNDS = "nessy.turn.rounds";
  static final String TOOL_CALLS = "nessy.turn.tool_calls";
  static final String TOOL_FAILURES = "nessy.turn.tool_failures";
  static final String TOOL_DENIALS = "nessy.turn.tool_denials";

  private final AgentType type;
  private final AgentTurns turns;
  private final ObservationRegistry observations;

  public TurnRecorder(AgentType type, AgentTurns turns, ObservationRegistry observations) {
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.turns = Objects.requireNonNull(turns, "turns must not be null");
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
  }

  /**
   * Folds {@code events} onto {@code before} one at a time and, at the one that ends a turn,
   * writes that turn's row and tags whatever observation is in force.
   *
   * @param at when the events were written, which is when the turn ended
   * @return the row, if a turn ended in these events
   */
  public Optional<AgentTurn> record(
      AgentId agent, AgentState before, List<AgentEvent> events, Instant at) {
    AgentState state = before;
    Optional<AgentTurn> recorded = Optional.empty();
    for (AgentEvent event : events) {
      Optional<TurnOutcome> ending = TurnTrajectory.endingOf(event);
      if (ending.isPresent() && state instanceof AgentState.Inferring inferring) {
        AgentTurn row = summarise(inferring, event, ending.get(), at);
        turns.record(type, agent, row);
        tag(row);
        recorded = Optional.of(row);
      }
      state = state.apply(event);
    }
    return recorded;
  }

  static AgentTurn summarise(
      AgentState.Inferring ending, AgentEvent event, TurnOutcome outcome, Instant at) {
    TurnTrajectory.State trajectory = ending.trajectory();
    TurnStats stats = TurnTally.after(ending.stats(), event);
    Trajectory fingerprint = TurnTrajectory.fingerprint(trajectory, outcome);
    return new AgentTurn(
        ending.turn(),
        event.seq(),
        trajectory.arrivedAt(),
        stats.startedAt(),
        at,
        fingerprint,
        outcome,
        trajectory.completed().size(),
        trajectory.toolCalls(),
        trajectory.count(CallOutcome.SUCCESS),
        trajectory.count(CallOutcome.FAILED),
        trajectory.count(CallOutcome.DENIED),
        stats.modelCalls(),
        trajectory.retries());
  }

  /**
   * High-cardinality only: a hash is one value per trajectory, and none of these may become a
   * dimension of the duration timer the direct door's observation also drives.
   */
  private void tag(AgentTurn row) {
    Observation current = observations.getCurrentObservation();
    if (current == null) {
      return;
    }
    current
        .highCardinalityKeyValue(HASH, row.trajectory().hash())
        .highCardinalityKeyValue(VERSION, Short.toString(row.trajectory().version()))
        .highCardinalityKeyValue(OUTCOME, row.outcome().name())
        .highCardinalityKeyValue(ROUNDS, Integer.toString(row.rounds()))
        .highCardinalityKeyValue(TOOL_CALLS, Integer.toString(row.toolCalls()))
        .highCardinalityKeyValue(TOOL_FAILURES, Integer.toString(row.toolFailures()))
        .highCardinalityKeyValue(TOOL_DENIALS, Integer.toString(row.toolDenials()));
  }
}
```

- [ ] **Step 4: Run the recorder test**

Run: `./mvnw -q -pl :nessy-engine -am test -Dtest=TurnRecorderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. If `the_current_observation_is_tagged_with_the_trajectory` fails because key-values added after start are not on the stopped context, STOP and report: the spec §7 flagged this as the thing to verify, and the fix is to tag the context (`current.getContext().addHighCardinalityKeyValue(...)`) instead.

- [ ] **Step 5: Wire the direct door**

In `DefaultDirectHarness`:
- Field `private final TurnRecorder turnRecorder;` set in the constructor: `this.turnRecorder = new TurnRecorder(agentType, backend.turns(), observations);` (after `backend`, `agentType` and `observations` are assigned).
- `executeStep`: after `backend.events().append(agentType, agent, decision.events(), state.seq(), at);` add `turnRecorder.record(agent, state, decision.events(), at);`.
- `recoverToIdle`: after `backend.events().append(agentType, agent, decision.events(), current.seq(), at);` add `turnRecorder.record(agent, current, decision.events(), at);`.
- Leave `terminate` and `beginTurn` alone and add a one-line comment at each: no turn ends here.

- [ ] **Step 6: Wire the queued door**

In `DefaultQueuedHarnessFactory`, where `DefaultQueuedHarness` is constructed, build `new TurnRecorder(type, backend.turns(), observations)` and pass it as a new constructor argument. In `DefaultQueuedHarness`: field `private final TurnRecorder turnRecorder;`, constructor parameter, and in `apply` after `backend.events().append(agentType, agentId, advance.events(), state.seq(), at);` add `turnRecorder.record(agentId, state, advance.events(), at);`. The deferral path (line ~343) writes only deferral events; add a one-line comment there: no turn ends here.

Search for every `new DefaultQueuedHarness(` in main and test sources and pass a recorder (tests can pass `new TurnRecorder(type, new InMemoryAgentTurns(), ObservationRegistry.NOOP)`).

- [ ] **Step 7: Write the failing direct-door test**

Model the harness construction on `DefaultDirectHarnessTest` (its `Scripted` provider, `factoryFor`, `harness(...)` helpers; copy the minimum needed into this test rather than making that test's helpers public). Keep an `InMemoryAgentTurns` the test can read by building the `FixedDirectBackend` with it explicitly.

```java
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DirectHarnessTrajectoryTest {

  // ... Scripted provider, Lookup tool, InMemoryLocks/InMemoryAgentEvents/InMemoryPayloads,
  //     an InMemoryAgentTurns named `turns`, and a harness(...) helper as in DefaultDirectHarnessTest,
  //     with the backend built as new FixedDirectBackend(locks, events, payloads, chapters, leases, turns).

  @Test
  void a_turn_through_the_direct_door_leaves_one_row_whose_bounds_are_its_events() {
    // provider scripted: Actions(one lookup call) then Answer("found it")
    AgentId agent = new AgentId(UUID.randomUUID());
    harness.ask(agent, "look up 7");
    List<AgentTurn> rows = turns.of(TYPE, agent);
    assertThat(rows).hasSize(1);
    AgentTurn row = rows.getFirst();
    List<AgentEvent> story = events.readAll(TYPE, agent);
    assertThat(story.getFirst()).isInstanceOf(AgentEvent.TurnStarted.class);
    assertThat(row.turn().value()).isEqualTo(story.getFirst().seq().value());
    assertThat(row.endingSeq()).isEqualTo(story.getLast().seq());
    assertThat(story).allSatisfy(e -> assertThat(e.seq().value()).isBetween(row.turn().value(), row.endingSeq().value()));
    assertThat(row.outcome()).isEqualTo(TurnOutcome.ANSWERED);
    assertThat(row.rounds()).isEqualTo(1);
    assertThat(row.toolCalls()).isEqualTo(1);
  }

  @Test
  void two_asks_with_different_inputs_and_the_same_path_share_a_trajectory() {
    // provider scripted twice: Actions(lookup) Answer, Actions(lookup) Answer
    AgentId agent = new AgentId(UUID.randomUUID());
    harness.ask(agent, "look up Bob");
    harness.ask(agent, "look up Bill");
    List<AgentTurn> rows = turns.of(TYPE, agent);
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).trajectory()).isEqualTo(rows.get(1).trajectory());
    assertThat(rows.get(0).turn()).isLessThan(rows.get(1).turn());
  }

  @Test
  void the_invoke_agent_span_carries_the_trajectory() {
    // ObservationRegistry.create() with an onStop-collecting handler, as DirectHarnessObservabilityTest does;
    // pass it to the factory (see how that test hands `observations` to DefaultDirectHarnessFactory).
    // ask once; find the stopped context named ObservedInferenceProvider.DURATION with
    // gen_ai.operation.name == invoke_agent; assert its high-cardinality "nessy.trajectory.hash"
    // equals turns.of(TYPE, agent).getFirst().trajectory().hash().
  }

  @Test
  void a_turn_the_policy_stops_leaves_a_row_that_says_stopped() {
    // factory with a TurnPolicy that returns FailTurn("enough") on the first decide(...)
    // (see TurnPolicy in nessy-api and how DefaultDirectHarnessFactory takes a policy; grep
    // DefaultDirectHarnessTest for "FailTurn" or "turnPolicy" and copy that setup).
    // provider scripted: Actions(lookup); the stop lands after the call settles.
    // assert turns.of(...) has one row with outcome STOPPED and rounds == 1.
  }
}
```

Write the two commented tests in full: everything they need is in `DefaultDirectHarnessTest` and `DirectHarnessObservabilityTest`; read those files and copy the construction code.

- [ ] **Step 8: Run the direct-door test and the whole engine module**

Run: `./mvnw -q -pl :nessy-engine -am test -Dtest=DirectHarnessTrajectoryTest -Dsurefire.failIfNoSpecifiedTests=false`, then `./mvnw -q -pl :nessy-engine -am test`
Expected: both exit 0.

- [ ] **Step 9: Format and commit**

```bash
./mvnw -q spotless:apply license:format
git add nessy-engine
git commit -m "feat(engine): TurnRecorder writes each turn's row and tags its span at the ending fold, on both doors"
```

---

### Task 7: The queued door against a database: atomicity, recovery, and the turn interval

**Files:**
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/queued/QueuedHarnessTrajectoryTest.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/direct/DirectHarnessRecoveryTrajectoryTest.java`

**Interfaces:**
- Consumes: everything above; `EngineFixture` (`nessy-engine/src/test/java/org/jwcarman/nessy/engine/EngineFixture.java`: Postgres container, `backend()`, `harnesses()`, `story(type, agent)`), `StatusReadAcrossAStepTest`'s pattern of wrapping a `QueuedBackend`.

- [ ] **Step 1: Write the queued-door container tests**

`@Tag("container")`. Use `EngineFixture` with a scripted provider (see how `QueuedHarnessAcceptanceTest` or `QueuedHarnessChaptersTest` scripts one and waits for a turn to finish; copy that waiting approach, never a sleep).

```java
@Tag("container")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class QueuedHarnessTrajectoryTest {

  @Test
  void a_told_turn_leaves_one_row_bounded_by_its_events() {
    // tell once; wait for the turn to complete; read fixture.backend().turns().of(type, agent)
    // assert one row; turn == first TurnStarted seq; endingSeq == last event seq; every event's
    // seq lies in [turn, endingSeq]; outcome ANSWERED.
  }

  @Test
  void the_rows_counts_agree_with_the_tally_of_the_same_turn() {
    // after the turn: TurnStats stats = TurnTally.of(fixture.story(type, agent), row.turn());
    // assert row.inferenceCalls() == stats.modelCalls() and row.toolCalls() == stats.toolCalls().
  }

  @Test
  void a_row_that_cannot_be_written_rolls_the_ending_event_back_too() {
    // Build the harness over a QueuedBackend wrapper (pattern: StatusReadAcrossAStepTest's inner
    // class, or ParkRefusingBackend) whose turns() returns an AgentTurns whose record(...) throws
    // IllegalStateException("refused"). EngineFixture has constructors that take a backend wrapper
    // if one of the work tests uses one; otherwise construct DefaultQueuedHarnessFactory over the
    // wrapped JdbcQueuedBackend directly the way EngineFixture does internally.
    // tell once; wait until the dispatcher has given up on the inference effect (the failing
    // record throws inside the locked step; the effect attempt fails; see how EffectDispatcher
    // reports an attempt that threw and what the test can wait on).
    // assert: fixture story has NO InferenceAnswered for that turn, and turns().of(...) is empty.
  }
}
```

Write all three in full. If the third proves impossible to drive deterministically through the queued door, write it instead as a direct-door test over `JdbcDirectBackend` wrapped the same way (the direct door's `executeStep` throws straight back to the caller, which is easy to assert with `assertThatThrownBy` around `ask`, followed by reading the story and the rows), and say so in the task report.

- [ ] **Step 2: Write the direct-door recovery test**

`recoverToIdle` ends a turn when an earlier ask left one open and overdue. `DefaultDirectHarnessTest` already has tests that drive this path (grep it for `overdue`, `recover`, `Busy`); copy the setup that leaves a turn open past its deadline, then `ask` again and assert `turns.of(TYPE, agent)` has a row for the abandoned turn whose outcome is `FAILED` or `STOPPED` (whichever the discharge produces; assert the actual arm and say in the report which it was and why).

- [ ] **Step 3: Run them**

Run: `./mvnw -q -pl :nessy-engine -am test -Dnessy.excludedGroups=live -Dtest='QueuedHarnessTrajectoryTest,DirectHarnessRecoveryTrajectoryTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 4: Format and commit**

```bash
./mvnw -q spotless:apply license:format
git add nessy-engine
git commit -m "test(engine): turn rows commit with their ending event, on both doors and through recovery"
```

---

### Task 8: Documentation and changelog

**Files:**
- Create: `docs/concepts/trajectories.md`
- Modify: `mkdocs.yml` (add `- Trajectories: concepts/trajectories.md` after the `Outcomes` line, ~line 90)
- Modify: `docs/concepts/storage.md` (wherever the tables are listed, add `nessy_agent_turn` in one sentence: one row per completed turn, its trajectory and counts, written with the ending event)
- Modify: `CHANGELOG.md` under `## [Unreleased]` / `### Added`

**Interfaces:** none.

- [ ] **Step 1: Write `docs/concepts/trajectories.md`**

Describe what is, not history (no "we decided", no roads not taken). Prose, in the voice of `docs/concepts/turns.md`. Cover, in this order, about 120 lines:

1. What a trajectory is: the behavioural equivalence class of a completed turn. The Bob/Bill example: same tool, same outcome, same ending, same fingerprint.
2. What is in it: ordered rounds; each round an unordered multiset of (tool name, outcome); the ending. Order within a round does not matter, count does, boundaries do. Show the three-way tool outcome table from the spec §4.1 and the five endings from §4.2, including that a truncated answer is its own class and why.
3. What is not in it: inputs, arguments, results, timing, usage, ids, provider, model, retries, deferrals, approvals.
4. Where it is computed: in the fold, beside the tally; the hash once at the ending event; replay gives the same answer.
5. The row: `nessy_agent_turn`, its columns in a table with one line each, that `turn_id` and `ending_seq` bound the turn's events, that it commits with the ending event, that it is a projection and can be rebuilt.
6. The hash: version 1 is SHA-256 over the framing in spec §4.4 (reproduce the framing block), stored as 64 lowercase hex characters; that it is versioned and a comparison across versions means nothing.
7. On the trace: the seven attributes, on the `invoke_agent` span on the direct door and the ending inference effect's span on the queued door; never a metric tag, and why.
8. Three starter queries, in SQL: distinct trajectories per agent type; the top five trajectories by share; turns whose trajectory first appeared in the last day.

- [ ] **Step 2: Add the nav entry and the storage mention**

- [ ] **Step 3: Add the changelog entry**

Under `### Added`:

```markdown
- **Trajectory fingerprints.** Every completed turn gets a deterministic, versioned digest of its
  behaviour: which tools ran in which rounds, what each came to, and how the turn ended, with
  inputs, arguments, results, timing and cost left out. It is computed in the fold and written to
  the new `nessy_agent_turn` table in the same transaction as the turn-ending event, with the
  turn's round, tool and inference counts beside it; the same values are tagged on the turn's span.
  `TurnOutcome` and `Trajectory` are the new public types; `AgentTurns` is the new backend store.
  See `docs/concepts/trajectories.md`.
```

- [ ] **Step 4: Build the docs if the repo has a docs check**

Run: `grep -n "mkdocs" .github/workflows/*.yml | head` and if a docs build step exists, run the same command locally (`mkdocs build --strict` if available; skip and say so if `mkdocs` is not installed).

- [ ] **Step 5: Commit**

```bash
git add docs mkdocs.yml CHANGELOG.md
git commit -m "docs: trajectories, the behavioural identity of a completed turn"
```

---

### Task 9: The final gate

**Files:** none new.

- [ ] **Step 1: Headers and formatting**

Run: `./mvnw -q spotless:apply license:format && git status --short`
Expected: nothing left unformatted; if `license:format` touched files, commit them alone as `chore: license headers`.

- [ ] **Step 2: The full gate, containers included**

Run: `pgrep -f "nessy.*jar" ; ./mvnw -q clean verify -Dnessy.excludedGroups=live` and check the exit code (`echo $?`), never the log text.
Expected: exit 0. If it fails, fix, commit, and rerun the gate once more.

- [ ] **Step 3: CI's own check**

Run: `./mvnw -q spotless:check license:check`
Expected: exit 0.

---

### Task 10: Live soak against local inference (done by the main session, not a subagent)

**Files:** none in the repo. Findings go in the summary to James and in memory.

- [ ] **Step 1: Preconditions**

LM Studio must be serving on its usual local port with the models the examples are configured for (see `local-models-for-nessy` memory and each example's `application.yaml`). Nothing else may be building: `pgrep -f "mvnw|maven"` must be empty before any jar is built, and no example may be running while a build runs.

- [ ] **Step 2: Build the example jars**

Run: `./mvnw -q -pl :nessy-example-chat-web,:nessy-example-watchman -am clean install -DskipTests` (artifactIds as the reactor names them; confirm with `grep -m1 artifactId nessy-examples/*/pom.xml`). A `-pl install` without `clean` skips repackaging, so `clean` is required and the jar's mtime is the proof.

- [ ] **Step 3: chat-web**

Start it with its own compose and a fresh database (the dev database is disposable). Drive at least six chats through the browser: two that need no tool, two that call the same tool with different inputs, one that makes the model call two tools in one round, one that repeats a tool across rounds. Then query:

```sql
SELECT trajectory_hash, outcome, round_count, tool_call_count, COUNT(*) AS turns
  FROM nessy_agent_turn WHERE agent_type = '<chat-web type>'
 GROUP BY 1,2,3,4 ORDER BY turns DESC;
```

Expect the two same-tool chats to share a hash and the no-tool chats to share another.

- [ ] **Step 4: watchman**

Start watchman the same way. Let it run long enough for its routine turns to repeat (its turns mostly look alike, which is the point: a long tail of a few trajectories). When its approver asks, answer: approve most, deny at least two, and let one time out if the approver has a deadline. Then query the same grouping plus:

```sql
SELECT trajectory_hash, COUNT(*) FROM nessy_agent_turn WHERE tool_denied_count > 0 GROUP BY 1;
SELECT COUNT(DISTINCT trajectory_hash), COUNT(*) FROM nessy_agent_turn WHERE agent_type = '<watchman type>';
```

Expect the denied turns to form their own trajectory, and a small distinct-count against a larger turn count.

- [ ] **Step 5: Stop both, record what was seen**

Stop the apps and their compose stacks. Put the distributions (as small tables) in the summary and a `project` memory noting what the soak showed and anything that looked wrong.
