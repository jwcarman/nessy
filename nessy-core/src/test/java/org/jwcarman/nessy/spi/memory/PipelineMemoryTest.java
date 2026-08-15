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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.transcript.Transcript;

class PipelineMemoryTest {

  /** A hand-rolled hydrator that records the exact {@link Transcript} reference it was handed. */
  private static final class CapturingHydrator implements ContextHydrator {

    private Transcript received;

    @Override
    public Context hydrate(ConversationId id, Transcript transcript) {
      this.received = transcript;
      return Context.empty();
    }
  }

  private static boolean hasText(Message message, String text) {
    List<ContentBlock> content = message.content();
    return content.size() == 1
        && content.getFirst() instanceof TextBlock(String blockText)
        && blockText.equals(text);
  }

  @Test
  void the_degenerate_pipeline_hydrates_with_the_full_floor() {
    // One shipped Memory now (design §10): the degenerate pipeline is exactly
    // ContextHydrator.full()'s hydration over the same transcript, not a second implementation
    // pinned to agree with it.
    ConversationId id = ConversationId.generate();
    Transcript transcript = Transcript.inMemory();
    PipelineMemory pipeline = Memory.pipeline(transcript).build();
    Message first = Message.user("one");
    Message second = Message.user("two");
    Message third = Message.user("three");

    pipeline.remember(id, first);
    pipeline.remember(id, second);
    pipeline.remember(id, third);

    assertThat(pipeline.recall(id)).isEqualTo(ContextHydrator.full().hydrate(id, transcript));
  }

  @Test
  void recalls_nothing_for_a_conversation_never_told_anything() {
    Transcript transcript = Transcript.inMemory();
    PipelineMemory memory = Memory.pipeline(transcript).build();

    Context recalled = memory.recall(ConversationId.generate());

    assertThat(recalled.messages()).isEmpty();
  }

  @Test
  void keeps_conversations_apart() {
    Transcript transcript = Transcript.inMemory();
    PipelineMemory memory = Memory.pipeline(transcript).build();
    ConversationId one = ConversationId.generate();
    ConversationId other = ConversationId.generate();

    memory.remember(one, Message.user("for one"));
    memory.remember(other, Message.user("for the other"));

    assertThat(memory.recall(one).messages()).containsExactly(Message.user("for one"));
    assertThat(memory.recall(other).messages()).containsExactly(Message.user("for the other"));
  }

  @Test
  void tolerates_the_same_message_told_twice_in_a_row() {
    // At-least-once tellings (design 2026-08-11, ruling 6): a crash between telling Memory and
    // persisting state re-tells the same message. remember is idempotent — the transcript's own
    // no-stutter rule, not reimplemented here.
    Transcript transcript = Transcript.inMemory();
    PipelineMemory memory = Memory.pipeline(transcript).build();
    ConversationId id = ConversationId.generate();
    Message toldFirst = Message.user("once only, please");
    Message toldAgain = Message.user("once only, please");

    memory.remember(id, toldFirst);
    memory.remember(id, toldAgain);

    assertThat(memory.recall(id).messages()).containsExactly(toldFirst);
  }

  @Test
  void recall_returns_an_immutable_snapshot() {
    Transcript transcript = Transcript.inMemory();
    PipelineMemory memory = Memory.pipeline(transcript).build();
    ConversationId id = ConversationId.generate();
    Message first = Message.user("first");
    memory.remember(id, first);

    Context recalled = memory.recall(id);
    List<Message> messages = recalled.messages();

    assertThat(messages).containsExactly(first);
    Message mutation = Message.user("mutation");
    assertThatThrownBy(() -> messages.add(mutation))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void recall_keeps_an_answered_tool_use_pair_intact() {
    // Narrowly targeted trimming: once the batched results message lands, the tool-use message is
    // no longer trailing and no longer open, so it must survive recall along with its answer.
    Transcript transcript = Transcript.inMemory();
    PipelineMemory memory = Memory.pipeline(transcript).build();
    ConversationId id = ConversationId.generate();
    Message userTurn = Message.user("issue a coupon, please");
    Message answeredToolUse =
        Message.assistant(
            List.of(
                new ToolUseBlock(
                    new ToolCall("c1", "issue_coupon", JsonNodeFactory.instance.objectNode()))));
    Message toolResults =
        Message.toolResults(List.of(new ToolResultBlock("c1", "coupon issued", false)));
    memory.remember(id, userTurn);
    memory.remember(id, answeredToolUse);
    memory.remember(id, toolResults);

    Context recalled = memory.recall(id);

    assertThat(recalled.messages()).containsExactly(userTurn, answeredToolUse, toolResults);
  }

  @Test
  void two_pipeline_memories_over_the_same_transcript_see_each_others_tellings() {
    // The seam is the storage, the memory is the policy: two PipelineMemory instances wrapping
    // the same Transcript are two windows on one log, not two logs.
    Transcript transcript = Transcript.inMemory();
    PipelineMemory memory = Memory.pipeline(transcript).build();
    PipelineMemory other = Memory.pipeline(transcript).build();
    ConversationId id = ConversationId.generate();
    Message first = Message.user("told through the first instance");
    Message second = Message.user("told through the second instance");

    memory.remember(id, first);
    other.remember(id, second);

    assertThat(memory.recall(id).messages()).containsExactly(first, second);
    assertThat(other.recall(id).messages()).containsExactly(first, second);
  }

  @Test
  void remember_appends_to_the_transcript() {
    ConversationId id = ConversationId.generate();
    Transcript transcript = Transcript.inMemory();
    PipelineMemory memory = Memory.pipeline(transcript).build();
    Message message = Message.user("hello");

    memory.remember(id, message);

    assertThat(transcript.all(id)).extracting(Transcript.Entry::message).containsExactly(message);
  }

  @Test
  void stages_run_in_registration_order_each_seeing_its_predecessors_output() {
    ConversationId id = ConversationId.generate();
    Transcript transcript = Transcript.inMemory();
    transcript.append(id, Message.user("start"));
    ContextTransformer appendStage1 =
        (conversationId, context) -> context.enrich(new TextBlock("stage1"));
    ContextTransformer mutateStage2 =
        (conversationId, context) -> {
          int sizeSeen = context.messages().size();
          return context.map(
              message ->
                  hasText(message, "start")
                      ? Message.user("start-seen-when-size-was-" + sizeSeen)
                      : message);
        };
    ContextTransformer appendStage3 =
        (conversationId, context) ->
            context.enrich(new TextBlock("stage3-saw-size-" + context.messages().size()));
    PipelineMemory memory =
        Memory.pipeline(transcript)
            .transform(appendStage1)
            .transform(mutateStage2)
            .transform(appendStage3)
            .build();

    Context recalled = memory.recall(id);

    assertThat(recalled.messages())
        .containsExactly(
            Message.user("start-seen-when-size-was-2"),
            Message.user("stage1"),
            Message.user("stage3-saw-size-2"));
  }

  @Test
  void a_nothing_to_add_stage_leaves_the_context_untouched() {
    ConversationId id = ConversationId.generate();
    Transcript transcript = Transcript.inMemory();
    Message only = Message.user("unchanged");
    transcript.append(id, only);
    ContextTransformer identity = (conversationId, context) -> context;
    PipelineMemory memory = Memory.pipeline(transcript).transform(identity).build();

    Context recalled = memory.recall(id);

    assertThat(recalled.messages()).containsExactly(only);
  }

  @Test
  void an_appended_amendment_survives_a_clamp_registered_before_it() {
    ConversationId id = ConversationId.generate();
    Transcript transcript = Transcript.inMemory();
    transcript.append(id, Message.user("m1"));
    transcript.append(id, Message.user("m2"));
    transcript.append(id, Message.user("m3"));
    transcript.append(id, Message.user("m4"));
    transcript.append(id, Message.user("m5"));
    ContextTransformer appendAmendment =
        (conversationId, context) -> context.enrich(new TextBlock("amendment"));
    PipelineMemory memory =
        Memory.pipeline(transcript).keepRecent(2).transform(appendAmendment).build();

    Context recalled = memory.recall(id);

    assertThat(recalled.messages())
        .containsExactly(Message.user("m4"), Message.user("m5"), Message.user("amendment"));
  }

  @Test
  void a_throwing_stage_fails_the_recall() {
    ConversationId id = ConversationId.generate();
    Transcript transcript = Transcript.inMemory();
    transcript.append(id, Message.user("hello"));
    ContextTransformer throwing =
        (conversationId, context) -> {
          throw new IllegalStateException("boom");
        };
    PipelineMemory memory = Memory.pipeline(transcript).transform(throwing).build();

    assertThatThrownBy(() -> memory.recall(id)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void a_custom_hydrator_receives_the_pipelines_own_transcript() {
    ConversationId id = ConversationId.generate();
    Transcript transcript = Transcript.inMemory();
    CapturingHydrator hydrator = new CapturingHydrator();
    PipelineMemory memory = Memory.pipeline(transcript).hydrator(hydrator).build();

    memory.recall(id);

    assertThat(hydrator.received).isSameAs(transcript);
  }

  @Test
  void one_hydration_strategy_per_pipeline() {
    Transcript transcript = Transcript.inMemory();
    PipelineMemory.Builder builder = Memory.pipeline(transcript).hydrator(ContextHydrator.full());

    assertThatThrownBy(() -> builder.hydrator(ContextHydrator.full()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void keep_recent_rejects_a_window_below_one() {
    Transcript transcript = Transcript.inMemory();
    PipelineMemory.Builder zeroBuilder = Memory.pipeline(transcript);
    PipelineMemory.Builder negativeBuilder = Memory.pipeline(transcript);

    assertThatThrownBy(() -> zeroBuilder.keepRecent(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("window must be at least 1");
    assertThatThrownBy(() -> negativeBuilder.keepRecent(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("window must be at least 1");
  }
}
