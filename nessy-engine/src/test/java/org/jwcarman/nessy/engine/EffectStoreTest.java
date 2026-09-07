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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.testing.TestDatabase;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

/**
 * A durable obligation.
 *
 * <p>Three properties, and none of them is "a row goes in and comes out". Attempting hands work
 * back due-order, across every agent of the type at once -- grouping and sequencing it by agent is
 * {@code EffectPoller}'s job, tested there. Attempting twice hands back an already-RUNNING row only
 * once its watchdog has lapsed, because at-least-once is the contract and
 * at-least-twice-immediately is not. And {@code attempts} counts FAILURES, not starts: a row picked
 * up while still {@code PENDING} (never attempted, or its previous attempt already recorded its own
 * failure on the way out) counts nothing, but a row found still {@code RUNNING} or expired {@code
 * PARKED} -- proof nobody else recorded that its last attempt failed -- counts one, in the SAME
 * UPDATE that re-claims it. See C1 in the Task 7 fix round.
 */
@DisplayName("A durable effect")
class EffectStoreTest {

  private static final AgentType TYPE = AgentType.of("watchman");
  private static final AgentId AGENT = AgentId.of("house-1");
  private static final AgentId OTHER = AgentId.of("house-2");
  private static final TurnId TURN = TurnId.of("turn-1");
  private static final Duration TIMEOUT = Duration.ofMinutes(1);

  // See concurrent_attempts_never_double_attempt: trial count and row count both widen the chance
  // a real overlap is caught, without making any single trial's own pass/fail timing-dependent.
  private static final int TRIALS = 6;
  private static final int ROWS_PER_TRIAL = 3000;

  private EmbeddedDatabase database;
  private EffectStore effects;

  @BeforeEach
  void fresh() {
    database = TestDatabase.fresh();
    effects = new EffectStore(database);
  }

  @AfterEach
  void close() {
    database.shutdown();
  }

  @Test
  @DisplayName("an agent type with nothing due attempts nothing")
  void nothing_due_attempts_nothing() {
    assertThat(attempt()).isEmpty();
  }

  @Test
  @DisplayName("a freshly inserted effect is due immediately")
  void a_fresh_effect_is_immediately_due() {
    insert(AGENT, 0, "call-model", null);

    assertThat(attempt())
        .extracting(effect -> text(effect.payload()))
        .containsExactly("call-model");
  }

  @Test
  @DisplayName("a batch spans every agent of the type, not just one")
  void a_batch_spans_every_agent() {
    insert(AGENT, 0, "mine", null);
    insert(OTHER, 0, "theirs", null);

    List<EffectStore.Attempted> attempted = attempt();

    assertThat(attempted).isNotEmpty();
    assertThat(attempted)
        .extracting(effect -> text(effect.payload()))
        .containsExactlyInAnyOrder("mine", "theirs");
  }

  @Test
  @DisplayName("a batch is capped at the requested size")
  void a_batch_is_capped() {
    for (int i = 0; i < 5; i++) {
      insert(AGENT, i, "effect-" + i, null);
    }

    assertThat(effects.attempt(TYPE, 2, Instant.now(), TIMEOUT)).hasSize(2);
  }

  @Test
  @DisplayName("an attempted effect is not due again while its watchdog is still live")
  void an_attempted_effect_is_not_immediately_due_again() {
    insert(AGENT, 0, "call-model", null);
    attempt();

    assertThat(attempt()).isEmpty();
  }

  @Test
  @DisplayName(
      "an attempted effect whose watchdog lapsed comes back, with attempts INCREMENTED -- the"
          + " lapsed watchdog IS the failure nobody else recorded")
  void a_lapsed_watchdog_is_attempted_again_with_attempts_incremented() {
    insert(AGENT, 0, "call-model", null);
    List<EffectStore.Attempted> first =
        effects.attempt(TYPE, 10, Instant.now(), Duration.ofMillis(-1));

    assertThat(first).extracting(EffectStore.Attempted::attempts).containsExactly(0);

    List<EffectStore.Attempted> second = attempt();

    assertThat(second).extracting(effect -> text(effect.payload())).containsExactly("call-model");
    assertThat(second)
        .as("C1: a row found still RUNNING past its watchdog counts as a failure on re-pickup")
        .extracting(EffectStore.Attempted::attempts)
        .containsExactly(1);
  }

  @Test
  @DisplayName("C1: a timed-out effect -- found RUNNING past its watchdog -- counts a failure")
  void a_timed_out_effect_counts_a_failure() {
    EffectId id = insertRunning(AGENT, 0, "call-model", Instant.now().minus(Duration.ofMinutes(5)));

    List<EffectStore.Attempted> retaken = attempt();

    assertThat(retaken).extracting(EffectStore.Attempted::id).containsExactly(id);
    assertThat(retaken).extracting(EffectStore.Attempted::attempts).containsExactly(1);
  }

  @Test
  @DisplayName(
      "C1: a row picked up from PENDING does not double-count its already-recorded failure")
  void a_pending_effect_does_not_double_count() {
    EffectId id = insertPendingWithAttempts(AGENT, 0, "call-model", 3);

    List<EffectStore.Attempted> taken = attempt();

    assertThat(taken).extracting(EffectStore.Attempted::id).containsExactly(id);
    assertThat(taken).extracting(EffectStore.Attempted::attempts).containsExactly(3);
  }

  @Test
  @DisplayName(
      "C1: a worker that keeps dying before it ever writes retry/abandon still exhausts the"
          + " RetryPolicy budget -- the loop C1 says cannot terminate, falsified")
  void a_repeatedly_timing_out_effect_reaches_giveUp() {
    insert(AGENT, 0, "call-model", null);
    RetryPolicy budget =
        RetryPolicy.exponential(Duration.ofMillis(1), 2.0, Duration.ofSeconds(1), 3);
    Random random = new Random(0);

    // Every pass "picks up" the row and simply abandons it where it lies -- no retry(), no
    // abandon() write-back -- exactly a worker whose JVM was killed mid-attempt, over and over.
    // Before C1's fix this loop runs forever: attempts never leaves 0, and RetryPolicy.decide(0,
    // ...) with maxAttempts=3 always answers RetryAfter, never GiveUp. A hard cap on the loop
    // count is what turns "hangs forever" into an assertable failure instead of an actual hang.
    int attemptsMade = 0;
    RetryPolicy.RetryDecision decision = null;
    for (int pass = 0; pass < 10; pass++) {
      List<EffectStore.Attempted> taken =
          effects.attempt(TYPE, 10, Instant.now(), Duration.ofMillis(-1));
      attemptsMade = taken.get(0).attempts();
      decision = budget.decide(attemptsMade, random);
      if (decision instanceof RetryPolicy.RetryDecision.GiveUp) {
        break;
      }
    }

    assertThat(attemptsMade).as("the failure count actually rose pass over pass").isEqualTo(3);
    assertThat(decision).isInstanceOf(RetryPolicy.RetryDecision.GiveUp.class);
  }

  @Test
  @DisplayName("two concurrent attempters never both take the same effect")
  void concurrent_attempts_never_double_attempt() throws Exception {
    // Repeated trials, each with enough rows that ONE attempter's transaction -- a SELECT plus
    // this many sequential take() UPDATEs -- stays open long enough for a genuinely concurrent
    // second attempter to run its own SELECT while the first is still mid-flight. A single
    // trial's overlap window is real but not guaranteed by any one run of the JVM's thread
    // scheduler; several trials make the chance of a broken (per-statement-autocommit) attempt()
    // passing by dumb luck negligible without making a single trial's pass/fail timing-dependent
    // on its own.
    for (int trial = 0; trial < TRIALS; trial++) {
      raceOneAttempt(AgentId.of("house-race-" + trial));
    }
  }

  private void raceOneAttempt(AgentId agent) throws Exception {
    int total = ROWS_PER_TRIAL;
    for (int i = 0; i < total; i++) {
      insert(agent, i, "effect-" + i, null);
    }

    CountDownLatch startGate = new CountDownLatch(1);
    ExecutorService threads = Executors.newFixedThreadPool(2);
    try {
      Future<List<EffectStore.Attempted>> first = threads.submit(() -> awaitAndAttempt(startGate));
      Future<List<EffectStore.Attempted>> second = threads.submit(() -> awaitAndAttempt(startGate));
      startGate.countDown();

      Set<EffectId> firstIds = idsOf(first.get(10, TimeUnit.SECONDS));
      Set<EffectId> secondIds = idsOf(second.get(10, TimeUnit.SECONDS));

      Set<EffectId> overlap = new HashSet<>(firstIds);
      overlap.retainAll(secondIds);

      assertThat(overlap).isEmpty();
      assertThat(firstIds.size() + secondIds.size()).isEqualTo(total);
    } finally {
      threads.shutdownNow();
      assertThat(threads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("a completed effect is gone for good")
  void completing_removes_it() {
    EffectId id = insert(AGENT, 0, "call-model", null);
    attempt();

    effects.complete(id);

    assertThat(payloadCount(id)).isZero();
  }

  @Test
  @DisplayName(
      "completing the same effect twice raises the second time -- nothing was left to discharge")
  void completing_twice_raises() {
    EffectId id = insert(AGENT, 0, "call-model", null);
    attempt();
    effects.complete(id);

    assertThatThrownBy(() -> effects.complete(id)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("completing an effect nobody ever attempted raises -- PENDING is not a discharge")
  void completing_an_unattempted_effect_raises() {
    EffectId id = insert(AGENT, 0, "call-model", null);

    assertThatThrownBy(() -> effects.complete(id)).isInstanceOf(IllegalStateException.class);

    // The row survives the failed discharge -- still there to be attempted and completed properly.
    assertThat(attempt())
        .extracting(effect -> text(effect.payload()))
        .containsExactly("call-model");
  }

  @Test
  @DisplayName("completing an id nobody ever inserted raises")
  void completing_an_unknown_id_raises() {
    assertThatThrownBy(() -> effects.complete(EffectId.next()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName(
      "retry reschedules a row, counts the failure, and it is due again once its time comes")
  void retry_reschedules_and_counts_the_failure() {
    EffectId id = insert(AGENT, 0, "call-model", null);
    attempt();

    effects.retry(id, Instant.now().plus(Duration.ofHours(1)));

    assertThat(attempt()).as("not due yet -- the backoff has not elapsed").isEmpty();
    assertThat(effects.attempt(TYPE, 10, Instant.now().plus(Duration.ofHours(2)), TIMEOUT))
        .as("due once the scheduled moment passes")
        .extracting(EffectStore.Attempted::attempts)
        .containsExactly(1);
  }

  @Test
  @DisplayName("abandon retires a row without discharging it, and it is never attempted again")
  void abandon_retires_it() {
    EffectId id = insert(AGENT, 0, "call-model", null);
    attempt();

    effects.abandon(id, "the model refused four times");

    assertThat(attempt()).isEmpty();
    assertThat(payloadOf(id)).isEqualTo("call-model");
  }

  @Test
  @DisplayName("abandon counts as a failure too, even though nothing consults the policy again")
  void abandon_counts_the_failure() {
    EffectId id = insert(AGENT, 0, "call-model", null);
    attempt();

    effects.abandon(id, "gave up");

    Integer attemptsAfter =
        JdbcClient.create(database)
            .sql("SELECT attempts FROM nessy_effect WHERE effect_id = ?")
            .param(id.value())
            .query(Integer.class)
            .single();
    assertThat(attemptsAfter).isEqualTo(1);
  }

  @Test
  @DisplayName("an effect with no turn yet round-trips a null turn id")
  void an_effect_with_no_turn_round_trips_null() {
    effects.insert(TYPE, AGENT, null, null, 0, bytes("take-work"), null);

    List<EffectStore.Attempted> attempted = attempt();

    assertThat(attempted).extracting(effect -> text(effect.payload())).containsExactly("take-work");
    assertThat(attempted).allMatch(effect -> effect.turnId() == null);
  }

  @Test
  @DisplayName("an effect naming a call round-trips its call id")
  void an_effect_with_a_call_round_trips_it() {
    CallId callId = CallId.of("call-1");
    insert(AGENT, 0, "ask-approver", callId);

    List<EffectStore.Attempted> attempted = attempt();

    assertThat(attempted).extracting(EffectStore.Attempted::callId).containsExactly(callId);
  }

  @Test
  @DisplayName("the trace context an effect was inserted with round-trips verbatim")
  void observability_round_trips() {
    String carrier = "{\"traceparent\":\"00-4bf92f-00f067-01\"}";
    effects.insert(TYPE, AGENT, TURN, null, 0, bytes("call-model"), carrier);

    List<EffectStore.Attempted> attempted = attempt();

    assertThat(attempted).extracting(EffectStore.Attempted::observability).containsExactly(carrier);
  }

  @Test
  @DisplayName("an effect inserted outside any trace carries no observability context")
  void observability_is_null_when_there_was_no_trace() {
    insert(AGENT, 0, "call-model", null);

    List<EffectStore.Attempted> attempted = attempt();

    assertThat(attempted).extracting(EffectStore.Attempted::payload).isNotEmpty();
    assertThat(attempted).allMatch(effect -> effect.observability() == null);
  }

  @Test
  @DisplayName(
      "deleting an agent's effects removes every one, pending or attempted, and leaves another"
          + " agent's alone")
  void delete_agent_removes_every_row_for_that_agent_only() {
    insert(AGENT, 0, "pending", null);
    EffectId attemptedId = insert(AGENT, 1, "attempted", null);
    attempt();
    insert(OTHER, 0, "theirs", null);

    effects.deleteAgent(TYPE, AGENT);

    assertThat(payloadCount(attemptedId)).as("the attempted row is gone too").isZero();
    assertThat(attempt())
        .as("a different agent's effects are untouched")
        .extracting(effect -> text(effect.payload()))
        .containsExactly("theirs");
  }

  @Test
  @DisplayName("a settled call discharges its own effect row by (type, agent, turn, call)")
  void delete_for_call_discharges_by_coordinates() {
    CallId callId = CallId.of("call-1");
    EffectId parked = insert(AGENT, 0, "ask-approver", callId);
    // Another agent's row naming the SAME call id must survive -- call ids are only unique within
    // one turn, never across agents.
    EffectId sameCallOtherAgent = insert(OTHER, 0, "ask-approver-other", callId);

    effects.deleteForCall(TYPE, AGENT, TURN, callId);

    assertThat(payloadCount(parked)).as("the named call's row is gone").isZero();
    assertThat(payloadCount(sameCallOtherAgent))
        .as("a different agent naming the same call id is untouched")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("parking a row that is not RUNNING reports no update and leaves it untouched")
  void parking_a_non_running_row_reports_false_and_changes_nothing() {
    CallId callId = CallId.of("call-1");
    // Freshly inserted, never attempt()-ed -- PENDING, not RUNNING. Unreachable through
    // AgentLogic/Transition today (ToolParked only ever answers a row already RUNNING), but the
    // contract has to hold anyway: a park racing a concurrent completion finds exactly this same
    // shape (the row gone RUNNING -> deleted, or already PARKED), and silently doing nothing there
    // is the bug this mechanism exists to remove.
    EffectId pending = insert(AGENT, 0, "ask-approver", callId);
    Instant originalActionableAt = actionableAtOf(pending);

    boolean parked =
        effects.park(TYPE, AGENT, TURN, callId, originalActionableAt.plus(Duration.ofDays(3)));

    assertThat(parked).as("no RUNNING row matched, so nothing was moved to PARKED").isFalse();
    assertThat(statusOf(pending)).as("still whatever it was, not PARKED").isEqualTo("PENDING");
    assertThat(actionableAtOf(pending))
        .as("untouched -- the row's short, original deadline still stands")
        .isEqualTo(originalActionableAt);
  }

  private String statusOf(EffectId id) {
    return JdbcClient.create(database)
        .sql("SELECT status FROM nessy_effect WHERE effect_id = ?")
        .param(id.value())
        .query(String.class)
        .single();
  }

  private Instant actionableAtOf(EffectId id) {
    return JdbcClient.create(database)
        .sql("SELECT actionable_at FROM nessy_effect WHERE effect_id = ?")
        .param(id.value())
        .query(java.sql.Timestamp.class)
        .single()
        .toInstant();
  }

  private int payloadCount(EffectId id) {
    Integer rows =
        JdbcClient.create(database)
            .sql("SELECT count(*) FROM nessy_effect WHERE effect_id = ?")
            .param(id.value())
            .query(Integer.class)
            .single();
    return rows == null ? 0 : rows;
  }

  private List<EffectStore.Attempted> awaitAndAttempt(CountDownLatch startGate)
      throws InterruptedException {
    startGate.await();
    return effects.attempt(TYPE, ROWS_PER_TRIAL, Instant.now(), TIMEOUT);
  }

  private static Set<EffectId> idsOf(List<EffectStore.Attempted> attempted) {
    return attempted.stream().map(EffectStore.Attempted::id).collect(Collectors.toSet());
  }

  private String payloadOf(EffectId id) {
    byte[] payload =
        JdbcClient.create(database)
            .sql("SELECT payload FROM nessy_effect WHERE effect_id = ?")
            .param(id.value())
            .query(byte[].class)
            .single();
    return text(payload);
  }

  private EffectId insert(AgentId agentId, int ordinal, String payload, CallId callId) {
    return effects.insert(TYPE, agentId, TURN, callId, ordinal, bytes(payload), null);
  }

  /**
   * A row planted directly as {@code RUNNING} with a watchdog already in the past -- the shape of a
   * worker that died mid-attempt, which {@link EffectStore#insert} has no way to produce (it always
   * starts a row {@code PENDING}). Raw SQL, deliberately: this is simulating a crash, not
   * exercising the store's own API for getting a row into this state.
   */
  private EffectId insertRunning(AgentId agentId, int ordinal, String payload, Instant watchdogAt) {
    EffectId id = EffectId.next();
    Instant now = Instant.now();
    JdbcClient.create(database)
        .sql(
            "INSERT INTO nessy_effect (effect_id, agent_type, agent_id, turn_id, call_id, ordinal,"
                + " payload, observability, status, attempts, actionable_at, created_at) VALUES (?,"
                + " ?, ?, ?, NULL, ?, ?, NULL, 'RUNNING', 0, ?, ?)")
        .param(id.value())
        .param(TYPE.name())
        .param(agentId.value())
        .param(TURN.value())
        .param(ordinal)
        .param(bytes(payload))
        .param(watchdogAt)
        .param(now)
        .update();
    return id;
  }

  /**
   * A row planted directly {@code PENDING} with a chosen {@code attempts}, for C1's double-count
   * test.
   */
  private EffectId insertPendingWithAttempts(
      AgentId agentId, int ordinal, String payload, int attempts) {
    EffectId id = EffectId.next();
    Instant now = Instant.now();
    JdbcClient.create(database)
        .sql(
            "INSERT INTO nessy_effect (effect_id, agent_type, agent_id, turn_id, call_id, ordinal,"
                + " payload, observability, status, attempts, actionable_at, created_at) VALUES (?,"
                + " ?, ?, ?, NULL, ?, ?, NULL, 'PENDING', ?, ?, ?)")
        .param(id.value())
        .param(TYPE.name())
        .param(agentId.value())
        .param(TURN.value())
        .param(ordinal)
        .param(bytes(payload))
        .param(attempts)
        .param(now)
        .param(now)
        .update();
    return id;
  }

  private List<EffectStore.Attempted> attempt() {
    return effects.attempt(TYPE, 100, Instant.now(), TIMEOUT);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String text(byte[] value) {
    return new String(value, StandardCharsets.UTF_8);
  }
}
