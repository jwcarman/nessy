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
 * One model's share of an agent's usage: how many inferences it served, and each kind summed.
 *
 * <p>A kind that no inference on this model reported stays {@link Tokens#none() not reported}; it
 * is never turned into a zero that a reader could mistake for a measurement.
 *
 * @param model the model, as its provider named it
 * @param inferences how many inferences were counted on it
 * @param input input tokens
 * @param output output tokens
 * @param cacheRead input tokens read from a cache
 * @param cacheWrite input tokens written to a cache
 * @param reasoning reasoning tokens
 */
public record ModelUsage(
    String model,
    int inferences,
    Tokens input,
    Tokens output,
    Tokens cacheRead,
    Tokens cacheWrite,
    Tokens reasoning) {

  public ModelUsage {
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(output, "output must not be null");
    Objects.requireNonNull(cacheRead, "cacheRead must not be null");
    Objects.requireNonNull(cacheWrite, "cacheWrite must not be null");
    Objects.requireNonNull(reasoning, "reasoning must not be null");
  }
}
