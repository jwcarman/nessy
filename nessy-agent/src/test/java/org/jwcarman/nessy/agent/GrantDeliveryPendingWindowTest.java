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
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.durable.ComputationApprover;
import org.jwcarman.nessy.agent.durable.ComputationDeferredToolCallPolicy;
import org.jwcarman.nessy.agent.durable.DurableDecisions;
import org.jwcarman.nessy.agent.durable.SubstrateComputations;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolUseBlock;
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
 * The §5a honesty amendment's PARKED gap, pinned as current behavior (durable-deliveries spec §5a,
 * "The grant-delivery-pending window"): between a grant's completion batch (approval computation
 * deleted, delivery created) and a worker draining that delivery, neither the approval nor the tool
 * computation exists for the call's address — presence-means-pending leaves no residue there. A
 * staleness redrive landing in exactly that window therefore does not absorb; it re-asks the
 * approver, because the delivery is keyed randomly and is not derivable from the call's address
 * (spec: "Closing it needs a ruling — a deterministic grant-delivery key or an explicit granted
 * marker — PARKED on the decision list"). This test pins the CURRENT re-ask behavior, not the ideal
 * one: the day that parked ruling lands, the fix should flip {@code notifications} from size 2 back
 * to size 1 here, not require someone to divine what "fixed" looks like.
 *
 * <p>A wrong implementation that already closed the gap (silently absorbing the redrive) would fail
 * this test by asserting {@code notifications.hasSize(2)} against an actual size of 1 — which is
 * exactly the desired failure mode: the assertion breaks loudly and points straight at the spec
 * paragraph to update.
 */
class GrantDeliveryPendingWindowTest {

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
  void aStalenessRedriveLandingAfterAGrantButBeforeItsDeliveryIsDrainedReAsksTheApprover() {
    var mapper = TestMappers.plainlyPinned();
    var substrate = new InMemorySubstrate();
    var memory = new VerbatimMemory();
    var store = new SubstrateAgentStateStore(substrate, "test-scope", Clock.systemUTC(), mapper);
    var backend = new SubstrateComputations(substrate, mapper);
    var notifications = new ArrayList<ApprovalRequest>();
    var approver = new ComputationApprover(backend, notifications::add, mapper);
    var deferredPolicy = new ComputationDeferredToolCallPolicy(backend, mapper);
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
    // grant's outbox delivery. No DeliveryWorker exists in this harness-only fixture, so the
    // delivery is deliberately left undrained: this IS the pending window.
    var address = notifications.getFirst().address();
    backend.complete(address.approval(), DurableDecisions.granted());
    assertThat(backend.find(address.approval())).isEmpty();
    assertThat(backend.find(address.execution())).isEmpty(); // no tool computation either — yet
    assertThat(substrate.keys("outbox", 10)).hasSize(1); // the grant survives only as this

    // Second redrive lands squarely inside the pending window.
    agent.redispatch();
    pump.pumpUntilQuiet();

    // CURRENT documented behavior (spec §5a honesty amendment, parked): the redrive finds neither
    // computation present, treats c1 as a fresh call, and re-asks. Fixing the parked gap should
    // turn this back into hasSize(1).
    assertThat(notifications).hasSize(2);
    assertThat(tool.invocations).hasValue(0); // the tool itself never ran a second time
  }
}
