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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.engine.agent.Effect;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 *
 * <p><b>Two different transaction contracts, on purpose.</b> {@link #claim(AgentType, AgentId,
 * Instant)} and {@link #claimExpired(AgentType, Instant, int)} manage their OWN transaction: {@code
 * SELECT ... FOR UPDATE} holds its row locks only until commit, so unless the SELECT and every
 * {@code take()} UPDATE that follows it share one transaction, the lock is gone before it means
 * anything and two claimers can both take the same row. Their {@link TransactionTemplate} uses
 * default {@code PROPAGATION_REQUIRED} rather than {@code REQUIRES_NEW}, so a caller that already
 * has a transaction open is joined rather than shadowed by a second one. {@link #insert}, {@link
 * #complete} and {@link #fail}, by contrast, take NO transaction of their own and must keep joining
 * whichever one the caller already has open: {@code Transition} writes state, inserts the effects a
 * decision produced, and discharges the effect a decision completed, all as one commit, and that
 * atomicity is the property the durability design rests on.
 */
final class EffectStore {

  /** How an effect is stored while its obligation is outstanding. */
  static final Codec<Effect> PAYLOADS = JsonCodec.of(EngineMapper.INSTANCE, Effect.class);

  private static final String PENDING = "PENDING";
  private static final String EXECUTING = "EXECUTING";
  private static final String FAILED = "FAILED";

  private static final String INSERT =
      "INSERT INTO nessy_effect"
          + " (effect_id, agent_type, agent_id, turn_id, ordinal, payload, observability, status,"
          + " attempts, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, ?)";
  private static final String SELECT_PENDING =
      "SELECT effect_id, agent_id, turn_id, ordinal, payload, observability, attempts"
          + " FROM nessy_effect"
          + " WHERE agent_type = ? AND agent_id = ? AND status = ?"
          + " ORDER BY ordinal FOR UPDATE SKIP LOCKED";
  private static final String SELECT_EXPIRED =
      "SELECT effect_id, agent_id, turn_id, ordinal, payload, observability, attempts"
          + " FROM nessy_effect"
          + " WHERE agent_type = ? AND status = ? AND expires_at < ?"
          + " ORDER BY expires_at FOR UPDATE SKIP LOCKED";
  private static final String TAKE =
      "UPDATE nessy_effect SET status = ?, attempts = attempts + 1, expires_at = ?"
          + " WHERE effect_id = ?";
  private static final String DELETE = "DELETE FROM nessy_effect WHERE effect_id = ?";
  private static final String RETIRE =
      "UPDATE nessy_effect SET status = ?, reason = ?, expires_at = NULL WHERE effect_id = ?";

  /**
   * One claimed obligation, and what it says to do.
   *
   * <p>{@code observability} is the W3C propagation carrier -- traceparent, tracestate, and any
   * intentionally propagated baggage -- as the JSON the caller of {@link #insert} serialized it to.
   * {@code EffectStore} stores and returns it verbatim; it neither parses nor interprets it. A
   * claiming node restores the trace context from it before performing the work, which is what
   * keeps a turn's distributed trace from fragmenting at the effect boundary. May be {@code null}:
   * an effect created outside any trace has no context to carry.
   *
   * <p>The generated equality a record gives you compares {@code payload} by IDENTITY, so two
   * {@code Claimed} values read from the same database would differ. Written out explicitly for the
   * same reason {@code BacklogStore.Row} is: nothing here relies on that today, but the day
   * something does, the failure is silent otherwise.
   */
  record Claimed(
      EffectId id,
      AgentId agentId,
      TurnId turnId,
      int ordinal,
      byte[] payload,
      String observability,
      int attempts) {

    @Override
    public boolean equals(Object other) {
      // Destructured with "other" names on purpose: the components are called the same things as
      // this record's own fields, so binding them bare would shadow every field it is comparing
      // against and the comparison would silently be with itself.
      return other
              instanceof
              Claimed(
                  EffectId otherId,
                  AgentId otherAgentId,
                  TurnId otherTurnId,
                  int otherOrdinal,
                  byte[] otherPayload,
                  String otherObservability,
                  int otherAttempts)
          && Objects.equals(id, otherId)
          && Objects.equals(agentId, otherAgentId)
          && Objects.equals(turnId, otherTurnId)
          && ordinal == otherOrdinal
          && Arrays.equals(payload, otherPayload)
          && Objects.equals(observability, otherObservability)
          && attempts == otherAttempts;
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          id, agentId, turnId, ordinal, Arrays.hashCode(payload), observability, attempts);
    }

    /** The payload is codec-encoded content, so it is measured rather than printed. */
    @Override
    public String toString() {
      return "Claimed[id=%s, agentId=%s, turnId=%s, ordinal=%d, payload=%d bytes,"
          + " observability=%s, attempts=%d]"
              .formatted(
                  id,
                  agentId,
                  turnId,
                  ordinal,
                  payload == null ? 0 : payload.length,
                  observability,
                  attempts);
    }
  }

  private final JdbcClient jdbc;
  private final TransactionTemplate claiming;

  EffectStore(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.jdbc = JdbcClient.create(dataSource);
    this.claiming = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  EffectId insert(
      AgentType agentType,
      AgentId agentId,
      TurnId turnId,
      int ordinal,
      byte[] payload,
      String observability) {
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
        .param(observability)
        .param(PENDING)
        .param(Instant.now())
        .update();
    return id;
  }

  /**
   * Takes this agent's pending work, in decision order, arming a watchdog on each.
   *
   * <p>The watchdog is written by the SAME statement that marks the row executing. Split in two, a
   * crash between them leaves a row nobody will ever reap. The SELECT and every {@code take()} run
   * in ONE transaction (see the class javadoc), which is what makes the lock this statement takes
   * still be held when the UPDATE that depends on it runs.
   */
  List<Claimed> claim(AgentType agentType, AgentId agentId, Instant watchdogAt) {
    Objects.requireNonNull(watchdogAt, "watchdogAt must not be null");
    return claiming.execute(
        status ->
            jdbc
                .sql(SELECT_PENDING)
                .param(agentType.name())
                .param(agentId.value())
                .param(PENDING)
                .query(EffectStore::claimed)
                .list()
                .stream()
                .map(effect -> take(effect, watchdogAt))
                .toList());
  }

  /**
   * Work whose watchdog fired: someone started it and nobody saw it finish.
   *
   * <p>Deliberately NOT called failed. The external action may well have happened, and the caller
   * decides what an unobserved outcome means for that particular effect.
   */
  List<Claimed> claimExpired(AgentType agentType, Instant now, int limit) {
    Objects.requireNonNull(now, "now must not be null");
    return claiming.execute(
        status ->
            jdbc
                .sql(SELECT_EXPIRED)
                .param(agentType.name())
                .param(EXECUTING)
                .param(now)
                .query(EffectStore::claimed)
                .list()
                .stream()
                .limit(limit)
                .map(effect -> take(effect, now.plusSeconds(60)))
                .toList());
  }

  /** The obligation is discharged. The row goes: it is not history, and history is not here. */
  void complete(EffectId id) {
    Objects.requireNonNull(id, "id must not be null");
    jdbc.sql(DELETE).param(id.value()).update();
  }

  /**
   * Retired without being discharged. The payload survives untouched, in its own column: {@code
   * reason} is where the failure goes, so the row that could tell an operator what the agent was
   * trying to do still can.
   */
  void fail(EffectId id, String reason) {
    Objects.requireNonNull(id, "id must not be null");
    jdbc.sql(RETIRE).param(FAILED).param(reason == null ? "" : reason).param(id.value()).update();
  }

  /**
   * Marks a row taken and returns it as the caller will see it: with {@code attempts} incremented,
   * because that is what this UPDATE is about to make true.
   */
  private Claimed take(Claimed raw, Instant watchdogAt) {
    jdbc.sql(TAKE).param(EXECUTING).param(watchdogAt).param(raw.id().value()).update();
    return new Claimed(
        raw.id(),
        raw.agentId(),
        raw.turnId(),
        raw.ordinal(),
        raw.payload(),
        raw.observability(),
        raw.attempts() + 1);
  }

  /**
   * Reads a row exactly as it is right now. {@code attempts} here is the count BEFORE this claim;
   * {@link #take(Claimed, Instant)} is what turns it into the count after.
   */
  private static Claimed claimed(ResultSet rs, int row) throws SQLException {
    String turnId = rs.getString("turn_id");
    return new Claimed(
        EffectId.of(rs.getString("effect_id")),
        AgentId.of(rs.getString("agent_id")),
        turnId == null ? null : TurnId.of(turnId),
        rs.getInt("ordinal"),
        rs.getBytes("payload"),
        rs.getString("observability"),
        rs.getInt("attempts"));
  }
}
