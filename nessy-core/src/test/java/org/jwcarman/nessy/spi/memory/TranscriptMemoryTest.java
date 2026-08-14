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
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

class TranscriptMemoryTest {

  private final Transcript transcript = Transcript.inMemory();
  private final TranscriptMemory memory = new TranscriptMemory(transcript);

  @Test
  void recalls_exactly_what_it_was_told_in_order() {
    ConversationId id = ConversationId.generate();
    Message first = Message.user("hello");
    Message second = Message.assistant(List.of(new TextBlock("hi there")));
    memory.remember(id, first);
    memory.remember(id, second);

    Context recalled = memory.recall(id);

    assertThat(recalled.messages()).containsExactly(first, second);
  }

  @Test
  void recalls_nothing_for_a_conversation_never_told_anything() {
    Context recalled = memory.recall(ConversationId.generate());
    assertThat(recalled.messages()).isEmpty();
  }

  @Test
  void keeps_conversations_apart() {
    ConversationId one = ConversationId.generate();
    ConversationId other = ConversationId.generate();
    memory.remember(one, Message.user("for one"));
    memory.remember(other, Message.user("for the other"));

    assertThat(memory.recall(one).messages()).containsExactly(Message.user("for one"));
    assertThat(memory.recall(other).messages()).containsExactly(Message.user("for the other"));
  }

  @Test
  void tolerates_the_same_message_told_twice_in_a_row() {
    // At-least-once tellings (design 2026-08-11, ruling 6): a crash between telling
    // Memory and persisting state re-tells the same message. remember is idempotent —
    // the transcript's own no-stutter rule, not reimplemented here.
    ConversationId id = ConversationId.generate();
    Message toldFirst = Message.user("once only, please");
    Message toldAgain = Message.user("once only, please");
    memory.remember(id, toldFirst);
    memory.remember(id, toldAgain);

    assertThat(memory.recall(id).messages()).containsExactly(toldFirst);
  }

  @Test
  void recall_returns_an_immutable_snapshot() {
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
  void recall_drops_a_trailing_unanswered_tool_use_so_the_context_stays_legal() {
    // The loop remembers the assistant's tool-use message the moment its fold settles, before it
    // learns whether the call will park — so a parked conversation's raw telling can legitimately
    // end in an unanswered tool-use message. Memory#recall is contracted to always return a legal
    // Context, so that open tail must not surface here.
    ConversationId id = ConversationId.generate();
    Message userTurn = Message.user("issue a coupon, please");
    Message openToolUse =
        Message.assistant(
            List.of(
                new ToolUseBlock(
                    new ToolCall("c1", "issue_coupon", JsonNodeFactory.instance.objectNode()))));
    memory.remember(id, userTurn);
    memory.remember(id, openToolUse);

    Context recalled = memory.recall(id);

    assertThat(recalled.messages()).containsExactly(userTurn);
  }

  @Test
  void recall_keeps_an_answered_tool_use_pair_intact() {
    // The trimming above must be narrowly targeted: once the batched results message lands, the
    // tool-use message is no longer trailing and no longer open — dropping it (or any answered
    // tool-use message) would be wrong. A recall that always drops the last tool-use-bearing
    // assistant message, or all of them, would wrongly pass the test above; this one pins the
    // other direction.
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
  void two_transcript_memories_over_the_same_transcript_see_each_others_tellings() {
    // The seam is the storage, the memory is the policy: two TranscriptMemory instances
    // wrapping the same Transcript are two windows on one log, not two logs.
    ConversationId id = ConversationId.generate();
    TranscriptMemory other = new TranscriptMemory(transcript);
    Message first = Message.user("told through the first instance");
    Message second = Message.user("told through the second instance");

    memory.remember(id, first);
    other.remember(id, second);

    assertThat(memory.recall(id).messages()).containsExactly(first, second);
    assertThat(other.recall(id).messages()).containsExactly(first, second);
  }
}
