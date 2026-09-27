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
package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The bound on a turn that would otherwise go round forever. */
@DisplayName("TurnPolicy")
class TurnPolicyTest {

  private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

  private static TurnStats afterCalls(int calls) {
    TurnStats stats = TurnStats.opened(NOW);
    for (int i = 0; i < calls; i++) {
      stats = stats.answered(Usage.of("a-model", 1, 1));
    }
    return stats;
  }

  @Nested
  @DisplayName("a bound on model calls")
  class Calls {

    @Test
    @DisplayName("a turn short of the first threshold is left alone")
    void under_the_threshold_carries_on() {
      assertThat(TurnPolicy.calls(20, 25).decide(afterCalls(19), NOW))
          .isInstanceOf(TurnDecision.Continue.class);
    }

    @Test
    @DisplayName("at the first threshold the model is asked to answer, not stopped")
    void at_the_first_threshold_it_answers() {
      assertThat(TurnPolicy.calls(20, 25).decide(afterCalls(20), NOW))
          .as("a long honest turn keeps its work and returns an answer")
          .isInstanceOf(TurnDecision.AnswerNow.class);
    }

    @Test
    @DisplayName("past the first threshold it still answers rather than skipping to the end")
    void past_the_first_threshold_it_still_answers() {
      assertThat(TurnPolicy.calls(20, 25).decide(afterCalls(24), NOW))
          .isInstanceOf(TurnDecision.AnswerNow.class);
    }

    @Test
    @DisplayName("at the second threshold the turn ends, and says why")
    void at_the_second_threshold_it_fails() {
      assertThat(TurnPolicy.calls(20, 25).decide(afterCalls(25), NOW))
          .asInstanceOf(
              org.assertj.core.api.InstanceOfAssertFactories.type(TurnDecision.FailTurn.class))
          .extracting(TurnDecision.FailTurn::reason)
          .asString()
          .contains("25");
    }

    @Test
    @DisplayName("a count that jumped past a threshold still fires, because it reads at-or-past")
    void a_threshold_is_not_an_equality() {
      // A tool completing moves a turn on without calling the model, so the count the policy sees
      // does not advance by one every time it is asked.
      assertThat(TurnPolicy.calls(20, 25).decide(afterCalls(40), NOW))
          .isInstanceOf(TurnDecision.FailTurn.class);
    }

    @Test
    @DisplayName("thresholds the wrong way round are refused where they are written")
    void thresholds_must_be_in_order() {
      assertThatThrownBy(() -> TurnPolicy.calls(25, 20))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("past answerAt");
    }

    @Test
    @DisplayName("a threshold below one call would end a turn before it began")
    void the_first_threshold_must_allow_a_call() {
      assertThatThrownBy(() -> TurnPolicy.calls(0, 5))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("at least one call");
    }
  }

  @Test
  @DisplayName("an unbounded turn is never interrupted, however long it runs")
  void unbounded_never_interrupts() {
    assertThat(TurnPolicy.unbounded().decide(afterCalls(500), NOW))
        .isInstanceOf(TurnDecision.Continue.class);
  }

  @Test
  @DisplayName("anything else is a lambda, which is the point of the shape")
  void a_policy_can_bound_whatever_it_likes() {
    TurnPolicy onSpend =
        (stats, now) ->
            stats.spent().orZero() > 100
                ? new TurnDecision.FailTurn("too expensive")
                : new TurnDecision.Continue();

    assertThat(onSpend.decide(afterCalls(60), NOW)).isInstanceOf(TurnDecision.FailTurn.class);
    assertThat(onSpend.decide(afterCalls(2), NOW)).isInstanceOf(TurnDecision.Continue.class);
  }
}
