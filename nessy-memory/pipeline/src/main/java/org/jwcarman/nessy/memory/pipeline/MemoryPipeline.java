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

import java.util.Objects;
import org.jwcarman.nessy.api.memory.Memory;

/**
 * A memory that recalls through stages.
 *
 * <pre>{@code
 * Memory memory =
 *     MemoryPipeline.of(
 *         TranscriptMemory.recent(substrate, TYPE, 100_000),
 *         pipeline -> pipeline.stage(NotebookTools.index(notebook)));
 * }</pre>
 *
 * <p>The bootstrap answers what the model should see before anything is added; the stages shape it
 * on the way out. Remembering goes straight through, so the record and the view cannot disagree —
 * they are the same object.
 *
 * <p><b>There is no hydration seam here.</b> Bootstrapping a context from what happened is exactly
 * what a {@link Memory} does, so a summarizing or snapshotting bootstrap is an ordinary {@code
 * Memory} implementation handed to this — not a second interface with the same shape and a
 * different name.
 */
public final class MemoryPipeline {

  private MemoryPipeline() {}

  public static Memory of(Memory bootstrap, MemoryPipelineCustomizer customizer) {
    Objects.requireNonNull(bootstrap, "bootstrap must not be null");
    Objects.requireNonNull(customizer, "customizer must not be null");
    MemoryPipelineConfig config = new MemoryPipelineConfig();
    customizer.customize(config);
    return new PipelineMemory(bootstrap, config.stages());
  }
}
