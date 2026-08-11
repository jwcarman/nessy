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
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

class ConversationStateFoldTest {

  private final ConversationId id = ConversationId.generate();
  private final ConversationState fresh = ConversationState.newConversation(id);

  // --- AgentTold ---

  @Test
  void aTellBirthsTheUserMessageAndAsksForTheModel() {
    Step step = fresh.fold(ConversationEvent.AgentTold.of(id, "hello"));

    assertThat(step.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
    assertThat(step.remember()).containsExactly(Message.user(List.of(new TextBlock("hello"))));
    assertThat(step.effects()).containsExactly(Effect.callModel());
  }

  @Test
  void aTellStartsAFreshErrorStreak() {
    ConversationState scarred = fresh.withConsecutiveErrors(2);
    Step step = scarred.fold(ConversationEvent.AgentTold.of(id, "again"));
    assertThat(step.state().consecutiveErrors()).isZero();
  }

  @Test
  void aMisdeliveredFactFailsLoudly() {
    ConversationEvent stray = ConversationEvent.AgentTold.of(ConversationId.generate(), "lost");
    assertThatThrownBy(() -> fresh.fold(stray))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("misdelivered");
  }

  // --- ModelResponded ---

  @Test
  void aCleanResponseCompletesTheTurn() {
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
  void homeworkFansOutOneEffectPerCall() {
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
  void aTokenCeilingResponseFailsTheConversationAndAnswersItsOwnHomework() {
    ToolCall orphan = call("call-1", "search");
    Message truncated = Message.assistant(List.of(new ToolUseBlock(orphan)));
    Step step =
        awaitingModel()
            .fold(
                new ConversationEvent.ModelResponded(
                    id, truncated, StopReason.MAX_TOKENS, usage(3)));

    assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
    assertThat(step.state().failureReason()).contains("MAX_TOKENS");
    assertThat(step.state().pendingCalls()).isEmpty();
    // the truncated message AND the abandoned-results flush are both remembered,
    // so the record never holds a tool_use without its tool_result
    assertThat(step.remember()).hasSize(2);
    assertThat(step.effects()).isEmpty();
  }

  @Test
  void aRefusalFailsTheConversation() {
    Message refusal = Message.assistant(List.of(new TextBlock("no")));
    Step step =
        awaitingModel()
            .fold(new ConversationEvent.ModelResponded(id, refusal, StopReason.REFUSAL, usage(1)));
    assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
    assertThat(step.state().failureReason()).contains("REFUSAL");
  }

  // --- ToolFinished ---

  @Test
  void resultsFoldInAnyOrderAndTheFlushWaitsForTheLastOne() {
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
  void anErroredResultGrowsTheStreakAndASuccessResetsIt() {
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
  void aFailedCallIsFateNotData() {
    Step step =
        awaitingModel().fold(new ConversationEvent.ModelCallFailed(id, "context window exceeded"));
    assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
    assertThat(step.state().failureReason()).isEqualTo("context window exceeded");
    assertThat(step.remember()).isEmpty();
    assertThat(step.effects()).isEmpty();
  }

  // --- halted ---

  @Test
  void haltingMidHomeworkAnswersEveryOutstandingCall() {
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
  void haltingWithNoDebtRemembersNothing() {
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
