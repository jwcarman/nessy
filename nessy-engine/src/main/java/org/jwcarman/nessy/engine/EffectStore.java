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
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.engine.agent.Effect;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Work the agent decided on, and who is doing it.
 *
 * <p><b>Inserted inside the transition's transaction</b>, so an effect and the fact it came from
 * commit together or not at all. Attempted and executed OUTSIDE it, so nothing external happens
 * while an agent's row is locked.
 *
 * <p><b>{@code SKIP LOCKED} rather than a queue.</b> Every node may poll at once and none of them
 * contends: an attempter takes what it can lock and steps over the rest. No leader election, no
 * singleton scheduler. Measured to behave correctly on H2 as well as PostgreSQL.
 *
 * <p><b>There is no reaper.</b> Recovery is the absence of an exclusion, not an action anyone
 * takes: a row whose {@code actionable_at} has passed is simply eligible again, and the same {@link
 * #attempt} that would retry a fresh row is the one that finds a stalled one too -- see the class
 * javadoc on {@code actionable_at} in {@code nessy-schema.sql}.
 *
 * <p><b>Two different transaction contracts, on purpose.</b> {@link #attempt} manages its OWN
 * transaction: {@code SELECT ... FOR UPDATE} holds its row locks only until commit, so unless the
 * SELECT and the UPDATE that follows it share one transaction, the lock is gone before it means
 * anything and two attempters can both take the same row. Its {@link TransactionTemplate} uses
 * default {@code PROPAGATION_REQUIRED} rather than {@code REQUIRES_NEW}, so a caller that already
 * has a transaction open is joined rather than shadowed by a second one. {@link #insert}, {@link
 * #complete}, {@link #retry}, and {@link #abandon}, by contrast, take NO transaction of their own
 * and must keep joining whichever one the caller already has open: {@code Transition} writes state,
 * inserts the effects a decision produced, and discharges the effect a decision completed, all as
 * one commit, and that atomicity is the property the durability design rests on.
 */
final class EffectStore {

  /** How an effect is stored while its obligation is outstanding. */
  static final Codec<Effect> PAYLOADS = JsonCodec.of(EngineMapper.INSTANCE, Effect.class);

  private static final String PENDING = "PENDING";
  private static final String RUNNING = "RUNNING";
  private static final String PARKED = "PARKED";
  private static final String FAILED = "FAILED";

  private static final String INSERT =
      "INSERT INTO nessy_effect"
          + " (effect_id, agent_type, agent_id, turn_id, call_id, ordinal, payload, observability,"
          + " status, attempts, actionable_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?,"
          + " ?)";
  private static final String SELECT_DUE =
      "SELECT effect_id, agent_id, turn_id, call_id, ordinal, payload, observability, attempts,"
          + " status"
          + " FROM nessy_effect"
          + " WHERE agent_type = ? AND actionable_at <= ?"
          + " ORDER BY actionable_at LIMIT ? FOR UPDATE SKIP LOCKED";
  private static final String TAKE =
      "UPDATE nessy_effect SET status = ?, actionable_at = ? WHERE effect_id = ?";
  private static final String COMPLETE =
      "DELETE FROM nessy_effect WHERE effect_id = ? AND status = ?";
  private static final String DELETE_AGENT =
      "DELETE FROM nessy_effect WHERE agent_type = ? AND agent_id = ?";
  private static final String DELETE_FOR_CALL =
      "DELETE FROM nessy_effect"
          + " WHERE agent_type = ? AND agent_id = ? AND turn_id = ? AND call_id = ?";
  private static final String EXISTS_FOR_CALL =
      "SELECT COUNT(*) FROM nessy_effect"
          + " WHERE agent_type = ? AND agent_id = ? AND turn_id = ? AND call_id = ?";
  private static final String PARK =
      "UPDATE nessy_effect SET status = ?, actionable_at = ?"
          + " WHERE agent_type = ? AND agent_id = ? AND turn_id = ? AND call_id = ? AND status = ?";
  private static final String RETRY =
      "UPDATE nessy_effect SET status = ?, actionable_at = ?, attempts = attempts + 1"
          + " WHERE effect_id = ?";
  private static final String ABANDON =
      "UPDATE nessy_effect SET status = ?, reason = ?, actionable_at = NULL,"
          + " attempts = attempts + 1 WHERE effect_id = ?";

  /**
   * One attempted obligation, and what it says to do.
   *
   * <p>{@code attempts} counts FAILURES, not starts -- how many times this obligation has already
   * failed, whichever caught it: the worker's own {@code retry}/{@code abandon} write-back after an
   * observed failure, or a later pass that finds a row still {@code RUNNING} past its watchdog and
   * treats that the same way. A first-ever attempt reads {@code 0}: nothing has failed yet. {@link
   * RetryPolicy#decide} takes this value AS IS -- no adjustment -- which is exactly why the count
   * means "failures", never "attempts so far including the one about to run": a counter that
   * included the in-flight attempt would need every caller to subtract one before asking, and the
   * next reader of a bare {@code attempts} column would not know which direction it counted. See
   * the retry section of the design of record 2026-09-04 (Task 7).
   *
   * <p>{@code observability} is the W3C propagation carrier -- traceparent, tracestate, and any
   * intentionally propagated baggage -- as the JSON the caller of {@link #insert} serialized it to.
   * {@code EffectStore} stores and returns it verbatim; it neither parses nor interprets it. May be
   * {@code null}: an effect created outside any trace has no context to carry.
   *
   * <p>The generated equality a record gives you compares {@code payload} by IDENTITY, so two
   * {@code Attempted} values read from the same database would differ. Written out explicitly for
   * the same reason {@code BacklogStore.Row} is: nothing here relies on that today, but the day
   * something does, the failure is silent otherwise.
   *
   * <p>{@code parked} is the row's status as {@link #attempt} found it, BEFORE the very same call
   * marks it {@code RUNNING} again with a fresh watchdog -- {@code true} only for a call whose own
   * TERM just lapsed, never for an ordinary watchdog timeout. It is how the caller tells the two
   * apart: a parked row come due is a deadline that ran out while somebody still held a reply
   * token, and re-attempting the {@code AskApprover}/{@code RunTool} payload underneath would
   * re-run a tool a person or a webhook may still answer -- exactly the defect a second,
   * disagreeing deadline mechanism used to cause. See {@code AgentRuntime#perform}.
   */
  record Attempted(
      EffectId id,
      AgentId agentId,
      TurnId turnId,
      CallId callId,
      int ordinal,
      byte[] payload,
      String observability,
      int attempts,
      boolean parked) {

    @Override
    public boolean equals(Object other) {
      // Destructured with "other" names on purpose: the components are called the same things as
      // this record's own fields, so binding them bare would shadow every field it is comparing
      // against and the comparison would silently be with itself.
      return other
              instanceof
              Attempted(
                  EffectId otherId,
                  AgentId otherAgentId,
                  TurnId otherTurnId,
                  CallId otherCallId,
                  int otherOrdinal,
                  byte[] otherPayload,
                  String otherObservability,
                  int otherAttempts,
                  boolean otherParked)
          && Objects.equals(id, otherId)
          && Objects.equals(agentId, otherAgentId)
          && Objects.equals(turnId, otherTurnId)
          && Objects.equals(callId, otherCallId)
          && ordinal == otherOrdinal
          && Arrays.equals(payload, otherPayload)
          && Objects.equals(observability, otherObservability)
          && attempts == otherAttempts
          && parked == otherParked;
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          id,
          agentId,
          turnId,
          callId,
          ordinal,
          Arrays.hashCode(payload),
          observability,
          attempts,
          parked);
    }

    /**
     * The payload is codec-encoded content, so it is measured rather than printed.
     *
     * <p>The format string is parenthesized before {@code .formatted} is called -- {@code "a" + "b"
     * .formatted(args)} binds the method call to {@code "b"} ALONE, per Java's ordinary precedence,
     * silently matching every conversion in the trailing fragment against the WRONG prefix of
     * {@code args}. Measured: it previously threw {@code IllegalFormatConversionException} the one
     * time something actually printed this record on a failure path.
     */
    @Override
    public String toString() {
      return ("Attempted[id=%s, agentId=%s, turnId=%s, callId=%s, ordinal=%d, payload=%d bytes,"
              + " observability=%s, attempts=%d, parked=%b]")
          .formatted(
              id,
              agentId,
              turnId,
              callId,
              ordinal,
              payload == null ? 0 : payload.length,
              observability,
              attempts,
              parked);
    }
  }

  private final JdbcClient jdbc;
  private final TransactionTemplate attempting;

  EffectStore(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.jdbc = JdbcClient.create(dataSource);
    this.attempting = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  EffectId insert(
      AgentType agentType,
      AgentId agentId,
      TurnId turnId,
      CallId callId,
      int ordinal,
      byte[] payload,
      String observability) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(payload, "payload must not be null");
    EffectId id = EffectId.next();
    Instant now = Instant.now();
    jdbc.sql(INSERT)
        .param(id.value())
        .param(agentType.name())
        .param(agentId.value())
        .param(turnId == null ? null : turnId.value())
        .param(callId == null ? null : callId.value())
        .param(ordinal)
        .param(payload)
        .param(observability)
        .param(PENDING)
        // Actionable the moment it exists: a freshly decided effect has nothing to wait for.
        .param(now)
        .param(now)
        .update();
    return id;
  }

  /**
   * Takes up to {@code batchSize} obligations across every agent of this type that are due right
   * now, arming a watchdog on each -- and returns them UNGROUPED. Grouping this batch by agent and
   * running each agent's rows sequentially, in {@code ordinal} order, is the caller's job (see
   * {@code EffectPoller}), never this store's: {@code Release} deletes the claims {@code Remember}
   * writes the exchange from, so two effects of the SAME agent running concurrently can write an
   * empty exchange and lose a turn silently.
   *
   * <p>"Due" is deliberately one test -- {@code actionable_at <= now} -- covering four different
   * histories at once: a fresh PENDING row, a PENDING row coming back from a scheduled backoff, a
   * RUNNING row whose watchdog quietly expired, and a PARKED row whose TERM ran out. There is no
   * separate reaper query; see {@code nessy_effect}'s schema comment. The caller tells a lapsed
   * term apart from an ordinary timeout by {@link Attempted#parked()}, read from this row's status
   * BEFORE {@link #take} overwrites it -- see that field's own javadoc.
   *
   * <p>The watchdog is written by the SAME statement that marks the row RUNNING. Split in two, a
   * crash between them leaves a row nobody will ever revisit. The SELECT and every {@link #take}
   * UPDATE that follows it run in ONE transaction (see the class javadoc), which is what makes the
   * lock this statement takes still be held when the UPDATE that depends on it runs.
   */
  List<Attempted> attempt(AgentType agentType, int batchSize, Instant now, Duration timeout) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(now, "now must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be at least 1");
    }
    Instant watchdogAt = now.plus(timeout);
    return attempting.execute(
        status ->
            jdbc
                .sql(SELECT_DUE)
                .param(agentType.name())
                .param(now)
                .param(batchSize)
                .query(EffectStore::attempted)
                .list()
                .stream()
                .map(effect -> take(effect, watchdogAt))
                .toList());
  }

  /**
   * The obligation is discharged. The row goes: it is not history, and history is not here.
   *
   * <p>Only ever discharges a row that is RUNNING -- attempted, not merely inserted -- because that
   * is the one status a genuine completion can find. A bare {@code DELETE FROM ... WHERE effect_id
   * = ?} with no status check and no row-count assertion made completing an effect twice, or one
   * that was never attempted, silently succeed at deleting nothing: nobody could tell "discharged"
   * and "already gone" apart from the outside.
   *
   * @throws IllegalStateException if no RUNNING row matched {@code id} -- nothing was discharged
   */
  void complete(EffectId id) {
    Objects.requireNonNull(id, "id must not be null");
    int discharged = jdbc.sql(COMPLETE).param(id.value()).param(RUNNING).update();
    if (discharged == 0) {
      throw new IllegalStateException(
          "effect " + id.value() + " was not RUNNING; nothing was discharged");
    }
  }

  /**
   * The failure write-back: back to PENDING, actionable at the given moment, {@code attempts}
   * incremented -- this failure is now counted. Called by {@code EffectWorker} the moment it
   * observes a genuine failure and has already asked {@link RetryPolicy} for a delay, rather than
   * leaving the row to be revisited only when its watchdog lapses.
   */
  void retry(EffectId id, Instant actionableAt) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(actionableAt, "actionableAt must not be null");
    jdbc.sql(RETRY).param(PENDING).param(actionableAt).param(id.value()).update();
  }

  /**
   * Every obligation this agent has outstanding, attempted or not. Only forgetting goes this wide.
   */
  void deleteAgent(AgentType agentType, AgentId agentId) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    jdbc.sql(DELETE_AGENT).param(agentType.name()).param(agentId.value()).update();
  }

  /**
   * A settled call discharges its own effect row directly, without ever being attempted again.
   *
   * <p>{@code Transition} calls this for every call its fold just marked {@code Completed} --
   * whichever route brought the news: a late answer, a denial, or a lapsed term -- so by the time
   * this runs, the call really is over. A gated tool parked on a person leaves its {@code
   * AskApprover} or {@code RunTool} row outstanding for as long as the deferral lasts (see {@code
   * Dispatcher} / {@code EffectWorker#tell}: {@code ToolParked} does not discharge it), and without
   * this, the poller would re-run a tool whose call was already settled once its row next came due.
   * Turn-scoped, because a {@link CallId} alone is only unique within one turn.
   */
  void deleteForCall(AgentType agentType, AgentId agentId, TurnId turnId, CallId callId) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(callId, "callId must not be null");
    jdbc.sql(DELETE_FOR_CALL)
        .param(agentType.name())
        .param(agentId.value())
        .param(turnId == null ? null : turnId.value())
        .param(callId.value())
        .update();
  }

  /**
   * Whether a call still has an outstanding effect row -- what {@code Replies} asks before waking
   * an agent to answer it, so a late answer for a call that already settled is refused rather than
   * resurrecting a forgotten or finished agent purely to say so. Replaces asking {@code Reminders}
   * whether a deadline was still armed: the effect row IS the call's whole remaining obligation
   * now, so there is exactly one place to ask, not two that could disagree about the answer.
   */
  boolean existsForCall(AgentType agentType, AgentId agentId, TurnId turnId, CallId callId) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(callId, "callId must not be null");
    Integer count =
        jdbc.sql(EXISTS_FOR_CALL)
            .param(agentType.name())
            .param(agentId.value())
            .param(turnId == null ? null : turnId.value())
            .param(callId.value())
            .query(Integer.class)
            .single();
    return count != null && count > 0;
  }

  /**
   * Moves a parked call's own outstanding row to its deadline: {@code RUNNING} becomes {@code
   * PARKED}, and {@code actionable_at} becomes {@code actionableAt} -- the clamped term a deferring
   * tool or approver was granted. This IS what makes {@code actionable_at} the row's one durable
   * deadline: no second table tracks it, and nothing here can disagree with what the poller reads.
   *
   * <p>Matches by {@code (agent_type, agent_id, turn_id, call_id)}, the same coordinates {@link
   * #deleteForCall} discharges by -- see {@code nessy_effect_call}. Restricted to a row currently
   * {@code RUNNING}: only the {@code AskApprover}/{@code RunTool} attempt this call is IN,
   * deferring right now, is ever the one being parked.
   *
   * <p><b>Reports rather than raises when it matches nothing.</b> Not finding a RUNNING row is
   * unreachable through today's paths, but it is exactly the shape of failure this mechanism exists
   * to make loud rather than possible: land nothing here and the row keeps its short watchdog, the
   * poller reattempts it on that schedule instead of the real term, and a human holding a reply
   * token can see their tool re-run underneath them. A caller racing a legitimate concurrent
   * completion (the row discharged out from under this write) is not a fault either, which is why
   * this is a boolean for the caller to log, not an exception -- the same reasoning {@link
   * #complete} already applies to a discharge that finds nothing RUNNING.
   *
   * @return whether a row was actually moved to PARKED
   */
  boolean park(
      AgentType agentType, AgentId agentId, TurnId turnId, CallId callId, Instant actionableAt) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(callId, "callId must not be null");
    Objects.requireNonNull(actionableAt, "actionableAt must not be null");
    int updated =
        jdbc.sql(PARK)
            .param(PARKED)
            .param(actionableAt)
            .param(agentType.name())
            .param(agentId.value())
            .param(turnId == null ? null : turnId.value())
            .param(callId.value())
            .param(RUNNING)
            .update();
    return updated > 0;
  }

  /**
   * Retired without being discharged: the {@link RetryPolicy} said stop, so this failure is counted
   * ({@code attempts} increments) same as any other, and then the row is closed rather than
   * rescheduled. The payload survives untouched, in its own column -- {@code reason} is where the
   * failure goes, so the row that could tell an operator what the agent was trying to do still can.
   */
  void abandon(EffectId id, String reason) {
    Objects.requireNonNull(id, "id must not be null");
    jdbc.sql(ABANDON).param(FAILED).param(reason == null ? "" : reason).param(id.value()).update();
  }

  /**
   * Marks a row RUNNING with a fresh watchdog. {@code attempts} is untouched -- pickup is not a
   * failure, so it counts nothing; see the class javadoc and {@code nessy_effect.attempts} in the
   * schema.
   */
  private Attempted take(Attempted raw, Instant watchdogAt) {
    jdbc.sql(TAKE).param(RUNNING).param(watchdogAt).param(raw.id().value()).update();
    return raw;
  }

  /**
   * Reads a row exactly as it is right now, {@code attempts} included -- {@link #take} changes
   * nothing about it, so what this reads back IS what the caller sees.
   */
  private static Attempted attempted(ResultSet rs, int row) throws SQLException {
    String turnId = rs.getString("turn_id");
    String callId = rs.getString("call_id");
    return new Attempted(
        EffectId.of(rs.getString("effect_id")),
        AgentId.of(rs.getString("agent_id")),
        turnId == null ? null : TurnId.of(turnId),
        callId == null ? null : CallId.of(callId),
        rs.getInt("ordinal"),
        rs.getBytes("payload"),
        rs.getString("observability"),
        rs.getInt("attempts"),
        PARKED.equals(rs.getString("status")));
  }
}
