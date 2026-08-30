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
package org.jwcarman.nessy.testing;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentSubscriber;

/**
 * Captures every {@link AgentEvent} handed to it, so tests can assert on what an agent announced.
 *
 * <p>Subscribers are called from engine threads, so the log is a {@link CopyOnWriteArrayList} and
 * every accessor hands back a snapshot: a test asserting while a turn is still running reads a
 * consistent list rather than a half-written one.
 */
public final class RecordingSubscriber implements AgentSubscriber {

  private final List<AgentEvent> received = new CopyOnWriteArrayList<>();

  @Override
  public void on(AgentEvent event) {
    received.add(event);
  }

  /** Every event seen, oldest first. */
  public List<AgentEvent> all() {
    return List.copyOf(received);
  }

  /** Just the events of one variant, oldest first. */
  public <E extends AgentEvent> List<E> ofType(Class<E> type) {
    return received.stream().filter(type::isInstance).map(type::cast).toList();
  }
}
