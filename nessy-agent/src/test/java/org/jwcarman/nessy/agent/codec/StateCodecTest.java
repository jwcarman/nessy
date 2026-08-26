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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentPhase;
import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.agent.ToolCallPhase;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.api.tool.authorization.Key;

class StateCodecTest {

  private static final ObjectMapper PINNED = Codecs.copyAndPin(new ObjectMapper());
  private static final StateCodec CODEC = new StateCodec(PINNED);
  private static final MessageCodec MESSAGE_CODEC =
      new MessageCodec(Codecs.copyAndPin(new ObjectMapper()));
  private static final ModelResponseId RESPONSE_ID = ModelResponseId.of("response-1");

  private static final ToolCall CALL_A =
      new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
  private static final ToolCall CALL_B =
      new ToolCall("b", "restart", JsonNodeFactory.instance.objectNode());
  private static final ToolCall CALL_C =
      new ToolCall("c", "deploy", JsonNodeFactory.instance.objectNode());
  private static final ToolCall CALL_D =
      new ToolCall("d", "drain", JsonNodeFactory.instance.objectNode());
  private static final ToolCall CALL_E =
      new ToolCall("e", "audit", JsonNodeFactory.instance.objectNode());

  private static Message turnOf(ToolCall... calls) {
    List<ContentBlock> blocks = new ArrayList<>();
    for (ToolCall call : calls) {
      blocks.add(new ToolUseBlock(call, null));
    }
    return Message.assistant(blocks);
  }

  private static final Key<String> TICKET = new Key<>(String.class, "test.ticket");

  private static ApprovalRequest request() {
    return ApprovalRequest.draft("ops", "prod-1", CALL_B, Map.of(), PINNED)
        .action("restart prod-1")
        .deposit(TICKET, "OPS-42")
        .freeze();
  }

  @Nested
  class PhaseRoundTrips {

    @Test
    void idlePhaseRoundTrips() {
      var phase = new AgentPhase.Idle();
      assertThat(CODEC.phase(CODEC.toJson(phase))).isEqualTo(phase);
    }

    @Test
    void awaitingModelPhaseRoundTrips() {
      var phase = new AgentPhase.AwaitingModel();
      assertThat(CODEC.phase(CODEC.toJson(phase))).isEqualTo(phase);
    }

    @Test
    void everyToolCallPhaseRoundTripsIncludingTheParkedRequest() {
      var turn = turnOf(CALL_A, CALL_B, CALL_C, CALL_D, CALL_E);
      var phase =
          new AgentPhase.AwaitingTools(
              turn,
              Map.of(
                  "a",
                  new ToolCallPhase.SeekingApproval(),
                  "b",
                  new ToolCallPhase.AwaitingApproval(ComputationId.of("approval-1"), request()),
                  "c",
                  new ToolCallPhase.RunningTool(),
                  "d",
                  new ToolCallPhase.AwaitingResult(ComputationId.of("tool-1")),
                  "e",
                  new ToolCallPhase.Completed(new ToolResultBlock("e", "audited", false))),
              RESPONSE_ID);

      var roundTripped = (AgentPhase.AwaitingTools) CODEC.phase(CODEC.toJson(phase));

      assertThat(roundTripped).isEqualTo(phase);
      assertThat(roundTripped.calls()).isNotEmpty();
      assertThat(roundTripped.calls().get("b"))
          .isInstanceOfSatisfying(
              ToolCallPhase.AwaitingApproval.class,
              parked -> assertThat(parked.request().action()).isEqualTo("restart prod-1"));
    }

    @Test
    void a_parked_requests_facts_still_read_back_typed_after_decoding() {
      var turn = turnOf(CALL_B);
      var phase =
          new AgentPhase.AwaitingTools(
              turn,
              Map.of(
                  "b",
                  new ToolCallPhase.AwaitingApproval(ComputationId.of("approval-1"), request())),
              RESPONSE_ID);

      var roundTripped = (AgentPhase.AwaitingTools) CODEC.phase(CODEC.toJson(phase));

      assertThat(roundTripped.calls()).isNotEmpty();
      assertThat(roundTripped.calls().get("b"))
          .isInstanceOfSatisfying(
              ToolCallPhase.AwaitingApproval.class,
              parked -> assertThat(parked.request().facts().get(TICKET)).contains("OPS-42"));
    }

    @Test
    void anAwaitingToolsPhaseWithOnePendingCallRoundTrips() {
      var turn = turnOf(CALL_A);
      var phase =
          new AgentPhase.AwaitingTools(
              turn, Map.of("a", new ToolCallPhase.SeekingApproval()), RESPONSE_ID);
      assertThat(CODEC.phase(CODEC.toJson(phase))).isEqualTo(phase);
    }

    @Test
    void everyToolCallPhaseCarriesItsOwnTypeDiscriminatorOnTheWire() {
      var turn = turnOf(CALL_A, CALL_B, CALL_C, CALL_D, CALL_E);
      var phase =
          new AgentPhase.AwaitingTools(
              turn,
              Map.of(
                  "a",
                  new ToolCallPhase.SeekingApproval(),
                  "b",
                  new ToolCallPhase.AwaitingApproval(ComputationId.of("approval-1"), request()),
                  "c",
                  new ToolCallPhase.RunningTool(),
                  "d",
                  new ToolCallPhase.AwaitingResult(ComputationId.of("tool-1")),
                  "e",
                  new ToolCallPhase.Completed(new ToolResultBlock("e", "audited", false))),
              RESPONSE_ID);

      String json = CODEC.toJson(phase);

      assertThat(json)
          .contains("\"type\":\"awaiting-tools\"")
          .contains("\"type\":\"seeking-approval\"")
          .contains("\"type\":\"awaiting-approval\"")
          .contains("\"type\":\"running-tool\"")
          .contains("\"type\":\"awaiting-result\"")
          .contains("\"type\":\"completed\"")
          // No derived method may reach the wire (deferral-by-callback spec §8). Nothing else
          // here would notice one: the mapper is pinned FAIL_ON_UNKNOWN_PROPERTIES=false, so a
          // phantom property decodes away silently and every round-trip assertion above stays
          // green while Completed writes its result block twice. Measured 2026-08-26: a phase's
          // no-arg result() is invisible to Jackson's record support with or without @JsonIgnore
          // — but renaming it getResult() emits exactly "result" on all seven phases, and this is
          // the assertion that catches that.
          .doesNotContain("\"result\"")
          .doesNotContain("outstanding");
    }

    @Test
    void callsSerializeInSortedIdOrder() {
      var turn = turnOf(CALL_B, CALL_A);
      var phase =
          new AgentPhase.AwaitingTools(
              turn,
              Map.of(
                  "b",
                  new ToolCallPhase.SeekingApproval(),
                  "a",
                  new ToolCallPhase.SeekingApproval()),
              RESPONSE_ID);

      String calls = CODEC.toJson(phase);
      String map = calls.substring(calls.indexOf("\"calls\""));

      assertThat(map.indexOf("\"a\"")).isLessThan(map.indexOf("\"b\""));
    }
  }

  @Nested
  class Discriminator {

    @Test
    void everyPhaseCarriesATypeDiscriminator() {
      assertThat(CODEC.toJson(new AgentPhase.Idle())).contains("\"type\":\"idle\"");
      assertThat(CODEC.toJson(new AgentPhase.AwaitingModel()))
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
      assertThat(CODEC.phase(json)).isEqualTo(new AgentPhase.Idle());
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
    void anAwaitingToolsPayloadWithNoCallsIsRejected() {
      var turn = turnOf(CALL_A);
      var json =
          "{\"type\":\"awaiting-tools\",\"assistantTurn\":"
              + MESSAGE_CODEC.toJson(turn)
              + ",\"calls\":{},\"responseId\":{\"value\":\"response-1\"}}";

      assertThatThrownBy(() -> CODEC.phase(json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAwaitingToolsPayloadNamingACallOutsideTheTurnIsRejectedNamingIt() {
      var turn = turnOf(CALL_A);
      var json =
          "{\"type\":\"awaiting-tools\",\"assistantTurn\":"
              + MESSAGE_CODEC.toJson(turn)
              + ",\"calls\":{\"ghost\":{\"type\":\"seeking-approval\"}},"
              + "\"responseId\":{\"value\":\"response-1\"}}";

      assertThatThrownBy(() -> CODEC.phase(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("ghost");
    }

    @Test
    void anAwaitingToolsPayloadWithAnUnknownToolCallPhaseDiscriminatorIsRejectedNamingIt() {
      var turn = turnOf(CALL_A);
      var json =
          "{\"type\":\"awaiting-tools\",\"assistantTurn\":"
              + MESSAGE_CODEC.toJson(turn)
              + ",\"calls\":{\"a\":{\"type\":\"escalated\"}},"
              + "\"responseId\":{\"value\":\"response-1\"}}";

      assertThatThrownBy(() -> CODEC.phase(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("escalated");
    }

    @Test
    void anAwaitingToolsPayloadWithANonObjectCallsFieldIsRejected() {
      var turn = turnOf(CALL_A);
      var json =
          "{\"type\":\"awaiting-tools\",\"assistantTurn\":"
              + MESSAGE_CODEC.toJson(turn)
              + ",\"calls\":\"oops\",\"responseId\":{\"value\":\"response-1\"}}";

      assertThatThrownBy(() -> CODEC.phase(json)).isInstanceOf(IllegalArgumentException.class);
    }
  }
}
