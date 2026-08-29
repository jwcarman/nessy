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
 * What a model says about itself: who it is, how much it can read, and what it can do.
 *
 * <p>{@code contextWindow} is the field that did not exist anywhere before. A caller used to
 * declare it in {@code ModelSettings} — guessing a number the model already knew, which is why it
 * was nullable and why the token budget was never enforced against anything real.
 *
 * @param id the model's id at its vendor, e.g. {@code "claude-opus-5"}
 * @param provider the provider that serves it, e.g. {@code "anthropic"}
 * @param contextWindow total tokens the model can read in one call, always positive
 * @param capabilities what it can actually do
 */
public record ModelDescription(
    String id, String provider, long contextWindow, Set<Capability> capabilities) {

  public ModelDescription {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(provider, "provider must not be null");
    Objects.requireNonNull(capabilities, "capabilities must not be null");
    if (contextWindow < 1) {
      throw new IllegalArgumentException("contextWindow must be positive, was " + contextWindow);
    }
    capabilities = Set.copyOf(capabilities);
  }

  /** Whether this model can do everything in {@code required}. */
  public boolean supportsAll(Set<Capability> required) {
    return capabilities.containsAll(required);
  }
}
