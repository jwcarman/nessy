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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.spi.substrate.Codec;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The {@code backlog} recipe (substrate spec §6.4): one document per scope, keyed by {@code
 * agentId}, holding the pending observations as a JSON array of strings — each element the base64
 * of one observation's {@link Codec#encode(Object)}, uniform regardless of what {@code codec}
 * actually is. An absent document reads as an empty queue; the document is created lazily on the
 * first {@link #add(Object)}. {@code add}/{@code poll} are read-mutate-CAS-retry loops; a full
 * queue is rejected with an {@link IllegalStateException}, the bound the deleted {@code
 * BoundedBacklog} used to enforce (spec §12).
 *
 * <p>The outer array-of-strings envelope is hand-written, not Jackson-bound: every element is
 * base64 ({@code [A-Za-z0-9+/=]}), so nothing in it is ever JSON-escapable, and a shared static
 * {@link com.fasterxml.jackson.databind.ObjectMapper} would be exactly the ambient statics this
 * branch (Task 3) exists to retire. Only the elements' meaning is caller-controlled, through {@code
 * codec}.
 *
 * @param <O> the observation vocabulary this backlog holds
 */
public final class SubstrateBacklog<O> implements Backlog<O> {

  private static final String KIND = "backlog";

  private final Substrate store;
  private final String agentId;
  private final int capacity;
  private final Codec<O> codec;

  public SubstrateBacklog(Substrate store, String agentId, int capacity, Codec<O> codec) {
    this.store = Objects.requireNonNull(store, "store must not be null");
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
      Optional<Substrate.Document> doc = store.read(KIND, agentId);
      List<String> queue =
          doc.map(d -> readQueue(new String(d.payload(), StandardCharsets.UTF_8)))
              .orElseGet(ArrayList::new);
      if (queue.size() >= capacity) {
        throw new IllegalStateException("backlog full (capacity " + capacity + ")");
      }
      queue.add(encoded);
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
        return Optional.of(codec.decode(Base64.getDecoder().decode(head)));
      } catch (ConflictException e) {
        // another writer changed the queue between our read and our write; retry
      }
    }
  }

  /**
   * Parses the hand-written {@code ["base64","base64",...]} envelope. Every element is base64
   * ({@code [A-Za-z0-9+/=]}), so a bare split-and-strip is exact — no escaping is possible inside
   * an element to confuse it with the {@code ","} separator or the {@code "\""} quote.
   */
  private List<String> readQueue(String payload) {
    String trimmed = payload.strip();
    if (trimmed.equals("[]")) {
      return new ArrayList<>();
    }
    if (trimmed.length() < 2
        || trimmed.charAt(0) != '['
        || trimmed.charAt(trimmed.length() - 1) != ']') {
      throw new IllegalArgumentException("malformed backlog payload");
    }
    String inner = trimmed.substring(1, trimmed.length() - 1);
    List<String> elements = new ArrayList<>();
    for (String element : inner.split(",", -1)) {
      String stripped = element.strip();
      if (stripped.length() < 2
          || stripped.charAt(0) != '"'
          || stripped.charAt(stripped.length() - 1) != '"') {
        throw new IllegalArgumentException("malformed backlog payload");
      }
      elements.add(stripped.substring(1, stripped.length() - 1));
    }
    return elements;
  }

  /** Writes the {@code ["base64","base64",...]} envelope; {@code "[]"} for an empty queue. */
  private String writeQueue(List<String> queue) {
    if (queue.isEmpty()) {
      return "[]";
    }
    return "[\"" + String.join("\",\"", queue) + "\"]";
  }
}
