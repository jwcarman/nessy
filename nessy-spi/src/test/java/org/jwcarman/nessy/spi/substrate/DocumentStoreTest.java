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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;

class DocumentStoreTest {

  record Counter(int value) {}

  /**
   * Simulates one lost race on {@link #write}, mirroring {@code RaceOnceOnWriteSubstrate}
   * (nessy-agent test support): the first write is preceded by a competitor's write landing first
   * at the very {@code expectedVersion} the caller is targeting, so the delegate throws a genuine
   * {@link ConflictException}; every later write goes straight through.
   */
  private static final class RaceOnceOnWriteSubstrate implements Substrate {

    private final Substrate delegate;
    private final byte[] competitorPayload;
    private boolean raced;

    private RaceOnceOnWriteSubstrate(Substrate delegate, byte[] competitorPayload) {
      this.delegate = Objects.requireNonNull(delegate);
      this.competitorPayload = Objects.requireNonNull(competitorPayload);
    }

    @Override
    public Optional<Document> read(String kind, String key) {
      return delegate.read(kind, key);
    }

    @Override
    public void write(String kind, String key, byte[] payload, long expectedVersion) {
      if (!raced) {
        raced = true;
        delegate.write(kind, key, competitorPayload, expectedVersion);
      }
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
  class ReadingAndWriting {

    @Test
    void readingAnAbsentKeyReturnsEmpty() {
      DocumentStore<Counter> counters = new InMemorySubstrate().document("counters", Counter.class);

      assertThat(counters.read("a")).isEmpty();
    }

    @Test
    void aWriteIsReadableAtItsVersion() {
      DocumentStore<Counter> counters = new InMemorySubstrate().document("counters", Counter.class);

      counters.write("a", new Counter(1), 0L);

      assertThat(counters.read("a")).contains(new Versioned<>(new Counter(1), 1L));
    }

    @Test
    void aStaleWriteThrowsConflict() {
      DocumentStore<Counter> counters = new InMemorySubstrate().document("counters", Counter.class);
      counters.write("a", new Counter(1), 0L);

      assertThatThrownBy(() -> counters.write("a", new Counter(2), 0L))
          .isInstanceOf(ConflictException.class);
    }

    @Test
    void existsIsFalseForAnAbsentKey() {
      DocumentStore<Counter> counters = new InMemorySubstrate().document("counters", Counter.class);

      assertThat(counters.exists("a")).isFalse();
    }

    @Test
    void existsIsTrueForAWrittenKeyWithoutDecodingIt() {
      InMemorySubstrate substrate = new InMemorySubstrate();
      DocumentStore<Counter> lossy =
          new SubstrateDocumentStore<>(
              substrate, "counters", new ByteRoundTripEnforcement.LossyCodec());
      lossy.write("a", new Counter(1), 0L);

      // a codec that can never decode still reports presence correctly — exists() never decodes.
      assertThat(lossy.exists("a")).isTrue();
    }
  }

  @Nested
  class VersionedRoundTrip {

    @Test
    void theValueAndVersionWrittenAreBothReadBackTogether() {
      DocumentStore<Counter> counters = new InMemorySubstrate().document("counters", Counter.class);
      counters.write("a", new Counter(1), 0L);

      Versioned<Counter> first = counters.read("a").orElseThrow();
      counters.write("a", new Counter(2), first.version());
      Versioned<Counter> second = counters.read("a").orElseThrow();

      assertThat(first).isEqualTo(new Versioned<>(new Counter(1), 1L));
      assertThat(second).isEqualTo(new Versioned<>(new Counter(2), 2L));
    }
  }

  @Nested
  class UpdateRetryUnderConflict {

    @Test
    void updateSeedsAnAbsentDocumentThenAppliesFn() {
      DocumentStore<Counter> counters = new InMemorySubstrate().document("counters", Counter.class);

      Counter result = counters.update("a", new Counter(0), c -> new Counter(c.value() + 1));

      assertThat(result).isEqualTo(new Counter(1));
      assertThat(counters.read("a")).contains(new Versioned<>(new Counter(1), 1L));
    }

    @Test
    void updateRetriesPastAConflictInjectedBetweenReadAndWrite() {
      InMemorySubstrate backing = new InMemorySubstrate();
      backing.document("counters", Counter.class).write("a", new Counter(1), 0L); // now at v1
      byte[] competitorPayload = backing.codecs().create(Counter.class).encode(new Counter(99));
      RaceOnceOnWriteSubstrate racing = new RaceOnceOnWriteSubstrate(backing, competitorPayload);
      DocumentStore<Counter> counters = racing.document("counters", Counter.class);

      Counter result = counters.update("a", new Counter(0), c -> new Counter(c.value() + 1));

      // update() reads (1, v1), computes fn(1)=2, and tries to write at expectedVersion=1; the
      // injected competitor write (99) lands first at that exact version, so update()'s own write
      // conflicts and retries — re-reading (99, v2), computing fn(99)=100, and writing that
      // successfully at v2 -> v3. The retry re-reads current truth rather than replaying a stale
      // computation.
      assertThat(result).isEqualTo(new Counter(100));
      assertThat(counters.read("a")).contains(new Versioned<>(new Counter(100), 3L));
    }
  }

  @Nested
  class OpMintingComposedIntoARealBatch {

    @Test
    void aMintedWriteOpParticipatesInAnAtomicBatchWithAnotherStore() {
      InMemorySubstrate substrate = new InMemorySubstrate();
      DocumentStore<Counter> counters = substrate.document("counters", Counter.class);
      DocumentStore<Counter> totals = substrate.document("totals", Counter.class);

      substrate.batch(
          List.of(
              counters.writeOp("a", new Counter(1), 0L), totals.writeOp("a", new Counter(1), 0L)));

      assertThat(counters.read("a")).contains(new Versioned<>(new Counter(1), 1L));
      assertThat(totals.read("a")).contains(new Versioned<>(new Counter(1), 1L));
    }

    @Test
    void aConflictInOneOpFailsTheWholeBatchLeavingBothStoresUntouched() {
      InMemorySubstrate substrate = new InMemorySubstrate();
      DocumentStore<Counter> counters = substrate.document("counters", Counter.class);
      DocumentStore<Counter> totals = substrate.document("totals", Counter.class);
      counters.write("a", new Counter(1), 0L);

      assertThatThrownBy(
              () ->
                  substrate.batch(
                      List.of(
                          counters.writeOp("a", new Counter(2), 0L), // stale: already at version 1
                          totals.writeOp("a", new Counter(1), 0L))))
          .isInstanceOf(ConflictException.class);

      assertThat(totals.read("a")).isEmpty();
    }

    @Test
    void aMintedDeleteOpRemovesTheDocument() {
      InMemorySubstrate substrate = new InMemorySubstrate();
      DocumentStore<Counter> counters = substrate.document("counters", Counter.class);
      counters.write("a", new Counter(1), 0L);

      substrate.batch(List.of(counters.deleteOp("a", 1L)));

      assertThat(counters.read("a")).isEmpty();
    }
  }

  @Nested
  class Keys {

    @Test
    void keysComeBackAscendingUpToTheLimit() {
      DocumentStore<Counter> counters = new InMemorySubstrate().document("counters", Counter.class);
      counters.write("b", new Counter(2), 0L);
      counters.write("a", new Counter(1), 0L);

      assertThat(counters.keys(10)).containsExactly("a", "b");
    }

    @Test
    void keysTruncateAtTheLimit() {
      DocumentStore<Counter> counters = new InMemorySubstrate().document("counters", Counter.class);
      counters.write("a", new Counter(1), 0L);
      counters.write("b", new Counter(2), 0L);

      assertThat(counters.keys(1)).containsExactly("a");
    }
  }

  @Nested
  class VersionAccessor {

    @Test
    void versionIsEmptyForAnAbsentKey() {
      DocumentStore<Counter> counters = new InMemorySubstrate().document("counters", Counter.class);

      assertThat(counters.version("a")).isEmpty();
    }

    @Test
    void versionMatchesTheWrittenDocumentsVersionWithoutDecodingIt() {
      InMemorySubstrate substrate = new InMemorySubstrate();
      DocumentStore<Counter> lossy =
          new SubstrateDocumentStore<>(
              substrate, "counters", new ByteRoundTripEnforcement.LossyCodec());
      lossy.write("a", new Counter(1), 0L);

      // a codec that can never decode still reports its version correctly — version() never
      // decodes, the version-only sibling of exists().
      assertThat(lossy.version("a")).hasValue(1L);
    }
  }

  @Nested
  class KindExplicitMinting {

    record Widget(String value) {}

    @Test
    void twoTypesMintedOverTwoKindsOnOneSubstrateDoNotCrossTalk() {
      InMemorySubstrate substrate = new InMemorySubstrate();
      DocumentStore<Counter> counters = substrate.document("counters", Counter.class);
      DocumentStore<Widget> widgets = substrate.document("widgets", Widget.class);

      counters.write("a", new Counter(7), 0L);
      widgets.write("a", new Widget("gizmo"), 0L);

      assertThat(counters.read("a")).contains(new Versioned<>(new Counter(7), 1L));
      assertThat(widgets.read("a")).contains(new Versioned<>(new Widget("gizmo"), 1L));
    }

    @Test
    void theSameKindStringMintedForADifferentTypeRebindsTheSameUnderlyingBytes() {
      InMemorySubstrate substrate = new InMemorySubstrate();
      DocumentStore<Counter> counters = substrate.document("things", Counter.class);
      DocumentStore<Widget> widgets = substrate.document("things", Widget.class);

      // same kind string, different Class<T> tokens at the mint — the kind is the storage
      // identity, not the Java type, so a document written through one shape reads back through
      // an incompatible shape as a decode failure, not silent corruption. Both records share the
      // "value" property name but not its type: a missing property would just default under the
      // pinned mapper's tolerant reads (spec §7), but a String "value" cannot coerce into
      // Counter's int "value" — a genuine type mismatch, which fails regardless of tolerant-read
      // pinning.
      widgets.write("a", new Widget("gizmo"), 0L);

      assertThatThrownBy(() -> counters.read("a")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSameKindMintedTwiceForTheSameTypeSeesTheSameData() {
      InMemorySubstrate substrate = new InMemorySubstrate();
      DocumentStore<Counter> first = substrate.document("counters", Counter.class);
      DocumentStore<Counter> second = substrate.document("counters", Counter.class);

      first.write("a", new Counter(5), 0L);

      assertThat(second.read("a")).contains(new Versioned<>(new Counter(5), 1L));
    }

    @Test
    void differentKindsForTheSameTypeAreIsolated() {
      InMemorySubstrate substrate = new InMemorySubstrate();
      DocumentStore<Counter> a = substrate.document("kind-a", Counter.class);
      DocumentStore<Counter> b = substrate.document("kind-b", Counter.class);

      a.write("x", new Counter(1), 0L);

      assertThat(a.read("x")).isNotEmpty();
      assertThat(b.read("x")).isEmpty();
    }
  }

  @Nested
  class ByteRoundTripEnforcement {

    /** A codec that lies: it never round-trips, exactly the failure mode spec §2 pins. */
    private static final class LossyCodec implements Codec<Counter> {

      @Override
      public byte[] encode(Counter value) {
        return "not json at all".getBytes();
      }

      @Override
      public Counter decode(byte[] bytes) {
        throw new IllegalArgumentException("cannot decode: " + new String(bytes));
      }
    }

    @Test
    void aCodecThatCannotRoundTripFailsTheSameWayThroughTheTypedViewAsItWouldRaw() {
      InMemorySubstrate substrate = new InMemorySubstrate();
      DocumentStore<Counter> lossy =
          new SubstrateDocumentStore<>(substrate, "counters", new LossyCodec());

      lossy.write("a", new Counter(1), 0L);

      assertThatThrownBy(() -> lossy.read("a")).isInstanceOf(IllegalArgumentException.class);
    }
  }
}
