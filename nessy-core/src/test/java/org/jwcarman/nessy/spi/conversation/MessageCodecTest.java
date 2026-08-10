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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.ImageBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

class MessageCodecTest {

  private final MessageCodec codec = MessageCodec.json(new ObjectMapper());

  private Message roundTrip(Message message) {
    return codec.decode(codec.encode(message));
  }

  @Nested
  class Every_block_variant_survives_the_round_trip {

    @Test
    void text_block() {
      Message message = Message.assistant(List.of(new TextBlock("hello")));

      assertThat(roundTrip(message)).isEqualTo(message);
    }

    @Test
    void tool_use_block() {
      Message message =
          Message.assistant(
              List.of(
                  new ToolUseBlock(
                      new ToolCall("c1", "echo", JsonNodeFactory.instance.objectNode()))));

      assertThat(roundTrip(message)).isEqualTo(message);
    }

    @Test
    void tool_result_block() {
      Message message = Message.toolResults(List.of(new ToolResultBlock("c1", "echoed", false)));

      assertThat(roundTrip(message)).isEqualTo(message);
    }

    @Test
    void thinking_block() {
      Message message = Message.assistant(List.of(new ThinkingBlock("reasoning", "sig")));

      assertThat(roundTrip(message)).isEqualTo(message);
    }

    @Test
    void redacted_thinking_block() {
      Message message = Message.assistant(List.of(new RedactedThinkingBlock("opaque")));

      assertThat(roundTrip(message)).isEqualTo(message);
    }

    @Test
    void image_block() {
      Message message = Message.user(List.of(new ImageBlock("image/png", "YmFzZTY0")));

      assertThat(roundTrip(message)).isEqualTo(message);
    }
  }

  @Test
  void the_encoding_is_utf8_json() throws Exception {
    Message message = Message.user("café");

    byte[] encoded = codec.encode(message);

    String json = new String(encoded, StandardCharsets.UTF_8);
    JsonNode tree = new ObjectMapper().readTree(json);
    assertThat(tree.get("role").asText()).isEqualTo("USER");
  }
}
