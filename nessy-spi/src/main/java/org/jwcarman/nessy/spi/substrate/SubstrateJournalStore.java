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

import java.util.List;
import java.util.Objects;

/**
 * The one library implementation of {@link JournalStore} (typed-stores spec §1 ruling 1), minted by
 * {@link Substrate#journal(String, Class)} — never constructed directly outside this package.
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
    byte[] payload = codec.encode(value);
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
    return new Substrate.Op.AppendEntry(kind, key, expectedSeq, codec.encode(value));
  }

  @Override
  public List<T> entries(String key, long fromSeq) {
    return substrate.entries(kind, key, fromSeq).stream()
        .map(e -> codec.decode(e.payload()))
        .toList();
  }

  private long head(String key) {
    List<Substrate.Entry> entries = substrate.entries(kind, key, 1);
    return entries.isEmpty() ? 0L : entries.getLast().seq();
  }
}
