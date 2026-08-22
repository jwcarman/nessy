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
 * layer neither constructs nor mutates the mapper it is given. Every type binds through {@code
 * readValue}/{@code writeValueAsBytes} directly, one plain path (substrate spec §3, §7, the
 * 2026-08-22 repeal); a sealed interface type rides Jackson's own polymorphic machinery via its own
 * {@code @JsonTypeInfo}/{@code @JsonSubTypes} annotations — the same annotations tool inputs bind
 * through — so the schema shown to a model and the bytes a store persists agree by construction.
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
   * A tolerant UTF-8 JSON codec for {@code type}, bound through {@code mapper}. A plain
   * (non-sealed) type binds through {@code readValue}/{@code writeValueAsBytes} directly. A sealed
   * interface {@code type} must already carry {@code @JsonTypeInfo}/{@code @JsonSubTypes} (the
   * standard annotations, e.g. so the same vocabulary also rides a tool input's schema/binding —
   * spec §3, json-repeal 2026-08-22); this call rejects an unannotated sealed {@code type} with an
   * {@link IllegalArgumentException} naming the exact annotations to add, before any write — plain
   * Jackson would otherwise encode an unannotated sealed value with no discriminator at all,
   * producing bytes nothing could ever decode back. An annotated sealed {@code type} defers wholly
   * to Jackson's own polymorphic machinery: the discriminator property and its per-record values
   * come from {@code @JsonSubTypes}, exactly as Jackson would bind them anywhere else. Jackson's
   * checked exceptions never leak past this boundary: malformed bytes, an unknown discriminator, or
   * a shape mismatch all surface as {@link IllegalArgumentException} naming the offense. A
   * permitted record that declares its own component sharing the discriminator's property name is
   * also rejected before any write, naming the record and the property — Jackson itself does not
   * protect against that collision (verified empirically: it silently duplicates the key on encode
   * and lets the record's own value win over the discriminator on decode).
   */
  static <T> Codec<T> json(ObjectMapper mapper, Class<T> type) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    Objects.requireNonNull(type, "type must not be null");
    return CodecSupport.json(mapper, type);
  }
}
