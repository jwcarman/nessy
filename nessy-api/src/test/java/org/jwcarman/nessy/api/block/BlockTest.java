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
package org.jwcarman.nessy.api.block;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.codec.spi.Codec;
import tools.jackson.databind.json.JsonMapper;

/**
 * The grammar's two jobs, tested where they are actually kept: what a block refuses to be, and what
 * it looks like once it is written down.
 *
 * <p>The positions themselves need no test. {@code Text} being legal in an observation and {@code
 * ToolCall} not being legal in an answer are compile-time facts, and a test that asserted them
 * could only assert what had already compiled.
 */
class BlockTest {

  private final Codec<Block> codec =
      new JacksonCodecFactory(JsonMapper.builder().build()).create(Block.class);

  @Test
  void aCallWithoutAnIdCanNeverBeDischarged() {
    assertThatThrownBy(() -> new Block.ToolCall(" ", "lookup", "{}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id");
  }

  @Test
  void aCallWithoutANameNamesNothingToRun() {
    assertThatThrownBy(() -> new Block.ToolCall("call_1", "", "{}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
  }

  /**
   * Models emit broken JSON routinely, and that is a tool failure the model can be told about and
   * retry. A block that refused to exist would instead fail the whole turn in the adapter decoding
   * the response, which is the one place with no way to report it.
   */
  @Test
  void argumentsAreNotParsedSoMalformedOnesSurviveToBeReported() {
    Block.ToolCall call = new Block.ToolCall("call_1", "lookup", "{\"q\": ");
    assertThat(call.arguments()).isEqualTo("{\"q\": ");
  }

  /** Zero-argument tools are ordinary. Nothing here demands a property. */
  @Test
  void aCallWithNoArgumentsIsAnEmptyObject() {
    assertThat(new Block.ToolCall("call_1", "tell-me-a-joke", "{}").arguments()).isEqualTo("{}");
  }

  /**
   * The discriminator is a stored format: transcripts already on disk name it, so changing this
   * string is a migration and this assertion is the thing that says so out loud.
   */
  @Test
  void aCallIsStoredUnderItsOwnDiscriminator() {
    String json =
        new String(
            codec.encode(new Block.ToolCall("call_1", "lookup", "{\"q\":\"x\"}")),
            StandardCharsets.UTF_8);
    assertThat(json).contains("\"type\":\"tool-call\"");
  }

  @Test
  void aCallRoundTripsThroughStorageUnchanged() {
    Block.ToolCall call = new Block.ToolCall("call_1", "lookup", "{\"q\":\"x\"}");
    assertThat(codec.decode(codec.encode(call))).isEqualTo(call);
  }
}
