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

import java.util.function.Consumer;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolConfig;

/**
 * How a {@link DirectHarness} is built, in the same words a {@link QueuedHarnessConfig} uses.
 *
 * <p>Almost everything means what it means on the other door: an agent type, a system prompt, what
 * a model is shown, which tools it may reach for and who guards them. Assembling a context and
 * binding a tool are one job each, and which door will start the turn is not part of either.
 *
 * <p><b>What is absent is absent because nothing here can produce it.</b> There is no effects
 * configuration -- no poll interval and no in-flight cap -- because there is no poller and nothing
 * in flight; the turn runs on the thread that asked for it. There is no policy, because coalescing
 * answers "what if more work arrives while this runs", and the only thing that can put work here is
 * the caller, who is blocked.
 *
 * @param <I> what a caller hands in
 */
public interface DirectHarnessConfig<I> {

  DirectHarnessConfig<I> agentType(AgentType agentType);

  DirectHarnessConfig<I> systemPrompt(String prompt);

  DirectHarnessConfig<I> systemPrompt(SystemPromptSource source);

  /** How what a caller hands in becomes what the model reads. */
  DirectHarnessConfig<I> inputRenderer(ObservationRenderer<I> renderer);

  /** The model, the budget, and what it is shown. */
  DirectHarnessConfig<I> inference(Consumer<InferenceConfig> customizer);

  /**
   * Who hears what happens while the turn runs.
   *
   * <p>Worth having even though the answer is returned: a caller waiting on a model wants to watch
   * it arrive, and the deltas are the only part that can be shown before the end.
   */
  DirectHarnessConfig<I> listener(NarrationListener listener);

  <T> DirectHarnessConfig<I> tool(Tool<T> tool, Consumer<ToolConfig<T>> customizer);

  default <T> DirectHarnessConfig<I> tool(Tool<T> tool) {
    return tool(tool, Customizers.withDefaults());
  }
}
