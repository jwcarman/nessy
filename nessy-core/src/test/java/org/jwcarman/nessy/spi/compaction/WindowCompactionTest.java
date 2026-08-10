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
package org.jwcarman.nessy.spi.compaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.TerminationPolicy;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.Reducer;
import org.jwcarman.nessy.spi.Step;

/**
 * {@link Compactors#window}: the zero-spend, lossy alternative to the summarizing default. Pure
 * over its input — no {@code Summarizer} involved anywhere in this file — so every scenario here
 * checks {@link Compactor#compact} and {@link Compactor#requiresCompaction} directly, plus one
 * check that the reducer applies its result exactly like any other compactor's.
 */
class WindowCompactionTest {

  private static final ConversationId CONVERSATION_ID = new ConversationId("s1");

  private static Compactor compactorFor(int keepRecent) {
    return Compactors.window(keepRecent).triggerTokens(1).build();
  }

  private static ConversationState stateWith(List<Message> messages) {
    return ConversationState.newConversation(CONVERSATION_ID).withMessages(messages);
  }

  /** Six user/assistant text pairs — twelve messages, every even index a genuine user turn. */
  private static List<Message> sixPairs() {
    List<Message> messages = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      messages.add(Message.user("u" + i));
      messages.add(Message.assistant(List.of(new TextBlock("a" + i))));
    }
    return messages;
  }

  /** One assistant tool_use answered by its result: no genuine user turn anywhere in it. */
  private static List<Message> toolOnlyExchange() {
    ToolCall call = new ToolCall("c1", "read_file", JsonNodeFactory.instance.objectNode());
    Message assistant = Message.assistant(List.of(new ToolUseBlock(call)));
    Message result = Message.user(List.of(new ToolResultBlock(call.id(), "contents", false)));
    return List.of(assistant, result);
  }

  @Nested
  class Construction {

    @Test
    void a_negative_keep_recent_is_rejected() {
      assertThatThrownBy(() -> Compactors.window(-1)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class Dropping_at_the_pair_safe_boundary {

    @Test
    void the_head_is_dropped_and_no_summary_is_spliced_in() {
      List<Message> workingSet = sixPairs();
      Compactor compactor = compactorFor(4);

      Compactor.Result result = compactor.compact(stateWith(workingSet));

      List<Message> expectedTail = workingSet.subList(8, workingSet.size());
      assertThat(result.workingSet()).isEqualTo(expectedTail);
    }
  }

  @Nested
  class No_safe_cut {

    @Test
    void no_safe_cut_returns_the_working_set_unchanged() {
      List<Message> workingSet = toolOnlyExchange();
      Compactor compactor = compactorFor(0);

      Compactor.Result result = compactor.compact(stateWith(workingSet));

      assertThat(result.workingSet()).isEqualTo(workingSet);
    }
  }

  @Nested
  class Consulting_the_trigger {

    @Test
    void requires_compaction_delegates_to_the_configured_trigger() {
      Compactor neverTriggers = Compactors.window(0).triggerTokens(Long.MAX_VALUE).build();

      assertThat(
              neverTriggers.requiresCompaction(
                  ConversationState.newConversation(CONVERSATION_ID)
                      .withLastInputTokens(Long.MAX_VALUE - 1)))
          .isFalse();
    }

    @Test
    void a_trigger_below_one_is_rejected() {
      Compactors.WindowBuilder builder = Compactors.window(0);

      assertThatThrownBy(() -> builder.triggerTokens(0))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_default_trigger_fires_at_exactly_one_hundred_thousand_not_one_below() {
      Compactor compactor = Compactors.window(0).build();

      assertThat(compactor.requiresCompaction(stateWith0(99_999))).isFalse();
      assertThat(compactor.requiresCompaction(stateWith0(100_000))).isTrue();
    }

    private ConversationState stateWith0(long lastInputTokens) {
      return ConversationState.newConversation(CONVERSATION_ID)
          .withLastInputTokens(lastInputTokens);
    }
  }

  @Nested
  class Window_derivation {

    @Test
    void a_window_not_greater_than_max_tokens_is_rejected() {
      Compactors.WindowBuilder builder = Compactors.window(0);

      assertThatThrownBy(() -> builder.window(2_000, 2_000))
          .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Same 0.8 × (window − maxTokens) arithmetic the summarizing builder uses, pinned here too so a
     * future divergence between the two builders' trigger derivation fails loudly.
     */
    @Test
    void the_derivation_fires_at_the_computed_boundary_not_one_token_early() {
      long window = 200_000;
      long maxTokens = 8_192;
      Compactor compactor = Compactors.window(0).window(window, maxTokens).build();

      assertThat(
              compactor.requiresCompaction(
                  ConversationState.newConversation(CONVERSATION_ID).withLastInputTokens(153_445)))
          .isFalse();
      assertThat(
              compactor.requiresCompaction(
                  ConversationState.newConversation(CONVERSATION_ID).withLastInputTokens(153_446)))
          .isTrue();
    }
  }

  @Nested
  class Reducer_integration {

    /**
     * The reducer doesn't know or care which {@link Compactor} produced a result — {@code
     * ReducerCompactionTest} covers that contract generically. This one scenario confirms the
     * window compactor's own {@link Compactor.Result} is the kind of shrink the reducer actually
     * applies, end to end.
     */
    @Test
    void the_reducers_non_shrinking_rule_treats_this_compactors_result_as_a_real_shrink() {
      List<Message> workingSet = sixPairs();
      ConversationState state = stateWith(workingSet).withLastInputTokens(1);
      Reducer reducer = new Reducer(TerminationPolicy.never(), compactorFor(4));
      Compactor.Result result = compactorFor(4).compact(state);

      Step step =
          reducer.reduce(
              state, new ConversationEvent.Compacted(CONVERSATION_ID, result.workingSet()));

      assertThat(step.state().messages()).isEqualTo(result.workingSet());
      assertThat(step.state().generation()).isEqualTo(state.generation() + 1);
    }
  }
}
