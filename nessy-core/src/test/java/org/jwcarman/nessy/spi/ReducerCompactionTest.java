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
import org.jwcarman.nessy.api.CompactionPolicy;
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
import org.jwcarman.nessy.api.Usage;

class ReducerCompactionTest {

  /** Builds seed states without ever triggering compaction itself: disabled trigger, no ceiling. */
  private final Reducer builder =
      new Reducer(TerminationPolicy.never(), CompactionPolicy.disabled());

  private final SessionState initial = SessionState.newSession(new SessionId("s1"));

  private static ToolCall call(String id) {
    return new ToolCall(id, "read_file", JsonNodeFactory.instance.objectNode());
  }

  private static CompactionPolicy policy(long triggerTokens, int keepRecentMessages) {
    return new CompactionPolicy(
        triggerTokens, keepRecentMessages, 2_048, CompactionPolicy.DEFAULT_INSTRUCTIONS);
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
    void a_turn_end_records_the_measured_input_tokens() {
      Step step =
          builder.reduce(
              initial, new Event.ModelTurnEnded(StopReason.END_TURN, new Usage(120_000, 50, 0)));

      assertThat(step.state().lastInputTokens()).isEqualTo(120_000);
    }

    @Test
    void below_the_trigger_a_user_message_calls_the_model_as_always() {
      // Seeded from a state with a genuine safe cut available (see at_the_trigger, below), so
      // this fails if the trigger comparison were ever loosened to ">" or the threshold drifted:
      // a broken comparison would compact here even though 99_999 is one token short of 100_000.
      SessionState state = fivePairsAndToolExchange().withLastInputTokens(99_999);
      Reducer reducer = new Reducer(TerminationPolicy.never(), policy(100_000, 4));

      Step step = reducer.reduce(state, Event.UserSaid.of("hi"));

      assertThat(step.effects()).containsExactly(Effect.callModel());
    }

    @Test
    void at_the_trigger_a_user_message_compacts_instead_of_calling() {
      SessionState state = fivePairsAndToolExchange().withLastInputTokens(100_000);
      Reducer reducer = new Reducer(TerminationPolicy.never(), policy(100_000, 4));

      Step step = reducer.reduce(state, Event.UserSaid.of("one more thing"));

      assertThat(step.state().status()).isEqualTo(SessionStatus.COMPACTING);
      assertThat(step.effects())
          .containsExactly(
              new Effect.Compact(
                  state.messages().subList(0, 8), policy(100_000, 4).instructions()));
    }

    @Test
    void termination_beats_compaction() {
      SessionState state = initial.withTurns(1).withLastInputTokens(100_000);
      Reducer reducer = new Reducer(TerminationPolicy.maxTurns(1), policy(100_000, 4));

      Step step = reducer.reduce(state, Event.UserSaid.of("more?"));

      assertThat(step.state().status()).isEqualTo(SessionStatus.FAILED);
      assertThat(step.effects()).isEmpty();
    }
  }

  @Nested
  class The_pair_safe_cut {

    @Test
    void the_cut_never_separates_a_tool_use_from_its_results() {
      ToolCall toolCall = call("c1");
      SessionState state = pair(initial, "u0", "a0");
      state = pair(state, "u1", "a1");
      state = pendingToolCall(state, toolCall);
      state =
          builder
              .reduce(state, new Event.ToolFinished(toolCall, ToolResult.ok("contents")))
              .state();
      state = pair(state, "u2", "a2");
      state = pair(state, "u3", "a3");
      state = state.withLastInputTokens(1);

      Reducer reducer = new Reducer(TerminationPolicy.never(), policy(1, 6));
      Step step = reducer.reduce(state, Event.UserSaid.of("more"));

      assertThat(step.state().status()).isEqualTo(SessionStatus.COMPACTING);
      assertThat(step.effects())
          .containsExactly(
              new Effect.Compact(state.messages().subList(0, 2), policy(1, 6).instructions()));
    }

    @Test
    void the_cut_lands_exactly_on_the_keep_recent_boundary_when_it_qualifies_there() {
      // keepRecentMessages=5 puts the naive limit at index 8 of the 13-message post-append
      // list — u5, itself a genuine user turn — so the cut is accepted on the very first check,
      // with no walk-down. This pins the limit's own formula (size - keepRecentMessages) as an
      // inclusive endpoint, distinct from at_the_trigger's one-step walk-down above.
      SessionState state = fivePairsAndToolExchange().withLastInputTokens(100_000);
      Reducer reducer = new Reducer(TerminationPolicy.never(), policy(100_000, 5));

      Step step = reducer.reduce(state, Event.UserSaid.of("one more thing"));

      assertThat(step.state().status()).isEqualTo(SessionStatus.COMPACTING);
      Effect.Compact compact = (Effect.Compact) step.effects().getFirst();
      assertThat(compact.messages()).isEqualTo(state.messages().subList(0, 8));
      assertThat(step.state().messages().size() - compact.messages().size()).isEqualTo(5);
    }

    @Test
    void with_no_safe_cut_the_model_is_called_uncompacted() {
      ToolCall toolCall = call("c1");
      SessionState state = pendingToolCall(initial, toolCall).withLastInputTokens(1);
      Reducer reducer = new Reducer(TerminationPolicy.never(), policy(1, 0));

      Step step =
          reducer.reduce(state, new Event.ToolFinished(toolCall, ToolResult.ok("contents")));

      assertThat(step.effects()).containsExactly(Effect.callModel());
      assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
    }
  }

  @Nested
  class Applying_a_summary {

    @Test
    void a_summary_replaces_the_prefix_and_keeps_the_tail_in_order() {
      SessionState state = fivePairsAndToolExchange().withLastInputTokens(100_000);
      Reducer reducer = new Reducer(TerminationPolicy.never(), policy(100_000, 4));
      Step compactStep = reducer.reduce(state, Event.UserSaid.of("one more thing"));
      Effect.Compact compact = (Effect.Compact) compactStep.effects().getFirst();
      List<Message> tail =
          compactStep
              .state()
              .messages()
              .subList(compact.messages().size(), compactStep.state().messages().size());

      Step summarized = reducer.reduce(compactStep.state(), new Event.Compacted("the gist"));

      List<Message> expected = new ArrayList<>();
      expected.add(Message.user("[Conversation summary — earlier turns compacted]\nthe gist"));
      expected.addAll(tail);
      assertThat(summarized.state().messages()).isEqualTo(expected);
      assertThat(summarized.state().generation()).isEqualTo(1);
      assertThat(summarized.state().lastInputTokens()).isZero();
      assertThat(summarized.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
      assertThat(summarized.effects()).containsExactly(Effect.callModel());
    }
  }

  @Nested
  class Skipping {

    @Test
    void a_skip_proceeds_to_the_model_without_retrying_in_place() {
      SessionState state = initial.withLastInputTokens(42);
      Reducer reducer = new Reducer(TerminationPolicy.never(), CompactionPolicy.defaults());

      Step step = reducer.reduce(state, new Event.CompactionSkipped("429"));

      assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
      assertThat(step.state().lastInputTokens()).isEqualTo(42);
      assertThat(step.effects()).containsExactly(Effect.callModel());
    }
  }
}
