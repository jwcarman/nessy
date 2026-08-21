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
package org.jwcarman.nessy.agent.narrate;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.Effect;
import org.jwcarman.nessy.agent.ModelOutcome;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.Transition;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Machine narration → human narration (§8). The transition carries everything synthesis needs:
 * assistant commits become AssistantSaid; landing on Idle ends the turn, with the failure reason
 * taken from ModelFinished(Failed) when that is what ended it. TurnEnded carries only the failure
 * reason; null means completed (distillation, 2026-08-20).
 *
 * <p>"Observers never influence" (§8) extends to their own failures: {@link #applied} guards every
 * {@link TurnObserver#on} call individually, so a throwing {@code TurnObserver} loses only the one
 * event it choked on — the shell's own commit already landed before {@code applied} ever runs, and
 * a narration exception must not stop the remaining commits from narrating or the turn from being
 * declared over. A throwing observer is logged and dropped, never rethrown.
 */
public final class TurnNarrationAdapter implements AgentObserver {

  private static final Logger log = LoggerFactory.getLogger(TurnNarrationAdapter.class);

  private final TurnObserver turn;

  public TurnNarrationAdapter(TurnObserver turn) {
    this.turn = Objects.requireNonNull(turn, "turn must not be null");
  }

  @Override
  public void applied(AgentEvent event, Transition transition) {
    for (Message committed : transition.commit()) {
      if (committed.role() == Role.ASSISTANT) {
        narrate(new TurnEvent.AssistantSaid(committed));
      }
    }
    if (transition.next() instanceof Phase.Idle) {
      if (event instanceof AgentEvent.ModelFinished(ModelOutcome.Failed(String reason))) {
        narrate(new TurnEvent.TurnEnded(reason));
      } else {
        narrate(new TurnEvent.TurnEnded(null));
      }
    }
  }

  /**
   * {@link TurnObserver#on}, guarded: a throwing observer is logged and dropped rather than
   * propagated, so one bad narration can never abort the apply path that is already committed by
   * the time this runs.
   */
  private void narrate(TurnEvent event) {
    try {
      turn.on(event);
    } catch (RuntimeException e) {
      log.warn("turn observer threw narrating {}; event dropped", event, e);
    }
  }

  @Override
  public void ignored(AgentEvent event) {
    log.debug("event ignored as stale: {}", event);
  }

  @Override
  public void renderFailed(Object observation, RuntimeException error) {
    log.warn("observation could not be rendered and was discarded: {}", observation, error);
  }

  @Override
  public void applyFailed(AgentEvent event, RuntimeException error) {
    log.warn("applying {} failed; event dropped", event, error);
  }

  @Override
  public void reFired(List<Effect> effects) {
    log.debug("effects re-fired: {}", effects);
  }

  @Override
  public void observationRequeued(Object observation) {
    log.debug("observation requeued: {}", observation);
  }
}
