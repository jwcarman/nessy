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
import org.jwcarman.nessy.api.CompactionPolicy;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.SessionStatus;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.TerminationPolicy;
import org.jwcarman.nessy.api.TextBlock;
import org.jwcarman.nessy.api.Usage;

class ReducerTextTest {

  private final Reducer reducer = Reducer.defaults();
  private final SessionState initial = SessionState.newSession(new SessionId("s1"));

  @Nested
  class User_input {

    @Test
    void user_input_is_recorded_and_asks_for_the_model() {
      Step step = reducer.reduce(initial, Event.UserSaid.of("what is 2+2?"));

      assertThat(step.state().messages()).containsExactly(Message.user("what is 2+2?"));
      assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
      assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void new_user_input_clears_the_error_streak() {
      SessionState state = initial.withConsecutiveErrors(2);

      Step step = reducer.reduce(state, Event.UserSaid.of("try again"));

      assertThat(step.state().consecutiveErrors()).isZero();
      assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
    }

    @Test
    void a_fresh_user_message_on_a_turn_exhausted_session_halts_instead_of_calling_the_model() {
      Reducer limited = new Reducer(TerminationPolicy.maxTurns(1), CompactionPolicy.disabled());
      SessionState exhausted = SessionState.newSession(new SessionId("s1")).withTurns(1);

      Step step = limited.reduce(exhausted, Event.UserSaid.of("more?"));

      assertThat(step.state().status()).isEqualTo(SessionStatus.FAILED);
      assertThat(step.state().failureReason()).contains("turn");
      assertThat(step.effects()).isEmpty();
    }
  }

  @Nested
  class Text_deltas {

    @Test
    void text_deltas_accumulate_into_a_single_pending_block() {
      SessionState state = reducer.reduce(initial, Event.UserSaid.of("hi")).state();

      state = reducer.reduce(state, new Event.TextDelta("Hel")).state();
      state = reducer.reduce(state, new Event.TextDelta("lo, ")).state();
      state = reducer.reduce(state, new Event.TextDelta("world")).state();

      assertThat(state.pendingBlocks()).containsExactly(new TextBlock("Hello, world"));
    }

    @Test
    void text_deltas_produce_no_effects() {
      Step step = reducer.reduce(initial, new Event.TextDelta("anything"));

      assertThat(step.effects()).isEmpty();
    }
  }

  @Nested
  class Turn_end {

    @Test
    void turn_end_with_no_tool_calls_settles_the_message_and_completes() {
      SessionState state = reducer.reduce(initial, Event.UserSaid.of("hi")).state();
      state = reducer.reduce(state, new Event.TextDelta("Hello!")).state();

      Step step =
          reducer.reduce(state, new Event.ModelTurnEnded(StopReason.END_TURN, Usage.zero()));

      assertThat(step.state().messages())
          .containsExactly(Message.user("hi"), Message.assistant(List.of(new TextBlock("Hello!"))));
      assertThat(step.state().pendingBlocks()).isEmpty();
      assertThat(step.state().status()).isEqualTo(SessionStatus.COMPLETE);
      assertThat(step.effects()).isEmpty();
    }

    @Test
    void a_turn_cut_off_at_the_token_ceiling_fails_rather_than_reporting_completion() {
      SessionState state = reducer.reduce(initial, Event.UserSaid.of("hi")).state();
      state = reducer.reduce(state, new Event.TextDelta("Half a sen")).state();

      Step step =
          reducer.reduce(state, new Event.ModelTurnEnded(StopReason.MAX_TOKENS, Usage.zero()));

      assertThat(step.state().status()).isEqualTo(SessionStatus.FAILED);
      assertThat(step.effects()).isEmpty();
      assertThat(step.state().messages())
          .containsExactly(
              Message.user("hi"), Message.assistant(List.of(new TextBlock("Half a sen"))));
    }

    @Test
    void turn_end_with_nothing_pending_adds_no_empty_message() {
      SessionState state = reducer.reduce(initial, Event.UserSaid.of("hi")).state();

      Step step =
          reducer.reduce(state, new Event.ModelTurnEnded(StopReason.END_TURN, Usage.zero()));

      assertThat(step.state().messages()).containsExactly(Message.user("hi"));
    }
  }
}
