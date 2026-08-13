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

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationSnapshot;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.event.ListenerRegistry;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.internal.ConversationLoop;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.memory.Memory;

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

  private final ConversationLoop loop;
  private final ListenerRegistry events;
  private final ConversationStore store;
  private final Memory memory;
  private final InputRenderer<I> renderer;

  Agent(
      ConversationLoop loop,
      ListenerRegistry events,
      ConversationStore store,
      Memory memory,
      InputRenderer<I> renderer) {
    this.loop = Objects.requireNonNull(loop, "loop must not be null");
    this.events = Objects.requireNonNull(events, "events must not be null");
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.memory = Objects.requireNonNull(memory, "memory must not be null");
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
  }

  /** Opens a fresh conversation. */
  public Conversation<I> converse() {
    return new Conversation<>(loop, ConversationId.generate(), events, renderer);
  }

  /** Reopens a stored session. The loop loads its state on the next send. */
  public Conversation<I> resume(ConversationId conversationId) {
    return new Conversation<>(loop, conversationId, events, renderer);
  }

  /**
   * The debugging affordance: exactly what a conversational call made against {@code id} right now
   * would see — the same {@link Memory#recall} the loop's own {@code ModelCallExecutor} consults on
   * every send, since that recall is the sole context-assembly seam left after the cutover (design
   * §17). Truthful without a model call, because recall is deterministic over what has already been
   * told.
   *
   * <p>{@code contextFor} throws because an unknown id under a debugger is a bug; {@link #snapshot}
   * is total because a browser-minted fresh id is a normal page rebuild.
   *
   * @throws IllegalArgumentException if no conversation {@code id} is stored
   */
  public Context contextFor(ConversationId id) {
    store.load(id).orElseThrow(() -> new IllegalArgumentException("unknown conversation: " + id));
    return memory.recall(id);
  }

  /**
   * The total page-rebuild read: everything a fresh page load needs to redraw one conversation,
   * whether or not it has ever been stored. {@code snapshot} is total because a browser-minted
   * fresh id is a normal page rebuild; {@link #contextFor} throws because an unknown id under a
   * debugger is a bug.
   *
   * <p>One {@link ConversationStore#load} plus, when a stored conversation is found, one {@link
   * Memory#recall} — the same recall {@link #contextFor} and the loop's own {@code
   * ModelCallExecutor} consult.
   */
  public ConversationSnapshot snapshot(ConversationId id) {
    Objects.requireNonNull(id, "id must not be null");
    return store
        .load(id)
        .map(
            loaded ->
                new ConversationSnapshot(
                    loaded.state().status(), loaded.state().parkedCalls(), memory.recall(id)))
        .orElseGet(
            () -> new ConversationSnapshot(ConversationStatus.IDLE, List.of(), Context.empty()));
  }
}
