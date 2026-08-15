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

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
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
  void the_degenerate_pipeline_is_transcript_memory_in_pipeline_clothing() {
    ConversationId id = ConversationId.generate();
    Transcript transcriptForPipeline = Transcript.inMemory();
    Transcript transcriptForTranscriptMemory = Transcript.inMemory();
    PipelineMemory pipeline = Memory.pipeline(transcriptForPipeline).build();
    TranscriptMemory transcriptMemory = new TranscriptMemory(transcriptForTranscriptMemory);
    Message first = Message.user("one");
    Message second = Message.user("two");
    Message third = Message.user("three");

    pipeline.remember(id, first);
    transcriptMemory.remember(id, first);
    pipeline.remember(id, second);
    transcriptMemory.remember(id, second);
    pipeline.remember(id, third);
    transcriptMemory.remember(id, third);

    assertThat(pipeline.recall(id)).isEqualTo(transcriptMemory.recall(id));
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
    PipelineMemory.Builder builder = Memory.pipeline(transcript);

    assertThatThrownBy(() -> builder.keepRecent(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
