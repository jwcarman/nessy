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
package org.jwcarman.nessy.engine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.jwcarman.nessy.api.AgentEvent;

/**
 * Everyone watching one agent, and the recent past they may have missed.
 *
 * <p><b>Its own entity, never a child of the agent.</b> A child dies when its parent passivates —
 * and passivation happens precisely when an agent is idle, which is exactly when a browser is still
 * holding an open stream with nothing arriving. Making it a child would kill the audience at the
 * quietest moment and orphan the connection with no error.
 *
 * <p><b>Nothing here is persisted.</b> Subscribers are addresses, and an address written down
 * outlives the process it points into: a stored {@code ActorRef} resolves after a restart and sends
 * to dead letters, silently. The buffer is deliberately ephemeral too — it exists so a reconnecting
 * browser does not lose a sentence mid-word, not so history can be replayed from cold.
 *
 * <p>Subscribers are watched, so a dropped connection unsubscribes itself rather than leaking a
 * routing entry.
 */
final class NarrationActor {

  /** How much recent past a reconnecting subscriber can be caught up on. */
  static final int BUFFERED_EVENTS = 256;

  sealed interface Command {}

  /** Something happened worth telling anyone who is listening. */
  record Narrate(AgentEvent event) implements Command {}

  /**
   * Start listening.
   *
   * @param afterEventId the last event the subscriber already saw, or null from a fresh start. Ids
   *     are UUIDv7 and therefore time-ordered, so "everything after this one" is a comparison
   *     rather than a lookup — which is what lets an SSE {@code Last-Event-ID} work directly.
   */
  record Subscribe(ActorRef<AgentEvent> subscriber, String afterEventId) implements Command {}

  record Unsubscribe(ActorRef<AgentEvent> subscriber) implements Command {}

  private record Gone(ActorRef<AgentEvent> subscriber) implements Command {}

  private NarrationActor() {}

  static Behavior<Command> create() {
    return Behaviors.setup(context -> new Listening(context).behavior());
  }

  private static final class Listening {

    private final ActorContext<Command> context;
    private final Set<ActorRef<AgentEvent>> subscribers = new HashSet<>();
    private final Deque<AgentEvent> recent = new ArrayDeque<>(BUFFERED_EVENTS);

    private Listening(ActorContext<Command> context) {
      this.context = context;
    }

    private Behavior<Command> behavior() {
      return Behaviors.receive(Command.class)
          .onMessage(Narrate.class, this::onNarrate)
          .onMessage(Subscribe.class, this::onSubscribe)
          .onMessage(Unsubscribe.class, this::onUnsubscribe)
          .onMessage(Gone.class, this::onGone)
          .build();
    }

    private Behavior<Command> onNarrate(Narrate message) {
      remember(message.event());
      subscribers.forEach(subscriber -> subscriber.tell(message.event()));
      return Behaviors.same();
    }

    private void remember(AgentEvent event) {
      if (recent.size() == BUFFERED_EVENTS) {
        recent.removeFirst();
      }
      recent.addLast(event);
    }

    private Behavior<Command> onSubscribe(Subscribe message) {
      catchUp(message.subscriber(), message.afterEventId());
      if (subscribers.add(message.subscriber())) {
        context.watchWith(message.subscriber(), new Gone(message.subscriber()));
      }
      return Behaviors.same();
    }

    /**
     * Replay before subscribing, not after: an event arriving between the two would otherwise be
     * delivered twice, and narration is already at-least-once without help.
     */
    private void catchUp(ActorRef<AgentEvent> subscriber, String afterEventId) {
      if (afterEventId == null) {
        return;
      }
      recent.stream()
          .filter(event -> event.id().compareTo(afterEventId) > 0)
          .forEach(subscriber::tell);
    }

    private Behavior<Command> onUnsubscribe(Unsubscribe message) {
      forget(message.subscriber());
      return Behaviors.same();
    }

    private Behavior<Command> onGone(Gone message) {
      forget(message.subscriber());
      return Behaviors.same();
    }

    private void forget(ActorRef<AgentEvent> subscriber) {
      if (subscribers.remove(subscriber)) {
        context.unwatch(subscriber);
      }
    }
  }
}
