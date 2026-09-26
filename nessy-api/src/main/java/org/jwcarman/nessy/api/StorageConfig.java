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
package org.jwcarman.nessy.api;

import org.jwcarman.codec.Codec;

/**
 * What happens to every byte the engine stores, beyond Jackson: compression, encryption, both.
 *
 * <p>Filled in through a {@link Customizer Customizer&lt;StorageConfig&gt;} bean, the same way
 * every other piece of the engine is configured. {@link #append(Codec)} is the only verb, and it is
 * append-only on purpose: Jackson is always at the front of the composed transform, so no
 * customizer can put encryption (or anything else) ahead of serialization.
 */
public interface StorageConfig {

  /**
   * Applied after Jackson on the way in, and before it on the way out.
   *
   * <p>A second call composes outward: the transform given first runs closest to Jackson, and each
   * later one wraps the ones before it.
   */
  StorageConfig append(Codec<byte[]> transform);
}
