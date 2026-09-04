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
package org.jwcarman.nessy.engine;

import java.util.Objects;

/**
 * One durable obligation's name.
 *
 * <p>Stable across retries on purpose: where an external system accepts an idempotency key, this is
 * what it gets, so a retry after an unknown outcome is the same request rather than a second one.
 */
public record EffectId(String value) {

  public EffectId {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("value must not be blank");
    }
  }

  public static EffectId of(String value) {
    return new EffectId(value);
  }

  /** A fresh id: a UUIDv7, so it sorts by the moment it was minted. */
  public static EffectId next() {
    return new EffectId(Identifiers.next());
  }

  @Override
  public String toString() {
    return value;
  }
}
