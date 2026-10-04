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

package org.jwcarman.nessy.spring.boot;

import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.api.IdentityCodec;

/**
 * The two layers behind the context's {@link CodecFactory}: the value codec, and the storage
 * transform applied after it.
 *
 * <p>A backend gets them apart so a payload's reference can be a hash of its content before the
 * transform. The {@link #composed} factory is the very one the context hands every other store;
 * when the context's {@code CodecFactory} bean is that instance, the backend takes the two layers,
 * and when an application has declared a {@code CodecFactory} of its own, it is not, and the
 * backend uses that bean as it always has.
 *
 * @param values the value codec, with no transform applied
 * @param transform the storage transform, or {@link IdentityCodec#INSTANCE}
 * @param composed {@code values} with {@code transform} appended to every codec it creates
 */
record StorageLayers(CodecFactory values, Codec<byte[]> transform, CodecFactory composed) {

  static StorageLayers of(CodecFactory values, Codec<byte[]> transform) {
    return new StorageLayers(values, transform, compose(values, transform));
  }

  private static CodecFactory compose(CodecFactory values, Codec<byte[]> transform) {
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

  /** Whether {@code codecs} is the factory these layers were composed into. */
  boolean composedInto(CodecFactory codecs) {
    return composed == codecs;
  }
}
