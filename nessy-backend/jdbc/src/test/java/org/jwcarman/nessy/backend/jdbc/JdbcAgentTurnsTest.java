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
    Instant t =
        Instant.parse("2026-10-07T14:02:01.220Z").plusSeconds(id).truncatedTo(ChronoUnit.MICROS);
    return new AgentTurn(
        new TurnId(id),
        new Seq(id + 13),
        t,
        t.plusMillis(11),
        t.plusSeconds(3),
        new Trajectory((short) 1, "8f".repeat(32)),
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
  void a_recorded_turn_reads_back_exactly() {
    AgentTurn recorded = turn(418, TurnOutcome.ANSWERED);
    turns.append(TYPE, agent, recorded);
    assertThat(turns.of(TYPE, agent)).containsExactly(recorded);
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
