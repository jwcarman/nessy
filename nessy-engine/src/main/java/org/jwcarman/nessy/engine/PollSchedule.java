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
 * How eagerly {@link EffectPoller} comes back.
 *
 * <p>Tightens to the floor the moment a pass finds work, backs off geometrically toward the ceiling
 * the moment one does not, and jitters every interval it hands out -- so a fleet of nodes that all
 * started polling around the same moment does not stay in lockstep, each one hammering the table at
 * the same instant every pass.
 *
 * <p><b>Not a Spring {@code Trigger}.</b> This is the pure policy a {@code Trigger} adapter would
 * wrap -- "how long until the next pass, given what the last one found" -- kept separable from
 * {@code TriggerContext} and {@code nextExecution} so it is constructible and testable with no
 * Spring context at all. Measuring the next delay from the LAST COMPLETION rather than a fixed
 * cadence is the adapter's job, not this class's: a {@code Trigger} reads {@link #next(int)} once
 * per completed pass and schedules from there, so a slow pass is never immediately followed by
 * another.
 *
 * <p>Mutable and NOT thread-safe by design: one {@code PollSchedule} belongs to one poller loop,
 * called from exactly one place between passes, the same way a {@code Trigger}'s own state is
 * private to the scheduler that owns it.
 */
final class PollSchedule {

  private final Duration floor;
  private final Duration ceiling;
  private final double backoffMultiplier;
  private final double jitterFraction;
  private final RandomGenerator random;
  private Duration current;

  /**
   * @param floor the shortest interval -- returned, jittered, the instant a pass finds any work
   * @param ceiling the longest interval backoff may reach
   * @param backoffMultiplier how much an idle pass stretches the interval by; must exceed 1.0 or
   *     idle passes would never back off
   * @param jitterFraction how far a returned interval may wander from its unjittered value, as a
   *     fraction of it (0.1 means ±10%) -- bounded to [0, 1] so a jittered interval is never
   *     negative
   * @param random the source of jitter, supplied by the caller -- see {@link RetryPolicy} for why
   */
  PollSchedule(
      Duration floor,
      Duration ceiling,
      double backoffMultiplier,
      double jitterFraction,
      RandomGenerator random) {
    this.floor = Objects.requireNonNull(floor, "floor must not be null");
    this.ceiling = Objects.requireNonNull(ceiling, "ceiling must not be null");
    this.random = Objects.requireNonNull(random, "random must not be null");
    if (floor.isNegative() || floor.isZero()) {
      throw new IllegalArgumentException("floor must be positive");
    }
    if (ceiling.compareTo(floor) < 0) {
      throw new IllegalArgumentException("ceiling must be at least floor");
    }
    if (backoffMultiplier <= 1.0) {
      throw new IllegalArgumentException("backoffMultiplier must exceed 1.0");
    }
    if (jitterFraction < 0.0 || jitterFraction > 1.0) {
      throw new IllegalArgumentException("jitterFraction must be within [0, 1]");
    }
    this.backoffMultiplier = backoffMultiplier;
    this.jitterFraction = jitterFraction;
    this.current = floor;
  }

  /**
   * Tells the schedule what the last pass found, and answers with the jittered wait before the next
   * one.
   *
   * @param found how many rows the last pass attempted -- any positive count tightens straight back
   *     to {@code floor}; zero stretches {@code current} by {@code backoffMultiplier}, capped at
   *     {@code ceiling}
   */
  Duration next(int found) {
    current = found > 0 ? floor : cap(scaled(current), ceiling);
    return jittered(current);
  }

  private Duration scaled(Duration value) {
    return Duration.ofMillis(Math.round(value.toMillis() * backoffMultiplier));
  }

  private static Duration cap(Duration value, Duration max) {
    return value.compareTo(max) > 0 ? max : value;
  }

  private Duration jittered(Duration base) {
    // A factor in [1 - jitterFraction, 1 + jitterFraction], so the jittered value can wander
    // either side of base -- never merely capped below it -- while jitterFraction's own [0, 1]
    // bound (checked in the constructor) keeps the factor itself from ever going negative.
    double factor = 1.0 + (random.nextDouble() * 2.0 - 1.0) * jitterFraction;
    long millis = Math.max(1, Math.round(base.toMillis() * factor));
    return Duration.ofMillis(millis);
  }
}
