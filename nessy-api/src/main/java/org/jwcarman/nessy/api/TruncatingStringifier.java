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

import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A {@link Stringifier} that makes what another writes one line and cuts it to a limit. */
final class TruncatingStringifier<T> implements Stringifier<T> {

  private static final Logger LOG = LoggerFactory.getLogger(TruncatingStringifier.class);
  private static final Pattern WHITESPACE =
      Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

  private final Stringifier<T> wrapped;
  private final Truncator truncator;
  private final int limit;

  TruncatingStringifier(Stringifier<T> wrapped, Truncator truncator, int limit) {
    this.wrapped = wrapped;
    this.truncator = truncator;
    this.limit = limit;
  }

  @Override
  public String stringify(T value) {
    String written = wrapped.stringify(value);
    if (written == null) {
      return "";
    }
    String line = WHITESPACE.matcher(written).replaceAll(" ").strip();
    String cut = truncator.truncate(line, limit);
    int length = cut.codePointCount(0, cut.length());
    if (length > limit) {
      LOG.warn(
          "a truncator returned {} characters for a limit of {}; cutting it to the limit",
          length,
          limit);
      return cut.substring(0, cut.offsetByCodePoints(0, limit));
    }
    return cut;
  }

  @Override
  public Stringifier<T> truncated(Truncator other, int asked) {
    if (asked >= limit) {
      return this;
    }
    return Stringifier.super.truncated(other, asked);
  }
}
