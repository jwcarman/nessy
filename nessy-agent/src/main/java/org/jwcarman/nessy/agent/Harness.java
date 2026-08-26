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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.nessy.agent.narrate.TurnNarrationAdapter;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.HarnessObserver;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.ObservationRenderer;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.turn.Subscription;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The recipe compiled (§10.11), plus its life-support (harness-first spec §4): one per {@link
 * AgentType}, id-free, immortal. Every id-free collaborator (the renderer, the model-call and
 * tool-call guts, the drain policy, the staleness policy) lives here exactly once, built by {@link
 * org.jwcarman.nessy.agent.host.Nessy}'s builders and never reconstructed per delivery — {@code
 * Nessy} is the harness's only compiler; there is no other door to this class. The {@link
 * HarnessObserver} is no longer among the per-scope collaborators at all (agentic-o11y spec §3):
 * this harness owns one fact stream ({@link #facts()}) that both fold sites publish through, and
 * the configured observer — or, absent one, the default narrator — is simply its first subscriber.
 * {@link #bind(AgentId)} stamps a fresh, id-specific {@link DefaultAgent} every time; binding is
 * cheap because the factories it calls hand back views over shared substrate, never build new
 * machinery.
 *
 * <p>The host tier's machinery moved in here (harness-first spec §4): the {@link DeliveryWorker}
 * and the {@link ApprovalDesk}/{@link CompletionDesk} are constructed by this class's own
 * constructor, exactly as the now-deleted long-running host shim used to build them — {@code
 * Nessy}'s builders now hand this constructor the substrate, mapper, and durable computation
 * backend those doors need, instead of wiring the worker themselves. {@link #of} then registers the
 * worker's six pumps — deliver, expire, purge, once each for the approval and tool kinds
 * (continuum-adoption spec §7) — onto a {@link ComputationScheduler} it mints for this harness,
 * deliberately after the constructor returns, not inside it (see {@link #of}'s own comment on why).
 * There is no reaper any more — that Substrate-outbox-scanning machinery was retired in the
 * migration onto Continuum (continuum-adoption spec §6); Continuum's own delivery mechanism claims,
 * leases, and expires computations itself. The harness is immortal, not closeable: {@link
 * #shutdown()} is the one undecorated lifecycle door, and it exists for infrastructure only.
 */
public final class Harness<O> {

  private final AgentType type;
  private final ObservationRenderer<O> renderer;
  private final boolean drainOnIdle;
  private final StalenessPolicy stalenessPolicy;
  private final Function<String, Memory> memoryFactory;
  private final Function<String, AgentStateStore> storeFactory;
  private final Function<String, Backlog<O>> backlogFactory;
  private final BiFunction<AgentId, TurnObserver, ModelCallExecutor> modelExecutorFactory;
  private final BiFunction<AgentId, TurnObserver, ToolCallExecutor> toolExecutorFactory;
  private final TurnFanout fanout;
  private final FactFanout facts;
  private final ConcurrentMap<AgentId, CompletableFuture<TurnOutcome.Parked>> approvalWaiters;
  private final Observations observations;
  private final DeliveryWorker<O> worker;
  private final ApprovalDesk approvals;
  private final CompletionDesk completions;
  private final ComputationScheduler scheduler;

  /**
   * The executor this harness created for itself because its door was handed none, and therefore
   * the one {@link #shutdown()} closes — or null when the caller supplied an executor, which is
   * never the harness's to close.
   */
  private final ExecutorService ownedExecutor;

  private Harness(
      AgentType type,
      ObservationRenderer<O> renderer,
      HarnessObserver harnessObserver,
      TurnObserver turnObserver,
      boolean drainOnIdle,
      StalenessPolicy stalenessPolicy,
      Function<String, Memory> memoryFactory,
      Function<String, AgentStateStore> storeFactory,
      Function<String, Backlog<O>> backlogFactory,
      BiFunction<AgentId, TurnObserver, ModelCallExecutor> modelExecutorFactory,
      BiFunction<AgentId, TurnObserver, ToolCallExecutor> toolExecutorFactory,
      Substrate substrate,
      ObjectMapper mapper,
      ContinuumClient<Approval, ApprovalRouting> approvalClient,
      ContinuumClient<ToolResult, Routing> toolClient,
      ConcurrentMap<AgentId, CompletableFuture<TurnOutcome.Parked>> approvalWaiters,
      ObservationRegistry observationRegistry,
      ConcurrentMap<AgentId, Observation> openSegments,
      ComputationScheduler scheduler,
      ExecutorService ownedExecutor) {
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    this.fanout =
        new TurnFanout(Objects.requireNonNull(turnObserver, "turnObserver must not be null"));
    this.facts = new FactFanout();
    // The configured observer, or — when the caller supplied none — the default narrator, which
    // resolves each fact's TurnObserver from the id it is handed (agentic-o11y spec §3). Either
    // way it is the stream's FIRST subscriber, so it narrates ahead of the observability bridge.
    this.facts.subscribe(
        harnessObserver != null ? harnessObserver : new TurnNarrationAdapter(fanout::observerFor));
    this.approvalWaiters =
        Objects.requireNonNull(approvalWaiters, "approvalWaiters must not be null");
    Objects.requireNonNull(observationRegistry, "observationRegistry must not be null");
    // The observability bridge is a subscriber like any other (agentic-o11y spec §3.1): segments,
    // both waits and the three counters are all functions of what the fold published. The two
    // spans it cannot derive — chat and execute_tool — are opened by the executors, which reach
    // this same object through observations().
    this.observations =
        new Observations(
            observationRegistry,
            type,
            Objects.requireNonNull(openSegments, "openSegments must not be null"));
    this.facts.subscribe(this.observations);
    this.drainOnIdle = drainOnIdle;
    this.stalenessPolicy =
        Objects.requireNonNull(stalenessPolicy, "stalenessPolicy must not be null");
    this.memoryFactory = Objects.requireNonNull(memoryFactory, "memoryFactory must not be null");
    this.storeFactory = Objects.requireNonNull(storeFactory, "storeFactory must not be null");
    this.backlogFactory = Objects.requireNonNull(backlogFactory, "backlogFactory must not be null");
    this.modelExecutorFactory =
        Objects.requireNonNull(modelExecutorFactory, "modelExecutorFactory must not be null");
    this.toolExecutorFactory =
        Objects.requireNonNull(toolExecutorFactory, "toolExecutorFactory must not be null");
    Objects.requireNonNull(substrate, "substrate must not be null");
    Objects.requireNonNull(mapper, "mapper must not be null");
    Objects.requireNonNull(approvalClient, "approvalClient must not be null");
    Objects.requireNonNull(toolClient, "toolClient must not be null");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    this.ownedExecutor = ownedExecutor;
    this.worker =
        new DeliveryWorker<>(
            substrate, mapper, this, this::resolve, scheduler, approvalClient, toolClient);
    this.approvals = new ApprovalDesk(approvalClient, storeFactory, worker::nudge);
    this.completions = new CompletionDesk(toolClient, worker::nudge);
  }

  /**
   * The one caller of this private constructor: {@link org.jwcarman.nessy.agent.host.Nessy} is the
   * harness's only compiler — the doors are {@code Nessy.harness(...)} (×2, the String and typed
   * customizer forms) and {@code Nessy.cli()}, not this factory, so this stays a plain composition
   * point rather than growing fluent setters of its own. No builder exists in user hands (spec §2):
   * each door hands a customizer a fresh config and turns it into a {@link Harness} atomically.
   * {@code substrate}, {@code mapper}, {@code approvalClient}, and {@code toolClient} (the approval
   * and tool kinds' own Continuum clients, continuum-adoption spec §3) are the life-support this
   * constructor owns (harness-first spec §4): the worker and desks it wires used to be a builder's
   * job.
   *
   * <p>{@code harnessObserver} is the fact stream's first subscriber (agentic-o11y spec §3), and it
   * is NULLABLE by design — the same convention {@code ownedExecutor} uses below: null means "this
   * harness subscribes the default narrating observer it builds over its own turn fanout", which is
   * what {@code HarnessConfig} passes whenever the application named none. There is no factory any
   * more; one observer serves every scope, told which one by the {@link AgentId} each call carries.
   *
   * <p>{@code openSegments} is where the observability bridge publishes the open {@code
   * invoke_agent} observation per scope, and where the model- and tool-call executors read it to
   * parent their own spans (spec §3.2) — a plain map handed in from outside for the same reason
   * {@code approvalWaiters} is: it belongs to both sides, and neither is a new public type.
   */
  public static <O> Harness<O> of(
      AgentType type,
      ObservationRenderer<O> renderer,
      HarnessObserver harnessObserver,
      TurnObserver turnObserver,
      boolean drainOnIdle,
      StalenessPolicy stalenessPolicy,
      Function<String, Memory> memoryFactory,
      Function<String, AgentStateStore> storeFactory,
      Function<String, Backlog<O>> backlogFactory,
      BiFunction<AgentId, TurnObserver, ModelCallExecutor> modelExecutorFactory,
      BiFunction<AgentId, TurnObserver, ToolCallExecutor> toolExecutorFactory,
      Substrate substrate,
      ObjectMapper mapper,
      ContinuumClient<Approval, ApprovalRouting> approvalClient,
      ContinuumClient<ToolResult, Routing> toolClient,
      ConcurrentMap<AgentId, CompletableFuture<TurnOutcome.Parked>> approvalWaiters,
      ObservationRegistry observationRegistry,
      ConcurrentMap<AgentId, Observation> openSegments) {
    return of(
        type,
        renderer,
        harnessObserver,
        turnObserver,
        drainOnIdle,
        stalenessPolicy,
        memoryFactory,
        storeFactory,
        backlogFactory,
        modelExecutorFactory,
        toolExecutorFactory,
        substrate,
        mapper,
        approvalClient,
        toolClient,
        approvalWaiters,
        observationRegistry,
        openSegments,
        null);
  }

  /**
   * The form the {@code Nessy} doors use: {@code ownedExecutor} is an executor the door created
   * because its caller supplied none, and which this harness therefore owns and closes in {@link
   * #shutdown()}. Pass null when the caller supplied the executor the factories capture — a
   * caller-supplied executor is never the harness's to close.
   */
  public static <O> Harness<O> of(
      AgentType type,
      ObservationRenderer<O> renderer,
      HarnessObserver harnessObserver,
      TurnObserver turnObserver,
      boolean drainOnIdle,
      StalenessPolicy stalenessPolicy,
      Function<String, Memory> memoryFactory,
      Function<String, AgentStateStore> storeFactory,
      Function<String, Backlog<O>> backlogFactory,
      BiFunction<AgentId, TurnObserver, ModelCallExecutor> modelExecutorFactory,
      BiFunction<AgentId, TurnObserver, ToolCallExecutor> toolExecutorFactory,
      Substrate substrate,
      ObjectMapper mapper,
      ContinuumClient<Approval, ApprovalRouting> approvalClient,
      ContinuumClient<ToolResult, Routing> toolClient,
      ConcurrentMap<AgentId, CompletableFuture<TurnOutcome.Parked>> approvalWaiters,
      ObservationRegistry observationRegistry,
      ConcurrentMap<AgentId, Observation> openSegments,
      ExecutorService ownedExecutor) {
    // Constructed here, not shared across separate Harness.of(...) calls (continuum-adoption spec
    // §7 leaves that wider sharing to a future task): one small pool per harness, replacing the
    // one-heartbeat-thread-per-harness this superseded.
    ComputationScheduler scheduler = new ComputationScheduler();
    Harness<O> harness =
        new Harness<>(
            type,
            renderer,
            harnessObserver,
            turnObserver,
            drainOnIdle,
            stalenessPolicy,
            memoryFactory,
            storeFactory,
            backlogFactory,
            modelExecutorFactory,
            toolExecutorFactory,
            substrate,
            mapper,
            approvalClient,
            toolClient,
            approvalWaiters,
            observationRegistry,
            openSegments,
            scheduler,
            ownedExecutor);
    // Registered here, after the constructor returns, not inside it: a scheduled pump reads
    // `harness.worker` the instant it first fires, and registering from inside a constructor risks
    // handing a background thread a `this` reference before the object is fully and safely
    // published to other threads (no-`this`-escape). Registering after `new Harness<>(...)` returns
    // guarantees safe publication.
    scheduler.register(harness.worker);
    return harness;
  }

  public AgentType type() {
    return type;
  }

  /**
   * The application door (harness-first spec §4): stamps a fresh {@link Binding} for {@code id} and
   * wraps it in a fresh {@link DefaultAgent} — thin, no I/O, transient by contract, never
   * closeable. {@link Binding} itself never crosses this door; it is internal wiring (see {@link
   * #binding}).
   */
  public Agent<O> bind(AgentId id) {
    return new DefaultAgent<>(this, binding(id));
  }

  /**
   * The raw scope handle {@link #bind(AgentId)} wraps for application code — package-private
   * (harness-first spec §4, the Binding demotion): {@link Binding} is internal wiring, never
   * application vocabulary. {@link DeliveryWorker} no longer needs this door directly — its fold
   * machinery dispatches through the id-keyed {@link #modelExecutorFor(AgentId)}/{@link
   * #toolExecutorFor(AgentId)}/{@link #memoryFor(AgentId)} seams instead — and this module's
   * white-box test fixtures re-seat onto {@link #bind(AgentId)} (now that {@link AgentResolver}
   * accepts {@link Agent}) or onto those same id-keyed seams.
   */
  Binding<O> binding(AgentId id) {
    Objects.requireNonNull(id, "id must not be null");
    String rawId = id.value();
    return new Binding<>(
        id, memoryFactory.apply(rawId), storeFactory.apply(rawId), backlogFactory.apply(rawId));
  }

  /**
   * The internal {@link AgentResolver} the delivery worker binds against: a delivery already
   * carries this harness's own type by the time it reaches the worker (the type-filtered sweep,
   * spec §5, skips everything else before decoding this far), so the check below is defense in
   * depth, not the primary filter.
   */
  private DefaultAgent<?> resolve(AgentType requestedType, AgentId id) {
    if (!requestedType.equals(type)) {
      throw new IllegalArgumentException("unknown agent type: " + requestedType.name());
    }
    return new DefaultAgent<>(this, binding(id));
  }

  ObservationRenderer<O> renderer() {
    return renderer;
  }

  /**
   * The harness's one fact stream (agentic-o11y spec §3), which both fold sites publish through:
   * {@link DefaultAgent}'s synchronous shell and {@link DeliveryWorker}'s durable one. It replaces
   * the per-scope {@code HarnessObserver} a factory used to stamp for each id — an observer is a
   * harness-level subscriber now, told which scope each fact belongs to by the leading {@link
   * AgentId} its methods carry.
   */
  FactFanout facts() {
    return facts;
  }

  /**
   * The observability bridge this harness built over {@code HarnessConfig#observationRegistry} —
   * the two spans that cannot be derived from the stream, {@code chat} and {@code execute_tool},
   * are opened against it by the model- and tool-call executors (agentic-o11y spec §3.1).
   */
  Observations observations() {
    return observations;
  }

  /**
   * Subscribes {@code observer} to the fact stream (agentic-o11y spec §3) — the {@link
   * HarnessObserver} overload beside {@link #subscribe(AgentId, TurnObserver)}. Package-private for
   * the same reason that one is: {@code HarnessConfig#harnessObserver} is application code's door,
   * and the public roster stops at {@link #type()}, {@link #bind(AgentId)}, {@link #approvals()},
   * {@link #completions()}, and {@link #shutdown()}.
   *
   * <p>Subscribers are isolated: a throw is logged and dropped, never propagated into the fold.
   */
  Subscription subscribe(HarnessObserver observer) {
    return facts.subscribe(observer);
  }

  /**
   * {@link DefaultAgent#ask}'s Parked-detection seam (front-ends spec §1): registers a fresh,
   * unresolved wait for the next park on {@code id} — completed by {@link #parked(AgentId,
   * TurnOutcome.Parked)}, which the {@code ApprovalDeferred} fold calls once the phase names the
   * ask. Registering before {@code tell} avoids the obvious race; a stale, never-completed
   * registration left behind by a turn that replied or failed instead of parking is the caller's
   * job to retire via {@link #cancelApprovalWait}.
   *
   * <p><b>One in-flight registration per id</b> (fix round 2, I2b): a {@code putIfAbsent}-style
   * guard refuses a SECOND registration for an id that already has one live, throwing rather than
   * silently overwriting it — an overwrite would orphan the first caller's waiter forever (nothing
   * would ever complete it, since only whichever registration is CURRENTLY in the map is ever
   * completed). This mirrors the retired {@code CliAgent}'s own one-turn-in-flight precedent for
   * the same reason: a second concurrent {@link DefaultAgent#ask} on one id is a caller bug, not a
   * queueable request.
   *
   * @throws IllegalStateException if {@code id} already has a live, uncompleted registration
   */
  CompletableFuture<TurnOutcome.Parked> awaitApproval(AgentId id) {
    Objects.requireNonNull(id, "id must not be null");
    CompletableFuture<TurnOutcome.Parked> future = new CompletableFuture<>();
    CompletableFuture<TurnOutcome.Parked> existing = approvalWaiters.putIfAbsent(id, future);
    if (existing != null) {
      throw new IllegalStateException("a previous ask is still in flight for this id");
    }
    return future;
  }

  /**
   * The park, as a fact (approval-lifecycle spec §1.3): completes {@code id}'s live wait, if it has
   * one, with the question the fold just recorded. Called from the {@code ApprovalDeferred} fold
   * itself, so a caller learns of the park only once the phase names it.
   */
  void parked(AgentId id, TurnOutcome.Parked parked) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(parked, "parked must not be null");
    CompletableFuture<TurnOutcome.Parked> waiting = approvalWaiters.remove(id);
    if (waiting != null) {
      waiting.complete(parked);
    }
  }

  /**
   * Retires {@code id}'s registration from {@link #awaitApproval(AgentId)} — but only if it is
   * still exactly {@code future}: a registration {@link #parked} already completed and removed is
   * left alone rather than torn out from under whatever completed it. (A LATER {@code ask} on the
   * same id can no longer have replaced it first — {@link #awaitApproval} refuses a second live
   * registration outright, fix round 2, I2b.)
   */
  void cancelApprovalWait(AgentId id, CompletableFuture<TurnOutcome.Parked> future) {
    approvalWaiters.remove(id, future);
  }

  boolean drainOnIdle() {
    return drainOnIdle;
  }

  StalenessPolicy stalenessPolicy() {
    return stalenessPolicy;
  }

  /**
   * The per-scope model executor for {@code binding} — a plain field-holding object (§10.11).
   * Package-private: {@link DefaultAgent} is the one caller left; the delivery worker dispatches
   * through {@link #modelExecutorFor(AgentId)} instead (harness-first spec §4, the Binding
   * demotion).
   */
  ModelCallExecutor modelExecutor(Binding<O> binding) {
    return modelExecutorFor(binding.id());
  }

  /**
   * The per-scope tool executor for {@code binding} — a plain field-holding object (§10.11).
   * Package-private for the same reason as {@link #modelExecutor(Binding)}.
   */
  ToolCallExecutor toolExecutor(Binding<O> binding) {
    return toolExecutorFactory.apply(binding.id(), fanout.observerFor(binding.id()));
  }

  /**
   * The id-keyed seam {@link DeliveryWorker} dispatches model calls through (harness-first spec §4,
   * the Binding demotion): equivalent to {@code modelExecutor(binding(id))}, without exposing
   * {@link Binding} across the package line. The factory is keyed by {@link AgentId} rather than
   * {@link Memory} (agentic-o11y spec §3.2): its {@code chat} span must be parented to the open
   * segment of a NAMED scope, and the scope's {@code Memory} is a view the factory resolves for
   * itself from the very same memory factory this class would have called. Package-private by
   * design (fix round F2): the worker's own seam, not a door — the public roster stops at {@link
   * #type()}, {@link #bind(AgentId)}, {@link #approvals()}, {@link #completions()}, and {@link
   * #shutdown()}.
   */
  ModelCallExecutor modelExecutorFor(AgentId id) {
    return modelExecutorFactory.apply(id, fanout.observerFor(id));
  }

  /**
   * The id-keyed seam {@link DeliveryWorker} dispatches tool calls through (harness-first spec §4,
   * the Binding demotion): equivalent to {@code toolExecutor(binding(id))}, without exposing {@link
   * Binding} across the package line — the tool executor factory only ever needed the scope's
   * {@link AgentId} itself. Package-private by design (fix round F2): the worker's own seam, not a
   * door — the public roster stops at {@link #type()}, {@link #bind(AgentId)}, {@link
   * #approvals()}, {@link #completions()}, and {@link #shutdown()}.
   */
  ToolCallExecutor toolExecutorFor(AgentId id) {
    return toolExecutorFactory.apply(id, fanout.observerFor(id));
  }

  /**
   * The id-keyed seam {@link DeliveryWorker} remembers a scope's tool-delivery facts through
   * (remembrance spec §1) — equivalent to {@code binding(id).memory()}, without exposing {@link
   * Binding} across the package line. Package-private by design (fix round F2): the worker's own
   * seam, not a door — the public roster stops at {@link #type()}, {@link #bind(AgentId)}, {@link
   * #approvals()}, {@link #completions()}, and {@link #shutdown()}.
   */
  Memory memoryFor(AgentId id) {
    Objects.requireNonNull(id, "id must not be null");
    return memoryFactory.apply(id.value());
  }

  /**
   * {@link Agent#subscribe(TurnObserver)}'s harness-side implementation (front-ends spec §2):
   * routes into {@code id}'s slice of the internal {@link TurnFanout} registry every model- and
   * tool-call executor this harness hands out already narrates through (see {@link
   * #modelExecutorFor(AgentId)}/{@link #toolExecutorFor(AgentId)}, and {@link #modelExecutor
   * (Binding)}/{@link #toolExecutor(Binding)}). Package-private — {@link DefaultAgent} is the only
   * caller; application code reaches this only through {@link #bind(AgentId)}'s {@link
   * Agent#subscribe(TurnObserver)} — the public roster stops at {@link #type()}, {@link
   * #bind(AgentId)}, {@link #approvals()}, {@link #completions()}, and {@link #shutdown()}.
   */
  Subscription subscribe(AgentId id, TurnObserver observer) {
    return fanout.subscribe(id, observer);
  }

  /**
   * Test seam (fix round 1, IMPORTANT-1): true while {@code id} still has at least one live
   * subscriber in the internal fanout registry — false once the last one for it has closed.
   */
  boolean hasSubscribers(AgentId id) {
    return fanout.hasSubscribers(id);
  }

  /** The approve/deny door (harness-first spec §4): this harness's own {@link ApprovalDesk}. */
  public ApprovalDesk approvals() {
    return approvals;
  }

  /** The completion door (harness-first spec §4): this harness's own {@link CompletionDesk}. */
  public CompletionDesk completions() {
    return completions;
  }

  /**
   * Infrastructure-only (harness-first spec §4): shuts down this harness's shared {@link
   * ComputationScheduler} (continuum-adoption spec §7). The harness is kept, never closed, by
   * application code — this door exists for a container's destroy callback or a test's teardown,
   * never application hygiene. Deliberately not {@link AutoCloseable}: nothing reaches for this by
   * accident through try-with-resources.
   *
   * <p>Stops the scheduled pumps only — it does not wait for them. Any model call or tool execution
   * already in flight on the model/tool executors keeps running to completion (or failure) on its
   * own thread; this method neither awaits nor cancels it.
   */
  public void shutdown() {
    scheduler.close();
    // After the pumps, never before: a pump still firing could otherwise submit to a closed
    // executor. Only an executor this harness created for itself is closed here; a
    // caller-supplied one belongs to the caller.
    if (ownedExecutor != null) {
      ownedExecutor.close();
    }
  }
}
