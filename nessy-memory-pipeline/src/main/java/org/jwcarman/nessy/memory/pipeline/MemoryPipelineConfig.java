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
package org.jwcarman.nessy.memory.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What {@code Memory.pipeline} hands a customizer: a CONFIG, not a builder (design of record
 * 2026-08-16 §1) — fluent setters, no public {@code build()}.
 *
 * <p>Only stages. There is no hydration seam here, deliberately: bootstrapping a context from what
 * happened is exactly what a {@code Memory} does, so a summarizing or snapshotting bootstrap is an
 * ordinary {@code Memory} implementation handed to {@code pipeline} — not a second interface with
 * the same shape and a different name.
 */
public final class MemoryPipelineConfig {

  private final List<ContextTransformer> stages = new ArrayList<>();

  MemoryPipelineConfig() {}

  /** Adds a stage. Stages run in the order they are added, each on the last one's output. */
  public MemoryPipelineConfig stage(ContextTransformer stage) {
    stages.add(Objects.requireNonNull(stage, "stage must not be null"));
    return this;
  }

  List<ContextTransformer> stages() {
    return List.copyOf(stages);
  }
}
