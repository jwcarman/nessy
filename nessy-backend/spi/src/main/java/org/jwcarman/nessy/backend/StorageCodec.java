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
package org.jwcarman.nessy.backend;

import java.util.Objects;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TypeRef;

/**
 * What happens to every byte the engine stores, after Jackson has written it and before Jackson
 * reads it back: compression, encryption, both.
 *
 * <p>A {@code byte[] -> byte[]} codec, and nothing more; the codec library's transforms ({@code
 * codec-zstd}, {@code codec-crypto}, ...) are exactly this shape and compose with {@link
 * Codec#andThen}. The type exists so that an application can declare one and be found -- a Spring
 * bean of a plain {@code Codec<byte[]>} names nothing in particular; one of these names the
 * engine's storage.
 *
 * <p>Applied to every row the engine writes: agent state, the story, and outstanding effects. It is
 * not applied to what other libraries store beside them (a notebook, a plan), which decide for
 * themselves.
 *
 * <p><b>TODO(james): revisit this type before it settles.</b> Moved here from the JDBC backend
 * module (2026-09-26, this was purely to break a reactor cycle that no longer applies), but James
 * is on record as not liking it as it stands: "I really don't know that I like that StorageCodec
 * stuff." Three things are tangled together and worth separating before this is called settled:
 *
 * <ul>
 *   <li>It is a marker interface minted for Spring bean lookup, and nothing more -- it adds no
 *       behaviour over {@code Codec<byte[]>} beyond {@link #after(CodecFactory)}. A
 *       {@code @Qualifier} would solve "find the bean that names the engine's storage" without
 *       inventing a type, and without every non-Spring implementer having to learn a Nessy-specific
 *       name for something the codec library already names.
 *   <li>The paragraph this replaced argued for living beside the JDBC backend because only {@code
 *       JdbcDirectBackend} and {@code JdbcQueuedBackend} applied it. That was a fact about where a
 *       reactor cycle happened to push the type, not a reason -- and it stops being true the moment
 *       a second backend applies a storage transform, which is part of why this moved to the SPI.
 *   <li>It is two things in one: a marker (so Spring can find it) and a composition helper ({@link
 *       #after(CodecFactory)}, which wraps every codec a factory makes). The second is real
 *       behaviour that may belong somewhere -- possibly on the factory side -- but it is not the
 *       same concern as being findable, and this type carries both.
 * </ul>
 *
 * <p>The open question: should this become a qualifier plus a plain {@code Codec<byte[]>}, with the
 * composition moved to whoever builds codecs? Nothing here decides that. This is public API
 * surface, so changing it needs James's sign-off before it happens, not after.
 */
public interface StorageCodec extends Codec<byte[]> {

  /** A plain byte transform, named as the engine's. */
  static StorageCodec of(Codec<byte[]> transform) {
    Objects.requireNonNull(transform, "transform must not be null");
    return new StorageCodec() {
      @Override
      public byte[] encode(byte[] bytes) {
        return transform.encode(bytes);
      }

      @Override
      public byte[] decode(byte[] bytes) {
        return transform.decode(bytes);
      }
    };
  }

  /**
   * Every codec the factory makes, with this applied after it on the way in and before it on the
   * way out.
   */
  default CodecFactory after(CodecFactory base) {
    Objects.requireNonNull(base, "base must not be null");
    return new CodecFactory() {
      @Override
      public <T> Codec<T> create(TypeRef<T> type) {
        return base.create(type).andThen(StorageCodec.this);
      }
    };
  }
}
