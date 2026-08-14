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
package org.jwcarman.nessy.spi.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.memory.SummaryStore.Summary;

class SummarizingMemoryTest {

  private static ToolCall toolCall(String id) {
    return new ToolCall(id, "search", JsonNodeFactory.instance.objectNode());
  }

  @Nested
  class Below_the_threshold {

    @Test
    void recall_makes_no_model_call_and_returns_the_tail_verbatim() {
      ConversationId id = ConversationId.generate();
      Transcript transcript = Transcript.inMemory();
      SummaryStore summaries = SummaryStore.inMemory();
      RecordingTextModelProvider provider = new RecordingTextModelProvider();
      SummarizingMemory memory =
          new SummarizingMemory(transcript, summaries, provider, "model", "summarize", 5);
      Message first = Message.user("one");
      Message second = Message.user("two");
      memory.remember(id, first);
      memory.remember(id, second);

      Context recalled = memory.recall(id);

      assertThat(provider.callCount()).isZero();
      assertThat(recalled.messages()).containsExactly(first, second);
    }

    @Test
    void an_existing_summary_renders_as_one_opening_user_message_ahead_of_the_tail() {
      ConversationId id = ConversationId.generate();
      Transcript transcript = Transcript.inMemory();
      SummaryStore summaries = SummaryStore.inMemory();
      summaries.save(id, new Summary(1L, "everything before message three"));
      transcript.append(id, Message.user("one"));
      transcript.append(id, Message.user("two"));
      Message third = Message.user("three");
      transcript.append(id, third);
      RecordingTextModelProvider provider = new RecordingTextModelProvider();
      SummarizingMemory memory =
          new SummarizingMemory(transcript, summaries, provider, "model", "summarize", 5);

      Context recalled = memory.recall(id);

      assertThat(provider.callCount()).isZero();
      assertThat(recalled.messages())
          .containsExactly(Message.user("everything before message three"), third);
    }
  }

  @Nested
  class Crossing_the_threshold {

    @Test
    void watermark_advances_past_the_pair_safe_prefix_it_folded() {
      ConversationId id = ConversationId.generate();
      Transcript transcript = Transcript.inMemory();
      SummaryStore summaries = SummaryStore.inMemory();
      RecordingTextModelProvider provider = new RecordingTextModelProvider("folded so far");
      SummarizingMemory memory =
          new SummarizingMemory(transcript, summaries, provider, "model", "summarize", 3);
      memory.remember(id, Message.user("one"));
      memory.remember(id, Message.user("two"));
      memory.remember(id, Message.user("three"));
      memory.remember(id, Message.user("four"));

      Context recalled = memory.recall(id);

      assertThat(provider.callCount()).isEqualTo(1);
      Optional<Summary> saved = summaries.find(id);
      assertThat(saved).isPresent();
      assertThat(saved.get().watermark()).isEqualTo(2L);
      assertThat(saved.get().text()).isEqualTo("folded so far");
      assertThat(recalled.messages())
          .containsExactly(Message.user("folded so far"), Message.user("four"));
    }

    @Test
    void a_tool_exchange_straddling_the_boundary_is_never_split() {
      // Naively folding "everything up to the threshold" would land the cut between the
      // tool_use and its answering tool_result. pairSafeCut must instead walk back to the
      // last genuine user turn, leaving the whole exchange in the unfolded tail.
      ConversationId id = ConversationId.generate();
      Transcript transcript = Transcript.inMemory();
      SummaryStore summaries = SummaryStore.inMemory();
      RecordingTextModelProvider provider = new RecordingTextModelProvider("folded prefix");
      SummarizingMemory memory =
          new SummarizingMemory(transcript, summaries, provider, "model", "summarize", 1);
      Message openingTurn = Message.user("one");
      Message secondTurn = Message.user("issue a coupon, please");
      Message toolUse = Message.assistant(List.of(new ToolUseBlock(toolCall("c1"))));
      Message toolResults =
          Message.toolResults(List.of(new ToolResultBlock("c1", "coupon issued", false)));
      memory.remember(id, openingTurn);
      memory.remember(id, secondTurn);
      memory.remember(id, toolUse);
      memory.remember(id, toolResults);

      Context recalled = memory.recall(id);

      Optional<Summary> saved = summaries.find(id);
      assertThat(saved).isPresent();
      assertThat(saved.get().watermark()).isZero();
      assertThat(recalled.messages())
          .containsExactly(Message.user("folded prefix"), secondTurn, toolUse, toolResults);
    }
  }

  @Nested
  class Crash_recovery {

    @Test
    void a_summary_save_that_never_lands_is_re_done_work_not_lost_words() {
      ConversationId id = ConversationId.generate();
      Transcript transcript = Transcript.inMemory();
      DiscardingSaveSummaryStore summaries =
          new DiscardingSaveSummaryStore(SummaryStore.inMemory());
      RecordingTextModelProvider provider =
          new RecordingTextModelProvider("folded again", "folded again");
      SummarizingMemory memory =
          new SummarizingMemory(transcript, summaries, provider, "model", "summarize", 3);
      memory.remember(id, Message.user("one"));
      memory.remember(id, Message.user("two"));
      memory.remember(id, Message.user("three"));
      memory.remember(id, Message.user("four"));

      Context first = memory.recall(id);
      Context second = memory.recall(id);

      assertThat(provider.callCount()).isEqualTo(2);
      assertThat(second.messages()).isEqualTo(first.messages());
      assertThat(summaries.find(id)).isEmpty();
    }

    /** Every {@link #save} silently discards, simulating a crash between fold and commit. */
    private record DiscardingSaveSummaryStore(SummaryStore delegate) implements SummaryStore {

      @Override
      public Optional<Summary> find(ConversationId id) {
        return delegate.find(id);
      }

      @Override
      public void save(ConversationId id, Summary summary) {
        // discarded on purpose
      }
    }
  }

  @Nested
  class Blank_fold_response {

    /**
     * Should-fix 9 (final review): a blank fold response used to still advance the watermark and
     * save an empty summary, which silently dropped the folded history from every future recall —
     * the watermark said those messages were folded, but the "summary" holding them was nothing. A
     * blank response must instead be treated the same as "nothing safe to fold": the watermark
     * stays put, nothing is saved, and the next recall retries the same fold over the same tail.
     */
    @Test
    void a_blank_model_response_leaves_the_watermark_unmoved_and_recall_still_sees_the_tail() {
      ConversationId id = ConversationId.generate();
      Transcript transcript = Transcript.inMemory();
      SummaryStore summaries = SummaryStore.inMemory();
      RecordingTextModelProvider provider = new RecordingTextModelProvider("   ");
      SummarizingMemory memory =
          new SummarizingMemory(transcript, summaries, provider, "model", "summarize", 3);
      Message first = Message.user("one");
      Message second = Message.user("two");
      Message third = Message.user("three");
      Message fourth = Message.user("four");
      memory.remember(id, first);
      memory.remember(id, second);
      memory.remember(id, third);
      memory.remember(id, fourth);

      Context recalled = memory.recall(id);

      assertThat(provider.callCount()).isEqualTo(1);
      assertThat(summaries.find(id)).isEmpty();
      assertThat(recalled.messages()).containsExactly(first, second, third, fourth);
    }
  }

  @Nested
  class Open_tail_trim {

    @Test
    void a_trailing_unanswered_tool_use_is_dropped_from_the_rendered_tail() {
      ConversationId id = ConversationId.generate();
      Transcript transcript = Transcript.inMemory();
      SummaryStore summaries = SummaryStore.inMemory();
      RecordingTextModelProvider provider = new RecordingTextModelProvider();
      SummarizingMemory memory =
          new SummarizingMemory(transcript, summaries, provider, "model", "summarize", 10);
      Message userTurn = Message.user("issue a coupon, please");
      Message openToolUse = Message.assistant(List.of(new ToolUseBlock(toolCall("c1"))));
      memory.remember(id, userTurn);
      memory.remember(id, openToolUse);

      Context recalled = memory.recall(id);

      assertThat(provider.callCount()).isZero();
      assertThat(recalled.messages()).containsExactly(userTurn);
    }
  }
}
