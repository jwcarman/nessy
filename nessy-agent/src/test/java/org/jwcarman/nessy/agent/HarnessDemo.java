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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.store.InMemoryAgentStateStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingMemory;
import org.jwcarman.nessy.agent.support.ScriptedModelExecutor;
import org.jwcarman.nessy.agent.support.ScriptedToolExecutor;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

/** Throwaway demo — not part of the suite's contract. Prints a whole turn. */
class HarnessDemo {

  @Test
  void aWholeTurnNarrated() {
    // ---- collaborators: plain construction, any order ----
    var pump = new PumpedExecutor();
    var memory = new RecordingMemory();
    var store = new InMemoryAgentStateStore();
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
    AgentObserver narrator =
        new AgentObserver() {
          @Override
          public void applied(AgentEvent event, Transition t) {
            System.out.printf(
                "  [narrate] %-14s -> %-14s commits=%d effects=%s%n",
                event.getClass().getSimpleName(),
                t.next().getClass().getSimpleName(),
                t.commit().size(),
                t.effects());
          }

          @Override
          public void ignored(AgentEvent event) {
            System.out.println("  [narrate] IGNORED stale " + event.getClass().getSimpleName());
          }

          @Override
          public void renderFailed(Object o, RuntimeException e) {}

          @Override
          public void applyFailed(AgentEvent e, RuntimeException x) {}

          @Override
          public void reFired(List<Effect> effects) {}

          @Override
          public void observationRequeued(Object observation) {}
        };

    // ---- the harness: the recipe, id-free — and one bind stamps this scope's handles ----
    var harness =
        Harness.<String>of(
            AgentType.of("demo"),
            text -> List.of(new TextBlock(text)),
            narrator,
            false,
            StalenessPolicy.never(),
            rawId -> memory,
            rawId -> store,
            rawId -> backlog,
            binding -> model,
            binding -> tools);
    var agent = new DefaultAgent<>(harness, harness.bind(AgentId.of("demo-scope")));

    // ---- script the world ----
    var lookup =
        new ToolCall("c1", "lookup_order", JsonNodeFactory.instance.objectNode().put("id", 42));
    var refund =
        new ToolCall("c2", "issue_refund", JsonNodeFactory.instance.objectNode().put("amount", 99));
    model.enqueue(
        new ModelOutcome.Responded(
            List.<ContentBlock>of(
                new ToolUseBlock(lookup, "gemini-sig"), new ToolUseBlock(refund, null)),
            List.of(lookup, refund)));
    model.enqueue(
        new ModelOutcome.Responded(
            List.of(new TextBlock("Order 42 found; refunded $99.")), List.of()));
    tools.answer("c1", new ToolOutcome.Returned(ToolResult.ok("{\"status\":\"shipped\"}")));
    tools.answer("c2", new ToolOutcome.Returned(ToolResult.ok("refund queued")));

    // ---- one observation, whole turn ----
    System.out.println("observe(\"refund order 42\")");
    agent.observe("refund order 42");
    System.out.println("pump...");
    pump.pumpUntilQuiet();

    // ---- a late duplicate, for flavor ----
    agent.deliver(
        new AgentEvent.ToolFinished(lookup, new ToolOutcome.Returned(ToolResult.ok("dupe"))));

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
  }
}
