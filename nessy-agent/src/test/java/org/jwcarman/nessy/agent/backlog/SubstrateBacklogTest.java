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
package org.jwcarman.nessy.agent.backlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.nessy.agent.support.RaceOnceOnWriteSubstrate;
import org.jwcarman.nessy.agent.support.TestCodecs;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.spi.substrate.Codec;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

class SubstrateBacklogTest {

  @Test
  void aNonPositiveCapacityIsRejected() {
    Substrate store = new InMemorySubstrate();
    Codec<String> codec = TestCodecs.utf8String();
    ObjectMapper mapper = TestMappers.plainlyPinned();
    assertThatThrownBy(() -> new SubstrateBacklog<>(store, "agent-a", 0, codec, mapper))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aFreshBacklogPollsEmpty() {
    var backlog =
        new SubstrateBacklog<>(
            new InMemorySubstrate(),
            "agent-a",
            2,
            TestCodecs.utf8String(),
            TestMappers.plainlyPinned());
    assertThat(backlog.poll()).isEmpty();
  }

  @Test
  void addedObservationsPollInFifoOrder() {
    var backlog =
        new SubstrateBacklog<>(
            new InMemorySubstrate(),
            "agent-a",
            3,
            TestCodecs.utf8String(),
            TestMappers.plainlyPinned());
    backlog.add("a");
    backlog.add("b");
    backlog.add("c");
    assertThat(backlog.poll()).contains("a");
    assertThat(backlog.poll()).contains("b");
    assertThat(backlog.poll()).contains("c");
    assertThat(backlog.poll()).isEmpty();
  }

  @Test
  void addBeyondCapacityThrowsTheRejection() {
    var backlog =
        new SubstrateBacklog<>(
            new InMemorySubstrate(),
            "agent-a",
            2,
            TestCodecs.utf8String(),
            TestMappers.plainlyPinned());
    backlog.add("a");
    backlog.add("b");
    assertThatThrownBy(() -> backlog.add("c"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("capacity 2");
  }

  @Test
  void pollingFreesCapacity() {
    var backlog =
        new SubstrateBacklog<>(
            new InMemorySubstrate(),
            "agent-a",
            2,
            TestCodecs.utf8String(),
            TestMappers.plainlyPinned());
    backlog.add("a");
    backlog.add("b");
    assertThat(backlog.poll()).contains("a");
    backlog.add("c");
    assertThat(backlog.poll()).contains("b");
    assertThat(backlog.poll()).contains("c");
    assertThat(backlog.poll()).isEmpty();
  }

  @Test
  void twoViewsOverOneSubstrateShareTheQueue() {
    Substrate store = new InMemorySubstrate();
    var writer =
        new SubstrateBacklog<>(
            store, "agent-a", 4, TestCodecs.utf8String(), TestMappers.plainlyPinned());
    var reader =
        new SubstrateBacklog<>(
            store, "agent-a", 4, TestCodecs.utf8String(), TestMappers.plainlyPinned());

    writer.add("first");
    writer.add("second");

    assertThat(reader.poll()).contains("first");
    assertThat(reader.poll()).contains("second");
    assertThat(reader.poll()).isEmpty();
  }

  @Test
  void addRetriesAfterLosingAWriteConflictAndTheElementStillLands() {
    Substrate raceStore =
        new RaceOnceOnWriteSubstrate(new InMemorySubstrate(), racedInDocument("raced-in"));
    var backlog =
        new SubstrateBacklog<>(
            raceStore, "agent-a", 2, TestCodecs.utf8String(), TestMappers.plainlyPinned());

    backlog.add("mine");

    assertThat(backlog.poll()).contains("raced-in");
    assertThat(backlog.poll()).contains("mine");
    assertThat(backlog.poll()).isEmpty();
  }

  @Test
  void pollRetriesAfterLosingAWriteConflictAndStillRemovesExactlyItsElement() {
    Substrate substrate = new InMemorySubstrate();
    var seeded =
        new SubstrateBacklog<>(
            substrate, "agent-a", 3, TestCodecs.utf8String(), TestMappers.plainlyPinned());
    seeded.add("a");
    seeded.add("b");

    Substrate raceStore = new RaceOnceOnWriteSubstrate(substrate, racedInDocument("a", "b", "c"));
    var backlog =
        new SubstrateBacklog<>(
            raceStore, "agent-a", 3, TestCodecs.utf8String(), TestMappers.plainlyPinned());

    assertThat(backlog.poll()).contains("a");

    var reader =
        new SubstrateBacklog<>(
            substrate, "agent-a", 3, TestCodecs.utf8String(), TestMappers.plainlyPinned());
    assertThat(reader.poll()).contains("b");
    assertThat(reader.poll()).contains("c");
    assertThat(reader.poll()).isEmpty();
  }

  /**
   * Spec §6.4: the stored document is a JSON array whose elements are the base64 of each
   * observation's {@code codec.encode(o)} — uniform regardless of what the codec's shape actually
   * is. Proven here with a record-typed backlog: the raw substrate document is read directly,
   * asserted to be base64 (not the record's plain JSON), and decoded back through the codec to the
   * original record.
   */
  @Test
  void aRecordTypedBacklogStoresBase64OfTheEncodedElementsInTheRawDocument()
      throws JsonProcessingException {
    record Note(String text, int priority) {}

    Substrate substrate = new InMemorySubstrate();
    ObjectMapper mapper = TestMappers.plainlyPinned();
    Codec<Note> codec = Codec.json(mapper, Note.class);
    var backlog = new SubstrateBacklog<>(substrate, "agent-a", 4, codec, mapper);

    var note = new Note("check the oven", 3);
    backlog.add(note);

    Substrate.Document doc = substrate.read("backlog", "agent-a").orElseThrow();
    String rawJson = new String(doc.payload(), StandardCharsets.UTF_8);
    assertThat(rawJson).doesNotContain("check the oven");

    String[] elements = TestMappers.plainlyPinned().readValue(rawJson, String[].class);
    assertThat(elements).isNotEmpty().hasSize(1);
    byte[] decodedBytes = Base64.getDecoder().decode(elements[0]);
    Note decodedNote = codec.decode(decodedBytes);
    assertThat(decodedNote).isEqualTo(note);

    assertThat(backlog.poll()).contains(note);
  }

  /**
   * Negative coverage for the mapper-bound envelope's malformed-payload rejection (json-repeal task
   * 2: the hand-written parser is gone, {@code readQueue} now binds through {@code mapper}). Each
   * seeds the substrate directly with a payload {@code mapper.readValue} cannot parse as a {@code
   * String[]} and asserts {@code poll()} fails loudly, wrapping Jackson's exception, rather than
   * silently misreading it.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("malformedBacklogPayloads")
  void pollRejectsAMalformedPayload(String description, String payload) {
    Substrate substrate = new InMemorySubstrate();
    substrate.write("backlog", "agent-a", payload.getBytes(StandardCharsets.UTF_8), 0L);
    var backlog =
        new SubstrateBacklog<>(
            substrate, "agent-a", 2, TestCodecs.utf8String(), TestMappers.plainlyPinned());

    assertThatThrownBy(backlog::poll)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("malformed backlog payload");
  }

  private static Stream<Arguments> malformedBacklogPayloads() {
    return Stream.of(
        Arguments.of("a payload that is not an array at all", "not an array"),
        Arguments.of("an unquoted element", "[abc]"),
        Arguments.of("an empty envelope that is not the empty array literal", ""),
        Arguments.of("an envelope missing its closing bracket", "[\"abc\""));
  }

  /**
   * Final review round (T2): the poison-decode contract. A codec that throws on decode still lets
   * {@code poll()} remove the element from the queue before decoding is attempted — the exception
   * propagates, but the element is gone, so a second {@code poll()} reaches the next element (or
   * empty) rather than looping forever on the same poison element.
   */
  @Test
  void pollConsumesAPoisonElementBeforePropagatingItsDecodeFailureThenReachesTheNextElement() {
    Codec<String> poisonOnFirst = TestCodecs.poisonOnDecode("boom", "a");
    var backlog =
        new SubstrateBacklog<>(
            new InMemorySubstrate(), "agent-a", 3, poisonOnFirst, TestMappers.plainlyPinned());
    backlog.add("a");
    backlog.add("b");

    assertThatThrownBy(backlog::poll)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("boom");

    assertThat(backlog.poll()).contains("b");
    assertThat(backlog.poll()).isEmpty();
  }

  private static byte[] racedInDocument(String... elements) {
    List<String> base64 =
        List.of(elements).stream()
            .map(e -> Base64.getEncoder().encodeToString(e.getBytes(StandardCharsets.UTF_8)))
            .toList();
    return TestMappers.plainlyPinned()
        .valueToTree(base64)
        .toString()
        .getBytes(StandardCharsets.UTF_8);
  }
}
