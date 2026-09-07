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

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How eagerly the poller comes back.
 *
 * <p>Zero-jitter schedules ({@code jitterFraction = 0.0}) isolate the backoff PROGRESSION from
 * random noise, so those assertions are exact equalities rather than ranges. The one jitter
 * assertion uses a SEEDED {@link Random} and a value computed independently by hand from the
 * documented formula, not copied from a run of the production code -- see {@code RetryPolicyTest}
 * for why that distinction matters.
 */
@DisplayName("A poll schedule")
class PollScheduleTest {

  @Test
  @DisplayName("finding work tightens straight back to the floor, even from a stretched interval")
  void finding_work_returns_to_the_floor() {
    PollSchedule schedule =
        new PollSchedule(Duration.ofMillis(100), Duration.ofSeconds(10), 2.0, 0.0, new Random(1));
    schedule.next(0); // stretches current to 200ms
    schedule.next(0); // stretches current to 400ms

    Duration next = schedule.next(5);

    assertThat(next).isEqualTo(Duration.ofMillis(100));
  }

  @Test
  @DisplayName("an idle pass doubles the interval each time, capped at the ceiling")
  void idle_passes_back_off_geometrically_up_to_the_ceiling() {
    PollSchedule schedule =
        new PollSchedule(Duration.ofMillis(100), Duration.ofSeconds(1), 2.0, 0.0, new Random(1));

    assertThat(schedule.next(0)).isEqualTo(Duration.ofMillis(200));
    assertThat(schedule.next(0)).isEqualTo(Duration.ofMillis(400));
    assertThat(schedule.next(0)).isEqualTo(Duration.ofMillis(800));
    assertThat(schedule.next(0)).isEqualTo(Duration.ofSeconds(1)); // 1600ms capped at 1000ms
    assertThat(schedule.next(0))
        .as("stays pinned at the ceiling, does not keep climbing past it")
        .isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  @DisplayName("jitter perturbs the returned interval by the documented formula, pinned by seed")
  void jitter_is_pinned_to_the_documented_formula() {
    PollSchedule schedule =
        new PollSchedule(Duration.ofSeconds(1), Duration.ofMinutes(1), 2.0, 0.2, new Random(42));

    Duration next = schedule.next(1); // any positive count: returns floor (1000ms), jittered

    // Seed 42's first nextDouble() is 0.7275636800328681 -- measured independently with a bare
    // `new Random(42).nextDouble()`. factor = 1 + (draw*2 - 1) * 0.2
    //        = 1 + (0.4551273600657362) * 0.2 = 1.0910254720131472
    // millis = round(1000 * 1.0910254720131472) = 1091.
    assertThat(next).isEqualTo(Duration.ofMillis(1091));
  }

  @Test
  @DisplayName("rejects a backoffMultiplier of 1.0 or below -- an idle pass would never back off")
  void rejects_a_non_growing_multiplier() {
    assertThatThrownBy(
            () ->
                new PollSchedule(
                    Duration.ofMillis(100), Duration.ofSeconds(1), 1.0, 0.0, new Random(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("rejects a ceiling below the floor")
  void rejects_a_ceiling_below_the_floor() {
    assertThatThrownBy(
            () ->
                new PollSchedule(
                    Duration.ofSeconds(10), Duration.ofSeconds(1), 2.0, 0.0, new Random(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("rejects a jitterFraction outside [0, 1]")
  void rejects_an_out_of_range_jitter_fraction() {
    assertThatThrownBy(
            () ->
                new PollSchedule(
                    Duration.ofMillis(100), Duration.ofSeconds(1), 2.0, 1.5, new Random(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
