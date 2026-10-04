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
 * The context's {@link CodecFactory}: the value codec with the storage transform appended to every
 * codec it creates, and the two layers still reachable apart.
 *
 * <p>A backend that is handed this factory takes {@link #values} and {@link #transform} instead, so
 * a payload's reference can be a hash of its content before the transform. When an application
 * declares a {@code CodecFactory} of its own, that bean is not one of these, and the backend uses
 * it as given.
 */
final class StorageLayers implements CodecFactory {

  private final CodecFactory values;
  private final Codec<byte[]> transform;

  /**
   * @param values the value codec, with no transform applied
   * @param transform the storage transform, or {@link IdentityCodec#INSTANCE} for none
   */
  StorageLayers(CodecFactory values, Codec<byte[]> transform) {
    this.values = values;
    this.transform = transform;
  }

  CodecFactory values() {
    return values;
  }

  Codec<byte[]> transform() {
    return transform;
  }

  @Override
  public <T> Codec<T> create(TypeRef<T> type) {
    if (transform == IdentityCodec.INSTANCE) {
      return values.create(type);
    }
    return values.create(type).andThen(transform);
  }
}
