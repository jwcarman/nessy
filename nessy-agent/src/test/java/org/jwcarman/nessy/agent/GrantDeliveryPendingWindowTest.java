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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * The §5a honesty amendment's window, now shut (computation-identity spec §4): between a grant's
 * completion batch (approval computation deleted, delivery created under the completed
 * computation's OWN deterministic key) and a worker draining that delivery, neither the approval
 * nor the tool computation exists for the call's address — presence-means-pending leaves no residue
 * there — but the delivery itself now sits at a key the gate CAN derive: {@code
 * ComputationId.of(address.indexKey())}, the same id the grant just completed. {@link
 * ComputationDeferredToolCallPolicy#pendingComputation} checks that key too now (via {@link
 * SubstrateComputations#deliveryPending}), so a staleness redrive landing squarely in the old
 * "pending window" absorbs instead of re-asking the approver — this test proves that shut window,
 * where the sibling {@code GrantDeliveryPendingWindowTest} used to pin the open one.
 */
class GrantDeliveryPendingWindowTest {

  /**
   * Fix round 1, item 5: reclaims every harness this test class built (directly or via {@link
   * org.jwcarman.nessy.agent.support.TestAgents} / {@code AgentFixture}) — each now owns a live
   * delivery-worker heartbeat (harness-first spec §4) that nothing else stops.
   */
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

  @Test
  void aStalenessRedriveLandingAfterAGrantButBeforeItsDeliveryIsDrainedAbsorbsRatherThanReasking() {
    var mapper = TestMappers.plainlyPinned();
    var substrate = new InMemorySubstrate();
    var memory = new VerbatimMemory();
    var store = new SubstrateAgentStateStore(substrate, "test-scope", Clock.systemUTC(), mapper);
    var approvalBackend = new SubstrateComputations(substrate, mapper, "approval", "outbox");
    var executionBackend = new SubstrateComputations(substrate, mapper, "computation", "outbox");
    var notifications = new ArrayList<ApprovalRequest>();
    var approver = new ComputationApprover(approvalBackend, store, notifications::add, mapper);
    var deferredPolicy =
        new ComputationDeferredToolCallPolicy(approvalBackend, executionBackend, mapper);
    var tool = new RecordingTool();
    var registry = ToolRegistry.of(ToolGrant.grant(tool, UsagePolicy.requireApproval()));
    var pump = new PumpedExecutor();
    var narrator = new RecordingTurnObserver();

    var c1 = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    var assistantTurn = Message.assistant(List.of(new ToolUseBlock(c1)));
    store.save(
        new State(
            new Phase.AwaitingTools(
                assistantTurn, Set.of("c1"), List.of(), ModelResponseId.of("r1")),
            0));

    var executor =
        new RegistryToolCallExecutor(
            registry,
            AgentType.of("test"),
            AgentId.of("test-scope"),
            narrator,
            pump,
            deferredPolicy,
            approver,
            mapper);
    var agent =
        TestAgents.<String>wired(
            memory,
            store,
            new NoopBacklog(),
            text -> List.of(),
            sink -> {},
            executor,
            AgentObserver.noop(),
            false,
            StalenessPolicy.never());

    // First redrive: c1's fresh ask. Exactly one notification, as usual.
    agent.redispatch();
    pump.pumpUntilQuiet();
    assertThat(notifications).hasSize(1);

    // Grant it directly — the transfer batch deletes the approval computation and creates the
    // grant's outbox delivery UNDER THE APPROVAL COMPUTATION'S OWN ID (spec §4). No DeliveryWorker
    // exists in this harness-only fixture, so the delivery is deliberately left undrained: this IS
    // the pending window.
    var address = new CallAddress("test", "test-scope", "r1", "c1");
    approvalBackend.complete(
        ComputationId.of(address.indexKey()), DurableDecisions.granted(mapper));
    assertThat(approvalBackend.find(ComputationId.of(address.indexKey()))).isEmpty();
    assertThat(executionBackend.find(ComputationId.of(address.indexKey())))
        .isEmpty(); // no tool computation yet
    assertThat(substrate.keys("outbox", 10))
        .containsExactly(ComputationId.of(address.indexKey()).value());

    // Second redrive lands squarely inside the pending window: the gate's pendingComputation check
    // now finds the undrained delivery at ComputationId.of(address.indexKey())'s own key and
    // absorbs — no second ask,
    // no second tool execution.
    agent.redispatch();
    pump.pumpUntilQuiet();

    assertThat(notifications).hasSize(1); // the window is shut: no re-ask
    assertThat(tool.invocations).hasValue(0); // the tool itself never ran a second time
    assertThat(substrate.keys("outbox", 10))
        .containsExactly(ComputationId.of(address.indexKey()).value());
  }

  @Test
  void aSecondCompletionAfterTheTransferIsAlreadyDoneAndLeavesOneDelivery() {
    var mapper = TestMappers.plainlyPinned();
    var substrate = new InMemorySubstrate();
    var approvalBackend = new SubstrateComputations(substrate, mapper, "approval", "outbox");
    var address = new CallAddress("test", "test-scope", "r1", "c1");
    approvalBackend.create(
        ComputationId.of(address.indexKey()),
        new ToolInvocationId("r1", "c1"),
        ScopeRouting.continuationFor(
            mapper,
            "test",
            "test-scope",
            "r1",
            new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode())),
        Optional.empty());

    CompletionResult first =
        approvalBackend.complete(
            ComputationId.of(address.indexKey()), DurableDecisions.granted(mapper));
    assertThat(substrate.keys("outbox", 10)).hasSize(1);

    // a second completion after the transfer: the computation is already gone (deleted by the
    // first), so this is the ordinary ALREADY_DONE path — still exactly one delivery, never a
    // duplicate. This does NOT exercise the deterministic-key convergence rail (see the sibling
    // test below for that): the computation being absent takes the ALREADY_DONE arm even though a
    // delivery is present — the converge branch is never entered.
    CompletionResult second =
        approvalBackend.complete(
            ComputationId.of(address.indexKey()), DurableDecisions.granted(mapper));

    assertThat(first).isEqualTo(CompletionResult.TRANSFERRED);
    assertThat(second).isEqualTo(CompletionResult.ALREADY_DONE);
    assertThat(substrate.keys("outbox", 10)).hasSize(1);
  }

  /**
   * The deterministic-key convergence rail itself (computation-identity spec §4): the computation
   * is STILL PRESENT (unlike the sibling test above, where it's already gone) — simulating a
   * redrive that re-created the computation after an earlier grant already transferred it, or a
   * genuinely concurrent completer landing after another has already written the delivery but
   * before this one's own read-then-batch. {@code complete()} must find the delivery already
   * sitting at the deterministic key and converge to {@code TRANSFERRED} without attempting (and
   * failing) a write to that same key — proving the {@code deliveryPending} pre-batch check, not
   * merely the ordinary absent-computation path. A revert of that check would instead spin the
   * retry loop forever (the delivery write can never succeed once occupied) or throw, either way
   * failing this test.
   */
  @Test
  @Timeout(10)
  void aDeliveryAlreadyPresentAtTheDeterministicKeyConvergesWithTheComputationStillPresent() {
    var mapper = TestMappers.plainlyPinned();
    var substrate = new InMemorySubstrate();
    var approvalBackend = new SubstrateComputations(substrate, mapper, "approval", "outbox");
    var address = new CallAddress("test", "test-scope", "r1", "c1");
    var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    var continuation = ScopeRouting.continuationFor(mapper, "test", "test-scope", "r1", call);
    approvalBackend.create(
        ComputationId.of(address.indexKey()),
        new ToolInvocationId("r1", "c1"),
        continuation,
        Optional.empty());

    // the delivery already sits at the computation's own deterministic key — as if an earlier
    // completion attempt had already transferred it, and the computation was somehow recreated
    // (or a concurrent completer is about to lose this exact race) — written directly, bypassing
    // complete(), so the computation document is left in place for this test's own call to find.
    var codec = new OutcomeCodec(mapper);
    var deliveryPayload =
        codec.toJson(
            new OutcomeCodec.DeliveryDocument(continuation, DurableDecisions.granted(mapper)));
    substrate.write(
        "outbox",
        ComputationId.of(address.indexKey()).value(),
        deliveryPayload.getBytes(StandardCharsets.UTF_8),
        0);

    CompletionResult result =
        approvalBackend.complete(
            ComputationId.of(address.indexKey()), DurableDecisions.granted(mapper));

    assertThat(result).isEqualTo(CompletionResult.TRANSFERRED);
    assertThat(substrate.keys("outbox", 10))
        .containsExactly(ComputationId.of(address.indexKey()).value());
    assertThat(approvalBackend.find(ComputationId.of(address.indexKey()))).isEmpty();
  }
}
