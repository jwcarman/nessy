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
import org.jwcarman.nessy.api.Context;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.spi.ContextAssembler;
import org.jwcarman.nessy.spi.ExecutionEngine;
import org.jwcarman.nessy.spi.session.SessionStore;

/**
 * A configured agent: a reusable factory of conversations, with the full machinery one call away.
 */
public final class Agent {

  private final ExecutionEngine engine;
  private final EventHub events;
  private final SessionStore store;
  private final ContextAssembler contextAssembler;

  Agent(
      ExecutionEngine engine,
      EventHub events,
      SessionStore store,
      ContextAssembler contextAssembler) {
    this.engine = Objects.requireNonNull(engine, "engine must not be null");
    this.events = Objects.requireNonNull(events, "events must not be null");
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.contextAssembler =
        Objects.requireNonNull(contextAssembler, "contextAssembler must not be null");
  }

  /** Opens a fresh conversation. */
  public Conversation converse() {
    return new Conversation(engine, SessionId.generate(), events);
  }

  /** Reopens a stored session. The engine loads its state on the next send. */
  public Conversation resume(SessionId sessionId) {
    return new Conversation(engine, sessionId, events);
  }

  /** The event-level API, for anything the facade does not say. */
  public ExecutionEngine engine() {
    return engine;
  }

  public EventHub events() {
    return events;
  }

  /**
   * The debugging affordance: exactly what a conversational call made against {@code id} right now
   * would see — the same projection, the same memories, assembled by the same {@link
   * ContextAssembler} instance the engine consults on every send. Truthful without a model call,
   * because assembly is deterministic over state; not free, because a configured {@link
   * org.jwcarman.nessy.spi.memory.Memory} still performs recall I/O to answer.
   *
   * @throws IllegalArgumentException if no session {@code id} is stored
   */
  public Context contextFor(SessionId id) {
    SessionState state =
        store.load(id).orElseThrow(() -> new IllegalArgumentException("unknown session: " + id));
    return contextAssembler.assemble(state);
  }
}
