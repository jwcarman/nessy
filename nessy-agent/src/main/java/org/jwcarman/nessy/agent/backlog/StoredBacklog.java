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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The {@code backlog} recipe (substrate spec §6.4): one document per scope, keyed by {@code
 * agentId}, holding the pending observations as a plain JSON array of strings — no codec involved,
 * just Jackson. An absent document reads as an empty queue; the document is created lazily on the
 * first {@link #add(String)}. {@code add}/{@code poll} are read-mutate-CAS-retry loops; a full
 * queue is rejected with an {@link IllegalStateException}, the bound the deleted {@code
 * BoundedBacklog} used to enforce (spec §12).
 */
public final class StoredBacklog implements Backlog<String> {

  private static final String KIND = "backlog";

  private final Substrate store;
  private final String agentId;
  private final int capacity;
  private final ObjectMapper mapper;

  public StoredBacklog(Substrate store, String agentId, int capacity, ObjectMapper mapper) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
    }
    this.capacity = capacity;
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  @Override
  public void add(String observation) {
    Objects.requireNonNull(observation, "observation must not be null");
    while (true) {
      Optional<Substrate.Document> doc = store.read(KIND, agentId);
      List<String> queue =
          doc.map(d -> readQueue(new String(d.payload(), StandardCharsets.UTF_8)))
              .orElseGet(ArrayList::new);
      if (queue.size() >= capacity) {
        throw new IllegalStateException("backlog full (capacity " + capacity + ")");
      }
      queue.add(observation);
      long expectedVersion = doc.map(Substrate.Document::version).orElse(0L);
      try {
        store.write(
            KIND, agentId, writeQueue(queue).getBytes(StandardCharsets.UTF_8), expectedVersion);
        return;
      } catch (ConflictException e) {
        // another writer changed the queue between our read and our write; retry
      }
    }
  }

  @Override
  public Optional<String> poll() {
    while (true) {
      Optional<Substrate.Document> doc = store.read(KIND, agentId);
      if (doc.isEmpty()) {
        return Optional.empty();
      }
      List<String> queue = readQueue(new String(doc.get().payload(), StandardCharsets.UTF_8));
      if (queue.isEmpty()) {
        return Optional.empty();
      }
      String head = queue.remove(0);
      try {
        store.write(
            KIND, agentId, writeQueue(queue).getBytes(StandardCharsets.UTF_8), doc.get().version());
        return Optional.of(head);
      } catch (ConflictException e) {
        // another writer changed the queue between our read and our write; retry
      }
    }
  }

  private List<String> readQueue(String payload) {
    try {
      String[] values = mapper.readValue(payload, String[].class);
      return new ArrayList<>(List.of(values));
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("malformed backlog payload", e);
    }
  }

  private String writeQueue(List<String> queue) {
    try {
      return mapper.writeValueAsString(queue);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("unwritable backlog payload", e);
    }
  }
}
