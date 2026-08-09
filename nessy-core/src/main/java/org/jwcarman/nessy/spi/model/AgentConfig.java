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
package org.jwcarman.nessy.spi.model;

import java.util.Objects;
import java.util.Set;

/**
 * The knobs one agent needs that are not seams.
 *
 * @param capabilities what the harness asks providers to use; a provider that cannot do one says so
 *     rather than silently degrading
 */
public record AgentConfig(
    String model, String systemPrompt, int maxTokens, Set<Capability> capabilities) {

  public AgentConfig {
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    if (maxTokens < 1) {
      throw new IllegalArgumentException("maxTokens must be at least 1");
    }
    capabilities = Set.copyOf(capabilities);
  }
}
