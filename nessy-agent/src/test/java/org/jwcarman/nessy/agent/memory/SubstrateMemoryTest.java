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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.codec.MessageCodec;
import org.jwcarman.nessy.agent.support.MarkerBytesCodec;
import org.jwcarman.nessy.agent.support.RaceOnceOnBatchSubstrate;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.Remembrance;
import org.jwcarman.nessy.spi.substrate.Codec;
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
  void rememberRetriesAfterLosingAConflictOnTheGuardingBatch() {
    Substrate delegate = new InMemorySubstrate();
    // A competitor bumps the "memory-keys" marker doc mid-flight — the same doc remember()'s own
    // batch CASes against — so remember()'s first attempt genuinely conflicts and must retry.
    byte[] competitorKeysPayload =
        delegate
            .codecs()
            .codec(RememberedKeys.class)
            .encode(new RememberedKeys(List.of("someone-else")));
    Substrate racing =
        new RaceOnceOnBatchSubstrate(delegate, KEYS_KIND, "agent-a", competitorKeysPayload);
    var memory = new SubstrateMemory(racing, "agent-a", TestMappers.plainlyPinned());

    memory.remember(new Remembrance.UserMessage("turn-1", Message.user("mine")));

    assertThat(memory.recall().messages()).containsExactly(Message.user("mine"));
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
          Codec.json(TestMappers.plainlyPinned(), Remembrance.class).then(new MarkerBytesCodec());
      var memory = new SubstrateMemory(substrate, "agent-a", codec);

      memory.remember(new Remembrance.UserMessage("turn-1", Message.user("mine")));

      byte[] rawPayload = substrate.entries(MEMORY_KIND, "agent-a", 1).getFirst().payload();
      assertThat(MarkerBytesCodec.isMarked(rawPayload)).isTrue();
      assertThat(memory.recall().messages()).containsExactly(Message.user("mine"));
    }
  }
}
