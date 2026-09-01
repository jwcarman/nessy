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
package org.jwcarman.nessy.spi.codec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Objects;
import org.jwcarman.codec.jackson2.Jackson2CodecFactory;
import org.jwcarman.codec.spi.CodecFactory;

/**
 * Internal storage machinery: the mapper-binding boundary every codec that renders the byte-payload
 * substrate's JSON relies on (spec §7). Public so codecs outside this package can reuse the same
 * tolerant-read/exception conventions instead of duplicating them; still not API vocabulary —
 * nessy-owned types carry their own Jackson annotations (spec §7), this class carries none.
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
   * {@code mapper.copy()} with the format-critical settings pinned (spec §7): lower-camel naming,
   * tolerant reads, inclusion pinned to ALWAYS, empty arrays written, root wrapping off, default
   * typing off. User-registered modules and serializers survive the copy — only the wire-format
   * knobs are pinned, because the stored format is a compatibility surface and cannot float on a
   * caller's presentation preferences.
   *
   * <p>This is the SINGLE source of truth for that knob list, so a document's format-critical
   * settings are pinned identically however the mapper reaches a codec — through a harness's {@code
   * .objectMapper(ObjectMapper)} or through a store's own factory.
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

  /**
   * A {@link CodecFactory} over a freshly constructed, pinned mapper.
   *
   * <p>One per caller, never a shared static (statics-die law): a mapper is configurable, and a
   * shared one lets any caller's configuration reach every other caller's stored bytes.
   */
  public static CodecFactory factory() {
    return factory(new ObjectMapper());
  }

  /**
   * A {@link CodecFactory} over {@code mapper}, copy-and-pinned.
   *
   * <p>This is the codec extension point: a caller-configured mapper — modules, naming strategy,
   * mix-ins — flows through every codec derived from it, untouched except for the format-critical
   * knobs {@link #copyAndPin(ObjectMapper)} always applies.
   */
  public static CodecFactory factory(ObjectMapper mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    return new Jackson2CodecFactory(copyAndPin(mapper));
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
   * door a caller uses to build a wire payload it will embed inside a larger document (e.g. one
   * discriminated arm of a sealed result type a hand-rolled codec like {@code ApprovalCodec}
   * assembles) instead of re-parsing a serialized string back into a tree.
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
