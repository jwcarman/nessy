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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Objects;
import org.jwcarman.nessy.api.tool.SealedInputs;

/**
 * The typed serialization seam above the {@link Substrate}'s opaque {@code byte[]} payloads (spec
 * §3, §7): a recipe stores {@code T} through a {@code Codec<T>} rather than juggling bytes itself.
 * A transform (gzip, encryption) is just a {@code Codec<byte[]>}; {@link #then(Codec)} chains one
 * onto a {@code Codec<T>}, encoding left-to-right and decoding right-to-left, so an
 * enterprise-at-rest story is one line: {@code Codec.json(mapper, type).then(gzip).then(aes)}.
 *
 * <p>{@link #json(ObjectMapper, Class)} is the default binding for user-defined shapes: tolerant
 * UTF-8 JSON via the caller's mapper, so user-registered modules flow through untouched — this
 * layer neither constructs nor mutates the mapper it is given. Plain types bind through {@code
 * readValue}/{@code writeValueAsBytes}; sealed interface types bind the {@link SealedInputs} way —
 * a {@code "type"} discriminator matched against {@link Class#getPermittedSubclasses()} — so sealed
 * vocabularies work unannotated.
 */
public interface Codec<T> {

  /**
   * Encodes {@code value} to bytes.
   *
   * @throws NullPointerException if {@code value} is null
   */
  byte[] encode(T value);

  /**
   * Decodes {@code bytes} back to {@code T}.
   *
   * @throws NullPointerException if {@code bytes} is null
   * @throws IllegalArgumentException if {@code bytes} is malformed, names an unknown discriminator,
   *     or otherwise does not shape into {@code T}
   */
  T decode(byte[] bytes);

  /**
   * Chains a byte-to-byte transform onto this codec: encoding runs this codec then {@code next},
   * left-to-right; decoding runs the chain backwards, {@code next} then this codec, right-to-left.
   */
  default Codec<T> then(Codec<byte[]> next) {
    Objects.requireNonNull(next, "next must not be null");
    return new ThenCodec<>(this, next);
  }

  /**
   * A tolerant UTF-8 JSON codec for {@code type}, bound through {@code mapper}. When {@code type}
   * is a sealed interface (spec §3, {@link SealedInputs}), encoding adds a {@code "type"}
   * discriminator naming the concrete permitted record's simple name, and decoding matches that
   * discriminator back to its record before binding the remainder — exactly {@link SealedInputs}'
   * convention for tool inputs, applied here to stored payloads. Jackson's checked exceptions never
   * leak past this boundary: malformed bytes, an unknown discriminator, or a shape mismatch all
   * surface as {@link IllegalArgumentException} naming the offense.
   */
  static <T> Codec<T> json(ObjectMapper mapper, Class<T> type) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    Objects.requireNonNull(type, "type must not be null");
    return SealedInputs.isSealedInput(type)
        ? new SealedJsonCodec<>(mapper, type)
        : new PlainJsonCodec<>(mapper, type);
  }

  /** {@link #then(Codec)}'s composed codec. */
  final class ThenCodec<T> implements Codec<T> {

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
  final class PlainJsonCodec<T> implements Codec<T> {

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
   * {@code Codec.json} for a sealed interface type: encodes with a {@code "type"} discriminator
   * naming the concrete permitted record, decodes by matching that discriminator the {@link
   * SealedInputs} way.
   */
  final class SealedJsonCodec<T> implements Codec<T> {

    private final ObjectMapper mapper;
    private final Class<T> type;

    private SealedJsonCodec(ObjectMapper mapper, Class<T> type) {
      this.mapper = mapper;
      this.type = type;
    }

    @Override
    public byte[] encode(T value) {
      Objects.requireNonNull(value, "value must not be null");
      JsonNode tree = mapper.valueToTree(value);
      if (!(tree instanceof ObjectNode objectNode)) {
        throw new IllegalArgumentException(
            "cannot encode " + value.getClass().getSimpleName() + ": not a JSON object");
      }
      objectNode.put("type", value.getClass().getSimpleName());
      try {
        return mapper.writeValueAsBytes(objectNode);
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException(
            "failed to encode " + value.getClass().getSimpleName() + " to JSON: " + e.getMessage(),
            e);
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
      return SealedInputs.bind(type, tree, mapper);
    }
  }
}
