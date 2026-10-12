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
package org.jwcarman.nessy.examples.toollab;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Calls a model the way the lab does: a call that throws is tried again a few times, a pause longer
 * each time, because a run of hundreds of calls will meet a rate limit. A call that comes back with
 * a result, including a refusal or a fault, is never tried again: the result is the finding.
 */
final class Models {

  /** How many times a call is made before the run gives up on it. */
  static final int ATTEMPTS = 4;

  /** The pause before the second attempt; each later pause is one longer. */
  static final Duration DEFAULT_PAUSE = Duration.ofSeconds(3);

  private Models() {}

  /**
   * What {@code call} returns, calling it again if it throws, up to {@link #ATTEMPTS} times in all.
   *
   * @throws IllegalStateException when every attempt failed, naming the last failure
   */
  static <T> T retrying(Supplier<T> call, Duration pause) {
    RuntimeException last = null;
    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
      try {
        return call.get();
      } catch (RuntimeException failed) {
        last = failed;
        if (attempt < ATTEMPTS) {
          sleep(pause.multipliedBy(attempt));
        }
      }
    }
    throw new IllegalStateException(
        "gave up after %d attempts: %s".formatted(ATTEMPTS, last.getMessage()), last);
  }

  private static void sleep(Duration pause) {
    try {
      Thread.sleep(pause);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting to ask the model again");
    }
  }
}
