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
import tools.jackson.databind.json.JsonMapper;

/**
 * Says a thing in a line of text.
 *
 * <p>What a call would do, what it returned, and whatever comes next: one type for all of them, so
 * the methods that bound the text live here and are available wherever a stringifier is taken.
 *
 * <p>Each of {@link #dropTail}, {@link #dropHead}, {@link #dropMiddle} and {@link #truncated}
 * returns a wrapper that runs this stringifier and then makes the text one line, turning every run
 * of whitespace, line breaks included, into one space and trimming the ends, and cuts it to the
 * limit with a {@link Truncator}. A limit below 1 is refused when the wrapper is made. A truncator
 * that returns more than the limit has a bug: the wrapper cuts what it returned to the limit,
 * keeping the start, and logs a warning. A truncator that returns null is treated the same way as
 * one that misbehaves: the line is the empty string, a warning is logged, and nothing is thrown.
 *
 * <p><b>A wrapper asked to drop to a limit it is already within returns itself.</b> What it writes
 * already fits, and how it was cut is left as its author chose. Asked for a smaller limit, it wraps
 * again, and the text is cut a second time to the smaller one.
 *
 * @param <T> what is said
 */
@FunctionalInterface
public interface Stringifier<T> {

  /**
   * @param value what to say
   * @return the text; an implementation may throw for a value it cannot say
   */
  String stringify(T value);

  /** This stringifier, keeping the start of what it writes. */
  default Stringifier<T> dropTail(int limit) {
    return truncated(Truncator.dropTail(), limit);
  }

  /** This stringifier, keeping the end of what it writes. */
  default Stringifier<T> dropHead(int limit) {
    return truncated(Truncator.dropHead(), limit);
  }

  /** This stringifier, keeping both ends of what it writes. */
  default Stringifier<T> dropMiddle(int limit) {
    return truncated(Truncator.dropMiddle(), limit);
  }

  /**
   * This stringifier, cut to {@code limit} by {@code truncator}.
   *
   * @throws NullPointerException if the truncator is null
   * @throws IllegalArgumentException if the limit is below 1
   */
  default Stringifier<T> truncated(Truncator truncator, int limit) {
    Objects.requireNonNull(truncator, "truncator must not be null");
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be at least 1, was " + limit);
    }
    return new TruncatingStringifier<>(this, truncator, limit);
  }

  /**
   * The default: {@code String.valueOf(value)}, the value's own {@code toString()}, null-safe.
   *
   * <p>Good enough for a record, {@code RefundOrder[orderId=ord_88, amountCents=4200]} reads well.
   * Two things it does not do: a value that is not a record says {@code
   * com.acme.PurgeRequest@1a2b3c}, and every component is printed, so a field holding a credential
   * reaches whoever is reading.
   */
  static <T> Stringifier<T> byToString() {
    return String::valueOf;
  }

  /**
   * The value as JSON, written by {@code mapper}.
   *
   * <p>For a value whose {@code toString()} says nothing useful, and for anyone who wants a call
   * written the way the model wrote it. A value the mapper cannot write makes {@link #stringify}
   * throw.
   *
   * @param mapper the application's configured mapper
   */
  static <T> Stringifier<T> json(JsonMapper mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    return mapper::writeValueAsString;
  }
}
