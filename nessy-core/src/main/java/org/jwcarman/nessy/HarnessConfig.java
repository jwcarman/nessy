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
package org.jwcarman.nessy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.event.ListenerRegistration;
import org.jwcarman.nessy.api.event.ListenerRegistry;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.Parks;
import org.jwcarman.nessy.spi.intent.IntentStore;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.subagent.SubagentLinks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What an application writes to describe a {@link Harness}, handed to {@link
 * Nessy#harness(HarnessCustomizer)}: a CONFIG, not a builder (design of record 2026-08-16 §1) —
 * fluent setters, no {@code build()}. {@link #provider} is the harness's one required thing;
 * everything else here has a default that works.
 *
 * <p>Assembles a {@link Harness}: the infrastructure an application sets up once and every agent it
 * builds then shares — disjointly from {@link AgentConfig}, which owns identity instead (design
 * §17's razor).
 */
public final class HarnessConfig implements ListenerDeclarations<HarnessConfig> {

  private static final Logger LOGGER = LoggerFactory.getLogger(HarnessConfig.class);

  private ModelProvider provider;
  private ConversationStore store;
  private boolean storeSet;
  private Parks parks;
  private SubagentLinks subagentLinks;
  private boolean subagentLinksSet;
  private IntentStore intentStore;
  private ObservationRegistry observations;
  private ObjectMapper mapper;
  private String defaultModel;
  private final List<ListenerRegistration> registrations = new ArrayList<>();

  HarnessConfig() {}

  /**
   * The model provider every agent this harness builds calls through. The harness's one required
   * thing — {@link Nessy#harness(HarnessCustomizer)} throws {@link NullPointerException} naming
   * this field if the customizer never calls it.
   */
  public HarnessConfig provider(ModelProvider provider) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    return this;
  }

  /** Where session state lives. Default: {@link ConversationStore#inMemory()}. */
  public HarnessConfig store(ConversationStore store) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.storeSet = true;
    return this;
  }

  /**
   * Where parked waits live, so a callback door can find its way back to them. Default: {@link
   * Parks#inMemory()}.
   */
  public HarnessConfig parks(Parks parks) {
    this.parks = Objects.requireNonNull(parks, "parks must not be null");
    return this;
  }

  /**
   * Where subagent parent-child correlations live, so a settled child's completion can find its way
   * back to the parent's own park (design of record 2026-08-16 §3). Default: {@link
   * SubagentLinks#inMemory()}.
   */
  public HarnessConfig subagentLinks(SubagentLinks subagentLinks) {
    this.subagentLinks = Objects.requireNonNull(subagentLinks, "subagentLinks must not be null");
    this.subagentLinksSet = true;
    return this;
  }

  /**
   * Where a declared intent lives, so a later call's authorization policy can read it back (design
   * §7, Task 3b). Default: {@link IntentStore#inMemory()}.
   */
  public HarnessConfig intentStore(IntentStore intentStore) {
    this.intentStore = Objects.requireNonNull(intentStore, "intentStore must not be null");
    return this;
  }

  /** Where loop-level metrics and traces go. Default: {@link ObservationRegistry#NOOP}. */
  public HarnessConfig observations(ObservationRegistry observations) {
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
    return this;
  }

  /**
   * The Jackson mapper used for tool argument (de)serialization and the default JSON renderer.
   * Default: {@code new ObjectMapper()}.
   */
  public HarnessConfig mapper(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    return this;
  }

  /**
   * Seeded default: every agent built from this harness uses this model unless it calls its own
   * {@link AgentConfig#model(String)}. Neither supplied is an {@link AgentConfigurationException}
   * at the agent factory, not here — a harness with no default model is legal on its own, exactly
   * like a harness with no listeners.
   */
  public HarnessConfig defaultModel(String defaultModel) {
    this.defaultModel = defaultModel;
    return this;
  }

  /**
   * Declares a synchronous listener seeded into every agent this harness builds — before that
   * agent's own registrations, in the order declared here. Frozen once this config becomes a {@link
   * Harness}: no mutation path exists afterward. A throw from {@code listener} propagates and stops
   * the emitting operation — the veto is the throw.
   */
  @Override
  public <T> HarnessConfig listen(Class<T> type, Consumer<T> listener) {
    registrations.add(ListenerRegistration.sync(type, listener));
    return this;
  }

  /**
   * Declares an asynchronous listener seeded into every agent this harness builds: {@code listener}
   * runs on a fresh virtual thread per event, and whatever it throws reaches {@code onError}
   * instead of the emitting thread — it never vetoes.
   */
  public <T> HarnessConfig listenAsync(
      Class<T> type, Consumer<T> listener, Consumer<Throwable> onError) {
    registrations.add(ListenerRegistration.async(type, listener, onError));
    return this;
  }

  /**
   * {@link #listenAsync(Class, Consumer, Consumer)}, reporting a failed listener to an SLF4J {@link
   * Logger} rather than requiring every caller to supply its own handler.
   */
  @Override
  public <T> HarnessConfig listenAsync(Class<T> type, Consumer<T> listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    return listenAsync(type, listener, t -> LOGGER.error("async event listener failed", t));
  }

  /**
   * Turns this config into the {@link Harness} it describes — the factory's own step, never a
   * public {@code build()} (design of record 2026-08-16 §1). Reached only from {@link
   * Nessy#harness(HarnessCustomizer)}, once {@code customize} has returned.
   */
  Harness build() {
    Objects.requireNonNull(provider, "provider must not be null");
    return new Harness(
        provider,
        new Harness.StoreSelection(
            Optional.ofNullable(store).orElseGet(this::defaultStore), storeSet),
        new Harness.CoordinationStores(
            Optional.ofNullable(parks).orElseGet(this::defaultParks),
            Optional.ofNullable(subagentLinks).orElseGet(this::defaultSubagentLinks),
            subagentLinksSet),
        Optional.ofNullable(intentStore).orElseGet(this::defaultIntentStore),
        Optional.ofNullable(observations).orElseGet(this::defaultObservations),
        Optional.ofNullable(mapper).orElseGet(this::defaultMapper),
        defaultModel,
        ListenerRegistry.of(registrations));
  }

  /** {@link ConversationStore#inMemory()} — session state kept only for the process's lifetime. */
  private ConversationStore defaultStore() {
    return ConversationStore.inMemory();
  }

  /**
   * {@link Parks#inMemory()} — parked waits kept only for the process's lifetime. An all-in-memory
   * harness is a coherent choice and stays silent; a harness whose {@link #store} was explicitly
   * configured is a different story — that mismatch (durable session state, in-memory parks) is
   * worth shouting about, so this warns once per {@link #build()} only when {@link #storeSet} is
   * true, mirroring {@link AgentConfig}'s own memory-defaulting guard.
   */
  private Parks defaultParks() {
    if (storeSet) {
      LOGGER.warn(
          "no parks registry configured for this harness: defaulting to Parks.inMemory(), even"
              + " though this harness's store was explicitly configured — every parked wait's token"
              + " is lost on process exit; call .parks(...) with a durable implementation (e.g."
              + " JdbcParks.create(...)) for any deployment that needs parks to survive a restart");
    }
    return Parks.inMemory();
  }

  /**
   * {@link SubagentLinks#inMemory()} — subagent links kept only for the process's lifetime. Unlike
   * {@link #defaultParks()} and {@link AgentConfig}'s own memory-defaulting guard, this stays
   * silent even with an explicitly configured {@link #store}: a harness with no subagents never
   * touches this store at all, so warning unconditionally here would fire for every durable-store
   * harness whether or not it ever declares a single {@code .subagent(...)}. The narrower warning —
   * only when an agent actually declares a subagent against a store-set-but-links-defaulted harness
   * — lives on {@link AgentConfig}'s own build path instead, which is the only place both facts (a
   * durable store, and at least one {@code .subagent(...)} declared) are known together; see {@link
   * Harness#subagentLinksSet()}.
   */
  private SubagentLinks defaultSubagentLinks() {
    return SubagentLinks.inMemory();
  }

  /** {@link IntentStore#inMemory()} — declared intent kept only for the process's lifetime. */
  private IntentStore defaultIntentStore() {
    return IntentStore.inMemory();
  }

  /** {@link ObservationRegistry#NOOP} — no metrics or traces emitted. */
  private ObservationRegistry defaultObservations() {
    return ObservationRegistry.NOOP;
  }

  /** A fresh, un-configured {@link ObjectMapper}. */
  private ObjectMapper defaultMapper() {
    return new ObjectMapper();
  }
}
