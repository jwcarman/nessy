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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.transcript.Transcript;

class ContextHydratorTest {

  private static ToolCall toolCall(String id) {
    return new ToolCall(id, "search", JsonNodeFactory.instance.objectNode());
  }

  @Nested
  class Full_hydration {

    @Test
    void full_hydration_returns_the_whole_telling() {
      ConversationId id = ConversationId.generate();
      Transcript transcript = Transcript.inMemory();
      Message first = Message.user("hello");
      Message second = Message.assistant(List.of(new TextBlock("hi there")));
      transcript.append(id, first);
      transcript.append(id, second);
      ContextHydrator hydrator = ContextHydrator.full();

      Context hydrated = hydrator.hydrate(id, transcript);

      assertThat(hydrated.messages()).containsExactly(first, second);
    }

    @Test
    void full_hydration_trims_the_open_tail() {
      ConversationId id = ConversationId.generate();
      Transcript transcript = Transcript.inMemory();
      Message userTurn = Message.user("issue a coupon, please");
      Message openToolUse = Message.assistant(List.of(new ToolUseBlock(toolCall("c1"))));
      transcript.append(id, userTurn);
      transcript.append(id, openToolUse);
      ContextHydrator hydrator = ContextHydrator.full();

      Context hydrated = hydrator.hydrate(id, transcript);

      assertThat(hydrated.messages()).containsExactly(userTurn);
    }
  }

  @Nested
  class Summarizing_hydration {

    @Test
    void summarizing_hydration_is_the_summarizing_memorys_recall() {
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
      Context viaMemory = memory.recall(id);

      SummaryStore hydratorSummaries = SummaryStore.inMemory();
      RecordingTextModelProvider hydratorProvider = new RecordingTextModelProvider("folded so far");
      ContextHydrator hydrator =
          ContextHydrator.summarizing(hydratorSummaries, hydratorProvider, "model", "summarize", 3);
      Transcript sameShapeTranscript = Transcript.inMemory();
      sameShapeTranscript.append(id, Message.user("one"));
      sameShapeTranscript.append(id, Message.user("two"));
      sameShapeTranscript.append(id, Message.user("three"));
      sameShapeTranscript.append(id, Message.user("four"));

      Context viaHydrator = hydrator.hydrate(id, sameShapeTranscript);

      assertThat(viaHydrator.messages()).isEqualTo(viaMemory.messages());
    }

    @Test
    void summarizing_hydration_validates_its_arguments() {
      SummaryStore summaries = SummaryStore.inMemory();
      RecordingTextModelProvider provider = new RecordingTextModelProvider();

      assertThatThrownBy(() -> ContextHydrator.summarizing(null, provider, "model", "summarize", 3))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(
              () -> ContextHydrator.summarizing(summaries, null, "model", "summarize", 3))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(
              () -> ContextHydrator.summarizing(summaries, provider, null, "summarize", 3))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> ContextHydrator.summarizing(summaries, provider, "model", null, 3))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(
              () -> ContextHydrator.summarizing(summaries, provider, "model", "summarize", -1))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
