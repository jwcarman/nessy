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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * §10.11's staleness judgment, named: is this quiet phase dead enough for the recovery arm to
 * re-fire it (§6.1)? The {@link Clock} lives inside the policy, not the shell — the policy owns
 * time, and phase-awareness is the point: an implementation may consult {@code phase} to decide
 * that a scope quiet on purpose (suspended on an approval computation, say) is not stale at all.
 */
@FunctionalInterface
public interface StalenessPolicy {

  /** §6.1's judgment, named: is this quiet phase dead enough to re-fire? */
  boolean isStale(AgentPhase phase, Instant lastSaved);

  /**
   * A policy that re-fires once a phase has sat quiet for at least {@code threshold}, by the system
   * clock.
   */
  static StalenessPolicy after(Duration threshold) {
    return after(threshold, Clock.systemUTC());
  }

  /**
   * A policy that re-fires once a phase has sat quiet for at least {@code threshold}, by {@code
   * clock}.
   */
  static StalenessPolicy after(Duration threshold, Clock clock) {
    Objects.requireNonNull(threshold, "threshold must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    if (threshold.isNegative()) {
      throw new IllegalArgumentException("threshold must not be negative");
    }
    return (phase, lastSaved) -> {
      Objects.requireNonNull(phase, "phase must not be null");
      Objects.requireNonNull(lastSaved, "lastSaved must not be null");
      return Duration.between(lastSaved, clock.instant()).compareTo(threshold) >= 0;
    };
  }

  /** A policy under which no phase is ever stale — the recovery arm never re-fires. */
  static StalenessPolicy never() {
    return (phase, lastSaved) -> false;
  }
}
