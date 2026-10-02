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
package org.jwcarman.nessy.engine.inference;

import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.inference.InferenceContext;

/**
 * Builds what the model is sent: the tail, then background.
 *
 * <p>The tail is the last {@code maxTail} turns of the agent's story, newest last. The context
 * carries no summaries: nothing here writes or reads them, so the story is sent whole up to that
 * cap. Ambient is whatever every source has to say right now, in the order the sources were added,
 * and those that have nothing to say are left out.
 */
public class ContextAssembler implements InferenceContextAssembler {

  private final TurnHistories histories;
  private final int maxTail;
  private final List<AmbientSource> ambient;

  public ContextAssembler(TurnHistories histories, int maxTail, List<AmbientSource> ambient) {
    if (maxTail <= 0) {
      throw new IllegalArgumentException("maxTail must be positive");
    }
    this.histories = histories;
    this.maxTail = maxTail;
    this.ambient = List.copyOf(ambient);
  }

  @Override
  public InferenceContext assemble(InferenceInvocation invocation) {
    // Handed over as turns. Flattening here would pick a wire shape on every adapter's behalf, and
    // they do not agree on one.
    List<Turn> tail =
        histories.forAgent(invocation.agentType(), invocation.agentId()).lastTurns(maxTail);
    return new InferenceContext(List.of(), tail, ambientFor(invocation.agentId()));
  }

  private List<Ambient> ambientFor(AgentId agentId) {
    return ambient.stream()
        .map(source -> source.forAgent(agentId))
        .flatMap(Optional::stream)
        .toList();
  }
}
