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
    private Memory memory;
    private String id = "cli";
    private List<Tool<?>> tools = List.of();
    private ExecutorService executor;

    /** The model backend the scope talks to. */
    public CliBuilder provider(ModelProvider provider) {
      this.provider = Objects.requireNonNull(provider, "provider must not be null");
      return this;
    }

    /** The model call's tuning knobs — model id, system prompt, token budget, capabilities. */
    public CliBuilder settings(ModelSettings settings) {
      this.settings = Objects.requireNonNull(settings, "settings must not be null");
      return this;
    }

    /** The conversation store; defaults to a fresh {@link VerbatimMemory} per build. */
    public CliBuilder memory(Memory memory) {
      this.memory = Objects.requireNonNull(memory, "memory must not be null");
      return this;
    }

    /** The scope's constant agent id. */
    public CliBuilder id(String id) {
      this.id = Objects.requireNonNull(id, "id must not be null");
      return this;
    }

    /** The tools the model may call during a turn. */
    public CliBuilder tools(Tool<?>... tools) {
      this.tools = List.of(Objects.requireNonNull(tools, "tools must not be null"));
      return this;
    }

    /** A caller-supplied executor; when omitted, the built agent owns and closes its own. */
    public CliBuilder executor(ExecutorService executor) {
      this.executor = Objects.requireNonNull(executor, "executor must not be null");
      return this;
    }

    public CliAgent build() {
      Objects.requireNonNull(provider, "provider must not be null");
      Objects.requireNonNull(settings, "settings must not be null");
      boolean ownsExecutor = executor == null;
      ExecutorService exec = ownsExecutor ? Executors.newVirtualThreadPerTaskExecutor() : executor;
      Memory effectiveMemory = memory != null ? memory : new VerbatimMemory();
      var relay = new RelayTurnObserver();
      ToolRegistry registry = ToolRegistry.of(tools.toArray(Tool[]::new));
      var agentId = AgentId.of(id);
      var wiring =
          new AgentWiring<String>(
              effectiveMemory,
              new InMemoryAgentStateStore(),
              inMemoryBacklog(),
              text -> List.of(new TextBlock(text)),
              new ProviderModelCallExecutor(
                  provider, settings, registry, effectiveMemory, relay, exec),
              new RegistryToolCallExecutor(registry, agentId, relay, exec),
              new TurnNarrationAdapter(relay),
              false,
              Duration.ofMinutes(5),
              Clock.systemUTC());
      return new CliAgent(new DefaultAgent<>(wiring), relay, exec, ownsExecutor);
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
