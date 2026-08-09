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

import java.util.Objects;

/** Tokens spent on one turn, or accumulated across a session. */
public record Usage(long inputTokens, long outputTokens, long cachedInputTokens) {

  public Usage {
    if (inputTokens < 0 || outputTokens < 0 || cachedInputTokens < 0) {
      throw new IllegalArgumentException("token counts must be non-negative");
    }
  }

  public static Usage zero() {
    return new Usage(0, 0, 0);
  }

  public Usage plus(Usage other) {
    Objects.requireNonNull(other, "other must not be null");
    return new Usage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        cachedInputTokens + other.cachedInputTokens);
  }
}
