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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.DocumentStore;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.jwcarman.nessy.spi.substrate.Versioned;

/**
 * The {@code backlog} recipe (substrate spec §6.4): one document per scope, keyed by {@code
 * agentId}, holding the pending observations as a JSON array of strings — each element the base64
 * of one observation's {@link Codec#encode(Object)}, uniform regardless of what {@code codec}
 * actually is. An absent document reads as an empty queue; the document is created lazily on the
 * first {@link #add(Object)}. {@code add}/{@code poll} are read-mutate-CAS-retry loops; a full
 * queue is rejected with an {@link IllegalStateException}, the bound the deleted {@code
 * BoundedBacklog} used to enforce (spec §12).
 *
 * <p>The outer array-of-strings envelope is a {@link DocumentStore}{@code <String[]>} (typed-
 * stores spec §1; codec-adoption spec §2): the {@link Substrate#document(String, Class)} mint over
 * {@code String[].class}, which derives from {@code store}'s own {@link Substrate#codecs()} factory
 * — no second {@code CodecFactory} constructed here (codec-adoption spec §2's "one factory at the
 * composition root" rule). Every element is base64 ({@code [A-Za-z0-9+/=]}), so nothing in it is
 * ever JSON-escapable; only the elements' meaning is caller-controlled, through {@code codec}.
 *
 * @param <O> the observation vocabulary this backlog holds
 */
public final class SubstrateBacklog<O> implements Backlog<O> {

  private static final String KIND = "backlog";

  private final DocumentStore<String[]> documents;
  private final String agentId;
  private final int capacity;
  private final Codec<O> codec;

  public SubstrateBacklog(Substrate store, String agentId, int capacity, Codec<O> codec) {
    Objects.requireNonNull(store, "store must not be null");
    this.documents = store.document(KIND, String[].class);
    this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
    }
    this.capacity = capacity;
    this.codec = Objects.requireNonNull(codec, "codec must not be null");
  }

  @Override
  public void add(O observation) {
    Objects.requireNonNull(observation, "observation must not be null");
    String encoded = Base64.getEncoder().encodeToString(codec.encode(observation));
    while (true) {
      Optional<Versioned<String[]>> existing = documents.read(agentId);
      List<String> queue = queueOf(existing);
      if (queue.size() >= capacity) {
        throw new IllegalStateException("backlog full (capacity " + capacity + ")");
      }
      queue.add(encoded);
      long expectedVersion = existing.map(Versioned::version).orElse(0L);
      try {
        documents.write(agentId, queue.toArray(new String[0]), expectedVersion);
        return;
      } catch (ConflictException _) {
        // another writer changed the queue between our read and our write; retry
      }
    }
  }

  /**
   * Polls the head observation, or empty if the queue is absent or empty. Decoding is the very last
   * step, after the CAS write that removes the element has already succeeded: a {@code
   * codec.decode} failure on an already-consumed element is a hard error by design — the element is
   * gone from the queue, and the exception propagates rather than looping to try the next one, so a
   * poison element never silently starves the rest of the backlog behind a retry loop.
   */
  @Override
  public Optional<O> poll() {
    while (true) {
      Optional<Versioned<String[]>> existing = documents.read(agentId);
      if (existing.isEmpty()) {
        return Optional.empty();
      }
      List<String> queue = queueOf(existing);
      if (queue.isEmpty()) {
        return Optional.empty();
      }
      String head = queue.remove(0);
      try {
        documents.write(agentId, queue.toArray(new String[0]), existing.get().version());
        return Optional.of(codec.decode(Base64.getDecoder().decode(head)));
      } catch (ConflictException _) {
        // another writer changed the queue between our read and our write; retry
      }
    }
  }

  private static List<String> queueOf(Optional<Versioned<String[]>> existing) {
    return existing
        .map(versioned -> new ArrayList<>(Arrays.asList(versioned.value())))
        .orElseGet(ArrayList::new);
  }
}
