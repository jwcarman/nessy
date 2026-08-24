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
import org.jwcarman.codec.spi.Codec;

/**
 * The one library implementation of {@link JournalStore} (typed-stores spec §1 ruling 1), minted by
 * {@link Substrate#journal(String, Class)} — never constructed directly outside this package.
 *
 * <p>Exception contract (codec-adoption spec §2): this typed view is where a raw {@code
 * org.jwcarman.codec} {@link UncheckedIOException} — thrown by the external Jackson2 codec on
 * malformed bytes or an encoding failure — gets translated into the teaching {@link
 * IllegalArgumentException} this store's callers have always seen, naming the {@code kind}. A
 * caller-supplied {@link Codec} that already throws its own {@link IllegalArgumentException} (or
 * anything else) rides through untouched.
 */
final class SubstrateJournalStore<T> implements JournalStore<T> {

  private final Substrate substrate;
  private final String kind;
  private final Codec<T> codec;

  SubstrateJournalStore(Substrate substrate, String kind, Codec<T> codec) {
    this.substrate = Objects.requireNonNull(substrate, "substrate must not be null");
    this.kind = Objects.requireNonNull(kind, "kind must not be null");
    this.codec = Objects.requireNonNull(codec, "codec must not be null");
  }

  @Override
  public void append(String key, T value) {
    Objects.requireNonNull(value, "value must not be null");
    byte[] payload = encode(value);
    while (true) {
      long nextSeq = head(key) + 1;
      try {
        substrate.append(kind, key, nextSeq, payload);
        return;
      } catch (ConflictException _) {
        // another appender took nextSeq first; re-read the head and retry
      }
    }
  }

  @Override
  public Substrate.Op appendOp(String key, long expectedSeq, T value) {
    Objects.requireNonNull(value, "value must not be null");
    return new Substrate.Op.AppendEntry(kind, key, expectedSeq, encode(value));
  }

  @Override
  public List<T> entries(String key, long fromSeq) {
    return substrate.entries(kind, key, fromSeq).stream().map(e -> decode(e.payload())).toList();
  }

  private long head(String key) {
    List<Substrate.Entry> entries = substrate.entries(kind, key, 1);
    return entries.isEmpty() ? 0L : entries.getLast().seq();
  }

  /**
   * {@code codec.decode}, translating a raw {@link UncheckedIOException} from the external codec
   * into the teaching {@link IllegalArgumentException} this view has always thrown for a malformed
   * {@code kind} entry (codec-adoption spec §2).
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
