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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.PolicyDecision;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * The approval kind on Continuum (continuum-adoption spec §3, §5, §7): an ask creates one
 * computation and notifies once, a redrive absorbs at the gate rather than re-notifying, and an
 * approval — granted, denied, or expired — reaches the tool (or an in-band failure) through {@link
 * DeliveryWorker#drainApprovals}, never through a second read of the computation.
 *
 * <p>Reuses {@code AbsorptionTest}'s harness fixture shape (a real {@link ComputationApprover} and
 * {@link ComputationDeferredToolCallPolicy}, a counting tool, a counting {@code RequireApproval}
 * policy) — the fixture is rebuilt here rather than shared because {@code AbsorptionTest}'s helper
 * classes are private to that file, and this test's collaborators (a Continuum client, a dispatch
 * index, a worker wired to drain approvals) are new.
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

  private static final class CountingRequireApprovalPolicy implements UsagePolicy {
    final AtomicInteger evaluations = new AtomicInteger();

    @Override
    public PolicyDecision evaluate(AuthzContext context) {
      evaluations.incrementAndGet();
      return new PolicyDecision.RequireApproval();
    }
  }

  private final ObjectMapper mapper = TestMappers.plainlyPinned();
  private final InMemorySubstrate substrate = new InMemorySubstrate();
  private final TestClock clock = new TestClock(Instant.parse("2026-08-24T00:00:00Z"));
  private final Continuum continuum =
      new DefaultContinuum(new InMemoryContinuumRepository(), clock);
  private final ContinuumClient<Decision, Routing> client =
      continuum.client(
          "approval/test",
          Decision.class,
          Routing.class,
          cfg ->
              cfg.resultCodec(DecisionCodec.codec(mapper))
                  .continuationCodec(Routing.codec(mapper))
                  .deadline(Duration.ofDays(7)));
  private final DispatchIndex index = new DispatchIndex(substrate, mapper, "dispatch/test");
  private final List<ApprovalRequest> notifications = new ArrayList<>();
  private final RecordingTool tool = new RecordingTool();
  private final CountingRequireApprovalPolicy policy = new CountingRequireApprovalPolicy();
  private final PumpedExecutor pump = new PumpedExecutor();
  private final RecordingTurnObserver turn = new RecordingTurnObserver();
  private final VerbatimMemory memory = new VerbatimMemory();
  private final SubstrateAgentStateStore store =
      new SubstrateAgentStateStore(substrate, "test-scope", Clock.systemUTC(), mapper);
  private final ContinuumClient<ToolResult, Routing> toolClient =
      TestToolClients.client("tool/test", mapper);
  private final ComputationDeferredToolCallPolicy deferredPolicy =
      new ComputationDeferredToolCallPolicy(index, toolClient);
  private final ComputationApprover approver =
      new ComputationApprover(client, index, store, notifications::add);
  private final RegistryToolCallExecutor executor =
      new RegistryToolCallExecutor(
          ToolRegistry.of(ToolGrant.grant(tool, policy)),
          AgentType.of("test"),
          AgentId.of("test-scope"),
          turn,
          pump,
          deferredPolicy,
          approver,
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
          StalenessPolicy.never());
  private final Agent<String> agent = harness.bind(AgentId.of("test-scope"));
  private final DeliveryWorker<String> worker =
      new DeliveryWorker<>(
          substrate, mapper, harness, (type, id) -> agent, client, index, toolClient);
  private final ApprovalDesk desk = new ApprovalDesk(client, worker::nudge);

  private void drainApprovals() {
    worker.drainApprovals(BatchSize.of(10));
  }

  private CallAddress addressOf(ToolCall call) {
    return new CallAddress("test", "test-scope", "r1", call.id());
  }

  private Routing routingFor(ToolCall call) {
    return new Routing("test", "test-scope", "r1", call);
  }

  /**
   * Seeds the scope's state to {@code AwaitingTools} pending {@code call}, then dispatches once.
   */
  private void driveOnceWithPending(ToolCall call) {
    store.save(
        new State(
            new Phase.AwaitingTools(
                Message.assistant(List.of(new ToolUseBlock(call))),
                Set.of(call.id()),
                List.of(),
                ModelResponseId.of("r1")),
            0));
    ((DefaultAgent<String>) agent).redispatch();
    pump.pumpUntilQuiet();
  }

  private void redrive() {
    ((DefaultAgent<String>) agent).redispatch();
    pump.pumpUntilQuiet();
  }

  private List<ToolResultBlock> foldedResults() {
    return memory.recall().messages().stream()
        .flatMap(m -> m.content().stream())
        .filter(ToolResultBlock.class::isInstance)
        .map(ToolResultBlock.class::cast)
        .toList();
  }

  @Test
  void askingCreatesOneComputationAndNotifiesOnce() {
    var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    var address = new CallAddress("test", "test-scope", "r1", "c1");
    driveOnceWithPending(call);

    assertThat(notifications).hasSize(1);
    assertThat(index.find(address))
        .hasValueSatisfying(
            entry -> assertThat(entry.kind()).isEqualTo(DispatchEntry.DispatchKind.APPROVAL));
    assertThat(tool.invocations).hasValue(0);
  }

  @Test
  void aRedriveWhileTheAskIsPendingDoesNotNotifyAgain() {
    var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    driveOnceWithPending(call);
    redrive();

    assertThat(notifications).hasSize(1);
    // F3: the policy itself must not be re-evaluated on a redrive that lands while the ask is
    // still pending — a non-constant policy that flipped to Allow between the two drives must
    // never get the chance to double-execute (mirrors AbsorptionTest's own F3 assertion).
    assertThat(policy.evaluations).hasValue(1);
    assertThat(tool.invocations).hasValue(0);
  }

  @Test
  void approvingRunsTheToolExactlyOnce() {
    var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    driveOnceWithPending(call);
    ComputationId pending =
        ComputationId.of(index.find(addressOf(call)).orElseThrow().computationId());

    desk.approve(pending);
    drainApprovals();

    assertThat(tool.invocations).hasValue(1);

    // an acknowledged delivery must not come back
    drainApprovals();
    assertThat(tool.invocations).hasValue(1);
  }

  @Test
  void denyingFoldsAFailureWithoutRunningTheTool() {
    var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    driveOnceWithPending(call);
    ComputationId pending =
        ComputationId.of(index.find(addressOf(call)).orElseThrow().computationId());

    desk.deny(pending, "not on a Friday");
    drainApprovals();

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
  void anExpiredApprovalFoldsAFailure() {
    var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    driveOnceWithPending(call);

    clock.advance(Duration.ofDays(8));
    // The behaviour under test — an expired approval folds an in-band failure — is real and this
    // proves it, but its production trigger is not wired yet: nothing in src/main calls
    // failExpiredComputations. The heartbeat only runs the drain paths (drainOnce,
    // safeDrainApprovalsOnce, reapOnce); the expiry pump (ComputationScheduler.expireApprovals) is
    // a later task's scope. Calling it here directly means this test is not yet end-to-end.
    client.failExpiredComputations(BatchSize.of(10));
    drainApprovals();

    assertThat(tool.invocations).hasValue(0);
    assertThat(foldedResults()).isNotEmpty();
    assertThat(foldedResults())
        .singleElement()
        .satisfies(result -> assertThat(result.isError()).isTrue());
  }

  @Test
  void aStaleGrantDoesNotRunTheTool() {
    var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    driveOnceWithPending(call);
    ComputationId real =
        ComputationId.of(index.find(addressOf(call)).orElseThrow().computationId());

    // An orphan: a second approval for the same call, with no index entry naming it — exactly
    // what a crash between create and index.record leaves behind.
    Computation orphan = client.create(routingFor(call));

    desk.approve(real);
    drainApprovals();
    assertThat(tool.invocations).hasValue(1);

    client.complete(orphan.id(), Decision.allow());
    drainApprovals();

    assertThat(tool.invocations).hasValue(1); // the orphan's grant was acknowledged, not run
  }
}
