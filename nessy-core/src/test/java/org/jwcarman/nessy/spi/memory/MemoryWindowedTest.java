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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;

/**
 * {@link Memory#windowed(Memory, int)}: a hand-rolled recording delegate stands in for a real
 * {@code Memory} so both duties — {@code remember} delegating whole, {@code recall} clipping via
 * {@link Context#keepRecent(int)} — are pinned independently, with no mocking library.
 */
class MemoryWindowedTest {

  /** Records every {@code remember} call verbatim and recalls whatever it was told, unclipped. */
  private static final class RecordingMemory implements Memory {

    private final List<Message> told = new ArrayList<>();
    private final List<ConversationId> rememberedIds = new ArrayList<>();

    @Override
    public void remember(ConversationId id, Message message) {
      rememberedIds.add(id);
      told.add(message);
    }

    @Override
    public Context recall(ConversationId id) {
      return new Context(List.copyOf(told));
    }
  }

  @Test
  void remember_delegates_straight_through() {
    RecordingMemory delegate = new RecordingMemory();
    Memory windowed = Memory.windowed(delegate, 2);
    ConversationId id = ConversationId.generate();
    Message message = Message.user("hi");

    windowed.remember(id, message);

    assertThat(delegate.told).containsExactly(message);
    assertThat(delegate.rememberedIds).containsExactly(id);
  }

  @Test
  void recall_clips_to_the_last_n_messages() {
    RecordingMemory delegate = new RecordingMemory();
    Memory windowed = Memory.windowed(delegate, 1);
    ConversationId id = ConversationId.generate();
    Message first = Message.user("first");
    Message second = Message.user("second");
    Message third = Message.user("third");
    delegate.remember(id, first);
    delegate.remember(id, second);
    delegate.remember(id, third);

    Context recalled = windowed.recall(id);

    assertThat(recalled.messages()).containsExactly(third);
  }

  @Test
  void recall_under_n_messages_stays_unclipped() {
    RecordingMemory delegate = new RecordingMemory();
    Memory windowed = Memory.windowed(delegate, 5);
    ConversationId id = ConversationId.generate();
    Message first = Message.user("first");
    Message second = Message.user("second");
    delegate.remember(id, first);
    delegate.remember(id, second);

    Context recalled = windowed.recall(id);

    assertThat(recalled.messages()).containsExactly(first, second);
  }

  @Test
  void a_window_below_one_is_rejected() {
    RecordingMemory zeroDelegate = new RecordingMemory();
    RecordingMemory negativeDelegate = new RecordingMemory();

    assertThatThrownBy(() -> Memory.windowed(zeroDelegate, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("window must be at least 1");
    assertThatThrownBy(() -> Memory.windowed(negativeDelegate, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("window must be at least 1");
  }
}
