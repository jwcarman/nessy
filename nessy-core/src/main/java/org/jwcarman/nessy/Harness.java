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
import java.util.Objects;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.model.ModelProvider;

/**
 * The application's infrastructure, assembled once and shared by every agent it builds.
 *
 * <p>Nessy's front door is a two-builder story. A {@code Harness} holds the substrate — the default
 * model provider, session store, event hub, observation registry, and object mapper — that make
 * sense once per application, not once per agent: infrastructure is ambient. {@link #agent()} then
 * returns an {@link AgentBuilder} seeded with this harness's pieces, ready to be given the identity
 * — model, system prompt, tools, policies — that makes it a particular agent: capability is
 * granted, and authority is declared, one {@code agent()} call at a time.
 *
 * <p>Two agents built from the same harness share its session store and event hub by construction,
 * which is what lets one hub subscriber observe every agent's traffic and one store hold every
 * agent's sessions. An agent may still override any one piece of infrastructure for itself via the
 * matching {@link AgentBuilder} setter — an escape hatch, not the normal path.
 *
 * <p>{@link HarnessBuilder#transcript} is sugar rather than a sixth stored piece: it registers an
 * inline journaling subscriber directly on {@link #hub} at {@link HarnessBuilder#build()} time, so
 * a {@code Harness} instance itself carries no transcript field to keep in sync with the hub.
 */
public final class Harness {

  private final ModelProvider provider;
  private final ConversationStore store;
  private final EventHub hub;
  private final ObservationRegistry observations;
  private final ObjectMapper mapper;

  Harness(
      ModelProvider provider,
      ConversationStore store,
      EventHub hub,
      ObservationRegistry observations,
      ObjectMapper mapper) {
    this.provider = provider;
    this.store = store;
    this.hub = hub;
    this.observations = observations;
    this.mapper = mapper;
  }

  /**
   * A fresh {@link AgentBuilder}, pre-wired with this harness's infrastructure, over the {@code
   * String} vocabulary — the degenerate, single-text-block case behind {@link Nessy#agent()}.
   * Defaults to {@link InputRenderer#text()}.
   */
  public AgentBuilder<String> agent() {
    return new AgentBuilder<>(this, String.class, InputRenderer.text());
  }

  /**
   * A fresh {@link AgentBuilder} over an application-owned input vocabulary {@code I} — typically a
   * sealed interface of records. Defaults to {@link InputRenderer#json(ObjectMapper)} over this
   * harness's own mapper; override with {@link AgentBuilder#renderer(InputRenderer)}.
   */
  public <I> AgentBuilder<I> agent(Class<I> vocabulary) {
    Objects.requireNonNull(vocabulary, "vocabulary must not be null");
    return new AgentBuilder<>(this, vocabulary, InputRenderer.json(mapper));
  }

  ModelProvider provider() {
    return provider;
  }

  ConversationStore store() {
    return store;
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
