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
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.session.SessionId;
import org.jwcarman.nessy.api.session.SessionState;
import org.jwcarman.nessy.api.session.SessionStatus;
import org.jwcarman.nessy.api.session.TerminationPolicy;
import org.jwcarman.nessy.api.session.Usage;
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

  /** Builds seed states without ever triggering compaction itself. */
  private final Reducer builder = new Reducer(TerminationPolicy.never(), Compactor.disabled());

  private final SessionState initial = SessionState.newSession(new SessionId("s1"));

  private static ToolCall call(String id) {
    return new ToolCall(id, "read_file", JsonNodeFactory.instance.objectNode());
  }

  /** A compactor whose only behavior that matters to the reducer is when it requires compaction. */
  private static Compactor triggeringAt(long triggerTokens) {
    return new Compactor() {
      @Override
      public boolean requiresCompaction(SessionState state) {
        return state.lastInputTokens() >= triggerTokens;
      }

      @Override
      public Result compact(SessionState state) {
        throw new UnsupportedOperationException(
            "the reducer never calls compact() itself; these tests drive compact()'s result"
                + " through Event.Compacted directly");
      }
    };
  }

  /** Drives one plain user/assistant text turn to completion, via {@link #builder}. */
  private SessionState pair(SessionState state, String userText, String assistantText) {
    SessionState afterUser = builder.reduce(state, Event.UserSaid.of(userText)).state();
    SessionState afterDelta = builder.reduce(afterUser, new Event.TextDelta(assistantText)).state();
    return builder
        .reduce(afterDelta, new Event.ModelTurnEnded(StopReason.END_TURN, Usage.zero()))
        .state();
  }

  /** Drives {@code toolCall} up to the point where it is approved but not yet finished. */
  private SessionState pendingToolCall(SessionState state, ToolCall toolCall) {
    SessionState requested = builder.reduce(state, new Event.ToolCallRequested(toolCall)).state();
    SessionState turnEnded =
        builder
            .reduce(requested, new Event.ModelTurnEnded(StopReason.TOOL_USE, Usage.zero()))
            .state();
    return builder.reduce(turnEnded, new Event.ApprovalDecided(toolCall, Decision.allow())).state();
  }

  /** Ten plain-text messages (five user/assistant pairs) followed by one finished tool exchange. */
  private SessionState fivePairsAndToolExchange() {
    SessionState state = initial;
    for (int i = 1; i <= 5; i++) {
      state = pair(state, "u" + i, "a" + i);
    }
    ToolCall toolCall = call("c1");
    state = pendingToolCall(state, toolCall);
    return builder
        .reduce(state, new Event.ToolFinished(toolCall, ToolResult.ok("contents")))
        .state();
  }

  @Nested
  class Triggering {

    @Test
    void at_the_trigger_compaction_is_emitted_as_a_bare_marker() {
      SessionState state = fivePairsAndToolExchange().withLastInputTokens(100_000);
      Reducer reducer = new Reducer(TerminationPolicy.never(), triggeringAt(100_000));

      Step step = reducer.reduce(state, Event.UserSaid.of("one more thing"));

      assertThat(step.state().status()).isEqualTo(SessionStatus.COMPACTING);
      assertThat(step.effects()).containsExactly(Effect.compact());
    }

    @Test
    void below_the_trigger_a_user_message_calls_the_model_as_always() {
      SessionState state = fivePairsAndToolExchange().withLastInputTokens(99_999);
      Reducer reducer = new Reducer(TerminationPolicy.never(), triggeringAt(100_000));

      Step step = reducer.reduce(state, Event.UserSaid.of("hi"));

      assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void termination_still_beats_compaction() {
      SessionState state = initial.withTurns(1).withLastInputTokens(100_000);
      Reducer reducer = new Reducer(TerminationPolicy.maxTurns(1), triggeringAt(100_000));

      Step step = reducer.reduce(state, Event.UserSaid.of("more?"));

      assertThat(step.state().status()).isEqualTo(SessionStatus.FAILED);
      assertThat(step.effects()).isEmpty();
    }

    /**
     * {@code userSaid} isn't the only decision point: {@code toolFinished} reaches the same {@code
     * proceedOrCompact} once the last pending call's result lands and the pending lane is empty
     * again. This pins that second site independently, so a change that wires triggering into only
     * one of the two call sites fails loudly here rather than only in an end-to-end test.
     */
    @Test
    void a_tool_result_at_the_trigger_also_compacts() {
      ToolCall toolCall = call("c2");
      SessionState pending =
          pendingToolCall(fivePairsAndToolExchange(), toolCall).withLastInputTokens(100_000);
      Reducer reducer = new Reducer(TerminationPolicy.never(), triggeringAt(100_000));

      Step step = reducer.reduce(pending, new Event.ToolFinished(toolCall, ToolResult.ok("more")));

      assertThat(step.state().status()).isEqualTo(SessionStatus.COMPACTING);
      assertThat(step.effects()).containsExactly(Effect.compact());
    }
  }

  @Nested
  class Applying_a_result {

    @Test
    void a_shrinking_result_replaces_the_working_set() {
      SessionState state = fivePairsAndToolExchange().withLastInputTokens(100_000);
      List<Message> tail = state.messages().subList(6, state.messages().size());
      List<Message> shrunk = new ArrayList<>();
      shrunk.add(Message.user("[Conversation summary — earlier turns compacted]\nthe gist"));
      shrunk.addAll(tail);
      Usage spend = new Usage(5_000, 200, 0);

      Step step = builder.reduce(state, new Event.Compacted(shrunk, spend));

      assertThat(step.state().messages()).isEqualTo(shrunk);
      assertThat(step.state().generation()).isEqualTo(state.generation() + 1);
      assertThat(step.state().usage()).isEqualTo(state.usage().plus(spend));
      assertThat(step.state().lastInputTokens()).isZero();
      assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
      assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void a_non_shrinking_result_is_a_skip() {
      SessionState state = fivePairsAndToolExchange().withLastInputTokens(100_000);
      Usage spend = new Usage(1_000, 50, 0);

      Step step = builder.reduce(state, new Event.Compacted(state.messages(), spend));

      assertThat(step.state().messages()).isEqualTo(state.messages());
      assertThat(step.state().generation()).isEqualTo(state.generation());
      assertThat(step.state().usage()).isEqualTo(state.usage().plus(spend));
      assertThat(step.state().lastInputTokens()).isEqualTo(state.lastInputTokens());
      assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
      assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    /**
     * {@code Effect.Compact} is only ever emitted from a settled state, but {@code Event.Compacted}
     * is not guaranteed to land against one: a durably replayed run can replay a stale result
     * against a state that has since moved on, and nothing at this layer stops a compactor from
     * answering late. A shrinking result that would otherwise replace the messages must still be
     * treated as a skip once tool debt is outstanding — the tail a shrink drops might be exactly
     * the messages a pending call belongs to, and splicing underneath it would strand the pending
     * lane.
     */
    @Test
    void a_result_while_tool_debt_is_outstanding_is_a_skip() {
      ToolCall toolCall = call("c3");
      SessionState state = pendingToolCall(fivePairsAndToolExchange(), toolCall);
      List<Message> shrunk =
          List.of(Message.user("[Conversation summary — earlier turns compacted]\nthe gist"));
      Usage spend = new Usage(2_000, 100, 0);

      Step step = builder.reduce(state, new Event.Compacted(shrunk, spend));

      assertThat(step.state().messages()).isEqualTo(state.messages());
      assertThat(step.state().generation()).isEqualTo(state.generation());
      assertThat(step.state().usage()).isEqualTo(state.usage().plus(spend));
      assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
      assertThat(step.effects()).containsExactly(Effect.callModel());
      assertThat(step.state().pendingCalls()).containsExactly(toolCall);
    }
  }

  @Nested
  class Skipping {

    @Test
    void a_skip_proceeds_to_the_model_without_retrying_in_place() {
      SessionState state = initial.withLastInputTokens(42);
      Reducer reducer = new Reducer(TerminationPolicy.never(), Compactor.disabled());

      Step step = reducer.reduce(state, new Event.CompactionSkipped("429"));

      assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
      assertThat(step.state().lastInputTokens()).isEqualTo(42);
      assertThat(step.effects()).containsExactly(Effect.callModel());
    }
  }
}
