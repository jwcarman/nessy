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
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles a {@link Harness}: the infrastructure an application sets up once and every agent it
 * builds then shares — disjointly from {@link AgentBuilder}, which owns identity instead (design
 * §17's razor). {@code provider} is required, by constructor signature via {@link
 * Nessy#harness(ModelProvider)}; everything else here has a default that works.
 */
public final class HarnessBuilder {

  private static final Logger LOGGER = LoggerFactory.getLogger(HarnessBuilder.class);

  private final ModelProvider provider;
  private ConversationStore store;
  private ObservationRegistry observations;
  private ObjectMapper mapper;
  private String defaultModel;
  private final List<ListenerRegistration> registrations = new ArrayList<>();

  HarnessBuilder(ModelProvider provider) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
  }

  /** Where session state lives. Default: {@link ConversationStore#inMemory()}. */
  public HarnessBuilder store(ConversationStore store) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    return this;
  }

  /** Where loop-level metrics and traces go. Default: {@link ObservationRegistry#NOOP}. */
  public HarnessBuilder observations(ObservationRegistry observations) {
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
    return this;
  }

  /**
   * The Jackson mapper used for tool argument (de)serialization and the default JSON renderer.
   * Default: {@code new ObjectMapper()}.
   */
  public HarnessBuilder mapper(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    return this;
  }

  /**
   * Seeded default: every agent built from this harness uses this model unless it calls its own
   * {@link AgentBuilder#model(String)}. Neither supplied is an {@link AgentConfigurationException}
   * at {@link AgentBuilder#build()}, not here — a harness with no default model is legal on its
   * own, exactly like a harness with no listeners.
   */
  public HarnessBuilder defaultModel(String defaultModel) {
    this.defaultModel = defaultModel;
    return this;
  }

  /**
   * Declares a synchronous listener seeded into every agent this harness builds — before that
   * agent's own registrations, in the order declared here. Frozen at {@link #build()}: no mutation
   * path exists afterward. A throw from {@code listener} propagates and stops the emitting
   * operation — the veto is the throw.
   */
  public <T> HarnessBuilder listen(Class<T> type, Consumer<T> listener) {
    registrations.add(ListenerRegistration.sync(type, listener));
    return this;
  }

  /**
   * Declares an asynchronous listener seeded into every agent this harness builds: {@code listener}
   * runs on a fresh virtual thread per event, and whatever it throws reaches {@code onError}
   * instead of the emitting thread — it never vetoes.
   */
  public <T> HarnessBuilder listenAsync(
      Class<T> type, Consumer<T> listener, Consumer<Throwable> onError) {
    registrations.add(ListenerRegistration.async(type, listener, onError));
    return this;
  }

  /**
   * {@link #listenAsync(Class, Consumer, Consumer)}, reporting a failed listener to an SLF4J {@link
   * Logger} rather than requiring every caller to supply its own handler.
   */
  public <T> HarnessBuilder listenAsync(Class<T> type, Consumer<T> listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    return listenAsync(type, listener, t -> LOGGER.error("async event listener failed", t));
  }

  public Harness build() {
    return new Harness(
        provider,
        Optional.ofNullable(store).orElseGet(this::defaultStore),
        Optional.ofNullable(observations).orElseGet(this::defaultObservations),
        Optional.ofNullable(mapper).orElseGet(this::defaultMapper),
        defaultModel,
        ListenerRegistry.of(registrations));
  }

  /** {@link ConversationStore#inMemory()} — session state kept only for the process's lifetime. */
  private ConversationStore defaultStore() {
    return ConversationStore.inMemory();
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
