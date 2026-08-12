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
import org.jwcarman.nessy.api.ParkToken;
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
  void a_told_fact_is_a_pure_note() {
    ConversationState scarred = fresh.withConsecutiveErrors(2);
    Step step = scarred.fold(ConversationEvent.AgentTold.of(id, "psst"));

    assertThat(step.state().told()).containsExactly(List.of(new TextBlock("psst")));
    assertThat(step.state().status()).isEqualTo(scarred.status());
    assertThat(step.state().consecutiveErrors()).isEqualTo(2);
    assertThat(step.remember()).isEmpty();
    assertThat(step.effects()).isEmpty();
  }

  @Test
  void a_misdelivered_fact_fails_loudly() {
    ConversationEvent stray = ConversationEvent.AgentTold.of(ConversationId.generate(), "lost");
    assertThatThrownBy(() -> fresh.fold(stray))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("misdelivered");
  }

  // --- openTurn ---

  @Test
  void open_turn_merges_every_note_into_one_user_message_in_arrival_order() {
    TextBlock a = new TextBlock("a");
    TextBlock b = new TextBlock("b");
    TextBlock c = new TextBlock("c");
    ConversationState scarred =
        fresh
            .withConsecutiveErrors(2)
            .withFailureReason("old failure")
            .fold(new ConversationEvent.AgentTold(id, List.of(a)))
            .state()
            .fold(new ConversationEvent.AgentTold(id, List.of(b)))
            .state()
            .fold(new ConversationEvent.AgentTold(id, List.of(c)))
            .state();

    Step step = scarred.openTurn();

    assertThat(step.remember()).containsExactly(Message.user(List.of(a, b, c)));
    assertThat(step.state().told()).isEmpty();
    assertThat(step.state().consecutiveErrors()).isZero();
    assertThat(step.state().failureReason()).isNull();
    assertThat(step.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
    assertThat(step.effects()).containsExactly(Effect.callModel());
  }

  @Test
  void open_turn_refuses_to_open_over_an_open_turn() {
    ConversationState open =
        fresh
            .fold(ConversationEvent.AgentTold.of(id, "go"))
            .state()
            .with(ConversationStatus.AWAITING_MODEL);

    assertThatThrownBy(open::openTurn)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AWAITING_MODEL");
  }

  @Test
  void open_turn_refuses_to_open_with_nothing_to_say() {
    assertThatThrownBy(fresh::openTurn).isInstanceOf(IllegalStateException.class);
  }

  // --- ModelResponded ---

  @Test
  void a_clean_response_completes_the_turn() {
    Message answer = Message.assistant(List.of(new TextBlock("done")));
    Step step =
        awaitingModel()
            .fold(new ConversationEvent.ModelResponded(id, answer, StopReason.END_TURN, usage(7)));

    assertThat(step.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    // The empty-lane clause: nothing left to continue with.
    assertThat(step.state().told()).isEmpty();
    assertThat(step.remember()).containsExactly(answer);
    assertThat(step.effects()).isEmpty();
    assertThat(step.state().modelCalls()).isEqualTo(1);
    assertThat(step.state().usage().inputTokens()).isEqualTo(7);
  }

  @Test
  void a_clean_response_with_no_notes_still_completes() {
    Message answer = Message.assistant(List.of(new TextBlock("done")));
    Step step =
        awaitingModel()
            .fold(new ConversationEvent.ModelResponded(id, answer, StopReason.END_TURN, usage(1)));

    assertThat(step.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    assertThat(step.state().told()).isEmpty();
    assertThat(step.remember()).containsExactly(answer);
    assertThat(step.effects()).isEmpty();
  }

  @Test
  void a_clean_response_with_unread_notes_continues_instead_of_completing() {
    ConversationState mid =
        awaitingModel()
            .withConsecutiveErrors(3)
            .fold(ConversationEvent.AgentTold.of(id, "psst"))
            .state();
    Message answer = Message.assistant(List.of(new TextBlock("done")));

    Step step =
        mid.fold(new ConversationEvent.ModelResponded(id, answer, StopReason.END_TURN, usage(2)));

    assertThat(step.remember())
        .containsExactly(answer, Message.user(List.of(new TextBlock("psst"))));
    assertThat(step.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
    assertThat(step.effects()).containsExactly(Effect.callModel());
    assertThat(step.state().told()).isEmpty();
    // Mid-turn tells don't reset the streak; only openTurn does.
    assertThat(step.state().consecutiveErrors()).isEqualTo(3);
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
        afterFirst.fold(ConversationEvent.AgentTold.of(id, "again")).state().openTurn().state();
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
  void homework_never_jumps_the_queue_ahead_of_unread_notes() {
    ToolCall call = call("call-1", "search");
    Message homework = Message.assistant(List.of(new ToolUseBlock(call)));
    ConversationState mid =
        awaitingModel().fold(ConversationEvent.AgentTold.of(id, "psst")).state();

    Step step =
        mid.fold(new ConversationEvent.ModelResponded(id, homework, StopReason.TOOL_USE, usage(3)));

    assertThat(step.state().status()).isEqualTo(ConversationStatus.EXECUTING_TOOL);
    assertThat(step.effects()).containsExactly(new Effect.ExecuteTool(call));
    assertThat(step.remember()).containsExactly(homework);
    // the note is not consumed here: it rides the eventual flush, once the homework settles
    assertThat(step.state().told()).containsExactly(List.of(new TextBlock("psst")));
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

  @Test
  void notes_ride_the_flush_beside_the_results() {
    ToolCall owed = call("call-1", "search");
    ConversationState owing =
        midHomework(owed).fold(ConversationEvent.AgentTold.of(id, "psst")).state();

    Step step = owing.fold(new ConversationEvent.ToolFinished(id, owed, ToolResult.ok("found")));

    Message expectedFlush =
        Message.toolResults(
            List.<ContentBlock>of(
                new ToolResultBlock("call-1", "found", false), new TextBlock("psst")));
    assertThat(step.remember()).containsExactly(expectedFlush);
    assertThat(step.state().told()).isEmpty();
    assertThat(step.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
    assertThat(step.effects()).containsExactly(Effect.callModel());
  }

  @Test
  void a_resumed_call_finishing_clears_its_park() {
    ToolCall resumed = call("call-1", "search");
    ParkedCall parked = new ParkedCall(ParkToken.generate(), resumed);
    ConversationState owing = awaitingModel().withParkedCalls(List.of(parked));

    Step step = owing.fold(new ConversationEvent.ToolFinished(id, resumed, ToolResult.ok("found")));

    assertThat(step.state().parkedCalls()).isEmpty();
  }

  /**
   * Opus fix round 1, Finding 1 (Critical): the last <em>pending</em> sibling settling must not
   * flush while a <em>parked</em> sibling is still outstanding — that would answer the wire with
   * {@code [result:c2]} alone, leaving {@code tool_use:c1} forever unanswered. The fold instead
   * waits, moving straight to {@code PARKED} with the settled result held in {@code
   * pendingResults}.
   */
  @Test
  void toolFinished_with_a_parked_sibling_holds_the_flush() {
    ToolCall c1 = call("call-1", "search");
    ToolCall c2 = call("call-2", "fetch");
    ParkToken token = ParkToken.generate();
    ConversationState c1Parked = midHomework(c1, c2).parked(c1, token);
    assertThat(c1Parked.status()).isEqualTo(ConversationStatus.EXECUTING_TOOL);

    Step step = c1Parked.fold(new ConversationEvent.ToolFinished(id, c2, ToolResult.ok("b")));

    assertThat(step.remember()).isEmpty();
    assertThat(step.effects()).isEmpty();
    assertThat(step.state().status()).isEqualTo(ConversationStatus.PARKED);
    assertThat(step.state().pendingCalls()).isEmpty();
    assertThat(step.state().parkedCalls()).containsExactly(new ParkedCall(token, c1));
    assertThat(step.state().pendingResults())
        .containsExactly(new ToolResultBlock("call-2", "b", false));
  }

  /**
   * The other half of Finding 1: once the parked sibling itself finishes (the resume's own {@code
   * ToolFinished}), that fold is the one that flushes everything together — the sibling's earlier
   * result and this one, riders included — exactly as if nothing had ever waited.
   */
  @Test
  void the_parked_siblings_own_finish_flushes_everything_held_for_it() {
    ToolCall c1 = call("call-1", "search");
    ToolCall c2 = call("call-2", "fetch");
    ParkToken token = ParkToken.generate();
    ConversationState waiting =
        midHomework(c1, c2)
            .parked(c1, token)
            .fold(new ConversationEvent.ToolFinished(id, c2, ToolResult.ok("b")))
            .state()
            .fold(ConversationEvent.AgentTold.of(id, "psst"))
            .state();
    assertThat(waiting.status()).isEqualTo(ConversationStatus.PARKED);

    Step step = waiting.fold(new ConversationEvent.ToolFinished(id, c1, ToolResult.ok("a")));

    Message expectedFlush =
        Message.toolResults(
            List.<ContentBlock>of(
                new ToolResultBlock("call-2", "b", false),
                new ToolResultBlock("call-1", "a", false),
                new TextBlock("psst")));
    assertThat(step.remember()).containsExactly(expectedFlush);
    assertThat(step.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
    assertThat(step.state().parkedCalls()).isEmpty();
    assertThat(step.state().pendingResults()).isEmpty();
    assertThat(step.state().told()).isEmpty();
    assertThat(step.effects()).containsExactly(Effect.callModel());
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

  @Test
  void halting_with_only_notes_and_no_tool_debt_still_flushes_them() {
    ConversationState owing =
        awaitingModel().fold(ConversationEvent.AgentTold.of(id, "psst")).state();

    Step step = owing.halted("hit the ceiling");

    Message expectedFlush = Message.toolResults(List.<ContentBlock>of(new TextBlock("psst")));
    assertThat(step.remember()).containsExactly(expectedFlush);
    assertThat(step.state().told()).isEmpty();
    assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
  }

  @Test
  void a_halting_conversation_still_delivers_the_worlds_words() {
    ToolCall owed = call("call-1", "search");
    ConversationState owing =
        midHomework(owed).fold(ConversationEvent.AgentTold.of(id, "psst")).state();

    Step step = owing.halted("hit the ceiling");

    Message expectedFlush =
        Message.toolResults(
            List.<ContentBlock>of(
                new ToolResultBlock(
                    "call-1", "Abandoned: the conversation failed before this tool ran.", true),
                new TextBlock("psst")));
    assertThat(step.remember()).containsExactly(expectedFlush);
    assertThat(step.state().told()).isEmpty();
    assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
  }

  // --- helpers ---

  private ConversationState awaitingModel() {
    return fresh.fold(ConversationEvent.AgentTold.of(id, "go")).state().openTurn().state();
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
