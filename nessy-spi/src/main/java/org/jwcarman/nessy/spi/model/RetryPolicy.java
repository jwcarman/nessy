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
package org.jwcarman.nessy.spi.model;

import java.time.Duration;

/**
 * How {@link RetryingModelProvider} backs off between attempts.
 *
 * @param maxAttempts total attempts, including the first (must be at least 1)
 * @param initialDelay delay before the first retry (must be positive)
 * @param multiplier factor applied to the delay after each failed attempt (must be at least 1.0)
 */
public record RetryPolicy(int maxAttempts, Duration initialDelay, double multiplier) {

  public RetryPolicy {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1");
    }
    if (initialDelay == null || initialDelay.isZero() || initialDelay.isNegative()) {
      throw new IllegalArgumentException("initialDelay must be positive");
    }
    if (multiplier < 1.0) {
      throw new IllegalArgumentException("multiplier must be at least 1.0");
    }
  }

  /** Three attempts, a 500ms initial delay, doubling each time. */
  public static RetryPolicy defaults() {
    return new RetryPolicy(3, Duration.ofMillis(500), 2.0);
  }
}
