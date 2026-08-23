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
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.spi.approval.Adjudication;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * The computation-backed adjudicator: the delivery pipeline, not a second read of the computation
 * (durable-deliveries spec §3, §5). {@code ApprovalRequest} no longer carries {@code responseId}
 * (identity spec §6, the continuation audit) — every test here first commits the scope's own state
 * to {@link Phase.AwaitingTools}, carrying {@code ADDRESS}'s {@code responseId}, the exact shape
 * the no-new-turn invariant guarantees at ask time, so {@link ComputationApprover} reads it from
 * there.
 */
class ComputationApproverTest {

  private static final CallAddress ADDRESS = new CallAddress("test-agent-type", "a1", "r7", "c1");

  private final SubstrateComputations backend =
      new SubstrateComputations(
          new InMemorySubstrate(), TestMappers.plainlyPinned(), "approval", "outbox");
  private final AgentStateStore state = awaitingToolsStateFor(ADDRESS);
  private final List<ApprovalRequest> notified = new ArrayList<>();
  private final ComputationApprover approver =
      new ComputationApprover(backend, state, notified::add, TestMappers.plainlyPinned());

  /**
   * Commits {@code address.agentId()}'s state to {@link Phase.AwaitingTools}, pending exactly
   * {@code address.callId()}, carrying {@code address.responseId()} — the committed state a real
   * scope is in at the exact moment its gate asks for approval (the no-new-turn invariant {@link
   * ComputationApprover} relies on).
   */
  private static AgentStateStore awaitingToolsStateFor(CallAddress address) {
    var mapper = TestMappers.plainlyPinned();
    var store =
        new SubstrateAgentStateStore(
            new InMemorySubstrate(), address.agentId(), Clock.systemUTC(), mapper);
    ToolCall pendingCall =
        new ToolCall(address.callId(), "some-tool", JsonNodeFactory.instance.objectNode());
    store.save(
        new State(
            new Phase.AwaitingTools(
                Message.assistant(List.of(new ToolUseBlock(pendingCall))),
                Set.of(address.callId()),
                List.of(),
                ModelResponseId.of(address.responseId())),
            0));
    return store;
  }

  private ApprovalRequest requestFor(CallAddress address) {
    ObjectNode arguments = JsonNodeFactory.instance.objectNode();
    ToolCall call = new ToolCall(address.callId(), "some-tool", arguments);
    AuthzContext context = AuthzContext.of("test-agent", call);
    return new ApprovalRequest(
        address.approval(), call, address.agentType(), address.agentId(), context);
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
  void theInvocationCarriesTheCommittedResponseIdReadFromTheScopesOwnStateAtAskTime() {
    ApprovalRequest request = requestFor(ADDRESS);

    approver.adjudicate(request);

    PendingComputation pending = backend.find(ADDRESS.approval()).orElseThrow();
    assertThat(pending.invocation().responseId()).isEqualTo(ADDRESS.responseId());
  }
}
