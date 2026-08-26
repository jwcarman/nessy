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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

class IdlePhaseTest {

  @Test
  void anObservationCommitsTheUserMessageAndCallsTheModel() {
    var content = List.<ContentBlock>of(new TextBlock("hi"));
    var t = new AgentPhase.Idle().handle(new AgentEvent.Observed(content));
    assertThat(t.next()).isEqualTo(new AgentPhase.AwaitingModel());
    assertThat(t.commit()).containsExactly(Message.user(content));
    assertThat(t.effects()).containsExactly(new Effect.CallModel());
  }

  @Test
  void aStrayModelCompletionIsIgnored() {
    var event = new AgentEvent.ModelFinished(new ModelOutcome.Failed("late"));
    assertThat(new AgentPhase.Idle().handle(event).isIgnored()).isTrue();
  }

  @Test
  void aStrayToolCompletionIsIgnored() {
    var call = new ToolCall("c1", "lookup", JsonNodeFactory.instance.objectNode());
    var event =
        new AgentEvent.ToolFinished(
            call, Optional.empty(), new ToolOutcome.Returned(ToolResult.ok("x")));
    assertThat(new AgentPhase.Idle().handle(event).isIgnored()).isTrue();
  }

  @Test
  void aStrayApprovalOrDeferralIsIgnored() {
    var call = new ToolCall("c1", "lookup", JsonNodeFactory.instance.objectNode());
    var parked = ComputationId.of("parked-1");
    var request =
        ApprovalRequest.draft("ops", "prod-1", call, Map.of(), new ObjectMapper()).freeze();

    assertThat(
            new AgentPhase.Idle()
                .handle(new AgentEvent.ApprovalDeferred(call, parked, request))
                .isIgnored())
        .isTrue();
    assertThat(
            new AgentPhase.Idle()
                .handle(
                    new AgentEvent.ApprovalAnswered(call, Optional.empty(), Approval.approved()))
                .isIgnored())
        .isTrue();
    assertThat(new AgentPhase.Idle().handle(new AgentEvent.ToolDeferred(call, parked)).isIgnored())
        .isTrue();
  }
}
