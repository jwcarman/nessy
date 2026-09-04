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
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.testing.TestDatabase;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

/**
 * A durable obligation.
 *
 * <p>Three properties, and none of them is "a row goes in and comes out". Claiming hands work back
 * in DECISION order, because a decision's effects are ordered and running them shuffled writes an
 * empty exchange. Claiming twice hands nothing back the second time, because at-least-once is the
 * contract and at-least-twice-immediately is not. And an expired row comes back to whoever asks,
 * because the node that claimed it is the one that died.
 */
@DisplayName("A durable effect")
class EffectsTest {

  private static final AgentType TYPE = AgentType.of("watchman");
  private static final AgentId AGENT = AgentId.of("house-1");
  private static final AgentId OTHER = AgentId.of("house-2");
  private static final TurnId TURN = TurnId.of("turn-1");
  private static final Instant SOON = Instant.now().plus(Duration.ofMinutes(1));

  // See concurrent_claims_never_double_claim: trial count and row count both widen the chance a
  // real overlap is caught, without making any single trial's own pass/fail timing-dependent.
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
  @DisplayName("an agent with nothing outstanding claims nothing")
  void nothing_pending_claims_nothing() {
    assertThat(effects.claim(TYPE, AGENT, SOON)).isEmpty();
  }

  @Test
  @DisplayName("claiming returns this agent's work in decision order")
  void claims_come_back_in_ordinal_order() {
    effects.insert(TYPE, AGENT, TURN, 1, bytes("release"), null);
    effects.insert(TYPE, AGENT, TURN, 0, bytes("remember"), null);

    List<EffectStore.Claimed> claimed = effects.claim(TYPE, AGENT, SOON);

    assertThat(claimed)
        .extracting(effect -> text(effect.payload()))
        .containsExactly("remember", "release");
  }

  @Test
  @DisplayName("one agent's claim never takes another agent's work")
  void claims_are_per_agent() {
    effects.insert(TYPE, AGENT, TURN, 0, bytes("mine"), null);
    effects.insert(TYPE, OTHER, TURN, 0, bytes("theirs"), null);

    List<EffectStore.Claimed> claimed = effects.claim(TYPE, AGENT, SOON);

    assertThat(claimed).isNotEmpty();
    assertThat(claimed).allMatch(effect -> "mine".equals(text(effect.payload())));
  }

  @Test
  @DisplayName("a claimed effect is not claimed again")
  void claiming_is_exclusive() {
    effects.insert(TYPE, AGENT, TURN, 0, bytes("call-model"), null);
    effects.claim(TYPE, AGENT, SOON);

    assertThat(effects.claim(TYPE, AGENT, SOON)).isEmpty();
  }

  @Test
  @DisplayName("two concurrent claimers never both take the same effect")
  void concurrent_claims_never_double_claim() throws Exception {
    // Repeated trials, each with enough rows that ONE claimer's transaction -- a SELECT plus this
    // many sequential take() UPDATEs -- stays open long enough for a genuinely concurrent second
    // claimer to run its own SELECT while the first is still mid-flight. A single trial's overlap
    // window is real but not guaranteed by any one run of the JVM's thread scheduler; several
    // trials make the chance of a broken (per-statement-autocommit) claim() passing by dumb luck
    // negligible without making a single trial's pass/fail timing-dependent on its own.
    for (int trial = 0; trial < TRIALS; trial++) {
      raceOneClaim(AgentId.of("house-race-" + trial));
    }
  }

  private void raceOneClaim(AgentId agent) throws Exception {
    int total = ROWS_PER_TRIAL;
    for (int i = 0; i < total; i++) {
      effects.insert(TYPE, agent, TURN, i, bytes("effect-" + i), null);
    }

    CountDownLatch startGate = new CountDownLatch(1);
    ExecutorService threads = Executors.newFixedThreadPool(2);
    try {
      Future<List<EffectStore.Claimed>> first =
          threads.submit(() -> awaitAndClaim(agent, startGate));
      Future<List<EffectStore.Claimed>> second =
          threads.submit(() -> awaitAndClaim(agent, startGate));
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
    EffectId id = effects.insert(TYPE, AGENT, TURN, 0, bytes("call-model"), null);
    effects.claim(TYPE, AGENT, SOON);

    effects.complete(id);

    assertThat(effects.claimExpired(TYPE, Instant.now().plus(Duration.ofHours(1)), 10)).isEmpty();
  }

  @Test
  @DisplayName(
      "completing the same effect twice raises the second time -- nothing was left to discharge")
  void completing_twice_raises() {
    EffectId id = effects.insert(TYPE, AGENT, TURN, 0, bytes("call-model"), null);
    effects.claim(TYPE, AGENT, SOON);
    effects.complete(id);

    assertThatThrownBy(() -> effects.complete(id)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("completing an effect nobody ever claimed raises -- PENDING is not a discharge")
  void completing_an_unclaimed_effect_raises() {
    EffectId id = effects.insert(TYPE, AGENT, TURN, 0, bytes("call-model"), null);

    assertThatThrownBy(() -> effects.complete(id)).isInstanceOf(IllegalStateException.class);

    // The row survives the failed discharge -- still there to be claimed and completed properly.
    assertThat(effects.claim(TYPE, AGENT, SOON))
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
  @DisplayName("an effect whose watchdog expired comes back, with its attempt counted")
  void an_expired_effect_is_reclaimable() {
    effects.insert(TYPE, AGENT, TURN, 0, bytes("call-model"), null);
    effects.claim(TYPE, AGENT, Instant.now().minus(Duration.ofSeconds(1)));

    List<EffectStore.Claimed> expired = effects.claimExpired(TYPE, Instant.now(), 10);

    assertThat(expired).extracting(effect -> text(effect.payload())).containsExactly("call-model");
    assertThat(expired).allMatch(effect -> effect.attempts() == 2);
  }

  @Test
  @DisplayName("an effect whose watchdog has not expired stays put")
  void a_live_effect_is_not_reaped() {
    effects.insert(TYPE, AGENT, TURN, 0, bytes("call-model"), null);
    effects.claim(TYPE, AGENT, SOON);

    assertThat(effects.claimExpired(TYPE, Instant.now(), 10)).isEmpty();
  }

  @Test
  @DisplayName("a failed effect stops being work, stops being reaped, and keeps its payload")
  void failing_retires_it() {
    EffectId id = effects.insert(TYPE, AGENT, TURN, 0, bytes("call-model"), null);
    effects.claim(TYPE, AGENT, Instant.now().minus(Duration.ofSeconds(1)));

    effects.fail(id, "the model refused four times");

    assertThat(effects.claimExpired(TYPE, Instant.now(), 10)).isEmpty();
    assertThat(effects.claim(TYPE, AGENT, SOON)).isEmpty();
    assertThat(payloadOf(id)).isEqualTo("call-model");
  }

  @Test
  @DisplayName("an effect with no turn yet round-trips a null turn id")
  void an_effect_with_no_turn_round_trips_null() {
    effects.insert(TYPE, AGENT, null, 0, bytes("take-work"), null);

    List<EffectStore.Claimed> claimed = effects.claim(TYPE, AGENT, SOON);

    assertThat(claimed).extracting(effect -> text(effect.payload())).containsExactly("take-work");
    assertThat(claimed).allMatch(effect -> effect.turnId() == null);
  }

  @Test
  @DisplayName("the trace context an effect was inserted with round-trips verbatim")
  void observability_round_trips() {
    String carrier = "{\"traceparent\":\"00-4bf92f-00f067-01\"}";
    effects.insert(TYPE, AGENT, TURN, 0, bytes("call-model"), carrier);

    List<EffectStore.Claimed> claimed = effects.claim(TYPE, AGENT, SOON);

    assertThat(claimed).extracting(EffectStore.Claimed::observability).containsExactly(carrier);
  }

  @Test
  @DisplayName("an effect inserted outside any trace carries no observability context")
  void observability_is_null_when_there_was_no_trace() {
    effects.insert(TYPE, AGENT, TURN, 0, bytes("call-model"), null);

    List<EffectStore.Claimed> claimed = effects.claim(TYPE, AGENT, SOON);

    assertThat(claimed).extracting(EffectStore.Claimed::payload).isNotEmpty();
    assertThat(claimed).allMatch(effect -> effect.observability() == null);
  }

  @Test
  @DisplayName(
      "deleting an agent's effects removes every one, pending or claimed, and leaves another agent's alone")
  void delete_agent_removes_every_row_for_that_agent_only() {
    effects.insert(TYPE, AGENT, TURN, 0, bytes("pending"), null);
    EffectId claimed = effects.insert(TYPE, AGENT, TURN, 1, bytes("claimed"), null);
    effects.claim(TYPE, AGENT, SOON);
    effects.insert(TYPE, OTHER, TURN, 0, bytes("theirs"), null);

    effects.deleteAgent(TYPE, AGENT);

    assertThat(effects.claim(TYPE, AGENT, SOON)).isEmpty();
    assertThat(effects.claimExpired(TYPE, Instant.now().plus(Duration.ofHours(1)), 10)).isEmpty();
    assertThat(payloadCount(claimed)).as("the claimed row is gone too").isZero();
    assertThat(effects.claim(TYPE, OTHER, SOON))
        .as("a different agent's effects are untouched")
        .extracting(effect -> text(effect.payload()))
        .containsExactly("theirs");
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

  private List<EffectStore.Claimed> awaitAndClaim(AgentId agent, CountDownLatch startGate)
      throws InterruptedException {
    startGate.await();
    return effects.claim(TYPE, agent, SOON);
  }

  private static Set<EffectId> idsOf(List<EffectStore.Claimed> claimed) {
    return claimed.stream().map(EffectStore.Claimed::id).collect(Collectors.toSet());
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

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String text(byte[] value) {
    return new String(value, StandardCharsets.UTF_8);
  }
}
