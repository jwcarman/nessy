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
 * WHICH agent — one instance, within its {@link AgentType}.
 *
 * <p>The type is code; the id is data. An id is only meaningful inside a type, so a watchman and a
 * support agent may both have an instance called {@code "house-12"} without colliding.
 *
 * <p>Chosen by the application, never minted here: an id is how the outside world names something
 * it already knows about — a house, a ticket, a customer — which is what makes an agent addressable
 * without a registry.
 *
 * <p>Chosen, but not arbitrary. An id is a primary-key column and an actor's address, so it is held
 * to the shared identifier rule — printable, bounded, and free of characters something downstream
 * reads as structure. An application whose own ids do not fit encodes them; that is a better
 * conversation to have at the call site than in an index three days later.
 */
public record AgentId(@JsonValue String value) {

  public AgentId {
    value = Identifier.checked("agent id", value);
  }

  @JsonCreator
  public static AgentId of(String value) {
    return new AgentId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
