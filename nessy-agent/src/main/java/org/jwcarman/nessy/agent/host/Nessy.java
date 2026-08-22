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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentResolver;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.Binding;
import org.jwcarman.nessy.agent.DefaultAgent;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.ResolvingAgentBinder;
import org.jwcarman.nessy.agent.ScopeRedrive;
import org.jwcarman.nessy.agent.ScopeResumption;
import org.jwcarman.nessy.agent.StalenessPolicy;
import org.jwcarman.nessy.agent.backlog.SubstrateBacklog;
import org.jwcarman.nessy.agent.codec.Codecs;
import org.jwcarman.nessy.agent.durable.ApprovalDesk;
import org.jwcarman.nessy.agent.durable.CompletionDesk;
import org.jwcarman.nessy.agent.durable.SlotApprover;
import org.jwcarman.nessy.agent.durable.SlotDeferredToolCallPolicy;
import org.jwcarman.nessy.agent.durable.SubstrateComputations;
import org.jwcarman.nessy.agent.memory.SubstrateMemory;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.model.ProviderModelCallExecutor;
import org.jwcarman.nessy.agent.narrate.TurnNarrationAdapter;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.ObservationRenderer;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.durable.ContinuationDispatcher;
import org.jwcarman.nessy.durable.DurableComputationBackend;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.substrate.Codec;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/** The front doors (§7.1). Builders wire existing seams; they never own machinery. */
public final class Nessy {

  private static final String PROVIDER_MUST_NOT_BE_NULL = "provider must not be null";
  private static final String SETTINGS_MUST_NOT_BE_NULL = "settings must not be null";

  /**
   * The {@code String} door's trivial backlog codec: UTF-8 bytes, nothing more. Not a new public
   * type — just the {@link Codec} contract's cheapest instance, private to this class.
   */
  private static final Codec<String> STRING_CODEC =
      new Codec<>() {
        @Override
        public byte[] encode(String value) {
          Objects.requireNonNull(value, "value must not be null");
          return value.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String decode(byte[] bytes) {
          Objects.requireNonNull(bytes, "bytes must not be null");
          return new String(bytes, StandardCharsets.UTF_8);
        }
      };

  private Nessy() {}

  public static CliBuilder cli() {
    return new CliBuilder();
  }

  /** The {@code String} text door (spec §6.4): today's renderer and ergonomics, unchanged. */
  public static AutonomousBuilder<String> autonomous() {
    AutonomousBuilder<String> builder = new AutonomousBuilder<>();
    builder.renderer = text -> List.of(new TextBlock(text));
    builder.backlogCodec = STRING_CODEC;
    return builder;
  }

  /**
   * The typed door (spec §6.4): observations are {@code O}, not {@code String}. The caller must
   * supply the {@link ObservationRenderer} that turns an {@code O} into inference content — the
   * same seam {@link Harness} has always taken — via {@link
   * AutonomousBuilder#renderer(ObservationRenderer)} before {@code build()}; the backlog codec
   * defaults to {@link Codec#json(ObjectMapper, Class)} over the builder's pinned mapper,
   * overridable via {@link AutonomousBuilder#backlogCodec(Codec)}.
   */
  public static <O> AutonomousBuilder<O> autonomous(Class<O> observationType) {
    Objects.requireNonNull(observationType, "observationType must not be null");
    AutonomousBuilder<O> builder = new AutonomousBuilder<>();
    builder.observationType = observationType;
    return builder;
  }

  public static final class CliBuilder {

    private ModelProvider provider;
    private ModelSettings settings;
    private Memory memory;
    private String id = "cli";
    private String typeName = "cli";
    private List<Tool<?>> tools = List.of();
    private ExecutorService executor;
    private ObjectMapper objectMapper = new ObjectMapper();

    /** The model backend the scope talks to. */
    public CliBuilder provider(ModelProvider provider) {
      this.provider = Objects.requireNonNull(provider, PROVIDER_MUST_NOT_BE_NULL);
      return this;
    }

    /** The model call's tuning knobs — model id, system prompt, token budget, capabilities. */
    public CliBuilder settings(ModelSettings settings) {
      this.settings = Objects.requireNonNull(settings, SETTINGS_MUST_NOT_BE_NULL);
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

    /**
     * The mapper the host binds JSON with; default a fresh {@link ObjectMapper}. {@code build()}
     * pins a copy of it (spec §7: lower-camel property naming, tolerant reads, no default typing)
     * and threads that one pinned copy through every recipe that binds JSON — user-registered
     * modules and serializers survive the copy.
     */
    public CliBuilder objectMapper(ObjectMapper objectMapper) {
      this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
      return this;
    }

    public CliAgent build() {
      Objects.requireNonNull(provider, PROVIDER_MUST_NOT_BE_NULL);
      Objects.requireNonNull(settings, SETTINGS_MUST_NOT_BE_NULL);
      ObjectMapper pinned = Codecs.copyAndPin(objectMapper);
      boolean ownsExecutor = executor == null;
      ExecutorService exec = ownsExecutor ? Executors.newVirtualThreadPerTaskExecutor() : executor;
      Memory effectiveMemory = memory != null ? memory : new VerbatimMemory();
      var relay = new RelayTurnObserver();
      ToolRegistry registry = ToolRegistry.of(tools.toArray(Tool[]::new));
      ToolRegistry limited = ToolRegistry.limited(registry, CompletionPolicy.AWAITABLE);
      var agentId = AgentId.of(id);
      var agentType = AgentType.of(typeName);
      Substrate substrate = new InMemorySubstrate();
      var store = new SubstrateAgentStateStore(substrate, id, Clock.systemUTC(), pinned);
      var backlog = new SubstrateBacklog<>(substrate, id, 1024, STRING_CODEC);
      Harness<String> harness =
          Harness.of(
              agentType,
              text -> List.of(new TextBlock(text)),
              new TurnNarrationAdapter(relay),
              false,
              StalenessPolicy.never(),
              rawId -> effectiveMemory,
              rawId -> store,
              rawId -> backlog,
              binding ->
                  new ProviderModelCallExecutor(
                      provider, settings, limited, binding.memory(), relay, exec),
              binding ->
                  new RegistryToolCallExecutor(
                      limited, agentType, binding.id(), relay, exec, pinned));
      Binding<String> binding = harness.bind(agentId);
      return new CliAgent(new DefaultAgent<>(harness, binding), relay, exec, ownsExecutor);
    }
  }

  public static final class AutonomousBuilder<O> {

    private ModelProvider provider;
    private ModelSettings settings;
    private String typeName = "autonomous";
    private List<ToolGrant> grants = List.of();
    private Function<String, Memory> memoryFactory;
    private Substrate substrate;
    private DurableComputationBackend backend;
    private Consumer<ApprovalRequest> approvalNotifier = request -> {};
    private TurnObserver turnObserver = TurnObserver.noop();
    // null until the caller sets one — build() defaults it to a TurnNarrationAdapter over
    // turnObserver, so AssistantSaid/TurnEnded narrate the way the CLI door always has.
    private AgentObserver agentObserver;
    private Executor executor;
    private int backlogCapacity = 1024;
    private StalenessPolicy stalenessPolicy = StalenessPolicy.after(Duration.ofMinutes(5));
    private ObjectMapper objectMapper = new ObjectMapper();
    // set by Nessy.autonomous() for the String door; required (via renderer(ObservationRenderer))
    // before build() for the typed door opened by Nessy.autonomous(Class).
    private ObservationRenderer<O> renderer;
    // set by Nessy.autonomous() for the String door; the typed door defaults it in build() to
    // Codec.json(pinned, observationType) unless backlogCodec(Codec) overrides it.
    private Codec<O> backlogCodec;
    // set only by Nessy.autonomous(Class<O>) — the typed door's default-codec source.
    private Class<O> observationType;

    /**
     * The recipe's name — the first coordinate of every durable address; default {@code
     * "autonomous"}.
     */
    public AutonomousBuilder<O> type(String typeName) {
      this.typeName = Objects.requireNonNull(typeName, "typeName must not be null");
      return this;
    }

    /** The model backend every scope talks to. */
    public AutonomousBuilder<O> provider(ModelProvider provider) {
      this.provider = Objects.requireNonNull(provider, PROVIDER_MUST_NOT_BE_NULL);
      return this;
    }

    /** The model call's tuning knobs — model id, system prompt, token budget, capabilities. */
    public AutonomousBuilder<O> settings(ModelSettings settings) {
      this.settings = Objects.requireNonNull(settings, SETTINGS_MUST_NOT_BE_NULL);
      return this;
    }

    /** The tool grants every scope carries, authority and all. */
    public AutonomousBuilder<O> grants(ToolGrant... grants) {
      this.grants = List.of(Objects.requireNonNull(grants, "grants must not be null"));
      return this;
    }

    /**
     * Sugar: each tool granted an answered-allow authority, via {@link ToolRegistry#of(Tool...)}.
     */
    public AutonomousBuilder<O> tools(Tool<?>... tools) {
      Objects.requireNonNull(tools, "tools must not be null");
      this.grants = ToolRegistry.of(tools).grants();
      return this;
    }

    /**
     * Builds each scope's conversation store from its raw id; default {@code id -> new
     * SubstrateMemory(substrate, id)} — a view over the one {@link Substrate} every scope shares
     * (§6.2). The substrate IS the shared state now; a factory is a view over it by construction,
     * never freshly-created state of its own. A factory is free to return views over any durable
     * memory shared across many hosts (spec §10.11) — the id is the only key, and losing a view
     * loses nothing.
     *
     * <p>Invoked once per delivery — the factory MUST return a view over shared state, never
     * freshly-created state; there is no per-id cache behind it.
     */
    public AutonomousBuilder<O> memoryFactory(Function<String, Memory> memoryFactory) {
      this.memoryFactory = Objects.requireNonNull(memoryFactory, "memoryFactory must not be null");
      return this;
    }

    /**
     * The one storage seam (substrate spec §12): every scope's state, memory (unless {@link
     * #memoryFactory(Function)} overrides it), and backlog live as documents in this substrate;
     * default a fresh {@link InMemorySubstrate}. Supply a durable {@link Substrate} — a JDBC or
     * DynamoDB adapter — to persist every scope beyond the process.
     */
    public AutonomousBuilder<O> substrate(Substrate substrate) {
      this.substrate = Objects.requireNonNull(substrate, "substrate must not be null");
      return this;
    }

    /**
     * The shared durable computation backend behind both desks; default {@link
     * SubstrateComputations} over this builder's {@link #substrate(Substrate)}. Override for a
     * genuinely foreign engine (Restate, Temporal) — nobody implements this seam to get a database
     * (spec §6.5).
     */
    public AutonomousBuilder<O> backend(DurableComputationBackend backend) {
      this.backend = Objects.requireNonNull(backend, "backend must not be null");
      return this;
    }

    /** Fires once, point-to-point, the moment an approval slot is first asked (§4.3 amendment). */
    public AutonomousBuilder<O> approvalNotifier(Consumer<ApprovalRequest> approvalNotifier) {
      this.approvalNotifier =
          Objects.requireNonNull(approvalNotifier, "approvalNotifier must not be null");
      return this;
    }

    /** The absent audience by default; every scope's turns narrate here. */
    public AutonomousBuilder<O> turnObserver(TurnObserver turnObserver) {
      this.turnObserver = Objects.requireNonNull(turnObserver, "turnObserver must not be null");
      return this;
    }

    /**
     * The shell-failure narration seam: {@code applyFailed}, {@code renderFailed}, {@code reFired},
     * and {@code observationRequeued} land here. Defaults to a {@link TurnNarrationAdapter} over
     * the turn observer, so {@code AssistantSaid}/{@code TurnEnded} narrate — the posture the CLI
     * door has always had; supplying your own observer here replaces that wiring entirely, so an
     * override that still wants those events narrated must wrap {@link TurnObserver} itself.
     */
    public AutonomousBuilder<O> agentObserver(AgentObserver agentObserver) {
      this.agentObserver = Objects.requireNonNull(agentObserver, "agentObserver must not be null");
      return this;
    }

    /** A caller-supplied executor; when omitted, the built host owns and closes its own. */
    public AutonomousBuilder<O> executor(Executor executor) {
      this.executor = Objects.requireNonNull(executor, "executor must not be null");
      return this;
    }

    /**
     * The per-scope capacity of the {@code backlog} document every scope holds in the shared {@link
     * #substrate(Substrate)} (spec §6.4, §11 open question 0); default 1024.
     */
    public AutonomousBuilder<O> backlogCapacity(int backlogCapacity) {
      if (backlogCapacity < 1) {
        throw new IllegalArgumentException("backlogCapacity must be at least 1");
      }
      this.backlogCapacity = backlogCapacity;
      return this;
    }

    /**
     * The §6.1 judgment: when a quiet phase counts as dead enough for the recovery arm to re-fire
     * it; default {@code after(Duration.ofMinutes(5))}.
     */
    public AutonomousBuilder<O> staleness(StalenessPolicy stalenessPolicy) {
      this.stalenessPolicy =
          Objects.requireNonNull(stalenessPolicy, "stalenessPolicy must not be null");
      return this;
    }

    /**
     * The mapper the host binds JSON with; default a fresh {@link ObjectMapper}. {@code build()}
     * pins a copy of it (spec §7: lower-camel property naming, tolerant reads, no default typing)
     * and threads that one pinned copy through every recipe that binds JSON — user-registered
     * modules and serializers survive the copy.
     */
    public AutonomousBuilder<O> objectMapper(ObjectMapper objectMapper) {
      this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
      return this;
    }

    /**
     * Translates observations to inference content, applied at poll time (§3.7) — the same {@link
     * ObservationRenderer} seam {@link Harness} has always taken. The {@code String} door ({@link
     * Nessy#autonomous()}) presets this; the typed door ({@link Nessy#autonomous(Class)}) requires
     * it before {@link #build()}.
     */
    public AutonomousBuilder<O> renderer(ObservationRenderer<O> renderer) {
      this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
      return this;
    }

    /**
     * The {@code backlog} document's per-observation codec (spec §6.4); default {@link
     * Codec#json(ObjectMapper, Class)} over this builder's pinned mapper and the typed door's
     * observation type. Override for a caller-supplied shape, a chained transform ({@link
     * Codec#then(Codec)}), or a test probe.
     */
    public AutonomousBuilder<O> backlogCodec(Codec<O> backlogCodec) {
      this.backlogCodec = Objects.requireNonNull(backlogCodec, "backlogCodec must not be null");
      return this;
    }

    public AutonomousHost<O> build() {
      Objects.requireNonNull(provider, PROVIDER_MUST_NOT_BE_NULL);
      Objects.requireNonNull(settings, SETTINGS_MUST_NOT_BE_NULL);
      ObservationRenderer<O> effectiveRenderer =
          Objects.requireNonNull(renderer, "renderer must not be null");
      ObjectMapper pinned = Codecs.copyAndPin(objectMapper);
      Codec<O> effectiveBacklogCodec =
          backlogCodec != null ? backlogCodec : Codec.json(pinned, observationType);
      boolean ownsExecutor = executor == null;
      ExecutorService owned = ownsExecutor ? Executors.newVirtualThreadPerTaskExecutor() : null;
      Executor exec = ownsExecutor ? owned : executor;
      var agentType = AgentType.of(typeName);
      ToolRegistry base = ToolRegistry.of(grants.toArray(ToolGrant[]::new));
      ToolRegistry registry = ToolRegistry.limited(base, CompletionPolicy.DURABLE);
      Substrate effectiveSubstrate = substrate != null ? substrate : new InMemorySubstrate();
      Function<String, Memory> effectiveMemoryFactory =
          memoryFactory != null
              ? memoryFactory
              : id -> new SubstrateMemory(effectiveSubstrate, id, pinned);
      Function<String, AgentStateStore> effectiveStoreFactory =
          id -> new SubstrateAgentStateStore(effectiveSubstrate, id, Clock.systemUTC(), pinned);
      Function<String, Backlog<O>> effectiveBacklogFactory =
          id ->
              new SubstrateBacklog<>(
                  effectiveSubstrate, id, backlogCapacity, effectiveBacklogCodec);
      DurableComputationBackend effectiveBackend =
          backend != null ? backend : new SubstrateComputations(effectiveSubstrate, pinned);
      AgentObserver effectiveAgentObserver =
          agentObserver != null ? agentObserver : new TurnNarrationAdapter(turnObserver);

      var dispatcher = new ContinuationDispatcher();
      var hostRef = new AtomicReference<AutonomousHost<O>>();
      AgentResolver resolver =
          (type, id) -> {
            if (!type.equals(agentType)) {
              throw new IllegalArgumentException("unknown agent type: " + type.name());
            }
            return hostRef.get().agentFor(id);
          };
      var scopeResumption = new ScopeResumption(new ResolvingAgentBinder(resolver), pinned);
      var scopeRedrive = new ScopeRedrive(resolver, pinned);

      Harness<O> harness =
          Harness.of(
              agentType,
              effectiveRenderer,
              effectiveAgentObserver,
              true,
              stalenessPolicy,
              effectiveMemoryFactory,
              effectiveStoreFactory,
              effectiveBacklogFactory,
              binding ->
                  new ProviderModelCallExecutor(
                      provider, settings, registry, binding.memory(), turnObserver, exec),
              binding ->
                  new RegistryToolCallExecutor(
                      registry,
                      agentType,
                      binding.id(),
                      turnObserver,
                      exec,
                      new SlotDeferredToolCallPolicy(effectiveBackend, scopeResumption),
                      new SlotApprover(effectiveBackend, approvalNotifier, scopeRedrive),
                      pinned));

      dispatcher.register(ScopeResumption.TYPE, scopeResumption);
      dispatcher.register(ScopeRedrive.TYPE, scopeRedrive);

      var approvals = new ApprovalDesk(effectiveBackend, dispatcher);
      var completions = new CompletionDesk(effectiveBackend, dispatcher);
      var host = new AutonomousHost<>(owned, approvals, completions, harness);
      hostRef.set(host);
      return host;
    }
  }
}
