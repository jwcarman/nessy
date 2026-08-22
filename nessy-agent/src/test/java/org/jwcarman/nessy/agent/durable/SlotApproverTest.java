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
package org.jwcarman.nessy.agent.durable;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.ScopeRedrive;
import org.jwcarman.nessy.agent.codec.Codecs;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.tool.CallAddress;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.durable.ComputationStatus;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.ContinuationDispatcher;
import org.jwcarman.nessy.spi.approval.Adjudication;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/** The slot-backed adjudicator: the approval slot IS the fact (spec §4.3 amendment). */
class SlotApproverTest {

  private static final CallAddress ADDRESS = new CallAddress("test-agent-type", "a1", "c1");

  private final StoredComputations backend =
      new StoredComputations(new InMemorySubstrate(), TestMappers.plainlyPinned());
  private final List<ApprovalRequest> notified = new ArrayList<>();
  private final ScopeRedrive scopeRedrive =
      new ScopeRedrive((type, id) -> null, TestMappers.plainlyPinned());
  private final SlotApprover approver = new SlotApprover(backend, notified::add, scopeRedrive);

  private ApprovalRequest requestFor(CallAddress address) {
    ObjectNode arguments = JsonNodeFactory.instance.objectNode();
    ToolCall call = new ToolCall(address.callId(), "some-tool", arguments);
    AuthzContext context = AuthzContext.of("test-agent", call);
    return new ApprovalRequest(address, call, context);
  }

  @Test
  void theFirstAskCreatesTheSlotNotifiesOnceAndSuspends() {
    ApprovalRequest request = requestFor(ADDRESS);

    Adjudication adjudication = approver.adjudicate(request);

    assertThat(adjudication).isEqualTo(new Adjudication.Suspended(ADDRESS.approval()));
    assertThat(notified).containsExactly(request);
    assertThat(backend.status(ADDRESS.approval())).contains(ComputationStatus.PENDING);
    List<Continuation> continuations = backend.continuationsOf(ADDRESS.approval());
    assertThat(continuations).hasSize(1);
    assertThat(continuations.get(0).type()).isEqualTo("REDRIVE_SCOPE");
  }

  @Test
  void aRedriveBeforeTheDecisionSuspendsAgainWithoutRenotifying() {
    ApprovalRequest request = requestFor(ADDRESS);

    approver.adjudicate(request);
    Adjudication second = approver.adjudicate(request);

    assertThat(second).isEqualTo(new Adjudication.Suspended(ADDRESS.approval()));
    assertThat(notified).containsExactly(request);
    assertThat(backend.continuationsOf(ADDRESS.approval())).hasSize(1);
  }

  @Test
  void anApprovedSlotGrants() {
    ApprovalRequest request = requestFor(ADDRESS);
    backend.complete(ADDRESS.approval(), DurableDecisions.granted());

    Adjudication adjudication = approver.adjudicate(request);

    assertThat(adjudication).isEqualTo(new Adjudication.Granted());
    assertThat(notified).isEmpty();
  }

  @Test
  void aDeniedSlotRefusesWithTheReason() {
    ApprovalRequest request = requestFor(ADDRESS);
    backend.complete(ADDRESS.approval(), DurableDecisions.denied("not on friday"));

    Adjudication adjudication = approver.adjudicate(request);

    assertThat(adjudication).isEqualTo(new Adjudication.Refused("not on friday"));
  }

  @Test
  void approvalBeforeTheQuestionWasEverAskedGrantsAtFirstAsk() {
    ApprovalRequest request = requestFor(ADDRESS);
    ContinuationDispatcher dispatcher = new ContinuationDispatcher();
    ApprovalDesk desk = new ApprovalDesk(backend, dispatcher);
    desk.approve(ADDRESS.approval());

    Adjudication adjudication = approver.adjudicate(request);

    assertThat(adjudication).isEqualTo(new Adjudication.Granted());
    assertThat(notified).isEmpty();
  }

  /**
   * Fix round 1 (task 3 review): {@code copyAndPin} did not pin serialization inclusion, so a
   * caller mapper configured with {@code NON_EMPTY} dropped the empty {@code continuations} array
   * {@link StoredComputations#create} writes — the very next {@code await} then failed to parse its
   * own just-written document. A full park round trip through a backend built from such a mapper is
   * the regression guard.
   */
  @Test
  void aParkRoundTripSurvivesAUserMapperConfiguredForNonEmptyInclusion() {
    ObjectMapper userMapper =
        new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
    ObjectMapper pinned = Codecs.copyAndPin(userMapper);
    StoredComputations nonEmptyBackend = new StoredComputations(new InMemorySubstrate(), pinned);
    List<ApprovalRequest> nonEmptyNotified = new ArrayList<>();
    ScopeRedrive nonEmptyScopeRedrive = new ScopeRedrive((type, id) -> null, pinned);
    SlotApprover nonEmptyApprover =
        new SlotApprover(nonEmptyBackend, nonEmptyNotified::add, nonEmptyScopeRedrive);
    ApprovalRequest request = requestFor(ADDRESS);

    Adjudication adjudication = nonEmptyApprover.adjudicate(request);

    assertThat(adjudication).isEqualTo(new Adjudication.Suspended(ADDRESS.approval()));

    nonEmptyBackend.complete(ADDRESS.approval(), DurableDecisions.granted());
    Adjudication decided = nonEmptyApprover.adjudicate(request);

    assertThat(decided).isEqualTo(new Adjudication.Granted());
  }
}
