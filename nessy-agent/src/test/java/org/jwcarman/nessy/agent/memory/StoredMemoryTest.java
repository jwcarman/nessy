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
package org.jwcarman.nessy.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.codec.MessageCodec;
import org.jwcarman.nessy.agent.support.RaceOnceOnAppendSubstrate;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

class StoredMemoryTest {

  private static final MessageCodec CODEC = new MessageCodec(new ObjectMapper());

  @Test
  void aFreshMemoryRecallsAnEmptyContext() {
    var memory = new StoredMemory(new InMemorySubstrate(), "agent-a", TestMappers.plainlyPinned());
    assertThat(memory.recall().messages()).isEmpty();
  }

  @Test
  void rememberedMessagesRecallInOrderAcrossTwoInstancesSharingOneSubstrate() {
    Substrate store = new InMemorySubstrate();
    var writer = new StoredMemory(store, "agent-a", TestMappers.plainlyPinned());
    var reader = new StoredMemory(store, "agent-a", TestMappers.plainlyPinned());

    writer.remember(Message.user("first"));
    writer.remember(Message.user("second"));

    assertThat(reader.recall().messages())
        .containsExactly(Message.user("first"), Message.user("second"));
  }

  @Test
  void rememberRetriesAfterLosingAConflictOnAppend() {
    Substrate delegate = new InMemorySubstrate();
    byte[] competitor =
        CODEC.toJson(Message.user("stole the slot")).getBytes(StandardCharsets.UTF_8);
    Substrate racing = new RaceOnceOnAppendSubstrate(delegate, competitor);
    var memory = new StoredMemory(racing, "agent-a", TestMappers.plainlyPinned());

    memory.remember(Message.user("mine"));

    assertThat(memory.recall().messages())
        .containsExactly(Message.user("stole the slot"), Message.user("mine"));
  }
}
