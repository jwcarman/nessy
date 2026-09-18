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
package org.jwcarman.nessy.engine.store;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.agent.EffectOutcome;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The effect table, row by row.
 *
 * <p>Shared by every agent type: it knows SQL and codecs, and nothing about what any particular
 * agent type's effects are worth waiting for. That is {@link EffectStore}'s job, one instance of
 * which sits in front of this per harness.
 *
 * <p>The outbox: what the agent owes the outside world.
 *
 * <p>A row is written in the transition that decided it and performed later. There is no lease and
 * nothing to renew -- {@code actionable_at} says when a row may be acted on, and a row past it is
 * simply eligible again. That is the whole of recovery: nothing hands work back, and no holder has
 * to be noticed dying.
 *
 * <p>Two meanings for one column, decided by status. On a {@code PENDING} row it is when to try --
 * either straight away or after a backoff; on a {@code RUNNING} row it is when to stop believing
 * the attempt is still alive.
 *
 * <p>There is no failed status. A row that failed is either going to be tried again, which is
 * pending, or it is finished with, which is deleted. A terminal status would be a third thing: a
 * row nothing will ever pick up, holding an obligation nobody will ever discharge.
 *
 * <p><b>Success deletes the row.</b> That is not tidiness -- a single-row delete is first-wins, so
 * it fences two performers of the same effect for free: the loser deletes nothing, throws, and its
 * transaction rolls back. Keeping a COMPLETED status instead would need an explicit fence to do the
 * same job.
 */
@Component
public class JdbcEffectStore {

  public static final String PENDING = "PENDING";
  public static final String RUNNING = "RUNNING";

  private static final String COLUMNS_SELECT =
      "SELECT effect_id, agent_id, payload, failure_payload, attempts_made, deadline,"
          + " trace_context";

  private static final String COLUMNS =
      "effect_id, agent_id, agent_type, payload, timeout_millis, failure_payload, deadline,"
          + " trace_context,"
          + " status, attempts_made, actionable_at, created_at, updated_at";

  private static final String INSERT =
      "INSERT INTO nessy_agent_effect ("
          + COLUMNS
          + ")"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, NULL)";

  /**
   * Takes what is due and marks it running, in one statement.
   *
   * <p>{@code SKIP LOCKED} is what lets several dispatchers run without coordinating: a row another
   * is already holding is passed over rather than waited for. The status filter matters as much --
   * a {@code RUNNING} row that is due again is an attempt nobody finished, and taking it is how
   * that recovers.
   */
  private static final String MARK_RUNNING =
      """
            UPDATE nessy_agent_effect
            SET status = ?,
                attempts_made = attempts_made + 1,
                actionable_at = LEAST(
                    ? + (timeout_millis * INTERVAL '1 millisecond'), deadline),
                updated_at = ?
            WHERE effect_id IN (
                SELECT effect_id FROM nessy_agent_effect
                WHERE agent_type = ? AND status IN (?, ?) AND actionable_at <= ?
                ORDER BY actionable_at
                FOR UPDATE SKIP LOCKED
                LIMIT ?)
            RETURNING effect_id, agent_id, payload, failure_payload, attempts_made, deadline,
                      trace_context
            """;

  /**
   * The rows of one agent that have been claimed and not finished.
   *
   * <p>Few by construction -- one per call a turn asked for -- so this is read whole and matched in
   * Java rather than reaching into the payload from SQL. What a late answer names is a call, and
   * which row holds that call is a question only the decoded effect can answer.
   */
  private static final String RUNNING_FOR =
      COLUMNS_SELECT
          + " FROM nessy_agent_effect WHERE agent_type = ? AND agent_id = ? AND status = ?";

  private static final String DELETE =
      "DELETE FROM nessy_agent_effect"
          + " WHERE effect_id = ? AND status = ? AND attempts_made = ?";
  private static final String RESCHEDULE =
      "UPDATE nessy_agent_effect SET status = ?, actionable_at = ?, updated_at = ?"
          + " WHERE effect_id = ? AND status = ? AND attempts_made = ?";

  private final JdbcClient jdbc;
  private final Codec<AgentEffect> effectCodec;
  private final Codec<EffectOutcome> outcomeCodec;

  /**
   * Builds its own codecs rather than being handed them.
   *
   * <p>Each of these has exactly one user -- this class -- so publishing them as beans would only
   * put the decision about what this table stores somewhere other than the table, and lean on
   * Spring telling four {@code Codec} beans apart by their type argument alone. The factory is the
   * shared thing; a codec for a type only this class writes is not.
   */
  public JdbcEffectStore(JdbcClient jdbc, CodecFactory codecs) {
    this.jdbc = jdbc;
    // What to do. Only what to do: which model to call is read from the binding.
    this.effectCodec = codecs.create(AgentEffect.class);
    // What to tell the agent when nobody can say anything better -- an unreadable payload, or
    // a deadline that passed before the work started.
    this.outcomeCodec = codecs.create(EffectOutcome.class);
  }

  /**
   * Writes down an effect to be performed later, and beside it the outcome to deliver if it never
   * can be.
   *
   * <p>Takes the effect and the failure themselves rather than their bytes: encoding them is this
   * store's business, and a caller holding a codec for something only this table stores is a caller
   * that has been handed the wrong thing.
   */
  public void insert(
      AgentType agentType,
      AgentId agentId,
      AgentEffect effect,
      Duration timeout,
      EffectOutcome undispatchable,
      Instant deadline,
      String traceContext,
      Instant at) {
    jdbc.sql(INSERT)
        .params(
            UUID.randomUUID(),
            agentId.value(),
            agentType.value(),
            effectCodec.encode(effect),
            timeout.toMillis(),
            outcomeCodec.encode(undispatchable),
            utc(deadline),
            traceContext,
            PENDING,
            utc(at),
            utc(at))
        .update();
  }

  /**
   * Reads the effect an attempt is for.
   *
   * <p>Separate from {@link #markRunning} and from {@link #failureOf} on purpose: the two blobs on
   * a row are independent, and a row whose effect cannot be read can still say what to tell the
   * waiting agent. Decoding both up front would lose that -- one unreadable blob would take the
   * other down with it -- so each is read only when it is wanted, and throws only for itself.
   */
  public AgentEffect effectOf(Attempt attempt) {
    return effectCodec.decode(attempt.payload());
  }

  /** Reads the outcome to deliver when {@link #effectOf} cannot be read. */
  public EffectOutcome failureOf(Attempt attempt) {
    return outcomeCodec.decode(attempt.failurePayload());
  }

  /**
   * Flips what is due to running and says when it is next due, in one statement. Nothing is taken
   * and nothing is owned: a row past {@code actionable_at} is simply eligible again, which is the
   * whole of recovery.
   *
   * <p>{@code actionable_at} is capped at the row's deadline: a row can come due at its deadline,
   * but never after, because coming due then means being given up on rather than tried again.
   * Nothing here writes the deadline -- it was settled when the effect was.
   */
  public List<Attempt> markRunning(AgentType agentType, Instant now, int batchSize) {
    return jdbc.sql(MARK_RUNNING)
        .params(
            RUNNING, utc(now), utc(now), agentType.value(), PENDING, RUNNING, utc(now), batchSize)
        .query(
            (rs, n) ->
                new Attempt(
                    rs.getObject("effect_id", UUID.class),
                    new AgentId(rs.getObject("agent_id", UUID.class)),
                    rs.getBytes("payload"),
                    rs.getBytes("failure_payload"),
                    rs.getInt("attempts_made"),
                    rs.getObject("deadline", OffsetDateTime.class).toInstant(),
                    rs.getString("trace_context")))
        .list();
  }

  /**
   * Every claimed, unfinished row of one agent.
   *
   * <p>For an answer arriving from outside: it names a call, and this is the small set of rows that
   * could be holding it -- one per call a turn asked for. Read whole and matched in Java rather
   * than reaching into the payload from SQL, because what a late answer names is a call and only
   * the decoded effect can say which row holds it.
   *
   * <p>A parked row looks exactly like an attempt in progress, which is the point: nothing here
   * records that anything was deferred, so there is nothing to tell the two apart and nothing that
   * needs to.
   */
  public List<Attempt> runningFor(AgentType agentType, AgentId agentId) {
    return jdbc.sql(RUNNING_FOR)
        .params(agentType.value(), agentId.value(), RUNNING)
        .query(
            (rs, n) ->
                new Attempt(
                    rs.getObject("effect_id", UUID.class),
                    new AgentId(rs.getObject("agent_id", UUID.class)),
                    rs.getBytes("payload"),
                    rs.getBytes("failure_payload"),
                    rs.getInt("attempts_made"),
                    rs.getObject("deadline", OffsetDateTime.class).toInstant(),
                    rs.getString("trace_context")))
        .list();
  }

  public boolean complete(UUID effectId, int attemptsMade) {
    return jdbc.sql(DELETE).params(effectId, RUNNING, attemptsMade).update() == 1;
  }

  /**
   * Puts a failed attempt back for another go, fenced the same way.
   *
   * <p>Back to {@code PENDING} rather than left running, because the row is genuinely not being
   * worked on and its status should say so -- and because {@code actionable_at} then means the
   * backoff rather than a watchdog, which is the same column answering the question its status
   * decides.
   */
  public boolean reschedule(UUID effectId, int attemptsMade, Instant at) {
    return jdbc.sql(RESCHEDULE)
            .params(PENDING, utc(at), utc(at), effectId, RUNNING, attemptsMade)
            .update()
        == 1;
  }

  /**
   * PostgreSQL will not accept a bare {@link Instant} as a parameter -- it refuses rather than
   * guessing which of its date types was meant. H2 accepts one happily, so this is exactly the kind
   * of bug that passes every test until it reaches the database it will actually run on.
   */
  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  /**
   * One attempt at one effect.
   *
   * <p>Named for what you hold rather than what it was: by the time this exists the row has been
   * flipped to running and its counter raised, so "due" describes the one state it is guaranteed
   * not to be in.
   *
   * <p>No type here, and no column for one: the payload is a sealed hierarchy, so decoding it
   * yields the type and an exhaustive switch over it. A discriminator alongside would be a second
   * copy of the same fact, free to disagree with the first.
   */
}
