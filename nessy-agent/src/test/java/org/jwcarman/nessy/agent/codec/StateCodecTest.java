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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

class StateCodecTest {

  @Nested
  class PhaseRoundTrips {

    @Test
    void idlePhaseRoundTrips() {
      var phase = new Phase.Idle();
      assertThat(StateCodec.phase(StateCodec.toJson(phase))).isEqualTo(phase);
    }

    @Test
    void awaitingModelPhaseRoundTrips() {
      var phase = new Phase.AwaitingModel();
      assertThat(StateCodec.phase(StateCodec.toJson(phase))).isEqualTo(phase);
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
      var roundTripped = StateCodec.phase(StateCodec.toJson(phase));
      assertThat(roundTripped).isEqualTo(phase);
      var awaitingTools = (Phase.AwaitingTools) roundTripped;
      assertThat(awaitingTools.pending()).isNotEmpty().containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void anAwaitingToolsPhaseWithNoGatheredResultsYetRoundTrips() {
      var call = new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
      var turn = Message.assistant(List.of(new ToolUseBlock(call)));
      var phase = new Phase.AwaitingTools(turn, Set.of("a"), List.of());
      assertThat(StateCodec.phase(StateCodec.toJson(phase))).isEqualTo(phase);
    }

    @Test
    void pendingIdsSerializeInSortedOrder() {
      var callB = new ToolCall("b", "restart", JsonNodeFactory.instance.objectNode());
      var callA = new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
      var turn = Message.assistant(List.of(new ToolUseBlock(callB), new ToolUseBlock(callA)));
      var phase = new Phase.AwaitingTools(turn, Set.of("b", "a"), List.of());
      assertThat(StateCodec.toJson(phase)).contains("\"pending\":[\"a\",\"b\"]");
    }
  }

  @Nested
  class Discriminator {

    @Test
    void everyPhaseCarriesATypeDiscriminator() {
      assertThat(StateCodec.toJson(new Phase.Idle())).contains("\"type\":\"idle\"");
      assertThat(StateCodec.toJson(new Phase.AwaitingModel()))
          .contains("\"type\":\"awaiting-model\"");
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
      assertThat(StateCodec.phase(json)).isEqualTo(new Phase.Idle());
    }

    @Test
    void anUnknownPhaseDiscriminatorIsRejected() {
      var json =
          """
          {"type":"awaiting-approval"}
          """;
      assertThatThrownBy(() -> StateCodec.phase(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("awaiting-approval");
    }

    @Test
    void malformedPhaseJsonIsRejected() {
      assertThatThrownBy(() -> StateCodec.phase("not json at all"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPhaseMissingItsTypeIsRejected() {
      assertThatThrownBy(() -> StateCodec.phase("{}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAwaitingToolsPayloadWithPendingOutsideTheAssistantTurnIsRejected() {
      var call = new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
      var turn = Message.assistant(List.of(new ToolUseBlock(call)));
      var json =
          "{\"type\":\"awaiting-tools\",\"assistantTurn\":"
              + MessageCodec.toJson(turn)
              + ",\"pending\":[\"a\",\"ghost\"],\"gathered\":[]}";
      assertThatThrownBy(() -> StateCodec.phase(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("ghost");
    }

    @Test
    void anAwaitingToolsPayloadWithNothingPendingIsRejected() {
      var call = new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
      var turn = Message.assistant(List.of(new ToolUseBlock(call)));
      var json =
          "{\"type\":\"awaiting-tools\",\"assistantTurn\":"
              + MessageCodec.toJson(turn)
              + ",\"pending\":[],\"gathered\":[]}";
      assertThatThrownBy(() -> StateCodec.phase(json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNonToolResultBlockInGatheredIsRejectedNamingItsType() {
      var call = new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
      var turn = Message.assistant(List.of(new ToolUseBlock(call)));
      var json =
          "{\"type\":\"awaiting-tools\",\"assistantTurn\":"
              + MessageCodec.toJson(turn)
              + ",\"pending\":[\"a\"],\"gathered\":[{\"type\":\"text\",\"text\":\"oops\"}]}";
      assertThatThrownBy(() -> StateCodec.phase(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("text");
    }

    @Test
    void anAwaitingToolsPayloadWithNonArrayPendingIsRejected() {
      var call = new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
      var turn = Message.assistant(List.of(new ToolUseBlock(call)));
      var json =
          "{\"type\":\"awaiting-tools\",\"assistantTurn\":"
              + MessageCodec.toJson(turn)
              + ",\"pending\":\"oops\",\"gathered\":[]}";
      assertThatThrownBy(() -> StateCodec.phase(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("pending");
    }

    @Test
    void anAwaitingToolsPayloadWithNonArrayGatheredIsRejected() {
      var call = new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
      var turn = Message.assistant(List.of(new ToolUseBlock(call)));
      var json =
          "{\"type\":\"awaiting-tools\",\"assistantTurn\":"
              + MessageCodec.toJson(turn)
              + ",\"pending\":[\"a\"],\"gathered\":\"oops\"}";
      assertThatThrownBy(() -> StateCodec.phase(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("gathered");
    }
  }
}
