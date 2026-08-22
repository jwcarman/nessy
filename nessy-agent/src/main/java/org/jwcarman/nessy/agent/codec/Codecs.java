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
package org.jwcarman.nessy.agent.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Internal storage machinery: the one {@link ObjectMapper} shared by every codec that renders the
 * string-payload substrate's JSON (spec §7), plus the boundary translation every codec relies on.
 * Public so recipes outside the {@code codec} package (e.g. {@code
 * org.jwcarman.nessy.agent.durable.OutcomeCodec}) can reuse the same tolerant-read/exception
 * conventions instead of duplicating them; still not API vocabulary — nessy-owned types carry their
 * own Jackson annotations (spec §7), this class carries none.
 *
 * <p>Reads are tolerant: unknown fields are ignored. Any {@link JsonProcessingException} — a parse
 * failure, an unresolved discriminator, a canonical-constructor invariant rejecting the payload —
 * is translated here into an {@link IllegalArgumentException} naming the offense; a Jackson
 * exception never leaks past a codec boundary.
 */
public final class Codecs {

  public static final ObjectMapper MAPPER =
      new ObjectMapper()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

  private Codecs() {}

  /** {@code json} parsed to a tree, or a malformed-payload {@link IllegalArgumentException}. */
  public static JsonNode readTree(String json, String owner) {
    try {
      return MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("malformed " + owner + " JSON: " + rootMessage(e), e);
    }
  }

  /**
   * {@code root} bound to {@code type}, or a malformed-payload {@link IllegalArgumentException}.
   */
  public static <T> T bind(JsonNode root, Class<T> type, String owner) {
    try {
      return MAPPER.treeToValue(root, type);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("malformed " + owner + " JSON: " + rootMessage(e), e);
    }
  }

  /**
   * {@code json} bound to {@code type}, or a malformed-payload {@link IllegalArgumentException}.
   */
  public static <T> T read(String json, Class<T> type, String owner) {
    try {
      return MAPPER.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("malformed " + owner + " JSON: " + rootMessage(e), e);
    }
  }

  /** {@code value} rendered to JSON, or an encoding-failure {@link IllegalArgumentException}. */
  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(
          "could not encode " + value.getClass().getSimpleName() + ": " + rootMessage(e), e);
    }
  }

  /**
   * {@code root}'s field {@code name}, if present, must be a JSON array — malformed payload
   * otherwise. A scalar or object value for {@code name} would otherwise fail deep inside binding
   * with a message that may not name the field; this fails loudly and names it up front.
   */
  public static void requireArrayIfPresent(JsonNode root, String name, String owner) {
    JsonNode field = root.get(name);
    if (field != null && !field.isArray()) {
      throw new IllegalArgumentException(owner + " field must be an array: " + name);
    }
  }

  /** The deepest cause's message — Jackson wraps constructor-thrown invariant failures. */
  private static String rootMessage(Throwable t) {
    Throwable deepest = t;
    while (deepest.getCause() != null) {
      deepest = deepest.getCause();
    }
    return deepest.getMessage();
  }
}
