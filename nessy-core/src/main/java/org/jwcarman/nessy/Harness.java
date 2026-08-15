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
import org.jwcarman.nessy.api.event.ListenerRegistry;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.Parks;
import org.jwcarman.nessy.spi.model.ModelProvider;

/**
 * The application's infrastructure, assembled once and shared by every agent it builds — inert
 * wiring, nothing more.
 *
 * <p>Nessy's front door is a two-builder story, disjoint by design (design §17's razor). A {@code
 * Harness} owns the substrate — the model provider, session store, observation registry, and object
 * mapper — that make sense once per application, not once per agent; none of it is overridable from
 * {@link AgentBuilder}, which owns identity instead. {@link #defaultModel()} and this harness's
 * {@link org.jwcarman.nessy.api.event.ListenerRegistry} are <em>seeded</em> rather than owned
 * outright: an agent may supply its own model, and always gets its own registrations appended after
 * the harness's, via {@link org.jwcarman.nessy.api.event.ListenerRegistry#extendedWith}.
 *
 * <p>{@link #agent()} returns an {@link AgentBuilder} pre-wired with this harness's shared pieces,
 * ready to be given the identity — model, system prompt, tools, policies — that makes it a
 * particular agent. The odd-one-out agent (a different provider, a different store) is a second
 * harness, never an override on this one.
 *
 * <p><strong>{@code Harness} is immutable after construction: every field here is final, and no
 * method on this class ever writes to one.</strong> It is a front door for <em>building</em> agents
 * only. Callbacks — {@code resume}, {@code approve}, {@code deny}, {@code progress}, {@code peek} —
 * do not live here; they live on {@link Agent}, the identity that actually owns the loop, the
 * grants, and the registry a callback needs to act (design of record amendment, 2026-08-14: "the
 * callbacks should not be coming to the harness. They should always go through the agent"). A
 * harness that built ten agents has ten front doors for callbacks, not one, and this class holds
 * none of them.
 */
public final class Harness {

  private final ModelProvider provider;
  private final ConversationStore store;
  private final boolean storeSet;
  private final Parks parks;
  private final ObservationRegistry observations;
  private final ObjectMapper mapper;
  private final String defaultModel;
  private final ListenerRegistry registry;

  /**
   * The session store and whether {@link HarnessBuilder#store(ConversationStore)} was ever called
   * to choose it explicitly — bundled together (java:S107: an eighth constructor parameter
   * otherwise) because they are never meaningful apart: {@link #storeSet()} exists only to describe
   * {@link #store()}'s own provenance.
   */
  record StoreSelection(ConversationStore store, boolean storeSet) {}

  Harness(
      ModelProvider provider,
      StoreSelection storeSelection,
      Parks parks,
      ObservationRegistry observations,
      ObjectMapper mapper,
      String defaultModel,
      ListenerRegistry registry) {
    this.provider = provider;
    this.store = storeSelection.store();
    this.storeSet = storeSelection.storeSet();
    this.parks = parks;
    this.observations = observations;
    this.mapper = mapper;
    this.defaultModel = defaultModel;
    this.registry = registry;
  }

  /**
   * A fresh {@link AgentBuilder}, pre-wired with this harness's infrastructure, over the {@code
   * String} vocabulary — the degenerate, single-text-block case. Defaults to {@link
   * InputRenderer#text()}.
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

  /**
   * Whether {@link HarnessBuilder#store(ConversationStore)} was ever called on the builder that
   * produced this harness — the bit {@link AgentBuilder#build()} reads to know a durable store was
   * explicitly chosen, so it can warn when an agent's memory was left on the in-memory default
   * anyway (the same set-vs-defaulted mismatch {@link HarnessBuilder#defaultParks()} guards).
   */
  boolean storeSet() {
    return storeSet;
  }

  Parks parks() {
    return parks;
  }

  ObservationRegistry observations() {
    return observations;
  }

  ObjectMapper mapper() {
    return mapper;
  }

  String defaultModel() {
    return defaultModel;
  }

  ListenerRegistry registry() {
    return registry;
  }
}
