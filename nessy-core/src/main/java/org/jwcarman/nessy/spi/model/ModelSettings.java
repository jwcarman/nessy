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
 * @param contextWindow the model's total token budget, or {@code null} if undeclared. This is the
 *     third sanctioned nullable field in this codebase (see {@code ModelRequest.responseSchema} for
 *     the second): most callers never set it, and a declared window exists only so {@link
 *     org.jwcarman.nessy.spi.compaction.Compactors.SummarizingBuilder#window} has something to
 *     derive a trigger from.
 */
public record ModelSettings(
    String model,
    String systemPrompt,
    int maxTokens,
    Set<Capability> capabilities,
    Long contextWindow) {

  public ModelSettings {
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    if (maxTokens < 1) {
      throw new IllegalArgumentException("maxTokens must be at least 1");
    }
    if (contextWindow != null && contextWindow <= maxTokens) {
      throw new IllegalArgumentException("contextWindow must be greater than maxTokens");
    }
    capabilities = Set.copyOf(capabilities);
  }
}
