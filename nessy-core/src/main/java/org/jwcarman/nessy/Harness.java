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
 * The application's infrastructure, assembled once and shared by every agent it builds.
 *
 * <p>Nessy's front door is a two-builder story. A {@code Harness} holds the six pieces of substrate
 * — the default model provider, session store, transcript store, event hub, observation registry,
 * and object mapper — that make sense once per application, not once per agent: infrastructure is
 * ambient. {@link #agent()} then returns an {@link AgentBuilder} seeded with this harness's pieces,
 * ready to be given the identity — model, system prompt, tools, policies — that makes it a
 * particular agent: capability is granted, and authority is declared, one {@code agent()} call at a
 * time.
 *
 * <p>Two agents built from the same harness share its session store and event hub by construction,
 * which is what lets one hub subscriber observe every agent's traffic and one store hold every
 * agent's sessions. An agent may still override any one piece of infrastructure for itself via the
 * matching {@link AgentBuilder} setter — an escape hatch, not the normal path.
 */
public final class Harness {

  private final ModelProvider provider;
  private final SessionStore store;
  private final TranscriptStore transcript;
  private final EventHub hub;
  private final ObservationRegistry observations;
  private final ObjectMapper mapper;

  Harness(
      ModelProvider provider,
      SessionStore store,
      TranscriptStore transcript,
      EventHub hub,
      ObservationRegistry observations,
      ObjectMapper mapper) {
    this.provider = provider;
    this.store = store;
    this.transcript = transcript;
    this.hub = hub;
    this.observations = observations;
    this.mapper = mapper;
  }

  /** A fresh {@link AgentBuilder}, pre-wired with this harness's infrastructure. */
  public AgentBuilder agent() {
    return new AgentBuilder(this);
  }

  ModelProvider provider() {
    return provider;
  }

  SessionStore store() {
    return store;
  }

  TranscriptStore transcript() {
    return transcript;
  }

  EventHub hub() {
    return hub;
  }

  ObservationRegistry observations() {
    return observations;
  }

  ObjectMapper mapper() {
    return mapper;
  }
}
