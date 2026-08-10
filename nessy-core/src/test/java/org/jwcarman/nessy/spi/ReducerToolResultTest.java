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
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.TerminationPolicy;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.compaction.Compactor;

class ReducerToolResultTest {

  private static final ConversationId ID = new ConversationId("s1");

  private final Reducer reducer =
      new Reducer(TerminationPolicy.maxConsecutiveErrors(2), Compactor.disabled());
  private final ConversationState initial = ConversationState.newConversation(ID);

  private static ToolCall call(String id) {
    return new ToolCall(id, "read_file", JsonNodeFactory.instance.objectNode());
  }

  /** Drives the loop to the point where {@code calls} are pending approval. */
  private ConversationState awaitingApproval(ToolCall... calls) {
    return awaitingApprovalWith(reducer, calls);
  }

  /** Drives {@code reducer} to the point where {@code calls} are pending approval. */
  private ConversationState awaitingApprovalWith(Reducer reducer, ToolCall... calls) {
    ConversationState state = initial;
    for (ToolCall each : calls) {
      state = reducer.reduce(state, new ConversationEvent.ToolCallRequested(ID, each)).state();
    }
    return reducer
        .reduce(state, new ConversationEvent.ModelTurnEnded(ID, StopReason.TOOL_USE, Usage.zero()))
        .state();
  }

  @Nested
  class Approval_decisions {

    @Test
    void approval_asks_for_execution() {
      ToolCall toolCall = call("c1");
      ConversationState state = awaitingApproval(toolCall);

      Step step =
          reducer.reduce(
              state, new ConversationEvent.ApprovalDecided(ID, toolCall, Decision.allow()));

      assertThat(step.state().status()).isEqualTo(ConversationStatus.EXECUTING_TOOL);
      assertThat(step.effects()).containsExactly(new Effect.ExecuteTool(toolCall));
    }

    @Test
    void denial_becomes_an_errored_result_the_model_can_see() {
      ToolCall toolCall = call("c1");
      ConversationState state = awaitingApproval(toolCall);

      Step step =
          reducer.reduce(
              state,
              new ConversationEvent.ApprovalDecided(ID, toolCall, new Decision.Deny("no thanks")));

      assertThat(step.state().messages().getLast().content())
          .containsExactly(new ToolResultBlock("c1", "Denied by user: no thanks", true));
      assertThat(step.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
      assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void a_denial_counts_toward_the_error_ceiling() {
      ToolCall toolCall = call("c1");
      ConversationState state = awaitingApproval(toolCall);

      Step step =
          reducer.reduce(
              state,
              new ConversationEvent.ApprovalDecided(ID, toolCall, new Decision.Deny("no thanks")));

      assertThat(step.state().consecutiveErrors()).isEqualTo(1);
    }
  }

  @Nested
  class Batching {

    @Test
    void a_finished_tool_flushes_results_and_calls_the_model_again() {
      ToolCall toolCall = call("c1");
      ConversationState state = awaitingApproval(toolCall);
      state =
          reducer
              .reduce(state, new ConversationEvent.ApprovalDecided(ID, toolCall, Decision.allow()))
              .state();

      Step step =
          reducer.reduce(
              state,
              new ConversationEvent.ToolFinished(ID, toolCall, ToolResult.ok("file contents")));

      assertThat(step.state().messages().getLast())
          .isEqualTo(
              Message.toolResults(List.of(new ToolResultBlock("c1", "file contents", false))));
      assertThat(step.state().pendingCalls()).isEmpty();
      assertThat(step.state().pendingResults()).isEmpty();
      assertThat(step.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
      assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void a_result_for_a_call_id_not_among_the_pending_calls_is_still_recorded_and_drops_nothing() {
      ToolCall first = call("c1");
      ToolCall second = call("c2");
      ConversationState state = awaitingApproval(first, second);
      state =
          reducer
              .reduce(state, new ConversationEvent.ApprovalDecided(ID, first, Decision.allow()))
              .state();

      ToolCall unknown = call("unknown-id");
      Step step =
          reducer.reduce(
              state, new ConversationEvent.ToolFinished(ID, unknown, ToolResult.ok("stray")));

      assertThat(step.state().pendingResults())
          .containsExactly(new ToolResultBlock("unknown-id", "stray", false));
      assertThat(step.state().pendingCalls()).containsExactly(first, second);
    }

    @Test
    void results_are_batched_into_one_message_when_several_calls_are_pending() {
      ToolCall first = call("c1");
      ToolCall second = call("c2");
      ConversationState state = awaitingApproval(first, second);

      state =
          reducer
              .reduce(state, new ConversationEvent.ApprovalDecided(ID, first, Decision.allow()))
              .state();
      Step afterFirst =
          reducer.reduce(
              state, new ConversationEvent.ToolFinished(ID, first, ToolResult.ok("one")));

      assertThat(afterFirst.state().pendingResults()).hasSize(1);
      assertThat(afterFirst.effects()).containsExactly(new Effect.RequestApproval(second));

      ConversationState afterApproval =
          reducer
              .reduce(
                  afterFirst.state(),
                  new ConversationEvent.ApprovalDecided(ID, second, Decision.allow()))
              .state();
      Step afterSecond =
          reducer.reduce(
              afterApproval, new ConversationEvent.ToolFinished(ID, second, ToolResult.ok("two")));

      assertThat(afterSecond.state().messages().getLast().content())
          .containsExactly(
              new ToolResultBlock("c1", "one", false), new ToolResultBlock("c2", "two", false));
      assertThat(afterSecond.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void a_turn_cut_off_at_the_token_ceiling_with_calls_still_pending_answers_every_one_of_them() {
      ToolCall first = call("c1");
      ToolCall second = call("c2");
      ConversationState state = initial;
      for (ToolCall each : List.of(first, second)) {
        state = reducer.reduce(state, new ConversationEvent.ToolCallRequested(ID, each)).state();
      }

      Step step =
          reducer.reduce(
              state, new ConversationEvent.ModelTurnEnded(ID, StopReason.MAX_TOKENS, Usage.zero()));

      assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
      assertThat(step.effects()).isEmpty();
      assertThat(step.state().pendingCalls()).isEmpty();
      assertThat(step.state().messages().getLast().content())
          .extracting(block -> ((ToolResultBlock) block).toolUseId())
          .containsExactly("c1", "c2");
      assertThat(step.state().messages().getLast().content())
          .allMatch(block -> ((ToolResultBlock) block).isError());
    }

    @Test
    void a_refusal_fails_loudly_and_still_answers_every_pending_tool_use() {
      ToolCall first = call("c1");
      ToolCall second = call("c2");
      ConversationState state = awaitingApprovalWith(reducer, first, second);

      Step step =
          reducer.reduce(
              state, new ConversationEvent.ModelTurnEnded(ID, StopReason.REFUSAL, Usage.zero()));

      assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
      assertThat(step.state().failureReason()).contains("REFUSAL");
      assertThat(step.state().pendingCalls()).isEmpty();
      assertThat(step.effects()).isEmpty();
    }
  }

  @Nested
  class The_error_ceiling {

    @Test
    void a_successful_result_resets_the_error_count() {
      ToolCall toolCall = call("c1");
      ConversationState state = awaitingApproval(toolCall).withConsecutiveErrors(1);
      state =
          reducer
              .reduce(state, new ConversationEvent.ApprovalDecided(ID, toolCall, Decision.allow()))
              .state();

      Step step =
          reducer.reduce(
              state, new ConversationEvent.ToolFinished(ID, toolCall, ToolResult.ok("fine")));

      assertThat(step.state().consecutiveErrors()).isZero();
    }

    @Test
    void an_error_below_the_ceiling_keeps_the_session_going() {
      ToolCall toolCall = call("c1");
      ConversationState state = awaitingApproval(toolCall);
      state =
          reducer
              .reduce(state, new ConversationEvent.ApprovalDecided(ID, toolCall, Decision.allow()))
              .state();

      Step step =
          reducer.reduce(
              state, new ConversationEvent.ToolFinished(ID, toolCall, ToolResult.error("boom")));

      assertThat(step.state().consecutiveErrors()).isEqualTo(1);
      assertThat(step.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
      assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void failing_with_calls_still_pending_answers_every_one_of_them() {
      Reducer strict = new Reducer(TerminationPolicy.maxConsecutiveErrors(1), Compactor.disabled());
      ToolCall first = call("c1");
      ToolCall second = call("c2");
      ConversationState state = initial;
      for (ToolCall each : List.of(first, second)) {
        state = strict.reduce(state, new ConversationEvent.ToolCallRequested(ID, each)).state();
      }
      state =
          strict
              .reduce(
                  state,
                  new ConversationEvent.ModelTurnEnded(ID, StopReason.TOOL_USE, Usage.zero()))
              .state();
      state =
          strict
              .reduce(state, new ConversationEvent.ApprovalDecided(ID, first, Decision.allow()))
              .state();

      Step step =
          strict.reduce(
              state, new ConversationEvent.ToolFinished(ID, first, ToolResult.error("boom")));

      assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
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
      ConversationState state = awaitingApproval(toolCall).withConsecutiveErrors(1);
      state =
          reducer
              .reduce(state, new ConversationEvent.ApprovalDecided(ID, toolCall, Decision.allow()))
              .state();

      Step step =
          reducer.reduce(
              state, new ConversationEvent.ToolFinished(ID, toolCall, ToolResult.error("boom")));

      assertThat(step.state().consecutiveErrors()).isEqualTo(2);
      assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
      assertThat(step.effects()).isEmpty();
      assertThat(step.state().messages().getLast().content())
          .containsExactly(new ToolResultBlock("c1", "boom", true));
    }

    @Test
    void halting_mid_batch_still_answers_every_pending_tool_use() {
      Reducer limited =
          new Reducer(TerminationPolicy.maxConsecutiveErrors(1), Compactor.disabled());
      ToolCall first = call("c1");
      ToolCall second = call("c2");
      ConversationState state = awaitingApprovalWith(limited, first, second);
      state =
          limited
              .reduce(state, new ConversationEvent.ApprovalDecided(ID, first, Decision.allow()))
              .state();

      Step step =
          limited.reduce(
              state, new ConversationEvent.ToolFinished(ID, first, ToolResult.error("boom")));

      assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
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
    Reducer limited = new Reducer(TerminationPolicy.maxTurns(1), Compactor.disabled());
    ToolCall first = call("c1");
    ToolCall second = call("c2");
    ConversationState state = awaitingApprovalWith(limited, first, second);

    Step step = limited.reduce(state, ConversationEvent.UserSaid.of(ID, "more?"));

    assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
    assertThat(step.state().failureReason()).contains("turn");
    assertThat(step.state().pendingCalls()).isEmpty();
    assertThat(step.state().messages().getLast().content())
        .extracting("toolUseId")
        .containsExactly("c1", "c2");
    assertThat(step.effects()).isEmpty();
  }

  @Test
  void a_fresh_user_message_clears_a_stale_failure_reason_from_a_resumed_session() {
    Reducer permissive = new Reducer(TerminationPolicy.never(), Compactor.disabled());
    ConversationState failed =
        initial.withConsecutiveErrors(3).withFailureReason("3 consecutive tool errors");

    Step step = permissive.reduce(failed, ConversationEvent.UserSaid.of(ID, "try again"));

    assertThat(step.state().failureReason()).isNull();
    assertThat(step.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
  }
}
