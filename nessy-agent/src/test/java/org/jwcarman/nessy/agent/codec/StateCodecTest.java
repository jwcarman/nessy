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
package org.jwcarman.nessy.agent.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

class StateCodecTest {

  private static final StateCodec CODEC = new StateCodec(Codecs.copyAndPin(new ObjectMapper()));
  private static final MessageCodec MESSAGE_CODEC =
      new MessageCodec(Codecs.copyAndPin(new ObjectMapper()));

  @Nested
  class PhaseRoundTrips {

    @Test
    void idlePhaseRoundTrips() {
      var phase = new Phase.Idle();
      assertThat(CODEC.phase(CODEC.toJson(phase))).isEqualTo(phase);
    }

    @Test
    void awaitingModelPhaseRoundTrips() {
      var phase = new Phase.AwaitingModel();
      assertThat(CODEC.phase(CODEC.toJson(phase))).isEqualTo(phase);
    }

    @Test
    void aPopulatedAwaitingToolsPhaseRoundTripsWithItsInvariantsIntact() {
      var callA = new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
      var callB = new ToolCall("b", "restart", JsonNodeFactory.instance.objectNode());
      var turn =
          Message.assistant(
              List.of(new ToolUseBlock(callA, "sig-a"), new ToolUseBlock(callB, null)));
      var phase =
          new Phase.AwaitingTools(
              turn, Set.of("a", "b"), List.of(new ToolResultBlock("z", "already gathered", false)));
      var roundTripped = CODEC.phase(CODEC.toJson(phase));
      assertThat(roundTripped).isEqualTo(phase);
      var awaitingTools = (Phase.AwaitingTools) roundTripped;
      assertThat(awaitingTools.pending()).isNotEmpty().containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void anAwaitingToolsPhaseWithNoGatheredResultsYetRoundTrips() {
      var call = new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
      var turn = Message.assistant(List.of(new ToolUseBlock(call)));
      var phase = new Phase.AwaitingTools(turn, Set.of("a"), List.of());
      assertThat(CODEC.phase(CODEC.toJson(phase))).isEqualTo(phase);
    }

    /**
     * Final review round (T3): the exact-shape golden for a populated {@code AwaitingTools} phase —
     * pinned so a future change to field order, discriminator spelling, or inclusion behavior fails
     * loudly here rather than only in a round-trip test blind to the wire shape itself.
     */
    @Test
    void aPopulatedAwaitingToolsPhaseRendersTheExactPinnedJsonShape() {
      var callA = new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
      var callB = new ToolCall("b", "restart", JsonNodeFactory.instance.objectNode());
      var turn =
          Message.assistant(
              List.of(new ToolUseBlock(callA, "sig-a"), new ToolUseBlock(callB, null)));
      var phase =
          new Phase.AwaitingTools(
              turn, Set.of("a", "b"), List.of(new ToolResultBlock("z", "already gathered", false)));

      String json = CODEC.toJson(phase);

      assertThat(json)
          .isEqualTo(
              "{\"type\":\"awaiting-tools\",\"assistantTurn\":{\"role\":\"assistant\","
                  + "\"content\":[{\"type\":\"tool-use\",\"id\":\"a\",\"name\":\"lookup\","
                  + "\"arguments\":{},\"signature\":\"sig-a\"},{\"type\":\"tool-use\",\"id\":\"b\","
                  + "\"name\":\"restart\",\"arguments\":{}}]},\"pending\":[\"a\",\"b\"],"
                  + "\"gathered\":[{\"type\":\"tool-result\",\"toolUseId\":\"z\","
                  + "\"content\":\"already gathered\",\"isError\":false}]}");
    }

    @Test
    void pendingIdsSerializeInSortedOrder() {
      var callB = new ToolCall("b", "restart", JsonNodeFactory.instance.objectNode());
      var callA = new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
      var turn = Message.assistant(List.of(new ToolUseBlock(callB), new ToolUseBlock(callA)));
      var phase = new Phase.AwaitingTools(turn, Set.of("b", "a"), List.of());
      assertThat(CODEC.toJson(phase)).contains("\"pending\":[\"a\",\"b\"]");
    }
  }

  @Nested
  class Discriminator {

    @Test
    void everyPhaseCarriesATypeDiscriminator() {
      assertThat(CODEC.toJson(new Phase.Idle())).contains("\"type\":\"idle\"");
      assertThat(CODEC.toJson(new Phase.AwaitingModel())).contains("\"type\":\"awaiting-model\"");
    }
  }

  @Nested
  class ToleranceAndRejection {

    @Test
    void anUnknownFieldOnAPhaseIsIgnored() {
      var json =
          """
          {"type":"idle","fromTheFuture":true}
          """;
      assertThat(CODEC.phase(json)).isEqualTo(new Phase.Idle());
    }

    @Test
    void anUnknownPhaseDiscriminatorIsRejected() {
      var json =
          """
          {"type":"awaiting-approval"}
          """;
      assertThatThrownBy(() -> CODEC.phase(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("awaiting-approval");
    }

    @Test
    void malformedPhaseJsonIsRejected() {
      assertThatThrownBy(() -> CODEC.phase("not json at all"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPhaseMissingItsTypeIsRejected() {
      assertThatThrownBy(() -> CODEC.phase("{}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAwaitingToolsPayloadWithNothingPendingIsRejected() {
      var call = new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
      var turn = Message.assistant(List.of(new ToolUseBlock(call)));
      var json =
          "{\"type\":\"awaiting-tools\",\"assistantTurn\":"
              + MESSAGE_CODEC.toJson(turn)
              + ",\"pending\":[],\"gathered\":[]}";
      assertThatThrownBy(() -> CODEC.phase(json)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.jwcarman.nessy.agent.codec.StateCodecTest#malformedAwaitingToolsPayloads")
    void anAwaitingToolsPayloadWithAStructuralDefectIsRejectedNamingIt(
        String description, String pendingJson, String gatheredJson, String expectedMessage) {
      var call = new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
      var turn = Message.assistant(List.of(new ToolUseBlock(call)));
      var json =
          "{\"type\":\"awaiting-tools\",\"assistantTurn\":"
              + MESSAGE_CODEC.toJson(turn)
              + ",\"pending\":"
              + pendingJson
              + ",\"gathered\":"
              + gatheredJson
              + "}";
      assertThatThrownBy(() -> CODEC.phase(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(expectedMessage);
    }
  }

  private static Stream<Arguments> malformedAwaitingToolsPayloads() {
    return Stream.of(
        Arguments.of("pending outside the assistant turn", "[\"a\",\"ghost\"]", "[]", "ghost"),
        Arguments.of(
            "a non-tool-result block in gathered",
            "[\"a\"]",
            "[{\"type\":\"text\",\"text\":\"oops\"}]",
            "text"),
        Arguments.of("a non-array pending", "\"oops\"", "[]", "pending"),
        Arguments.of("a non-array gathered", "[\"a\"]", "\"oops\"", "gathered"));
  }
}
