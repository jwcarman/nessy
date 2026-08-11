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
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;

class ListMemoryTest {

  private final ListMemory memory = new ListMemory();

  @Test
  void recallsExactlyWhatItWasToldInOrder() {
    ConversationId id = ConversationId.generate();
    Message first = Message.user("hello");
    Message second = Message.assistant(List.of(new TextBlock("hi there")));
    memory.remember(id, first);
    memory.remember(id, second);

    Context recalled = memory.recall(id);

    assertThat(recalled.messages()).containsExactly(first, second);
  }

  @Test
  void recallsNothingForAConversationNeverToldAnything() {
    Context recalled = memory.recall(ConversationId.generate());
    assertThat(recalled.messages()).isEmpty();
  }

  @Test
  void keepsConversationsApart() {
    ConversationId one = ConversationId.generate();
    ConversationId other = ConversationId.generate();
    memory.remember(one, Message.user("for one"));
    memory.remember(other, Message.user("for the other"));

    assertThat(memory.recall(one).messages()).containsExactly(Message.user("for one"));
    assertThat(memory.recall(other).messages()).containsExactly(Message.user("for the other"));
  }

  @Test
  void toleratesTheSameMessageToldTwiceInARow() {
    // At-least-once tellings (design 2026-08-11, ruling 6): a crash between telling
    // Memory and persisting state re-tells the same message. remember is idempotent.
    ConversationId id = ConversationId.generate();
    Message toldFirst = Message.user("once only, please");
    Message toldAgain = Message.user("once only, please");
    memory.remember(id, toldFirst);
    memory.remember(id, toldAgain);

    assertThat(memory.recall(id).messages()).containsExactly(toldFirst);
  }

  @Test
  void recallReturnsAnImmutableSnapshotUnaffectedByLaterRemembering() {
    // recall's list must be a point-in-time snapshot, not a live view: if remember ever
    // mutated a list already handed out by recall, a reader holding an earlier recall
    // result would see later tellings appear underneath it. Fixed by always storing a
    // fresh List.copyOf in ListMemory#remember rather than mutating in place — this also
    // removes the unsynchronized-read race between remember and recall on the same id.
    ConversationId id = ConversationId.generate();
    Message first = Message.user("first");
    memory.remember(id, first);

    Context earlySnapshot = memory.recall(id);
    memory.remember(id, Message.user("second"));

    assertThat(earlySnapshot.messages()).containsExactly(first);
    assertThatThrownBy(() -> earlySnapshot.messages().add(Message.user("mutation")))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
