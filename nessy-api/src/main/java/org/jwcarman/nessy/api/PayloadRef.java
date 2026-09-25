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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/**
 * Where the content is, for content the core never sees.
 *
 * <p>Observations, model output and tool results are claim-checked by the harness before a command
 * reaches the state, and what crosses is this. The core branches on identifiers, status, human
 * decisions and counts; everything else is behind one of these.
 *
 * <p>Opaque on purpose. The harness that minted it knows how to resolve it -- from a table, from
 * memory, or by revealing a surrogate through a destination that may refuse. None of that is the
 * core's business.
 */
public record PayloadRef(@JsonValue String value) {

  public PayloadRef {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(
          "a payload reference that names nothing is not a reference");
    }
  }

  /**
   * Read back from the bare string it was written as.
   *
   * <p>{@code @JsonValue} and this together keep a reference stored the way every other value type
   * here is stored -- as the string it wraps, not as an object wrapping a string. A reference sits
   * inside every event that carries content, so getting this wrong would nest an object in each of
   * them and make every stored row unreadable by anything expecting the plain form.
   */
  @JsonCreator
  public static PayloadRef of(String value) {
    return new PayloadRef(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
