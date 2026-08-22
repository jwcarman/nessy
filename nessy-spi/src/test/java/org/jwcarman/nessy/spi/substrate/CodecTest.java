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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CodecTest {

  record Address(String city, String state) {}

  sealed interface Vocabulary permits Restart, Shutdown {}

  record Restart(String host) implements Vocabulary {}

  record Shutdown(String reason) implements Vocabulary {}

  sealed interface CollidingVocabulary permits Ev {}

  record Ev(String type, String body) implements CollidingVocabulary {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Appends {@code marker} to the bytes it sees on encode, strips it back off on decode. */
  private static final class MarkerCodec implements Codec<byte[]> {

    private final byte marker;
    private final List<byte[]> encodeInputs = new ArrayList<>();
    private final List<byte[]> decodeInputs = new ArrayList<>();

    private MarkerCodec(byte marker) {
      this.marker = marker;
    }

    @Override
    public byte[] encode(byte[] value) {
      encodeInputs.add(value.clone());
      byte[] marked = Arrays.copyOf(value, value.length + 1);
      marked[value.length] = marker;
      return marked;
    }

    @Override
    public byte[] decode(byte[] bytes) {
      decodeInputs.add(bytes.clone());
      return Arrays.copyOf(bytes, bytes.length - 1);
    }
  }

  @Nested
  class PlainRecords {

    @Test
    void jsonRoundTripsAPlainRecord() {
      Codec<Address> codec = Codec.json(MAPPER, Address.class);
      Address original = new Address("Columbus", "OH");

      Address decoded = codec.decode(codec.encode(original));

      assertThat(decoded).isEqualTo(original);
    }

    @Test
    void jsonEncodesUtf8Bytes() {
      Codec<Address> codec = Codec.json(MAPPER, Address.class);
      Address original = new Address("Columbus", "OH");

      byte[] encoded = codec.encode(original);

      assertThat(new String(encoded, UTF_8)).contains("Columbus");
    }

    @Test
    void malformedBytesAreRejectedNamingTheOffense() {
      Codec<Address> codec = Codec.json(MAPPER, Address.class);
      byte[] malformed = "not json at all {".getBytes(UTF_8);

      assertThatThrownBy(() -> codec.decode(malformed))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Address");
    }

    @Test
    void aNullValueOnEncodeThrowsNpeWithAMessage() {
      Codec<Address> codec = Codec.json(MAPPER, Address.class);
      assertThatThrownBy(() -> codec.encode(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("value");
    }

    @Test
    void nullBytesOnDecodeThrowNpeWithAMessage() {
      Codec<Address> codec = Codec.json(MAPPER, Address.class);
      assertThatThrownBy(() -> codec.decode(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("bytes");
    }
  }

  @Nested
  class SealedVocabularies {

    @Test
    void jsonRoundTripsOneMemberOfASealedVocabularyViaTheClassToken() {
      Codec<Vocabulary> codec = Codec.json(MAPPER, Vocabulary.class);
      Vocabulary original = new Restart("prod-eu");

      Vocabulary decoded = codec.decode(codec.encode(original));

      assertThat(decoded).isEqualTo(original);
    }

    @Test
    void jsonRoundTripsADifferentMemberOfTheSameSealedVocabulary() {
      Codec<Vocabulary> codec = Codec.json(MAPPER, Vocabulary.class);
      Vocabulary original = new Shutdown("maintenance");

      Vocabulary decoded = codec.decode(codec.encode(original));

      assertThat(decoded).isEqualTo(original);
    }

    @Test
    void unknownDiscriminatorIsRejectedNamingIt() {
      Codec<Vocabulary> codec = Codec.json(MAPPER, Vocabulary.class);
      byte[] bytes = "{\"type\":\"Reboot\",\"host\":\"prod-eu\"}".getBytes(UTF_8);

      assertThatThrownBy(() -> codec.decode(bytes))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Reboot");
    }

    @Test
    void encodingARecordThatDeclaresItsOwnTypeComponentIsRejectedNamingItAndWritesNothing() {
      Codec<CollidingVocabulary> codec = Codec.json(MAPPER, CollidingVocabulary.class);
      Ev original = new Ev("user-value", "payload");

      assertThatThrownBy(() -> codec.encode(original))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Ev")
          .hasMessageContaining("type");
    }
  }

  @Nested
  class ThenOrder {

    @Test
    void encodePassesThroughTheAppendedTransformLast() {
      MarkerCodec marker = new MarkerCodec((byte) '!');
      Codec<Address> plain = Codec.json(MAPPER, Address.class);
      Codec<Address> withMarker = plain.then(marker);
      Address original = new Address("Columbus", "OH");

      byte[] plainBytes = plain.encode(original);
      byte[] result = withMarker.encode(original);

      assertThat(marker.encodeInputs).hasSize(1);
      assertThat(marker.encodeInputs.getFirst()).isEqualTo(plainBytes);
      assertThat(result).hasSize(plainBytes.length + 1);
    }

    @Test
    void decodePassesThroughTheAppendedTransformFirst() {
      MarkerCodec marker = new MarkerCodec((byte) '!');
      Codec<Address> plain = Codec.json(MAPPER, Address.class);
      Codec<Address> withMarker = plain.then(marker);
      Address original = new Address("Columbus", "OH");

      byte[] markedBytes = withMarker.encode(original);
      Address decoded = withMarker.decode(markedBytes);

      assertThat(marker.decodeInputs).hasSize(1);
      assertThat(marker.decodeInputs.getFirst()).isEqualTo(markedBytes);
      assertThat(decoded).isEqualTo(original);
    }

    @Test
    void aStackedChainEncodesAThenBAndDecodesBThenA() {
      MarkerCodec a = new MarkerCodec((byte) 'a');
      MarkerCodec b = new MarkerCodec((byte) 'b');
      Codec<Address> plain = Codec.json(MAPPER, Address.class);
      Codec<Address> stacked = plain.then(a).then(b);
      Address original = new Address("Columbus", "OH");

      byte[] plainBytes = plain.encode(original);
      byte[] result = stacked.encode(original);
      byte[] expectedAfterA = Arrays.copyOf(plainBytes, plainBytes.length + 1);
      expectedAfterA[plainBytes.length] = (byte) 'a';

      // encode order: plain, then a, then b
      assertThat(a.encodeInputs).hasSize(1);
      assertThat(a.encodeInputs.getFirst()).isEqualTo(plainBytes);
      assertThat(b.encodeInputs).hasSize(1);
      assertThat(b.encodeInputs.getFirst()).isEqualTo(expectedAfterA);
      assertThat(result).hasSize(plainBytes.length + 2);
      assertThat(result[result.length - 1]).isEqualTo((byte) 'b');
      assertThat(result[result.length - 2]).isEqualTo((byte) 'a');

      Address decoded = stacked.decode(result);
      assertThat(decoded).isEqualTo(original);
    }

    @Test
    void malformedBytesThroughTheChainAreRejectedNamingTheOffense() {
      MarkerCodec marker = new MarkerCodec((byte) '!');
      Codec<Address> chained = Codec.json(MAPPER, Address.class).then(marker);
      byte[] malformed = marker.encode("not json at all {".getBytes(UTF_8));

      assertThatThrownBy(() -> chained.decode(malformed))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Address");
    }

    @Test
    void unknownDiscriminatorThroughTheChainIsRejectedNamingIt() {
      MarkerCodec marker = new MarkerCodec((byte) '!');
      Codec<Vocabulary> chained = Codec.json(MAPPER, Vocabulary.class).then(marker);
      byte[] bytes = marker.encode("{\"type\":\"Reboot\",\"host\":\"prod-eu\"}".getBytes(UTF_8));

      assertThatThrownBy(() -> chained.decode(bytes))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Reboot");
    }
  }

  @Nested
  class NestedSealedVocabularies {

    sealed interface Vocabulary permits Restart, Ops {}

    record Restart(String host) implements Vocabulary {}

    sealed interface Ops extends Vocabulary permits Diagnose {}

    record Diagnose(String target) implements Ops {}

    @Test
    void encodingAClassReachedOnlyThroughANestedSealedInterfaceFailsLoudly() {
      Codec<Vocabulary> codec = Codec.json(MAPPER, Vocabulary.class);
      Diagnose notADirectPermittedSubclass = new Diagnose("prod-eu");

      assertThatThrownBy(() -> codec.encode(notADirectPermittedSubclass))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Diagnose")
          .hasMessageContaining("Vocabulary");
    }

    @Test
    void encodingADirectlyPermittedRecordOfTheSameNestedVocabularyStillRoundTrips() {
      Codec<Vocabulary> codec = Codec.json(MAPPER, Vocabulary.class);
      Vocabulary original = new Restart("prod-eu");

      Vocabulary decoded = codec.decode(codec.encode(original));

      assertThat(decoded).isEqualTo(original);
    }

    @Test
    void decodingADiscriminatorThatMatchesANonRecordPermitFailsLoudlyNamingItRatherThanNpe() {
      Codec<Vocabulary> codec = Codec.json(MAPPER, Vocabulary.class);
      byte[] bytes = "{\"type\":\"Ops\",\"target\":\"prod-eu\"}".getBytes(UTF_8);

      assertThatThrownBy(() -> codec.decode(bytes))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Ops");
    }
  }
}
