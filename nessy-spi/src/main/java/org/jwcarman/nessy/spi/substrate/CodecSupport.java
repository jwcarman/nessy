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
package org.jwcarman.nessy.spi.substrate;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Package-private implementations backing {@link Codec}'s default and static factory methods. Kept
 * out of the interface body (and out of the public API entirely — this class and every nested type
 * here are package-private) so the published {@code nessy-spi} jar exposes exactly one new public
 * type for this seam: {@link Codec} itself.
 */
final class CodecSupport {

  private CodecSupport() {}

  static <T> Codec<T> then(Codec<T> first, Codec<byte[]> next) {
    return new ThenCodec<>(first, next);
  }

  /**
   * {@code type.isInterface() && type.isSealed()} routes to {@link SealedJsonCodec}, a
   * self-contained inline copy of the discriminator dispatch the deleted {@code SealedInputs}
   * (repealed 2026-08-22, substrate spec §7) used to provide. TODO(json-repeal task 2): this
   * permits-walking dispatch is temporary — nessy-intent's rewrite removes {@link SealedJsonCodec}
   * entirely once its stores bind sealed vocabularies through their own Jackson annotations, the
   * same way tool inputs now do.
   */
  static <T> Codec<T> json(ObjectMapper mapper, Class<T> type) {
    return type.isInterface() && type.isSealed()
        ? new SealedJsonCodec<>(mapper, type)
        : new PlainJsonCodec<>(mapper, type);
  }

  /** {@link Codec#then(Codec)}'s composed codec. */
  private static final class ThenCodec<T> implements Codec<T> {

    private final Codec<T> first;
    private final Codec<byte[]> next;

    private ThenCodec(Codec<T> first, Codec<byte[]> next) {
      this.first = first;
      this.next = next;
    }

    @Override
    public byte[] encode(T value) {
      return next.encode(first.encode(value));
    }

    @Override
    public T decode(byte[] bytes) {
      return first.decode(next.decode(bytes));
    }
  }

  /**
   * {@code Codec.json} for a plain (non-sealed) type: direct {@code readValue}/{@code
   * writeValueAsBytes}.
   */
  private static final class PlainJsonCodec<T> implements Codec<T> {

    private final ObjectMapper mapper;
    private final Class<T> type;

    private PlainJsonCodec(ObjectMapper mapper, Class<T> type) {
      this.mapper = mapper;
      this.type = type;
    }

    @Override
    public byte[] encode(T value) {
      Objects.requireNonNull(value, "value must not be null");
      try {
        return mapper.writeValueAsBytes(value);
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException(
            "failed to encode " + type.getSimpleName() + " to JSON: " + e.getMessage(), e);
      }
    }

    @Override
    public T decode(byte[] bytes) {
      Objects.requireNonNull(bytes, "bytes must not be null");
      try {
        return mapper.readValue(bytes, type);
      } catch (IOException e) {
        throw new IllegalArgumentException(
            "failed to decode " + type.getSimpleName() + " from JSON: " + e.getMessage(), e);
      }
    }
  }

  /**
   * {@code Codec.json} for a sealed interface type. When {@code type} itself carries
   * {@code @JsonTypeInfo} (the standard Jackson annotation a caller may already have added, e.g. so
   * the same vocabulary also rides a tool input's schema/binding), this defers entirely to
   * Jackson's own polymorphic machinery — no inline dispatch involved. Otherwise it falls back to
   * encoding with a {@code "type"} discriminator naming the concrete permitted record and decoding
   * by matching that discriminator back against {@link Class#getPermittedSubclasses()} — the same
   * convention the deleted {@code SealedInputs} used, inlined here (see {@link #json} TODO) rather
   * than reintroducing a dependency on the deleted class. The unannotated encode path checks the
   * value's runtime class is a direct permitted subclass of {@code type} before writing anything —
   * a class reached only through a nested sealed vocabulary would write a discriminator {@link
   * #decode} could never match back on decode, so that case fails loudly here instead.
   */
  private static final class SealedJsonCodec<T> implements Codec<T> {

    private final ObjectMapper mapper;
    private final Class<T> type;
    private final boolean jacksonAnnotated;

    private SealedJsonCodec(ObjectMapper mapper, Class<T> type) {
      this.mapper = mapper;
      this.type = type;
      this.jacksonAnnotated = type.isAnnotationPresent(JsonTypeInfo.class);
    }

    @Override
    public byte[] encode(T value) {
      Objects.requireNonNull(value, "value must not be null");
      if (jacksonAnnotated) {
        try {
          return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException(
              "failed to encode " + type.getSimpleName() + " to JSON: " + e.getMessage(), e);
        }
      }
      Class<?> runtimeType = value.getClass();
      if (!Arrays.asList(type.getPermittedSubclasses()).contains(runtimeType)) {
        throw new IllegalArgumentException(
            "cannot encode "
                + runtimeType.getSimpleName()
                + ": not a direct permitted subclass of "
                + type.getSimpleName());
      }
      if (Stream.of(runtimeType.getRecordComponents()).anyMatch(c -> c.getName().equals("type"))) {
        throw new IllegalArgumentException(
            "vocabulary record "
                + runtimeType.getSimpleName()
                + " declares a component named \"type\", which collides with the discriminator");
      }
      JsonNode tree = mapper.valueToTree(value);
      if (!(tree instanceof ObjectNode objectNode)) {
        throw new IllegalArgumentException(
            "cannot encode " + runtimeType.getSimpleName() + ": not a JSON object");
      }
      objectNode.put("type", runtimeType.getSimpleName());
      try {
        return mapper.writeValueAsBytes(objectNode);
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException(
            "failed to encode " + runtimeType.getSimpleName() + " to JSON: " + e.getMessage(), e);
      }
    }

    @Override
    public T decode(byte[] bytes) {
      Objects.requireNonNull(bytes, "bytes must not be null");
      JsonNode tree;
      try {
        tree = mapper.readTree(bytes);
      } catch (IOException e) {
        throw new IllegalArgumentException(
            "failed to decode " + type.getSimpleName() + " from JSON: " + e.getMessage(), e);
      }
      if (tree == null) {
        throw new IllegalArgumentException(
            "failed to decode " + type.getSimpleName() + " from JSON: empty payload");
      }
      if (jacksonAnnotated) {
        try {
          return mapper.convertValue(tree, type);
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException(
              "failed to decode " + type.getSimpleName() + " from JSON: " + e.getMessage(), e);
        }
      }
      return bind(tree);
    }

    /**
     * Reads {@code "type"}, matches a permitted record's simple name, binds the remaining
     * properties into that record. Missing/unknown {@code "type"} and a matched permit that is not
     * a record both fail loudly, naming the offense, exactly as {@code SealedInputs#bind} did.
     */
    private T bind(JsonNode tree) {
      Class<?>[] permitted = type.getPermittedSubclasses();
      String requestedType = tree.isObject() ? tree.path("type").asText(null) : null;
      Class<?> matched = requestedType == null ? null : matching(permitted, requestedType);
      if (matched == null) {
        throw new IllegalArgumentException(
            "unknown \"type\" for "
                + type.getSimpleName()
                + ": "
                + (requestedType == null ? "<missing>" : requestedType)
                + "; expected one of: "
                + legalTypeNames(permitted));
      }
      RecordComponent[] components = matched.getRecordComponents();
      if (components == null) {
        throw new IllegalArgumentException(
            "permitted type "
                + matched.getSimpleName()
                + " of sealed vocabulary "
                + type.getSimpleName()
                + " is not a record");
      }
      ObjectNode remainder = ((ObjectNode) tree).deepCopy();
      remainder.remove("type");
      return type.cast(mapper.convertValue(remainder, matched));
    }

    private static Class<?> matching(Class<?>[] permitted, String requestedType) {
      for (Class<?> candidate : permitted) {
        if (candidate.getSimpleName().equals(requestedType)) {
          return candidate;
        }
      }
      return null;
    }

    private static String legalTypeNames(Class<?>[] permitted) {
      return Arrays.stream(permitted).map(Class::getSimpleName).collect(Collectors.joining(", "));
    }
  }
}
