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
import org.jwcarman.nessy.api.CompactionStrategy;
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

/**
 * What the reducer itself owns around compaction: consulting the strategy at each {@code CallModel}
 * decision point, handing over the whole working set unexamined, and applying whatever result comes
 * back. What the strategy does with that working set — cuts, summaries, prefixes — is {@code
 * SummarizingCompactionTest}'s business, not this one's; the reducer no longer computes any of it.
 */
class ReducerCompactionTest {

  /** Builds seed states without ever triggering compaction itself. */
  private final Reducer builder =
      new Reducer(TerminationPolicy.never(), CompactionStrategy.disabled());

  private final SessionState initial = SessionState.newSession(new SessionId("s1"));

  private static ToolCall call(String id) {
    return new ToolCall(id, "read_file", JsonNodeFactory.instance.objectNode());
  }

  /** A strategy whose only behavior that matters to the reducer is when it requires compaction. */
  private static CompactionStrategy triggeringAt(long triggerTokens) {
    return new CompactionStrategy() {
      @Override
      public boolean requiresCompaction(SessionState state) {
        return state.lastInputTokens() >= triggerTokens;
      }

      @Override
      public Result compact(List<Message> workingSet) {
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
    void at_the_trigger_the_whole_working_set_goes_to_the_strategy() {
      SessionState state = fivePairsAndToolExchange().withLastInputTokens(100_000);
      Reducer reducer = new Reducer(TerminationPolicy.never(), triggeringAt(100_000));

      Step step = reducer.reduce(state, Event.UserSaid.of("one more thing"));

      assertThat(step.state().status()).isEqualTo(SessionStatus.COMPACTING);
      assertThat(step.effects()).containsExactly(new Effect.Compact(step.state().messages()));
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
  }

  @Nested
  class Skipping {

    @Test
    void a_skip_proceeds_to_the_model_without_retrying_in_place() {
      SessionState state = initial.withLastInputTokens(42);
      Reducer reducer = new Reducer(TerminationPolicy.never(), CompactionStrategy.disabled());

      Step step = reducer.reduce(state, new Event.CompactionSkipped("429"));

      assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_MODEL);
      assertThat(step.state().lastInputTokens()).isEqualTo(42);
      assertThat(step.effects()).containsExactly(Effect.callModel());
    }
  }
}
