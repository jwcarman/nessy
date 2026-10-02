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
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.store.TurnHistory;
import org.jwcarman.nessy.inference.InferenceContext;

/**
 * Builds what the model is sent: summaries, then the tail, then background.
 *
 * <p>The summaries are the stored summaries of the agent's closed chapters, oldest first. The tail
 * begins after the last of them: the turns after that summary's {@code through}, capped at {@code
 * maxTail} newest turns. With no summaries the tail is the last {@code maxTail} turns, the window
 * an agent has before any chapter closes. A chapter whose summary is not yet written is not shown,
 * and its turns stay in the tail, whole.
 *
 * <p>A summary that reaches the turn being answered is refused rather than sent: the turn being
 * answered is always the newest turn, so a tail that comes back empty means a summary has replaced
 * the question before it was answered.
 *
 * <p>With no {@link Chapters} the story is sent whole up to the cap. Ambient is whatever every
 * source has to say right now, in the order the sources were added, and those that have nothing to
 * say are left out.
 */
public class ContextAssembler implements InferenceContextAssembler {

  private final TurnHistories histories;
  private final Chapters chapters;
  private final int maxTail;
  private final List<AmbientSource> ambient;

  /** An assembler for an agent with no chapters: the tail is the last {@code maxTail} turns. */
  public ContextAssembler(TurnHistories histories, int maxTail, List<AmbientSource> ambient) {
    this(histories, null, maxTail, ambient);
  }

  /** An assembler that shows the summaries {@code chapters} holds; none when it is null. */
  public ContextAssembler(
      TurnHistories histories,
      @Nullable Chapters chapters,
      int maxTail,
      List<AmbientSource> ambient) {
    if (maxTail <= 0) {
      throw new IllegalArgumentException("maxTail must be positive");
    }
    this.histories = histories;
    this.chapters = chapters;
    this.maxTail = maxTail;
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
    return new InferenceContext(
        summaries, tail(history, summaries), ambientFor(invocation.agentId()));
  }

  private List<Turn> tail(TurnHistory history, List<Summary> summaries) {
    if (summaries.isEmpty()) {
      return history.lastTurns(maxTail);
    }
    TurnId through = summaries.getLast().chapter().through();
    List<Turn> tail = history.lastTurnsAfter(through, maxTail);
    if (tail.isEmpty()) {
      throw new IllegalStateException(
          "a summary reaches the turn being answered: nothing is left after turn " + through);
    }
    return tail;
  }

  private List<Ambient> ambientFor(AgentId agentId) {
    return ambient.stream()
        .map(source -> source.forAgent(agentId))
        .flatMap(Optional::stream)
        .toList();
  }
}
