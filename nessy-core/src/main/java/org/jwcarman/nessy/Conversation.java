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
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.event.SessionEvent;
import org.jwcarman.nessy.api.event.Subscription;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.api.session.SessionId;
import org.jwcarman.nessy.spi.ExecutionEngine;

/**
 * One session. Sugar over {@code engine.run} — no semantics of its own.
 *
 * @param <I> this conversation's input vocabulary — what {@link #tell} accepts
 */
public final class Conversation<I> {

  private final ExecutionEngine engine;
  private final SessionId sessionId;
  private final EventHub events;
  private final InputRenderer<I> renderer;

  Conversation(
      ExecutionEngine engine, SessionId sessionId, EventHub events, InputRenderer<I> renderer) {
    this.engine = Objects.requireNonNull(engine, "engine must not be null");
    this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
    this.events = Objects.requireNonNull(events, "events must not be null");
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
  }

  /**
   * Tells this conversation something from its input vocabulary. {@code input} is rendered into
   * content blocks by this agent's {@link InputRenderer} and carried into the loop as the same
   * {@code UserSaid} event {@code send(String)} used to build directly — typing dissolves at the
   * wire, the sealed {@link Event} grammar never changes shape.
   *
   * @throws IllegalArgumentException if the renderer produces a null or empty block list
   * @throws RuntimeException whatever the renderer itself throws, unwrapped — a renderer is the
   *     application's own code, failing on its own thread, with the caller present to see it
   */
  public Reply tell(I input) {
    return new Reply(engine.run(sessionId, render(input)));
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
   * <p>{@code tap} is just another hub subscriber, so the synchronous spine's veto-by-throw (design
   * §9.1) applies to it exactly as it would to any other subscriber: if {@code tap} throws, that
   * exception propagates straight out of {@code emit}, out of the engine's {@code run}, and out of
   * this method — a throwing {@code tap} aborts the call. A {@code tap} that must not be allowed to
   * do that wraps itself with {@link EventHub#async(java.util.function.Consumer,
   * java.util.function.Consumer)} before being handed here.
   *
   * @throws IllegalArgumentException if the renderer produces a null or empty block list
   * @throws RuntimeException whatever the renderer itself throws, unwrapped, or whatever {@code
   *     tap} itself throws
   */
  public Reply tell(I input, Consumer<Event> tap) {
    Objects.requireNonNull(tap, "tap must not be null");
    Event.UserSaid event = render(input);
    try (Subscription subscription =
        events.subscribe(SessionEvent.class, sessionEvent -> deliver(sessionEvent, tap))) {
      return new Reply(engine.run(sessionId, event));
    }
  }

  /**
   * Renders {@code input} and fails loud, before the engine ever sees it, if the renderer did not
   * hold up its end: a null or empty block list is rejected here; anything the renderer itself
   * throws simply propagates, since it is the caller's own code running on the caller's own thread
   * — there is no session state to protect yet.
   */
  private Event.UserSaid render(I input) {
    List<ContentBlock> blocks = renderer.render(input);
    if (blocks == null || blocks.isEmpty()) {
      throw new IllegalArgumentException(
          "InputRenderer produced no content blocks for input: " + input);
    }
    return new Event.UserSaid(blocks);
  }

  private void deliver(SessionEvent sessionEvent, Consumer<Event> tap) {
    if (sessionEvent.sessionId().equals(sessionId)) {
      tap.accept(sessionEvent.event());
    }
  }

  public SessionId sessionId() {
    return sessionId;
  }
}
