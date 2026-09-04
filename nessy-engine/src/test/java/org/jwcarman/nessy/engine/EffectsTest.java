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
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.testing.TestDatabase;
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
  @DisplayName("a failed effect stops being work and stops being reaped")
  void failing_retires_it() {
    EffectId id = effects.insert(TYPE, AGENT, TURN, 0, "call-model");
    effects.claim(TYPE, AGENT, Instant.now().minus(Duration.ofSeconds(1)));

    effects.fail(id, "the model refused four times");

    assertThat(effects.claimExpired(TYPE, Instant.now(), 10)).isEmpty();
    assertThat(effects.claim(TYPE, AGENT, SOON)).isEmpty();
  }

  @Test
  @DisplayName("an effect with no turn yet round-trips a null turn id")
  void an_effect_with_no_turn_round_trips_null() {
    effects.insert(TYPE, AGENT, null, 0, "take-work");

    List<Effects.Claimed> claimed = effects.claim(TYPE, AGENT, SOON);

    assertThat(claimed).extracting(Effects.Claimed::payload).containsExactly("take-work");
    assertThat(claimed).allMatch(effect -> effect.turnId() == null);
  }
}
