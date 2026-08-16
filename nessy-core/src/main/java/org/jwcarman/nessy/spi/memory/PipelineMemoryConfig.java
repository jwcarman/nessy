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
package org.jwcarman.nessy.spi.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.transcript.Transcript;

/**
 * What {@link Memory#pipeline(Transcript, PipelineMemoryCustomizer)} hands a customizer: a CONFIG,
 * not a builder (design of record 2026-08-16 §1) — fluent setters, no public {@code build()}. Names
 * a hydration strategy (default {@link ContextHydrator#full()}) and an ordered list of {@link
 * ContextTransformer} stages, every one of them required — optional behavior arrives pre-wrapped
 * via {@link ContextTransformer#optional(ContextTransformer)}.
 */
public final class PipelineMemoryConfig {

  private final Transcript transcript;
  private ContextHydrator hydrator;
  private final List<ContextTransformer> stages = new ArrayList<>();

  PipelineMemoryConfig(Transcript transcript) {
    this.transcript = Objects.requireNonNull(transcript, "transcript must not be null");
  }

  /**
   * Sets the hydration strategy. Setting a hydrator twice — by this verb or by {@link
   * #summarizing}, in either order — is an {@link IllegalStateException}: one hydration strategy
   * per pipeline.
   */
  public PipelineMemoryConfig hydrator(ContextHydrator hydrator) {
    Objects.requireNonNull(hydrator, "hydrator must not be null");
    if (this.hydrator != null) {
      throw new IllegalStateException("one hydration strategy per pipeline");
    }
    this.hydrator = hydrator;
    return this;
  }

  /**
   * Sugar for {@code hydrator(ContextHydrator.summarizing(summaries, provider, model, prompt,
   * tailThreshold))}; parameters mirror {@link ContextHydrator#summarizing} exactly.
   */
  public PipelineMemoryConfig summarizing(
      SummaryStore summaries,
      ModelProvider provider,
      String model,
      String prompt,
      int tailThreshold) {
    return hydrator(ContextHydrator.summarizing(summaries, provider, model, prompt, tailThreshold));
  }

  /**
   * Registers the pair-safe trim ({@link Context#keepRecent(int)}) as a required stage at its call
   * position.
   *
   * @throws IllegalArgumentException if {@code n} is less than 1
   */
  public PipelineMemoryConfig keepRecent(int n) {
    if (n < 1) {
      throw new IllegalArgumentException("window must be at least 1");
    }
    stages.add((id, context) -> context.keepRecent(n));
    return this;
  }

  /** Registers {@code stage} as a required stage, appended after any already registered. */
  public PipelineMemoryConfig transform(ContextTransformer stage) {
    stages.add(Objects.requireNonNull(stage, "stage must not be null"));
    return this;
  }

  /**
   * Turns this config into the {@link PipelineMemory} it describes — the factory's own step, never
   * a public {@code build()} (design of record 2026-08-16 §1). Reached only from {@link
   * Memory#pipeline(Transcript, PipelineMemoryCustomizer)}, once {@code customize} has returned:
   * {@code remember} appends to the transcript, {@code recall} runs the named (or defaulted)
   * hydrator, then folds the stage list in registration order.
   */
  PipelineMemory build() {
    return new PipelineMemory(
        transcript, hydrator != null ? hydrator : ContextHydrator.full(), stages);
  }
}
