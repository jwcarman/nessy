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
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentSubscriber;
import org.jwcarman.nessy.api.AgentSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who is watching an agent, on this node, and the recent past they may have missed.
 *
 * <p><b>Deliberately not distributed and not durable.</b> This was a sharded actor so an event
 * could reach a browser attached to another node; without sharding it is a map, and cross-node
 * delivery returns in phase 3 as a Substrate {@code Journal} behind the same {@link Narrator} seam.
 * Nothing about correctness depends on a subscriber existing, which is what makes that swap safe.
 *
 * <p><b>The replay buffer is the same bargain {@code NarrationActor} struck.</b> Bounded, per
 * agent, and gone the moment this process is: it exists so a reconnecting browser does not lose a
 * sentence mid-word, not so history can be replayed from cold. That is a smaller promise than a
 * durable journal, and this class makes exactly that promise and no more.
 *
 * <p><b>A subscriber that throws is ejected.</b> Narration is best-effort; one broken watcher must
 * not stop a turn or silence the others.
 */
final class Narration {

  private static final Logger LOG = LoggerFactory.getLogger(Narration.class);

  /** How much recent past a reconnecting subscriber can be caught up on. Matches the old actor. */
  private static final int BUFFERED_EVENTS = 256;

  private final Map<AgentId, Set<AgentSubscriber>> watchers = new ConcurrentHashMap<>();
  private final Map<AgentId, Deque<AgentEvent>> recent = new ConcurrentHashMap<>();

  /** This agent's narrator. Cheap: it closes over the id and reads the map on each event. */
  Narrator narratorFor(AgentId agentId) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    return event -> deliver(agentId, event);
  }

  AgentSubscription subscribe(AgentId agentId, AgentSubscriber subscriber) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(subscriber, "subscriber must not be null");
    watchers.computeIfAbsent(agentId, _ -> ConcurrentHashMap.newKeySet()).add(subscriber);
    return () -> remove(agentId, subscriber);
  }

  /**
   * Starts listening, first replaying whatever this node still remembers from after {@code
   * afterEventId} -- events are UUIDv7 and therefore time-ordered, so "everything after this one"
   * is a comparison rather than a lookup. Replay happens BEFORE subscribing, not after: an event
   * arriving between the two would otherwise be delivered twice, and narration is already
   * at-least-once without help.
   *
   * <p>{@code afterEventId} of {@code null} replays nothing, same as {@link #subscribe(AgentId,
   * AgentSubscriber)}.
   */
  AgentSubscription subscribe(AgentId agentId, AgentSubscriber subscriber, String afterEventId) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(subscriber, "subscriber must not be null");
    if (afterEventId != null) {
      snapshotFor(agentId).stream()
          .filter(event -> event.id().compareTo(afterEventId) > 0)
          .forEach(subscriber::on);
    }
    return subscribe(agentId, subscriber);
  }

  /** Drops every watcher of an agent that is going away. Silent if there were none. */
  void forget(AgentId agentId) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    watchers.remove(agentId);
    recent.remove(agentId);
  }

  private void deliver(AgentId agentId, AgentEvent event) {
    remember(agentId, event);
    Collection<AgentSubscriber> subscribers = watchers.get(agentId);
    if (subscribers == null) {
      return;
    }
    // Copied before iterating: a subscriber may close its own subscription from inside on().
    for (AgentSubscriber subscriber : List.copyOf(subscribers)) {
      try {
        subscriber.on(event);
      } catch (RuntimeException failure) {
        LOG.warn("[{}] a subscriber threw and was ejected", agentId.value(), failure);
        remove(agentId, subscriber);
      }
    }
  }

  private void remember(AgentId agentId, AgentEvent event) {
    Deque<AgentEvent> buffer = recent.computeIfAbsent(agentId, _ -> new ArrayDeque<>());
    synchronized (buffer) {
      if (buffer.size() == BUFFERED_EVENTS) {
        buffer.removeFirst();
      }
      buffer.addLast(event);
    }
  }

  /** A snapshot safe to iterate without holding the buffer's own lock. */
  private List<AgentEvent> snapshotFor(AgentId agentId) {
    Deque<AgentEvent> buffer = recent.computeIfAbsent(agentId, _ -> new ArrayDeque<>());
    synchronized (buffer) {
      return List.copyOf(buffer);
    }
  }

  private void remove(AgentId agentId, AgentSubscriber subscriber) {
    watchers.computeIfPresent(
        agentId,
        (_, subscribers) -> {
          subscribers.remove(subscriber);
          return subscribers.isEmpty() ? null : subscribers;
        });
  }
}
