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

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.UnaryOperator;
import org.jwcarman.codec.spi.Codec;

/**
 * The one library implementation of {@link DocumentStore} (typed-stores spec §1 ruling 1), minted
 * by {@link Substrate#document(String, Class)} — never constructed directly outside this package.
 *
 * <p>Exception contract (codec-adoption spec §2): this typed view is where a raw {@code
 * org.jwcarman.codec} {@link UncheckedIOException} — thrown by the external Jackson2 codec on
 * malformed bytes or an encoding failure — gets translated into the teaching {@link
 * IllegalArgumentException} this store's callers have always seen, naming the {@code kind}. A
 * caller-supplied {@link Codec} that already throws its own {@link IllegalArgumentException} (or
 * anything else) rides through untouched.
 */
final class SubstrateDocumentStore<T> implements DocumentStore<T> {

  private final Substrate substrate;
  private final String kind;
  private final Codec<T> codec;

  SubstrateDocumentStore(Substrate substrate, String kind, Codec<T> codec) {
    this.substrate = Objects.requireNonNull(substrate, "substrate must not be null");
    this.kind = Objects.requireNonNull(kind, "kind must not be null");
    this.codec = Objects.requireNonNull(codec, "codec must not be null");
  }

  @Override
  public Optional<Versioned<T>> read(String key) {
    return substrate
        .read(kind, key)
        .map(doc -> new Versioned<>(decode(doc.payload()), doc.version()));
  }

  @Override
  public boolean exists(String key) {
    return substrate.read(kind, key).isPresent();
  }

  @Override
  public OptionalLong version(String key) {
    return substrate
        .read(kind, key)
        .map(Substrate.Document::version)
        .map(OptionalLong::of)
        .orElse(OptionalLong.empty());
  }

  @Override
  public void write(String key, T value, long expectedVersion) {
    Objects.requireNonNull(value, "value must not be null");
    substrate.write(kind, key, encode(value), expectedVersion);
  }

  @Override
  public T update(String key, T seed, UnaryOperator<T> fn) {
    Objects.requireNonNull(seed, "seed must not be null");
    Objects.requireNonNull(fn, "fn must not be null");
    while (true) {
      Optional<Versioned<T>> existing = read(key);
      T current = existing.map(Versioned::value).orElse(seed);
      long expectedVersion = existing.map(Versioned::version).orElse(0L);
      T next = fn.apply(current);
      try {
        write(key, next, expectedVersion);
        return next;
      } catch (ConflictException _) {
        // another writer changed the document between our read and our write; retry
      }
    }
  }

  @Override
  public List<String> keys(int limit) {
    return substrate.keys(kind, limit);
  }

  /**
   * {@code codec.decode}, translating a raw {@link UncheckedIOException} from the external codec
   * into the teaching {@link IllegalArgumentException} this view has always thrown for a malformed
   * {@code kind} payload (codec-adoption spec §2).
   */
  private T decode(byte[] payload) {
    try {
      return codec.decode(payload);
    } catch (UncheckedIOException e) {
      throw new IllegalArgumentException(
          "failed to decode " + kind + " payload: " + e.getMessage(), e);
    }
  }

  /**
   * {@code codec.encode}, translating a raw {@link UncheckedIOException} from the external codec
   * into the teaching {@link IllegalArgumentException} this view has always thrown for an
   * unencodable {@code kind} value (codec-adoption spec §2).
   */
  private byte[] encode(T value) {
    try {
      return codec.encode(value);
    } catch (UncheckedIOException e) {
      throw new IllegalArgumentException(
          "failed to encode " + kind + " payload: " + e.getMessage(), e);
    }
  }
}
