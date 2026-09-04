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
package org.jwcarman.nessy.engine;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Work the agent decided on, and who is doing it.
 *
 * <p><b>Inserted inside the transition's transaction</b>, so an effect and the fact it came from
 * commit together or not at all. Claimed and executed OUTSIDE it, so nothing external happens while
 * an agent's row is locked.
 *
 * <p><b>{@code SKIP LOCKED} rather than a queue.</b> Every node may reap at once and none of them
 * contends: a claimer takes what it can lock and steps over the rest. No leader election, no
 * singleton scheduler. Measured to behave correctly on H2 as well as PostgreSQL.
 */
final class Effects {

  private static final String PENDING = "PENDING";
  private static final String EXECUTING = "EXECUTING";
  private static final String FAILED = "FAILED";

  private static final String INSERT =
      "INSERT INTO nessy_effect"
          + " (effect_id, agent_type, agent_id, turn_id, ordinal, payload, status, attempts,"
          + " expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 0, NULL, ?)";
  private static final String SELECT_PENDING =
      "SELECT effect_id, agent_id, turn_id, ordinal, payload, attempts FROM nessy_effect"
          + " WHERE agent_type = ? AND agent_id = ? AND status = ?"
          + " ORDER BY ordinal FOR UPDATE SKIP LOCKED";
  private static final String SELECT_EXPIRED =
      "SELECT effect_id, agent_id, turn_id, ordinal, payload, attempts FROM nessy_effect"
          + " WHERE agent_type = ? AND status = ? AND expires_at < ?"
          + " ORDER BY expires_at FOR UPDATE SKIP LOCKED";
  private static final String TAKE =
      "UPDATE nessy_effect SET status = ?, attempts = attempts + 1, expires_at = ?"
          + " WHERE effect_id = ?";
  private static final String DELETE = "DELETE FROM nessy_effect WHERE effect_id = ?";
  private static final String RETIRE =
      "UPDATE nessy_effect SET status = ?, payload = ?, expires_at = NULL WHERE effect_id = ?";

  /** One claimed obligation, and what it says to do. */
  record Claimed(
      EffectId id, AgentId agentId, TurnId turnId, int ordinal, String payload, int attempts) {}

  private final JdbcClient jdbc;

  Effects(DataSource dataSource) {
    this.jdbc =
        JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
  }

  EffectId insert(
      AgentType agentType, AgentId agentId, TurnId turnId, int ordinal, String payload) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(payload, "payload must not be null");
    EffectId id = EffectId.next();
    jdbc.sql(INSERT)
        .param(id.value())
        .param(agentType.name())
        .param(agentId.value())
        .param(turnId == null ? null : turnId.value())
        .param(ordinal)
        .param(payload)
        .param(PENDING)
        .param(Instant.now())
        .update();
    return id;
  }

  /**
   * Takes this agent's pending work, in decision order, arming a watchdog on each.
   *
   * <p>The watchdog is written by the SAME statement that marks the row executing. Split in two, a
   * crash between them leaves a row nobody will ever reap.
   */
  List<Claimed> claim(AgentType agentType, AgentId agentId, Instant watchdogAt) {
    Objects.requireNonNull(watchdogAt, "watchdogAt must not be null");
    List<Claimed> found =
        jdbc.sql(SELECT_PENDING)
            .param(agentType.name())
            .param(agentId.value())
            .param(PENDING)
            .query(Effects::claimed)
            .list();
    found.forEach(effect -> take(effect.id(), watchdogAt));
    return found;
  }

  /**
   * Work whose watchdog fired: someone started it and nobody saw it finish.
   *
   * <p>Deliberately NOT called failed. The external action may well have happened, and the caller
   * decides what an unobserved outcome means for that particular effect.
   */
  List<Claimed> claimExpired(AgentType agentType, Instant now, int limit) {
    Objects.requireNonNull(now, "now must not be null");
    List<Claimed> found =
        jdbc
            .sql(SELECT_EXPIRED)
            .param(agentType.name())
            .param(EXECUTING)
            .param(now)
            .query(Effects::claimed)
            .list()
            .stream()
            .limit(limit)
            .toList();
    found.forEach(effect -> take(effect.id(), now.plusSeconds(60)));
    return found;
  }

  /** The obligation is discharged. The row goes: it is not history, and history is not here. */
  void complete(EffectId id) {
    Objects.requireNonNull(id, "id must not be null");
    jdbc.sql(DELETE).param(id.value()).update();
  }

  /** Retired without being discharged. Kept, with its reason, because someone will ask. */
  void fail(EffectId id, String reason) {
    Objects.requireNonNull(id, "id must not be null");
    jdbc.sql(RETIRE).param(FAILED).param(reason == null ? "" : reason).param(id.value()).update();
  }

  private void take(EffectId id, Instant watchdogAt) {
    jdbc.sql(TAKE).param(EXECUTING).param(watchdogAt).param(id.value()).update();
  }

  /**
   * Reads a row as claimed.
   *
   * <p>{@code attempts + 1}, not {@code attempts}: this mapping runs at SELECT time, before the
   * {@link #take(EffectId, Instant)} that follows it increments the column, so the raw value would
   * be one attempt stale. Every caller of this method calls {@code take} on the row it returns, so
   * reporting the post-increment count here is what makes the returned {@link Claimed} describe the
   * row as it actually ends up.
   */
  private static Claimed claimed(ResultSet rs, int row) throws SQLException {
    String turnId = rs.getString("turn_id");
    return new Claimed(
        EffectId.of(rs.getString("effect_id")),
        AgentId.of(rs.getString("agent_id")),
        turnId == null ? null : TurnId.of(turnId),
        rs.getInt("ordinal"),
        rs.getString("payload"),
        rs.getInt("attempts") + 1);
  }
}
