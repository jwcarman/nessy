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

/**
 * The one guard behind every identifier that names a database column: non-null, non-blank, and no
 * longer than the column behind it.
 *
 * <p>Length only, deliberately -- no ASCII check. The bound comes from a real {@code VARCHAR}
 * somewhere; an ASCII rule would come from nothing but an assumption, and this exists to state
 * facts rather than guesses.
 *
 * <p>Not every identifier in this system uses this. {@link org.jwcarman.nessy.api.tool.CallId} and
 * {@link org.jwcarman.nessy.api.tool.ToolName} come from the model, not from us, so nothing here
 * gets to say how long is too long for one.
 */
public final class Identifiers {

  private Identifiers() {}

  /**
   * @param value the candidate value
   * @param what what the value names -- e.g. {@code "agent type"} or {@code "lock kind"} -- so the
   *     message says what is wrong rather than naming the record component that held it. A wrapper
   *     that reported "value must not be blank" would leave a caller with several strings in play
   *     and no idea which one it meant.
   * @param maxLength the longest the column behind this value allows
   * @return {@code value}, unchanged, once it has passed every check
   */
  public static String require(String value, String what, int maxLength) {
    Objects.requireNonNull(value, what + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(what + " must not be blank");
    }
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(
          "%s must be at most %d characters, got %d".formatted(what, maxLength, value.length()));
    }
    return value;
  }
}
