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
package org.jwcarman.nessy.engine.store;

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
