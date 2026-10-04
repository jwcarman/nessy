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

package org.jwcarman.nessy.backend.jdbc;

import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.api.IdentityCodec;

/**
 * The value codec and the storage transform, composed the way the auto-configuration composes them:
 * for every store but the payloads, which are given the two apart.
 */
final class StorageCodecs {

  private StorageCodecs() {}

  /** The plain factory itself when the transform is the identity, so nothing wraps it for free. */
  static CodecFactory compose(CodecFactory values, Codec<byte[]> transform) {
    if (transform == IdentityCodec.INSTANCE) {
      return values;
    }
    return new CodecFactory() {
      @Override
      public <T> Codec<T> create(TypeRef<T> type) {
        return values.create(type).andThen(transform);
      }
    };
  }
}
