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

import java.util.Collection;
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
 * Who is watching an agent, on this node.
 *
 * <p><b>Deliberately not distributed and not durable.</b> This was a sharded actor so an event
 * could reach a browser attached to another node; without sharding it is a map, and cross-node
 * delivery returns in phase 3 as a Substrate {@code Journal} behind the same {@link Narrator} seam.
 * Nothing about correctness depends on a subscriber existing, which is what makes that swap safe.
 *
 * <p><b>A subscriber that throws is ejected.</b> Narration is best-effort; one broken watcher must
 * not stop a turn or silence the others.
 */
final class Narration {

  private static final Logger LOG = LoggerFactory.getLogger(Narration.class);

  private final Map<AgentId, Set<AgentSubscriber>> watchers = new ConcurrentHashMap<>();

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

  /** Drops every watcher of an agent that is going away. Silent if there were none. */
  void forget(AgentId agentId) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    watchers.remove(agentId);
  }

  private void deliver(AgentId agentId, AgentEvent event) {
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

  private void remove(AgentId agentId, AgentSubscriber subscriber) {
    watchers.computeIfPresent(
        agentId,
        (_, subscribers) -> {
          subscribers.remove(subscriber);
          return subscribers.isEmpty() ? null : subscribers;
        });
  }
}
