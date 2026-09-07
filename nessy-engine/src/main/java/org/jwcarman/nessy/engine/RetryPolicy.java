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

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * How many more times an obligation may be tried, and how long to wait before the next one.
 *
 * <p>A PURE function of the attempt count and a caller-supplied {@link RandomGenerator} -- never a
 * clock, never a database, never a binding. That is what keeps jitter testable with a seeded
 * generator rather than tolerated by a loose assertion, and it is why this returns a {@link
 * RetryDecision} rather than a bare {@code Duration}: {@code GiveUp} is a real answer, not a
 * sentinel a caller has to invent (a negative duration, a magic {@code Duration.ZERO}).
 *
 * <p>Deliberately carries no {@code includes}/{@code excludes} predicate over exception types.
 * Classification -- whether a particular failure is worth retrying at all -- belongs one layer
 * down, in whichever adapter caught it: only a model client knows what its own 404 means. A failure
 * already classified permanent short-circuits to {@link RetryDecision.GiveUp} without ever
 * consulting a policy.
 */
@FunctionalInterface
public interface RetryPolicy {

  /** What a policy answers with: try again after a wait, or stop for good. */
  sealed interface RetryDecision {

    /** Try again no sooner than {@code delay} from now. */
    record RetryAfter(Duration delay) implements RetryDecision {
      public RetryAfter {
        Objects.requireNonNull(delay, "delay must not be null");
        if (delay.isNegative()) {
          throw new IllegalArgumentException("delay must not be negative");
        }
      }
    }

    /** No more attempts. Whoever asked is done retrying. */
    record GiveUp() implements RetryDecision {}
  }

  /**
   * @param attemptsMade how many attempts this obligation has already used, before the one being
   *     weighed right now
   * @param random the source of jitter for this decision, supplied by the caller rather than read
   *     from a static default -- see the class javadoc
   */
  RetryDecision decide(int attemptsMade, RandomGenerator random);

  /**
   * Exponential backoff with equal jitter, capped.
   *
   * <p>{@code delay = base * multiplier ^ attemptsMade}, capped at {@code maxDelay} BEFORE the
   * {@code double}-to-{@code long} conversion -- {@code delay × multiplier^n} overflows a {@code
   * long} millisecond count well before a real backoff would ever reach it, and capping after the
   * cast would already have wrapped. "Equal jitter" halves the capped delay and adds a uniformly
   * random amount up to the other half, so a retry is never scheduled at exactly zero and never at
   * the full capped delay either -- the two properties that keep a fleet of retries from either
   * stampeding together or wearing a permanently maximal wait.
   *
   * @param maxAttempts the total attempts allowed, including the first -- {@code decide} gives up
   *     once {@code attemptsMade >= maxAttempts}
   */
  static RetryPolicy exponential(
      Duration base, double multiplier, Duration maxDelay, int maxAttempts) {
    Objects.requireNonNull(base, "base must not be null");
    Objects.requireNonNull(maxDelay, "maxDelay must not be null");
    if (base.isNegative() || base.isZero()) {
      throw new IllegalArgumentException("base must be positive");
    }
    if (multiplier < 1.0) {
      throw new IllegalArgumentException("multiplier must be at least 1.0");
    }
    if (maxDelay.compareTo(base) < 0) {
      throw new IllegalArgumentException("maxDelay must be at least base");
    }
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1");
    }
    long capMillis = maxDelay.toMillis();
    long baseMillis = base.toMillis();
    return (attemptsMade, random) -> {
      Objects.requireNonNull(random, "random must not be null");
      if (attemptsMade >= maxAttempts) {
        return new RetryDecision.GiveUp();
      }
      // Capped here, as a double, before anything is narrowed to a long -- multiplier^n grows
      // without bound and a long millisecond count overflows long before a delay anyone would
      // actually wait for.
      double raw = baseMillis * Math.pow(multiplier, attemptsMade);
      double capped = Math.min(raw, (double) capMillis);
      double half = capped / 2.0;
      long jittered = Math.round(half + random.nextDouble() * half);
      return new RetryDecision.RetryAfter(Duration.ofMillis(jittered));
    };
  }
}
