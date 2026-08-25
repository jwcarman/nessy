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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.api.Backoff;
import org.jwcarman.continuum.api.BatchSize;
import org.jwcarman.continuum.api.Lease;
import org.jwcarman.nessy.agent.support.TestApprovalClients;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/**
 * {@code defer()} does the plumbing (approval-lifecycle spec §1.3): one computation, carrying the
 * frozen request as its continuation, and the fold delivered through the sink BEFORE the id ever
 * reaches the approver — the ordering that makes an early answer impossible to lose.
 */
class ComputationApprovalContextTest {

  private final ObjectMapper mapper = TestMappers.plainlyPinned();
  private final ContinuumClient<Approval, ApprovalRouting> client =
      TestApprovalClients.client("approval/test", mapper);

  private static final ToolCall CALL =
      new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
  private static final Routing ROUTING = new Routing("ops", "prod-eu", "r1", CALL);

  private ApprovalRequest request() {
    return ApprovalRequest.draft("ops", "prod-eu", CALL, mapper).action("restart prod-eu").freeze();
  }

  @Test
  void deferParksTheQuestionAndFoldsItBeforeHandingBackTheId() {
    var delivered = new ArrayList<AgentEvent>();
    var seenWhenTheIdArrived = new ArrayList<Integer>();
    ApprovalRequest request = request();
    var context = new ComputationApprovalContext(client, ROUTING, request, delivered::add);

    ApprovalOutcome outcome = context.defer();
    seenWhenTheIdArrived.add(delivered.size());

    assertThat(outcome).isInstanceOf(ApprovalOutcome.Deferred.class);
    var id = ((ApprovalOutcome.Deferred) outcome).id();
    // the fold had already happened by the time defer() returned
    assertThat(seenWhenTheIdArrived).containsExactly(1);
    assertThat(delivered).containsExactly(new AgentEvent.ApprovalDeferred(CALL, id, request));
  }

  @Test
  void theParkedComputationCarriesTheFrozenRequestAsItsContinuation() {
    var context = new ComputationApprovalContext(client, ROUTING, request(), event -> {});

    ApprovalOutcome outcome = context.defer();

    var id = ((ApprovalOutcome.Deferred) outcome).id();
    client.complete(ContinuumIds.continuumId(id.value()), Approval.approved());
    List<ApprovalRouting> continuations = new ArrayList<>();
    client.deliverResults(
        BatchSize.of(10),
        Lease.ofSeconds(30),
        Backoff.ofSeconds(5),
        delivery -> continuations.add(delivery.continuation()));

    assertThat(continuations).hasSize(1);
    assertThat(continuations.getFirst().routing()).isEqualTo(ROUTING);
    assertThat(continuations.getFirst().request().action()).isEqualTo("restart prod-eu");
  }

  @Test
  void aSecondDeferReturnsTheSameOutcomeAndParksNothingNew() {
    var delivered = new ArrayList<AgentEvent>();
    var context = new ComputationApprovalContext(client, ROUTING, request(), delivered::add);

    ApprovalOutcome first = context.defer();
    ApprovalOutcome second = context.defer();

    assertThat(second).isSameAs(first);
    assertThat(delivered).hasSize(1);
  }

  @Test
  void requestHandsBackTheFrozenQuestion() {
    ApprovalRequest request = request();
    var context = new ComputationApprovalContext(client, ROUTING, request, event -> {});

    assertThat(context.request()).isSameAs(request);
  }
}
