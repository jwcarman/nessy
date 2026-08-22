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
package org.jwcarman.nessy.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.substrate.Codec;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

class SubstrateIntentStoreTest {

  /** A plainly-pinned mapper — tolerant reads, same as the substrate's format contract. */
  private static final ObjectMapper MAPPER =
      new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  @Nested
  class Declaring {

    @Test
    void anUnwrittenStoreHoldsNoDeclarationBeforeAnyDeclaration() {
      var store =
          new SubstrateIntentStore<>(new InMemorySubstrate(), "agent-a", Intent.class, MAPPER);

      assertThat(store.latest()).isEmpty();
    }

    @Test
    void aSecondDeclarationReplacesTheFirstLastWriteWins() {
      var store =
          new SubstrateIntentStore<>(new InMemorySubstrate(), "agent-a", Intent.class, MAPPER);

      store.declare(new Intent("first declaration"));
      store.declare(new Intent("second declaration"));

      assertThat(store.latest()).contains(new Intent("second declaration"));
    }

    @Test
    void aStoredDeclarationWithAnUnknownFieldStillReads() {
      var substrate = new InMemorySubstrate();
      substrate.write(
          "intent",
          "agent-a",
          "{\"declaration\":\"restart prod-eu\",\"futureField\":\"not yet invented\"}"
              .getBytes(StandardCharsets.UTF_8),
          0);
      var store = new SubstrateIntentStore<>(substrate, "agent-a", Intent.class, MAPPER);

      assertThat(store.latest()).contains(new Intent("restart prod-eu"));
    }
  }

  @Nested
  class Two_store_views_over_one_substrate {

    @Test
    void shareTheDeclaration() {
      var substrate = new InMemorySubstrate();
      var writer = new SubstrateIntentStore<>(substrate, "agent-a", Intent.class, MAPPER);
      var reader = new SubstrateIntentStore<>(substrate, "agent-a", Intent.class, MAPPER);

      writer.declare(new Intent("restart prod-eu to clear the stuck deploy"));

      assertThat(reader.latest()).contains(new Intent("restart prod-eu to clear the stuck deploy"));
    }
  }

  @Nested
  class Sealed_vocabulary {

    sealed interface OpsIntent permits Restart, Diagnose {}

    record Restart(String target, String reason) implements OpsIntent {}

    record Diagnose(String target) implements OpsIntent {}

    @Test
    void aDeclarationRoundTripsThroughTheClassToken() {
      var store =
          new SubstrateIntentStore<>(new InMemorySubstrate(), "agent-a", OpsIntent.class, MAPPER);

      store.declare(new Restart("prod-eu", "stuck deploy"));

      assertThat(store.latest()).contains(new Restart("prod-eu", "stuck deploy"));
    }

    @Test
    void aDifferentPermittedShapeRoundTripsThroughTheClassTokenToo() {
      var store =
          new SubstrateIntentStore<>(new InMemorySubstrate(), "agent-a", OpsIntent.class, MAPPER);

      store.declare(new Diagnose("prod-eu"));

      assertThat(store.latest()).contains(new Diagnose("prod-eu"));
    }
  }

  @Nested
  class A_cas_conflict_on_declare {

    @Test
    void retriesAndTheRetriedDeclarationStillWins() {
      var substrate = new InMemorySubstrate();
      var raced =
          new SubstrateIntentStore<>(
              new RaceOnceOnWriteSubstrate(substrate), "agent-a", Intent.class, MAPPER);

      raced.declare(new Intent("restart prod-eu to clear the stuck deploy"));

      var readBack = new SubstrateIntentStore<>(substrate, "agent-a", Intent.class, MAPPER);
      assertThat(readBack.latest())
          .contains(new Intent("restart prod-eu to clear the stuck deploy"));
    }
  }

  @Nested
  class A_custom_codec {

    @Test
    void isHonoredByBothWritesAndReads() {
      var substrate = new InMemorySubstrate();
      Codec<Intent> codec = Codec.json(MAPPER, Intent.class).then(new MarkerBytesCodec());
      var store = new SubstrateIntentStore<>(substrate, "agent-a", codec);

      store.declare(new Intent("restart prod-eu"));

      byte[] rawPayload = substrate.read("intent", "agent-a").orElseThrow().payload();
      assertThat(MarkerBytesCodec.isMarked(rawPayload)).isTrue();
      assertThat(store.latest()).contains(new Intent("restart prod-eu"));
    }
  }

  /**
   * A trivial byte-transform codec: prepends a fixed marker to every encoded payload and strips it
   * back off on decode. Chained onto a {@code Codec<T>} via {@link Codec#then(Codec)}, it proves a
   * caller-supplied codec is actually honored by the recipe — the substrate's raw stored bytes
   * carry the marker, and a read still round-trips through it. A local hand-rolled equivalent of
   * nessy-agent's own {@code MarkerBytesCodec} test support, which this module cannot depend on
   * (design authority: nessy-intent depends only on nessy-api and nessy-spi).
   */
  private static final class MarkerBytesCodec implements Codec<byte[]> {

    private static final byte[] MARKER = "MARKER:".getBytes(StandardCharsets.UTF_8);

    @Override
    public byte[] encode(byte[] value) {
      byte[] marked = new byte[MARKER.length + value.length];
      System.arraycopy(MARKER, 0, marked, 0, MARKER.length);
      System.arraycopy(value, 0, marked, MARKER.length, value.length);
      return marked;
    }

    @Override
    public byte[] decode(byte[] bytes) {
      return Arrays.copyOfRange(bytes, MARKER.length, bytes.length);
    }

    static boolean isMarked(byte[] payload) {
      return payload.length >= MARKER.length
          && Arrays.equals(payload, 0, MARKER.length, MARKER, 0, MARKER.length);
    }
  }

  /**
   * Simulates one lost race on {@link #write}: the first call is preceded by a competitor's write
   * landing first at the very {@code expectedVersion} the caller is targeting, so the delegate
   * throws a genuine {@code ConflictException}; every later write goes straight through. A local
   * hand-rolled equivalent of {@code RaceOnceOnWriteSubstrate} (nessy-agent's own test support),
   * which this module cannot depend on (design authority: nessy-intent depends only on nessy-api
   * and nessy-spi).
   */
  private static final class RaceOnceOnWriteSubstrate implements Substrate {

    private final Substrate delegate;
    private boolean raced;

    private RaceOnceOnWriteSubstrate(Substrate delegate) {
      this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public Optional<Document> read(String kind, String key) {
      return delegate.read(kind, key);
    }

    @Override
    public void write(String kind, String key, byte[] payload, long expectedVersion) {
      if (!raced) {
        raced = true;
        delegate.write(
            kind,
            key,
            "{\"declaration\":\"a competing declaration\"}".getBytes(StandardCharsets.UTF_8),
            expectedVersion);
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
  }
}
