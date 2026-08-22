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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Internal storage machinery: the one {@link ObjectMapper} shared by every codec that renders the
 * string-payload substrate's JSON (spec §7), plus the tree-reading helpers common to all of them.
 * Public so recipes outside the {@code codec} package (e.g. {@code
 * org.jwcarman.nessy.agent.durable.OutcomeCodec}) can reuse the same discriminator/tolerant-read
 * conventions instead of duplicating them; still not API vocabulary — no domain type carries a
 * Jackson annotation.
 */
public final class Codecs {

  public static final ObjectMapper MAPPER = new ObjectMapper();

  private Codecs() {}

  /** The required field {@code name} on {@code node}, as text — malformed payload otherwise. */
  public static String requireText(ObjectNode node, String name, String owner) {
    JsonNode field = node.get(name);
    if (field == null || !field.isTextual()) {
      throw new IllegalArgumentException(owner + " missing required field: " + name);
    }
    return field.asText();
  }

  /** The required field {@code name} on {@code node} — malformed payload otherwise. */
  public static JsonNode requireField(ObjectNode node, String name, String owner) {
    JsonNode field = node.get(name);
    if (field == null) {
      throw new IllegalArgumentException(owner + " missing required field: " + name);
    }
    return field;
  }

  /**
   * The required field {@code name} on {@code node}, as an array — malformed payload otherwise. A
   * scalar or object value for {@code name} would otherwise iterate as zero elements and read as a
   * silent empty collection; this fails loudly instead.
   */
  public static ArrayNode requireArray(ObjectNode node, String name, String owner) {
    JsonNode field = node.get(name);
    if (field == null || !field.isArray()) {
      throw new IllegalArgumentException(owner + " field must be an array: " + name);
    }
    return (ArrayNode) field;
  }

  /**
   * The required field {@code name} on {@code node}, as a boolean — malformed payload otherwise.
   */
  public static boolean requireBoolean(ObjectNode node, String name, String owner) {
    JsonNode field = node.get(name);
    if (field == null || !field.isBoolean()) {
      throw new IllegalArgumentException(owner + " missing required field: " + name);
    }
    return field.asBoolean();
  }

  /** {@code node} as an object node, or a malformed-payload {@link IllegalArgumentException}. */
  public static ObjectNode requireObject(JsonNode node, String owner) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("malformed " + owner + ": expected an object");
    }
    return (ObjectNode) node;
  }

  /**
   * {@code json} parsed as an object node, or a malformed-payload {@link IllegalArgumentException}.
   */
  public static ObjectNode readObject(String json, String owner) {
    JsonNode node;
    try {
      node = MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("malformed " + owner + " JSON", e);
    }
    return requireObject(node, owner + " JSON");
  }
}
