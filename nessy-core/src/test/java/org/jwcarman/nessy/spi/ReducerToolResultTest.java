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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.SessionStatus;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.TerminationPolicy;
import org.jwcarman.nessy.api.ToolCall;
import org.jwcarman.nessy.api.ToolResult;
import org.jwcarman.nessy.api.ToolResultBlock;
import org.jwcarman.nessy.api.Usage;

class ReducerToolResultTest {

  private final Reducer reducer = new Reducer(TerminationPolicy.maxConsecutiveErrors(2));
  private final SessionState initial = SessionState.newSession(new SessionId("s1"));

  private static ToolCall call(String id) {
    return new ToolCall(id, "read_file", JsonNodeFactory.instance.objectNode());
  }

  /** Drives the loop to the point where {@code calls} are pending approval. */
  private SessionState awaitingApproval(ToolCall... calls) {
    return awaitingApprovalWith(reducer, calls);
  }

  /** Drives {@code reducer} to the point where {@code calls} are pending approval. */
  private SessionState awaitingApprovalWith(Reducer reducer, ToolCall... calls) {
    SessionState state = initial;
    for (ToolCall each : calls) {
      state = reducer.reduce(state, new Event.ToolCallRequested(each)).state();
    }
    return reducer
        .reduce(state, new Event.ModelTurnEnded(StopReason.TOOL_USE, Usage.zero()))
        .state();
  }

  @Nested
  class Approval_decisions {

    @Test
    void approval_asks_for_execution() {
      ToolCall toolCall = call("c1");
      SessionState state = awaitingApproval(toolCall);

      Step step = reducer.reduce(state, new Event.ApprovalDecided(toolCall, Decision.allow()));

      assertThat(step.state().status()).isEqualTo(SessionStatus.EXECUTING_TOOL);
      assertThat(step.effects()).containsExactly(new Effect.ExecuteTool(toolCall));
    }

    @Test
    void denial_becomes_an_errored_result_the_model_can_see() {
      ToolCall toolCall = call("c1");
      SessionState state = awaitingApproval(toolCall);

      Step step =
          reducer.reduce(
              state, new Event.ApprovalDecided(toolCall, new Decision.Deny("no thanks")));

      assertThat(step.state().messages().getLast().content())
          .containsExactly(new ToolResultBlock("c1", "Denied by user: no thanks", true));
      assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
      assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void a_denial_counts_toward_the_error_ceiling() {
      ToolCall toolCall = call("c1");
      SessionState state = awaitingApproval(toolCall);

      Step step =
          reducer.reduce(
              state, new Event.ApprovalDecided(toolCall, new Decision.Deny("no thanks")));

      assertThat(step.state().consecutiveErrors()).isEqualTo(1);
    }
  }

  @Nested
  class Batching {

    @Test
    void a_finished_tool_flushes_results_and_calls_the_model_again() {
      ToolCall toolCall = call("c1");
      SessionState state = awaitingApproval(toolCall);
      state = reducer.reduce(state, new Event.ApprovalDecided(toolCall, Decision.allow())).state();

      Step step =
          reducer.reduce(state, new Event.ToolFinished(toolCall, ToolResult.ok("file contents")));

      assertThat(step.state().messages().getLast())
          .isEqualTo(
              Message.toolResults(List.of(new ToolResultBlock("c1", "file contents", false))));
      assertThat(step.state().pendingCalls()).isEmpty();
      assertThat(step.state().pendingResults()).isEmpty();
      assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
      assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void results_are_batched_into_one_message_when_several_calls_are_pending() {
      ToolCall first = call("c1");
      ToolCall second = call("c2");
      SessionState state = awaitingApproval(first, second);

      state = reducer.reduce(state, new Event.ApprovalDecided(first, Decision.allow())).state();
      Step afterFirst = reducer.reduce(state, new Event.ToolFinished(first, ToolResult.ok("one")));

      assertThat(afterFirst.state().pendingResults()).hasSize(1);
      assertThat(afterFirst.effects()).containsExactly(new Effect.RequestApproval(second));

      SessionState afterApproval =
          reducer
              .reduce(afterFirst.state(), new Event.ApprovalDecided(second, Decision.allow()))
              .state();
      Step afterSecond =
          reducer.reduce(afterApproval, new Event.ToolFinished(second, ToolResult.ok("two")));

      assertThat(afterSecond.state().messages().getLast().content())
          .containsExactly(
              new ToolResultBlock("c1", "one", false), new ToolResultBlock("c2", "two", false));
      assertThat(afterSecond.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void a_turn_cut_off_at_the_token_ceiling_with_calls_still_pending_answers_every_one_of_them() {
      ToolCall first = call("c1");
      ToolCall second = call("c2");
      SessionState state = initial;
      for (ToolCall each : List.of(first, second)) {
        state = reducer.reduce(state, new Event.ToolCallRequested(each)).state();
      }

      Step step =
          reducer.reduce(state, new Event.ModelTurnEnded(StopReason.MAX_TOKENS, Usage.zero()));

      assertThat(step.state().status()).isEqualTo(SessionStatus.FAILED);
      assertThat(step.effects()).isEmpty();
      assertThat(step.state().pendingCalls()).isEmpty();
      assertThat(step.state().messages().getLast().content())
          .extracting(block -> ((ToolResultBlock) block).toolUseId())
          .containsExactly("c1", "c2");
      assertThat(step.state().messages().getLast().content())
          .allMatch(block -> ((ToolResultBlock) block).isError());
    }
  }

  @Nested
  class The_error_ceiling {

    @Test
    void a_successful_result_resets_the_error_count() {
      ToolCall toolCall = call("c1");
      SessionState state = awaitingApproval(toolCall).withConsecutiveErrors(1);
      state = reducer.reduce(state, new Event.ApprovalDecided(toolCall, Decision.allow())).state();

      Step step = reducer.reduce(state, new Event.ToolFinished(toolCall, ToolResult.ok("fine")));

      assertThat(step.state().consecutiveErrors()).isZero();
    }

    @Test
    void an_error_below_the_ceiling_keeps_the_session_going() {
      ToolCall toolCall = call("c1");
      SessionState state = awaitingApproval(toolCall);
      state = reducer.reduce(state, new Event.ApprovalDecided(toolCall, Decision.allow())).state();

      Step step = reducer.reduce(state, new Event.ToolFinished(toolCall, ToolResult.error("boom")));

      assertThat(step.state().consecutiveErrors()).isEqualTo(1);
      assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
      assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void failing_with_calls_still_pending_answers_every_one_of_them() {
      Reducer strict = new Reducer(TerminationPolicy.maxConsecutiveErrors(1));
      ToolCall first = call("c1");
      ToolCall second = call("c2");
      SessionState state = initial;
      for (ToolCall each : List.of(first, second)) {
        state = strict.reduce(state, new Event.ToolCallRequested(each)).state();
      }
      state =
          strict.reduce(state, new Event.ModelTurnEnded(StopReason.TOOL_USE, Usage.zero())).state();
      state = strict.reduce(state, new Event.ApprovalDecided(first, Decision.allow())).state();

      Step step = strict.reduce(state, new Event.ToolFinished(first, ToolResult.error("boom")));

      assertThat(step.state().status()).isEqualTo(SessionStatus.FAILED);
      assertThat(step.state().pendingCalls()).isEmpty();
      assertThat(step.state().messages().getLast().content())
          .extracting(block -> ((ToolResultBlock) block).toolUseId())
          .containsExactly("c1", "c2");
      assertThat(step.state().messages().getLast().content())
          .allMatch(block -> ((ToolResultBlock) block).isError());
    }

    @Test
    void reaching_the_error_ceiling_fails_the_session_instead_of_looping() {
      ToolCall toolCall = call("c1");
      SessionState state = awaitingApproval(toolCall).withConsecutiveErrors(1);
      state = reducer.reduce(state, new Event.ApprovalDecided(toolCall, Decision.allow())).state();

      Step step = reducer.reduce(state, new Event.ToolFinished(toolCall, ToolResult.error("boom")));

      assertThat(step.state().consecutiveErrors()).isEqualTo(2);
      assertThat(step.state().status()).isEqualTo(SessionStatus.FAILED);
      assertThat(step.effects()).isEmpty();
      assertThat(step.state().messages().getLast().content())
          .containsExactly(new ToolResultBlock("c1", "boom", true));
    }

    @Test
    void halting_mid_batch_still_answers_every_pending_tool_use() {
      Reducer limited = new Reducer(TerminationPolicy.maxConsecutiveErrors(1));
      ToolCall first = call("c1");
      ToolCall second = call("c2");
      SessionState state = awaitingApprovalWith(limited, first, second);
      state = limited.reduce(state, new Event.ApprovalDecided(first, Decision.allow())).state();

      Step step = limited.reduce(state, new Event.ToolFinished(first, ToolResult.error("boom")));

      assertThat(step.state().status()).isEqualTo(SessionStatus.FAILED);
      assertThat(step.state().failureReason()).contains("consecutive");
      assertThat(step.state().pendingCalls()).isEmpty();
      assertThat(step.state().messages().getLast().content())
          .extracting("toolUseId")
          .containsExactly("c1", "c2");
      assertThat(step.effects()).isEmpty();
    }
  }

  @Test
  void a_user_message_that_trips_the_turn_ceiling_still_answers_every_pending_tool_use() {
    Reducer limited = new Reducer(TerminationPolicy.maxTurns(1));
    ToolCall first = call("c1");
    ToolCall second = call("c2");
    SessionState state = awaitingApprovalWith(limited, first, second);

    Step step = limited.reduce(state, Event.UserSaid.of("more?"));

    assertThat(step.state().status()).isEqualTo(SessionStatus.FAILED);
    assertThat(step.state().failureReason()).contains("turn");
    assertThat(step.state().pendingCalls()).isEmpty();
    assertThat(step.state().messages().getLast().content())
        .extracting("toolUseId")
        .containsExactly("c1", "c2");
    assertThat(step.effects()).isEmpty();
  }

  @Test
  void a_fresh_user_message_clears_a_stale_failure_reason_from_a_resumed_session() {
    Reducer permissive = new Reducer(TerminationPolicy.never());
    SessionState failed =
        initial.withConsecutiveErrors(3).withFailureReason("3 consecutive tool errors");

    Step step = permissive.reduce(failed, Event.UserSaid.of("try again"));

    assertThat(step.state().failureReason()).isNull();
    assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
  }
}
