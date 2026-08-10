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
package org.jwcarman.nessy.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;

class ReducerGrammarTest {

  private static final ConversationId ID = new ConversationId("s1");

  private final Reducer reducer = Reducer.defaults();
  private final ConversationState initial = ConversationState.newConversation(ID);

  @Test
  void thinking_deltas_accumulate_into_a_single_thinking_block() {
    ConversationState state =
        reducer.reduce(initial, ConversationEvent.AgentTold.of(ID, "hi")).state();
    state = reducer.reduce(state, new ConversationEvent.ThinkingDelta(ID, "Let me ")).state();
    state = reducer.reduce(state, new ConversationEvent.ThinkingDelta(ID, "think.")).state();
    state = reducer.reduce(state, new ConversationEvent.TextDelta(ID, "Answer.")).state();

    assertThat(state.pendingBlocks())
        .containsExactly(new ThinkingBlock("Let me think.", ""), new TextBlock("Answer."));
  }

  @Test
  void thinking_and_text_deltas_never_merge_across_each_other() {
    ConversationState state =
        reducer.reduce(initial, ConversationEvent.AgentTold.of(ID, "hi")).state();
    state =
        reducer.reduce(state, new ConversationEvent.ThinkingDelta(ID, "First thought.")).state();
    state = reducer.reduce(state, new ConversationEvent.TextDelta(ID, "Answer.")).state();
    state =
        reducer.reduce(state, new ConversationEvent.ThinkingDelta(ID, "Second thought.")).state();

    assertThat(state.pendingBlocks())
        .containsExactly(
            new ThinkingBlock("First thought.", ""),
            new TextBlock("Answer."),
            new ThinkingBlock("Second thought.", ""));
  }

  @Test
  void turns_and_usage_accumulate_across_turn_ends() {
    ConversationState state =
        reducer.reduce(initial, ConversationEvent.AgentTold.of(ID, "hi")).state();
    state =
        reducer
            .reduce(
                state,
                new ConversationEvent.ModelTurnEnded(
                    ID, StopReason.END_TURN, new Usage(100, 50, 0)))
            .state();

    assertThat(state.turns()).isEqualTo(1);
    assertThat(state.usage()).isEqualTo(new Usage(100, 50, 0));
  }

  @Test
  void token_ceiling_failure_records_its_reason() {
    ConversationState state =
        reducer.reduce(initial, ConversationEvent.AgentTold.of(ID, "hi")).state();
    state =
        reducer
            .reduce(
                state,
                new ConversationEvent.ModelTurnEnded(ID, StopReason.MAX_TOKENS, Usage.zero()))
            .state();

    assertThat(state.status()).isEqualTo(ConversationStatus.FAILED);
    assertThat(state.failureReason()).contains("MAX_TOKENS");
  }

  @Test
  void a_signature_lands_on_the_trailing_thinking_block() {
    ConversationState state =
        reducer.reduce(initial, ConversationEvent.AgentTold.of(ID, "hi")).state();
    state = reducer.reduce(state, new ConversationEvent.ThinkingDelta(ID, "Let me think.")).state();
    state = reducer.reduce(state, new ConversationEvent.ThinkingSigned(ID, "sig-abc")).state();

    assertThat(state.pendingBlocks())
        .containsExactly(new ThinkingBlock("Let me think.", "sig-abc"));
  }

  @Test
  void a_signature_with_no_trailing_thinking_block_changes_nothing() {
    ConversationState state =
        reducer.reduce(initial, ConversationEvent.AgentTold.of(ID, "hi")).state();
    state = reducer.reduce(state, new ConversationEvent.TextDelta(ID, "Answer.")).state();
    Step step = reducer.reduce(state, new ConversationEvent.ThinkingSigned(ID, "sig-abc"));

    assertThat(step.state().pendingBlocks()).containsExactly(new TextBlock("Answer."));
    assertThat(step.effects()).isEmpty();
  }

  @Test
  void a_delta_after_a_signature_starts_a_fresh_thinking_block() {
    ConversationState state =
        reducer.reduce(initial, ConversationEvent.AgentTold.of(ID, "hi")).state();
    state = reducer.reduce(state, new ConversationEvent.ThinkingDelta(ID, "first")).state();
    state = reducer.reduce(state, new ConversationEvent.ThinkingSigned(ID, "sig-1")).state();
    state = reducer.reduce(state, new ConversationEvent.ThinkingDelta(ID, "second")).state();
    state = reducer.reduce(state, new ConversationEvent.ThinkingSigned(ID, "sig-2")).state();

    assertThat(state.pendingBlocks())
        .containsExactly(new ThinkingBlock("first", "sig-1"), new ThinkingBlock("second", "sig-2"));
  }

  @Test
  void redacted_thinking_appends_its_block_in_order() {
    ConversationState state =
        reducer.reduce(initial, ConversationEvent.AgentTold.of(ID, "hi")).state();
    state =
        reducer
            .reduce(state, new ConversationEvent.RedactedThinkingArrived(ID, "opaque-bytes"))
            .state();
    state = reducer.reduce(state, new ConversationEvent.TextDelta(ID, "Answer.")).state();

    assertThat(state.pendingBlocks())
        .containsExactly(new RedactedThinkingBlock("opaque-bytes"), new TextBlock("Answer."));
  }

  @Test
  void agent_told_carries_arbitrary_content_blocks() {
    Step step =
        reducer.reduce(
            initial, new ConversationEvent.AgentTold(ID, List.of(new TextBlock("describe this"))));

    assertThat(step.state().messages())
        .containsExactly(new Message(Role.USER, List.of(new TextBlock("describe this"))));
  }

  /**
   * The misdelivery guard (design §17): a fact addressed to one conversation must never fold into
   * another's state, so this fails loudly, before any variant-specific handling runs, rather than
   * silently corrupting the wrong conversation's state.
   */
  @Test
  void a_misdelivered_fact_is_rejected_loudly() {
    ConversationId foreign = new ConversationId("s2");

    assertThatThrownBy(() -> reducer.reduce(initial, ConversationEvent.AgentTold.of(foreign, "hi")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(foreign.toString())
        .hasMessageContaining(ID.toString());
  }

  @Test
  void usage_addition_is_componentwise() {
    assertThat(Usage.zero().plus(new Usage(3, 4, 0)).plus(new Usage(10, 20, 0)))
        .isEqualTo(new Usage(13, 24, 0));
  }
}
