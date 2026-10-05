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
 * <p>Declared as a single {@code Bean} of this type -- one per application, not a stream of
 * customizer beans -- so the order in which transforms compose is written where an application can
 * see it, rather than decided by bean registration order:
 *
 * <pre>{@code
 * @Bean
 * StorageCodecConfigurer storage() {
 *   return original -> original.andThen(gzip).andThen(aes);
 * }
 * }</pre>
 *
 * <p>A transform need not be deterministic. Encryption with a fresh nonce on every write is fine: a
 * payload's reference is a hash of its content taken before this transform, so the same content is
 * one reference and one copy whatever the transform writes.
 *
 * <p>Take-and-return rather than append-only: {@link #configure(Codec)} is handed the transform
 * assembled so far and returns the transform to use from here on, composed with {@link
 * Codec#andThen}. That shape is what lets this be a plain lambda -- a method returning a fresh
 * {@code Codec<T>} for a caller-chosen {@code T} could not be implemented by one, since a lambda
 * cannot implement a generic method.
 */
@FunctionalInterface
public interface StorageCodecConfigurer {

  /**
   * The transform applied after Jackson on the way in, and before it on the way out.
   *
   * @param original the transform assembled before this configurer ran -- the identity transform
   *     when nothing has been added yet
   * @return the transform to use from here on
   */
  Codec<byte[]> configure(Codec<byte[]> original);
}
