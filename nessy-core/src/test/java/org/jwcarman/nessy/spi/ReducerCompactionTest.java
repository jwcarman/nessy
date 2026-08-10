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
import java.util.ArrayList;
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
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.compaction.Compactor;

/**
 * What the reducer itself owns around compaction: consulting the compactor at each {@code
 * CallModel} decision point, handing over the whole ledger unexamined, and applying whatever result
 * comes back. What the compactor does with that ledger — cuts, summaries, prefixes — is {@code
 * SummarizingCompactionTest}'s business, not this one's; the reducer no longer computes any of it.
 */
class ReducerCompactionTest {

  private static final ConversationId ID = new ConversationId("s1");

  /** Builds seed states without ever triggering compaction itself. */
  private final Reducer builder = new Reducer(TerminationPolicy.never(), Compactor.disabled());

  private final ConversationState initial = ConversationState.newConversation(ID);

  private static ToolCall call(String id) {
    return new ToolCall(id, "read_file", JsonNodeFactory.instance.objectNode());
  }

  /** A compactor whose only behavior that matters to the reducer is when it requires compaction. */
  private static Compactor triggeringAt(long triggerTokens) {
    return new Compactor() {
      @Override
      public boolean requiresCompaction(ConversationState state) {
        return state.lastInputTokens() >= triggerTokens;
      }

      @Override
      public Result compact(ConversationState state) {
        throw new UnsupportedOperationException(
            "the reducer never calls compact() itself; these tests drive compact()'s result"
                + " through ConversationEvent.Compacted directly");
      }
    };
  }

  /** Drives one plain user/assistant text turn to completion, via {@link #builder}. */
  private ConversationState pair(ConversationState state, String userText, String assistantText) {
    ConversationState afterUser =
        builder.reduce(state, ConversationEvent.AgentTold.of(ID, userText)).state();
    ConversationState afterDelta =
        builder.reduce(afterUser, new ConversationEvent.TextDelta(ID, assistantText)).state();
    return builder
        .reduce(
            afterDelta, new ConversationEvent.ModelTurnEnded(ID, StopReason.END_TURN, Usage.zero()))
        .state();
  }

  /** Drives {@code toolCall} up to the point where it is approved but not yet finished. */
  private ConversationState pendingToolCall(ConversationState state, ToolCall toolCall) {
    ConversationState requested =
        builder.reduce(state, new ConversationEvent.ToolCallRequested(ID, toolCall)).state();
    ConversationState turnEnded =
        builder
            .reduce(
                requested,
                new ConversationEvent.ModelTurnEnded(ID, StopReason.TOOL_USE, Usage.zero()))
            .state();
    return builder
        .reduce(turnEnded, new ConversationEvent.ApprovalDecided(ID, toolCall, Decision.allow()))
        .state();
  }

  /** Ten plain-text messages (five user/assistant pairs) followed by one finished tool exchange. */
  private ConversationState fivePairsAndToolExchange() {
    ConversationState state = initial;
    for (int i = 1; i <= 5; i++) {
      state = pair(state, "u" + i, "a" + i);
    }
    ToolCall toolCall = call("c1");
    state = pendingToolCall(state, toolCall);
    return builder
        .reduce(state, new ConversationEvent.ToolFinished(ID, toolCall, ToolResult.ok("contents")))
        .state();
  }

  @Nested
  class Triggering {

    @Test
    void at_the_trigger_compaction_is_emitted_as_a_bare_marker() {
      ConversationState state = fivePairsAndToolExchange().withLastInputTokens(100_000);
      Reducer reducer = new Reducer(TerminationPolicy.never(), triggeringAt(100_000));

      Step step = reducer.reduce(state, ConversationEvent.AgentTold.of(ID, "one more thing"));

      assertThat(step.state().status()).isEqualTo(ConversationStatus.COMPACTING);
      assertThat(step.effects()).containsExactly(Effect.compact());
    }

    @Test
    void below_the_trigger_a_user_message_calls_the_model_as_always() {
      ConversationState state = fivePairsAndToolExchange().withLastInputTokens(99_999);
      Reducer reducer = new Reducer(TerminationPolicy.never(), triggeringAt(100_000));

      Step step = reducer.reduce(state, ConversationEvent.AgentTold.of(ID, "hi"));

      assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void termination_still_beats_compaction() {
      ConversationState state = initial.withTurns(1).withLastInputTokens(100_000);
      Reducer reducer = new Reducer(TerminationPolicy.maxTurns(1), triggeringAt(100_000));

      Step step = reducer.reduce(state, ConversationEvent.AgentTold.of(ID, "more?"));

      assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
      assertThat(step.effects()).isEmpty();
    }

    /**
     * {@code agentTold} isn't the only decision point: {@code toolFinished} reaches the same {@code
     * proceedOrCompact} once the last pending call's result lands and the pending lane is empty
     * again. This pins that second site independently, so a change that wires triggering into only
     * one of the two call sites fails loudly here rather than only in an end-to-end test.
     */
    @Test
    void a_tool_result_at_the_trigger_also_compacts() {
      ToolCall toolCall = call("c2");
      ConversationState pending =
          pendingToolCall(fivePairsAndToolExchange(), toolCall).withLastInputTokens(100_000);
      Reducer reducer = new Reducer(TerminationPolicy.never(), triggeringAt(100_000));

      Step step =
          reducer.reduce(
              pending, new ConversationEvent.ToolFinished(ID, toolCall, ToolResult.ok("more")));

      assertThat(step.state().status()).isEqualTo(ConversationStatus.COMPACTING);
      assertThat(step.effects()).containsExactly(Effect.compact());
    }
  }

  @Nested
  class Applying_a_result {

    @Test
    void a_shrinking_result_replaces_the_working_set() {
      ConversationState state = fivePairsAndToolExchange().withLastInputTokens(100_000);
      List<Message> tail = state.messages().subList(6, state.messages().size());
      List<Message> shrunk = new ArrayList<>();
      shrunk.add(Message.user("[Conversation summary — earlier turns compacted]\nthe gist"));
      shrunk.addAll(tail);

      Step step = builder.reduce(state, new ConversationEvent.Compacted(ID, shrunk));

      assertThat(step.state().messages()).isEqualTo(shrunk);
      assertThat(step.state().generation()).isEqualTo(state.generation() + 1);
      // The jurisdiction rule (design §10.6): compaction carries no spend, so applying its
      // result never changes the ledger's usage.
      assertThat(step.state().usage()).isEqualTo(state.usage());
      assertThat(step.state().lastInputTokens()).isZero();
      assertThat(step.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
      assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void a_non_shrinking_result_is_a_skip() {
      ConversationState state = fivePairsAndToolExchange().withLastInputTokens(100_000);

      Step step = builder.reduce(state, new ConversationEvent.Compacted(ID, state.messages()));

      assertThat(step.state().messages()).isEqualTo(state.messages());
      assertThat(step.state().generation()).isEqualTo(state.generation());
      assertThat(step.state().usage()).isEqualTo(state.usage());
      assertThat(step.state().lastInputTokens()).isEqualTo(state.lastInputTokens());
      assertThat(step.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
      assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    /**
     * {@code Effect.Compact} is only ever emitted from a settled state, but {@code
     * ConversationEvent.Compacted} is not guaranteed to land against one: a durably replayed run
     * can replay a stale result against a state that has since moved on, and nothing at this layer
     * stops a compactor from answering late. A shrinking result that would otherwise replace the
     * messages must still be treated as a skip once tool debt is outstanding — the tail a shrink
     * drops might be exactly the messages a pending call belongs to, and splicing underneath it
     * would strand the pending lane.
     */
    @Test
    void a_result_while_tool_debt_is_outstanding_is_a_skip() {
      ToolCall toolCall = call("c3");
      ConversationState state = pendingToolCall(fivePairsAndToolExchange(), toolCall);
      List<Message> shrunk =
          List.of(Message.user("[Conversation summary — earlier turns compacted]\nthe gist"));

      Step step = builder.reduce(state, new ConversationEvent.Compacted(ID, shrunk));

      assertThat(step.state().messages()).isEqualTo(state.messages());
      assertThat(step.state().generation()).isEqualTo(state.generation());
      assertThat(step.state().usage()).isEqualTo(state.usage());
      assertThat(step.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
      assertThat(step.effects()).containsExactly(Effect.callModel());
      assertThat(step.state().pendingCalls()).containsExactly(toolCall);
    }
  }

  @Nested
  class Skipping {

    @Test
    void a_skip_proceeds_to_the_model_without_retrying_in_place() {
      ConversationState state = initial.withLastInputTokens(42);
      Reducer reducer = new Reducer(TerminationPolicy.never(), Compactor.disabled());

      Step step = reducer.reduce(state, new ConversationEvent.CompactionSkipped(ID, "429"));

      assertThat(step.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
      assertThat(step.state().lastInputTokens()).isEqualTo(42);
      assertThat(step.effects()).containsExactly(Effect.callModel());
    }
  }
}
