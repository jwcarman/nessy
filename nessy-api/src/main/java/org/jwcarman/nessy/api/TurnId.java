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

/**
 * ONE pass through the model and its tools, from an observation to an answer.
 *
 * <p>Minted by the engine, never by an application: a turn is the engine's own unit of work. It is
 * also the backlog row's id, because one observation is one turn — which is what makes taking work
 * idempotent across a crash.
 *
 * <p>A type rather than a {@code String} because it travels beside {@link CallId} nearly
 * everywhere, and two adjacent strings can be transposed in silence.
 */
public record TurnId(@JsonValue String value) {

  public TurnId {
    value = Identifier.checked("turn id", value);
  }

  @JsonCreator
  public static TurnId of(String value) {
    return new TurnId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
