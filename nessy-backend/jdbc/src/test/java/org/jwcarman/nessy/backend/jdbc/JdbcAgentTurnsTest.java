/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessy.backend.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Tag("container")
@DisplayName("Completed turns kept in a database")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JdbcAgentTurnsTest {

  private static final String JSON =
      "{\"rounds\":[[{\"tool\":\"read\",\"outcome\":\"FAILED\"},"
          + "{\"tool\":\"search\",\"outcome\":\"SUCCESS\"},{\"tool\":\"search\",\"outcome\":\"SUCCESS\"}],"
          + "[{\"tool\":\"fetch\",\"outcome\":\"SUCCESS\"}]],\"outcome\":\"ANSWERED\"}";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String DISAGREEMENTS =
      """
      SELECT COUNT(*) FROM nessy_agent_turn t WHERE agent_id = ? AND (
        jsonb_array_length(trajectory->'rounds') <> round_count
        OR trajectory->>'outcome' <> outcome
        OR (SELECT COUNT(*) FROM jsonb_path_query(trajectory, '$.rounds[*][*]')) <> tool_call_count
        OR (SELECT COUNT(*) FROM jsonb_path_query(trajectory, '$.rounds[*][*] ? (@.outcome == "SUCCESS")')) <> tool_success_count
        OR (SELECT COUNT(*) FROM jsonb_path_query(trajectory, '$.rounds[*][*] ? (@.outcome == "FAILED")')) <> tool_failure_count
        OR (SELECT COUNT(*) FROM jsonb_path_query(trajectory, '$.rounds[*][*] ? (@.outcome == "DENIED")')) <> tool_denied_count)
      """;

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
    Instant t =
        Instant.parse("2026-10-07T14:02:01.220Z").plusSeconds(id).truncatedTo(ChronoUnit.MICROS);
    return new AgentTurn(
        new TurnId(id),
        new Seq(id + 13),
        t,
        t.plusMillis(11),
        t.plusSeconds(3),
        new Trajectory((short) 1, "8f".repeat(32)),
        JSON,
        "Q",
        true,
        outcome,
        2,
        4,
        3,
        1,
        0,
        3,
        0);
  }

  @Test
  void a_recorded_turn_reads_back_equal_with_its_json_normalised() {
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

  @Test
  void every_rows_counts_agree_with_its_trajectory_json() {
    String twoCalls =
        "{\"rounds\":[[{\"tool\":\"a\",\"outcome\":\"SUCCESS\"},"
            + "{\"tool\":\"b\",\"outcome\":\"DENIED\"}]],\"outcome\":\"STOPPED\"}";
    turns.append(TYPE, agent, turn(1, TurnOutcome.ANSWERED));
    turns.append(
        TYPE,
        agent,
        withJson(
            turn(2, TurnOutcome.ANSWERED), "{\"rounds\":[],\"outcome\":\"ANSWERED\"}", 0, 0, 0, 0));
    turns.append(TYPE, agent, withJson(turn(3, TurnOutcome.STOPPED), twoCalls, 1, 1, 0, 1));
    assertThat(disagreements()).isZero();

    String oneCall =
        "{\"rounds\":[[{\"tool\":\"a\",\"outcome\":\"SUCCESS\"}]],\"outcome\":\"ANSWERED\"}";
    turns.append(TYPE, agent, withJson(turn(4, TurnOutcome.ANSWERED), oneCall, 1, 2, 0, 0));
    assertThat(disagreements()).isEqualTo(1);
  }

  private long disagreements() {
    return JdbcClient.create(database)
        .sql(DISAGREEMENTS)
        .params(agent.value())
        .query(Long.class)
        .single();
  }

  private static AgentTurn labelled(AgentTurn row, String label) {
    return new AgentTurn(
        row.turn(),
        row.endingSeq(),
        row.arrivedAt(),
        row.startedAt(),
        row.endedAt(),
        row.trajectory(),
        row.trajectoryJson(),
        label,
        row.novel(),
        row.outcome(),
        row.rounds(),
        row.toolCalls(),
        row.toolSuccesses(),
        row.toolFailures(),
        row.toolDenials(),
        row.inferenceCalls(),
        row.inferenceRetries());
  }

  @Test
  void a_label_with_a_replacement_character_round_trips() {
    AgentTurn recorded = labelled(turn(5, TurnOutcome.ANSWERED), "a\uFFFDb");
    turns.append(TYPE, agent, recorded);
    assertThat(turns.of(TYPE, agent))
        .singleElement()
        .extracting(AgentTurn::label)
        .isEqualTo("a\uFFFDb");
  }

  @Test
  void turns_group_by_label() {
    turns.append(TYPE, agent, labelled(turn(1, TurnOutcome.ANSWERED), "x"));
    turns.append(TYPE, agent, labelled(turn(2, TurnOutcome.ANSWERED), "x"));
    turns.append(TYPE, agent, labelled(turn(3, TurnOutcome.ANSWERED), "y"));
    List<String> grouped =
        JdbcClient.create(database)
            .sql(
                "SELECT label || '=' || COUNT(*) FROM nessy_agent_turn WHERE agent_id = ? "
                    + "GROUP BY label ORDER BY label")
            .params(agent.value())
            .query(String.class)
            .list();
    assertThat(grouped).containsExactly("x=2", "y=1");
  }

  /** The same row with a different trajectory and the counts that trajectory implies. */
  private static AgentTurn withJson(
      AgentTurn row, String json, int rounds, int ok, int fail, int denied) {
    return new AgentTurn(
        row.turn(),
        row.endingSeq(),
        row.arrivedAt(),
        row.startedAt(),
        row.endedAt(),
        row.trajectory(),
        json,
        row.label(),
        row.novel(),
        row.outcome(),
        rounds,
        ok + fail + denied,
        ok,
        fail,
        denied,
        row.inferenceCalls(),
        row.inferenceRetries());
  }

  @Test
  void turns_read_back_oldest_first_whatever_order_they_were_written() {
    turns.append(TYPE, agent, turn(30, TurnOutcome.STOPPED));
    turns.append(TYPE, agent, turn(10, TurnOutcome.TRUNCATED));
    assertThat(turns.of(TYPE, agent))
        .extracting(AgentTurn::turn)
        .containsExactly(new TurnId(10), new TurnId(30));
  }

  @Test
  void every_outcome_round_trips() {
    for (TurnOutcome outcome : TurnOutcome.values()) {
      turns.append(TYPE, agent, turn(outcome.ordinal() + 1, outcome));
    }
    assertThat(turns.of(TYPE, agent))
        .extracting(AgentTurn::outcome)
        .containsExactly(TurnOutcome.values());
  }

  @Test
  void a_turn_recorded_twice_is_refused() {
    turns.append(TYPE, agent, turn(1, TurnOutcome.ANSWERED));
    AgentTurn again = turn(1, TurnOutcome.FAILED);
    assertThatThrownBy(() -> turns.append(TYPE, agent, again))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void another_agent_of_the_same_type_has_its_own_turns() {
    turns.append(TYPE, agent, turn(1, TurnOutcome.ANSWERED));
    assertThat(turns.of(TYPE, new AgentId(UUID.randomUUID()))).isEmpty();
  }

  private static AgentType freshType() {
    return new AgentType("t" + UUID.randomUUID().toString().substring(0, 8));
  }

  private static Trajectory path() {
    return new Trajectory((short) 1, "ab".repeat(32));
  }

  private long knownRows(AgentType type) {
    return JdbcClient.create(database)
        .sql("SELECT COUNT(*) FROM nessy_known_trajectory WHERE agent_type = ?")
        .params(type.value())
        .query(Long.class)
        .single();
  }

  private TransactionTemplate transaction() {
    return new TransactionTemplate(new DataSourceTransactionManager(database));
  }

  @Test
  void the_first_sighting_of_a_trajectory_is_novel_and_the_second_is_not() {
    AgentType type = freshType();
    Instant now = Instant.now();
    boolean first = turns.firstSighting(type, "Q", path(), now);
    boolean second = turns.firstSighting(type, "Q", path(), now);
    assertThat(first).isTrue();
    assertThat(second).isFalse();
  }

  @Test
  void the_same_trajectory_under_another_label_type_or_version_is_novel_again() {
    AgentType type = freshType();
    Instant now = Instant.now();
    assertThat(turns.firstSighting(type, "Q", path(), now)).isTrue();
    assertThat(turns.firstSighting(type, "R", path(), now)).isTrue();
    assertThat(turns.firstSighting(freshType(), "Q", path(), now)).isTrue();
    assertThat(turns.firstSighting(type, "Q", new Trajectory((short) 2, path().hash()), now))
        .isTrue();
  }

  @Test
  void a_sighting_keeps_the_first_time_and_a_repeat_does_not_move_it() {
    AgentType type = freshType();
    Instant t = Instant.now().truncatedTo(ChronoUnit.MICROS);
    turns.firstSighting(type, "Q", path(), t);
    turns.firstSighting(type, "Q", path(), t.plusSeconds(60));
    OffsetDateTime firstSeen =
        JdbcClient.create(database)
            .sql("SELECT first_seen FROM nessy_known_trajectory WHERE agent_type = ?")
            .params(type.value())
            .query(OffsetDateTime.class)
            .single();
    assertThat(firstSeen.toInstant()).isEqualTo(t);
  }

  @Test
  void a_row_reads_back_the_novelty_it_was_written_with() {
    AgentTurn repeat = withNovelty(turn(1, TurnOutcome.ANSWERED), false);
    AgentTurn fresh = withNovelty(turn(2, TurnOutcome.ANSWERED), true);
    turns.append(TYPE, agent, repeat);
    turns.append(TYPE, agent, fresh);
    assertThat(turns.of(TYPE, agent)).extracting(AgentTurn::novel).containsExactly(false, true);
  }

  private static AgentTurn withNovelty(AgentTurn row, boolean novel) {
    return new AgentTurn(
        row.turn(),
        row.endingSeq(),
        row.arrivedAt(),
        row.startedAt(),
        row.endedAt(),
        row.trajectory(),
        row.trajectoryJson(),
        row.label(),
        novel,
        row.outcome(),
        row.rounds(),
        row.toolCalls(),
        row.toolSuccesses(),
        row.toolFailures(),
        row.toolDenials(),
        row.inferenceCalls(),
        row.inferenceRetries());
  }

  @Test
  void a_sighting_in_a_rolled_back_transaction_leaves_nothing_and_the_next_is_novel() {
    AgentType type = freshType();
    TransactionTemplate template = transaction();
    Boolean inside =
        template.execute(
            status -> {
              boolean novel = turns.firstSighting(type, "Q", path(), Instant.now());
              status.setRollbackOnly();
              return novel;
            });
    assertThat(inside).isTrue();
    assertThat(knownRows(type)).isZero();
    assertThat(turns.firstSighting(type, "Q", path(), Instant.now())).isTrue();
    assertThat(knownRows(type)).isEqualTo(1);
  }

  @Test
  void two_transactions_sighting_one_new_path_at_once_make_exactly_one_novel() throws Exception {
    AgentType type = freshType();
    assertThat(racingSightings(type, false)).containsExactly(true, false);
    assertThat(knownRows(type)).isEqualTo(1);
  }

  @Test
  void when_the_first_of_two_rolls_back_the_second_is_novel() throws Exception {
    AgentType type = freshType();
    assertThat(racingSightings(type, true)).containsExactly(true, true);
    assertThat(knownRows(type)).isEqualTo(1);
  }

  /**
   * Transaction A sights and holds its transaction open; B then sights the same key and blocks on
   * A's index entry; A is released to commit or roll back. Returns A's and B's answers.
   */
  private List<Boolean> racingSightings(AgentType type, boolean firstRollsBack) throws Exception {
    CountDownLatch sighted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> a =
          pool.submit(
              () ->
                  transaction()
                      .execute(
                          status -> {
                            boolean novel = turns.firstSighting(type, "Q", path(), Instant.now());
                            sighted.countDown();
                            awaitLatch(release);
                            if (firstRollsBack) {
                              status.setRollbackOnly();
                            }
                            return novel;
                          }));
      assertThat(sighted.await(30, TimeUnit.SECONDS)).isTrue();
      CompletableFuture<Integer> backendOfB = new CompletableFuture<>();
      Future<Boolean> b =
          pool.submit(
              () ->
                  transaction()
                      .execute(
                          status -> {
                            backendOfB.complete(
                                JdbcClient.create(database)
                                    .sql("SELECT pg_backend_pid()")
                                    .query(Integer.class)
                                    .single());
                            return turns.firstSighting(type, "Q", path(), Instant.now());
                          }));
      int pid = backendOfB.get(30, TimeUnit.SECONDS);
      await().atMost(Duration.ofSeconds(30)).until(() -> "Lock".equals(waitEventTypeOf(pid)));
      assertThat(b.isDone()).as("B is waiting on A's index entry").isFalse();
      release.countDown();
      return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  private String waitEventTypeOf(int pid) {
    return JdbcClient.create(database)
        .sql("SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?")
        .params(pid)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("latch never released");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /**
   * Pseudo-random 3-byte characters, so Postgres cannot compress the index entry below its limit.
   */
  private static String randomThreeByteLabel(int characters) {
    Random random = new Random(20261008L);
    StringBuilder label = new StringBuilder();
    while (label.length() < characters) {
      label.append((char) (0x0800 + random.nextInt(0xD7FF - 0x0800)));
    }
    return label.toString();
  }

  @Test
  void a_label_of_the_most_characters_the_engine_lets_through_can_key_a_known_trajectory() {
    // The engine cuts a label to 256 characters (InputLabels), counted as code points, as Postgres
    // counts them. 256 pseudo-random 3-byte characters (768 bytes) stand in for the widest label;
    // 256 four-byte characters would reach 1,024 bytes, still well under Postgres's 2,704-byte
    // btree entry limit. Random so the index entry cannot be compressed below it; a repeated
    // character would pass for the wrong reason.
    String label = randomThreeByteLabel(256);
    assertThat(label).hasSize(256);
    assertThat(turns.firstSighting(freshType(), label, path(), Instant.now())).isTrue();
  }

  @Test
  void a_label_beyond_the_column_is_refused_by_the_database() {
    String label = randomThreeByteLabel(257);
    AgentType type = freshType();
    Trajectory trajectory = path();
    Instant now = Instant.now();
    assertThatThrownBy(() -> turns.firstSighting(type, label, trajectory, now))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("value too long");
  }
}
