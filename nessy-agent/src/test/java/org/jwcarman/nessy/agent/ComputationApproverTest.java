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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.computation.PendingComputation;
import org.jwcarman.nessy.api.tool.CallAddress;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.spi.approval.Adjudication;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * The computation-backed adjudicator: the delivery pipeline, not a second read of the computation
 * (durable-deliveries spec §3, §5).
 */
class ComputationApproverTest {

  private static final CallAddress ADDRESS = new CallAddress("test-agent-type", "a1", "r7", "c1");

  private final SubstrateComputations backend =
      new SubstrateComputations(new InMemorySubstrate(), TestMappers.plainlyPinned());
  private final List<ApprovalRequest> notified = new ArrayList<>();
  private final ComputationApprover approver =
      new ComputationApprover(backend, notified::add, TestMappers.plainlyPinned());

  private ApprovalRequest requestFor(CallAddress address) {
    ObjectNode arguments = JsonNodeFactory.instance.objectNode();
    ToolCall call = new ToolCall(address.callId(), "some-tool", arguments);
    AuthzContext context = AuthzContext.of("test-agent", call);
    return new ApprovalRequest(address, call, context);
  }

  @Test
  void theFirstAskCreatesTheComputationNotifiesOnceAndSuspends() {
    ApprovalRequest request = requestFor(ADDRESS);

    Adjudication adjudication = approver.adjudicate(request);

    assertThat(adjudication).isEqualTo(new Adjudication.Suspended(ADDRESS.approval()));
    assertThat(notified).containsExactly(request);
    assertThat(backend.find(ADDRESS.approval())).isPresent();
  }

  @Test
  void aRedriveBeforeTheDecisionSuspendsAgainWithoutRenotifying() {
    ApprovalRequest request = requestFor(ADDRESS);

    approver.adjudicate(request);
    Adjudication second = approver.adjudicate(request);

    assertThat(second).isEqualTo(new Adjudication.Suspended(ADDRESS.approval()));
    assertThat(notified).containsExactly(request);
  }

  @Test
  void theReturnAddressCarriesTheAgentCoordinateAndTheCall() {
    ApprovalRequest request = requestFor(ADDRESS);

    approver.adjudicate(request);

    PendingComputation pending = backend.find(ADDRESS.approval()).orElseThrow();
    assertThat(pending.returnAddress().type()).isEqualTo("SCOPE_RESUME");
    ScopeRouting.Routing routing =
        ScopeRouting.decode(TestMappers.plainlyPinned(), pending.returnAddress());
    assertThat(routing.agentType()).isEqualTo(ADDRESS.agentType());
    assertThat(routing.agentId()).isEqualTo(ADDRESS.agentId());
    assertThat(routing.call()).isEqualTo(request.call());
    assertThat(pending.invocation().callId()).isEqualTo(ADDRESS.callId());
  }

  @Test
  void theInvocationCarriesTheRealCommittedResponseIdFromTheAddress() {
    ApprovalRequest request = requestFor(ADDRESS);

    approver.adjudicate(request);

    PendingComputation pending = backend.find(ADDRESS.approval()).orElseThrow();
    assertThat(pending.invocation().responseId()).isEqualTo(ADDRESS.responseId());
  }
}
