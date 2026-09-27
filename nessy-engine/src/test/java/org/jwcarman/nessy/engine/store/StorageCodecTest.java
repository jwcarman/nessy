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
package org.jwcarman.nessy.engine.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * Something done to every stored byte, after Jackson: the rows are not JSON any more, and the
 * engine reads them back all the same.
 */
@DisplayName("A storage codec")
class StorageCodecTest {

  private static final AgentType CHAT = new AgentType("chat");

  /** Not a cipher; enough to make a row unreadable to anyone who does not undo it. */
  private static final Codec<byte[]> REVERSED =
      new Codec<>() {
        @Override
        public byte[] encode(byte[] bytes) {
          return reverse(bytes);
        }

        @Override
        public byte[] decode(byte[] bytes) {
          return reverse(bytes);
        }
      };

  private static byte[] reverse(byte[] bytes) {
    byte[] out = new byte[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      out[i] = bytes[bytes.length - 1 - i];
    }
    return out;
  }

  private EngineFixture engine;
  private QueuedHarness<String> harness;

  @BeforeEach
  void startEngine() {
    engine =
        new EngineFixture(
            (request, narrator) ->
                new InferenceResult.Answer(List.of(new Block.Text("a lake monster"))),
            REVERSED);
    harness =
        engine
            .harnesses()
            .create(
                CHAT,
                String.class,
                config ->
                    config
                        .systemPrompt("You are a test assistant.")
                        .effects(e -> e.pollInterval(Duration.ofMillis(100))));
  }

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  @Test
  void every_row_is_written_through_it_and_read_back_through_it() {
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness.tell(agentId, "what is nessy?");

    // The turn completes: state, story and effect rows all went through the codec both ways.
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(engine.story(CHAT, agentId))
                    .filteredOn(AgentEvent.InferenceAnswered.class::isInstance)
                    .hasSize(1));

    // And what is on disk is not JSON: a reader without the codec gets nothing.
    List<byte[]> story =
        engine
            .jdbc()
            .sql("SELECT payload FROM nessy_agent_event WHERE agent_id = ?")
            .params(agentId.value())
            .query(byte[].class)
            .list();
    assertThat(story).isNotEmpty().allSatisfy(row -> assertThat(row[0]).isNotEqualTo((byte) '{'));
    assertThat(new String(reverse(story.getFirst()), java.nio.charset.StandardCharsets.UTF_8))
        .as("and it is JSON again once the codec is undone")
        .startsWith("{");

    // The content is kept apart from the record of it, and goes through the codec too.
    List<byte[]> content =
        engine
            .jdbc()
            .sql("SELECT content FROM nessy_payload WHERE agent_id = ?")
            .params(agentId.value())
            .query(byte[].class)
            .list();
    assertThat(content).isNotEmpty().allSatisfy(row -> assertThat(row[0]).isNotEqualTo((byte) '['));
  }
}
