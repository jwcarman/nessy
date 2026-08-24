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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestApprovalClients;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Decision;
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
 * The §5a honesty amendment's window, now shut a different way (continuum-adoption spec §5): a
 * completed-but-undrained grant leaves the dispatch index's entry exactly as it was — completing a
 * Continuum computation touches only Continuum's own store, never the index — so {@link
 * ComputationDeferredToolCallPolicy#pendingComputation} still finds the same APPROVAL entry it
 * found before the grant, and a staleness redrive landing squarely in this window absorbs instead
 * of re-asking the approver.
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
    var toolClient = TestToolClients.client("tool/test", mapper);
    var approvalClient = TestApprovalClients.client("approval/test", mapper);
    var index = new DispatchIndex(substrate, mapper, "dispatch/test");
    var notifications = new ArrayList<ApprovalRequest>();
    var approver = new ComputationApprover(approvalClient, index, store, notifications::add);
    var deferredPolicy = new ComputationDeferredToolCallPolicy(index, toolClient);
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
            StalenessPolicy.after(Duration.ZERO));

    // First redrive: c1's fresh ask. Exactly one notification, as usual.
    agent.drive();
    pump.pumpUntilQuiet();
    assertThat(notifications).hasSize(1);

    // Grant it directly, through the real approval client — no DeliveryWorker exists in this
    // harness-only fixture, so the grant is deliberately left undrained: this IS the pending
    // window. The dispatch index entry is untouched by completion alone (only a fold deletes or
    // overwrites it), so it still names the same APPROVAL computation this call was asked under.
    var address = new CallAddress("test", "test-scope", "r1", "c1");
    ComputationId approvalId = ComputationId.of(index.find(address).orElseThrow().computationId());
    approvalClient.complete(ContinuumIds.continuumId(approvalId.value()), Decision.allow());
    assertThat(index.find(address))
        .hasValueSatisfying(
            entry -> assertThat(entry.kind()).isEqualTo(DispatchEntry.DispatchKind.APPROVAL));

    // Second redrive lands squarely inside the pending window: the gate's pendingComputation check
    // still finds the (now-granted-but-undrained) APPROVAL entry and absorbs — no second ask, no
    // second tool execution.
    agent.drive();
    pump.pumpUntilQuiet();

    assertThat(notifications).hasSize(1); // the window is shut: no re-ask
    assertThat(tool.invocations).hasValue(0); // the tool itself never ran a second time
    assertThat(index.find(address))
        .hasValueSatisfying(
            entry -> assertThat(entry.kind()).isEqualTo(DispatchEntry.DispatchKind.APPROVAL));
  }
}
