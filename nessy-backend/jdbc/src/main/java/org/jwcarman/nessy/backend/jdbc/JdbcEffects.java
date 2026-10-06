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
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.Attempt;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.effect.Effects;
import org.jwcarman.nessy.backend.effect.FailedAttempt;
import org.jwcarman.nessy.backend.effect.LiveEffect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The effect table, row by row.
 *
 * <p>Shared by every agent type: it knows SQL and codecs, and nothing about what any particular
 * agent type's effects are worth waiting for. That is {@code Outbox}'s job, one instance of which
 * sits in front of this per harness.
 *
 * <p>The rows underneath the outbox: what an agent owes the outside world.
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
public class JdbcEffects implements Effects {

  private static final Logger LOG = LoggerFactory.getLogger(JdbcEffects.class);

  private static final String COL_EFFECT_ID = "effect_id";
  private static final String COL_AGENT_ID = "agent_id";
  private static final String COL_PAYLOAD = "payload";
  private static final String COL_ATTEMPTS_MADE = "attempts_made";
  private static final String COL_DEADLINE = "deadline";

  public static final String PENDING = "PENDING";
  public static final String RUNNING = "RUNNING";

  private static final String COLUMNS_SELECT =
      "SELECT effect_id, agent_id, payload, failure_payload, attempts_made, deadline,"
          + " trace_context, failed_attempts";

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
   *
   * <p>The rows are picked and locked in a {@code MATERIALIZED} CTE, evaluated once per statement,
   * and the update joins to that fixed set. An {@code IN (SELECT ... FOR UPDATE SKIP LOCKED LIMIT
   * ?)} subquery gives no such promise: on a plan that rescans it for each outer row, concurrent
   * claims skip different rows on each rescan and the update takes more than the limit.
   */
  private static final String MARK_RUNNING =
      """
            WITH claimed AS MATERIALIZED (
                SELECT effect_id FROM nessy_agent_effect
                WHERE agent_type = ? AND status IN (?, ?) AND actionable_at <= ?
                ORDER BY actionable_at
                FOR UPDATE SKIP LOCKED
                LIMIT ?)
            UPDATE nessy_agent_effect e
            SET status = ?,
                attempts_made = e.attempts_made + 1,
                actionable_at = LEAST(
                    ? + (e.timeout_millis * INTERVAL '1 millisecond'), e.deadline),
                updated_at = ?
            FROM claimed
            WHERE e.effect_id = claimed.effect_id
            RETURNING e.effect_id, e.agent_id, e.payload, e.failure_payload, e.attempts_made,
                      e.deadline, e.trace_context, e.failed_attempts
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
      "UPDATE nessy_agent_effect SET status = ?, actionable_at = ?, updated_at = ?,"
          + " failed_attempts = ?"
          + " WHERE effect_id = ? AND status = ? AND attempts_made = ?";

  private static final String PARK =
      "UPDATE nessy_agent_effect"
          + " SET parked_at = ?, actionable_at = deadline, updated_at = ?"
          + " WHERE effect_id = ? AND status = ? AND attempts_made = ?";

  /**
   * What a read of live work needs of a row, and nothing it does not: the failure blob and the
   * trace are for performing a row, which a read never does.
   */
  private static final String LIVE_SELECT =
      "SELECT effect_id, agent_type, agent_id, payload, created_at, parked_at, deadline,"
          + " attempts_made, status FROM nessy_agent_effect";

  /** Every row of one agent: few by construction, so no limit. */
  private static final String LIVE_FOR =
      LIVE_SELECT + " WHERE agent_type = ? AND agent_id = ? ORDER BY created_at, effect_id";

  /**
   * The rows waiting on an answer. A row claimed again at its deadline keeps {@code parked_at}
   * until it is deleted, which is why the deadline is compared and not the mark alone.
   */
  private static final String PARKED_NOW =
      LIVE_SELECT + " WHERE parked_at IS NOT NULL AND status = ? AND deadline > ?";

  private static final String OF_TYPE = " AND agent_type = ?";
  private static final String AFTER = " AND (created_at, effect_id) > (?, ?)";
  private static final String PARKED_NOW_PAGE = " ORDER BY created_at, effect_id LIMIT ?";

  private final JdbcClient jdbc;
  private final Codec<AgentEffect> effectCodec;
  private final Codec<EffectOutcome> outcomeCodec;
  private final Codec<List<FailedAttempt>> attemptCodec;

  /**
   * Builds its own codecs rather than being handed them.
   *
   * <p>Each of these has exactly one user -- this class -- so publishing them as beans would only
   * put the decision about what this table stores somewhere other than the table, and lean on
   * Spring telling four {@code Codec} beans apart by their type argument alone. The factory is the
   * shared thing; a codec for a type only this class writes is not.
   */
  public JdbcEffects(JdbcClient jdbc, CodecFactory codecs) {
    this.jdbc = jdbc;
    // What to do. Only what to do: which model to call is read from the binding.
    this.effectCodec = codecs.create(AgentEffect.class);
    // What to tell the agent when nobody can say anything better -- an unreadable payload, or
    // a deadline that passed before the work started.
    this.outcomeCodec = codecs.create(EffectOutcome.class);
    // What the attempts before the current one learned. A TypeRef rather than a class because
    // what is stored is the whole list: a row keeps one blob, not a row per attempt.
    this.attemptCodec = codecs.create(new TypeRef<List<FailedAttempt>>() {});
  }

  /** Null where nothing was ever kept, which is every row that was never tried twice. */
  @Override
  public List<FailedAttempt> attemptsOf(Attempt attempt) {
    Objects.requireNonNull(attempt, "attempt must not be null");
    byte[] stored = attempt.failedAttempts();
    return stored == null ? List.of() : attemptCodec.decode(stored);
  }

  /**
   * Writes down an effect to be performed later, and beside it the outcome to deliver if it never
   * can be.
   *
   * <p>Takes the effect and the failure themselves rather than their bytes: encoding them is this
   * store's business, and a caller holding a codec for something only this table stores is a caller
   * that has been handed the wrong thing.
   */
  @Override
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
  @Override
  public AgentEffect effectOf(Attempt attempt) {
    return effectCodec.decode(attempt.payload());
  }

  /** Reads the outcome to deliver when {@link #effectOf} cannot be read. */
  @Override
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
  @Override
  public List<Attempt> markRunning(AgentType agentType, Instant now, int batchSize) {
    return jdbc.sql(MARK_RUNNING)
        .params(
            agentType.value(), PENDING, RUNNING, utc(now), batchSize, RUNNING, utc(now), utc(now))
        .query(
            (rs, n) ->
                new Attempt(
                    rs.getObject(COL_EFFECT_ID, UUID.class),
                    new AgentId(rs.getObject(COL_AGENT_ID, UUID.class)),
                    rs.getBytes(COL_PAYLOAD),
                    rs.getBytes("failure_payload"),
                    rs.getInt(COL_ATTEMPTS_MADE),
                    rs.getObject(COL_DEADLINE, OffsetDateTime.class).toInstant(),
                    rs.getString("trace_context"),
                    rs.getBytes("failed_attempts")))
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
   * <p>A parked row is still a running row, which is the point: a reply that arrives while it waits
   * finds it here like any other attempt. That it was parked changes only when it is due again, not
   * whether it is found.
   */
  @Override
  public List<Attempt> runningFor(AgentType agentType, AgentId agentId) {
    return jdbc.sql(RUNNING_FOR)
        .params(agentType.value(), agentId.value(), RUNNING)
        .query(
            (rs, n) ->
                new Attempt(
                    rs.getObject(COL_EFFECT_ID, UUID.class),
                    new AgentId(rs.getObject(COL_AGENT_ID, UUID.class)),
                    rs.getBytes(COL_PAYLOAD),
                    rs.getBytes("failure_payload"),
                    rs.getInt(COL_ATTEMPTS_MADE),
                    rs.getObject(COL_DEADLINE, OffsetDateTime.class).toInstant(),
                    rs.getString("trace_context"),
                    rs.getBytes("failed_attempts")))
        .list();
  }

  @Override
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
  @Override
  public boolean reschedule(
      UUID effectId, int attemptsMade, Instant at, List<FailedAttempt> failedAttempts) {
    byte[] encoded =
        failedAttempts == null || failedAttempts.isEmpty()
            ? null
            : attemptCodec.encode(List.copyOf(failedAttempts));
    return jdbc.sql(RESCHEDULE)
            .params(PENDING, utc(at), utc(at), encoded, effectId, RUNNING, attemptsMade)
            .update()
        == 1;
  }

  /**
   * Marks a running attempt as parked and makes its row due at its deadline.
   *
   * <p>The claim set {@code actionable_at} to when that attempt stops being believed, and a claimer
   * whose clock runs behind the writer's can put that earlier than the deadline. A parked row is
   * waiting for an answer, not working, so it is due at the deadline and not before. Fenced like
   * {@link #complete} and {@link #reschedule}.
   */
  @Override
  public boolean park(UUID effectId, int attemptsMade, Instant at) {
    return jdbc.sql(PARK).params(utc(at), utc(at), effectId, RUNNING, attemptsMade).update() == 1;
  }

  /**
   * Every live row of one agent, read without claiming any of it.
   *
   * <p>A row whose payload this build cannot decode is left out and logged. A read for display must
   * not fail because of one row, and the dispatcher has its own way of settling such a row.
   */
  @Override
  public List<LiveEffect> liveFor(AgentType agentType, AgentId agentId) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    return jdbc
        .sql(LIVE_FOR)
        .params(agentType.value(), agentId.value())
        .query((rs, n) -> read(rs).effect())
        .list()
        .stream()
        .flatMap(Optional::stream)
        .toList();
  }

  /**
   * The rows waiting on an answer, a page at a time.
   *
   * <p>Keyed on {@code (created_at, effect_id)} rather than an offset: rows can finish between
   * pages, and an offset would then skip the row that slid into the place of the one that left. The
   * cursor is the last row the caller got, so a row is never repeated and never missed.
   *
   * <p>A row that cannot be decoded is passed over and the read goes on past it, so a page is full
   * unless the rows ran out: an empty page means there is nothing more, however many unreadable
   * rows sat in the way.
   */
  @Override
  public List<LiveEffect> parkedNow(
      Optional<AgentType> agentType, Instant now, Optional<LiveEffect> after, int limit) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(now, "now must not be null");
    Objects.requireNonNull(after, "after must not be null");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive, was " + limit);
    }
    List<LiveEffect> found = new ArrayList<>();
    Optional<Cursor> cursor = after.map(last -> new Cursor(last.createdAt(), last.effectId()));
    while (found.size() < limit) {
      int wanted = limit - found.size();
      List<Read> rows = parkedPage(agentType, now, cursor, wanted);
      rows.forEach(row -> row.effect().ifPresent(found::add));
      if (rows.size() < wanted) {
        break;
      }
      cursor = Optional.of(rows.getLast().cursor());
    }
    return List.copyOf(found);
  }

  private List<Read> parkedPage(
      Optional<AgentType> agentType, Instant now, Optional<Cursor> after, int limit) {
    StringBuilder sql = new StringBuilder(PARKED_NOW);
    List<Object> params = new ArrayList<>(List.of(RUNNING, utc(now)));
    agentType.ifPresent(
        type -> {
          sql.append(OF_TYPE);
          params.add(type.value());
        });
    after.ifPresent(
        last -> {
          sql.append(AFTER);
          params.add(utc(last.createdAt()));
          params.add(last.effectId());
        });
    sql.append(PARKED_NOW_PAGE);
    params.add(limit);
    return jdbc.sql(sql.toString()).params(params).query((rs, n) -> read(rs)).list();
  }

  /** Where a page stopped: the order's own key, so the next one starts strictly after it. */
  private record Cursor(Instant createdAt, UUID effectId) {}

  /** A row as read, kept even when it cannot be decoded so that the cursor can pass over it. */
  private record Read(Cursor cursor, Optional<LiveEffect> effect) {}

  private Read read(ResultSet rs) throws SQLException {
    UUID effectId = rs.getObject(COL_EFFECT_ID, UUID.class);
    Instant createdAt = rs.getObject("created_at", OffsetDateTime.class).toInstant();
    AgentEffect effect;
    try {
      effect = effectCodec.decode(rs.getBytes(COL_PAYLOAD));
    } catch (RuntimeException e) {
      LOG.warn("Skipping effect {}: its payload cannot be decoded", effectId, e);
      return new Read(new Cursor(createdAt, effectId), Optional.empty());
    }
    OffsetDateTime parkedAt = rs.getObject("parked_at", OffsetDateTime.class);
    return new Read(
        new Cursor(createdAt, effectId),
        Optional.of(
            new LiveEffect(
                effectId,
                new AgentType(rs.getString("agent_type")),
                new AgentId(rs.getObject(COL_AGENT_ID, UUID.class)),
                effect,
                createdAt,
                Optional.ofNullable(parkedAt).map(OffsetDateTime::toInstant),
                rs.getObject(COL_DEADLINE, OffsetDateTime.class).toInstant(),
                rs.getInt(COL_ATTEMPTS_MADE),
                RUNNING.equals(rs.getString("status")))));
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
