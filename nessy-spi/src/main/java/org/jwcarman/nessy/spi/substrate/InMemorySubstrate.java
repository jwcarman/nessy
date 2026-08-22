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

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The reference {@link Substrate} substrate: one lock guards a document map and a journal map, both
 * keyed by {@code (kind, key)}. {@link #batch(List)} validates and applies every op against private
 * copies of only the {@code (kind, key)} pairs the batch actually touches — snapshotted out of the
 * live maps, mutated in isolation, merged back only on success — so a conflict anywhere in the
 * batch leaves the live store byte-for-byte as it was (spec §4.3) without ever copying the whole
 * store. This is a single-node, in-process reference implementation, not a durable substrate.
 */
public final class InMemorySubstrate implements Substrate {

  private final Object lock = new Object();
  private final Clock clock;
  private final Map<DocKey, Document> documents = new HashMap<>();
  private final Map<DocKey, NavigableMap<Long, Entry>> journals = new HashMap<>();

  public InMemorySubstrate() {
    this(Clock.systemUTC());
  }

  public InMemorySubstrate(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Optional<Document> read(String kind, String key) {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(key, "key must not be null");
    synchronized (lock) {
      return Optional.ofNullable(documents.get(new DocKey(kind, key)));
    }
  }

  @Override
  public void write(String kind, String key, String payload, long expectedVersion) {
    synchronized (lock) {
      applyWrite(documents, kind, key, payload, expectedVersion, clock.instant());
    }
  }

  @Override
  public void delete(String kind, String key, long expectedVersion) {
    synchronized (lock) {
      applyDelete(documents, kind, key, expectedVersion);
    }
  }

  @Override
  public List<String> keys(String kind, int limit) {
    Objects.requireNonNull(kind, "kind must not be null");
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be at least 1, was " + limit);
    }
    synchronized (lock) {
      return documents.keySet().stream()
          .filter(docKey -> docKey.kind().equals(kind))
          .map(DocKey::key)
          .sorted()
          .limit(limit)
          .toList();
    }
  }

  @Override
  public void append(String kind, String key, long expectedSeq, String payload) {
    synchronized (lock) {
      applyAppend(journals, kind, key, expectedSeq, payload, clock.instant());
    }
  }

  @Override
  public List<Entry> entries(String kind, String key, long fromSeq) {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(key, "key must not be null");
    synchronized (lock) {
      NavigableMap<Long, Entry> journal = journals.get(new DocKey(kind, key));
      if (journal == null) {
        return List.of();
      }
      return List.copyOf(journal.tailMap(fromSeq, true).values());
    }
  }

  @Override
  public void batch(List<Op> ops) {
    Objects.requireNonNull(ops, "ops must not be null");
    List<Op> snapshot = List.copyOf(ops);
    synchronized (lock) {
      Set<DocKey> documentKeys = new HashSet<>();
      Set<DocKey> journalKeys = new HashSet<>();
      for (Op op : snapshot) {
        collectTouchedKey(op, documentKeys, journalKeys);
      }

      Map<DocKey, Document> documentsCopy = new HashMap<>();
      for (DocKey docKey : documentKeys) {
        Document current = documents.get(docKey);
        if (current != null) {
          documentsCopy.put(docKey, current);
        }
      }
      Map<DocKey, NavigableMap<Long, Entry>> journalsCopy = new HashMap<>();
      for (DocKey docKey : journalKeys) {
        NavigableMap<Long, Entry> current = journals.get(docKey);
        if (current != null) {
          journalsCopy.put(docKey, new TreeMap<>(current));
        }
      }

      Instant now = clock.instant();
      for (Op op : snapshot) {
        applyOp(documentsCopy, journalsCopy, op, now);
      }

      for (DocKey docKey : documentKeys) {
        Document result = documentsCopy.get(docKey);
        if (result == null) {
          documents.remove(docKey);
        } else {
          documents.put(docKey, result);
        }
      }
      for (DocKey docKey : journalKeys) {
        journals.put(docKey, journalsCopy.get(docKey));
      }
    }
  }

  private void collectTouchedKey(Op op, Set<DocKey> documentKeys, Set<DocKey> journalKeys) {
    switch (op) {
      case Op.WriteDocument w -> documentKeys.add(new DocKey(w.kind(), w.key()));
      case Op.DeleteDocument d -> documentKeys.add(new DocKey(d.kind(), d.key()));
      case Op.AppendEntry a -> journalKeys.add(new DocKey(a.kind(), a.key()));
    }
  }

  private void applyOp(
      Map<DocKey, Document> documentsCopy,
      Map<DocKey, NavigableMap<Long, Entry>> journalsCopy,
      Op op,
      Instant now) {
    switch (op) {
      case Op.WriteDocument w ->
          applyWrite(documentsCopy, w.kind(), w.key(), w.payload(), w.expectedVersion(), now);
      case Op.DeleteDocument d ->
          applyDelete(documentsCopy, d.kind(), d.key(), d.expectedVersion());
      case Op.AppendEntry a ->
          applyAppend(journalsCopy, a.kind(), a.key(), a.seq(), a.payload(), now);
    }
  }

  /**
   * {@code expectedVersion == 0} means "absent" — a create when the document truly is absent, a
   * stale-write conflict when a version already exists.
   */
  private void applyWrite(
      Map<DocKey, Document> target,
      String kind,
      String key,
      String payload,
      long expectedVersion,
      Instant now) {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(payload, "payload must not be null");
    DocKey docKey = new DocKey(kind, key);
    Document current = target.get(docKey);
    long currentVersion = current == null ? 0L : current.version();
    if (currentVersion != expectedVersion) {
      throw new ConflictException(
          "stale write at kind="
              + kind
              + " key="
              + key
              + ": expected version "
              + expectedVersion
              + " but found "
              + currentVersion);
    }
    target.put(docKey, new Document(payload, expectedVersion + 1, now));
  }

  /**
   * {@code expectedVersion == 0} means "absent", symmetric with {@link #applyWrite}: deleting a
   * genuinely absent document at {@code 0} is an idempotent no-op success; deleting a present one
   * at {@code 0}, or any other version mismatch, is a stale-delete conflict.
   */
  private void applyDelete(
      Map<DocKey, Document> target, String kind, String key, long expectedVersion) {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(key, "key must not be null");
    DocKey docKey = new DocKey(kind, key);
    Document current = target.get(docKey);
    long currentVersion = current == null ? 0L : current.version();
    if (currentVersion != expectedVersion) {
      throw new ConflictException(
          "stale delete at kind="
              + kind
              + " key="
              + key
              + ": expected version "
              + expectedVersion
              + " but found "
              + currentVersion);
    }
    target.remove(docKey);
  }

  private void applyAppend(
      Map<DocKey, NavigableMap<Long, Entry>> target,
      String kind,
      String key,
      long expectedSeq,
      String payload,
      Instant now) {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(payload, "payload must not be null");
    DocKey docKey = new DocKey(kind, key);
    NavigableMap<Long, Entry> journal = target.computeIfAbsent(docKey, unused -> new TreeMap<>());
    if (journal.containsKey(expectedSeq)) {
      throw new ConflictException(
          "stale append at kind="
              + kind
              + " key="
              + key
              + ": an entry already exists at seq "
              + expectedSeq);
    }
    journal.put(expectedSeq, new Entry(expectedSeq, payload, now));
  }

  private record DocKey(String kind, String key) {}
}
