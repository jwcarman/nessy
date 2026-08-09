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
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.Role;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.SessionStatus;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.TextBlock;
import org.jwcarman.nessy.api.ThinkingBlock;
import org.jwcarman.nessy.api.Usage;

class ReducerGrammarTest {

  private final Reducer reducer = Reducer.withDefaults();
  private final SessionState initial = SessionState.newSession(new SessionId("s1"));

  @Test
  void thinking_deltas_accumulate_into_a_single_thinking_block() {
    SessionState state = reducer.reduce(initial, Event.UserSaid.of("hi")).state();
    state = reducer.reduce(state, new Event.ThinkingDelta("Let me ")).state();
    state = reducer.reduce(state, new Event.ThinkingDelta("think.")).state();
    state = reducer.reduce(state, new Event.TextDelta("Answer.")).state();

    assertThat(state.pendingBlocks())
        .containsExactly(new ThinkingBlock("Let me think.", ""), new TextBlock("Answer."));
  }

  @Test
  void thinking_and_text_deltas_never_merge_across_each_other() {
    SessionState state = reducer.reduce(initial, Event.UserSaid.of("hi")).state();
    state = reducer.reduce(state, new Event.ThinkingDelta("First thought.")).state();
    state = reducer.reduce(state, new Event.TextDelta("Answer.")).state();
    state = reducer.reduce(state, new Event.ThinkingDelta("Second thought.")).state();

    assertThat(state.pendingBlocks())
        .containsExactly(
            new ThinkingBlock("First thought.", ""),
            new TextBlock("Answer."),
            new ThinkingBlock("Second thought.", ""));
  }

  @Test
  void turns_and_usage_accumulate_across_turn_ends() {
    SessionState state = reducer.reduce(initial, Event.UserSaid.of("hi")).state();
    state =
        reducer
            .reduce(state, new Event.ModelTurnEnded(StopReason.END_TURN, new Usage(100, 50)))
            .state();

    assertThat(state.turns()).isEqualTo(1);
    assertThat(state.usage()).isEqualTo(new Usage(100, 50));
  }

  @Test
  void token_ceiling_failure_records_its_reason() {
    SessionState state = reducer.reduce(initial, Event.UserSaid.of("hi")).state();
    state =
        reducer
            .reduce(state, new Event.ModelTurnEnded(StopReason.MAX_TOKENS, Usage.zero()))
            .state();

    assertThat(state.status()).isEqualTo(SessionStatus.FAILED);
    assertThat(state.failureReason()).contains("MAX_TOKENS");
  }

  @Test
  void user_said_carries_arbitrary_content_blocks() {
    Step step =
        reducer.reduce(initial, new Event.UserSaid(List.of(new TextBlock("describe this"))));

    assertThat(step.state().messages())
        .containsExactly(new Message(Role.USER, List.of(new TextBlock("describe this"))));
  }

  @Test
  void usage_addition_is_componentwise() {
    assertThat(Usage.zero().plus(new Usage(3, 4)).plus(new Usage(10, 20)))
        .isEqualTo(new Usage(13, 24));
  }
}
