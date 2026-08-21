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
package org.jwcarman.nessy.agent.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.Memory;

/**
 * The shared, thread-safe underlay behind many scopes' conversation history (spec §10.11): one map
 * of per-id message lists. {@link #forScope(String)} returns a thin view — a reference to this map
 * plus an id, never a copy of the data. Losing a view loses nothing; two views of the same id
 * observe each other's writes. The map holds one entry per distinct scope id ever touched and never
 * evicts one — this is a single-node, bounded-population choice, not a durable substrate.
 */
public final class InMemoryMemorySubstrate {

  private final ConcurrentHashMap<String, List<Message>> scopes = new ConcurrentHashMap<>();

  /**
   * A thin view over one scope's message list in the shared map — remember/recall semantics
   * identical to {@link VerbatimMemory}.
   */
  public Memory forScope(String id) {
    Objects.requireNonNull(id, "id must not be null");
    return new View(id);
  }

  private List<Message> messagesFor(String id) {
    return scopes.computeIfAbsent(id, key -> new ArrayList<>());
  }

  private final class View implements Memory {

    private final String id;

    private View(String id) {
      this.id = id;
    }

    @Override
    public void remember(Message message) {
      Objects.requireNonNull(message, "message must not be null");
      List<Message> messages = messagesFor(id);
      synchronized (messages) {
        messages.add(message);
      }
    }

    @Override
    public Context recall() {
      List<Message> messages = messagesFor(id);
      synchronized (messages) {
        return Context.of(List.copyOf(messages));
      }
    }
  }
}
