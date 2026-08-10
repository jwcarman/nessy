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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;

/**
 * {@link Compactors#summarizing}'s builder: its own validation, the 0.8 window-derivation
 * arithmetic, and the summarizing default's own defaults (trigger and keep-recent) pinned so a
 * future change to either literal fails loudly here rather than only downstream.
 */
class CompactorsTest {

  private static final ConversationId CONVERSATION_ID = new ConversationId("s1");

  /**
   * Never actually invoked by these tests — every scenario here only checks construction or the
   * pure {@code requiresCompaction} half, never {@code compact}.
   */
  private static final Summarizer UNUSED_SUMMARIZER =
      head -> {
        throw new UnsupportedOperationException("this test never calls compact()");
      };

  private static ConversationState stateWith(long lastInputTokens) {
    return ConversationState.newConversation(CONVERSATION_ID).withLastInputTokens(lastInputTokens);
  }

  @Nested
  class Construction {

    @Test
    void summarizing_rejects_a_null_summarizer() {
      assertThatThrownBy(() -> Compactors.summarizing(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class TriggerTokens {

    @Test
    void a_trigger_below_one_is_rejected() {
      Compactors.SummarizingBuilder builder = Compactors.summarizing(UNUSED_SUMMARIZER);

      assertThatThrownBy(() -> builder.triggerTokens(0))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_trigger_of_exactly_one_is_accepted() {
      Compactor compactor = Compactors.summarizing(UNUSED_SUMMARIZER).triggerTokens(1).build();

      assertThat(compactor.requiresCompaction(stateWith(1))).isTrue();
    }
  }

  @Nested
  class KeepRecent {

    @Test
    void a_negative_keep_recent_is_rejected() {
      Compactors.SummarizingBuilder builder = Compactors.summarizing(UNUSED_SUMMARIZER);

      assertThatThrownBy(() -> builder.keepRecent(-1)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class Window {

    @Test
    void a_window_equal_to_max_tokens_is_rejected() {
      Compactors.SummarizingBuilder builder = Compactors.summarizing(UNUSED_SUMMARIZER);

      assertThatThrownBy(() -> builder.window(2_000, 2_000))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_window_smaller_than_max_tokens_is_rejected() {
      Compactors.SummarizingBuilder builder = Compactors.summarizing(UNUSED_SUMMARIZER);

      assertThatThrownBy(() -> builder.window(1_000, 2_000))
          .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 0.8 × (200_000 − 8_192) = 0.8 × 191_808 = 153_446.4, truncated to 153_446 by the {@code
     * (long)} cast — pinned at both the exact literal and the boundary either side of it.
     */
    @Test
    void the_derivation_fires_at_the_computed_boundary_not_one_token_early() {
      long window = 200_000;
      long maxTokens = 8_192;
      long expectedTrigger = (long) (0.8 * (window - maxTokens));
      assertThat(expectedTrigger).isEqualTo(153_446);
      Compactor compactor =
          Compactors.summarizing(UNUSED_SUMMARIZER).window(window, maxTokens).build();

      assertThat(compactor.requiresCompaction(stateWith(153_445))).isFalse();
      assertThat(compactor.requiresCompaction(stateWith(153_446))).isTrue();
    }

    @Test
    void window_and_triggerTokens_share_one_value_and_the_last_call_wins() {
      // triggerTokens(1) called first, then overwritten by window(...): the derived ~153_446
      // trigger wins, so a lastInputTokens of 1 must not fire.
      Compactor triggerThenWindow =
          Compactors.summarizing(UNUSED_SUMMARIZER).triggerTokens(1).window(200_000, 8_192).build();
      // window(...) called first, then overwritten by triggerTokens(1): the literal 1 wins, so
      // even a lastInputTokens far below the window-derived value fires.
      Compactor windowThenTrigger =
          Compactors.summarizing(UNUSED_SUMMARIZER).window(200_000, 8_192).triggerTokens(1).build();

      assertThat(triggerThenWindow.requiresCompaction(stateWith(1))).isFalse();
      assertThat(triggerThenWindow.requiresCompaction(stateWith(153_446))).isTrue();
      assertThat(windowThenTrigger.requiresCompaction(stateWith(1))).isTrue();
    }
  }

  @Nested
  class SummarizingDefaults {

    @Test
    void the_default_trigger_fires_at_exactly_one_hundred_thousand_not_one_below() {
      Compactor compactor = Compactors.summarizing(UNUSED_SUMMARIZER).build();

      assertThat(compactor.requiresCompaction(stateWith(99_999))).isFalse();
      assertThat(compactor.requiresCompaction(stateWith(100_000))).isTrue();
    }

    /**
     * {@code keepRecent} has no getter, so its default of 10 is pinned the only honest way
     * available: observing where an uncustomized builder actually cuts. Fourteen messages (seven
     * pairs) with a trigger low enough to fire immediately — {@code pairSafeCut(10)} computes
     * {@code limit = min(14 - 10, 14 - 1) = 4}, and index 4 is a genuine user turn, so the cut
     * lands at 4: the last 10 messages (indices 4..13) survive verbatim.
     */
    @Test
    void the_default_keep_recent_is_ten_messages() {
      RecordingSummarizer summarizer = new RecordingSummarizer("gist");
      Compactor compactor =
          Compactors.summarizing(summarizer).triggerTokens(1).build(); // keepRecent left default

      List<Message> workingSet = sevenPairs();
      Compactor.Result result = compactor.compact(stateWithMessages(workingSet));

      List<Message> expectedTail = workingSet.subList(4, workingSet.size());
      assertThat(expectedTail).hasSize(10);
      assertThat(result.workingSet().subList(1, result.workingSet().size()))
          .isEqualTo(expectedTail);
    }

    private List<Message> sevenPairs() {
      List<Message> messages = new ArrayList<>();
      for (int i = 0; i < 7; i++) {
        messages.add(Message.user("u" + i));
        messages.add(Message.assistant(List.of(new TextBlock("a" + i))));
      }
      return messages;
    }

    private ConversationState stateWithMessages(List<Message> messages) {
      return ConversationState.newConversation(CONVERSATION_ID).withMessages(messages);
    }
  }

  @Nested
  class Disabled {

    @Test
    void requires_compaction_always_answers_false() {
      Compactor compactor = Compactor.disabled();

      assertThat(compactor.requiresCompaction(stateWith(Long.MAX_VALUE))).isFalse();
    }

    @Test
    void compact_still_throws_rather_than_silently_doing_nothing_if_ever_invoked_directly() {
      Compactor compactor = Compactor.disabled();

      assertThatThrownBy(() -> compactor.compact(stateWith(0)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("compaction is disabled");
    }
  }

  /** Replays one scripted summary, regardless of the head it is handed. */
  private static final class RecordingSummarizer implements Summarizer {

    private final String summary;

    RecordingSummarizer(String summary) {
      this.summary = summary;
    }

    @Override
    public String summarize(Context head) {
      return summary;
    }
  }
}
