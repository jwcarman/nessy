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

import java.util.Objects;
import java.util.function.Function;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.ObservationRenderer;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.spi.Memory;

/**
 * The recipe compiled (§10.11): one per {@link AgentType}, id-free, immortal. A harness is an agent
 * with the scope left blank — every id-free collaborator (the renderer, the observer, the
 * model-call and tool-call guts, the drain policy, the staleness policy) lives here exactly once,
 * built by {@link org.jwcarman.nessy.agent.host.Nessy}'s builders and never reconstructed per
 * delivery. {@link #bind(AgentId)} stamps the thin, id-specific handles — {@link Binding} — fresh
 * every time; binding is cheap because the factories it calls hand back views over shared substrate
 * (Task 2), never build new machinery.
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
      Function<Binding<O>, ToolCallExecutor> toolExecutorFactory) {
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
  }

  /**
   * The one caller of this private constructor: {@link org.jwcarman.nessy.agent.host.Nessy} is the
   * harness's only compiler — the two host builders are the doors, not this factory, so this stays
   * a plain composition point rather than growing fluent setters of its own.
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
      Function<Binding<O>, ToolCallExecutor> toolExecutorFactory) {
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
        toolExecutorFactory);
  }

  public AgentType type() {
    return type;
  }

  /**
   * Stamps a fresh {@link Binding} for {@code id}: thin, no I/O — the factories hand back views
   * over shared substrate, not new machinery (spec §10.11).
   */
  public Binding<O> bind(AgentId id) {
    Objects.requireNonNull(id, "id must not be null");
    String rawId = id.value();
    return new Binding<>(
        id, memoryFactory.apply(rawId), storeFactory.apply(rawId), backlogFactory.apply(rawId));
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

  /** The per-scope model executor for {@code binding} — a plain field-holding object (§10.11). */
  ModelCallExecutor modelExecutor(Binding<O> binding) {
    return modelExecutorFactory.apply(binding);
  }

  /** The per-scope tool executor for {@code binding} — a plain field-holding object (§10.11). */
  ToolCallExecutor toolExecutor(Binding<O> binding) {
    return toolExecutorFactory.apply(binding);
  }
}
