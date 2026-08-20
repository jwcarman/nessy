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
package org.jwcarman.nessy.agent.host;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentWiring;
import org.jwcarman.nessy.agent.DefaultAgent;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.model.ProviderModelCallExecutor;
import org.jwcarman.nessy.agent.narrate.TurnNarrationAdapter;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.Memory;
import org.jwcarman.nessy.agent.store.InMemoryAgentStateStore;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelSettings;

/** The front doors (§7.1). Builders wire existing seams; they never own machinery. */
public final class Nessy {

  private Nessy() {}

  public static CliBuilder cli() {
    return new CliBuilder();
  }

  public static final class CliBuilder {

    private ModelProvider provider;
    private ModelSettings settings;
    private Memory memory = new VerbatimMemory();
    private String id = "cli";
    private List<Tool<?>> tools = List.of();
    private ExecutorService executor;

    public CliBuilder provider(ModelProvider provider) {
      this.provider = provider;
      return this;
    }

    public CliBuilder settings(ModelSettings settings) {
      this.settings = settings;
      return this;
    }

    public CliBuilder memory(Memory memory) {
      this.memory = memory;
      return this;
    }

    public CliBuilder id(String id) {
      this.id = id;
      return this;
    }

    public CliBuilder tools(Tool<?>... tools) {
      this.tools = List.of(tools);
      return this;
    }

    public CliBuilder executor(ExecutorService executor) {
      this.executor = executor;
      return this;
    }

    public CliAgent build() {
      Objects.requireNonNull(provider, "provider must not be null");
      Objects.requireNonNull(settings, "settings must not be null");
      ExecutorService exec =
          executor != null ? executor : Executors.newVirtualThreadPerTaskExecutor();
      var relay = new RelayTurnObserver();
      ToolRegistry registry = ToolRegistry.of(tools.toArray(Tool[]::new));
      var agentId = AgentId.of(id);
      var wiring =
          new AgentWiring<String>(
              memory,
              new InMemoryAgentStateStore(),
              inMemoryBacklog(),
              text -> List.of(new TextBlock(text)),
              new ProviderModelCallExecutor(provider, settings, registry, memory, relay, exec),
              new RegistryToolCallExecutor(registry, agentId, relay, exec),
              new TurnNarrationAdapter(relay),
              false,
              Duration.ofMinutes(5),
              Clock.systemUTC());
      return new CliAgent(new DefaultAgent<>(wiring), relay, exec);
    }

    private static Backlog<String> inMemoryBacklog() {
      Deque<String> queue = new ArrayDeque<>();
      return new Backlog<>() {
        @Override
        public synchronized void add(String observation) {
          queue.add(observation);
        }

        @Override
        public synchronized Optional<String> poll() {
          return Optional.ofNullable(queue.poll());
        }
      };
    }
  }
}
