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
package org.jwcarman.nessy.api.tool.approval;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.jwcarman.nessy.api.tool.authorization.Key;

/**
 * Typed facts, stored as JSON (approval-lifecycle spec §1.2). {@link Deposits#put} encodes the
 * value through the pinned mapper immediately; {@link #get} decodes to the key's declared type.
 * There is no way to put an unrenderable value in, so there is no way for a request to fail to
 * render — a value the mapper cannot encode fails inside the enricher, at the line that deposited
 * it, naming the key.
 *
 * <p>The document is the storage form: a {@code Facts} is a {@code Map<String, JsonNode>} keyed by
 * {@link Key#name()}, serialized as exactly that. Typed reads need a mapper to decode with — the
 * one the harness pinned, since it may carry user modules — which {@link Deposits#freeze} attaches
 * for the live request and {@link ApprovalRequest#codec} re-attaches after decoding. A bag decoded
 * without one still answers {@link #raw(String)} (the desk and the console render JSON) and refuses
 * {@link #get} with a message saying why.
 */
public final class Facts {

  private final Map<String, JsonNode> entries;
  @JsonIgnore private final ObjectMapper mapper; // null until attached

  private Facts(Map<String, JsonNode> entries, ObjectMapper mapper) {
    this.entries = Collections.unmodifiableSortedMap(new TreeMap<>(entries));
    this.mapper = mapper;
  }

  /** Jackson's door: the document alone, unattached. */
  @JsonCreator
  static Facts fromEntries(Map<String, JsonNode> entries) {
    return new Facts(Objects.requireNonNull(entries, "entries must not be null"), null);
  }

  /** The document — what serializes. */
  @JsonValue
  Map<String, JsonNode> entries() {
    return entries;
  }

  /** The mutable half, alive only during enrichment. */
  public static Deposits deposits(ObjectMapper pinned) {
    return new Deposits(Objects.requireNonNull(pinned, "pinned mapper must not be null"));
  }

  /** A copy of this bag that decodes with {@code pinned}. */
  public Facts attach(ObjectMapper pinned) {
    return new Facts(entries, Objects.requireNonNull(pinned, "pinned mapper must not be null"));
  }

  /** The fact under {@code key}, decoded to its declared type; empty if nothing was deposited. */
  public <T> Optional<T> get(Key<T> key) {
    Objects.requireNonNull(key, "key must not be null");
    JsonNode node = entries.get(key.name());
    if (node == null) {
      return Optional.empty();
    }
    if (mapper == null) {
      throw new IllegalStateException(
          "facts decoded from storage are not attached to a mapper; read raw(\""
              + key.name()
              + "\") or attach(mapper) first");
    }
    try {
      return Optional.of(mapper.treeToValue(node, key.type()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "fact '" + key.name() + "' does not decode as " + key.type().getName(), e);
    }
  }

  /** The fact under {@code name} as JSON, or null — for renderers that never decode. */
  public JsonNode raw(String name) {
    return entries.get(Objects.requireNonNull(name, "name must not be null"));
  }

  /** Every deposited name, sorted. */
  public Set<String> names() {
    return entries.keySet();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Facts other && entries.equals(other.entries);
  }

  @Override
  public int hashCode() {
    return entries.hashCode();
  }

  @Override
  public String toString() {
    return "Facts" + entries;
  }

  /** Enrichment's mutable bag. {@link #freeze} hands back the immutable, attached document. */
  public static final class Deposits {

    private final Map<String, JsonNode> entries = new TreeMap<>();
    private final ObjectMapper pinned;

    private Deposits(ObjectMapper pinned) {
      this.pinned = pinned;
    }

    /**
     * Encodes {@code value} now. A value the mapper cannot render fails HERE, naming the key.
     *
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if the mapper cannot render {@code value}
     */
    public <T> void put(Key<T> key, T value) {
      Objects.requireNonNull(key, "key must not be null");
      Objects.requireNonNull(value, () -> "fact '" + key.name() + "' must not be null");
      try {
        entries.put(key.name(), pinned.valueToTree(value));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "fact '" + key.name() + "' cannot be rendered as JSON: " + e.getMessage(), e);
      }
    }

    public Facts freeze() {
      return new Facts(entries, pinned);
    }
  }
}
