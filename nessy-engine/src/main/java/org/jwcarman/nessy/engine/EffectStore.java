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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * singleton scheduler. The non-blocking CONCURRENCY this buys -- two attempters racing the same
 * batch without one blocking on the other's lock -- is measured on H2 only, by {@code
 * EffectStoreTest}. {@code EffectStorePostgresCertificationTest} runs the same statement against
 * real PostgreSQL and proves the SQL itself is accepted there, but does not race two attempters
 * against it, so PostgreSQL's own lock-skipping behavior under real contention remains unmeasured.
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
  // I3 (Task 7 fix round): ordered by ordinal too, not actionable_at alone -- a same-instant tie
  // could otherwise cut one agent's group at the LIMIT boundary with an earlier ordinal left
  // outside the batch and a later one inside it, which is C2's failure by another road (a group
  // this pass never sees in full is a group EffectPoller cannot sequence correctly).
  private static final String SELECT_DUE =
      "SELECT effect_id, agent_id, turn_id, call_id, ordinal, payload, observability, attempts,"
          + " status"
          + " FROM nessy_effect"
          + " WHERE agent_type = ? AND actionable_at <= ?"
          + " ORDER BY actionable_at, ordinal LIMIT ? FOR UPDATE SKIP LOCKED";

  /**
   * The conditional increment is evaluated against the PRE-update row -- measured, not assumed, by
   * {@code EffectStoreTest} on H2 and by {@code EffectStorePostgresCertificationTest} on real
   * PostgreSQL, where the same {@code CASE WHEN} was re-run against a row inserted directly as
   * {@code RUNNING}: this is the single most load-bearing unverified claim the whole exhaustion
   * guarantee rested on before that second measurement existed. A row already {@code RUNNING} or
   * {@code PARKED} when this UPDATE finds it (both only possible because {@code SELECT_DUE} already
   * proved {@code actionable_at <= now}) is evidence nobody else recorded that this obligation's
   * last attempt failed -- see the class javadoc on {@code attempts} counting failures, and C1 in
   * the Task 7 fix round. A {@code PENDING} row's previous attempt already recorded its OWN failure
   * on the way out (via {@link #retry}), so incrementing here too would double-count it.
   */
  private static final String TAKE =
      "UPDATE nessy_effect SET status = ?, actionable_at = ?,"
          + " attempts = attempts + CASE WHEN status IN (?, ?) THEN 1 ELSE 0 END"
          + " WHERE effect_id = ?";

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
  // F2: FAILED is deliberately excluded. An abandoned row keeps its place in the table as the
  // record of a spent obligation, with actionable_at NULL -- nobody will ever attempt it again, so
  // counting it as outstanding would tell recovery this agent still owes work it does not, and
  // wedge it out of the one mechanism that could re-emit what it really lost.
  private static final String OUTSTANDING =
      "SELECT COUNT(*) FROM nessy_effect"
          + " WHERE agent_type = ? AND agent_id = ? AND status IN (?, ?, ?)";
  private static final String PARK =
      "UPDATE nessy_effect SET status = ?, actionable_at = ?"
          + " WHERE agent_type = ? AND agent_id = ? AND turn_id = ? AND call_id = ? AND status = ?";
  private static final String RETRY =
      "UPDATE nessy_effect SET status = ?, actionable_at = ?, attempts = attempts + 1, reason = ?"
          + " WHERE effect_id = ?";
  private static final String REASON = "SELECT reason FROM nessy_effect WHERE effect_id = ?";
  private static final String STATUS_AND_ACTIONABLE =
      "SELECT status, actionable_at FROM nessy_effect WHERE effect_id = ?";
  // R-AC (Task 7 fix round 2): SETS status back to PENDING, not just actionable_at. A sibling
  // this pass's own attempt() already marked RUNNING, and that C2's fix then held back, is
  // deliberately NOT RUNNING -- left RUNNING, TAKE's conditional increment (see its own javadoc)
  // would read that as "found still RUNNING past its watchdog" and charge it a failure the next
  // time it comes due, even though it never ran at all. PENDING + a future actionable_at is
  // exactly "scheduled, not yet due" -- the same meaning actionable_at already carries for a
  // fresh row, keeping the three meanings of that column coherent.
  //
  // F3: COALESCE rather than "turn_id = ?". Bound to null, that read as "turn_id = NULL", which
  // matches NOTHING in SQL -- so a group stopped by an agent's first-ever TakeWork, the one effect
  // decided before any turn exists, released not one sibling. The turn-less rows are a turn of
  // their own here, which is what the empty string stands for; it can never collide with a real
  // turn id (Identifier rejects empty).
  private static final String DEFER_SIBLINGS =
      "UPDATE nessy_effect SET status = :pending, actionable_at = :at"
          + " WHERE agent_type = :type AND agent_id = :agent AND status IN (:pending, :running)"
          + " AND (COALESCE(turn_id, '') = :turn AND ordinal > :ordinal)";
  // F3: the same statement, widened to name rows outright. The clause above is turn-scoped and the
  // poller groups by AGENT, so a group holding two turns' rows -- which the throw path makes
  // reachable, TakeWork running past an outstanding turn-T row and starting T+1 -- left every
  // turn-(T+1) row this pass claimed RUNNING and un-run. Naming them is exact where a second
  // ordering predicate would not be: it needs no comparison between turn ids, whose order is a
  // property of how Identifiers mints them (UUIDv7) and not one to make a database collation
  // responsible for.
  private static final String DEFER_SIBLINGS_AND_ROWS =
      "UPDATE nessy_effect SET status = :pending, actionable_at = :at"
          + " WHERE agent_type = :type AND agent_id = :agent AND status IN (:pending, :running)"
          + " AND ((COALESCE(turn_id, '') = :turn AND ordinal > :ordinal)"
          + " OR effect_id IN (:ids))";
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
    Timestamp now = ts(Instant.now());
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
                .param(ts(now))
                .param(batchSize)
                .query(EffectStore::row)
                .list()
                .stream()
                .map(due -> take(due, watchdogAt))
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
   * incremented -- this failure is now counted -- and {@code reason} recorded. Called by {@code
   * EffectWorker} the moment it observes a genuine failure and has already asked {@link
   * RetryPolicy} for a delay, rather than leaving the row to be revisited only when its watchdog
   * lapses.
   *
   * <p>{@code reason} is overwritten on every retry, so the row always carries the MOST RECENT
   * attempt's failure -- which is what lets {@link #lastFailure} hand a chronically failing {@code
   * CallModel} obligation's real provider message forward to {@code giveUp}, rather than only the
   * generic "gave up after N failures" a policy's own verdict produces.
   */
  void retry(EffectId id, Instant actionableAt, String reason) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(actionableAt, "actionableAt must not be null");
    jdbc.sql(RETRY).param(PENDING).param(ts(actionableAt)).param(reason).param(id.value()).update();
  }

  /**
   * What the last recorded attempt at {@code id} failed with, if any attempt has failed yet --
   * empty for a row still on its first attempt. Read by {@code giveUp} BEFORE it overwrites this
   * same column with the exhaustion's own reason, so a real provider message survives to the fold
   * an exhausted {@code CallModel} produces instead of being replaced by a generic one.
   */
  Optional<String> lastFailure(EffectId id) {
    Objects.requireNonNull(id, "id must not be null");
    return jdbc.sql(REASON).param(id.value()).query(String.class).optional();
  }

  /**
   * Whether {@code id}'s outcome was already decided SYNCHRONOUSLY, inside the {@code perform} call
   * that just returned -- read by {@code EffectPoller} the instant it does, to decide whether the
   * rest of this agent's group should keep running this pass. See C2 in the Task 7 fix round.
   *
   * <p>A synchronous {@code settle} failure writes {@code retry} (the row goes {@code PENDING}) or
   * {@code giveUp} writes {@code abandon} (the row goes {@code FAILED}) before {@code perform}
   * returns -- both are the outcome landing IN, and both {@link Held#held()}. Asynchronous work (a
   * model call, a tool, an approver) answers later, on a different thread entirely, so the row this
   * reads is still {@code RUNNING} the moment {@code perform} returns -- same as a row that quietly
   * succeeded and was {@link #complete}d (gone entirely). Both of those are NOT held: keep going.
   */
  Held heldAt(EffectId id) {
    Objects.requireNonNull(id, "id must not be null");
    List<Held> rows =
        jdbc.sql(STATUS_AND_ACTIONABLE)
            .param(id.value())
            .query(
                (rs, rowNum) -> {
                  String status = rs.getString("status");
                  java.sql.Timestamp actionableAt = rs.getTimestamp("actionable_at");
                  return switch (status) {
                    case PENDING ->
                        // A real retry -- actionable_at is the backoff EffectWorker just scheduled.
                        new Held(true, actionableAt.toInstant());
                    case FAILED ->
                        // Abandoned: terminal, with no natural "next" moment of its own --
                        // actionable_at is NULL by construction (see ABANDON).
                        new Held(true, null);
                    case RUNNING ->
                        // NOT held -- the outcome has not landed -- but the moment is reported all
                        // the same: F1. A row that THREW is left RUNNING deliberately, and its
                        // watchdog is when it will be attempted again, so it is exactly what a
                        // sibling released by that throw has to wait behind. The caller decides
                        // whether it has any use for this; heldAt() only reports what the row says.
                        new Held(false, actionableAt.toInstant());
                    default ->
                        // PARKED. actionable_at here is a person's deadline, days out, and never a
                        // sibling's place in a queue -- a parked call is a hand-off, so the group
                        // carries on and nothing is deferred behind it at all.
                        new Held(false, null);
                  };
                })
            .list();
    return rows.isEmpty() ? new Held(false, null) : rows.get(0);
  }

  /**
   * Whether an obligation's outcome landed synchronously, and the moment this row is next
   * actionable -- which is the moment any sibling released on its account must wait behind. See
   * {@link #heldAt}.
   *
   * <p>{@code deferSiblingsTo} is null exactly when there is nothing to wait behind: an abandoned
   * row (terminal, {@code actionable_at} NULL), a parked one (whose {@code actionable_at} is a
   * person's deadline, not a queue position), and a row already discharged and gone.
   */
  record Held(boolean held, Instant deferSiblingsTo) {}

  /**
   * Pushes every still-outstanding row of ONE agent's SAME turn above {@code afterOrdinal}, plus
   * every row {@code alsoNamed} names, back to {@code PENDING} with {@code actionable_at} set to
   * {@code actionableAt} -- called by {@code EffectPoller} the instant an earlier row in this
   * pass's group stops it -- by retrying, by being abandoned, or by throwing -- so a sibling
   * ordinal after it (already marked {@code RUNNING} by THIS pass's own {@link #attempt}) does not
   * come due on its own, unrelated watchdog before the retry does, and run past a row that has not
   * run yet -- see C2 in the Task 7 fix round.
   *
   * <p><b>Two reaches, because there are two ways to be behind the stopping row.</b> F3. The turn
   * clause reaches rows this pass never even saw -- an ordinal left outside the batch by the LIMIT,
   * which would otherwise still be due at its ORIGINAL instant and sort in front of the retried row
   * next pass. {@code alsoNamed} reaches the rest of THIS pass's group, whatever turn each row
   * belongs to: the poller groups by agent, and a group holding two turns is reachable (a {@code
   * TakeWork} that ran past an outstanding turn-T row starts T+1, and a watchdog later both turns
   * come due together). A row matching both is updated once and counted once, because it is one
   * statement.
   *
   * <p><b>Sets {@code status} back to {@code PENDING}, not only {@code actionable_at}</b> -- R-AC,
   * the defect C1 and C2 created together. Left {@code RUNNING}, {@link #take}'s conditional
   * increment (see its own javadoc) reads a held sibling's later pickup as "found still RUNNING
   * past its watchdog" and charges it a failure it never earned: it was deliberately not run, and
   * being held is not a failure. {@code PENDING} plus a future {@code actionable_at} is exactly
   * "scheduled, not yet due" -- the same thing those two columns already mean for a fresh row,
   * which is what keeps {@code actionable_at}'s three meanings (backoff / deadline / park term)
   * coherent rather than adding a fourth, PENDING-flavored deadline nothing else recognizes.
   *
   * <p>Restricted to {@code PENDING}/{@code RUNNING} in the WHERE clause -- a {@code PARKED} row's
   * {@code actionable_at} is a human's deadline, never this mechanism's to move, and this SET
   * clause moving it to {@code PENDING} would be exactly that kind of overreach.
   *
   * @return how many sibling rows were pushed out
   */
  int deferSiblings(
      AgentType agentType,
      AgentId agentId,
      TurnId turnId,
      int afterOrdinal,
      List<EffectId> alsoNamed,
      Instant actionableAt) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(alsoNamed, "alsoNamed must not be null");
    Objects.requireNonNull(actionableAt, "actionableAt must not be null");
    JdbcClient.StatementSpec statement =
        jdbc.sql(alsoNamed.isEmpty() ? DEFER_SIBLINGS : DEFER_SIBLINGS_AND_ROWS)
            .param("pending", PENDING)
            .param("running", RUNNING)
            .param("at", ts(actionableAt))
            .param("type", agentType.name())
            .param("agent", agentId.value())
            .param("turn", turnId == null ? "" : turnId.value())
            .param("ordinal", afterOrdinal);
    if (!alsoNamed.isEmpty()) {
      statement = statement.param("ids", alsoNamed.stream().map(EffectId::value).toList());
    }
    return statement.update();
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
   * Whether this agent still owes ANY obligation -- attempted, waiting, or parked on a person.
   *
   * <p>F2: what tells a recovery drive apart from a redundant one. Recovery exists to replace
   * obligations that were LOST -- a node that died between committing a decision and its rows being
   * run -- and an agent with rows still in this table has lost nothing: every one of them is
   * already due, or due again the moment its watchdog or its term lapses. Re-folding {@code
   * Recovered} over such an agent emits a SECOND row for work that is already outstanding, which
   * for a running tool is a re-invocation and for a parked call is a second reply address.
   *
   * <p>Read inside {@code Transition}'s transaction, after the agent's row is locked, so a decision
   * committing new effects cannot slip between the question and the answer.
   */
  boolean hasOutstanding(AgentType agentType, AgentId agentId) {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Integer count =
        jdbc.sql(OUTSTANDING)
            .param(agentType.name())
            .param(agentId.value())
            .param(PENDING)
            .param(RUNNING)
            .param(PARKED)
            .query(Integer.class)
            .single();
    return count != null && count > 0;
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
            .param(ts(actionableAt))
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
   * Marks a row RUNNING with a fresh watchdog, and increments {@code attempts} in the SAME
   * statement -- but ONLY when {@code due} was already {@code RUNNING} or {@code PARKED}: a fresh
   * pickup of a row that was still {@code PENDING} counts nothing, because {@code PENDING} means
   * either "never attempted" or "its previous attempt already recorded its own failure on the way
   * out" (see {@link #retry}), and double-counting that would be wrong in the other direction. See
   * C1 in the Task 7 fix round -- before this, {@code attempts} was untouched by every pickup, so a
   * row whose worker died could loop at the poll interval forever without {@link RetryPolicy} ever
   * being consulted with a rising count.
   */
  private Attempted take(Row due, Instant watchdogAt) {
    jdbc.sql(TAKE)
        .param(RUNNING)
        .param(ts(watchdogAt))
        .param(RUNNING)
        .param(PARKED)
        .param(due.id().value())
        .update();
    boolean wasOutstanding = RUNNING.equals(due.status()) || PARKED.equals(due.status());
    return new Attempted(
        due.id(),
        due.agentId(),
        due.turnId(),
        due.callId(),
        due.ordinal(),
        due.payload(),
        due.observability(),
        wasOutstanding ? due.attempts() + 1 : due.attempts(),
        PARKED.equals(due.status()));
  }

  /**
   * One row exactly as {@code SELECT_DUE} found it, BEFORE {@link #take} touches it -- {@code
   * status} travels only this far; {@link Attempted} exposes {@code parked} (derived from it) but
   * not the raw value, because nothing past {@link #take} needs to tell {@code RUNNING} and {@code
   * PENDING} apart.
   */
  private record Row(
      EffectId id,
      AgentId agentId,
      TurnId turnId,
      CallId callId,
      int ordinal,
      byte[] payload,
      String observability,
      int attempts,
      String status) {}

  private static Row row(ResultSet rs, int rowNum) throws SQLException {
    String turnId = rs.getString("turn_id");
    String callId = rs.getString("call_id");
    return new Row(
        EffectId.of(rs.getString("effect_id")),
        AgentId.of(rs.getString("agent_id")),
        turnId == null ? null : TurnId.of(turnId),
        callId == null ? null : CallId.of(callId),
        rs.getInt("ordinal"),
        rs.getBytes("payload"),
        rs.getString("observability"),
        rs.getInt("attempts"),
        rs.getString("status"));
  }

  /**
   * Every {@code actionable_at}/{@code created_at} parameter goes through {@link
   * JdbcTimestamps#ts(Instant)}, never a bare {@link Instant} -- measured against real PostgreSQL
   * by {@code EffectStorePostgresCertificationTest}: pgjdbc's {@code setObject} cannot infer a SQL
   * type for {@code java.time.Instant} and throws, where H2 had silently tolerated it. This is
   * exactly the class of silent H2/PostgreSQL divergence the certification module exists to catch.
   */
  private static Timestamp ts(Instant instant) {
    return JdbcTimestamps.ts(instant);
  }
}
