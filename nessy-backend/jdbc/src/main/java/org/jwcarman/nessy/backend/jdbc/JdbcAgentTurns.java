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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Trajectory;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.backend.turn.AgentTurn;
import org.jwcarman.nessy.backend.turn.AgentTurns;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link AgentTurns} over rows in {@code nessy_agent_turn}. It opens no transaction of its own: the
 * caller's ambient one carries the insert, so a turn's row commits with the event that ended it.
 */
public final class JdbcAgentTurns implements AgentTurns {

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

  private final JdbcClient jdbc;

  public JdbcAgentTurns(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public void record(AgentType type, AgentId agent, AgentTurn turn) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    Objects.requireNonNull(turn, "turn must not be null");
    try {
      jdbc.sql(INSERT)
          .params(
              type.value(),
              agent.value(),
              turn.turn().value(),
              turn.endingSeq().value(),
              timestamp(turn.arrivedAt()),
              timestamp(turn.startedAt()),
              timestamp(turn.endedAt()),
              turn.trajectory().version(),
              turn.trajectory().hash(),
              turn.outcome().name(),
              turn.rounds(),
              turn.toolCalls(),
              turn.toolSuccesses(),
              turn.toolFailures(),
              turn.toolDenials(),
              turn.inferenceCalls(),
              turn.inferenceRetries())
          .update();
    } catch (DuplicateKeyException e) {
      throw new IllegalStateException(
          "agent " + agent.value() + " already has a row for turn " + turn.turn().value(), e);
    }
  }

  @Override
  public List<AgentTurn> of(AgentType type, AgentId agent) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    return jdbc.sql(SELECT).params(type.value(), agent.value()).query((rs, _) -> read(rs)).list();
  }

  private static AgentTurn read(ResultSet rs) throws SQLException {
    return new AgentTurn(
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
        rs.getInt("inference_retry_count"));
  }

  private static OffsetDateTime timestamp(Instant at) {
    return at.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant();
  }
}
