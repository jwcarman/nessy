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
import java.util.function.Function;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentPhase;
import org.jwcarman.nessy.agent.AgentTransition;
import org.jwcarman.nessy.agent.Effect;
import org.jwcarman.nessy.agent.ModelOutcome;
import org.jwcarman.nessy.agent.spi.HarnessObserver;
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
 * <p>One instance serves the whole harness (agentic-o11y spec §3): this is a subscriber on the
 * harness's fact stream, not a per-scope object stamped by a factory, so it resolves the {@link
 * TurnObserver} to narrate onto from the {@link AgentId} it is handed on every call — the harness's
 * per-id turn fanout. That preserves exactly the routing the per-id factory used to give it: a
 * scope's own {@code subscribe}d observers and the harness's global one each see {@code
 * AssistantSaid}/{@code TurnEnded} exactly once.
 *
 * <p>"Observers never influence" (§8) extends to their own failures: {@link #applied} guards every
 * {@link TurnObserver#on} call individually, so a throwing {@code TurnObserver} loses only the one
 * event it choked on — the shell's own commit already landed before {@code applied} ever runs, and
 * a narration exception must not stop the remaining commits from narrating or the turn from being
 * declared over. A throwing observer is logged and dropped, never rethrown.
 */
public final class TurnNarrationAdapter implements HarnessObserver {

  private static final Logger log = LoggerFactory.getLogger(TurnNarrationAdapter.class);

  private final Function<AgentId, TurnObserver> turnObservers;

  /**
   * @param turnObservers resolves the {@link TurnObserver} a given scope's narration belongs on —
   *     the harness's own per-id turn fanout
   */
  public TurnNarrationAdapter(Function<AgentId, TurnObserver> turnObservers) {
    this.turnObservers = Objects.requireNonNull(turnObservers, "turnObservers must not be null");
  }

  @Override
  public void applied(AgentId id, AgentEvent event, AgentTransition transition) {
    for (Message committed : transition.commit()) {
      if (committed.role() == Role.ASSISTANT) {
        narrate(id, new TurnEvent.AssistantSaid(committed));
      }
    }
    if (transition.next() instanceof AgentPhase.Idle) {
      if (event instanceof AgentEvent.ModelFinished(ModelOutcome.Failed(String reason))) {
        narrate(id, new TurnEvent.TurnEnded(reason));
      } else {
        narrate(id, new TurnEvent.TurnEnded(null));
      }
    }
  }

  /**
   * {@link TurnObserver#on}, guarded: a throwing observer is logged and dropped rather than
   * propagated, so one bad narration can never abort the apply path that is already committed by
   * the time this runs.
   */
  private void narrate(AgentId id, TurnEvent event) {
    try {
      turnObservers.apply(id).on(event);
    } catch (RuntimeException e) {
      log.warn("turn observer threw narrating {}; event dropped", event, e);
    }
  }

  @Override
  public void ignored(AgentId id, AgentEvent event) {
    log.debug("event ignored as stale for agent {}: {}", id.value(), event);
  }

  @Override
  public void renderFailed(AgentId id, Object observation, RuntimeException error) {
    log.warn(
        "observation for agent {} could not be rendered and was discarded: {}",
        id.value(),
        observation,
        error);
  }

  @Override
  public void applyFailed(AgentId id, AgentEvent event, RuntimeException error) {
    log.warn("applying {} for agent {} failed; event dropped", event, id.value(), error);
  }

  @Override
  public void reFired(AgentId id, List<Effect> effects) {
    log.debug("effects re-fired for agent {}: {}", id.value(), effects);
  }

  @Override
  public void observationRequeued(AgentId id, Object observation) {
    log.debug("observation requeued for agent {}: {}", id.value(), observation);
  }
}
