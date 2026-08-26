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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.api.Backoff;
import org.jwcarman.continuum.api.BatchSize;
import org.jwcarman.continuum.api.Lease;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.NoToolsExecutor;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestApprovalClients;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolUseBlock;
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

  @AfterEach
  void shutdownTrackedHarnesses() {
    HarnessTeardown.shutdownAllTracked();
  }

  private final ObjectMapper mapper = TestMappers.plainlyPinned();
  private final ContinuumClient<Approval, ApprovalRouting> client =
      TestApprovalClients.client("approval/test", mapper);

  private static final ToolCall CALL =
      new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
  private static final Routing ROUTING = new Routing("ops", "prod-eu", "r1", CALL);

  private ApprovalRequest request() {
    return ApprovalRequest.draft("ops", "prod-eu", CALL, Map.of(), mapper)
        .action("restart prod-eu")
        .freeze();
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

  /**
   * The §3 hole, closed: the sink here is the REAL {@code DefaultAgent::deliver} over a store that
   * refuses every save, which is how a park fails in production. {@code deliver} narrates {@code
   * applyFailed} and then rethrows (tool-context-defer spec §3), so the approver is never told
   * "parked" about a question the scope does not name — it sees the exception, the executor turns
   * it into a denial, and the orphan computation expires into a dropped mismatch.
   */
  @Test
  void deferPropagatesWhenTheFoldCannotCommitAndParksNothingInTheScope() {
    var narrated = new ArrayList<AgentEvent>();
    DefaultAgent<String> agent = agentThatCannotSave(narrated);
    var context = new ComputationApprovalContext(client, ROUTING, request(), agent::deliver);

    assertThatThrownBy(context::defer)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(SAVE_REFUSED);

    assertThat(narrated).hasSize(1); // applyFailed, exactly once
    assertThat(narrated.getFirst()).isInstanceOf(AgentEvent.ApprovalDeferred.class);
    // nothing committed, so the call is still exactly where the fold found it
    Phase phase = new UnsavableStore().load().phase();
    assertThat(((Phase.AwaitingTools) phase).calls().get(CALL.id()))
        .isInstanceOf(CallStatus.Pending.class);
  }

  private static final String SAVE_REFUSED = "the substrate is down";

  private static State seeded() {
    return new State(
        new Phase.AwaitingTools(
            Message.assistant(List.of(new ToolUseBlock(CALL))),
            Map.of(CALL.id(), new CallStatus.Pending()),
            ModelResponseId.of("r1")),
        0);
  }

  /** Loads a scope whose call is Pending and refuses every save — no fold can ever commit. */
  private record UnsavableStore() implements AgentStateStore {
    @Override
    public State load() {
      return seeded();
    }

    @Override
    public void save(State state) {
      throw new IllegalStateException(SAVE_REFUSED);
    }

    @Override
    public Instant lastSaved() {
      return Instant.EPOCH;
    }
  }

  private static final class NoopBacklog implements Backlog<String> {
    @Override
    public void add(String observation) {}

    @Override
    public Optional<String> poll() {
      return Optional.empty();
    }
  }

  /** Records only {@code applyFailed}; every other callback is a silent no-op. */
  private record FailureRecorder(List<AgentEvent> narrated) implements AgentObserver {
    @Override
    public void applied(AgentEvent event, Transition transition) {
      // silent: only applyFailed is recorded
    }

    @Override
    public void ignored(AgentEvent event) {
      // silent: only applyFailed is recorded
    }

    @Override
    public void renderFailed(Object observation, RuntimeException error) {
      // silent: only applyFailed is recorded
    }

    @Override
    public void applyFailed(AgentEvent event, RuntimeException error) {
      narrated.add(event);
    }

    @Override
    public void reFired(List<Effect> effects) {
      // silent: only applyFailed is recorded
    }

    @Override
    public void observationRequeued(Object observation) {
      // silent: only applyFailed is recorded
    }
  }

  private DefaultAgent<String> agentThatCannotSave(List<AgentEvent> narrated) {
    return TestAgents.wired(
        new VerbatimMemory(),
        new UnsavableStore(),
        new NoopBacklog(),
        text -> List.of(),
        sink -> {},
        new NoToolsExecutor(),
        new FailureRecorder(narrated),
        false,
        StalenessPolicy.never());
  }
}
