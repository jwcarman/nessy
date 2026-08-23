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
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.StalenessPolicy;
import org.jwcarman.nessy.agent.backlog.SubstrateBacklog;
import org.jwcarman.nessy.agent.codec.Codecs;
import org.jwcarman.nessy.agent.durable.ComputationApprover;
import org.jwcarman.nessy.agent.durable.ComputationDeferredToolCallPolicy;
import org.jwcarman.nessy.agent.durable.SubstrateComputations;
import org.jwcarman.nessy.agent.memory.SubstrateMemory;
import org.jwcarman.nessy.agent.model.ProviderModelCallExecutor;
import org.jwcarman.nessy.agent.narrate.TurnNarrationAdapter;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.ObservationRenderer;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.durable.DurableComputationBackend;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.substrate.Codec;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * A CONFIG, not a builder (harness-first spec §2, renamed from the pre-customizer fluent builder
 * this reform replaced): fluent setters, no public {@code build()} — the same {@link
 * org.jwcarman.nessy.api.tool.ToolConfig} idiom, describing a kept, immortal {@link Harness} rather
 * than a host. Reached only through {@link Nessy#harness(HarnessCustomizer)} or {@link
 * Nessy#harness(Class, HarnessCustomizer)}, which alone turn a filled-in config into the {@link
 * Harness} it describes.
 */
public final class HarnessConfig<O> {

  private Model model;
  private ModelSettings settings;
  private String systemPrompt;
  private String typeName = "agent";
  private List<ToolGrant> grants = List.of();
  private Function<String, Memory> memoryFactory;
  private Substrate substrate;
  private DurableComputationBackend backend;
  private Consumer<ApprovalRequest> approvalNotifier = request -> {};
  private TurnObserver turnObserver = TurnObserver.noop();
  // null until the caller sets one — finish() defaults it to a TurnNarrationAdapter over
  // turnObserver, so AssistantSaid/TurnEnded narrate the way the CLI door always has.
  private AgentObserver agentObserver;
  private Executor executor;
  private int backlogCapacity = 1024;
  private StalenessPolicy stalenessPolicy = StalenessPolicy.after(Duration.ofMinutes(5));
  private ObjectMapper objectMapper = new ObjectMapper();
  // package-private: Nessy.harness(HarnessCustomizer) sets this directly for the String door;
  // required (via renderer(ObservationRenderer)) for the typed door opened by
  // Nessy.harness(Class, HarnessCustomizer).
  ObservationRenderer<O> renderer;
  // package-private: Nessy.harness(HarnessCustomizer) sets this directly for the String door; the
  // typed door always derives its codec in finish() from Codec.json(pinned, observationType) — no
  // override seam (parked for James).
  Codec<O> backlogCodec;
  // package-private: Nessy.harness(Class, HarnessCustomizer) sets this directly — the typed door's
  // codec-derivation source.
  Class<O> observationType;

  /**
   * Reachable only through {@link Nessy#harness(HarnessCustomizer)} or {@link Nessy#harness(Class,
   * HarnessCustomizer)}.
   */
  HarnessConfig() {}

  /**
   * The recipe's name — the first coordinate of every durable address; default {@code "agent"}.
   *
   * <p><b>One harness per agent type per substrate</b> (spec §3): two harnesses sharing both the
   * same {@code type} and the same {@link #substrate(Substrate)} would double-drain each other's
   * deliveries — each harness's worker and reaper sweep every record carrying that type, regardless
   * of which harness instance produced it. Give two harnesses over one substrate distinct types, or
   * give them distinct substrates.
   */
  public HarnessConfig<O> type(String typeName) {
    this.typeName = Objects.requireNonNull(typeName, "typeName must not be null");
    return this;
  }

  /**
   * The bound model handle every scope talks to — the harness's one required dependency (spec §3):
   * no environment fallback, so this stays the one thing every caller must supply explicitly. A
   * {@link Model} already knows which model it runs (spec §7); no model string threads through the
   * harness at all.
   */
  public HarnessConfig<O> model(Model model) {
    this.model = Objects.requireNonNull(model, Nessy.MODEL_MUST_NOT_BE_NULL);
    return this;
  }

  /**
   * First-class, harness-level configuration (spec §3, §7) — no longer a field on {@link
   * ModelSettings}: required, with no settings fallback.
   */
  public HarnessConfig<O> systemPrompt(String systemPrompt) {
    this.systemPrompt = Objects.requireNonNull(systemPrompt, Nessy.SYSTEM_PROMPT_MUST_NOT_BE_NULL);
    return this;
  }

  /**
   * The model call's OPTIONAL tuning knobs — max token budget, requested capabilities, context
   * window (spec §7); default {@link ModelSettings#defaults()} when never called.
   */
  public HarnessConfig<O> settings(ModelSettings settings) {
    this.settings = Objects.requireNonNull(settings, "settings must not be null");
    return this;
  }

  /**
   * The tool grants every scope carries, authority and all. Shares one slot with {@link
   * #tools(Tool[])} — whichever of the two is called last wins; calling both is not additive.
   */
  public HarnessConfig<O> grants(ToolGrant... grants) {
    this.grants = List.of(Objects.requireNonNull(grants, "grants must not be null"));
    return this;
  }

  /**
   * Sugar: each tool granted an answered-allow authority, via {@link ToolRegistry#of(Tool...)}.
   * Shares one slot with {@link #grants(ToolGrant...)} — whichever of the two is called last wins;
   * calling both is not additive.
   */
  public HarnessConfig<O> tools(Tool<?>... tools) {
    Objects.requireNonNull(tools, "tools must not be null");
    this.grants = ToolRegistry.of(tools).grants();
    return this;
  }

  /**
   * Builds each scope's conversation store from its raw id; default {@code id -> new
   * SubstrateMemory(substrate, id)} — a view over the one {@link Substrate} every scope shares
   * (§6.2). The substrate IS the shared state now; a factory is a view over it by construction,
   * never freshly-created state of its own. A factory is free to return views over any durable
   * memory shared across many hosts (spec §10.11) — the id is the only key, and losing a view loses
   * nothing.
   *
   * <p>Invoked once per binding — the factory MUST return a view over shared state, never
   * freshly-created state; there is no per-id cache behind it. One caller cares which view comes
   * back: {@code DeliveryWorker}'s grant path requires the bound {@link Memory} to be backed by the
   * same {@link Substrate}, plain — a scope wired with anything else fails loudly before a granted
   * tool ever runs (durable-deliveries spec §5a; see {@code DeliveryWorker}'s own javadoc for why).
   */
  public HarnessConfig<O> memoryFactory(Function<String, Memory> memoryFactory) {
    this.memoryFactory = Objects.requireNonNull(memoryFactory, "memoryFactory must not be null");
    return this;
  }

  /**
   * The one storage seam (substrate spec §12): every scope's state, memory (unless {@link
   * #memoryFactory(Function)} overrides it), and backlog live as documents in this substrate;
   * default a fresh {@link InMemorySubstrate}. Supply a durable {@link Substrate} — a JDBC or
   * DynamoDB adapter — to persist every scope beyond the process.
   */
  public HarnessConfig<O> substrate(Substrate substrate) {
    this.substrate = Objects.requireNonNull(substrate, "substrate must not be null");
    return this;
  }

  /**
   * The shared durable computation backend behind both desks; default {@link SubstrateComputations}
   * over this builder's {@link #substrate(Substrate)}. Override for a genuinely foreign engine
   * (Restate, Temporal) — nobody implements this seam to get a database (spec §6.5).
   *
   * <p><b>Integration contract:</b> the {@code DeliveryWorker} reads completions from this
   * builder's {@link #substrate(Substrate)} — specifically, {@code kind=outbox} delivery documents
   * ({@code {destination, outcome}}, spec §4) — never from the backend directly. A foreign {@code
   * DurableComputationBackend} MUST write those same {@code outbox} documents into this substrate
   * on {@code complete()}; that write is the only way a completion ever reaches a parked scope. A
   * backend that completes computations some other way (its own store, its own callback) parks
   * every scope's completion forever — this config does not police that, so get the write right.
   */
  public HarnessConfig<O> backend(DurableComputationBackend backend) {
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    return this;
  }

  /**
   * Fires once, point-to-point, the moment an approval computation is first asked (§4.3 amendment).
   */
  public HarnessConfig<O> approvalNotifier(Consumer<ApprovalRequest> approvalNotifier) {
    this.approvalNotifier =
        Objects.requireNonNull(approvalNotifier, "approvalNotifier must not be null");
    return this;
  }

  /** The absent audience by default; every scope's turns narrate here. */
  public HarnessConfig<O> turnObserver(TurnObserver turnObserver) {
    this.turnObserver = Objects.requireNonNull(turnObserver, "turnObserver must not be null");
    return this;
  }

  /**
   * The shell-failure narration seam: {@code applyFailed}, {@code renderFailed}, {@code reFired},
   * and {@code observationRequeued} land here. Defaults to a {@link TurnNarrationAdapter} over the
   * turn observer, so {@code AssistantSaid}/{@code TurnEnded} narrate — the posture the CLI door
   * has always had; supplying your own observer here replaces that wiring entirely, so an override
   * that still wants those events narrated must wrap {@link TurnObserver} itself.
   */
  public HarnessConfig<O> agentObserver(AgentObserver agentObserver) {
    this.agentObserver = Objects.requireNonNull(agentObserver, "agentObserver must not be null");
    return this;
  }

  /**
   * A caller-supplied executor; when omitted, {@link #finish()} owns one of its own — a virtual-
   * thread-per-task executor that, like the rest of the harness's life-support, lives exactly as
   * long as the process (spec §4): there is no lifecycle door to shut it down through, and none is
   * needed.
   */
  public HarnessConfig<O> executor(Executor executor) {
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
    return this;
  }

  /**
   * The per-scope capacity of the {@code backlog} document every scope holds in the shared {@link
   * #substrate(Substrate)} (spec §6.4, §11 open question 0); default 1024.
   */
  public HarnessConfig<O> backlogCapacity(int backlogCapacity) {
    if (backlogCapacity < 1) {
      throw new IllegalArgumentException("backlogCapacity must be at least 1");
    }
    this.backlogCapacity = backlogCapacity;
    return this;
  }

  /**
   * The §6.1 judgment: when a quiet phase counts as dead enough for the recovery arm to re-fire it;
   * default {@code after(Duration.ofMinutes(5))}.
   */
  public HarnessConfig<O> staleness(StalenessPolicy stalenessPolicy) {
    this.stalenessPolicy =
        Objects.requireNonNull(stalenessPolicy, "stalenessPolicy must not be null");
    return this;
  }

  /**
   * The mapper the host binds JSON with; default a fresh {@link ObjectMapper}. {@link #finish()}
   * pins a copy of it (spec §7: lower-camel property naming, tolerant reads, no default typing) and
   * threads that one pinned copy through every recipe that binds JSON — user-registered modules and
   * serializers survive the copy.
   */
  public HarnessConfig<O> objectMapper(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    return this;
  }

  /**
   * Translates observations to inference content, applied at poll time (§3.7) — the same {@link
   * ObservationRenderer} seam {@link Harness} has always taken. The {@code String} door ({@link
   * Nessy#harness(HarnessCustomizer)}) presets this; the typed door ({@link Nessy#harness(Class,
   * HarnessCustomizer)}) requires the customizer to set it.
   */
  public HarnessConfig<O> renderer(ObservationRenderer<O> renderer) {
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    return this;
  }

  /**
   * Turns this config into the {@link Harness} it describes — reached only from {@link
   * Nessy#harness(HarnessCustomizer)} or {@link Nessy#harness(Class, HarnessCustomizer)}, once
   * {@code customize} has returned (the {@link org.jwcarman.nessy.api.tool.ToolConfig#finish()}
   * idiom — no public {@code build()} survives here).
   */
  Harness<O> finish() {
    if (model == null) {
      throw new NullPointerException(
          "model must not be null — Nessy.harness(...) requires .model(Model) inside the"
              + " customizer; it is the harness's one required dependency (spec §3), with no"
              + " environment fallback");
    }
    if (systemPrompt == null) {
      throw new NullPointerException(
          "systemPrompt must not be null — Nessy.harness(...) requires .systemPrompt(String)"
              + " inside the customizer; it is required, harness-level configuration (spec §3, §7)");
    }
    if (renderer == null) {
      throw new NullPointerException(
          "renderer must not be null — the typed door (Nessy.harness(Class, HarnessCustomizer))"
              + " requires .renderer(ObservationRenderer) inside the customizer; unlike the String"
              + " door, which presets one, the typed door has no default renderer for O");
    }
    ModelSettings effectiveSettings = settings != null ? settings : ModelSettings.defaults();
    ObservationRenderer<O> effectiveRenderer = renderer;
    ObjectMapper pinned = Codecs.copyAndPin(objectMapper);
    // the String door (Nessy.harness()) presets backlogCodec to STRING_CODEC; the typed door
    // (Nessy.harness(Class)) always derives it here — no override seam (parked for James).
    Codec<O> effectiveBacklogCodec =
        backlogCodec != null ? backlogCodec : Codec.json(pinned, observationType);
    // The harness is immortal, not closeable (spec §4): an owned executor here lives exactly as
    // long as the process, same as the worker's daemon heartbeat — there is no lifecycle door to
    // shut it down through, and none is needed.
    Executor exec = executor != null ? executor : Executors.newVirtualThreadPerTaskExecutor();
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
                effectiveSubstrate, id, backlogCapacity, effectiveBacklogCodec, pinned);
    DurableComputationBackend effectiveBackend =
        backend != null ? backend : new SubstrateComputations(effectiveSubstrate, pinned);
    AgentObserver effectiveAgentObserver =
        agentObserver != null ? agentObserver : new TurnNarrationAdapter(turnObserver);
    // Fix round 1 M1: snapshot these three fields into locals so the two executor factory
    // lambdas below close over values captured at this atomic-construction moment, not over
    // `this` fields a later mutation (there is none in practice — the config never escapes — but
    // the lambdas should say what they mean).
    Model effectiveModel = model;
    String effectiveSystemPrompt = systemPrompt;
    TurnObserver effectiveTurnObserver = turnObserver;
    Consumer<ApprovalRequest> effectiveApprovalNotifier = approvalNotifier;

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
            scopeMemory ->
                new ProviderModelCallExecutor(
                    effectiveModel,
                    effectiveSystemPrompt,
                    effectiveSettings,
                    registry,
                    scopeMemory,
                    effectiveTurnObserver,
                    exec),
            scopeId ->
                new RegistryToolCallExecutor(
                    registry,
                    agentType,
                    scopeId,
                    effectiveTurnObserver,
                    exec,
                    new ComputationDeferredToolCallPolicy(effectiveBackend, pinned),
                    new ComputationApprover(effectiveBackend, effectiveApprovalNotifier, pinned),
                    pinned),
            effectiveSubstrate,
            pinned,
            effectiveBackend);

    return harness;
  }
}
