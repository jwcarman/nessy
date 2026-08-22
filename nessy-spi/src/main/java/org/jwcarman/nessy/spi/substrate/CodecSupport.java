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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Objects;

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
   * One plain tolerant-binding path (substrate spec §3, §7 repeal): {@code writeValueAsBytes}/
   * {@code readValue} through {@code mapper}, exactly as {@code mapper} is configured — no
   * inspection of {@code type} beyond that. A sealed interface {@code type} rides whatever
   * polymorphism {@code mapper} resolves for it (annotations on the type, a mix-in, a custom {@code
   * AnnotationIntrospector} — Nessy does not care which); an unannotated sealed type simply gets
   * Jackson's own natural behavior, translated at this boundary like any other malformed or
   * unbindable input (no discriminator ever gets written, so nothing this layer wrote could ever
   * decode back through the same unannotated type — Jackson's own failure names that, same as it
   * would for any Jackson caller).
   */
  static <T> Codec<T> json(ObjectMapper mapper, Class<T> type) {
    return new JsonCodec<>(mapper, type);
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
   * {@code Codec.json} for any type: a literal {@code writeValueAsBytes}/{@code readValue} pair
   * through {@code mapper}. Whatever {@code mapper} is configured to do — annotated sealed types,
   * mix-ins, custom modules — happens exactly as it would for any other Jackson caller; this codec
   * inspects neither {@code type} nor the mapper's configuration before binding. The only thing
   * this layer owns is the boundary: a Jackson checked exception never leaks past it, translated
   * into an {@link IllegalArgumentException} naming the offense.
   */
  private static final class JsonCodec<T> implements Codec<T> {

    private final ObjectMapper mapper;
    private final Class<T> type;

    private JsonCodec(ObjectMapper mapper, Class<T> type) {
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
}
