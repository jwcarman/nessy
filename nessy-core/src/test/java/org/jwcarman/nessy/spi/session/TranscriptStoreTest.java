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
package org.jwcarman.nessy.spi.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.session.SessionId;
import org.jwcarman.nessy.api.session.Usage;

class TranscriptStoreTest {

  private static final SessionId ID = new SessionId("s1");

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

    assertThat(store.entries(new SessionId("unknown"))).isEmpty();
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
  void none_swallows_everything_silently() {
    TranscriptStore store = TranscriptStore.none();

    assertThatCode(() -> store.append(ID, new TranscriptEntry(Message.user("hi"), Usage.zero())))
        .doesNotThrowAnyException();
  }
}
