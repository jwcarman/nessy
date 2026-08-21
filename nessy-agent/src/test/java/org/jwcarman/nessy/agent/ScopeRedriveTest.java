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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.durable.DurableDecisions;
import org.jwcarman.nessy.agent.durable.SlotDeferredToolCallPolicy;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.model.ProviderModelCallExecutor;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.InMemoryAgentStateStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.ScriptedModelProvider;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.CallAddress;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.InMemoryDurableComputationBackend;
import org.jwcarman.nessy.spi.model.ModelEvent;

/** The redrive door (spec §4.3 amendment): a poke that re-fires a scope's outstanding effects. */
class ScopeRedriveTest {

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
      return Awaited.deferred();
    }
  }

  private static final class CountingToolCallExecutor implements ToolCallExecutor {
    private final ToolCallExecutor delegate;
    private int invocations;

    CountingToolCallExecutor(ToolCallExecutor delegate) {
      this.delegate = delegate;
    }

    @Override
    public void executeTool(ToolCall call, Sink sink) {
      invocations++;
      delegate.executeTool(call, sink);
    }
  }

  @Test
  void aFiredRedriveResolvesTheScopeAndRedispatchesItsOutstandingEffects() {
    var pump = new PumpedExecutor();
    var memory = new VerbatimMemory();
    var store = new InMemoryAgentStateStore();
    var backend = new InMemoryDurableComputationBackend();
    var narrator = new RecordingTurnObserver();
    var type = AgentType.of("approver");
    var id = AgentId.of("demo");
    var registry = ToolRegistry.of(new RiskyTool());

    var call =
        new ToolCall(
            "c1", "restart_prod", JsonNodeFactory.instance.objectNode().put("action", "restart"));
    var provider =
        new ScriptedModelProvider(List.of(List.of(new ModelEvent.ToolUseEmitted(call, null))));

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

    var counting =
        new CountingToolCallExecutor(
            new RegistryToolCallExecutor(
                registry, type, id, narrator, pump, new SlotDeferredToolCallPolicy(backend)));

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
                    counting,
                    AgentObserver.noop(),
                    false,
                    Duration.ofMinutes(5),
                    Clock.systemUTC()));

    List<String> seen = new ArrayList<>();
    AgentResolver resolver =
        (t, i) -> {
          seen.add(t.name() + "/" + i.value());
          return agents.get();
        };

    agents.get().observe("please restart prod");
    pump.pumpUntilQuiet();

    assertThat(store.load().phase()).isInstanceOf(Phase.AwaitingTools.class);
    assertThat(counting.invocations).isEqualTo(1);

    var address = new CallAddress(type.name(), id.value(), call.id());
    var continuation = ScopeRedrive.continuationFor(address);
    var redrive = new ScopeRedrive(resolver);

    redrive.completed(continuation, DurableDecisions.granted());
    pump.pumpUntilQuiet();

    assertThat(counting.invocations).isEqualTo(2);
    assertThat(store.load().phase()).isInstanceOf(Phase.AwaitingTools.class);
    assertThat(seen).containsExactly("approver/demo");
  }

  @Test
  void anUndecodableRedriveContinuationFailsLoudly() {
    AgentResolver resolver = (t, i) -> null;
    var redrive = new ScopeRedrive(resolver);
    var continuation = new Continuation("REDRIVE_SCOPE", "{");
    var outcome = DurableDecisions.granted();

    assertThatThrownBy(() -> redrive.completed(continuation, outcome))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aRedriveContinuationMissingTheAgentIdFailsLoudlyNotWithAnNpe() {
    AgentResolver resolver = (t, i) -> null;
    var redrive = new ScopeRedrive(resolver);
    var continuation = new Continuation("REDRIVE_SCOPE", "{\"agentType\":\"a\"}");
    var outcome = DurableDecisions.granted();

    assertThatThrownBy(() -> redrive.completed(continuation, outcome))
        .isInstanceOf(IllegalStateException.class);
  }
}
