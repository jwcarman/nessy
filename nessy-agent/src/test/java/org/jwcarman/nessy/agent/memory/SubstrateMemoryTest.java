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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson2.Jackson2CodecFactory;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.nessy.agent.support.MarkerBytesCodec;
import org.jwcarman.nessy.agent.support.RaceOnceOnBatchSubstrate;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.Remembrance;
import org.jwcarman.nessy.spi.memory.MessageCodec;
import org.jwcarman.nessy.spi.memory.RememberedMarker;
import org.jwcarman.nessy.spi.memory.SubstrateMemory;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

class SubstrateMemoryTest {

  private static final MessageCodec CODEC = new MessageCodec(new ObjectMapper());
  private static final String MEMORY_KIND = "memory";
  private static final String KEYS_KIND = "memory-keys";

  @Test
  void aFreshMemoryRecallsAnEmptyContext() {
    var memory =
        new SubstrateMemory(new InMemorySubstrate(), "agent-a", TestMappers.plainlyPinned());
    assertThat(memory.recall().messages()).isEmpty();
  }

  @Test
  void rememberedMessagesRecallInOrderAcrossTwoInstancesSharingOneSubstrate() {
    Substrate store = new InMemorySubstrate();
    var writer = new SubstrateMemory(store, "agent-a", TestMappers.plainlyPinned());
    var reader = new SubstrateMemory(store, "agent-a", TestMappers.plainlyPinned());

    writer.remember(new Remembrance.UserMessage("turn-1", Message.user("first")));
    writer.remember(new Remembrance.UserMessage("turn-2", Message.user("second")));

    assertThat(reader.recall().messages())
        .containsExactly(Message.user("first"), Message.user("second"));
  }

  @Test
  void rememberRetriesAfterLosingASeqRaceOnTheJournalAppend() {
    // A DIFFERENT remembrance's entry lands at the seq this remember() computed, between its raw
    // head read and its own batch — a genuine race (fix round 1 Q5): the batch conflicts, the
    // marker for THIS key never got created, so the loop re-reads the raw head and retries.
    Substrate delegate = new InMemorySubstrate();
    byte[] competitorPayload =
        CODEC.toJson(Message.user("stole the slot")).getBytes(StandardCharsets.UTF_8);
    Substrate racing =
        new RaceOnceOnJournalAppendViaBatch(delegate, MEMORY_KIND, "agent-a", competitorPayload);
    var memory = new SubstrateMemory(racing, "agent-a", TestMappers.plainlyPinned());

    memory.remember(new Remembrance.UserMessage("turn-1", Message.user("mine")));

    assertThat(delegate.entries(MEMORY_KIND, "agent-a", 1)).hasSize(2);
  }

  @Test
  void aMarkerConflictConverges() {
    // A competitor's marker for the EXACT SAME remembrance key already exists by the time this
    // remember()'s own batch tries to create it — the create-only marker write conflicts, and
    // markers.exists(...) now being true is how remember() tells "raced to remember the same
    // fact" apart from "raced against a different one" (fix round 1 Q5).
    Substrate delegate = new InMemorySubstrate();
    CodecFactory codecs = delegate.codecs();
    byte[] competitorMarkerPayload =
        codecs.create(RememberedMarker.class).encode(new RememberedMarker("turn-1"));
    Substrate racing =
        new RaceOnceOnBatchSubstrate(
            delegate, KEYS_KIND, "agent-a/turn-1", competitorMarkerPayload);
    var memory = new SubstrateMemory(racing, "agent-a", TestMappers.plainlyPinned());

    memory.remember(new Remembrance.UserMessage("turn-1", Message.user("mine")));

    // the marker conflict converged: remember() returned without appending its own entry at all
    assertThat(delegate.entries(MEMORY_KIND, "agent-a", 1)).isEmpty();
  }

  @Test
  void rememberingTheSameKeyTwiceConvergesToOneFact() {
    Substrate store = new InMemorySubstrate();
    var memory = new SubstrateMemory(store, "agent-a", TestMappers.plainlyPinned());
    var remembrance = new Remembrance.UserMessage("turn-1", Message.user("only once"));

    memory.remember(remembrance);
    memory.remember(remembrance);

    assertThat(memory.recall().messages()).containsExactly(Message.user("only once"));
  }

  @Test
  void aCrashSimulatedRedeliveryThatRemembersAgainConvergesToOneFact() {
    // The three laws (remembrance spec §1): remember runs before the caller's own commit: a
    // crash between the two leaves the caller's work pending, and the redrive re-remembers the
    // SAME key — this proves that re-remembering it (with no commit ever having happened
    // in-between, from memory's point of view) still converges to one fact.
    Substrate store = new InMemorySubstrate();
    var memory = new SubstrateMemory(store, "agent-a", TestMappers.plainlyPinned());
    var call = new ToolCall("call-1", "lookup", JsonNodeFactory.instance.objectNode());
    var remembrance = new Remembrance.ToolExchange("exec-1", call, ToolResult.ok("42"));

    memory.remember(remembrance); // the original attempt
    memory.remember(remembrance); // the crash-redrive's re-remember

    // a lone ToolExchange has no matching AssistantMessage to pair with, so recall() holds it as
    // an orphan rather than surfacing it (RemembranceFold's own contract) — the journal itself,
    // not recall(), is where convergence to one fact is visible here.
    assertThat(store.entries(MEMORY_KIND, "agent-a", 1)).hasSize(1);
  }

  @Test
  void aTranscriptWrittenBeforeTheRemembranceReformStillReads() {
    // Wire compatibility (spec §6): a pre-reform SubstrateMemory wrote bare Message JSON, with no
    // "type" discriminator — seeded here raw, bypassing remember() entirely, exactly as an
    // existing production transcript would already sit in a substrate this code now reads from.
    Substrate store = new InMemorySubstrate();
    byte[] legacyUser = CODEC.toJson(Message.user("legacy hello")).getBytes(StandardCharsets.UTF_8);
    byte[] legacyAssistant =
        CODEC
            .toJson(Message.assistant(List.of(new TextBlock("legacy hi"))))
            .getBytes(StandardCharsets.UTF_8);
    store.append(MEMORY_KIND, "agent-a", 1, legacyUser);
    store.append(MEMORY_KIND, "agent-a", 2, legacyAssistant);

    var memory = new SubstrateMemory(store, "agent-a", TestMappers.plainlyPinned());

    assertThat(memory.recall().messages())
        .containsExactly(
            Message.user("legacy hello"), Message.assistant(List.of(new TextBlock("legacy hi"))));
  }

  @Nested
  class ACustomCodec {

    @Test
    void isHonoredByBothWritesAndReads() {
      Substrate substrate = new InMemorySubstrate();
      Codec<Remembrance> codec =
          new Jackson2CodecFactory(TestMappers.plainlyPinned())
              .create(Remembrance.class)
              .andThen(new MarkerBytesCodec());
      var memory = new SubstrateMemory(substrate, "agent-a", codec);

      memory.remember(new Remembrance.UserMessage("turn-1", Message.user("mine")));

      byte[] rawPayload = substrate.entries(MEMORY_KIND, "agent-a", 1).getFirst().payload();
      assertThat(MarkerBytesCodec.isMarked(rawPayload)).isTrue();
      assertThat(memory.recall().messages()).containsExactly(Message.user("mine"));
    }
  }

  /**
   * Test-local: injects one competing raw journal append, via {@link #batch}, the instant before
   * delegating the real batch — the one shape {@code SubstrateMemory#remember}'s own retry loop
   * exists to survive. Deliberately not a shared support class (fix round 1 Q7 retired the
   * general-purpose {@code RaceOnceOnAppendSubstrate}): this scenario is specific to a journal
   * append riding inside a {@code batch()} call, which nothing else in this module needs to race.
   */
  private static final class RaceOnceOnJournalAppendViaBatch implements Substrate {

    private final Substrate delegate;
    private final String kind;
    private final String key;
    private final byte[] competitorPayload;
    private boolean raced;

    RaceOnceOnJournalAppendViaBatch(
        Substrate delegate, String kind, String key, byte[] competitorPayload) {
      this.delegate = delegate;
      this.kind = kind;
      this.key = key;
      this.competitorPayload = competitorPayload;
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
      delegate.append(kind, key, expectedSeq, payload);
    }

    @Override
    public List<Entry> entries(String kind, String key, long fromSeq) {
      return delegate.entries(kind, key, fromSeq);
    }

    @Override
    public long head(String kind, String key) {
      return delegate.head(kind, key);
    }

    @Override
    public void batch(List<Op> ops) {
      if (!raced) {
        raced = true;
        long nextSeq = delegate.entries(kind, key, 1).size() + 1L;
        delegate.append(kind, key, nextSeq, competitorPayload); // someone else appended first
      }
      delegate.batch(ops);
    }

    @Override
    public CodecFactory codecs() {
      return delegate.codecs();
    }
  }
}
