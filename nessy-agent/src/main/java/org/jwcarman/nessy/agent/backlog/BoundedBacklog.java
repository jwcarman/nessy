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
import java.util.Optional;
import org.jwcarman.nessy.agent.spi.Backlog;

/**
 * A single-scope, fixed-capacity backlog (spec §11, open question 0): a bounded queue whose
 * rejection vocabulary is the exception itself, surfaced at the door — a full backlog refuses the
 * observation rather than growing without bound.
 */
public final class BoundedBacklog<O> implements Backlog<O> {

  private final int capacity;
  private final Deque<O> queue = new ArrayDeque<>();

  public BoundedBacklog(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
    }
    this.capacity = capacity;
  }

  @Override
  public synchronized void add(O observation) {
    if (queue.size() >= capacity) {
      throw new IllegalStateException("backlog full (capacity " + capacity + ")");
    }
    queue.add(observation);
  }

  @Override
  public synchronized Optional<O> poll() {
    return Optional.ofNullable(queue.poll());
  }
}
