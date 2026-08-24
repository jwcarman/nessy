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
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolUseBlock;
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
 * Ownership-split absorption (durable-deliveries spec §5a, §6): a staleness redrive must never
 * re-ask a still-pending approval's notifier a second time, and must never re-dispatch a call whose
 * work has already gone durable (an approval that has been granted into a tool computation). Both
 * are proven here by driving {@link DefaultAgent#redispatch()} — the exact mechanism a staleness
 * redrive uses — twice over a scope carrying one call of each kind, with a real {@link
 * ComputationApprover} (so its create-idempotent notifier behavior is exercised for real, not
 * assumed) and a tool that records every invocation.
 */
class AbsorptionTest {

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

  private static final class CountingRequireApprovalPolicy implements UsagePolicy {
    final AtomicInteger evaluations = new AtomicInteger();

    @Override
    public PolicyDecision evaluate(AuthzContext context) {
      evaluations.incrementAndGet();
      return new PolicyDecision.RequireApproval();
    }
  }

  @Test
  void aStalenessRedriveOverAPendingApprovalAndAGrantedInFlightToolAbsorbsBoth() {
    var mapper = TestMappers.plainlyPinned();
    var substrate = new InMemorySubstrate();
    var memory = new VerbatimMemory();
    var store = new SubstrateAgentStateStore(substrate, "test-scope", Clock.systemUTC(), mapper);
    var toolClient = TestToolClients.client("tool", mapper);
    var approvalClient = TestApprovalClients.client("approval", mapper);
    var index = new DispatchIndex(substrate, mapper, "dispatch");
    var notifications = new java.util.ArrayList<ApprovalRequest>();
    var approver = new ComputationApprover(approvalClient, index, store, notifications::add);
    var deferredPolicy = new ComputationDeferredToolCallPolicy(index, toolClient);
    var tool = new RecordingTool();
    var policy = new CountingRequireApprovalPolicy();
    var registry = ToolRegistry.of(ToolGrant.grant(tool, policy));
    var pump = new PumpedExecutor();
    var narrator = new RecordingTurnObserver();

    var c1 = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    var c2 = new ToolCall("c2", "restart", JsonNodeFactory.instance.objectNode());

    // c2 is already "granted and in flight": its tool computation exists BEFORE any redispatch —
    // simulating a grant the grant arm already ran, whose own delivery this test does not need to
    // model, since the gate-level absorption only ever looks for the index entry's presence.
    var c2Address = new CallAddress("test", "test-scope", "r1", "c2");
    var c2Routing = new Routing("test", "test-scope", "r1", c2);
    var c2Computation = toolClient.create(c2Routing);
    index.record(
        c2Address,
        new DispatchEntry(c2Computation.id().value().toString(), DispatchEntry.DispatchKind.TOOL));

    var assistantTurn = Message.assistant(List.of(new ToolUseBlock(c1), new ToolUseBlock(c2)));
    store.save(
        new State(
            new Phase.AwaitingTools(
                assistantTurn, Set.of("c1", "c2"), List.of(), ModelResponseId.of("r1")),
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

    agent.drive();
    pump.pumpUntilQuiet();
    agent.drive();
    pump.pumpUntilQuiet();

    assertThat(notifications).hasSize(1); // c1's first ask only — never a second, never for c2
    assertThat(tool.invocations).hasValue(0); // neither call ever reached the tool
    // F3: the policy itself is not re-evaluated on the redrive that lands while c1's approval is
    // still pending — a non-constant policy that flipped to Allow between the two redrives must
    // not get the chance to double-execute the tool. Evaluated once, for c1's first (fresh) ask;
    // c2 never reaches the policy at all, since its work is already durably in flight.
    assertThat(policy.evaluations).hasValue(1);
  }
}
