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
 * in DECISION order, because a decision's instructions are ordered and running them shuffled writes
 * an empty exchange. Claiming twice hands nothing back the second time, because at-least-once is
 * the contract and at-least-twice-immediately is not. And an expired row comes back to whoever
 * asks, because the node that claimed it is the one that died.
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
  private Effects effects;

  @BeforeEach
  void fresh() {
    database = TestDatabase.fresh();
    effects = new Effects(database);
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
    effects.insert(TYPE, AGENT, TURN, 1, "release");
    effects.insert(TYPE, AGENT, TURN, 0, "remember");

    List<Effects.Claimed> claimed = effects.claim(TYPE, AGENT, SOON);

    assertThat(claimed).extracting(Effects.Claimed::payload).containsExactly("remember", "release");
  }

  @Test
  @DisplayName("one agent's claim never takes another agent's work")
  void claims_are_per_agent() {
    effects.insert(TYPE, AGENT, TURN, 0, "mine");
    effects.insert(TYPE, OTHER, TURN, 0, "theirs");

    List<Effects.Claimed> claimed = effects.claim(TYPE, AGENT, SOON);

    assertThat(claimed).isNotEmpty();
    assertThat(claimed).allMatch(effect -> "mine".equals(effect.payload()));
  }

  @Test
  @DisplayName("a claimed effect is not claimed again")
  void claiming_is_exclusive() {
    effects.insert(TYPE, AGENT, TURN, 0, "call-model");
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
      effects.insert(TYPE, agent, TURN, i, "effect-" + i);
    }

    CountDownLatch startGate = new CountDownLatch(1);
    ExecutorService threads = Executors.newFixedThreadPool(2);
    try {
      Future<List<Effects.Claimed>> first = threads.submit(() -> awaitAndClaim(agent, startGate));
      Future<List<Effects.Claimed>> second = threads.submit(() -> awaitAndClaim(agent, startGate));
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
    EffectId id = effects.insert(TYPE, AGENT, TURN, 0, "call-model");
    effects.claim(TYPE, AGENT, SOON);

    effects.complete(id);

    assertThat(effects.claimExpired(TYPE, Instant.now().plus(Duration.ofHours(1)), 10)).isEmpty();
  }

  @Test
  @DisplayName("an effect whose watchdog expired comes back, with its attempt counted")
  void an_expired_effect_is_reclaimable() {
    effects.insert(TYPE, AGENT, TURN, 0, "call-model");
    effects.claim(TYPE, AGENT, Instant.now().minus(Duration.ofSeconds(1)));

    List<Effects.Claimed> expired = effects.claimExpired(TYPE, Instant.now(), 10);

    assertThat(expired).extracting(Effects.Claimed::payload).containsExactly("call-model");
    assertThat(expired).allMatch(effect -> effect.attempts() == 2);
  }

  @Test
  @DisplayName("an effect whose watchdog has not expired stays put")
  void a_live_effect_is_not_reaped() {
    effects.insert(TYPE, AGENT, TURN, 0, "call-model");
    effects.claim(TYPE, AGENT, SOON);

    assertThat(effects.claimExpired(TYPE, Instant.now(), 10)).isEmpty();
  }

  @Test
  @DisplayName("a failed effect stops being work, stops being reaped, and keeps its payload")
  void failing_retires_it() {
    EffectId id = effects.insert(TYPE, AGENT, TURN, 0, "call-model");
    effects.claim(TYPE, AGENT, Instant.now().minus(Duration.ofSeconds(1)));

    effects.fail(id, "the model refused four times");

    assertThat(effects.claimExpired(TYPE, Instant.now(), 10)).isEmpty();
    assertThat(effects.claim(TYPE, AGENT, SOON)).isEmpty();
    assertThat(payloadOf(id)).isEqualTo("call-model");
  }

  @Test
  @DisplayName("an effect with no turn yet round-trips a null turn id")
  void an_effect_with_no_turn_round_trips_null() {
    effects.insert(TYPE, AGENT, null, 0, "take-work");

    List<Effects.Claimed> claimed = effects.claim(TYPE, AGENT, SOON);

    assertThat(claimed).extracting(Effects.Claimed::payload).containsExactly("take-work");
    assertThat(claimed).allMatch(effect -> effect.turnId() == null);
  }

  private List<Effects.Claimed> awaitAndClaim(AgentId agent, CountDownLatch startGate)
      throws InterruptedException {
    startGate.await();
    return effects.claim(TYPE, agent, SOON);
  }

  private static Set<EffectId> idsOf(List<Effects.Claimed> claimed) {
    return claimed.stream().map(Effects.Claimed::id).collect(Collectors.toSet());
  }

  private String payloadOf(EffectId id) {
    return JdbcClient.create(database)
        .sql("SELECT payload FROM nessy_effect WHERE effect_id = ?")
        .param(id.value())
        .query(String.class)
        .single();
  }
}
