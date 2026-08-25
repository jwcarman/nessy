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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.Continuum;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.DefaultContinuum;
import org.jwcarman.continuum.api.BatchSize;
import org.jwcarman.continuum.memory.InMemoryContinuumRepository;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.NoToolsExecutor;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestClock;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * The early-delivery rule (approval-lifecycle spec §4): an answer that arrives while its call is
 * still {@code Pending} — the park has not folded yet — is RELEASED, not acknowledged, so Continuum
 * re-delivers it after the backoff rather than losing it. Same for a tool result reaching a call
 * still {@code Running}. Continuum catches the consumer's throw itself and releases the claim, so
 * the observable proof is the re-delivery: the same answer lands again once the fold has committed.
 */
class DeliveryWorkerEarlyDeliveryTest {

  private static final ToolCall CALL =
      new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
  private static final Duration PAST_THE_BACKOFF = Duration.ofSeconds(6);

  private static final class NoopBacklog implements Backlog<String> {
    @Override
    public void add(String observation) {}

    @Override
    public Optional<String> poll() {
      return Optional.empty();
    }
  }

  private final ObjectMapper mapper = TestMappers.plainlyPinned();
  private final InMemorySubstrate substrate = new InMemorySubstrate();
  private final TestClock clock = new TestClock(Instant.parse("2026-08-25T00:00:00Z"));
  private final Continuum continuum =
      new DefaultContinuum(new InMemoryContinuumRepository(), clock);
  private final SubstrateAgentStateStore store =
      new SubstrateAgentStateStore(substrate, "test-scope", Clock.systemUTC(), mapper);
  private final ContinuumClient<Approval, ApprovalRouting> approvalClient =
      continuum.client(
          "approval/test",
          Approval.class,
          ApprovalRouting.class,
          cfg ->
              cfg.resultCodec(ApprovalCodec.codec(mapper))
                  .continuationCodec(ApprovalRouting.codec(mapper))
                  .deadline(Duration.ofDays(7)));
  private final ContinuumClient<ToolResult, Routing> toolClient =
      continuum.client(
          "tool/test",
          ToolResult.class,
          Routing.class,
          cfg ->
              cfg.resultCodec(TestToolClients.toolResultCodec(mapper))
                  .continuationCodec(Routing.codec(mapper))
                  .deadline(Duration.ofHours(1)));
  private final Harness<String> harness =
      TestAgents.<String>harness(
          new VerbatimMemory(),
          store,
          new NoopBacklog(),
          text -> List.of(),
          sink -> {},
          new NoToolsExecutor(),
          AgentObserver.noop(),
          false,
          StalenessPolicy.never());
  private final Agent<String> agent = harness.bind(AgentId.of("test-scope"));
  private final DeliveryWorker<String> worker =
      new DeliveryWorker<>(
          substrate,
          mapper,
          harness,
          (type, id) -> agent,
          new PumpedExecutor(),
          approvalClient,
          toolClient);

  private void scopeWith(CallStatus status) {
    Message turn = Message.assistant(List.of(new ToolUseBlock(CALL)));
    Phase phase = new Phase.AwaitingTools(turn, Map.of("c1", status), ModelResponseId.of("r1"));
    store.save(new State(phase, store.load().version()));
  }

  private CallStatus status() {
    return ((Phase.AwaitingTools) store.load().phase()).calls().get("c1");
  }

  private ApprovalRequest request() {
    return ApprovalRequest.draft("test", "test-scope", CALL, mapper).freeze();
  }

  private ComputationId answerAnApproval() {
    var created =
        approvalClient.create(
            new ApprovalRouting(new Routing("test", "test-scope", "r1", CALL), request()));
    approvalClient.complete(created.id(), Approval.approved());
    return ComputationId.of(created.id().value().toString());
  }

  @Test
  void anAnswerForAStillPendingCallIsReleasedAndRedeliveredOnceTheParkHasFolded() {
    scopeWith(new CallStatus.Pending());
    ComputationId parked = answerAnApproval();

    worker.drainApprovals(BatchSize.of(10)); // early: released, not acknowledged

    assertThat(status()).isInstanceOf(CallStatus.Pending.class);

    // the park folds late, exactly as the racing defer() would have
    scopeWith(new CallStatus.AwaitingApproval(parked, request()));
    clock.advance(PAST_THE_BACKOFF);
    worker.drainApprovals(BatchSize.of(10));

    assertThat(status()).isInstanceOf(CallStatus.Running.class);
  }

  @Test
  void aResultForAStillRunningCallIsReleasedAndRedeliveredOnceTheParkHasFolded() {
    scopeWith(new CallStatus.Running());
    var created = toolClient.create(new Routing("test", "test-scope", "r1", CALL));
    toolClient.complete(created.id(), ToolResult.ok("done"));
    ComputationId parked = ComputationId.of(created.id().value().toString());

    worker.drainTools(BatchSize.of(10)); // early: released, not acknowledged

    assertThat(status()).isInstanceOf(CallStatus.Running.class);

    scopeWith(new CallStatus.AwaitingResult(parked));
    clock.advance(PAST_THE_BACKOFF);
    worker.drainTools(BatchSize.of(10));

    assertThat(store.load().phase()).isInstanceOf(Phase.AwaitingModel.class);
  }

  @Test
  void anAnswerNamingAComputationTheParkedCallDoesNotIsStaleAndSimplyAcknowledged() {
    scopeWith(new CallStatus.AwaitingApproval(ComputationId.of("parked-elsewhere"), request()));
    answerAnApproval(); // an orphan: a different computation entirely

    worker.drainApprovals(BatchSize.of(10));

    assertThat(status()).isInstanceOf(CallStatus.AwaitingApproval.class);

    // acknowledged, not released: nothing comes back after the backoff
    clock.advance(PAST_THE_BACKOFF);
    int redelivered = worker.drainApprovals(BatchSize.of(10));

    assertThat(redelivered).isZero();
  }
}
