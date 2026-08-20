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
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;

/**
 * Machine narration → human narration (§8). The transition carries everything synthesis needs:
 * assistant commits become AssistantSaid; landing on Idle ends the turn, with the failure reason
 * taken from ModelFinished(Failed) when that is what ended it. ConversationStatus is interim
 * vocabulary until the Plan-5 distillation reshapes TurnEnded.
 */
public final class TurnNarrationAdapter implements AgentObserver {

  private final TurnObserver turn;

  public TurnNarrationAdapter(TurnObserver turn) {
    this.turn = Objects.requireNonNull(turn, "turn must not be null");
  }

  @Override
  public void applied(AgentEvent event, Transition transition) {
    for (Message committed : transition.commit()) {
      if (committed.role() == Role.ASSISTANT) {
        turn.on(new TurnEvent.AssistantSaid(committed));
      }
    }
    if (transition.next() instanceof Phase.Idle) {
      if (event instanceof AgentEvent.ModelFinished(ModelOutcome.Failed(String reason))) {
        turn.on(new TurnEvent.TurnEnded(ConversationStatus.FAILED, reason));
      } else {
        turn.on(new TurnEvent.TurnEnded(ConversationStatus.COMPLETE, null));
      }
    }
  }

  @Override
  public void ignored(AgentEvent event) {}

  @Override
  public void renderFailed(Object observation, RuntimeException error) {}

  @Override
  public void applyFailed(AgentEvent event, RuntimeException error) {}

  @Override
  public void reFired(List<Effect> effects) {}

  @Override
  public void observationRequeued(Object observation) {}
}
