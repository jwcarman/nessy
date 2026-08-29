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

import java.util.EnumSet;
import java.util.Set;

/**
 * What a caller asks of a model: how long an answer, and what it cannot do without.
 *
 * <p>Everything here is INTENT. Facts about the model — its context window, what it actually
 * supports — live on {@link ModelDescription}, because the model knows them and the caller was only
 * ever guessing.
 *
 * <p><b>{@code required} is what you cannot live without, not a wish list.</b> Naming a capability
 * here makes a model that lacks it fail at resolution rather than misbehave mid-turn. Anything a
 * model offers beyond this is used opportunistically and never has to be asked for — which is the
 * distinction the old flat {@code capabilities} set could not express: a missing {@code
 * PROMPT_CACHING} costs money, a missing {@code VISION} means the agent cannot function, and one
 * set said neither.
 *
 * @param maxTokens the longest answer to allow, validated against the model's real window at
 *     resolution
 * @param required capabilities the caller cannot proceed without
 */
public record ModelSettings(int maxTokens, Set<Capability> required) {

  /** The max-tokens budget a harness gets when {@code .settings(...)} is never called. */
  public static final int DEFAULT_MAX_TOKENS = 8192;

  public ModelSettings {
    if (maxTokens < 1) {
      throw new IllegalArgumentException("maxTokens must be at least 1");
    }
    required = required.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(required));
  }

  /** The honest defaults a harness runs with when no tuning is supplied. */
  public static ModelSettings defaults() {
    return new ModelSettings(DEFAULT_MAX_TOKENS, Set.of());
  }

  /**
   * Checks these settings against the model that was actually resolved, throwing if they cannot be
   * honoured.
   *
   * <p>This is the comparison that never existed. Both halves were always present — a caller
   * requested capabilities, a {@link Model} reported them — and nothing put them side by side, so
   * asking for something a provider did not offer failed silently and forever. Calling this at
   * resolution turns that into a startup error naming the capability.
   *
   * @throws IllegalArgumentException if a required capability is missing, or {@code maxTokens} does
   *     not fit inside the model's real context window
   */
  public void requireSatisfiedBy(ModelDescription description) {
    if (!description.supportsAll(required)) {
      Set<Capability> missing = EnumSet.copyOf(required);
      missing.removeAll(description.capabilities());
      throw new IllegalArgumentException(
          "model %s (%s) does not support required %s"
              .formatted(description.id(), description.provider(), missing));
    }
    if (maxTokens >= description.contextWindow()) {
      throw new IllegalArgumentException(
          "maxTokens %d does not fit in %s's context window of %d"
              .formatted(maxTokens, description.id(), description.contextWindow()));
    }
  }
}
