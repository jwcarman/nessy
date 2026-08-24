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

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;

/**
 * The substrate (spec §2): two shapes — a document store (mutable current-truth, addressed by
 * {@code (kind, key)}) and a journal (immutable history, addressed by {@code (kind, key, seq)}) —
 * plus one atomic batch across both. Payloads are opaque, non-null byte arrays the substrate never
 * inspects or constrains; UTF-8 JSON is the house convention above the seam, but the contract
 * itself only says "bytes" (spec §4.5). Every mutation carries a CAS expectation and a miss is a
 * {@link ConflictException}, never a wait (spec §4.1–§4.2); implementations must be safe for
 * concurrent use (spec §4.7).
 */
public interface Substrate {

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
  void write(String kind, String key, byte[] payload, long expectedVersion);

  /**
   * Deletes the document at {@code (kind, key)} under the same CAS discipline as {@link
   * #write(String, String, byte[], long)}: {@code expectedVersion} is what the caller believes is
   * currently stored, and {@code 0} means "I believe this is absent". Deleting a document that is
   * genuinely absent at {@code expectedVersion == 0} is therefore an idempotent success (a no-op);
   * deleting a document that is present at {@code expectedVersion == 0} is a conflict, as is any
   * other version mismatch (spec §4.1).
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
  void append(String kind, String key, long expectedSeq, byte[] payload);

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

  /**
   * This substrate's {@link CodecFactory} (typed-stores spec §1 ruling 3): {@link #document(String,
   * Class)} and {@link #journal(String, Class)} derive their {@link Codec} from it. Implementations
   * satisfy this by extending {@link SubstrateSupport}, which owns one pinned standard Jackson
   * factory per substrate instance (statics-die law); overriding the mapper at construction is the
   * codec extension point.
   */
  CodecFactory codecs();

  /**
   * Mints a {@link DocumentStore} over {@code kind}, kind-explicit (spec ruling 2: {@code kind} is
   * a stable storage name given at the mint, never derived from {@code type}'s class name, so a
   * rename never orphans data). The store's {@link Codec} comes from {@link #codecs()}.
   *
   * @throws NullPointerException if {@code kind} or {@code type} is null
   */
  default <T> DocumentStore<T> document(String kind, Class<T> type) {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(type, "type must not be null");
    return new SubstrateDocumentStore<>(this, kind, codecs().create(type));
  }

  /**
   * Mints a {@link DocumentStore} over {@code kind} with a caller-supplied {@link Codec}, bypassing
   * {@link #codecs()} entirely — the same escape hatch a feature's own caller-supplied- codec
   * constructor offered before this reform (a transform chained with {@link Codec#andThen(Codec)},
   * or a test probe).
   *
   * @throws NullPointerException if {@code kind} or {@code codec} is null
   */
  default <T> DocumentStore<T> document(String kind, Codec<T> codec) {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(codec, "codec must not be null");
    return new SubstrateDocumentStore<>(this, kind, codec);
  }

  /**
   * Mints a {@link JournalStore} over {@code kind}, kind-explicit, mirroring {@link
   * #document(String, Class)}.
   *
   * @throws NullPointerException if {@code kind} or {@code type} is null
   */
  default <T> JournalStore<T> journal(String kind, Class<T> type) {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(type, "type must not be null");
    return new SubstrateJournalStore<>(this, kind, codecs().create(type));
  }

  /**
   * Mints a {@link JournalStore} over {@code kind} with a caller-supplied {@link Codec}, bypassing
   * {@link #codecs()} entirely, mirroring {@link #document(String, Codec)}.
   *
   * @throws NullPointerException if {@code kind} or {@code codec} is null
   */
  default <T> JournalStore<T> journal(String kind, Codec<T> codec) {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(codec, "codec must not be null");
    return new SubstrateJournalStore<>(this, kind, codec);
  }

  /**
   * A document's current payload, version, and last-write timestamp. Content-equal on {@code
   * payload} bytes ({@link Arrays#equals(byte[], byte[])}), never array identity; the payload is
   * defensively copied on construction and on read so no caller can alias stored truth (spec §4.5).
   */
  record Document(byte[] payload, long version, Instant updatedAt) {

    public Document {
      payload = Objects.requireNonNull(payload, SubstrateSupport.PAYLOAD_NULL_MESSAGE).clone();
    }

    @Override
    public byte[] payload() {
      return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other
          instanceof Document(byte[] thatPayload, long thatVersion, Instant thatUpdatedAt))) {
        return false;
      }
      return version == thatVersion
          && Arrays.equals(payload, thatPayload)
          && Objects.equals(updatedAt, thatUpdatedAt);
    }

    @Override
    public int hashCode() {
      return Objects.hash(Arrays.hashCode(payload), version, updatedAt);
    }

    @Override
    public String toString() {
      return "Document[payloadBytes="
          + payload.length
          + ", version="
          + version
          + ", updatedAt="
          + updatedAt
          + "]";
    }
  }

  /**
   * One journal entry: its sequence, payload, and append timestamp. Content-equal on {@code
   * payload} bytes, defensively copied on construction and on read, per {@link Document}.
   */
  record Entry(long seq, byte[] payload, Instant appendedAt) {

    public Entry {
      payload = Objects.requireNonNull(payload, SubstrateSupport.PAYLOAD_NULL_MESSAGE).clone();
    }

    @Override
    public byte[] payload() {
      return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Entry(long thatSeq, byte[] thatPayload, Instant thatAppendedAt))) {
        return false;
      }
      return seq == thatSeq
          && Arrays.equals(payload, thatPayload)
          && Objects.equals(appendedAt, thatAppendedAt);
    }

    @Override
    public int hashCode() {
      return Objects.hash(seq, Arrays.hashCode(payload), appendedAt);
    }

    @Override
    public String toString() {
      return "Entry[seq="
          + seq
          + SubstrateSupport.PAYLOAD_BYTES_LABEL
          + payload.length
          + ", appendedAt="
          + appendedAt
          + "]";
    }
  }

  /** One operation a {@link #batch(List)} call applies. */
  sealed interface Op {

    /**
     * Writes a document under CAS, as {@link Substrate#write(String, String, byte[], long)}.
     * Content-equal on {@code payload} bytes, defensively copied on construction and on read, per
     * {@link Document}.
     */
    record WriteDocument(String kind, String key, byte[] payload, long expectedVersion)
        implements Op {

      public WriteDocument {
        payload = Objects.requireNonNull(payload, SubstrateSupport.PAYLOAD_NULL_MESSAGE).clone();
      }

      @Override
      public byte[] payload() {
        return payload.clone();
      }

      @Override
      public boolean equals(Object other) {
        if (this == other) {
          return true;
        }
        if (!(other
            instanceof
            WriteDocument(
                String thatKind,
                String thatKey,
                byte[] thatPayload,
                long thatExpectedVersion))) {
          return false;
        }
        return expectedVersion == thatExpectedVersion
            && Objects.equals(kind, thatKind)
            && Objects.equals(key, thatKey)
            && Arrays.equals(payload, thatPayload);
      }

      @Override
      public int hashCode() {
        return Objects.hash(kind, key, Arrays.hashCode(payload), expectedVersion);
      }

      @Override
      public String toString() {
        return "WriteDocument[kind="
            + kind
            + ", key="
            + key
            + SubstrateSupport.PAYLOAD_BYTES_LABEL
            + payload.length
            + ", expectedVersion="
            + expectedVersion
            + "]";
      }
    }

    /** Deletes a document under CAS, as {@link Substrate#delete(String, String, long)}. */
    record DeleteDocument(String kind, String key, long expectedVersion) implements Op {}

    /**
     * Appends a journal entry, as {@link Substrate#append(String, String, long, byte[])}.
     * Content-equal on {@code payload} bytes, defensively copied on construction and on read, per
     * {@link Document}.
     */
    record AppendEntry(String kind, String key, long seq, byte[] payload) implements Op {

      public AppendEntry {
        payload = Objects.requireNonNull(payload, SubstrateSupport.PAYLOAD_NULL_MESSAGE).clone();
      }

      @Override
      public byte[] payload() {
        return payload.clone();
      }

      @Override
      public boolean equals(Object other) {
        if (this == other) {
          return true;
        }
        if (!(other
            instanceof
            AppendEntry(String thatKind, String thatKey, long thatSeq, byte[] thatPayload))) {
          return false;
        }
        return seq == thatSeq
            && Objects.equals(kind, thatKind)
            && Objects.equals(key, thatKey)
            && Arrays.equals(payload, thatPayload);
      }

      @Override
      public int hashCode() {
        return Objects.hash(kind, key, seq, Arrays.hashCode(payload));
      }

      @Override
      public String toString() {
        return "AppendEntry[kind="
            + kind
            + ", key="
            + key
            + ", seq="
            + seq
            + SubstrateSupport.PAYLOAD_BYTES_LABEL
            + payload.length
            + "]";
      }
    }
  }
}
