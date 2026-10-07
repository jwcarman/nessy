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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
}
