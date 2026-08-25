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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.Continuum;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.DefaultContinuum;
import org.jwcarman.continuum.api.BatchSize;
import org.jwcarman.continuum.api.Computation;
import org.jwcarman.continuum.memory.InMemoryContinuumRepository;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestClock;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalContext;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.api.tool.approval.Approver;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * The approval kind on Continuum (approval-lifecycle spec §1.3, §2, §5): a deferral parks one
 * computation and the phase records it, a re-fire leaves an {@code AwaitingApproval} call alone,
 * and an answer — approved, denied, or expired — reaches the scope through {@link
 * DeliveryWorker#drainApprovals}, never through a second read of the computation. An answer for a
 * computation the phase does not name is ignored: the phase is the map.
 */
class ApprovalOnContinuumTest {

  @AfterEach
  void shutdownTrackedHarnesses() {
    HarnessTeardown.shutdownAllTracked();
  }

  record NoInput() {}

  private static final class RecordingTool implements Tool<NoInput> {
    final AtomicInteger invocations = new AtomicInteger();

    @Override
    public String name() {
      return "restart";
    }

    @Override
    public String description() {
      return "gated behind approval";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      invocations.incrementAndGet();
      return Awaited.ready(ToolResult.ok("restarted"));
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

  /** Always parks, and counts how many times it was ever asked. */
  private static final class CountingDeferringApprover implements Approver {
    final AtomicInteger asks = new AtomicInteger();

    @Override
    public ApprovalOutcome approve(ApprovalContext context) {
      asks.incrementAndGet();
      return context.defer();
    }
  }

  private final ObjectMapper mapper = TestMappers.plainlyPinned();
  private final InMemorySubstrate substrate = new InMemorySubstrate();
  private final TestClock clock = new TestClock(Instant.parse("2026-08-24T00:00:00Z"));
  private final Continuum continuum =
      new DefaultContinuum(new InMemoryContinuumRepository(), clock);
  private final ContinuumClient<Approval, ApprovalRouting> client =
      continuum.client(
          "approval/test",
          Approval.class,
          ApprovalRouting.class,
          cfg ->
              cfg.resultCodec(ApprovalCodec.codec(mapper))
                  .continuationCodec(ApprovalRouting.codec(mapper))
                  .deadline(Duration.ofDays(7)));
  private final RecordingTool tool = new RecordingTool();
  private final CountingDeferringApprover approver = new CountingDeferringApprover();
  private final PumpedExecutor pump = new PumpedExecutor();
  private final RecordingTurnObserver turn = new RecordingTurnObserver();
  private final VerbatimMemory memory = new VerbatimMemory();
  private final SubstrateAgentStateStore store =
      new SubstrateAgentStateStore(substrate, "test-scope", Clock.systemUTC(), mapper);
  private final ContinuumClient<ToolResult, Routing> toolClient =
      TestToolClients.client("tool/test", mapper);
  private final ComputationDeferredToolCallPolicy deferredPolicy =
      new ComputationDeferredToolCallPolicy(toolClient);
  private final RegistryToolCallExecutor executor =
      new RegistryToolCallExecutor(
          ToolRegistry.of(ToolGrant.grant(tool, approver)),
          AgentType.of("test"),
          AgentId.of("test-scope"),
          turn,
          pump,
          deferredPolicy,
          (call, responseId, request, sink) ->
              new ComputationApprovalContext(
                  client,
                  new Routing("test", "test-scope", responseId.value(), call),
                  request,
                  sink),
          mapper);
  private final Harness<String> harness =
      TestAgents.<String>harness(
          memory,
          store,
          new NoopBacklog(),
          text -> List.of(),
          sink -> {},
          executor,
          AgentObserver.noop(),
          false,
          StalenessPolicy.after(Duration.ZERO));
  private final Agent<String> agent = harness.bind(AgentId.of("test-scope"));

  /**
   * A {@link PumpedExecutor}, never pumped in this file: every {@code desk.approve}/{@code
   * desk.deny} call below is immediately followed by an explicit, synchronous {@link
   * #drainApprovals()} — {@link DeliveryWorker#nudge()}'s own submitted approval drain would just
   * be redundant, so it is left queued and unpumped rather than raced against the explicit call.
   */
  private final PumpedExecutor nudgePump = new PumpedExecutor();

  private final DeliveryWorker<String> worker =
      new DeliveryWorker<>(
          substrate, mapper, harness, (type, id) -> agent, nudgePump, client, toolClient);
  private final ApprovalDesk desk = new ApprovalDesk(client, id -> store, worker::nudge);

  private void drainApprovals() {
    worker.drainApprovals(BatchSize.of(10));
  }

  private Routing routingFor(ToolCall call) {
    return new Routing("test", "test-scope", "r1", call);
  }

  private ApprovalRequest requestFor(ToolCall call) {
    return ApprovalRequest.draft("test", "test-scope", call, mapper).freeze();
  }

  /**
   * Seeds the scope's state to {@code AwaitingTools} with {@code call} Pending, then dispatches.
   */
  private void driveOnceWithPending(ToolCall call) {
    store.save(
        new State(
            new Phase.AwaitingTools(
                Message.assistant(List.of(new ToolUseBlock(call))),
                Map.of(call.id(), new CallStatus.Pending()),
                ModelResponseId.of("r1")),
            0));
    agent.drive();
    pump.pumpUntilQuiet();
  }

  private void redrive() {
    agent.drive();
    pump.pumpUntilQuiet();
  }

  /** The computation the phase says this call is awaiting approval of. */
  private ComputationId parkedIdFor(ToolCall call) {
    Phase phase = store.load().phase();
    CallStatus status = ((Phase.AwaitingTools) phase).calls().get(call.id());
    return ((CallStatus.AwaitingApproval) status).approval();
  }

  private List<ToolResultBlock> foldedResults() {
    return memory.recall().messages().stream()
        .flatMap(m -> m.content().stream())
        .filter(ToolResultBlock.class::isInstance)
        .map(ToolResultBlock.class::cast)
        .toList();
  }

  @Test
  void askingParksOneComputationAndThePhaseNamesIt() {
    var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());

    driveOnceWithPending(call);

    assertThat(approver.asks).hasValue(1);
    assertThat(parkedIdFor(call).value()).isNotBlank();
    assertThat(tool.invocations).hasValue(0);
  }

  @Test
  void aRefireWhileTheAskIsParkedDoesNotAskAgain() {
    var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    driveOnceWithPending(call);

    redrive();

    // AwaitingApproval emits no effect (spec §3): Continuum holds the ask and will deliver.
    assertThat(approver.asks).hasValue(1);
    assertThat(tool.invocations).hasValue(0);
  }

  @Test
  void approvingRunsTheToolExactlyOnce() {
    var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    driveOnceWithPending(call);
    ComputationId parked = parkedIdFor(call);

    desk.approve(parked, "ada", "");
    drainApprovals();
    pump.pumpUntilQuiet();

    assertThat(tool.invocations).hasValue(1);

    // an acknowledged delivery must not come back
    drainApprovals();
    pump.pumpUntilQuiet();
    assertThat(tool.invocations).hasValue(1);
  }

  @Test
  void denyingFoldsAFailureWithoutRunningTheTool() {
    var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    driveOnceWithPending(call);
    ComputationId parked = parkedIdFor(call);

    desk.deny(parked, "ada", "not on a Friday");
    drainApprovals();
    pump.pumpUntilQuiet();

    assertThat(tool.invocations).hasValue(0);
    assertThat(foldedResults()).isNotEmpty();
    assertThat(foldedResults())
        .singleElement()
        .satisfies(
            result -> {
              assertThat(result.isError()).isTrue();
              assertThat(result.content()).contains("not on a Friday");
            });
  }

  @Test
  void withdrawingFoldsAsADenialTheModelReads() {
    var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    driveOnceWithPending(call);
    ComputationId parked = parkedIdFor(call);

    desk.withdraw(parked, "the incident closed itself");
    drainApprovals();
    pump.pumpUntilQuiet();

    assertThat(tool.invocations).hasValue(0);
    assertThat(foldedResults()).isNotEmpty();
    assertThat(foldedResults())
        .singleElement()
        .satisfies(
            result -> {
              assertThat(result.isError()).isTrue();
              assertThat(result.content()).contains("withdrawn");
            });
  }

  @Test
  void anExpiredApprovalFoldsAFailure() {
    var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    driveOnceWithPending(call);

    clock.advance(Duration.ofDays(8));
    // Its production trigger IS wired (continuum-adoption spec §7): DeliveryWorker.expireApprovals
    // delegates straight to failExpiredComputations, and ComputationScheduler.register schedules it
    // as one of the worker's six pumps. Calling failExpiredComputations directly here isolates the
    // behaviour under test from the scheduler's own fixed-delay timing.
    client.failExpiredComputations(BatchSize.of(10));
    drainApprovals();
    pump.pumpUntilQuiet();

    assertThat(tool.invocations).hasValue(0);
    assertThat(foldedResults()).isNotEmpty();
    assertThat(foldedResults())
        .singleElement()
        .satisfies(result -> assertThat(result.isError()).isTrue());
  }

  @Test
  void anAnswerForAComputationThePhaseDoesNotNameIsIgnored() {
    var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    driveOnceWithPending(call);
    ComputationId real = parkedIdFor(call);

    // An orphan: a second, still-live approval for the same call — exactly what a crash inside
    // defer(), after create and before the fold committed, leaves behind (spec §6). Only the
    // answer whose id the phase names is honoured.
    Computation orphan = client.create(new ApprovalRouting(routingFor(call), requestFor(call)));
    assertThat(orphan.id().value().toString()).isNotEqualTo(real.value());

    client.complete(orphan.id(), Approval.approved());
    drainApprovals();
    pump.pumpUntilQuiet();

    assertThat(tool.invocations).hasValue(0); // the orphan's answer was acknowledged, not folded
    assertThat(foldedResults()).isEmpty();

    desk.approve(real, "ada", "");
    drainApprovals();
    pump.pumpUntilQuiet();

    assertThat(tool.invocations).hasValue(1); // the real answer, and only it, ran the tool
  }

  @Test
  void anOrphanedApprovalsExpiryDoesNotFoldAFailureOverTheLiveCall() {
    var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    driveOnceWithPending(call);
    ComputationId real = parkedIdFor(call);

    // An orphan with its own short deadline — the real approval's own 7-day deadline is untouched,
    // so advancing past the orphan's deadline expires only the orphan.
    Computation orphan =
        client.create(new ApprovalRouting(routingFor(call), requestFor(call)), Duration.ofHours(1));
    assertThat(orphan.id().value().toString()).isNotEqualTo(real.value());

    clock.advance(Duration.ofHours(2));
    client.failExpiredComputations(BatchSize.of(10));
    drainApprovals();
    pump.pumpUntilQuiet();

    assertThat(foldedResults()).isEmpty();
    assertThat(tool.invocations).hasValue(0);

    desk.approve(real, "ada", "");
    drainApprovals();
    pump.pumpUntilQuiet();

    assertThat(tool.invocations).hasValue(1); // the human's real approval still lands
  }
}
