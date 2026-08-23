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
import org.jwcarman.nessy.agent.durable.ApprovalDesk;
import org.jwcarman.nessy.agent.durable.CompletionDesk;
import org.jwcarman.nessy.agent.durable.DeliveryWorker;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.ObservationRenderer;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.durable.DurableComputationBackend;
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
 * daemon-threaded by this class's own constructor, exactly as {@code AutonomousHost} used to build
 * them — {@code Nessy}'s builders now hand this constructor the substrate, mapper, and durable
 * computation backend those doors need, instead of wiring the worker themselves. The harness is
 * immortal, not closeable: {@link #shutdown()} is the one undecorated lifecycle door, and it exists
 * for infrastructure only.
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
  private final Function<Binding<O>, ModelCallExecutor> modelExecutorFactory;
  private final Function<Binding<O>, ToolCallExecutor> toolExecutorFactory;
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
      Function<Binding<O>, ModelCallExecutor> modelExecutorFactory,
      Function<Binding<O>, ToolCallExecutor> toolExecutorFactory,
      Substrate substrate,
      ObjectMapper mapper,
      DurableComputationBackend backend) {
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
    Objects.requireNonNull(backend, "backend must not be null");
    this.worker = new DeliveryWorker<>(substrate, mapper, this, this::resolve);
    this.approvals = new ApprovalDesk(backend, worker::nudge);
    this.completions = new CompletionDesk(backend, worker::nudge);
    worker.start();
  }

  /**
   * The one caller of this private constructor: {@link org.jwcarman.nessy.agent.host.Nessy} is the
   * harness's only compiler — the two host builders are the doors, not this factory, so this stays
   * a plain composition point rather than growing fluent setters of its own. {@code substrate},
   * {@code mapper}, and {@code backend} are this task's growth (harness-first spec §4): the life-
   * support this constructor now owns needs them, where a builder used to wire the worker and desks
   * itself.
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
      Function<Binding<O>, ModelCallExecutor> modelExecutorFactory,
      Function<Binding<O>, ToolCallExecutor> toolExecutorFactory,
      Substrate substrate,
      ObjectMapper mapper,
      DurableComputationBackend backend) {
    return new Harness<>(
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
        backend);
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
   * The raw scope handle {@link #bind(AgentId)} wraps for application code — kept public only
   * because two nessy-agent internals reach across this class's package line for it directly:
   * {@link DeliveryWorker}'s fold machinery (which dispatches through {@link
   * #modelExecutor(Binding)} and {@link #toolExecutor(Binding)} without going through a scope's
   * {@link DefaultAgent} shell) and this module's white-box test fixtures, which construct a {@link
   * DefaultAgent} by hand to satisfy {@link AgentResolver}'s concrete return type. A
   * package-private door was tried first and does not reach either caller — both live outside
   * {@code org.jwcarman.nessy.agent} — so this stays the minimal honest path rather than a false
   * demotion. Not application vocabulary: {@link Binding} is never returned by {@link
   * #bind(AgentId)}, the only door application code has.
   */
  public Binding<O> binding(AgentId id) {
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
   * Public so the delivery worker (durable-deliveries spec §5) can dispatch a post-commit {@code
   * CallModel} effect exactly as {@link DefaultAgent} does, without duplicating the harness's own
   * factory wiring.
   */
  public ModelCallExecutor modelExecutor(Binding<O> binding) {
    return modelExecutorFactory.apply(binding);
  }

  /**
   * The per-scope tool executor for {@code binding} — a plain field-holding object (§10.11). Public
   * for the same reason as {@link #modelExecutor(Binding)}.
   */
  public ToolCallExecutor toolExecutor(Binding<O> binding) {
    return toolExecutorFactory.apply(binding);
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
   */
  public void shutdown() {
    worker.close();
  }
}
