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
package org.jwcarman.nessy.spi.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The storage kernel (spec §2): two shapes — a document store (mutable current-truth, addressed by
 * {@code (kind, key)}) and a journal (immutable history, addressed by {@code (kind, key, seq)}) —
 * plus one atomic batch across both. Payloads are opaque, non-null strings the kernel never parses;
 * JSON is the house convention but the contract itself only says "string" (spec §4.5). Every
 * mutation carries a CAS expectation and a miss is a {@link ConflictException}, never a wait (spec
 * §4.1–§4.2); implementations must be safe for concurrent use (spec §4.7).
 */
public interface ScopedStore {

  /**
   * The current document at {@code (kind, key)}, or empty if none has ever been written or the last
   * write was a delete.
   *
   * @throws NullPointerException if {@code kind} or {@code key} is null
   */
  Optional<Document> read(String kind, String key);

  /**
   * Writes the document at {@code (kind, key)} under CAS. {@code expectedVersion == 0} creates the
   * document at version 1 — a document already present is a conflict. {@code expectedVersion == v}
   * (v &gt; 0) succeeds iff the stored version is exactly {@code v}, storing at {@code v + 1} (spec
   * §4.1).
   *
   * @throws NullPointerException if {@code kind}, {@code key}, or {@code payload} is null
   * @throws ConflictException if the stored version does not match {@code expectedVersion}
   */
  void write(String kind, String key, String payload, long expectedVersion);

  /**
   * Deletes the document at {@code (kind, key)} under the same CAS discipline as {@link
   * #write(String, String, String, long)}.
   *
   * @throws NullPointerException if {@code kind} or {@code key} is null
   * @throws ConflictException if the stored version does not match {@code expectedVersion}
   */
  void delete(String kind, String key, long expectedVersion);

  /**
   * Document keys under {@code kind}, in ascending lexicographic order, at most {@code limit}
   * results (spec §4.4).
   *
   * @throws NullPointerException if {@code kind} is null
   * @throws IllegalArgumentException if {@code limit} is less than 1
   */
  List<String> keys(String kind, int limit);

  /**
   * Appends an entry to the journal at {@code (kind, key)}, create-only: the entry is created at
   * exactly {@code expectedSeq}; an entry already at that seq is a conflict, and the caller
   * re-reads the head and retries (spec §4.2). Sequences start at 1.
   *
   * @throws NullPointerException if {@code kind}, {@code key}, or {@code payload} is null
   * @throws ConflictException if an entry already exists at {@code expectedSeq}
   */
  void append(String kind, String key, long expectedSeq, String payload);

  /**
   * Journal entries at {@code (kind, key)} from {@code fromSeq} (inclusive) in ascending order.
   * Empty for an unknown key.
   *
   * @throws NullPointerException if {@code kind} or {@code key} is null
   */
  List<Entry> entries(String kind, String key, long fromSeq);

  /**
   * Applies {@code ops} atomically: all succeed or none apply. Any CAS or seq miss fails the whole
   * batch with {@link ConflictException} and leaves every shape it touches untouched (spec §4.3).
   *
   * @throws NullPointerException if {@code ops} is null
   * @throws ConflictException if any op's expectation is not met
   */
  void batch(List<Op> ops);

  /** A document's current payload, version, and last-write timestamp. */
  record Document(String payload, long version, Instant updatedAt) {}

  /** One journal entry: its sequence, payload, and append timestamp. */
  record Entry(long seq, String payload, Instant appendedAt) {}

  /** One operation a {@link #batch(List)} call applies. */
  sealed interface Op {

    /** Writes a document under CAS, as {@link ScopedStore#write(String, String, String, long)}. */
    record WriteDocument(String kind, String key, String payload, long expectedVersion)
        implements Op {}

    /** Deletes a document under CAS, as {@link ScopedStore#delete(String, String, long)}. */
    record DeleteDocument(String kind, String key, long expectedVersion) implements Op {}

    /** Appends a journal entry, as {@link ScopedStore#append(String, String, long, String)}. */
    record AppendEntry(String kind, String key, long seq, String payload) implements Op {}
  }
}
