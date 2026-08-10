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
import java.util.function.Consumer;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.event.Subscription;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.spi.ExecutionEngine;

/**
 * One session. Sugar over {@code engine.run} — no semantics of its own.
 *
 * @param <I> this conversation's input vocabulary — what {@link #tell} accepts
 */
public final class Conversation<I> {

  private final ExecutionEngine engine;
  private final ConversationId conversationId;
  private final EventHub events;
  private final InputRenderer<I> renderer;

  Conversation(
      ExecutionEngine engine,
      ConversationId conversationId,
      EventHub events,
      InputRenderer<I> renderer) {
    this.engine = Objects.requireNonNull(engine, "engine must not be null");
    this.conversationId = Objects.requireNonNull(conversationId, "conversationId must not be null");
    this.events = Objects.requireNonNull(events, "events must not be null");
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
  }

  /**
   * Tells this conversation something from its input vocabulary. {@code input} is rendered into
   * content blocks by this agent's {@link InputRenderer} and carried into the loop as the same
   * {@code UserSaid} event {@code send(String)} used to build directly — typing dissolves at the
   * wire, the sealed {@link ConversationEvent} grammar never changes shape.
   *
   * @throws IllegalArgumentException if the renderer produces a null or empty block list
   * @throws RuntimeException whatever the renderer itself throws, unwrapped — a renderer is the
   *     application's own code, failing on its own thread, with the caller present to see it
   */
  public Reply tell(I input) {
    return new Reply(engine.run(conversationId, render(input)));
  }

  /**
   * Tells, delivering this conversation's loop events to {@code tap} for the duration of the call.
   *
   * <p>Three guarantees: {@code tap} sees only this conversation's events (every other session's
   * traffic on the same hub is filtered out); delivery is synchronous, in loop order, on the
   * calling thread — the same contract {@link EventHub} itself makes; and the subscription is
   * closed when {@code tell} returns, whether normally or by exception, so {@code tap} never fires
   * again afterward.
   *
   * <p>{@code tap} is just another hub subscriber, wired here with {@link EventHub#subscribe(Class,
   * Consumer)} — always sync, never a per-call choice — so the synchronous spine's veto-by-throw
   * (design §9.1) applies to it exactly as it would to any other sync subscriber: if {@code tap}
   * throws, that exception propagates straight out of {@code emit}, out of the engine's {@code
   * run}, and out of this method — a throwing {@code tap} aborts the call. A {@code tap} that must
   * not be allowed to do that catches its own exceptions, or hands its own work off to another
   * thread, rather than relying on this method to protect it.
   *
   * <p>The envelope is gone: this subscribes to the raw, self-attributing {@link ConversationEvent}
   * directly and filters by {@link ConversationEvent#conversationId()} rather than unwrapping a
   * publishing record.
   *
   * @throws IllegalArgumentException if the renderer produces a null or empty block list
   * @throws RuntimeException whatever the renderer itself throws, unwrapped, or whatever {@code
   *     tap} itself throws
   */
  public Reply tell(I input, Consumer<ConversationEvent> tap) {
    Objects.requireNonNull(tap, "tap must not be null");
    ConversationEvent.UserSaid event = render(input);
    try (Subscription subscription =
        events.subscribe(ConversationEvent.class, e -> deliver(e, tap))) {
      return new Reply(engine.run(conversationId, event));
    }
  }

  /**
   * Renders {@code input} and fails loud, before the engine ever sees it, if the renderer did not
   * hold up its end: a null or empty block list is rejected here; anything the renderer itself
   * throws simply propagates, since it is the caller's own code running on the caller's own thread
   * — there is no session state to protect yet.
   */
  private ConversationEvent.UserSaid render(I input) {
    List<ContentBlock> blocks = renderer.render(input);
    if (blocks == null || blocks.isEmpty()) {
      throw new IllegalArgumentException(
          "InputRenderer produced no content blocks for input: " + input);
    }
    return new ConversationEvent.UserSaid(conversationId, blocks);
  }

  private void deliver(ConversationEvent event, Consumer<ConversationEvent> tap) {
    if (event.conversationId().equals(conversationId)) {
      tap.accept(event);
    }
  }

  public ConversationId conversationId() {
    return conversationId;
  }
}
