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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class JournalStoreTest {

  record Note(String text) {}

  /**
   * Simulates one lost race on {@link #append}, mirroring {@code RaceOnceOnAppendSubstrate}
   * (nessy-agent test support): the first append is preceded by a competitor's append landing first
   * at the very {@code expectedSeq} the caller is targeting.
   */
  private static final class RaceOnceOnAppendSubstrate implements Substrate {

    private final Substrate delegate;
    private final byte[] competitorPayload;
    private boolean raced;

    private RaceOnceOnAppendSubstrate(Substrate delegate, byte[] competitorPayload) {
      this.delegate = Objects.requireNonNull(delegate);
      this.competitorPayload = Objects.requireNonNull(competitorPayload);
    }

    @Override
    public Optional<Document> read(String kind, String key) {
      return delegate.read(kind, key);
    }

    @Override
    public void write(String kind, String key, byte[] payload, long expectedVersion) {
      delegate.write(kind, key, payload, expectedVersion);
    }

    @Override
    public void delete(String kind, String key, long expectedVersion) {
      delegate.delete(kind, key, expectedVersion);
    }

    @Override
    public List<String> keys(String kind, int limit) {
      return delegate.keys(kind, limit);
    }

    @Override
    public void append(String kind, String key, long expectedSeq, byte[] payload) {
      if (!raced) {
        raced = true;
        delegate.append(kind, key, expectedSeq, competitorPayload);
      }
      delegate.append(kind, key, expectedSeq, payload);
    }

    @Override
    public List<Entry> entries(String kind, String key, long fromSeq) {
      return delegate.entries(kind, key, fromSeq);
    }

    @Override
    public void batch(List<Op> ops) {
      delegate.batch(ops);
    }

    @Override
    public CodecFactory codecs() {
      return delegate.codecs();
    }
  }

  @Nested
  class AppendingAndReading {

    @Test
    void anAppendedEntryIsReadableFromSeqOne() {
      JournalStore<Note> notes = new InMemorySubstrate().journal("notes", Note.class);

      notes.append("a", new Note("first"));

      assertThat(notes.entries("a", 1)).containsExactly(new Note("first"));
    }

    @Test
    void successiveAppendsLandInOrder() {
      JournalStore<Note> notes = new InMemorySubstrate().journal("notes", Note.class);

      notes.append("a", new Note("first"));
      notes.append("a", new Note("second"));

      assertThat(notes.entries("a", 1)).containsExactly(new Note("first"), new Note("second"));
    }

    @Test
    void anUnknownKeyReadsAsEmpty() {
      JournalStore<Note> notes = new InMemorySubstrate().journal("notes", Note.class);

      assertThat(notes.entries("unknown", 1)).isEmpty();
    }

    @Test
    void appendRetriesPastAConflictInjectedOnTheHeadSeq() {
      InMemorySubstrate backing = new InMemorySubstrate();
      byte[] competitorPayload = backing.codecs().codec(Note.class).encode(new Note("racer"));
      RaceOnceOnAppendSubstrate racing = new RaceOnceOnAppendSubstrate(backing, competitorPayload);
      JournalStore<Note> notes = racing.journal("notes", Note.class);

      notes.append("a", new Note("mine"));

      assertThat(notes.entries("a", 1))
          .containsExactly(new Note("racer"), new Note("mine")); // racer took seq 1; mine landed 2
    }
  }

  @Nested
  class OpMintingComposedIntoARealBatch {

    @Test
    void aMintedAppendOpParticipatesInAnAtomicBatchWithADocumentStore() {
      InMemorySubstrate substrate = new InMemorySubstrate();
      DocumentStore<Note> state = substrate.document("state", Note.class);
      JournalStore<Note> notes = substrate.journal("notes", Note.class);

      substrate.batch(
          List.of(
              state.writeOp("a", new Note("stateful"), 0L),
              notes.appendOp("a", 1, new Note("logged"))));

      assertThat(state.read("a")).contains(new Versioned<>(new Note("stateful"), 1L));
      assertThat(notes.entries("a", 1)).containsExactly(new Note("logged"));
    }
  }

  @Nested
  class KindExplicitMinting {

    record Marker(String label) {}

    @Test
    void twoTypesMintedOverTwoKindsOnOneSubstrateDoNotCrossTalk() {
      InMemorySubstrate substrate = new InMemorySubstrate();
      JournalStore<Note> notes = substrate.journal("notes", Note.class);
      JournalStore<Marker> markers = substrate.journal("markers", Marker.class);

      notes.append("a", new Note("hello"));
      markers.append("a", new Marker("checkpoint"));

      assertThat(notes.entries("a", 1)).containsExactly(new Note("hello"));
      assertThat(markers.entries("a", 1)).containsExactly(new Marker("checkpoint"));
    }
  }
}
