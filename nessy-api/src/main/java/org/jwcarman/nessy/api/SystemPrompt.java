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
 * What an agent type is told about itself, before anything it is told about the world.
 *
 * <p>Fixed for the life of a harness. It is the head of every request, and a provider caches a
 * request's leading text, so a change in it invalidates everything cached for every agent of the
 * type. What varies by agent belongs in a {@link StateSource}; what varies by the moment, in an
 * {@link AmbientSource}.
 *
 * <p>Blank is rejected rather than treated as absent. An application that wants no system prompt
 * says so by not setting one; a blank string is somebody's template that came out empty, and
 * sending it is worse than sending nothing -- some providers reject an empty system message, and
 * the ones that do not are being told the agent has no instructions at all.
 */
public record SystemPrompt(String value) {

  public SystemPrompt {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("value must not be blank");
    }
  }
}
