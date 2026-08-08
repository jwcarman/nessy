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
package org.jwcarman.nessy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReducerTextTest {

  private final Reducer reducer = Reducer.withDefaults();
  private final SessionState initial = SessionState.newSession(new SessionId("s1"));

  @Test
  void userInputIsRecordedAndAsksForTheModel() {
    Step step = reducer.reduce(initial, new Event.UserSaid("what is 2+2?"));

    assertThat(step.state().messages()).containsExactly(Message.user("what is 2+2?"));
    assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
    assertThat(step.effects()).containsExactly(Effect.callModel());
  }

  @Test
  void textDeltasAccumulateIntoASinglePendingBlock() {
    SessionState state = reducer.reduce(initial, new Event.UserSaid("hi")).state();

    state = reducer.reduce(state, new Event.TextDelta("Hel")).state();
    state = reducer.reduce(state, new Event.TextDelta("lo, ")).state();
    state = reducer.reduce(state, new Event.TextDelta("world")).state();

    assertThat(state.pendingBlocks()).containsExactly(new TextBlock("Hello, world"));
  }

  @Test
  void textDeltasProduceNoEffects() {
    Step step = reducer.reduce(initial, new Event.TextDelta("anything"));

    assertThat(step.effects()).isEmpty();
  }

  @Test
  void turnEndWithNoToolCallsSettlesTheMessageAndCompletes() {
    SessionState state = reducer.reduce(initial, new Event.UserSaid("hi")).state();
    state = reducer.reduce(state, new Event.TextDelta("Hello!")).state();

    Step step = reducer.reduce(state, new Event.ModelTurnEnded(StopReason.END_TURN));

    assertThat(step.state().messages())
        .containsExactly(Message.user("hi"), Message.assistant(List.of(new TextBlock("Hello!"))));
    assertThat(step.state().pendingBlocks()).isEmpty();
    assertThat(step.state().status()).isEqualTo(SessionStatus.COMPLETE);
    assertThat(step.effects()).isEmpty();
  }

  @Test
  void newUserInputClearsTheErrorStreak() {
    SessionState state = initial.withConsecutiveErrors(2);

    Step step = reducer.reduce(state, new Event.UserSaid("try again"));

    assertThat(step.state().consecutiveErrors()).isZero();
    assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
  }

  @Test
  void aTurnCutOffAtTheTokenCeilingFailsRatherThanReportingCompletion() {
    SessionState state = reducer.reduce(initial, new Event.UserSaid("hi")).state();
    state = reducer.reduce(state, new Event.TextDelta("Half a sen")).state();

    Step step = reducer.reduce(state, new Event.ModelTurnEnded(StopReason.MAX_TOKENS));

    assertThat(step.state().status()).isEqualTo(SessionStatus.FAILED);
    assertThat(step.effects()).isEmpty();
    assertThat(step.state().messages())
        .containsExactly(
            Message.user("hi"), Message.assistant(List.of(new TextBlock("Half a sen"))));
  }

  @Test
  void turnEndWithNothingPendingAddsNoEmptyMessage() {
    SessionState state = reducer.reduce(initial, new Event.UserSaid("hi")).state();

    Step step = reducer.reduce(state, new Event.ModelTurnEnded(StopReason.END_TURN));

    assertThat(step.state().messages()).containsExactly(Message.user("hi"));
  }
}
