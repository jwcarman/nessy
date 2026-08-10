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
package org.jwcarman.nessy.spi.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.event.EventSpine;
import org.jwcarman.nessy.api.event.EventSpines;
import org.jwcarman.nessy.api.event.MessageAppended;
import org.jwcarman.nessy.api.message.Message;

class TranscriptStoreTest {

  private static final ConversationId ID = new ConversationId("s1");

  @Test
  void appends_read_back_in_order() {
    InMemoryTranscriptStore store = new InMemoryTranscriptStore();
    TranscriptEntry first = new TranscriptEntry(Message.user("hi"), Usage.zero());
    TranscriptEntry second = new TranscriptEntry(Message.assistant(List.of()), new Usage(1, 2, 0));

    store.append(ID, first);
    store.append(ID, second);

    assertThat(store.entries(ID)).containsExactly(first, second);
  }

  @Test
  void an_unknown_session_reads_empty() {
    InMemoryTranscriptStore store = new InMemoryTranscriptStore();

    assertThat(store.entries(new ConversationId("unknown"))).isEmpty();
  }

  @Test
  void entries_are_immutable_to_readers() {
    InMemoryTranscriptStore store = new InMemoryTranscriptStore();
    store.append(ID, new TranscriptEntry(Message.user("hi"), Usage.zero()));

    List<TranscriptEntry> read = store.entries(ID);

    assertThatCode(() -> read.add(new TranscriptEntry(Message.user("more"), Usage.zero())))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(store.entries(ID)).hasSize(1);
  }

  @Test
  void declareListener_turns_each_MessageAppended_into_one_append() {
    InMemoryTranscriptStore store = new InMemoryTranscriptStore();
    EventSpine hub = EventSpines.of(List.of(store.declareListener()));
    Message message = Message.user("hi");
    Usage usage = new Usage(3, 4, 0);

    hub.emit(new MessageAppended(ID, message, usage));

    assertThat(store.entries(ID)).containsExactly(new TranscriptEntry(message, usage));
  }

  @Test
  void declareListener_ignores_events_of_other_types() {
    InMemoryTranscriptStore store = new InMemoryTranscriptStore();
    EventSpine hub = EventSpines.of(List.of(store.declareListener()));

    hub.emit("not a MessageAppended");

    assertThat(store.entries(ID)).isEmpty();
  }
}
