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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentResolver;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.AgentWiring;
import org.jwcarman.nessy.agent.DefaultAgent;
import org.jwcarman.nessy.agent.ResolvingAgentBinder;
import org.jwcarman.nessy.agent.ScopeRedrive;
import org.jwcarman.nessy.agent.ScopeResumption;
import org.jwcarman.nessy.agent.backlog.BoundedBacklog;
import org.jwcarman.nessy.agent.durable.ApprovalDesk;
import org.jwcarman.nessy.agent.durable.CompletionDesk;
import org.jwcarman.nessy.agent.durable.SlotApprover;
import org.jwcarman.nessy.agent.durable.SlotDeferredToolCallPolicy;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.model.ProviderModelCallExecutor;
import org.jwcarman.nessy.agent.narrate.TurnNarrationAdapter;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.agent.store.InMemoryAgentStateStore;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.durable.ContinuationDispatcher;
import org.jwcarman.nessy.durable.DurableComputationBackend;
import org.jwcarman.nessy.durable.InMemoryDurableComputationBackend;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelSettings;

/** The front doors (§7.1). Builders wire existing seams; they never own machinery. */
public final class Nessy {

  private Nessy() {}

  public static CliBuilder cli() {
    return new CliBuilder();
  }

  public static AutonomousBuilder autonomous() {
    return new AutonomousBuilder();
  }

  public static final class CliBuilder {

    private ModelProvider provider;
    private ModelSettings settings;
    private Memory memory;
    private String id = "cli";
    private String typeName = "cli";
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

    /** The recipe's name — the first coordinate of every durable address. */
    public CliBuilder type(String typeName) {
      this.typeName = Objects.requireNonNull(typeName, "typeName must not be null");
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
      ToolRegistry limited = ToolRegistry.limited(registry, CompletionPolicy.AWAITABLE);
      var agentId = AgentId.of(id);
      var agentType = AgentType.of(typeName);
      var wiring =
          new AgentWiring<String>(
              effectiveMemory,
              new InMemoryAgentStateStore(),
              inMemoryBacklog(),
              text -> List.of(new TextBlock(text)),
              new ProviderModelCallExecutor(
                  provider, settings, limited, effectiveMemory, relay, exec),
              new RegistryToolCallExecutor(limited, agentType, agentId, relay, exec),
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

  public static final class AutonomousBuilder {

    private ModelProvider provider;
    private ModelSettings settings;
    private String typeName = "autonomous";
    private List<ToolGrant> grants = List.of();
    private Function<String, Memory> memoryFactory = id -> new VerbatimMemory();
    private Function<String, AgentStateStore> storeFactory = id -> new InMemoryAgentStateStore();
    private DurableComputationBackend backend = new InMemoryDurableComputationBackend();
    private Consumer<ApprovalRequest> approvalNotifier = request -> {};
    private TurnObserver turnObserver = TurnObserver.noop();
    private AgentObserver agentObserver = AgentObserver.noop();
    private Executor executor;
    private int backlogCapacity = 1024;
    private Duration staleThreshold = Duration.ofMinutes(5);
    private Clock clock = Clock.systemUTC();

    /**
     * The recipe's name — the first coordinate of every durable address; default {@code
     * "autonomous"}.
     */
    public AutonomousBuilder type(String typeName) {
      this.typeName = Objects.requireNonNull(typeName, "typeName must not be null");
      return this;
    }

    /** The model backend every scope talks to. */
    public AutonomousBuilder provider(ModelProvider provider) {
      this.provider = Objects.requireNonNull(provider, "provider must not be null");
      return this;
    }

    /** The model call's tuning knobs — model id, system prompt, token budget, capabilities. */
    public AutonomousBuilder settings(ModelSettings settings) {
      this.settings = Objects.requireNonNull(settings, "settings must not be null");
      return this;
    }

    /** The tool grants every scope carries, authority and all. */
    public AutonomousBuilder grants(ToolGrant... grants) {
      this.grants = List.of(Objects.requireNonNull(grants, "grants must not be null"));
      return this;
    }

    /**
     * Sugar: each tool granted an answered-allow authority, via {@link ToolRegistry#of(Tool...)}.
     */
    public AutonomousBuilder tools(Tool<?>... tools) {
      Objects.requireNonNull(tools, "tools must not be null");
      this.grants = ToolRegistry.of(tools).grants();
      return this;
    }

    /**
     * Builds each scope's conversation store from its raw id; default a fresh {@link
     * VerbatimMemory}.
     */
    public AutonomousBuilder memoryFactory(Function<String, Memory> memoryFactory) {
      this.memoryFactory = Objects.requireNonNull(memoryFactory, "memoryFactory must not be null");
      return this;
    }

    /**
     * Builds each scope's state store from its raw id; default a fresh {@link
     * InMemoryAgentStateStore}.
     */
    public AutonomousBuilder storeFactory(Function<String, AgentStateStore> storeFactory) {
      this.storeFactory = Objects.requireNonNull(storeFactory, "storeFactory must not be null");
      return this;
    }

    /** The shared durable computation backend behind both desks. */
    public AutonomousBuilder backend(DurableComputationBackend backend) {
      this.backend = Objects.requireNonNull(backend, "backend must not be null");
      return this;
    }

    /** Fires once, point-to-point, the moment an approval slot is first asked (§4.3 amendment). */
    public AutonomousBuilder approvalNotifier(Consumer<ApprovalRequest> approvalNotifier) {
      this.approvalNotifier =
          Objects.requireNonNull(approvalNotifier, "approvalNotifier must not be null");
      return this;
    }

    /** The absent audience by default; every scope's turns narrate here. */
    public AutonomousBuilder turnObserver(TurnObserver turnObserver) {
      this.turnObserver = Objects.requireNonNull(turnObserver, "turnObserver must not be null");
      return this;
    }

    /**
     * The shell-failure narration seam: {@code applyFailed}, {@code renderFailed}, {@code reFired},
     * and {@code observationRequeued} land here; default keeps {@link AgentObserver#noop()}.
     */
    public AutonomousBuilder agentObserver(AgentObserver agentObserver) {
      this.agentObserver = Objects.requireNonNull(agentObserver, "agentObserver must not be null");
      return this;
    }

    /** A caller-supplied executor; when omitted, the built host owns and closes its own. */
    public AutonomousBuilder executor(Executor executor) {
      this.executor = Objects.requireNonNull(executor, "executor must not be null");
      return this;
    }

    /** The bounded backlog's per-scope capacity (spec §11, open question 0); default 1024. */
    public AutonomousBuilder backlogCapacity(int backlogCapacity) {
      if (backlogCapacity < 1) {
        throw new IllegalArgumentException("backlogCapacity must be at least 1");
      }
      this.backlogCapacity = backlogCapacity;
      return this;
    }

    /**
     * How long a quiet phase must age before the recovery arm re-fires it (§6.1); default 5
     * minutes.
     */
    public AutonomousBuilder staleThreshold(Duration staleThreshold) {
      this.staleThreshold =
          Objects.requireNonNull(staleThreshold, "staleThreshold must not be null");
      return this;
    }

    /** The clock every scope's staleness check reads; default system UTC. */
    public AutonomousBuilder clock(Clock clock) {
      this.clock = Objects.requireNonNull(clock, "clock must not be null");
      return this;
    }

    public AutonomousHost build() {
      Objects.requireNonNull(provider, "provider must not be null");
      Objects.requireNonNull(settings, "settings must not be null");
      boolean ownsExecutor = executor == null;
      ExecutorService owned = ownsExecutor ? Executors.newVirtualThreadPerTaskExecutor() : null;
      Executor exec = ownsExecutor ? owned : executor;
      var agentType = AgentType.of(typeName);
      ToolRegistry base = ToolRegistry.of(grants.toArray(ToolGrant[]::new));
      ToolRegistry registry = ToolRegistry.limited(base, CompletionPolicy.DURABLE);

      Function<AgentId, AgentWiring<String>> wirings =
          agentId -> {
            String rawId = agentId.value();
            Memory memory = memoryFactory.apply(rawId);
            return new AgentWiring<>(
                memory,
                storeFactory.apply(rawId),
                new BoundedBacklog<>(backlogCapacity),
                text -> List.of(new TextBlock(text)),
                new ProviderModelCallExecutor(
                    provider, settings, registry, memory, turnObserver, exec),
                new RegistryToolCallExecutor(
                    registry,
                    agentType,
                    agentId,
                    turnObserver,
                    exec,
                    new SlotDeferredToolCallPolicy(backend),
                    new SlotApprover(backend, approvalNotifier)),
                agentObserver,
                true,
                staleThreshold,
                clock);
          };

      var dispatcher = new ContinuationDispatcher();
      var hostRef = new AtomicReference<AutonomousHost>();
      AgentResolver resolver =
          (type, id) -> {
            if (!type.equals(agentType)) {
              throw new IllegalArgumentException("unknown agent type: " + type.name());
            }
            return hostRef.get().agentFor(id);
          };
      dispatcher.register(
          ScopeResumption.TYPE, new ScopeResumption(new ResolvingAgentBinder(resolver)));
      dispatcher.register(ScopeRedrive.TYPE, new ScopeRedrive(resolver));

      var approvals = new ApprovalDesk(backend, dispatcher);
      var completions = new CompletionDesk(backend, dispatcher);
      var host = new AutonomousHost(owned, approvals, completions, wirings);
      hostRef.set(host);
      return host;
    }
  }
}
