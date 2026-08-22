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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.codec.MessageCodec;
import org.jwcarman.nessy.agent.support.MarkerBytesCodec;
import org.jwcarman.nessy.agent.support.RaceOnceOnAppendSubstrate;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.substrate.Codec;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

class SubstrateMemoryTest {

  private static final MessageCodec CODEC = new MessageCodec(new ObjectMapper());

  @Test
  void aFreshMemoryRecallsAnEmptyContext() {
    var memory =
        new SubstrateMemory(new InMemorySubstrate(), "agent-a", TestMappers.plainlyPinned());
    assertThat(memory.recall().messages()).isEmpty();
  }

  @Test
  void rememberedMessagesRecallInOrderAcrossTwoInstancesSharingOneSubstrate() {
    Substrate store = new InMemorySubstrate();
    var writer = new SubstrateMemory(store, "agent-a", TestMappers.plainlyPinned());
    var reader = new SubstrateMemory(store, "agent-a", TestMappers.plainlyPinned());

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
    var memory = new SubstrateMemory(racing, "agent-a", TestMappers.plainlyPinned());

    memory.remember(Message.user("mine"));

    assertThat(memory.recall().messages())
        .containsExactly(Message.user("stole the slot"), Message.user("mine"));
  }

  @Nested
  class ACustomCodec {

    @Test
    void isHonoredByBothWritesAndReads() {
      Substrate substrate = new InMemorySubstrate();
      Codec<Message> codec =
          Codec.json(TestMappers.plainlyPinned(), Message.class).then(new MarkerBytesCodec());
      var memory = new SubstrateMemory(substrate, "agent-a", codec);

      memory.remember(Message.user("mine"));

      byte[] rawPayload = substrate.entries("memory", "agent-a", 1).getFirst().payload();
      assertThat(MarkerBytesCodec.isMarked(rawPayload)).isTrue();
      assertThat(memory.recall().messages()).containsExactly(Message.user("mine"));
    }
  }
}
