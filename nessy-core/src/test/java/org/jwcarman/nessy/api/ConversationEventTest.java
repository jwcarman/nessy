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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.Effect;
import org.jwcarman.nessy.api.conversation.Step;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;

class ConversationEventTest {

  @Test
  void events_are_exhaustively_matchable() {
    ConversationEvent event = ConversationEvent.AgentTold.of(new ConversationId("s1"), "hello");

    String described =
        switch (event) {
          case ConversationEvent.AgentTold e ->
              "user:" + ((TextBlock) e.content().getFirst()).text();
          case ConversationEvent.ModelResponded e -> "responded:" + e.message();
          case ConversationEvent.ModelCallFailed e -> "failed:" + e.reason();
          case ConversationEvent.ToolFinished e -> "finished:" + e.call().name();
        };

    assertThat(described).isEqualTo("user:hello");
  }

  @Test
  void modelRespondedCarriesTheSettledMessageWhole() {
    ConversationId id = ConversationId.generate();
    Message message = Message.assistant(List.of(new TextBlock("the answer")));
    ConversationEvent.ModelResponded fact =
        new ConversationEvent.ModelResponded(id, message, StopReason.END_TURN, Usage.zero());

    assertThat(fact.conversationId()).isEqualTo(id);
    assertThat(fact.message()).isEqualTo(message);
  }

  @Test
  void modelCallFailedNamesItsReason() {
    ConversationEvent.ModelCallFailed fact =
        new ConversationEvent.ModelCallFailed(ConversationId.generate(), "context window exceeded");

    assertThat(fact.reason()).isEqualTo("context window exceeded");
  }

  @Test
  void modelRespondedRejectsNullMessage() {
    ConversationId id = ConversationId.generate();

    assertThatThrownBy(
            () -> new ConversationEvent.ModelResponded(id, null, StopReason.END_TURN, Usage.zero()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void allow_is_a_shared_instance() {
    assertThat(Decision.allow()).isSameAs(Decision.allow());
  }

  @Test
  void deny_carries_its_reason() {
    Decision decision = new Decision.Deny("user pressed n");

    assertThat(decision).isInstanceOf(Decision.Deny.class);
    assertThat(((Decision.Deny) decision).reason()).isEqualTo("user pressed n");
  }

  @Test
  void step_of_collects_its_effects() {
    ConversationState state = ConversationState.newConversation(new ConversationId("s1"));

    Step step = Step.of(state, Effect.callModel());

    assertThat(step.state()).isSameAs(state);
    assertThat(step.effects()).containsExactly(Effect.callModel());
  }

  @Test
  void step_effects_are_unmodifiable() {
    ConversationState state = ConversationState.newConversation(new ConversationId("s1"));

    assertThat(Step.of(state).effects()).isUnmodifiable();
  }
}
