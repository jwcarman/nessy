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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.support.TestClock;

/** The §6.1 judgment, named: {@link StalenessPolicy} owns time so the shell never reads a clock. */
class StalenessPolicyTest {

  private static final Instant T0 = Instant.parse("2026-08-20T12:00:00Z");
  private static final Duration THRESHOLD = Duration.ofMinutes(5);

  @Test
  void aPhaseAgedLessThanTheThresholdIsNotStale() {
    var clock = new TestClock(T0);
    var policy = StalenessPolicy.after(THRESHOLD, clock);
    clock.advance(THRESHOLD.minusSeconds(1));
    assertThat(policy.isStale(new Phase.AwaitingModel(), T0)).isFalse();
  }

  @Test
  void aPhaseAgedExactlyTheThresholdIsStale() {
    // Pins the inclusive boundary (>=): exactly-at-threshold must count as stale.
    var clock = new TestClock(T0);
    var policy = StalenessPolicy.after(THRESHOLD, clock);
    clock.advance(THRESHOLD);
    assertThat(policy.isStale(new Phase.AwaitingModel(), T0)).isTrue();
  }

  @Test
  void aPhaseAgedPastTheThresholdIsStale() {
    var clock = new TestClock(T0);
    var policy = StalenessPolicy.after(THRESHOLD, clock);
    clock.advance(THRESHOLD.plusMinutes(1));
    assertThat(policy.isStale(new Phase.AwaitingModel(), T0)).isTrue();
  }

  @Test
  void neverIsNeverStaleNoMatterHowMuchTimeHasPassed() {
    var policy = StalenessPolicy.never();
    assertThat(policy.isStale(new Phase.AwaitingModel(), Instant.EPOCH)).isFalse();
  }

  @Test
  void thePhaseGivenToTheJudgmentIsThePhaseTheCallerPassed() {
    var clock = new TestClock(T0);
    var seen = new Phase[1];
    StalenessPolicy recording =
        (phase, lastSaved) -> {
          seen[0] = phase;
          return false;
        };
    var phase = new Phase.AwaitingModel();
    recording.isStale(phase, clock.instant());
    assertThat(seen[0]).isSameAs(phase);
  }

  @Test
  void afterWithoutAnExplicitClockUsesSystemUtc() {
    var policy = StalenessPolicy.after(Duration.ofMillis(1));
    assertThat(policy.isStale(new Phase.AwaitingModel(), Instant.now().minusSeconds(1))).isTrue();
  }

  @Test
  void aNegativeThresholdIsRejected() {
    var clock = new TestClock(T0);
    var negative = Duration.ofSeconds(-1);
    assertThatThrownBy(() -> StalenessPolicy.after(negative, clock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("threshold must not be negative");
  }

  @Test
  void aZeroThresholdIsLegalAndMakesEveryPhaseImmediatelyStale() {
    var clock = new TestClock(T0);
    var policy = StalenessPolicy.after(Duration.ZERO, clock);
    assertThat(policy.isStale(new Phase.AwaitingModel(), T0)).isTrue();
  }
}
