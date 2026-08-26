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
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.continuum.Continuum;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.DefaultContinuum;
import org.jwcarman.continuum.memory.InMemoryContinuumRepository;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.ApprovalCodec;
import org.jwcarman.nessy.agent.ApprovalRouting;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.Kinds;
import org.jwcarman.nessy.agent.Routing;
import org.jwcarman.nessy.agent.StalenessPolicy;
import org.jwcarman.nessy.agent.TurnOutcome;
import org.jwcarman.nessy.agent.backlog.SubstrateBacklog;
import org.jwcarman.nessy.agent.codec.Codecs;
import org.jwcarman.nessy.agent.memory.SubstrateMemory;
import org.jwcarman.nessy.agent.model.ProviderModelCallExecutor;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.HarnessObserver;
import org.jwcarman.nessy.agent.spi.ObservationRenderer;
import org.jwcarman.nessy.agent.store.AgentPhaseStore;
import org.jwcarman.nessy.agent.store.SubstrateAgentPhaseStore;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.Memory;
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
   * with no declared {@link org.jwcarman.nessy.api.tool.Tool#timeout()} gets stamped with — {@code
   * ComputationToolContext#defer()} passes a declared timeout straight through to {@code
   * create(routing, timeout)} instead, overriding this default. Continuum requires every
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
  private Continuum continuum;
  private TurnObserver turnObserver = TurnObserver.noop();
  // Additive: every harnessObserver(...) call appends one more subscriber. The finished Harness
  // always subscribes its own default narrator FIRST, whatever is in here, so AssistantSaid/
  // TurnEnded narrate the way the CLI door always has — an application's observer is one more
  // listener, never a replacement.
  private final List<HarnessObserver> harnessObservers = new ArrayList<>();
  private Executor executor;
  private int backlogCapacity = 1024;
  private StalenessPolicy stalenessPolicy = StalenessPolicy.after(Duration.ofMinutes(5));
  private ObjectMapper objectMapper = new ObjectMapper();
  private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;
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
   * <p><b>Two harnesses sharing a {@code type} must share both {@link #substrate(Substrate)} and
   * {@link #continuum(Continuum)}, or neither.</b> Two harnesses sharing a type and a substrate
   * write one set of scopes, so they must share the Continuum those scopes name — but the failure
   * when they do not is loud now (approval-lifecycle spec §8): an answer arrives for a computation
   * no phase names and is ignored, rather than draining into a scope that reads {@code Idle}.
   * Continuum's kinds are {@code approval/<agentType>} and {@code tool/<agentType>}, drained with
   * no substrate discriminator: harnesses sharing type and Continuum over DIFFERENT substrates
   * cross-drain, one claiming a delivery for a scope that exists only in the other's substrate and
   * folding it against a scope that reads {@code Idle} — the call hangs forever, silently. Share
   * all three; or use distinct types; or use a distinct substrate AND a distinct Continuum. Sharing
   * exactly one store is never right.
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
   * The fact-stream seam (agentic-o11y spec §3): the observer subscribed to the one stream both
   * fold sites publish through, so {@code applied}, {@code ignored}, {@code applyFailed}, {@code
   * renderFailed}, {@code reFired} and {@code observationRequeued} land here for EVERY scope this
   * harness runs — a durable delivery's fold included, which narrated nothing at all before the
   * stream existed.
   *
   * <p><b>ADDITIVE</b> (amended 2026-08-26, watchman branch — James: "I don't want only one
   * observer"). Each call subscribes one more observer, in call order, and no call displaces
   * anything: the narrating observer ({@code TurnNarrationAdapter}) is always subscribed first, so
   * {@code AssistantSaid}/{@code TurnEnded} narrate the way the CLI door always has, whether or not
   * an application names observers of its own. Call this once per listener — a projection, a
   * metrics bridge, an audit log — rather than composing them into one by hand.
   *
   * <p>One observer instance serves every scope rather than being stamped per id, which is why its
   * methods lead with the {@link org.jwcarman.nessy.agent.AgentId} the fact is about. Subscribers
   * are isolated: a throw is logged and dropped, never propagated into the fold and never kept from
   * the other subscribers.
   */
  public HarnessConfig<O> harnessObserver(HarnessObserver harnessObserver) {
    this.harnessObservers.add(
        Objects.requireNonNull(harnessObserver, "harnessObserver must not be null"));
    return this;
  }

  /**
   * The computation store for the approval and tool kinds (continuum-adoption spec §3): the {@link
   * Continuum} whose deliveries this harness's pumps claim and whose computations its desks decide.
   * When omitted, {@link #finish()} mints a private, in-memory one — computations then live exactly
   * as long as this harness and are visible to no other. Supply one to change either property: a
   * {@code continuum-jdbc}-backed Continuum makes parked calls survive the process, and the SAME
   * instance handed to two harnesses that also share a type and a substrate lets either one deliver
   * what the other parked. Pair it with a substrate of the same durability tier — a durable
   * computation store over a volatile substrate silently drops every delivery onto a scope restored
   * to {@code Idle}; the reverse hangs calls. And never share it between two harnesses of one type
   * over DIFFERENT substrates: its kinds are keyed by type alone, so one harness would claim and
   * drop deliveries meant for scopes that exist only in the other's substrate.
   */
  public HarnessConfig<O> continuum(Continuum continuum) {
    this.continuum = Objects.requireNonNull(continuum, "continuum must not be null");
    return this;
  }

  /**
   * A caller-supplied executor, never closed by the harness; when omitted, {@link #finish()}
   * creates a virtual-thread-per-task executor the harness owns and closes in {@code
   * Harness#shutdown()}, after its pumps.
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
   * The one observability seam (agentic-o11y spec §0, §4): where this harness's spans and counters
   * are recorded. Default {@link ObservationRegistry#NOOP} — absent an application-supplied
   * registry, nothing costs anything and the roster is inert.
   *
   * <p>What lands here is Micrometer {@link io.micrometer.observation.Observation}s named per the
   * OpenTelemetry GenAI semantic conventions: {@code invoke_agent} per segment, {@code chat} per
   * model call (carrying {@code gen_ai.usage.input_tokens}/{@code output_tokens}), {@code
   * execute_tool} per tool run, and Nessy's own {@code nessy.approval.wait}/{@code nessy.tool.wait}
   * dwell spans and three engine counters. Exporters, the OTel tracing bridge, OTLP and any {@code
   * MeterRegistry} live in the application, never in the harness: supply a registry with the
   * handlers you want. In particular the semconv {@code gen_ai.client.token.usage} metric is the
   * application's to record — an {@code ObservationRegistry} times observations but cannot record a
   * value histogram, so the token counts ride the {@code chat} observation as key-values for an
   * application-side {@code ObservationHandler} to read on stop (spec §1.2).
   */
  public HarnessConfig<O> observationRegistry(ObservationRegistry observationRegistry) {
    this.observationRegistry =
        Objects.requireNonNull(observationRegistry, "observationRegistry must not be null");
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
    // An executor created here because the caller supplied none is the harness's own: it is handed
    // to Harness.of as `ownedExecutor` and closed by Harness#shutdown() after the scheduler's
    // pumps, the same door that already closes the ComputationScheduler (continuum-adoption spec
    // §7). A caller-supplied executor is never the harness's to close, so `ownedExecutor` is null
    // for it and only `exec` — what the model and tool executors below capture — carries it.
    ExecutorService ownedExecutor =
        executor == null ? Executors.newVirtualThreadPerTaskExecutor() : null;
    Executor exec = executor != null ? executor : ownedExecutor;
    var agentType = AgentType.of(typeName);
    ToolRegistry base = ToolRegistry.of(grants.toArray(ToolGrant[]::new));
    ToolRegistry registry = ToolRegistry.limited(base, CompletionPolicy.DURABLE);
    Function<String, Memory> boundMemoryFactory =
        memoryFactory != null
            ? memoryFactory
            : id -> new SubstrateMemory(effectiveSubstrate, id, pinned);
    Function<String, AgentPhaseStore> effectiveStoreFactory =
        id -> new SubstrateAgentPhaseStore(effectiveSubstrate, id, Clock.systemUTC(), pinned);
    Function<String, Backlog<O>> effectiveBacklogFactory =
        id ->
            new SubstrateBacklog<>(effectiveSubstrate, id, backlogCapacity, effectiveBacklogCodec);
    // The approval and tool kinds' own store, on Continuum rather than Substrate (continuum-
    // adoption spec §3). Caller-supplied through continuum(Continuum) — a continuum-jdbc-backed
    // one, or one instance shared by several harnesses — or, when omitted, minted here: private,
    // in-memory, and gone with this harness.
    boolean computationsMinted = continuum == null;
    Continuum effectiveContinuum =
        computationsMinted
            ? new DefaultContinuum(new InMemoryContinuumRepository(), InstantSource.system())
            : continuum;
    // Guard 1 (continuum-adoption spec §11.1): the two stores must share one durability tier.
    // This warns only when it KNOWS they do not — a substrate that is not InMemorySubstrate is
    // durable, and a computation store minted here is volatile by construction. That is the hang
    // direction: a parked call's delivery dies with the process while the scope it belonged to
    // survives in the substrate. A caller-supplied Continuum is not inspected — the harness cannot
    // tell a durable repository from a volatile one through the Continuum interface, and a shared
    // in-memory Continuum over an in-memory substrate is a legitimate shape — so the caller who
    // supplies one is trusted to have matched the tiers. A warning, not a throw, matching how
    // Continuum's own auto-configuration handles the equivalent situation.
    boolean substrateDurable = !(effectiveSubstrate instanceof InMemorySubstrate);
    if (substrateDurable && computationsMinted) {
      log.warn(
          "Durability mismatch: the substrate is durable but no Continuum was supplied, so the"
              + " computation store is in-memory. A parked call's delivery will not survive the"
              + " process while the scope it belongs to will — supply a durable Continuum via"
              + " continuum(...) to match.");
    }
    ContinuumClient<Approval, ApprovalRouting> effectiveApprovalClient =
        effectiveContinuum.client(
            Kinds.approval(agentType),
            Approval.class,
            ApprovalRouting.class,
            cfg ->
                cfg.resultCodec(ApprovalCodec.codec(pinned))
                    .continuationCodec(ApprovalRouting.codec(pinned))
                    .deadline(APPROVAL_DEADLINE));
    // ToolResult carries no Jackson polymorphism of its own (a plain record, unlike Approval's
    // sealed Approved/Denied), so the substrate's own pinned Jackson2 codec factory binds it
    // directly — no hand-rolled codec needed the way ApprovalCodec exists for the approval kind.
    ContinuumClient<ToolResult, Routing> effectiveToolClient =
        effectiveContinuum.client(
            Kinds.tool(agentType),
            ToolResult.class,
            Routing.class,
            cfg ->
                cfg.resultCodec(effectiveSubstrate.codecs().create(ToolResult.class))
                    .continuationCodec(Routing.codec(pinned))
                    .deadline(DEFAULT_TOOL_DEADLINE));
    // Fix round 1 M1: snapshot these three fields into locals so the two executor factory
    // lambdas below close over values captured at this atomic-construction moment, not over
    // `this` fields a later mutation (there is none in practice — the config never escapes — but
    // the lambdas should say what they mean).
    Model effectiveModel = model;
    String effectiveSystemPrompt = systemPrompt;
    TurnObserver effectiveTurnObserver = turnObserver;
    // Agent#ask's Parked-detection seam (front-ends spec §1): a plain map, not a new public type,
    // handed to the finished Harness, whose ApprovalDeferred fold completes whichever wait is
    // registered for that id (Harness#parked).
    ConcurrentMap<AgentId, CompletableFuture<TurnOutcome.Parked>> approvalWaiters =
        new ConcurrentHashMap<>();
    // The open invoke_agent span per scope (agentic-o11y spec §3.2) — like approvalWaiters above, a
    // plain map rather than a new public type. The harness's package-private Observations writes it
    // as segments open and close; the two executor factories below read it to parent their own chat
    // and execute_tool spans, because Micrometer's scope does not follow executor.execute onto
    // another virtual thread. It is created HERE, not inside the harness, for the one reason
    // approvalWaiters is: the executor factories are lambdas this method closes over, and they need
    // the same instance the harness will use.
    ConcurrentMap<AgentId, Observation> openSegments = new ConcurrentHashMap<>();
    // The ONE site a per-scope Memory is built (James 2026-08-26: "add memory spans"), so every
    // recall and every remember is described — including the model executor's own recall below,
    // which is the call that decides how big each prompt is. Wrapping here rather than inside
    // SubstrateMemory covers a caller-supplied memoryFactory too: a vector store, a Redis view, a
    // custom schema, all instrumented without knowing they are. Inert at ObservationRegistry.NOOP.
    Function<String, Memory> effectiveMemoryFactory =
        id ->
            new ObservingMemory(
                boundMemoryFactory.apply(id),
                observationRegistry,
                agentType.name(),
                () -> openSegments.get(AgentId.of(id)));

    Harness<O> harness =
        Harness.of(
            agentType,
            effectiveModel.provider(),
            effectiveModel.id(),
            effectiveRenderer,
            List.copyOf(harnessObservers),
            effectiveTurnObserver,
            true,
            stalenessPolicy,
            effectiveMemoryFactory,
            effectiveStoreFactory,
            effectiveBacklogFactory,
            (scopeId, scopeTurnObserver) ->
                new ProviderModelCallExecutor(
                    effectiveModel,
                    effectiveSystemPrompt,
                    effectiveSettings,
                    registry,
                    effectiveMemoryFactory.apply(scopeId.value()),
                    scopeTurnObserver,
                    exec,
                    observationRegistry,
                    () -> openSegments.get(scopeId)),
            (scopeId, scopeTurnObserver) ->
                new RegistryToolCallExecutor(
                    registry,
                    agentType,
                    scopeId,
                    scopeTurnObserver,
                    exec,
                    effectiveApprovalClient,
                    effectiveToolClient,
                    pinned,
                    observationRegistry,
                    () -> openSegments.get(scopeId)),
            effectiveSubstrate,
            pinned,
            effectiveApprovalClient,
            effectiveToolClient,
            approvalWaiters,
            observationRegistry,
            openSegments,
            ownedExecutor);

    return harness;
  }
}
