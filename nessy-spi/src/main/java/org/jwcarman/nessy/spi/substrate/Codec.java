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
 * layer neither constructs nor mutates the mapper it is given, and inspects neither the mapper's
 * configuration nor {@code type} before binding (substrate spec §3, §7, the 2026-08-22 repeal): no
 * babysitting a caller's own Jackson setup. Every type binds through {@code readValue}/{@code
 * writeValueAsBytes} directly — including a sealed interface type, which rides whatever
 * polymorphism {@code mapper} resolves for it (annotations on the type, a mix-in, a custom
 * introspector; configure Jackson however you like) exactly as any other Jackson caller would get.
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
   * A tolerant UTF-8 JSON codec for {@code type}: a literal {@code writeValueAsBytes}/{@code
   * readValue} pair through {@code mapper}, exactly as {@code mapper} is configured — this call
   * inspects neither {@code type} nor the mapper's configuration first. A sealed interface {@code
   * type} binds through whatever polymorphism {@code mapper} resolves for it —
   * {@code @JsonTypeInfo}/{@code @JsonSubTypes} directly on the type, a {@code
   * mapper.addMixIn(...)}, a custom {@code AnnotationIntrospector} — the same vocabulary a tool
   * input's schema/binding would also ride (spec §3, json-repeal 2026-08-22). An unannotated sealed
   * {@code type} simply gets Jackson's own natural behavior: no discriminator is ever written, so
   * decoding fails with Jackson's own error — translated at this boundary like any other malformed
   * input, not guarded against up front. The only thing this layer owns is that boundary: Jackson's
   * checked exceptions never leak past it, malformed bytes or an unknown discriminator or a shape
   * mismatch all surface as {@link IllegalArgumentException} naming the offense. Jackson's own
   * behavior on a permitted record whose own component happens to share the discriminator's
   * property name — verified empirically to be a silent duplicate-key/overwrite, not a thrown
   * exception — is likewise not guarded against here; that is the caller's own Jackson
   * configuration to own, same as any other Jackson application.
   */
  static <T> Codec<T> json(ObjectMapper mapper, Class<T> type) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    Objects.requireNonNull(type, "type must not be null");
    return CodecSupport.json(mapper, type);
  }
}
