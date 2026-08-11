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
package org.jwcarman.nessy.api.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

class ConversationStateFoldTest {

  private final ConversationId id = ConversationId.generate();
  private final ConversationState fresh = ConversationState.newConversation(id);

  // --- AgentTold ---

  @Test
  void a_tell_births_the_user_message_and_asks_for_the_model() {
    Step step = fresh.fold(ConversationEvent.AgentTold.of(id, "hello"));

    assertThat(step.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
    assertThat(step.remember()).containsExactly(Message.user(List.of(new TextBlock("hello"))));
    assertThat(step.effects()).containsExactly(Effect.callModel());
  }

  @Test
  void a_tell_starts_a_fresh_error_streak() {
    ConversationState scarred = fresh.withConsecutiveErrors(2).withFailureReason("old");
    Step step = scarred.fold(ConversationEvent.AgentTold.of(id, "again"));
    assertThat(step.state().consecutiveErrors()).isZero();
    assertThat(step.state().failureReason()).isNull();
  }

  @Test
  void a_misdelivered_fact_fails_loudly() {
    ConversationEvent stray = ConversationEvent.AgentTold.of(ConversationId.generate(), "lost");
    assertThatThrownBy(() -> fresh.fold(stray))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("misdelivered");
  }

  // --- ModelResponded ---

  @Test
  void a_clean_response_completes_the_turn() {
    Message answer = Message.assistant(List.of(new TextBlock("done")));
    Step step =
        awaitingModel()
            .fold(new ConversationEvent.ModelResponded(id, answer, StopReason.END_TURN, usage(7)));

    assertThat(step.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    assertThat(step.remember()).containsExactly(answer);
    assertThat(step.effects()).isEmpty();
    assertThat(step.state().modelCalls()).isEqualTo(1);
    assertThat(step.state().usage().inputTokens()).isEqualTo(7);
  }

  @Test
  void usage_and_model_calls_accumulate_across_two_tells() {
    Message firstAnswer = Message.assistant(List.of(new TextBlock("first")));
    ConversationState afterFirst =
        awaitingModel()
            .fold(
                new ConversationEvent.ModelResponded(
                    id, firstAnswer, StopReason.END_TURN, usage(3)))
            .state();

    ConversationState awaitingSecond =
        afterFirst.fold(ConversationEvent.AgentTold.of(id, "again")).state();
    Message secondAnswer = Message.assistant(List.of(new TextBlock("second")));
    ConversationState afterSecond =
        awaitingSecond
            .fold(
                new ConversationEvent.ModelResponded(
                    id, secondAnswer, StopReason.END_TURN, usage(4)))
            .state();

    assertThat(afterSecond.modelCalls()).isEqualTo(2);
    assertThat(afterSecond.usage().inputTokens()).isEqualTo(7);
  }

  @Test
  void homework_fans_out_one_effect_per_call() {
    ToolCall first = call("call-1", "search");
    ToolCall second = call("call-2", "fetch");
    Message homework =
        Message.assistant(List.of(new ToolUseBlock(first), new ToolUseBlock(second)));
    Step step =
        awaitingModel()
            .fold(
                new ConversationEvent.ModelResponded(id, homework, StopReason.TOOL_USE, usage(3)));

    assertThat(step.state().status()).isEqualTo(ConversationStatus.EXECUTING_TOOL);
    assertThat(step.state().pendingCalls()).containsExactly(first, second);
    assertThat(step.effects())
        .containsExactly(new Effect.ExecuteTool(first), new Effect.ExecuteTool(second));
  }

  @Test
  void a_token_ceiling_response_fails_the_conversation_and_answers_its_own_homework() {
    ToolCall orphan = call("call-1", "search");
    Message truncatedAssistantMessage = Message.assistant(List.of(new ToolUseBlock(orphan)));
    Step step =
        awaitingModel()
            .fold(
                new ConversationEvent.ModelResponded(
                    id, truncatedAssistantMessage, StopReason.MAX_TOKENS, usage(3)));

    assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
    assertThat(step.state().failureReason()).contains("MAX_TOKENS");
    assertThat(step.state().pendingCalls()).isEmpty();
    // the truncated message AND the abandoned-results flush are both remembered, in that
    // order, so the record never holds a tool_use without its tool_result
    Message expectedAbandonedFlushMessage =
        Message.toolResults(
            List.<ContentBlock>of(
                new ToolResultBlock(
                    orphan.id(),
                    "Abandoned: the conversation failed before this tool ran.",
                    true)));
    assertThat(step.remember())
        .containsExactly(truncatedAssistantMessage, expectedAbandonedFlushMessage);
    assertThat(step.effects()).isEmpty();
  }

  @Test
  void a_refusal_fails_the_conversation() {
    Message refusal = Message.assistant(List.of(new TextBlock("no")));
    Step step =
        awaitingModel()
            .fold(new ConversationEvent.ModelResponded(id, refusal, StopReason.REFUSAL, usage(1)));
    assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
    assertThat(step.state().failureReason()).contains("REFUSAL");
  }

  // --- ToolFinished ---

  @Test
  void results_fold_in_any_order_and_the_flush_waits_for_the_last_one() {
    ToolCall first = call("call-1", "search");
    ToolCall second = call("call-2", "fetch");
    ConversationState owing = midHomework(first, second);

    Step afterSecond =
        owing.fold(new ConversationEvent.ToolFinished(id, second, ToolResult.ok("b")));
    assertThat(afterSecond.remember()).isEmpty();
    assertThat(afterSecond.effects()).isEmpty();
    assertThat(afterSecond.state().pendingCalls()).containsExactly(first);

    Step afterFirst =
        afterSecond.state().fold(new ConversationEvent.ToolFinished(id, first, ToolResult.ok("a")));
    assertThat(afterFirst.state().pendingCalls()).isEmpty();
    assertThat(afterFirst.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
    assertThat(afterFirst.remember()).hasSize(1); // the batched results message
    assertThat(afterFirst.effects()).containsExactly(Effect.callModel());
  }

  @Test
  void an_errored_result_grows_the_streak_and_a_success_resets_it() {
    ToolCall first = call("call-1", "search");
    ToolCall second = call("call-2", "fetch");
    ConversationState owing = midHomework(first, second);

    ConversationState afterError =
        owing.fold(new ConversationEvent.ToolFinished(id, first, ToolResult.error("boom"))).state();
    assertThat(afterError.consecutiveErrors()).isEqualTo(1);

    ConversationState afterSuccess =
        afterError
            .fold(new ConversationEvent.ToolFinished(id, second, ToolResult.ok("ok")))
            .state();
    assertThat(afterSuccess.consecutiveErrors()).isZero();
  }

  // --- ModelCallFailed ---

  @Test
  void a_failed_call_is_fate_not_data() {
    Step step =
        awaitingModel().fold(new ConversationEvent.ModelCallFailed(id, "context window exceeded"));
    assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
    assertThat(step.state().failureReason()).isEqualTo("context window exceeded");
    assertThat(step.remember()).isEmpty();
    assertThat(step.effects()).isEmpty();
  }

  // --- halted ---

  @Test
  void halting_mid_homework_answers_every_outstanding_call() {
    ToolCall owed = call("call-1", "search");
    ConversationState owing = midHomework(owed);

    Step step = owing.halted("hit the error ceiling");

    assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
    assertThat(step.state().failureReason()).isEqualTo("hit the error ceiling");
    assertThat(step.state().pendingCalls()).isEmpty();
    assertThat(step.remember()).hasSize(1); // abandoned-results flush
    assertThat(step.effects()).isEmpty();
  }

  @Test
  void halting_with_no_debt_remembers_nothing() {
    Step step = awaitingModel().halted("turn ceiling");
    assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
    assertThat(step.remember()).isEmpty();
  }

  // --- helpers ---

  private ConversationState awaitingModel() {
    return fresh.fold(ConversationEvent.AgentTold.of(id, "go")).state();
  }

  private ConversationState midHomework(ToolCall... calls) {
    List<ContentBlock> blocks =
        Arrays.stream(calls).map(call -> (ContentBlock) new ToolUseBlock(call)).toList();
    return awaitingModel()
        .fold(
            new ConversationEvent.ModelResponded(
                id, Message.assistant(blocks), StopReason.TOOL_USE, usage(1)))
        .state();
  }

  private static ToolCall call(String id, String name) {
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    return new ToolCall(id, name, args);
  }

  private static Usage usage(long inputTokens) {
    return new Usage(inputTokens, 0, 0);
  }
}
