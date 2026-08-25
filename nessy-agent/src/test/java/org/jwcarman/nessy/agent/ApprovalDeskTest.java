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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.TestApprovalClients;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The two doors of the desk (approval-lifecycle spec §1.6): by the computation's own id, and by
 * coordinates resolved through the scope's phase — which is the map.
 */
class ApprovalDeskTest {

  private static final ToolCall CALL =
      new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
  private static final AgentId SCOPE = AgentId.of("prod-eu");

  private final ObjectMapper mapper = TestMappers.plainlyPinned();
  private final ContinuumClient<Approval, ApprovalRouting> client =
      TestApprovalClients.client("approval", mapper);
  private final Substrate substrate = new InMemorySubstrate();
  private int nudges;
  private final ApprovalDesk desk = new ApprovalDesk(client, this::storeFor, () -> nudges++);

  private AgentStateStore storeFor(String id) {
    return new SubstrateAgentStateStore(substrate, id, Clock.systemUTC(), mapper);
  }

  private static Routing routing() {
    return new Routing("t", SCOPE.value(), "response-1", CALL);
  }

  private ApprovalRequest request() {
    return ApprovalRequest.draft("t", SCOPE.value(), CALL, mapper)
        .action("restart prod-eu")
        .freeze();
  }

  private ComputationId park() {
    var created = client.create(new ApprovalRouting(routing(), request()));
    return ComputationId.of(created.id().value().toString());
  }

  /** Puts the scope in {@code AwaitingApproval(id)} for {@code c1}, the way the fold would. */
  private void scopeAwaits(ComputationId id) {
    Message turn = Message.assistant(List.<ContentBlock>of(new ToolUseBlock(CALL, null)));
    Phase phase =
        new Phase.AwaitingTools(
            turn,
            Map.of("c1", new CallStatus.AwaitingApproval(id, request())),
            ModelResponseId.of("response-1"));
    AgentStateStore store = storeFor(SCOPE.value());
    store.save(new State(phase, store.load().version()));
  }

  @Test
  void approvingByIdNudgesTheWorker() {
    ComputationId id = park();

    desk.approve(id, "ada", "");

    assertThat(nudges).isEqualTo(1);
  }

  @Test
  void denyingByIdNudgesTheWorker() {
    ComputationId id = park();

    desk.deny(id, "ada", "not on a Friday");

    assertThat(nudges).isEqualTo(1);
  }

  @Test
  void anAnonymousAnswerIsRefused() {
    ComputationId id = park();

    assertThatThrownBy(() -> desk.approve(id, "  ", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("anonymous");
  }

  @Test
  void denyingWithANullReasonIsRejected() {
    ComputationId id = park();

    assertThatThrownBy(() -> desk.deny(id, "ada", null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void denyingWithABlankReasonIsRejected() {
    ComputationId id = park();

    assertThatThrownBy(() -> desk.deny(id, "ada", "   "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void withdrawingFoldsAsADenialAndNudges() {
    ComputationId id = park();

    desk.withdraw(id, "the incident closed itself");

    assertThat(nudges).isEqualTo(1);
  }

  @Test
  void theByCoordinatesDoorReachesTheSameFold() {
    ComputationId id = park();
    scopeAwaits(id);

    desk.approve(SCOPE, "c1", "ada", "looks fine");

    assertThat(nudges).isEqualTo(1);
  }

  @Test
  void theByCoordinatesDenialReachesTheSameFold() {
    ComputationId id = park();
    scopeAwaits(id);

    desk.deny(SCOPE, "c1", "ada", "not on a Friday");

    assertThat(nudges).isEqualTo(1);
  }

  @Test
  void theByCoordinatesDoorShowsTheParkedQuestion() {
    ComputationId id = park();
    scopeAwaits(id);

    assertThat(desk.request(SCOPE, "c1").action()).isEqualTo("restart prod-eu");
  }

  @Test
  void aCallThatIsNotAwaitingApprovalIsRefusedLoudly() {
    assertThatThrownBy(() -> desk.approve(SCOPE, "c1", "ada", ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is not awaiting approval");
  }

  @Test
  void decidingAGenuinelyAbsentIdIsBenignAndStillNudges() {
    var ghost = ComputationId.of(UUID.randomUUID().toString());

    desk.approve(ghost, "ada", "");

    assertThat(nudges).isEqualTo(1);
  }

  @Test
  void aSecondDecisionOnAnAlreadyResolvedIdIsBenignNotAThrow() {
    ComputationId id = park();
    desk.approve(id, "ada", "");

    assertThatCode(() -> desk.deny(id, "ada", "too late")).doesNotThrowAnyException();
  }
}
