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
import java.util.Objects;
import java.util.function.Function;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.ObservationRenderer;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The recipe compiled (§10.11), plus its life-support (harness-first spec §4): one per {@link
 * AgentType}, id-free, immortal. Every id-free collaborator (the renderer, the observer, the
 * model-call and tool-call guts, the drain policy, the staleness policy) lives here exactly once,
 * built by {@link org.jwcarman.nessy.agent.host.Nessy}'s builders and never reconstructed per
 * delivery — {@code Nessy} is the harness's only compiler; there is no other door to this class.
 * {@link #bind(AgentId)} stamps a fresh, id-specific {@link DefaultAgent} every time; binding is
 * cheap because the factories it calls hand back views over shared substrate, never build new
 * machinery.
 *
 * <p>The host tier's machinery moved in here (harness-first spec §4): the {@link DeliveryWorker},
 * the {@link ApprovalDesk}/{@link CompletionDesk}, and the reaper sweep are constructed and
 * daemon-threaded by this class's own constructor, exactly as the now-deleted long-running host
 * shim used to build them — {@code Nessy}'s builders now hand this constructor the substrate,
 * mapper, and durable computation backend those doors need, instead of wiring the worker
 * themselves. The harness is immortal, not closeable: {@link #shutdown()} is the one undecorated
 * lifecycle door, and it exists for infrastructure only.
 */
public final class Harness<O> {

  private final AgentType type;
  private final ObservationRenderer<O> renderer;
  private final AgentObserver observer;
  private final boolean drainOnIdle;
  private final StalenessPolicy stalenessPolicy;
  private final Function<String, Memory> memoryFactory;
  private final Function<String, AgentStateStore> storeFactory;
  private final Function<String, Backlog<O>> backlogFactory;
  private final Function<Memory, ModelCallExecutor> modelExecutorFactory;
  private final Function<AgentId, ToolCallExecutor> toolExecutorFactory;
  private final DeliveryWorker<O> worker;
  private final ApprovalDesk approvals;
  private final CompletionDesk completions;

  private Harness(
      AgentType type,
      ObservationRenderer<O> renderer,
      AgentObserver observer,
      boolean drainOnIdle,
      StalenessPolicy stalenessPolicy,
      Function<String, Memory> memoryFactory,
      Function<String, AgentStateStore> storeFactory,
      Function<String, Backlog<O>> backlogFactory,
      Function<Memory, ModelCallExecutor> modelExecutorFactory,
      Function<AgentId, ToolCallExecutor> toolExecutorFactory,
      Substrate substrate,
      ObjectMapper mapper,
      SubstrateComputations approvalBackend,
      SubstrateComputations executionBackend) {
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    this.observer = Objects.requireNonNull(observer, "observer must not be null");
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
    Objects.requireNonNull(approvalBackend, "approvalBackend must not be null");
    Objects.requireNonNull(executionBackend, "executionBackend must not be null");
    this.worker = new DeliveryWorker<>(substrate, mapper, this, this::resolve);
    this.approvals = new ApprovalDesk(approvalBackend, mapper, worker::nudge);
    this.completions = new CompletionDesk(executionBackend, worker::nudge);
  }

  /**
   * The one caller of this private constructor: {@link org.jwcarman.nessy.agent.host.Nessy} is the
   * harness's only compiler — the doors are {@code Nessy.harness(...)} (×2, the String and typed
   * customizer forms) and {@code Nessy.cli()}, not this factory, so this stays a plain composition
   * point rather than growing fluent setters of its own. No builder exists in user hands (spec §2):
   * each door hands a customizer a fresh config and turns it into a {@link Harness} atomically.
   * {@code substrate}, {@code mapper}, and the two kind-scoped backends (computation-identity spec
   * §3: {@code approvalBackend} over {@code approval/<agentType>}, {@code executionBackend} over
   * {@code computation/<agentType>}, sharing {@code outbox/<agentType>}) are this task's growth
   * (harness-first spec §4): the life-support this constructor now owns needs them, where a builder
   * used to wire the worker and desks itself.
   */
  public static <O> Harness<O> of(
      AgentType type,
      ObservationRenderer<O> renderer,
      AgentObserver observer,
      boolean drainOnIdle,
      StalenessPolicy stalenessPolicy,
      Function<String, Memory> memoryFactory,
      Function<String, AgentStateStore> storeFactory,
      Function<String, Backlog<O>> backlogFactory,
      Function<Memory, ModelCallExecutor> modelExecutorFactory,
      Function<AgentId, ToolCallExecutor> toolExecutorFactory,
      Substrate substrate,
      ObjectMapper mapper,
      SubstrateComputations approvalBackend,
      SubstrateComputations executionBackend) {
    Harness<O> harness =
        new Harness<>(
            type,
            renderer,
            observer,
            drainOnIdle,
            stalenessPolicy,
            memoryFactory,
            storeFactory,
            backlogFactory,
            modelExecutorFactory,
            toolExecutorFactory,
            substrate,
            mapper,
            approvalBackend,
            executionBackend);
    // Started here, after the constructor returns, not inside it: the heartbeat thread reads
    // `harness` (via DeliveryWorker's own field) the instant it runs, and starting a thread from
    // inside a constructor risks handing that thread a `this` reference before the object is fully
    // and safely published to other threads (no-`this`-escape). Starting after `new Harness<>(...)`
    // returns guarantees safe publication.
    harness.worker.start();
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

  AgentObserver observer() {
    return observer;
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
    return modelExecutorFactory.apply(binding.memory());
  }

  /**
   * The per-scope tool executor for {@code binding} — a plain field-holding object (§10.11).
   * Package-private for the same reason as {@link #modelExecutor(Binding)}.
   */
  ToolCallExecutor toolExecutor(Binding<O> binding) {
    return toolExecutorFactory.apply(binding.id());
  }

  /**
   * The id-keyed seam {@link DeliveryWorker} dispatches model calls through (harness-first spec §4,
   * the Binding demotion): equivalent to {@code modelExecutor(binding(id))}, without exposing
   * {@link Binding} across the package line — the model executor factory only ever needed the
   * scope's {@link Memory}, so this reads straight off {@code memoryFactory} rather than stamping a
   * whole {@link Binding} just to reach one field of it. Package-private by design (fix round F2):
   * the worker's own seam, not a door — the public roster stops at {@link #type()}, {@link
   * #bind(AgentId)}, {@link #approvals()}, {@link #completions()}, and {@link #shutdown()}.
   */
  ModelCallExecutor modelExecutorFor(AgentId id) {
    return modelExecutorFactory.apply(memoryFor(id));
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
    return toolExecutorFactory.apply(id);
  }

  /**
   * The id-keyed seam {@link DeliveryWorker}'s {@code requirePlainSubstrateMemory} guard reads
   * through (harness-first spec §4, the Binding demotion): equivalent to {@code
   * binding(id).memory()}, without exposing {@link Binding} across the package line.
   * Package-private by design (fix round F2): the worker's own seam, not a door — the public roster
   * stops at {@link #type()}, {@link #bind(AgentId)}, {@link #approvals()}, {@link #completions()},
   * and {@link #shutdown()}.
   */
  Memory memoryFor(AgentId id) {
    Objects.requireNonNull(id, "id must not be null");
    return memoryFactory.apply(id.value());
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
   * Infrastructure-only (harness-first spec §4): quiesces this harness's delivery worker heartbeat.
   * The harness is kept, never closed, by application code — this door exists for a container's
   * destroy callback or a test's teardown, never application hygiene. Deliberately not {@link
   * AutoCloseable}: nothing reaches for this by accident through try-with-resources.
   *
   * <p>Stops the heartbeat only — it does not wait for it. Any model call or tool execution already
   * in flight on the model/tool executors keeps running to completion (or failure) on its own
   * thread; this method neither awaits nor cancels it.
   */
  public void shutdown() {
    worker.close();
  }
}
