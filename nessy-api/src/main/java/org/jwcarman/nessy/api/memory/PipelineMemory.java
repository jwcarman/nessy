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
package org.jwcarman.nessy.api.memory;

import java.util.List;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.HistoryMessage;

/**
 * A memory that recalls through stages.
 *
 * <p>Package-private: the only way to make one is {@code Memory.pipeline}, so this never becomes a
 * name anyone has to know.
 *
 * <p>{@code remember} delegates untouched. Only the recall side is shaped, which is what keeps the
 * record and the view from disagreeing — they are the same object, so there is no second store to
 * point at the wrong thing.
 */
final class PipelineMemory implements Memory {

  private final Memory bootstrap;
  private final List<ContextTransformer> stages;

  PipelineMemory(Memory bootstrap, List<ContextTransformer> stages) {
    this.bootstrap = bootstrap;
    this.stages = List.copyOf(stages);
  }

  @Override
  public Context recall(AgentId agentId) {
    Context context = bootstrap.recall(agentId);
    for (ContextTransformer stage : stages) {
      context = stage.transform(agentId, context);
    }
    return context;
  }

  @Override
  public void remember(AgentId agentId, HistoryMessage message) {
    bootstrap.remember(agentId, message);
  }
}
