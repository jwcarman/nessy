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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CodecTest {

  record Address(String city, String state) {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Restart.class, name = "Restart"),
    @JsonSubTypes.Type(value = Shutdown.class, name = "Shutdown")
  })
  sealed interface Vocabulary permits Restart, Shutdown {}

  record Restart(String host) implements Vocabulary {}

  record Shutdown(String reason) implements Vocabulary {}

  /**
   * Carries no Jackson annotations of its own — {@link
   * SealedVocabularies#aVocabularyAnnotatedOnlyThroughAMapperRegisteredMixInRoundTrips} registers
   * {@link MixInVocabularyPolymorphism} on a fresh mapper via {@code addMixIn} instead: {@code
   * Codec.json} inspects neither the type nor the mapper's configuration, so binding works exactly
   * the same regardless of how the caller attached the polymorphism info.
   */
  sealed interface MixInVocabulary permits MixInRestart {}

  record MixInRestart(String host) implements MixInVocabulary {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({@JsonSubTypes.Type(value = MixInRestart.class, name = "MixInRestart")})
  interface MixInVocabularyPolymorphism {}

  /**
   * A nested sealed hierarchy: {@code NestedOps} is itself a sealed interface, permitted by {@code
   * NestedVocabulary} alongside the directly-permitted {@code NestedRestart}. The discriminator
   * names on {@code NestedVocabulary}'s own {@code @JsonSubTypes} are what binding actually reads
   * (Jackson, not permits-walking) — {@code NestedRestart}, reached directly, round-trips through
   * {@code Codec.json(MAPPER, NestedVocabulary.class)} the same as any other permitted member.
   */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = NestedRestart.class, name = "Restart"),
    @JsonSubTypes.Type(value = NestedDiagnose.class, name = "Diagnose")
  })
  sealed interface NestedVocabulary permits NestedRestart, NestedOps {}

  record NestedRestart(String host) implements NestedVocabulary {}

  sealed interface NestedOps extends NestedVocabulary permits NestedDiagnose {}

  record NestedDiagnose(String target) implements NestedOps {}

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

    /**
     * {@code Codec.json} inspects neither {@code type} nor the mapper's configuration before
     * binding: a vocabulary with zero Jackson annotations of its own, but a polymorphism mix-in
     * registered on the mapper, binds and round-trips exactly like a directly-annotated one would —
     * no construction-time guard stands between a caller and whatever their own mapper resolves.
     */
    @Test
    void aVocabularyAnnotatedOnlyThroughAMapperRegisteredMixInRoundTrips() {
      ObjectMapper mixInMapper = new ObjectMapper();
      mixInMapper.addMixIn(MixInVocabulary.class, MixInVocabularyPolymorphism.class);
      Codec<MixInVocabulary> codec = Codec.json(mixInMapper, MixInVocabulary.class);
      MixInVocabulary original = new MixInRestart("prod-eu");

      MixInVocabulary decoded = codec.decode(codec.encode(original));

      assertThat(decoded).isEqualTo(original);
    }

    @Test
    void aDirectlyPermittedRecordOfANestedSealedHierarchyRoundTrips() {
      Codec<NestedVocabulary> codec = Codec.json(MAPPER, NestedVocabulary.class);
      NestedVocabulary original = new NestedRestart("prod-eu");

      NestedVocabulary decoded = codec.decode(codec.encode(original));

      assertThat(decoded).isEqualTo(original);
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
}
