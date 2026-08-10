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

import java.util.Objects;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.event.ListenerRegistry;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.spi.ExecutionEngine;
import org.jwcarman.nessy.spi.context.ContextPipeline;
import org.jwcarman.nessy.spi.conversation.ConversationStore;

/**
 * A configured agent: a reusable factory of conversations, with the full machinery one call away.
 *
 * <p>There is no agent-wide dynamic subscription any more (design §17): a listener that must watch
 * every conversation this agent ever runs is declared once, at build time, via {@code
 * AgentBuilder#listen}/{@code listenAsync}; the only thing left to attach at runtime is one
 * conversation's own traffic, through {@link Conversation#events()}.
 *
 * @param <I> the input vocabulary a {@code tell} to one of this agent's conversations may carry
 */
public final class Agent<I> {

  private final ExecutionEngine engine;
  private final ListenerRegistry events;
  private final ConversationStore store;
  private final ContextPipeline contextPipeline;
  private final InputRenderer<I> renderer;

  Agent(
      ExecutionEngine engine,
      ListenerRegistry events,
      ConversationStore store,
      ContextPipeline contextPipeline,
      InputRenderer<I> renderer) {
    this.engine = Objects.requireNonNull(engine, "engine must not be null");
    this.events = Objects.requireNonNull(events, "events must not be null");
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.contextPipeline =
        Objects.requireNonNull(contextPipeline, "contextPipeline must not be null");
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
  }

  /** Opens a fresh conversation. */
  public Conversation<I> converse() {
    return new Conversation<>(engine, ConversationId.generate(), events, renderer);
  }

  /** Reopens a stored session. The engine loads its state on the next send. */
  public Conversation<I> resume(ConversationId conversationId) {
    return new Conversation<>(engine, conversationId, events, renderer);
  }

  /** The event-level API, for anything the facade does not say. */
  public ExecutionEngine engine() {
    return engine;
  }

  /**
   * The debugging affordance: exactly what a conversational call made against {@code id} right now
   * would see — the same projections, the same enrichments, assembled by the same {@link
   * ContextPipeline} instance the engine consults on every send. Truthful without a model call,
   * because assembly is deterministic over state; not free, because a configured {@link
   * org.jwcarman.nessy.spi.context.ContextEnricher} still performs enrichment I/O to answer.
   *
   * @throws IllegalArgumentException if no session {@code id} is stored
   */
  public Context contextFor(ConversationId id) {
    ConversationState state =
        store.load(id).orElseThrow(() -> new IllegalArgumentException("unknown session: " + id));
    return contextPipeline.assemble(state);
  }
}
