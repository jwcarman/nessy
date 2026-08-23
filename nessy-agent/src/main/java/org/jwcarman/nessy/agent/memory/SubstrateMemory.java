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
package org.jwcarman.nessy.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.agent.codec.MessageCodec;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.Remembrance;
import org.jwcarman.nessy.spi.substrate.Codec;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.DocumentStore;
import org.jwcarman.nessy.spi.substrate.JournalStore;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The {@code memory} recipe (substrate spec §6.2, remembrance spec §3): one journal per scope,
 * keyed by {@code agentId}, one entry per {@link Remembrance}. Idempotence is a per-remembrance
 * {@link RememberedMarker} document (kind {@code memory-keys}, key {@code agentId + "/" +
 * remembrance.key()}), CAS-written CREATE-ONLY in the SAME {@link Substrate#batch} as the journal
 * append it guards: the marker create succeeding IS "not yet remembered"; a {@link
 * ConflictException} on that exact marker IS "already remembered" — {@link #remember(Remembrance)}
 * is then a no-op, which is how re-remembering the same key converges to one remembered fact (the
 * SPI's own idempotence law). This is O(1) per call and never reads a growing list: fix round 1 Q5
 * retired the earlier one-document-per-scope-list design, which would have decoded and rewritten an
 * ever-larger document on every single {@code remember}.
 *
 * <p>{@link #remember(Remembrance)} never decodes the transcript to find the next sequence — it
 * reads the RAW {@link Substrate#entries(String, String, long)} (undecoded {@code byte[]} payloads,
 * the {@code DeliveryWorker#currentMemoryHead} shape this class inherited) and takes the last seq +
 * 1. This matters for a caller-supplied {@link Codec}: its decode failures stay confined to {@link
 * #recall()}, the only place this class ever decodes a stored entry to append one (fix round 1 Q5).
 *
 * <p>On a lost race (a genuine one — near-zero in practice since a scope's own fold is already
 * serialized upstream), the batch's {@link ConflictException} is disambiguated by checking whether
 * THIS remembrance's own marker now exists: if it does, a racer remembered the same key first and
 * this call converges (returns); if it does not, the conflict was the journal append losing a seq
 * race against a DIFFERENT remembrance, and the loop retries with a freshly re-read head.
 *
 * <p>{@link #recall()} folds every entry from seq 1 through {@link RemembranceFold}, the same
 * reassembly {@link VerbatimMemory} shares.
 *
 * <p>Wire compatibility (spec §6): a transcript written before this reform is a bare {@code
 * Message} per entry, with no {@code "type"} discriminator — the decoder recognizes the absence of
 * one and reads it back as a {@link JournalEntry.Legacy}, which {@link RemembranceFold} emits
 * verbatim rather than trying to re-pair it. Nothing here ever re-encodes a legacy entry; every
 * write this class makes is {@link JournalEntry.Fresh}.
 *
 * <p>The stored shape binds through a {@code Codec<JournalEntry>}, over a {@code
 * JournalStore<JournalEntry>} (typed-stores spec §1): {@link #SubstrateMemory(Substrate, String,
 * ObjectMapper)} defaults to the built-in binding (JSON via {@code mapper}, with the legacy
 * fallback above); {@link #SubstrateMemory(Substrate, String, Codec)} accepts a caller-supplied
 * {@code Codec<Remembrance>} directly — a transform chained on with {@link Codec#then(Codec)}
 * (encryption, compression) or a test probe. A custom codec owns its own decode failure and
 * legacy-reading story; the built-in fallback above is this class's own.
 */
public final class SubstrateMemory implements Memory {

  private static final String KIND = "memory";
  private static final String KEYS_KIND = "memory-keys";

  private final Substrate store;
  private final String agentId;
  private final JournalStore<JournalEntry> journal;
  private final DocumentStore<RememberedMarker> markers;

  /**
   * Defaults the stored shape to the built-in {@link Remembrance} JSON binding over {@code mapper}.
   */
  public SubstrateMemory(Substrate store, String agentId, ObjectMapper mapper) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
    this.journal =
        store.journal(
            KIND,
            new DefaultJournalEntryCodec(
                Objects.requireNonNull(mapper, "mapper must not be null")));
    this.markers = store.document(KEYS_KIND, RememberedMarker.class);
  }

  /**
   * Accepts a caller-supplied {@code Codec<Remembrance>} directly — the same escape hatch the
   * pre-reform constructor offered over {@code Codec<Message>}, moved onto this reform's own domain
   * shape (a transform chained on with {@link Codec#then(Codec)}, or a test probe).
   */
  public SubstrateMemory(Substrate store, String agentId, Codec<Remembrance> codec) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
    this.journal =
        store.journal(
            KIND,
            new CustomJournalEntryCodec(Objects.requireNonNull(codec, "codec must not be null")));
    this.markers = store.document(KEYS_KIND, RememberedMarker.class);
  }

  @Override
  public void remember(Remembrance remembrance) {
    Objects.requireNonNull(remembrance, "remembrance must not be null");
    String markerKey = markerKey(remembrance.key());
    while (true) {
      long nextSeq = rawHeadSeq() + 1L;
      List<Substrate.Op> ops =
          List.of(
              journal.appendOp(agentId, nextSeq, new JournalEntry.Fresh(remembrance)),
              markers.writeOp(markerKey, new RememberedMarker(remembrance.key()), 0L));
      try {
        store.batch(ops);
        return;
      } catch (ConflictException _) {
        if (markers.exists(markerKey)) {
          return; // this exact key was already remembered — converges (remembrance spec §1 law 2)
        }
        // else: the journal append lost a seq race against a DIFFERENT remembrance — re-read the
        // raw head and retry with a fresh seq
      }
    }
  }

  @Override
  public Context recall() {
    RemembranceFold fold = new RemembranceFold();
    for (JournalEntry entry : journal.entries(agentId, 1)) {
      switch (entry) {
        case JournalEntry.Fresh(var remembrance) -> fold.add(remembrance);
        case JournalEntry.Legacy(var message) -> fold.addLegacy(message);
      }
    }
    return fold.toContext();
  }

  /** The RAW (undecoded) journal head — see this class's own javadoc for why. */
  private long rawHeadSeq() {
    List<Substrate.Entry> entries = store.entries(KIND, agentId, 1);
    return entries.isEmpty() ? 0L : entries.getLast().seq();
  }

  private String markerKey(String remembranceKey) {
    return agentId + "/" + remembranceKey;
  }

  /**
   * The built-in binding: JSON via a caller-supplied, already-pinned {@link ObjectMapper} — a
   * {@link Remembrance} encodes through its own {@code @JsonTypeInfo}/{@code @JsonSubTypes}
   * polymorphism; a decode presented with no {@code "type"} property is read back as a legacy,
   * pre-reform {@code Message} entry instead (spec §6).
   */
  private static final class DefaultJournalEntryCodec implements Codec<JournalEntry> {

    private static final String TYPE_PROPERTY = "type";

    private final ObjectMapper mapper;
    private final MessageCodec legacyMessageCodec;

    DefaultJournalEntryCodec(ObjectMapper mapper) {
      this.mapper = mapper;
      this.legacyMessageCodec = new MessageCodec(mapper);
    }

    @Override
    public byte[] encode(JournalEntry value) {
      if (value instanceof JournalEntry.Fresh(var remembrance)) {
        try {
          return mapper.writeValueAsBytes(remembrance);
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("failed to encode remembrance: " + e.getMessage(), e);
        }
      }
      throw new IllegalStateException(
          "a legacy journal entry is never re-encoded — it is a read-only artifact of transcripts"
              + " written before the remembrance reform (spec §6)");
    }

    @Override
    public JournalEntry decode(byte[] bytes) {
      Objects.requireNonNull(bytes, "bytes must not be null");
      String json = new String(bytes, StandardCharsets.UTF_8);
      JsonNode root;
      try {
        root = mapper.readTree(json);
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException("malformed memory journal entry: " + e.getMessage(), e);
      }
      if (root.has(TYPE_PROPERTY)) {
        try {
          return new JournalEntry.Fresh(mapper.treeToValue(root, Remembrance.class));
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("malformed remembrance: " + e.getMessage(), e);
        }
      }
      return new JournalEntry.Legacy(legacyMessageCodec.message(json));
    }
  }

  /**
   * A caller-supplied {@code Codec<Remembrance>}, wrapped to the journal's own entry shape.
   * Fresh-only by construction: nothing offers this codec bytes it did not itself just encode
   * within the SAME process's write path, so there is no legacy-reading story to own here — a
   * caller that needs one composes it into their own {@code Codec<Remembrance>}.
   */
  private static final class CustomJournalEntryCodec implements Codec<JournalEntry> {

    private final Codec<Remembrance> delegate;

    CustomJournalEntryCodec(Codec<Remembrance> delegate) {
      this.delegate = delegate;
    }

    @Override
    public byte[] encode(JournalEntry value) {
      if (value instanceof JournalEntry.Fresh(var remembrance)) {
        return delegate.encode(remembrance);
      }
      throw new IllegalStateException("a legacy journal entry is never re-encoded");
    }

    @Override
    public JournalEntry decode(byte[] bytes) {
      return new JournalEntry.Fresh(delegate.decode(bytes));
    }
  }
}
