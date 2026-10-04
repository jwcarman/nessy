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
package org.jwcarman.nessy.backend.inmemory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.Attempt;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.effect.Effects;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Parking an effect held in this process")
class InMemoryEffectsParkTest {

  private static final AgentType TYPE = new AgentType("parked");
  private static final Instant START = Instant.parse("2026-10-03T00:00:00Z");
  private static final Duration TIMEOUT = Duration.ofMinutes(1);
  private static final Instant DEADLINE = START.plus(Duration.ofHours(1));

  private final Effects effects =
      new InMemoryEffects(new JacksonCodecFactory(JsonMapper.builder().build()));
  private final AgentId agent = AgentId.random();

  private Attempt claimedEffect() {
    effects.insert(
        TYPE,
        agent,
        new AgentEffect.Infer(new TurnId(1)),
        TIMEOUT,
        new EffectOutcome.InferenceRefused("undispatchable", Usage.unreported()),
        DEADLINE,
        null,
        START);
    return effects.markRunning(TYPE, START, 1).getFirst();
  }

  @Nested
  @DisplayName("A running row")
  class ARunningRow {

    @Test
    void a_parked_row_is_due_at_its_deadline() {
      Attempt attempt = claimedEffect();

      boolean parked = effects.park(attempt.effectId(), attempt.attemptsMade(), START);

      assertThat(parked).isTrue();
      assertThat(effects.markRunning(TYPE, DEADLINE.minusMillis(1), 1)).isEmpty();
      assertThat(effects.markRunning(TYPE, DEADLINE, 1)).hasSize(1);
    }

    @Test
    void an_unparked_claim_is_taken_again_after_its_timeout() {
      claimedEffect();

      List<Attempt> again = effects.markRunning(TYPE, START.plus(TIMEOUT).plusSeconds(1), 1);

      assertThat(again).hasSize(1);
    }

    @Test
    void a_parked_row_is_not_claimed_before_its_deadline_when_the_claimers_clock_is_behind() {
      Attempt attempt = claimedEffect();
      effects.park(attempt.effectId(), attempt.attemptsMade(), START.plusSeconds(5));

      List<Attempt> afterTimeout = effects.markRunning(TYPE, START.plus(TIMEOUT).plusSeconds(1), 1);
      List<Attempt> beforeDeadline = effects.markRunning(TYPE, DEADLINE.minusSeconds(1), 1);

      assertThat(afterTimeout).isEmpty();
      assertThat(beforeDeadline).isEmpty();
    }

    @Test
    void a_parked_row_is_claimed_at_its_deadline() {
      Attempt attempt = claimedEffect();
      effects.park(attempt.effectId(), attempt.attemptsMade(), START);

      List<Attempt> atDeadline = effects.markRunning(TYPE, DEADLINE, 1);

      assertThat(atDeadline).extracting(Attempt::effectId).containsExactly(attempt.effectId());
      assertThat(atDeadline.getFirst().attemptsMade()).isEqualTo(2);
    }

    @Test
    void a_parked_row_is_still_found_running() {
      Attempt attempt = claimedEffect();
      effects.park(attempt.effectId(), attempt.attemptsMade(), START);

      List<Attempt> running = effects.runningFor(TYPE, agent);

      assertThat(running).extracting(Attempt::effectId).containsExactly(attempt.effectId());
    }

    @Test
    void a_parked_row_is_retired_as_any_other() {
      Attempt attempt = claimedEffect();
      effects.park(attempt.effectId(), attempt.attemptsMade(), START);

      boolean retired = effects.complete(attempt.effectId(), attempt.attemptsMade());

      assertThat(retired).isTrue();
      assertThat(effects.markRunning(TYPE, DEADLINE, 1)).isEmpty();
    }

    @Test
    void parking_under_another_attempts_number_marks_nothing() {
      Attempt attempt = claimedEffect();

      boolean parked = effects.park(attempt.effectId(), attempt.attemptsMade() + 1, START);

      assertThat(parked).isFalse();
      assertThat(effects.markRunning(TYPE, START.plus(TIMEOUT).plusSeconds(1), 1)).hasSize(1);
    }
  }

  @Nested
  @DisplayName("A row that is not running")
  class ARowThatIsNotRunning {

    @Test
    void parking_a_row_that_was_settled_marks_nothing() {
      Attempt attempt = claimedEffect();
      effects.complete(attempt.effectId(), attempt.attemptsMade());

      boolean parked = effects.park(attempt.effectId(), attempt.attemptsMade(), START);

      assertThat(parked).isFalse();
    }

    @Test
    void parking_a_pending_row_marks_nothing() {
      Attempt attempt = claimedEffect();
      effects.reschedule(attempt.effectId(), attempt.attemptsMade(), START, List.of());

      boolean parked = effects.park(attempt.effectId(), attempt.attemptsMade(), START);

      assertThat(parked).isFalse();
    }
  }
}
