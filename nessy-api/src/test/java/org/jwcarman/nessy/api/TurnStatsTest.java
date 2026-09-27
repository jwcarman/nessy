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

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** What a turn has done so far, which is what a policy decides on. */
@DisplayName("TurnStats")
class TurnStatsTest {

  private static final Instant OPENED = Instant.parse("2026-09-27T12:00:00Z");

  @Test
  @DisplayName("a turn that has just opened has done nothing")
  void an_opened_turn_is_empty() {
    TurnStats stats = TurnStats.opened(OPENED);

    assertThat(stats.modelCalls()).isZero();
    assertThat(stats.toolCalls()).isZero();
    assertThat(stats.failedAttempts()).isZero();
    assertThat(stats.spent()).isEqualTo(Tokens.none());
    assertThat(stats.wasted()).isEqualTo(Tokens.none());
  }

  @Nested
  @DisplayName("counting")
  class Counting {

    @Test
    @DisplayName("a call that answered is a call, and what it cost is spent")
    void an_answer_counts_and_costs() {
      TurnStats stats = TurnStats.opened(OPENED).answered(Usage.of("a-model", 100, 20));

      assertThat(stats.modelCalls()).isEqualTo(1);
      assertThat(stats.failedAttempts()).as("it did not fail").isZero();
      assertThat(stats.spent()).isEqualTo(Tokens.of(120));
      assertThat(stats.wasted()).isEqualTo(Tokens.none());
    }

    @Test
    @DisplayName("a call that failed is a call too, and what it cost is also wasted")
    void a_failure_counts_in_both() {
      TurnStats stats = TurnStats.opened(OPENED).failed(Usage.of("a-model", 40, 0));

      assertThat(stats.modelCalls())
          .as("someone was asked; a request crossed the wire and a rate limit was consumed")
          .isEqualTo(1);
      assertThat(stats.failedAttempts()).isEqualTo(1);
      assertThat(stats.spent())
          .as("spent is everything, not what it bought")
          .isEqualTo(Tokens.of(40));
      assertThat(stats.wasted()).isEqualTo(Tokens.of(40));
    }

    @Test
    @DisplayName("tool calls are counted where the model asks for them")
    void tools_are_counted_when_asked_for() {
      TurnStats stats = TurnStats.opened(OPENED).requestedActions(3, Usage.of("a-model", 10, 5));

      assertThat(stats.toolCalls()).isEqualTo(3);
      assertThat(stats.modelCalls())
          .as("asking for tools is an answer from the model")
          .isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("reading a part out of the whole")
  class Derived {

    @Test
    @DisplayName("what the spending bought is the total less what was wasted")
    void productive_is_what_is_left() {
      TurnStats stats =
          TurnStats.opened(OPENED)
              .failed(Usage.of("a-model", 40, 0))
              .answered(Usage.of("a-model", 100, 20));

      assertThat(stats.modelCalls()).isEqualTo(2);
      assertThat(stats.spent()).isEqualTo(Tokens.of(160));
      assertThat(stats.productiveCalls()).isEqualTo(1);
      assertThat(stats.productiveTokens()).isEqualTo(Tokens.of(120));
    }

    @Test
    @DisplayName("a turn nobody counted reports no spend rather than none spent")
    void an_uncounted_turn_stays_uncounted() {
      TurnStats stats = TurnStats.opened(OPENED).answered(Usage.unreported());

      assertThat(stats.modelCalls()).as("the call happened even if nobody counted it").isEqualTo(1);
      assertThat(stats.spent().counted()).isFalse();
      assertThat(stats.productiveTokens().counted()).isFalse();
    }

    @Test
    @DisplayName("elapsed is worked out against a clock, never carried")
    void elapsed_is_derived() {
      TurnStats stats = TurnStats.opened(OPENED);

      assertThat(stats.elapsed(OPENED.plusSeconds(90))).isEqualTo(Duration.ofSeconds(90));
    }
  }
}
