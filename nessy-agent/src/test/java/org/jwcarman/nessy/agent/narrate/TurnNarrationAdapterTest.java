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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.Effect;
import org.jwcarman.nessy.agent.ModelOutcome;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.Transition;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.turn.TurnEvent;

class TurnNarrationAdapterTest {

  private final RecordingTurnObserver turn = new RecordingTurnObserver();
  private final TurnNarrationAdapter adapter = new TurnNarrationAdapter(turn);

  @Test
  void anAssistantCommitBecomesAssistantSaid() {
    var said = Message.assistant(List.of(new TextBlock("hi")));
    var t = Transition.to(new Phase.Idle()).commit(said);
    adapter.applied(
        new AgentEvent.ModelFinished(new ModelOutcome.Responded(said.content(), List.of())), t);
    assertThat(turn.events()).isNotEmpty();
    assertThat(turn.events().getFirst()).isEqualTo(new TurnEvent.AssistantSaid(said));
  }

  @Test
  void reachingIdleEndsTheTurnCompleted() {
    var t = Transition.to(new Phase.Idle());
    adapter.applied(
        new AgentEvent.ModelFinished(new ModelOutcome.Responded(List.of(), List.of())), t);
    assertThat(turn.events()).contains(new TurnEvent.TurnEnded(ConversationStatus.COMPLETE, null));
  }

  @Test
  void aModelFailureEndsTheTurnFailedWithItsReason() {
    var t = Transition.to(new Phase.Idle());
    adapter.applied(new AgentEvent.ModelFinished(new ModelOutcome.Failed("overloaded")), t);
    assertThat(turn.events())
        .contains(new TurnEvent.TurnEnded(ConversationStatus.FAILED, "overloaded"));
  }

  @Test
  void aUserCommitIsNotNarratedAsAssistantSaid() {
    var t =
        Transition.to(new Phase.AwaitingModel(), new Effect.CallModel())
            .commit(Message.user("hello"));
    adapter.applied(new AgentEvent.Observed(List.of(new TextBlock("hello"))), t);
    assertThat(turn.events()).isEmpty();
  }
}
