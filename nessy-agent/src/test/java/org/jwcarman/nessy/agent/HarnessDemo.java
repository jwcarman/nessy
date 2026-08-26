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
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.HarnessObserver;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingMemory;
import org.jwcarman.nessy.agent.support.ScriptedModelExecutor;
import org.jwcarman.nessy.agent.support.ScriptedToolExecutor;
import org.jwcarman.nessy.agent.support.TestApprovalClients;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/** Throwaway demo — not part of the suite's contract. Prints a whole turn. */
class HarnessDemo {

  @Test
  void aWholeTurnNarrated() {
    // ---- collaborators: plain construction, any order ----
    var pump = new PumpedExecutor();
    var memory = new RecordingMemory();
    var store =
        new SubstrateAgentStateStore(
            new InMemorySubstrate(), "demo-scope", Clock.systemUTC(), TestMappers.plainlyPinned());
    var model = new ScriptedModelExecutor(pump, memory);
    var tools = new ScriptedToolExecutor(pump);
    Deque<String> queue = new ArrayDeque<>();
    Backlog<String> backlog =
        new Backlog<>() {
          @Override
          public void add(String o) {
            queue.add(o);
          }

          @Override
          public Optional<String> poll() {
            return Optional.ofNullable(queue.poll());
          }
        };
    HarnessObserver narrator =
        new HarnessObserver() {
          @Override
          public void applied(AgentId id, AgentEvent event, Transition t) {
            System.out.printf(
                "  [narrate] %-14s -> %-14s commits=%d effects=%s%n",
                event.getClass().getSimpleName(),
                t.next().getClass().getSimpleName(),
                t.commit().size(),
                t.effects());
          }

          @Override
          public void ignored(AgentId id, AgentEvent event) {
            System.out.println("  [narrate] IGNORED stale " + event.getClass().getSimpleName());
          }

          @Override
          public void renderFailed(AgentId id, Object o, RuntimeException e) {
            // deliberately silent: the demo narrator ignores render failures
          }

          @Override
          public void applyFailed(AgentId id, AgentEvent e, RuntimeException x) {
            // deliberately silent: the demo narrator ignores apply failures
          }

          @Override
          public void reFired(AgentId id, List<Effect> effects) {
            // deliberately silent: the demo narrator ignores re-fires
          }

          @Override
          public void observationRequeued(AgentId id, Object observation) {
            // deliberately silent: the demo narrator ignores requeues
          }
        };

    // ---- the harness: the recipe, id-free — and one bind stamps this scope's handles ----
    Substrate lifeSupportSubstrate = new InMemorySubstrate();
    var lifeSupportMapper = TestMappers.plainlyPinned();
    var demoType = AgentType.of("demo");
    var approvalClient = TestApprovalClients.client(Kinds.approval(demoType), lifeSupportMapper);
    var toolClient = TestToolClients.client(Kinds.tool(demoType), lifeSupportMapper);
    var harness =
        Harness.<String>of(
            demoType,
            "test_provider",
            "test-model",
            text -> List.of(new TextBlock(text)),
            List.of(narrator),
            TurnObserver.noop(),
            false,
            StalenessPolicy.never(),
            rawId -> memory,
            rawId -> store,
            rawId -> backlog,
            (mem, obs) -> model,
            (id, obs) -> tools,
            lifeSupportSubstrate,
            lifeSupportMapper,
            approvalClient,
            toolClient,
            new ConcurrentHashMap<>(),
            ObservationRegistry.NOOP,
            new ConcurrentHashMap<>());
    var agent = new DefaultAgent<>(harness, harness.binding(AgentId.of("demo-scope")));

    // ---- script the world ----
    var lookup =
        new ToolCall("c1", "lookup_order", JsonNodeFactory.instance.objectNode().put("id", 42));
    var refund =
        new ToolCall("c2", "issue_refund", JsonNodeFactory.instance.objectNode().put("amount", 99));
    model.enqueue(
        new ModelOutcome.Responded(
            List.<ContentBlock>of(
                new ToolUseBlock(lookup, "gemini-sig"), new ToolUseBlock(refund, null)),
            List.of(lookup, refund),
            ModelResponseId.of("response-1")));
    model.enqueue(
        new ModelOutcome.Responded(
            List.of(new TextBlock("Order 42 found; refunded $99.")),
            List.of(),
            ModelResponseId.of("response-1")));
    tools.answer("c1", new ToolOutcome.Returned(ToolResult.ok("{\"status\":\"shipped\"}")));
    tools.answer("c2", new ToolOutcome.Returned(ToolResult.ok("refund queued")));

    // ---- one observation, whole turn ----
    System.out.println("tell(\"refund order 42\")");
    agent.tell("refund order 42");
    System.out.println("pump...");
    pump.pumpUntilQuiet();

    // ---- a late duplicate, for flavor ----
    agent.deliver(
        new AgentEvent.ToolFinished(
            lookup, Optional.empty(), new ToolOutcome.Returned(ToolResult.ok("dupe"))));

    System.out.println(
        "\nfinal phase: " + store.load().phase() + "  version: " + store.load().version());
    System.out.println("\nmemory (what the model would recall):");
    memory
        .remembered()
        .forEach(
            m ->
                System.out.println(
                    "  "
                        + m.role()
                        + ": "
                        + m.content().stream().map(b -> b.getClass().getSimpleName()).toList()));

    // ---- the turn actually finished, and the model's recollections were captured ----
    assertThat(store.load().phase()).isInstanceOf(Phase.Idle.class);
    assertThat(memory.remembered()).isNotEmpty();
  }
}
