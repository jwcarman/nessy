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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

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
 * readValue}/{@code writeValueAsBytes}; sealed interface types bind through a {@code "type"}
 * discriminator matched against {@link Class#getPermittedSubclasses()} — a temporary inline
 * mechanism (json-repeal task 2 replaces it with the same standard Jackson annotations tool inputs
 * now bind through).
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
    return CodecSupport.then(this, next);
  }

  /**
   * A tolerant UTF-8 JSON codec for {@code type}, bound through {@code mapper}. When {@code type}
   * is a sealed interface (spec §3), encoding adds a {@code "type"} discriminator naming the
   * concrete permitted record's simple name, and decoding matches that discriminator back to its
   * record before binding the remainder. Jackson's checked exceptions never leak past this
   * boundary: malformed bytes, an unknown discriminator, or a shape mismatch all surface as {@link
   * IllegalArgumentException} naming the offense. Encoding a value whose runtime class is not a
   * direct permitted subclass of {@code type} (e.g. a member reached through a nested sealed
   * vocabulary) is rejected the same way, naming the class and the vocabulary, rather than writing
   * a discriminator decoding could never match back.
   */
  static <T> Codec<T> json(ObjectMapper mapper, Class<T> type) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    Objects.requireNonNull(type, "type must not be null");
    return CodecSupport.json(mapper, type);
  }
}
