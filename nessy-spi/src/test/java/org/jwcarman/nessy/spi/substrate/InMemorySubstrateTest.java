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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InMemorySubstrateTest {

  private static byte[] bytes(String text) {
    return text.getBytes(UTF_8);
  }

  private static String text(byte[] bytes) {
    return new String(bytes, UTF_8);
  }

  @Nested
  class Documents {

    @Test
    void writeAtVersionZeroCreatesTheDocumentAtVersionOne() {
      var store = new InMemorySubstrate();
      store.write("state", "agent-a", bytes("{\"phase\":\"idle\"}"), 0L);
      assertThat(store.read("state", "agent-a"))
          .contains(
              new Substrate.Document(
                  bytes("{\"phase\":\"idle\"}"),
                  1L,
                  store.read("state", "agent-a").orElseThrow().updatedAt()));
    }

    @Test
    void aMatchingCasWriteIncrementsTheVersionByOne() {
      var store = new InMemorySubstrate();
      store.write("state", "agent-a", bytes("v1"), 0L);
      store.write("state", "agent-a", bytes("v2"), 1L);
      assertThat(store.read("state", "agent-a").orElseThrow().version()).isEqualTo(2L);
      assertThat(text(store.read("state", "agent-a").orElseThrow().payload())).isEqualTo("v2");
    }

    @Test
    void aCreateWriteAgainstAnAlreadyPresentDocumentThrowsConflict() {
      var store = new InMemorySubstrate();
      store.write("state", "agent-a", bytes("v1"), 0L);
      byte[] v2 = bytes("v2");
      assertThatThrownBy(() -> store.write("state", "agent-a", v2, 0L))
          .isInstanceOf(ConflictException.class);
    }

    @Test
    void aStaleCasWriteThrowsConflict() {
      var store = new InMemorySubstrate();
      store.write("state", "agent-a", bytes("v1"), 0L);
      byte[] v2 = bytes("v2");
      assertThatThrownBy(() -> store.write("state", "agent-a", v2, 5L))
          .isInstanceOf(ConflictException.class);
    }

    @Test
    void aStaleDeleteThrowsConflict() {
      var store = new InMemorySubstrate();
      store.write("state", "agent-a", bytes("v1"), 0L);
      assertThatThrownBy(() -> store.delete("state", "agent-a", 5L))
          .isInstanceOf(ConflictException.class);
    }

    @Test
    void aMatchingDeleteRemovesTheDocument() {
      var store = new InMemorySubstrate();
      store.write("state", "agent-a", bytes("v1"), 0L);
      store.delete("state", "agent-a", 1L);
      assertThat(store.read("state", "agent-a")).isEmpty();
    }

    @Test
    void deletingAnAbsentDocumentAtVersionZeroIsANoOpSuccess() {
      var store = new InMemorySubstrate();
      store.delete("state", "never-written", 0L);
      assertThat(store.read("state", "never-written")).isEmpty();
    }

    @Test
    void deletingAPresentDocumentAtVersionZeroThrowsConflict() {
      var store = new InMemorySubstrate();
      store.write("state", "agent-a", bytes("v1"), 0L);
      assertThatThrownBy(() -> store.delete("state", "agent-a", 0L))
          .isInstanceOf(ConflictException.class);
    }

    @Test
    void readingAnAbsentDocumentReturnsEmpty() {
      var store = new InMemorySubstrate();
      assertThat(store.read("state", "unknown-agent")).isEmpty();
    }

    @Test
    void keysComeBackInAscendingLexicographicOrderUpToTheLimit() {
      var store = new InMemorySubstrate();
      store.write("state", "charlie", bytes("v"), 0L);
      store.write("state", "alpha", bytes("v"), 0L);
      store.write("state", "bravo", bytes("v"), 0L);
      assertThat(store.keys("state", 2)).containsExactly("alpha", "bravo");
    }

    @Test
    void keysOnlyReportsTheRequestedKind() {
      var store = new InMemorySubstrate();
      store.write("state", "agent-a", bytes("v"), 0L);
      store.write("memory", "agent-a", bytes("v"), 0L);
      assertThat(store.keys("state", 10)).containsExactly("agent-a");
    }

    @Test
    void aLimitBelowOneIsRejected() {
      var store = new InMemorySubstrate();
      assertThatThrownBy(() -> store.keys("state", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullKindOnReadThrowsNpeWithAMessage() {
      var store = new InMemorySubstrate();
      assertThatThrownBy(() -> store.read(null, "agent-a"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("kind");
    }

    @Test
    void nullKeyOnWriteThrowsNpeWithAMessage() {
      var store = new InMemorySubstrate();
      byte[] payload = bytes("v");
      assertThatThrownBy(() -> store.write("state", null, payload, 0L))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("key");
    }

    @Test
    void nullPayloadOnWriteThrowsNpeWithAMessage() {
      var store = new InMemorySubstrate();
      assertThatThrownBy(() -> store.write("state", "agent-a", null, 0L))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("payload");
    }

    @Test
    void updatedAtComesFromTheInjectedClock() {
      var fixed = Instant.parse("2026-08-21T12:00:00Z");
      var store = new InMemorySubstrate(Clock.fixed(fixed, ZoneOffset.UTC));
      store.write("state", "agent-a", bytes("v1"), 0L);
      assertThat(store.read("state", "agent-a").orElseThrow().updatedAt()).isEqualTo(fixed);
    }

    @Test
    void twoDocumentsWithEqualContentDistinctArraysAreEqual() {
      var updatedAt = Instant.parse("2026-08-21T12:00:00Z");
      var first = new Substrate.Document(bytes("same content"), 3L, updatedAt);
      var second = new Substrate.Document(bytes("same content"), 3L, updatedAt);
      assertThat(first.payload()).isNotSameAs(second.payload());
      assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    void constructingADocumentWithANullPayloadThrowsNpeWithAMessage() {
      var updatedAt = Instant.parse("2026-08-21T12:00:00Z");
      assertThatThrownBy(() -> new Substrate.Document(null, 3L, updatedAt))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("payload");
    }

    @Test
    void mutatingTheCallersArrayAfterWriteDoesNotChangeALaterRead() {
      var store = new InMemorySubstrate();
      byte[] payload = bytes("original");
      store.write("state", "agent-a", payload, 0L);
      payload[0] = (byte) 'X';
      assertThat(text(store.read("state", "agent-a").orElseThrow().payload()))
          .isEqualTo("original");
    }

    @Test
    void mutatingAReturnedDocumentPayloadArrayDoesNotChangeTheStore() {
      var store = new InMemorySubstrate();
      store.write("state", "agent-a", bytes("original"), 0L);
      byte[] returned = store.read("state", "agent-a").orElseThrow().payload();
      returned[0] = (byte) 'X';
      assertThat(text(store.read("state", "agent-a").orElseThrow().payload()))
          .isEqualTo("original");
    }
  }

  @Nested
  class Journal {

    @Test
    void appendAtSeqOneCreatesTheFirstEntry() {
      var store = new InMemorySubstrate();
      store.append("memory", "agent-a", 1L, bytes("first"));
      assertThat(store.entries("memory", "agent-a", 1L))
          .extracting(entry -> text(entry.payload()))
          .containsExactly("first");
    }

    @Test
    void appendingAtHeadPlusOneAppendsAfterTheExistingEntry() {
      var store = new InMemorySubstrate();
      store.append("memory", "agent-a", 1L, bytes("first"));
      store.append("memory", "agent-a", 2L, bytes("second"));
      assertThat(store.entries("memory", "agent-a", 1L))
          .extracting(entry -> text(entry.payload()))
          .containsExactly("first", "second");
    }

    @Test
    void appendingAtAnOccupiedSeqThrowsConflict() {
      var store = new InMemorySubstrate();
      store.append("memory", "agent-a", 1L, bytes("first"));
      byte[] replacement = bytes("replacement");
      assertThatThrownBy(() -> store.append("memory", "agent-a", 1L, replacement))
          .isInstanceOf(ConflictException.class);
    }

    @Test
    void appendingPastAGapIsNotItselfAConflict() {
      var store = new InMemorySubstrate();
      store.append("memory", "agent-a", 1L, bytes("first"));
      store.append("memory", "agent-a", 5L, bytes("farAhead"));
      assertThat(store.entries("memory", "agent-a", 1L))
          .extracting(Substrate.Entry::seq)
          .containsExactly(1L, 5L);
    }

    @Test
    void entriesFromSeqSlicesInclusively() {
      var store = new InMemorySubstrate();
      store.append("memory", "agent-a", 1L, bytes("one"));
      store.append("memory", "agent-a", 2L, bytes("two"));
      store.append("memory", "agent-a", 3L, bytes("three"));
      assertThat(store.entries("memory", "agent-a", 2L))
          .extracting(entry -> text(entry.payload()))
          .containsExactly("two", "three");
    }

    @Test
    void entriesForAnUnknownKeyIsEmpty() {
      var store = new InMemorySubstrate();
      assertThat(store.entries("memory", "unknown-agent", 1L)).isEmpty();
    }

    @Test
    void appendedAtComesFromTheInjectedClock() {
      var fixed = Instant.parse("2026-08-21T12:00:00Z");
      var store = new InMemorySubstrate(Clock.fixed(fixed, ZoneOffset.UTC));
      store.append("memory", "agent-a", 1L, bytes("first"));
      assertThat(store.entries("memory", "agent-a", 1L).getFirst().appendedAt()).isEqualTo(fixed);
    }

    @Test
    void nullPayloadOnAppendThrowsNpeWithAMessage() {
      var store = new InMemorySubstrate();
      assertThatThrownBy(() -> store.append("memory", "agent-a", 1L, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("payload");
    }

    @Test
    void mutatingTheCallersArrayAfterAppendDoesNotChangeALaterRead() {
      var store = new InMemorySubstrate();
      byte[] payload = bytes("original");
      store.append("memory", "agent-a", 1L, payload);
      payload[0] = (byte) 'X';
      assertThat(text(store.entries("memory", "agent-a", 1L).getFirst().payload()))
          .isEqualTo("original");
    }

    @Test
    void mutatingAReturnedEntryPayloadArrayDoesNotChangeTheStore() {
      var store = new InMemorySubstrate();
      store.append("memory", "agent-a", 1L, bytes("original"));
      byte[] returned = store.entries("memory", "agent-a", 1L).getFirst().payload();
      returned[0] = (byte) 'X';
      assertThat(text(store.entries("memory", "agent-a", 1L).getFirst().payload()))
          .isEqualTo("original");
    }

    @Test
    void twoEntriesWithEqualContentDistinctArraysAreEqualButDifferWhenSeqDiffers() {
      var appendedAt = Instant.parse("2026-08-21T12:00:00Z");
      var first = new Substrate.Entry(1L, bytes("same content"), appendedAt);
      var second = new Substrate.Entry(1L, bytes("same content"), appendedAt);
      var differentSeq = new Substrate.Entry(2L, bytes("same content"), appendedAt);

      assertThat(first.payload()).isNotSameAs(second.payload());
      assertThat(first).isEqualTo(second).hasSameHashCodeAs(second).isNotEqualTo(differentSeq);
    }
  }

  @Nested
  class Batch {

    @Test
    void aMixedShapeBatchAppliesEveryOpOnSuccess() {
      var store = new InMemorySubstrate();
      store.batch(
          List.of(
              new Substrate.Op.WriteDocument("state", "agent-a", bytes("v1"), 0L),
              new Substrate.Op.AppendEntry("memory", "agent-a", 1L, bytes("hello"))));
      assertThat(text(store.read("state", "agent-a").orElseThrow().payload())).isEqualTo("v1");
      assertThat(store.entries("memory", "agent-a", 1L))
          .extracting(entry -> text(entry.payload()))
          .containsExactly("hello");
    }

    @Test
    void aBatchWithOneStaleOpAppliesNothing() {
      var store = new InMemorySubstrate();
      store.write("state", "agent-a", bytes("original"), 0L);
      List<Substrate.Op> ops =
          List.of(
              new Substrate.Op.AppendEntry("memory", "agent-a", 1L, bytes("hello")),
              new Substrate.Op.WriteDocument("state", "agent-a", bytes("stale"), 99L));

      assertThatThrownBy(() -> store.batch(ops)).isInstanceOf(ConflictException.class);

      assertThat(text(store.read("state", "agent-a").orElseThrow().payload()))
          .isEqualTo("original");
      assertThat(store.entries("memory", "agent-a", 1L)).isEmpty();
    }

    @Test
    void aBatchDeleteRemovesTheDocument() {
      var store = new InMemorySubstrate();
      store.write("state", "agent-a", bytes("v1"), 0L);
      store.batch(List.of(new Substrate.Op.DeleteDocument("state", "agent-a", 1L)));
      assertThat(store.read("state", "agent-a")).isEmpty();
    }

    @Test
    void nullOpsThrowsNpeWithAMessage() {
      var store = new InMemorySubstrate();
      assertThatThrownBy(() -> store.batch(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("ops");
    }

    @Test
    void
        twoWriteDocumentOpsWithEqualContentDistinctArraysAreEqualButDifferWhenExpectedVersionDiffers() {
      var first = new Substrate.Op.WriteDocument("state", "agent-a", bytes("same content"), 0L);
      var second = new Substrate.Op.WriteDocument("state", "agent-a", bytes("same content"), 0L);
      var differentVersion =
          new Substrate.Op.WriteDocument("state", "agent-a", bytes("same content"), 1L);

      assertThat(first.payload()).isNotSameAs(second.payload());
      assertThat(first).isEqualTo(second).hasSameHashCodeAs(second).isNotEqualTo(differentVersion);
    }

    @Test
    void twoAppendEntryOpsWithEqualContentDistinctArraysAreEqualButDifferWhenSeqDiffers() {
      var first = new Substrate.Op.AppendEntry("memory", "agent-a", 1L, bytes("same content"));
      var second = new Substrate.Op.AppendEntry("memory", "agent-a", 1L, bytes("same content"));
      var differentSeq =
          new Substrate.Op.AppendEntry("memory", "agent-a", 2L, bytes("same content"));

      assertThat(first.payload()).isNotSameAs(second.payload());
      assertThat(first).isEqualTo(second).hasSameHashCodeAs(second).isNotEqualTo(differentSeq);
    }
  }

  @Nested
  class Concurrency {

    @Test
    void racingCasWritersProduceExactlyOneWinnerPerRound() throws Exception {
      var store = new InMemorySubstrate();
      store.write("state", "agent-a", bytes("v0"), 0L);
      int racers = 16;
      List<Callable<Boolean>> attempts = new ArrayList<>();
      for (int i = 0; i < racers; i++) {
        attempts.add(
            () -> {
              try {
                store.write("state", "agent-a", bytes("v1"), 1L);
                return true;
              } catch (ConflictException _) {
                return false;
              }
            });
      }
      List<Boolean> outcomes = new ArrayList<>();
      try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
        for (var future : pool.invokeAll(attempts)) {
          outcomes.add(future.get());
        }
      }
      assertThat(outcomes).isNotEmpty();
      assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(1L);
      assertThat(store.read("state", "agent-a").orElseThrow().version()).isEqualTo(2L);
    }
  }

  @Nested
  class ToStringHygieneOnTheArrayBearingRecords {

    private static final String MARKER = "super-secret-marker-xyz";

    @Test
    void allFourArrayBearingRecordsReportPayloadSizeNeverPayloadContent() {
      var now = Instant.parse("2026-08-21T12:00:00Z");
      var document = new Substrate.Document(bytes(MARKER), 1L, now);
      var entry = new Substrate.Entry(1L, bytes(MARKER), now);
      var writeDocument = new Substrate.Op.WriteDocument("state", "agent-a", bytes(MARKER), 0L);
      var appendEntry = new Substrate.Op.AppendEntry("memory", "agent-a", 1L, bytes(MARKER));

      assertThat(document.toString()).contains("payloadBytes=").doesNotContain(MARKER);
      assertThat(entry.toString()).contains("payloadBytes=").doesNotContain(MARKER);
      assertThat(writeDocument.toString()).contains("payloadBytes=").doesNotContain(MARKER);
      assertThat(appendEntry.toString()).contains("payloadBytes=").doesNotContain(MARKER);
    }
  }

  @Nested
  class TheCodecExtensionPoint {

    record Marked(String value) {}

    /**
     * Writes {@code value} prefixed {@code "MARKED:"} — a registered serializer, not a format knob.
     */
    private static final class MarkedSerializer extends JsonSerializer<Marked> {
      @Override
      public void serialize(Marked value, JsonGenerator gen, SerializerProvider serializers)
          throws IOException {
        gen.writeStartObject();
        gen.writeStringField("value", "MARKED:" + value.value());
        gen.writeEndObject();
      }
    }

    private static final class MarkedDeserializer extends JsonDeserializer<Marked> {
      @Override
      public Marked deserialize(JsonParser parser, DeserializationContext context)
          throws IOException {
        ObjectNode node = parser.getCodec().readTree(parser);
        String raw = node.get("value").asText();
        return new Marked(raw.substring("MARKED:".length()));
      }
    }

    /**
     * Typed-stores fix round 1, Q2: a mapper handed to {@code new InMemorySubstrate(mapper)} must
     * actually be the mapper {@link Substrate#codecs()} binds through — not silently discarded for
     * a bare default. A registered serializer/deserializer module (not a pin-overridden format knob
     * like naming strategy or inclusion) proves the caller's own mapper configuration is live: it
     * must be visible in the raw stored bytes, and the round trip must use it too. This test fails
     * against a {@code SubstrateSupport} that ignores its {@code ObjectMapper} constructor argument
     * — the stored bytes would read plain {@code {"value":"hello"}} instead.
     */
    @Test
    void aRegisteredModuleSurvivesPinningAndIsVisibleInTheStoredBytes() {
      SimpleModule module = new SimpleModule();
      module.addSerializer(Marked.class, new MarkedSerializer());
      module.addDeserializer(Marked.class, new MarkedDeserializer());
      ObjectMapper customMapper = new ObjectMapper().registerModule(module);
      InMemorySubstrate substrate = new InMemorySubstrate(customMapper);
      DocumentStore<Marked> marks = substrate.document("marks", Marked.class);

      marks.write("a", new Marked("hello"), 0L);

      byte[] rawPayload = substrate.read("marks", "a").orElseThrow().payload();
      assertThat(new String(rawPayload, UTF_8)).contains("MARKED:hello");
      assertThat(marks.read("a")).contains(new Versioned<>(new Marked("hello"), 1L));
    }
  }
}
