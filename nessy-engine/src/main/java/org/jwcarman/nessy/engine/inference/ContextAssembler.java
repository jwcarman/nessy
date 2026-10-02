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
import org.jspecify.annotations.Nullable;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.Memory;
import org.jwcarman.nessy.api.MemorySource;
import org.jwcarman.nessy.api.State;
import org.jwcarman.nessy.api.StateSource;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.store.TurnHistory;
import org.jwcarman.nessy.inference.InferenceContext;

/**
 * Builds what the model is sent, stratum by stratum: summaries, the tail, memory, state, the active
 * turn, then background.
 *
 * <p>The summaries are the stored summaries of the agent's closed chapters, oldest first. The
 * newest turn after them is the active turn, the one being answered; the completed turns before it
 * are the tail, capped at {@code maxTail} newest, so {@code maxTail} counts completed turns only.
 * With no summaries the tail is the last {@code maxTail} turns before the active one, the window an
 * agent has before any chapter closes. A chapter whose summary is not yet written is not shown, and
 * its turns stay in the tail, whole.
 *
 * <p>A summary that reaches the turn being answered is refused rather than sent: the turn being
 * answered is always the newest turn, so nothing left after the summaries means a summary has
 * replaced the question before it was answered. So is a story with no turn at all: there is nothing
 * to answer.
 *
 * <p>Memory sources and state sources are each asked with the active turn, in the order they were
 * bound, and then the ambient sources; those that have nothing to say are left out. Nothing is held
 * for a source between calls.
 */
public class ContextAssembler implements InferenceContextAssembler {

  private final TurnHistories histories;
  private final Chapters chapters;
  private final int maxTail;
  private final List<MemorySource> memory;
  private final List<StateSource> state;
  private final List<AmbientSource> ambient;

  /** An assembler for an agent with no chapters: the tail is the last {@code maxTail} turns. */
  public ContextAssembler(
      TurnHistories histories,
      int maxTail,
      List<MemorySource> memory,
      List<StateSource> state,
      List<AmbientSource> ambient) {
    this(histories, null, maxTail, memory, state, ambient);
  }

  /** An assembler that shows the summaries {@code chapters} holds; none when it is null. */
  public ContextAssembler(
      TurnHistories histories,
      @Nullable Chapters chapters,
      int maxTail,
      List<MemorySource> memory,
      List<StateSource> state,
      List<AmbientSource> ambient) {
    if (maxTail <= 0) {
      throw new IllegalArgumentException("maxTail must be positive");
    }
    this.histories = histories;
    this.chapters = chapters;
    this.maxTail = maxTail;
    this.memory = List.copyOf(memory);
    this.state = List.copyOf(state);
    this.ambient = List.copyOf(ambient);
  }

  @Override
  public InferenceContext assemble(InferenceInvocation invocation) {
    TurnHistory history = histories.forAgent(invocation.agentType(), invocation.agentId());
    List<Summary> summaries =
        chapters == null
            ? List.of()
            : chapters.summaries(invocation.agentType(), invocation.agentId());
    // Handed over as turns. Flattening here would pick a wire shape on every adapter's behalf, and
    // they do not agree on one.
    List<Turn> turns = turns(history, summaries);
    Turn active = turns.getLast();
    AgentId agentId = invocation.agentId();
    return new InferenceContext(
        summaries,
        turns.subList(0, turns.size() - 1),
        memoryFor(agentId, active),
        stateFor(agentId, active),
        active,
        ambientFor(agentId));
  }

  /** The tail and then the active turn: one more than {@code maxTail}, the newest last. */
  private List<Turn> turns(TurnHistory history, List<Summary> summaries) {
    if (summaries.isEmpty()) {
      List<Turn> turns = history.lastTurns(maxTail + 1);
      if (turns.isEmpty()) {
        throw new IllegalStateException("there is no turn to answer: the story is empty");
      }
      return turns;
    }
    TurnId through = summaries.getLast().chapter().through();
    List<Turn> turns = history.lastTurnsAfter(through, maxTail + 1);
    if (turns.isEmpty()) {
      throw new IllegalStateException(
          "a summary reaches the turn being answered: nothing is left after turn " + through);
    }
    return turns;
  }

  private List<Memory> memoryFor(AgentId agentId, Turn active) {
    return memory.stream()
        .map(source -> source.forAgent(agentId, active))
        .flatMap(Optional::stream)
        .toList();
  }

  private List<State> stateFor(AgentId agentId, Turn active) {
    return state.stream()
        .map(source -> source.forAgent(agentId, active))
        .flatMap(Optional::stream)
        .toList();
  }

  private List<Ambient> ambientFor(AgentId agentId) {
    return ambient.stream()
        .map(source -> source.forAgent(agentId))
        .flatMap(Optional::stream)
        .toList();
  }
}
