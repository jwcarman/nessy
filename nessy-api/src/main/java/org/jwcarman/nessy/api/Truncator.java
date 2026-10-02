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

/**
 * Cuts a string down to a limit.
 *
 * <p>A limit is a number of characters as a reader counts them: a character made of two {@code
 * char}s, an emoji say, is one. The three supplied truncators never split one.
 *
 * <p>What the supplied ones promise: text already within the limit comes back as it was given; what
 * comes back is never longer than the limit, the marker included; the marker is {@code ...}. A
 * limit too small to hold the marker and one character on each side it keeps gives a plain cut to
 * the limit with no marker: below 4 for {@link #dropTail()} and {@link #dropHead()}, below 5 for
 * {@link #dropMiddle()}.
 *
 * <p>A truncator of an application's own may do anything that honours the limit: cut on words, on
 * lines, on a tokeniser's count.
 */
@FunctionalInterface
public interface Truncator {

  /**
   * @param text what to cut
   * @param limit the most characters that may come back
   * @return {@code text}, no longer than {@code limit} characters
   */
  String truncate(String text, int limit);

  /** Keeps the start and drops the rest; the marker goes at the end. */
  static Truncator dropTail() {
    return Truncators.DROP_TAIL;
  }

  /** Keeps the end and drops what came before it; the marker goes at the start. */
  static Truncator dropHead() {
    return Truncators.DROP_HEAD;
  }

  /**
   * Keeps the start and the end and drops what lies between, the marker in the gap.
   *
   * <p>The start and the end get an equal share of what the limit leaves after the marker; an odd
   * character goes to the start.
   */
  static Truncator dropMiddle() {
    return Truncators.DROP_MIDDLE;
  }
}
