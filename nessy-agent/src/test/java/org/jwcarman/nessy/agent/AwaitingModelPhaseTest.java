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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

class AwaitingModelPhaseTest {

  private static final ToolCall CALL_A =
      new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
  private static final ToolCall CALL_B =
      new ToolCall("b", "restart", JsonNodeFactory.instance.objectNode());
  private static final ModelResponseId RESPONSE_ID = ModelResponseId.of("response-1");

  @Test
  void aTerminalAnswerCommitsTheAssistantMessageAndGoesIdle() {
    var content = List.<ContentBlock>of(new TextBlock("done"));
    var event =
        new AgentEvent.ModelFinished(new ModelOutcome.Responded(content, List.of(), RESPONSE_ID));
    var t = new Phase.AwaitingModel().handle(event);
    assertThat(t.next()).isEqualTo(new Phase.Idle());
    assertThat(t.commit()).containsExactly(Message.assistant(content));
    assertThat(t.effects()).isEmpty();
  }

  @Test
  void aToolRequestingAnswerHoldsTheTurnBackAndFiresEveryCall() {
    var content =
        List.<ContentBlock>of(new ToolUseBlock(CALL_A, "sig-a"), new ToolUseBlock(CALL_B, "sig-b"));
    var event =
        new AgentEvent.ModelFinished(
            new ModelOutcome.Responded(content, List.of(CALL_A, CALL_B), RESPONSE_ID));
    var t = new Phase.AwaitingModel().handle(event);
    // the held-back unit: NOTHING committed until every result is in (spec §2.5)
    assertThat(t.commit()).isEmpty();
    assertThat(t.next())
        .isEqualTo(
            new Phase.AwaitingTools(
                Message.assistant(content),
                Map.of("a", new CallStatus.Pending(), "b", new CallStatus.Pending()),
                RESPONSE_ID));
    assertThat(t.effects())
        .containsExactly(new Effect.SeekApproval(CALL_A), new Effect.SeekApproval(CALL_B));
  }

  @Test
  void theHeldBackTurnKeepsProviderSignaturesBecauseItIsBuiltFromContentBlocks() {
    var content = List.<ContentBlock>of(new ToolUseBlock(CALL_A, "gemini-thought-sig"));
    var event =
        new AgentEvent.ModelFinished(
            new ModelOutcome.Responded(content, List.of(CALL_A), RESPONSE_ID));
    var t = new Phase.AwaitingModel().handle(event);
    var held = ((Phase.AwaitingTools) t.next()).assistantTurn();
    assertThat(held.content()).containsExactly(new ToolUseBlock(CALL_A, "gemini-thought-sig"));
  }

  @Test
  void aModelFailureGoesIdleAndCommitsNothing() {
    var event = new AgentEvent.ModelFinished(new ModelOutcome.Failed("overloaded"));
    var t = new Phase.AwaitingModel().handle(event);
    assertThat(t.next()).isEqualTo(new Phase.Idle());
    assertThat(t.commit()).isEmpty();
    assertThat(t.effects()).isEmpty();
  }

  @Test
  void aStrayToolCompletionIsIgnored() {
    var event =
        new AgentEvent.ToolFinished(
            CALL_A, Optional.empty(), new ToolOutcome.Returned(ToolResult.ok("x")));
    assertThat(new Phase.AwaitingModel().handle(event).isIgnored()).isTrue();
  }

  @Test
  void aStrayApprovalOrDeferralIsIgnored() {
    var parked = ComputationId.of("parked-1");
    var request =
        ApprovalRequest.draft("ops", "prod-1", CALL_A, Map.of(), new ObjectMapper()).freeze();

    assertThat(
            new Phase.AwaitingModel()
                .handle(new AgentEvent.ApprovalDeferred(CALL_A, parked, request))
                .isIgnored())
        .isTrue();
    assertThat(
            new Phase.AwaitingModel()
                .handle(
                    new AgentEvent.ApprovalAnswered(CALL_A, Optional.empty(), Approval.approved()))
                .isIgnored())
        .isTrue();
    assertThat(
            new Phase.AwaitingModel()
                .handle(new AgentEvent.ToolDeferred(CALL_A, parked))
                .isIgnored())
        .isTrue();
  }

  @Test
  void anObservationReachingThisPhaseIsAProgrammingError() {
    var phase = new Phase.AwaitingModel();
    var event = new AgentEvent.Observed(List.of(new TextBlock("hi")));
    assertThatThrownBy(() -> phase.handle(event)).isInstanceOf(IllegalStateException.class);
  }

  @Nested
  class Purity {

    /**
     * The purity law (durable-deliveries spec §2): {@code Phase.handle} never mints its own id, so
     * a CAS-retry re-handling the same committed {@code ModelFinished} event against fresh state
     * folds to identical state — the carried {@code responseId} included — every time.
     */
    @Test
    void reHandlingTheSameModelFinishedEventTwiceYieldsIdenticalStateIncludingTheResponseId() {
      var content =
          List.<ContentBlock>of(
              new ToolUseBlock(CALL_A, "sig-a"), new ToolUseBlock(CALL_B, "sig-b"));
      var event =
          new AgentEvent.ModelFinished(
              new ModelOutcome.Responded(content, List.of(CALL_A, CALL_B), RESPONSE_ID));

      var first = new Phase.AwaitingModel().handle(event).next();
      var second = new Phase.AwaitingModel().handle(event).next();

      assertThat(first).isEqualTo(second);
      assertThat(((Phase.AwaitingTools) first).responseId())
          .isEqualTo(((Phase.AwaitingTools) second).responseId())
          .isEqualTo(RESPONSE_ID);
    }
  }
}
