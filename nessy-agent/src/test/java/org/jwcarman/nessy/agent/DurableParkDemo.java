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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.durable.ApprovalDesk;
import org.jwcarman.nessy.agent.durable.DurableParkedCallPolicy;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.model.ProviderModelCallExecutor;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.store.InMemoryAgentStateStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.ScriptedModelProvider;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.ComputationStatus;
import org.jwcarman.nessy.durable.ContinuationDispatcher;
import org.jwcarman.nessy.durable.InMemoryDurableComputationBackend;
import org.jwcarman.nessy.spi.model.ModelEvent;

/**
 * The narrated proof (the-slot plan, Task 6): a turn parks on approval, the instance becomes
 * garbage, and the approval builds a FRESH agent to finish the turn — the out-of-band bind of spec
 * §4.3, demonstrated in one lambda.
 */
class DurableParkDemo {

  record ApprovalInput(String action) {}

  static final class RiskyTool implements Tool<ApprovalInput> {
    @Override
    public String name() {
      return "restart_prod";
    }

    @Override
    public String description() {
      return "restarts production; requires human approval";
    }

    @Override
    public Class<ApprovalInput> inputType() {
      return ApprovalInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(ApprovalInput input, ToolContext context) {
      return Awaited.parked(new ParkToken("tok-demo-1"));
    }
  }

  @Test
  void aParkedTurnSurvivesInstanceDeathAndResumesOnAFreshAgent() {
    // the durable world — everything an instance is NOT
    var pump = new PumpedExecutor();
    var memory = new VerbatimMemory();
    var store = new InMemoryAgentStateStore();
    var backend = new InMemoryDurableComputationBackend();
    var dispatcher = new ContinuationDispatcher();
    var desk = new ApprovalDesk(backend, dispatcher);
    var narrator = new RecordingTurnObserver();
    var type = AgentType.of("approver");
    var id = AgentId.of("demo");
    var registry = ToolRegistry.of(new RiskyTool());

    var call =
        new ToolCall(
            "c1", "restart_prod", JsonNodeFactory.instance.objectNode().put("action", "restart"));
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(call, null)),
                List.of(new ModelEvent.TextChunk("Restarted. All good."))));

    Deque<String> queue = new ArrayDeque<>();
    Backlog<String> backlog =
        new Backlog<>() {
          @Override
          public void add(String observation) {
            queue.add(observation);
          }

          @Override
          public Optional<String> poll() {
            return Optional.ofNullable(queue.poll());
          }
        };

    // a FRESH DefaultAgent over the shared world, every time anyone needs one
    Supplier<DefaultAgent<String>> agents =
        () ->
            new DefaultAgent<>(
                new AgentWiring<>(
                    memory,
                    store,
                    backlog,
                    text -> List.of(new TextBlock(text)),
                    new ProviderModelCallExecutor(
                        provider, TestSettings.settings(), registry, memory, narrator, pump),
                    new RegistryToolCallExecutor(
                        registry,
                        id,
                        narrator,
                        pump,
                        new DurableParkedCallPolicy(backend, type, id)),
                    AgentObserver.noop(),
                    false,
                    Duration.ofMinutes(5),
                    Clock.systemUTC()));

    dispatcher.register(
        ScopeResumption.TYPE, new ScopeResumption((t, i, event) -> agents.get().deliver(event)));

    System.out.println("== turn begins ==");
    agents.get().observe("please restart prod");
    pump.pumpUntilQuiet();

    var slot = ComputationId.of("tool:approver:demo:c1");
    System.out.println("phase after park: " + store.load().phase().getClass().getSimpleName());
    assertThat(store.load().phase()).isInstanceOf(Phase.AwaitingTools.class);
    assertThat(backend.status(slot)).contains(ComputationStatus.PENDING);
    // only the observation is committed; the assistant tool-use turn is held back
    assertThat(memory.recall().messages())
        .containsExactly(Message.user(List.of(new TextBlock("please restart prod"))));
    assertThat(narrator.events()).isNotEmpty();
    assertThat(narrator.events()).noneMatch(e -> e instanceof TurnEvent.ToolCallCompleted);

    System.out.println("== the instance is garbage; hours pass; any node may answer ==");
    desk.approve(slot, ToolResult.ok("approved by jcarman"));
    pump.pumpUntilQuiet();

    System.out.println("final phase: " + store.load().phase());
    assertThat(store.load().phase()).isEqualTo(new Phase.Idle());
    assertThat(backend.status(slot)).contains(ComputationStatus.SUCCEEDED);

    List<Message> transcript = memory.recall().messages();
    System.out.println("transcript:");
    transcript.forEach(
        m ->
            System.out.println(
                "  "
                    + m.role()
                    + ": "
                    + m.content().stream().map(b -> b.getClass().getSimpleName()).toList()));
    assertThat(transcript).hasSize(4);
    assertThat(transcript.get(2).content())
        .contains(new ToolResultBlock("c1", "approved by jcarman", false));
    assertThat(transcript.get(3).content()).contains(new TextBlock("Restarted. All good."));
    assertThat(provider.requests()).hasSize(2);
    assertThat(provider.requests().get(1).context().messages()).hasSize(3);
  }
}
