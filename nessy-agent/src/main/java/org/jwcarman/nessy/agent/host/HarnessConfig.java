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
import java.time.InstantSource;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.continuum.Continuum;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.DefaultContinuum;
import org.jwcarman.continuum.memory.InMemoryContinuumRepository;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.ComputationApprover;
import org.jwcarman.nessy.agent.ComputationDeferredToolCallPolicy;
import org.jwcarman.nessy.agent.DecisionCodec;
import org.jwcarman.nessy.agent.DispatchIndex;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.Kinds;
import org.jwcarman.nessy.agent.Routing;
import org.jwcarman.nessy.agent.StalenessPolicy;
import org.jwcarman.nessy.agent.backlog.SubstrateBacklog;
import org.jwcarman.nessy.agent.codec.Codecs;
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
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A CONFIG, not a builder (harness-first spec §2, renamed from the pre-customizer fluent builder
 * this reform replaced): fluent setters, no public {@code build()} — the same {@link
 * org.jwcarman.nessy.api.tool.ToolConfig} idiom, describing a kept, immortal {@link Harness} rather
 * than a host. Reached only through {@link Nessy#harness(HarnessCustomizer)} or {@link
 * Nessy#harness(Class, HarnessCustomizer)}, which alone turn a filled-in config into the {@link
 * Harness} it describes.
 */
public final class HarnessConfig<O> {

  private static final Logger log = LoggerFactory.getLogger(HarnessConfig.class);

  /**
   * The approval kind's Continuum deadline (continuum-adoption spec §7, §9, ruled): an approval
   * waiting forever is a leak; expiry arrives through the normal delivery path. Harness-level, not
   * per-tool — a per-tool override is deferred until something needs it.
   */
  private static final Duration APPROVAL_DEADLINE = Duration.ofDays(7);

  /**
   * The tool kind's default Continuum deadline (continuum-adoption spec §3, §11.2): what a tool
   * with no declared {@link org.jwcarman.nessy.api.tool.Tool#timeout()} gets stamped with — {@link
   * ComputationDeferredToolCallPolicy#onDeferred} passes a declared timeout straight through to
   * {@code create(routing, timeout)} instead, overriding this default. Continuum requires every
   * computation to carry a deadline (no deadline-less wait survives adoption, spec §3), so a tool
   * that never used to expire now does — a day is generous for an external system to answer while
   * still bounding the leak a truly abandoned computation would otherwise be.
   */
  private static final Duration DEFAULT_TOOL_DEADLINE = Duration.ofDays(1);

  private Model model;
  private ModelSettings settings;
  private String systemPrompt;
  private String typeName = "agent";
  private List<ToolGrant> grants = List.of();
  private Function<String, Memory> memoryFactory;
  private Substrate substrate;
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
  // typed door always derives its codec in finish() from the substrate's own CodecFactory over
  // observationType (codec-adoption spec §2) — no override seam (parked for James).
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
   * Fires once, point-to-point, the moment an approval computation is first asked (§4.3 amendment).
   */
  public HarnessConfig<O> approvalNotifier(Consumer<ApprovalRequest> approvalNotifier) {
    this.approvalNotifier =
        Objects.requireNonNull(approvalNotifier, "approvalNotifier must not be null");
    return this;
  }

  /**
   * The absent audience by default; every scope's turns narrate here. Front-ends spec §2: this
   * stays working exactly as before — the finished {@link Harness} composes it as one more
   * subscriber in its internal per-id fanout, alongside whatever a scope's own {@link
   * org.jwcarman.nessy.agent.Agent#subscribe} adds later. It runs last and unguarded on every
   * emission, so a throwing observer keeps its long-standing meaning ({@link TurnObserver}'s own
   * javadoc); a {@code subscribe}d observer, by contrast, is isolated — its throw is logged and
   * dropped, never propagated.
   */
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
    // typed-stores spec §1 ruling 3; codec-adoption spec §2: the substrate's own CodecFactory
    // (one Jackson2CodecFactory, held by SubstrateSupport) is the codec extension point now — the
    // default substrate is constructed over the SAME pinned mapper every other recipe here
    // threads through, so its codec factory derives byte-identical codecs to the retired
    // Codec.json(pinned, ...) call it replaces; a caller-supplied substrate carries its own
    // pinned mapper, the override door ruling 3 describes.
    Substrate effectiveSubstrate = substrate != null ? substrate : new InMemorySubstrate(pinned);
    // the String door (Nessy.harness()) presets backlogCodec to STRING_CODEC — an explicit
    // caller-supplied codec, unaffected by this seam; the typed door (Nessy.harness(Class)) now
    // derives its fallback from the substrate's own codec factory instead of a hand-rolled
    // Codec.json call (the retired .backlogCodec derivation seam).
    Codec<O> effectiveBacklogCodec =
        backlogCodec != null ? backlogCodec : effectiveSubstrate.codecs().create(observationType);
    // The harness is immortal, not closeable (spec §4): an owned executor here lives exactly as
    // long as the process, same as the worker's daemon heartbeat — there is no lifecycle door to
    // shut it down through, and none is needed.
    Executor exec = executor != null ? executor : Executors.newVirtualThreadPerTaskExecutor();
    var agentType = AgentType.of(typeName);
    ToolRegistry base = ToolRegistry.of(grants.toArray(ToolGrant[]::new));
    ToolRegistry registry = ToolRegistry.limited(base, CompletionPolicy.DURABLE);
    Function<String, Memory> effectiveMemoryFactory =
        memoryFactory != null
            ? memoryFactory
            : id -> new SubstrateMemory(effectiveSubstrate, id, pinned);
    Function<String, AgentStateStore> effectiveStoreFactory =
        id -> new SubstrateAgentStateStore(effectiveSubstrate, id, Clock.systemUTC(), pinned);
    Function<String, Backlog<O>> effectiveBacklogFactory =
        id ->
            new SubstrateBacklog<>(effectiveSubstrate, id, backlogCapacity, effectiveBacklogCodec);
    // The approval and tool kinds' own stores, on Continuum rather than Substrate (continuum-
    // adoption spec §3): wired to continuum-memory, never continuum-jdbc — both kinds still share
    // one durability tier with the scope's own Substrate state, and InMemorySubstrate is the only
    // shipped Substrate (spec §11.1) until a durable one exists.
    InMemoryContinuumRepository repository = new InMemoryContinuumRepository();
    Continuum continuum = new DefaultContinuum(repository, InstantSource.system());
    // Guard 1 (continuum-adoption spec §11.1): the two stores must share one durability tier —
    // both volatile or both durable. instanceof against InMemorySubstrate and Continuum's own
    // InMemoryContinuumRepository is crude — it cannot judge a third-party Substrate that happens
    // to be volatile for reasons of its own — but it catches the realistic mistake: wiring
    // continuum-jdbc while leaving the substrate the default in-memory one. A warning, not a
    // throw, matching how Continuum's own auto-configuration handles the equivalent situation.
    boolean substrateVolatile = effectiveSubstrate instanceof InMemorySubstrate;
    boolean computationsVolatile = repository instanceof InMemoryContinuumRepository;
    if (substrateVolatile != computationsVolatile) {
      log.warn(
          "Durability mismatch: the substrate is {} and the computation store is {}. "
              + "These must match — a durable computation store against a volatile substrate "
              + "silently drops every delivery, and the reverse hangs calls permanently.",
          substrateVolatile ? "in-memory" : "durable",
          computationsVolatile ? "in-memory" : "durable");
    }
    ContinuumClient<Decision, Routing> effectiveApprovalClient =
        continuum.client(
            Kinds.approval(agentType),
            Decision.class,
            Routing.class,
            cfg ->
                cfg.resultCodec(DecisionCodec.codec(pinned))
                    .continuationCodec(Routing.codec(pinned))
                    .deadline(APPROVAL_DEADLINE));
    // ToolResult carries no Jackson polymorphism of its own (a plain record, unlike Decision's
    // sealed Allow/Deny), so the substrate's own pinned Jackson2 codec factory binds it directly —
    // no hand-rolled codec needed the way DecisionCodec exists for the approval kind.
    ContinuumClient<ToolResult, Routing> effectiveToolClient =
        continuum.client(
            Kinds.tool(agentType),
            ToolResult.class,
            Routing.class,
            cfg ->
                cfg.resultCodec(effectiveSubstrate.codecs().create(ToolResult.class))
                    .continuationCodec(Routing.codec(pinned))
                    .deadline(DEFAULT_TOOL_DEADLINE));
    DispatchIndex effectiveDispatchIndex =
        new DispatchIndex(effectiveSubstrate, pinned, Kinds.dispatchIndex(agentType));
    // The default narrator targets the id-scoped TurnObserver Harness.observerFor(id) hands it
    // (fanout.observerFor(id)) — the ONE path AssistantSaid/TurnEnded now narrate through (front-
    // ends spec §1, Task 3's fix for Task 2's fanout gap): before this, TurnNarrationAdapter
    // targeted the raw configured turnObserver directly, bypassing the fanout entirely, so a
    // subscribe()d per-id observer never saw AssistantSaid/TurnEnded at all. A caller-supplied
    // agentObserver, by contrast, still replaces the wiring wholesale and stays id-free — the
    // factory below simply ignores the per-id TurnObserver it is handed and returns the same fixed
    // instance every time, exactly as before this change.
    Function<TurnObserver, AgentObserver> effectiveAgentObserverFactory =
        agentObserver != null ? perIdTurnObserver -> agentObserver : TurnNarrationAdapter::new;
    // Fix round 1 M1: snapshot these three fields into locals so the two executor factory
    // lambdas below close over values captured at this atomic-construction moment, not over
    // `this` fields a later mutation (there is none in practice — the config never escapes — but
    // the lambdas should say what they mean).
    Model effectiveModel = model;
    String effectiveSystemPrompt = systemPrompt;
    TurnObserver effectiveTurnObserver = turnObserver;
    Consumer<ApprovalRequest> effectiveApprovalNotifier = approvalNotifier;
    // Agent#ask's Parked-detection seam (front-ends spec §1): a plain map, not a new public type,
    // shared by reference between this notifier wrapper (built here, before Harness exists) and
    // the finished Harness (Harness.awaitApproval/cancelApprovalWait just read/write it) — the
    // only way to thread a per-id capture through the toolExecutorFactory closure below, which is
    // baked here but not actually invoked until well after Harness.of returns. Threaded through
    // the EXISTING notifier seam (no new event type): every request still reaches the caller's own
    // configured notifier exactly as before; capturing is a side effect on the way there.
    ConcurrentMap<AgentId, CompletableFuture<ApprovalRequest>> approvalWaiters =
        new ConcurrentHashMap<>();
    Consumer<ApprovalRequest> capturingApprovalNotifier =
        request -> {
          CompletableFuture<ApprovalRequest> waiting =
              approvalWaiters.remove(AgentId.of(request.agentId()));
          if (waiting != null) {
            waiting.complete(request);
          }
          effectiveApprovalNotifier.accept(request);
        };

    Harness<O> harness =
        Harness.of(
            agentType,
            effectiveRenderer,
            effectiveAgentObserverFactory,
            effectiveTurnObserver,
            true,
            stalenessPolicy,
            effectiveMemoryFactory,
            effectiveStoreFactory,
            effectiveBacklogFactory,
            (scopeMemory, scopeTurnObserver) ->
                new ProviderModelCallExecutor(
                    effectiveModel,
                    effectiveSystemPrompt,
                    effectiveSettings,
                    registry,
                    scopeMemory,
                    scopeTurnObserver,
                    exec),
            (scopeId, scopeTurnObserver) ->
                new RegistryToolCallExecutor(
                    registry,
                    agentType,
                    scopeId,
                    scopeTurnObserver,
                    exec,
                    new ComputationDeferredToolCallPolicy(
                        effectiveDispatchIndex, effectiveToolClient),
                    new ComputationApprover(
                        effectiveApprovalClient,
                        effectiveDispatchIndex,
                        effectiveStoreFactory.apply(scopeId.value()),
                        capturingApprovalNotifier),
                    pinned),
            effectiveSubstrate,
            pinned,
            effectiveApprovalClient,
            effectiveDispatchIndex,
            effectiveToolClient,
            approvalWaiters);

    return harness;
  }
}
