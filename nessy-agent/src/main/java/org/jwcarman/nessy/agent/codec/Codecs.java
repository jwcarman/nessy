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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Objects;

/**
 * Internal storage machinery: the mapper-binding boundary every codec that renders the byte-payload
 * substrate's JSON relies on (spec §7). Public so recipes outside the {@code codec} package (e.g.
 * {@code org.jwcarman.nessy.agent.OutcomeCodec}) can reuse the same tolerant-read/exception
 * conventions instead of duplicating them; still not API vocabulary — nessy-owned types carry their
 * own Jackson annotations (spec §7), this class carries none.
 *
 * <p>Reads are tolerant: unknown fields are ignored. Any {@link JsonProcessingException} — a parse
 * failure, an unresolved discriminator, a canonical-constructor invariant rejecting the payload —
 * is translated here into an {@link IllegalArgumentException} naming the offense; a Jackson
 * exception never leaks past a codec boundary.
 *
 * <p>An instance wraps one caller-supplied {@link ObjectMapper} — no mapper is ambient here. {@link
 * #copyAndPin(ObjectMapper)} is the one place the host builder's format-critical settings get
 * pinned onto a copy of the caller's mapper (spec §7); every codec downstream binds through that
 * pinned copy, threaded in, never re-derived.
 */
public final class Codecs {

  private final ObjectMapper mapper;

  public Codecs(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  /**
   * {@code mapper.copy()} with the format-critical settings pinned (spec §7): lower-camel property
   * naming, tolerant reads (unknown fields ignored), no default typing. User-registered modules and
   * serializers survive the copy — only the wire-format knobs are pinned, since the stored format
   * is a compatibility surface and cannot float on presentation preferences. {@code
   * FAIL_ON_EMPTY_BEANS} is also disabled on the copy so a zero-component wire record (e.g. an
   * outcome variant with no payload) still renders rather than throwing.
   *
   * <p>Serialization inclusion is pinned to {@code ALWAYS}: a caller mapper configured for {@code
   * NON_EMPTY} (or any other omit-if-default policy) would otherwise survive the copy and drop
   * empty or absent fields from the wire — a recipe whose document round-trips through its own
   * canonical constructor (spec §7) then fails to parse the very document it just wrote. {@code
   * WRITE_EMPTY_JSON_ARRAYS} is pinned {@code true} for the same reason: a per-type {@code
   * configOverride} on the caller's mapper can still ask for {@code NON_EMPTY} on a specific class,
   * and the pin alone does not out-rank that override — a wire record with a collection field that
   * must always render carries its own {@code @JsonInclude(ALWAYS)} to close that route. Root
   * wrapping is pinned off both directions for the same reason: it is a presentation preference,
   * not a format the stored bytes can float on.
   *
   * <p>What the pin does <em>not</em> defend against: a caller mapper with {@code
   * MapperFeature.USE_ANNOTATIONS} disabled, a caller-installed {@code setVisibility} override, or
   * {@code WRITE_SINGLE_ELEM_ARRAYS_UNWRAPPED} enabled. These disable binding wholesale rather than
   * merely omitting empty values, so they are not format-critical settings this pin can restore —
   * they fail loudly at read time instead, and that failure is the caller's own foot.
   */
  public static ObjectMapper copyAndPin(ObjectMapper mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    return mapper
        .copy()
        .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
        .setPropertyInclusion(
            JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS))
        .configure(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS, true)
        .configure(SerializationFeature.WRAP_ROOT_VALUE, false)
        .configure(DeserializationFeature.UNWRAP_ROOT_VALUE, false)
        .deactivateDefaultTyping();
  }

  /** {@code json} parsed to a tree, or a malformed-payload {@link IllegalArgumentException}. */
  public JsonNode readTree(String json, String owner) {
    try {
      return mapper.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("malformed " + owner + " JSON: " + rootMessage(e), e);
    }
  }

  /**
   * {@code root} bound to {@code type}, or a malformed-payload {@link IllegalArgumentException}.
   */
  public <T> T bind(JsonNode root, Class<T> type, String owner) {
    try {
      return mapper.treeToValue(root, type);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("malformed " + owner + " JSON: " + rootMessage(e), e);
    }
  }

  /**
   * {@code value} rendered to a {@link JsonNode} tree rather than a JSON string — the data-born
   * door a caller uses to build a wire payload it will embed inside a larger document (e.g. {@code
   * OutcomeCodec#encodeSuccess}) instead of re-parsing a serialized string back into a tree.
   */
  public JsonNode toTree(Object value) {
    return mapper.valueToTree(value);
  }

  /** {@code value} rendered to JSON, or an encoding-failure {@link IllegalArgumentException}. */
  public String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
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

  /**
   * {@code root}'s field {@code name} must be present and a JSON array — malformed payload
   * otherwise (missing or wrong-typed). A scalar or object value for {@code name}, or an absent
   * key, would otherwise fail deep inside binding with a message that may not name the field; this
   * fails loudly and names it up front.
   */
  public static void requireArray(JsonNode root, String name, String owner) {
    JsonNode field = root.get(name);
    if (field == null || !field.isArray()) {
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
