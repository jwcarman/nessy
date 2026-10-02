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
package org.jwcarman.nessy.inference;

import java.util.Objects;

/**
 * What a model call is for: answering a turn, writing a chapter's summary. The span and the metrics
 * recorded for the call carry it as a label, so spend and cache use can be told apart by purpose.
 *
 * <p>An application may make its own, with {@code new InferencePurpose("triage")}. Keep the set of
 * values in use small: each one is a series in every metric the call is recorded in. For that
 * reason a value is plain -- 1 to 32 characters, lower-case ASCII letters, digits, {@code -} and
 * {@code _} -- and anything else is refused.
 *
 * <p>A purpose is a fact for the code that records the call. It is never sent to the vendor.
 *
 * @param value the label, as it appears on a span and in a metric
 */
public record InferencePurpose(String value) {

  /** An agent answering its turn. */
  public static final InferencePurpose ANSWER = new InferencePurpose("answer");

  /** A chapter being summarised. */
  public static final InferencePurpose SUMMARY = new InferencePurpose("summary");

  private static final int MAX_LENGTH = 32;

  public InferencePurpose {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isEmpty() || value.length() > MAX_LENGTH || !plain(value)) {
      throw new IllegalArgumentException(
          "a purpose is 1 to 32 characters of lower-case ASCII letters, digits, '-' and '_', but"
              + " was '%s'".formatted(value));
    }
  }

  private static boolean plain(String text) {
    return text.chars()
        .allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_');
  }
}
