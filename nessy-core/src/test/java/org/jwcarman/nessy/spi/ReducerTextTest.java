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

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.TerminationPolicy;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.spi.compaction.Compactor;

class ReducerTextTest {

  private static final ConversationId ID = new ConversationId("s1");

  private final Reducer reducer = Reducer.defaults();
  private final ConversationState initial = ConversationState.newConversation(ID);

  @Nested
  class User_input {

    @Test
    void user_input_is_recorded_and_asks_for_the_model() {
      Step step = reducer.reduce(initial, ConversationEvent.AgentTold.of(ID, "what is 2+2?"));

      assertThat(step.state().messages()).containsExactly(Message.user("what is 2+2?"));
      assertThat(step.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
      assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void new_user_input_clears_the_error_streak() {
      ConversationState state = initial.withConsecutiveErrors(2);

      Step step = reducer.reduce(state, ConversationEvent.AgentTold.of(ID, "try again"));

      assertThat(step.state().consecutiveErrors()).isZero();
      assertThat(step.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
    }

    @Test
    void a_fresh_user_message_on_a_turn_exhausted_session_halts_instead_of_calling_the_model() {
      Reducer limited = new Reducer(TerminationPolicy.maxTurns(1), Compactor.disabled());
      ConversationState exhausted = ConversationState.newConversation(ID).withTurns(1);

      Step step = limited.reduce(exhausted, ConversationEvent.AgentTold.of(ID, "more?"));

      assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
      assertThat(step.state().failureReason()).contains("turn");
      assertThat(step.effects()).isEmpty();
    }
  }

  @Nested
  class Text_deltas {

    @Test
    void text_deltas_accumulate_into_a_single_pending_block() {
      ConversationState state =
          reducer.reduce(initial, ConversationEvent.AgentTold.of(ID, "hi")).state();

      state = reducer.reduce(state, new ConversationEvent.TextDelta(ID, "Hel")).state();
      state = reducer.reduce(state, new ConversationEvent.TextDelta(ID, "lo, ")).state();
      state = reducer.reduce(state, new ConversationEvent.TextDelta(ID, "world")).state();

      assertThat(state.pendingBlocks()).containsExactly(new TextBlock("Hello, world"));
    }

    @Test
    void text_deltas_produce_no_effects() {
      Step step = reducer.reduce(initial, new ConversationEvent.TextDelta(ID, "anything"));

      assertThat(step.effects()).isEmpty();
    }
  }

  @Nested
  class Turn_end {

    @Test
    void turn_end_with_no_tool_calls_settles_the_message_and_completes() {
      ConversationState state =
          reducer.reduce(initial, ConversationEvent.AgentTold.of(ID, "hi")).state();
      state = reducer.reduce(state, new ConversationEvent.TextDelta(ID, "Hello!")).state();

      Step step =
          reducer.reduce(
              state, new ConversationEvent.ModelTurnEnded(ID, StopReason.END_TURN, Usage.zero()));

      assertThat(step.state().messages())
          .containsExactly(Message.user("hi"), Message.assistant(List.of(new TextBlock("Hello!"))));
      assertThat(step.state().pendingBlocks()).isEmpty();
      assertThat(step.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(step.effects()).isEmpty();
    }

    @Test
    void a_turn_cut_off_at_the_token_ceiling_fails_rather_than_reporting_completion() {
      ConversationState state =
          reducer.reduce(initial, ConversationEvent.AgentTold.of(ID, "hi")).state();
      state = reducer.reduce(state, new ConversationEvent.TextDelta(ID, "Half a sen")).state();

      Step step =
          reducer.reduce(
              state, new ConversationEvent.ModelTurnEnded(ID, StopReason.MAX_TOKENS, Usage.zero()));

      assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
      assertThat(step.effects()).isEmpty();
      assertThat(step.state().messages())
          .containsExactly(
              Message.user("hi"), Message.assistant(List.of(new TextBlock("Half a sen"))));
    }

    @Test
    void turn_end_with_nothing_pending_adds_no_empty_message() {
      ConversationState state =
          reducer.reduce(initial, ConversationEvent.AgentTold.of(ID, "hi")).state();

      Step step =
          reducer.reduce(
              state, new ConversationEvent.ModelTurnEnded(ID, StopReason.END_TURN, Usage.zero()));

      assertThat(step.state().messages()).containsExactly(Message.user("hi"));
    }

    /**
     * {@code lastInputTokens} is what a {@code Compactor} reads to decide whether to fire; this
     * pins the one place it gets written, independent of any compaction test.
     */
    @Test
    void a_turn_end_records_the_measured_input_tokens() {
      Step step =
          reducer.reduce(
              initial,
              new ConversationEvent.ModelTurnEnded(
                  ID, StopReason.END_TURN, new Usage(120_000, 50, 0)));

      assertThat(step.state().lastInputTokens()).isEqualTo(120_000);
    }
  }
}
