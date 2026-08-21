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
package org.jwcarman.nessy.agent.backlog;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.agent.spi.Backlog;

/**
 * The shared, thread-safe underlay behind many scopes' observation queues (spec §10.11): one map of
 * per-id bounded deques, all sharing a single capacity. {@link #forScope(String)} returns a thin
 * view — a reference to this map plus an id, never a copy of the data. Losing a view loses nothing;
 * two views of the same id observe each other's writes. The map holds one entry per distinct scope
 * id ever touched and never evicts one — this is a single-node, bounded-population choice, not a
 * durable substrate.
 */
public final class InMemoryBacklogSubstrate {

  private final int capacity;
  private final ConcurrentHashMap<String, Deque<String>> scopes = new ConcurrentHashMap<>();

  public InMemoryBacklogSubstrate(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
    }
    this.capacity = capacity;
  }

  /**
   * A thin view over one scope's deque in the shared map — rejection semantics identical to {@link
   * BoundedBacklog}.
   */
  public Backlog<String> forScope(String id) {
    Objects.requireNonNull(id, "id must not be null");
    return new View(id);
  }

  private final class View implements Backlog<String> {

    private final String id;

    private View(String id) {
      this.id = id;
    }

    private Deque<String> queue() {
      return scopes.computeIfAbsent(id, key -> new ArrayDeque<>());
    }

    @Override
    public void add(String observation) {
      Objects.requireNonNull(observation, "observation must not be null");
      Deque<String> queue = queue();
      synchronized (queue) {
        if (queue.size() >= capacity) {
          throw new IllegalStateException("backlog full (capacity " + capacity + ")");
        }
        queue.add(observation);
      }
    }

    @Override
    public Optional<String> poll() {
      Deque<String> queue = queue();
      synchronized (queue) {
        return Optional.ofNullable(queue.poll());
      }
    }
  }
}
