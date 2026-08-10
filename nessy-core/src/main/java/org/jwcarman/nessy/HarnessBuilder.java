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
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.session.SessionStore;
import org.jwcarman.nessy.spi.session.TranscriptStore;

/**
 * Assembles a {@link Harness}: the six pieces of infrastructure an application sets up once and
 * every agent it builds then shares.
 *
 * <p>Every knob here has a default that works, so {@code Nessy.harness().build()} is already a
 * usable harness — a harness with no provider simply requires every agent it seeds to supply its
 * own via {@link AgentBuilder#provider(ModelProvider)}.
 */
public final class HarnessBuilder {

  private ModelProvider provider;
  private SessionStore store = SessionStore.inMemory();
  private TranscriptStore transcript = TranscriptStore.none();
  private EventHub hub = EventHub.synchronous();
  private ObservationRegistry observations = ObservationRegistry.NOOP;
  private ObjectMapper mapper = new ObjectMapper();

  HarnessBuilder() {}

  /**
   * The default model line every agent seeded from this harness uses, unless it calls its own
   * {@link AgentBuilder#provider(ModelProvider)}. Optional here: a harness with no provider is
   * legal, but an agent that neither inherits nor overrides one fails at {@link
   * AgentBuilder#build()}.
   */
  public HarnessBuilder provider(ModelProvider provider) {
    this.provider = provider;
    return this;
  }

  /** Where session state lives. Default: {@link SessionStore#inMemory()}. */
  public HarnessBuilder store(SessionStore store) {
    this.store = store;
    return this;
  }

  /**
   * Where every message is journaled the moment it is born. Default: {@link TranscriptStore#none()}
   * — retention is a deliberate declaration, not a silent default.
   */
  public HarnessBuilder transcript(TranscriptStore transcript) {
    this.transcript = transcript;
    return this;
  }

  /**
   * Where {@link org.jwcarman.nessy.api.event.SessionEvent}s are published. Default: {@link
   * EventHub#synchronous()}.
   */
  public HarnessBuilder hub(EventHub hub) {
    this.hub = hub;
    return this;
  }

  /** Where engine-level metrics and traces go. Default: {@link ObservationRegistry#NOOP}. */
  public HarnessBuilder observations(ObservationRegistry observations) {
    this.observations = observations;
    return this;
  }

  /**
   * The Jackson mapper used for tool argument (de)serialization. Default: {@code new
   * ObjectMapper()}.
   */
  public HarnessBuilder mapper(ObjectMapper mapper) {
    this.mapper = mapper;
    return this;
  }

  public Harness build() {
    return new Harness(provider, store, transcript, hub, observations, mapper);
  }
}
