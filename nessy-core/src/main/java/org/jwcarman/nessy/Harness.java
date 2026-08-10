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
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.event.ListenerDeclaration;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.model.ModelProvider;

/**
 * The application's infrastructure, assembled once and shared by every agent it builds.
 *
 * <p>Nessy's front door is a two-builder story, disjoint by design (design §17's razor). A {@code
 * Harness} owns the substrate — the model provider, session store, observation registry, and object
 * mapper — that make sense once per application, not once per agent; none of it is overridable from
 * {@link AgentBuilder}, which owns identity instead. {@link #defaultModel()} and this harness's
 * declared listeners are <em>seeded</em> rather than owned outright: an agent may supply its own
 * model, and always gets its own declarations appended after the harness's.
 *
 * <p>{@link #agent()} returns an {@link AgentBuilder} pre-wired with this harness's shared pieces,
 * ready to be given the identity — model, system prompt, tools, policies — that makes it a
 * particular agent. The odd-one-out agent (a different provider, a different store) is a second
 * harness, never an override on this one.
 */
public final class Harness {

  private final ModelProvider provider;
  private final ConversationStore store;
  private final ObservationRegistry observations;
  private final ObjectMapper mapper;
  private final String defaultModel;
  private final List<ListenerDeclaration> declarations;

  Harness(
      ModelProvider provider,
      ConversationStore store,
      ObservationRegistry observations,
      ObjectMapper mapper,
      String defaultModel,
      List<ListenerDeclaration> declarations) {
    this.provider = provider;
    this.store = store;
    this.observations = observations;
    this.mapper = mapper;
    this.defaultModel = defaultModel;
    this.declarations = declarations;
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

  ObservationRegistry observations() {
    return observations;
  }

  ObjectMapper mapper() {
    return mapper;
  }

  String defaultModel() {
    return defaultModel;
  }

  List<ListenerDeclaration> declarations() {
    return declarations;
  }
}
