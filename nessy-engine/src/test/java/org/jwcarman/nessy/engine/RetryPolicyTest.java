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
import org.jwcarman.nessy.engine.RetryPolicy.RetryDecision;

/**
 * How many more times, and after how long.
 *
 * <p>Every jittered assertion below uses a SEEDED {@link Random} and a value computed independently
 * of the production code (by hand, from the documented formula, not by running the policy and
 * copying its answer) -- a loose "somewhere in range" assertion would still pass if the jitter
 * formula silently changed shape (say, from equal jitter to full jitter), which is exactly the kind
 * of test that looks like proof and is not.
 */
@DisplayName("A retry policy")
class RetryPolicyTest {

  @Test
  @DisplayName("gives up once attemptsMade reaches maxAttempts, and never touches random for it")
  void gives_up_at_the_limit() {
    RetryPolicy policy =
        RetryPolicy.exponential(Duration.ofSeconds(1), 2.0, Duration.ofMinutes(5), 3);
    // A random that throws if asked -- proof GiveUp is decided WITHOUT consulting jitter, not
    // merely that some answer came back that happens to be GiveUp.
    Random exploding =
        new Random() {
          @Override
          public double nextDouble() {
            throw new AssertionError("GiveUp must not consult random");
          }
        };

    assertThat(policy.decide(3, exploding)).isInstanceOf(RetryDecision.GiveUp.class);
    assertThat(policy.decide(4, exploding)).isInstanceOf(RetryDecision.GiveUp.class);
  }

  @Test
  @DisplayName("stays under the limit and returns a delay computed by the documented formula")
  void retries_under_the_limit_with_a_pinned_jittered_delay() {
    RetryPolicy policy =
        RetryPolicy.exponential(Duration.ofSeconds(1), 2.0, Duration.ofMinutes(1), 5);
    // Seed 42's first nextDouble() is 0.7275636800328681 -- measured independently with a bare
    // `new Random(42).nextDouble()`, not read off this test's own failure output.
    Random seeded = new Random(42);

    RetryDecision decision = policy.decide(2, seeded);

    // base=1000ms, multiplier=2.0, attemptsMade=2: raw = 1000 * 2^2 = 4000ms, under the 60000ms
    // cap so capped = 4000. Equal jitter: half = 2000, delay = round(2000 + draw * 2000) =
    // round(2000 + 0.7275636800328681 * 2000) = round(3455.1273600657362) = 3455.
    assertThat(decision).isInstanceOf(RetryDecision.RetryAfter.class);
    assertThat(((RetryDecision.RetryAfter) decision).delay()).isEqualTo(Duration.ofMillis(3455));
  }

  @Test
  @DisplayName("the delay never exceeds maxDelay, even when multiplier^attemptsMade overflows")
  void the_delay_is_capped_even_when_the_raw_value_would_overflow() {
    // multiplier=1e200 raised to the 50th power is Double.POSITIVE_INFINITY -- if the cap were
    // applied AFTER narrowing to long (rather than on the double, before the cast, as documented),
    // this would either throw, wrap to a nonsense negative duration, or silently return
    // Long.MAX_VALUE millis instead of the configured cap.
    RetryPolicy policy =
        RetryPolicy.exponential(Duration.ofMillis(1), 1e200, Duration.ofSeconds(5), 100);
    Random maxDraw =
        new Random() {
          @Override
          public double nextDouble() {
            return 1.0; // the top of the jitter range, so the returned delay should equal the cap
          }
        };

    RetryDecision decision = policy.decide(50, maxDraw);

    assertThat(decision).isInstanceOf(RetryDecision.RetryAfter.class);
    assertThat(((RetryDecision.RetryAfter) decision).delay()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("equal jitter never returns a delay below half the capped value")
  void jitter_never_goes_below_half() {
    RetryPolicy policy =
        RetryPolicy.exponential(Duration.ofSeconds(10), 1.0, Duration.ofMinutes(1), 10);
    Random minDraw =
        new Random() {
          @Override
          public double nextDouble() {
            return 0.0;
          }
        };

    RetryDecision decision = policy.decide(0, minDraw);

    // multiplier 1.0 means raw is always base (10s); half of that is 5s, and a zero draw should
    // land exactly there -- not zero, which a full-jitter formula would have produced instead.
    assertThat(decision).isInstanceOf(RetryDecision.RetryAfter.class);
    assertThat(((RetryDecision.RetryAfter) decision).delay()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("rejects a multiplier below 1.0, which could only ever shrink the delay")
  void rejects_a_shrinking_multiplier() {
    assertThatThrownBy(
            () -> RetryPolicy.exponential(Duration.ofSeconds(1), 0.5, Duration.ofMinutes(1), 3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("rejects a maxAttempts below 1 -- a policy that never gets to try once is not one")
  void rejects_a_non_positive_max_attempts() {
    assertThatThrownBy(
            () -> RetryPolicy.exponential(Duration.ofSeconds(1), 2.0, Duration.ofMinutes(1), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
