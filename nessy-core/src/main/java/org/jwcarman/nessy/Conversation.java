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
import java.util.function.Consumer;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.event.SessionEvent;
import org.jwcarman.nessy.api.event.Subscription;
import org.jwcarman.nessy.spi.ExecutionEngine;

/** One session. Sugar over {@code engine.run} — no semantics of its own. */
public final class Conversation {

  private final ExecutionEngine engine;
  private final SessionId sessionId;
  private final EventHub events;

  Conversation(ExecutionEngine engine, SessionId sessionId, EventHub events) {
    this.engine = Objects.requireNonNull(engine, "engine must not be null");
    this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
    this.events = Objects.requireNonNull(events, "events must not be null");
  }

  public Reply send(String text) {
    return new Reply(engine.run(sessionId, Event.UserSaid.of(text)));
  }

  /**
   * Sends, delivering this conversation's loop events to {@code tap} for the duration of the send.
   *
   * <p>Three guarantees: {@code tap} sees only this conversation's events (every other session's
   * traffic on the same hub is filtered out); delivery is synchronous, in loop order, on the
   * calling thread — the same contract {@link EventHub} itself makes; and the subscription is
   * closed when {@code send} returns, whether normally or by exception, so {@code tap} never fires
   * again afterward.
   *
   * <p>If {@code tap} throws, the exception is contained by the hub — {@link EventHub}'s delivery
   * contract catches and discards a subscriber's {@link RuntimeException} rather than letting it
   * propagate — so a throwing {@code tap} will not abort the send; the loop continues and this
   * method still returns a normal {@link Reply}.
   */
  public Reply send(String text, Consumer<Event> tap) {
    Objects.requireNonNull(tap, "tap must not be null");
    try (Subscription subscription =
        events.subscribe(SessionEvent.class, sessionEvent -> deliver(sessionEvent, tap))) {
      return new Reply(engine.run(sessionId, Event.UserSaid.of(text)));
    }
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
