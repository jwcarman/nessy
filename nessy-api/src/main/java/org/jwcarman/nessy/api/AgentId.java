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
 * WHICH agent — one instance, within its {@link AgentType}.
 *
 * <p>The type is code; the id is data. An id is only meaningful inside a type, so a watchman and a
 * support agent may both have an instance called {@code "house-12"} without colliding.
 *
 * <p>Chosen by the application, never minted here: an id is how the outside world names something
 * it already knows about — a house, a ticket, a customer — which is what makes an agent addressable
 * without a registry.
 */
public record AgentId(String value) {

  public AgentId {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("agent id must not be blank");
    }
  }

  public static AgentId of(String value) {
    return new AgentId(value);
  }
}
