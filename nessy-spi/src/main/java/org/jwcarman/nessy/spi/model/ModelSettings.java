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

import java.util.Set;

/**
 * The optional tuning bag — everything the harness needs that is not a seam and not the model
 * handle itself.
 *
 * <p>The model and the system prompt are not here: the former is the {@code Model} handle passed to
 * the harness, the latter is harness-level configuration (see {@code HarnessConfig.systemPrompt}).
 * {@link #defaults()} is what a harness built without {@code .settings(...)} gets.
 *
 * @param capabilities what the harness asks providers to use; a provider that cannot do one says so
 *     rather than silently degrading
 * @param contextWindow the model's total token budget, or {@code null} if undeclared. This is the
 *     third sanctioned nullable field in this codebase (see {@code ModelRequest.responseSchema} for
 *     the second): most callers never set it. Validated at construction (must exceed {@code
 *     maxTokens}, when declared) but otherwise not yet consumed by anything in the loop — a
 *     declared-but-unconsumed setting, reserved for a future retention policy to read.
 */
public record ModelSettings(int maxTokens, Set<Capability> capabilities, Long contextWindow) {

  /** The max-tokens budget a harness gets when {@code .settings(...)} is never called. */
  public static final int DEFAULT_MAX_TOKENS = 8192;

  public ModelSettings {
    if (maxTokens < 1) {
      throw new IllegalArgumentException("maxTokens must be at least 1");
    }
    if (contextWindow != null && contextWindow <= maxTokens) {
      throw new IllegalArgumentException("contextWindow must be greater than maxTokens");
    }
    capabilities = Set.copyOf(capabilities);
  }

  /** The honest defaults a harness runs with when no tuning is supplied. */
  public static ModelSettings defaults() {
    return new ModelSettings(DEFAULT_MAX_TOKENS, Set.of(), null);
  }
}
