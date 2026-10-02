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

/** The three truncators {@link Truncator} supplies, counting and cutting in code points. */
final class Truncators {

  private static final String MARKER = "...";

  static final Truncator DROP_TAIL =
      (text, limit) -> {
        int count = checked(text, limit);
        if (count <= limit) {
          return text;
        }
        if (limit < MARKER.length() + 1) {
          return start(text, limit);
        }
        return start(text, limit - MARKER.length()) + MARKER;
      };

  static final Truncator DROP_HEAD =
      (text, limit) -> {
        int count = checked(text, limit);
        if (count <= limit) {
          return text;
        }
        if (limit < MARKER.length() + 1) {
          return end(text, count, limit);
        }
        return MARKER + end(text, count, limit - MARKER.length());
      };

  static final Truncator DROP_MIDDLE =
      (text, limit) -> {
        int count = checked(text, limit);
        if (count <= limit) {
          return text;
        }
        if (limit < MARKER.length() + 2) {
          return start(text, limit);
        }
        int kept = limit - MARKER.length();
        int head = (kept + 1) / 2;
        return start(text, head) + MARKER + end(text, count, kept - head);
      };

  private Truncators() {}

  private static int checked(String text, int limit) {
    Objects.requireNonNull(text, "text must not be null");
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be at least 1, was " + limit);
    }
    return text.codePointCount(0, text.length());
  }

  private static String start(String text, int characters) {
    return text.substring(0, text.offsetByCodePoints(0, characters));
  }

  private static String end(String text, int count, int characters) {
    return text.substring(text.offsetByCodePoints(0, count - characters));
  }
}
