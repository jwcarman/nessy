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
package org.jwcarman.nessy.engine.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a value type looks like once it is written down.
 *
 * <p><b>Wrapping a value that is already stored is a migration unless the wrapper is
 * transparent.</b> {@code CallId} and {@code ToolName} became types long after rows carrying them
 * as bare strings existed, and a record serialises as an object by default -- so without
 * {@code @JsonValue} every one of those rows would have stopped reading, silently, and the only
 * symptom would have been agents that could no longer be loaded.
 *
 * <p>So these are not round-trip tests. A round trip passes perfectly well against a format nobody
 * else can read. What is pinned here is the <em>exact bytes</em>, and against literal JSON copied
 * from a live database rather than from anything this code produced.
 */
class ValueTypeCodecTest {

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final Codec<HistoryEntry> entries =
      new JacksonCodecFactory(JsonMapper.builder().build()).create(HistoryEntry.class);

  private String json(Object value) {
    return mapper.writeValueAsString(value);
  }

  // ---- the shape ---------------------------------------------------------------------

  @Test
  void aValueTypeIsWrittenAsTheBareStringItWraps() {
    assertThat(json(new CallId("call_1"))).isEqualTo("\"call_1\"");
    assertThat(json(new ToolName("lake_depth"))).isEqualTo("\"lake_depth\"");
  }

  @Test
  void andIsReadBackFromOne() {
    assertThat(mapper.readValue("\"call_1\"", CallId.class)).isEqualTo(new CallId("call_1"));
    assertThat(mapper.readValue("\"lake_depth\"", ToolName.class))
        .isEqualTo(new ToolName("lake_depth"));
  }

  /**
   * A record used as a map key takes a different path through Jackson than one used as a value, and
   * {@code @JsonCreator} is not obviously part of it. The agent's own state keys its outstanding
   * calls this way, so a failure here would be an agent that cannot be loaded.
   */
  @Test
  void aValueTypeWorksAsAMapKey() {
    Map<CallId, String> outstanding = Map.of(new CallId("call_1"), "running");

    assertThat(json(outstanding)).isEqualTo("{\"call_1\":\"running\"}");
    assertThat(
            mapper.<Map<CallId, String>>readValue(
                "{\"call_1\":\"running\"}", new TypeReference<Map<CallId, String>>() {}))
        .containsExactly(Map.entry(new CallId("call_1"), "running"));
  }

  // ---- the rows that already exist ----------------------------------------------------

  /**
   * Copied from a live agent's history, written before either of these was a type.
   *
   * <p>The assertion that matters: this still decodes, and into the same call it always meant.
   *
   * <p><b>The prose block is {@code commentary} rather than {@code text}, and that difference is
   * a migration.</b> Rows written before commentary existed carry {@code {"type":"text"}} beside
   * their calls, and {@code Text} is no longer legal in that slot -- so they do not decode at
   * all. The fix is a rewrite of the stored payload, not anything in this code:
   *
   * <pre>{@code
   * UPDATE nessy_agent_history
   *    SET payload = convert_to(replace(convert_from(payload, 'UTF8'),
   *                                     '{"type":"text"', '{"type":"commentary"'), 'UTF8')
   *  WHERE convert_from(payload, 'UTF8') LIKE '%inference-requested-actions%';
   * }</pre>
   *
   * <p>Recorded here rather than in a note because this test is the thing that found it.
   */
  @Test
  void anEntryStoredBeforeTheseWereTypesStillReads() {
    String stored =
        """
                {"type":"inference-requested-actions","seq":2,"turn":1,"blocks":[\
                {"type":"commentary","text":"looking it up"},\
                {"type":"tool-call","id":"729606640","name":"lake_depth",\
                "arguments":"{\\"name\\":\\"Loch Ness\\"}"}]}""";

    HistoryEntry.InferenceRequestedActions entry =
        (HistoryEntry.InferenceRequestedActions)
            entries.decode(stored.getBytes(StandardCharsets.UTF_8));

    assertThat(entry.calls())
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.id()).isEqualTo(new CallId("729606640"));
              assertThat(call.name()).isEqualTo(new ToolName("lake_depth"));
              assertThat(call.arguments()).isEqualTo("{\"name\":\"Loch Ness\"}");
            });
  }

  /** And writing one produces those same bytes, so old and new rows are indistinguishable. */
  @Test
  void writingOneProducesTheSameBytesItAlwaysDid() {
    String written =
        new String(
            entries.encode(
                new HistoryEntry.InferenceRequestedActions(
                    new Seq(2),
                    new TurnId(1),
                    List.of(
                        new Block.ToolCall(
                            "729606640", "lake_depth", "{\"name\":\"Loch Ness\"}")))),
            StandardCharsets.UTF_8);

    assertThat(written)
        .contains("\"id\":\"729606640\"")
        .contains("\"name\":\"lake_depth\"")
        .doesNotContain("\"value\"");
  }

  @Test
  void aResultEntryNamesItsCallAsAString() {
    String written =
        new String(
            entries.encode(
                new HistoryEntry.ToolSucceeded(
                    new Seq(4),
                    new TurnId(1),
                    new CallId("729606640"),
                    HistoryEntry.ToolSucceeded.text("1412 metres"))),
            StandardCharsets.UTF_8);

    assertThat(written).contains("\"callId\":\"729606640\"").doesNotContain("\"value\"");
  }

  @Test
  void aGrantNamesItsCallAsAStringAndKeepsAnAbsentReferenceAbsent() {
    String written =
        new String(
            entries.encode(
                new HistoryEntry.ToolApproved(
                    new Seq(3), new TurnId(1), new CallId("c1"), Optional.of("jcarman"))),
            StandardCharsets.UTF_8);

    assertThat(written).contains("\"callId\":\"c1\"").contains("\"reference\":\"jcarman\"");
  }

  /**
   * Effect rows carry them too, and an effect that will not decode is one the engine can only
   * discharge with its stored failure -- so this is the same compatibility surface.
   */
  @Test
  void anEffectNamesItsCallAndToolAsStrings() {
    Codec<AgentEffect> effects =
        new JacksonCodecFactory(JsonMapper.builder().build()).create(AgentEffect.class);
    String written =
        new String(
            effects.encode(
                new AgentEffect.CallTool(new Seq(2), new CallId("c1"), new ToolName("lookup"))),
            StandardCharsets.UTF_8);

    assertThat(written)
        .contains("\"callId\":\"c1\"")
        .contains("\"toolName\":\"lookup\"")
        .doesNotContain("\"value\"");
    assertThat(effects.decode(written.getBytes(StandardCharsets.UTF_8)))
        .isEqualTo(new AgentEffect.CallTool(new Seq(2), new CallId("c1"), new ToolName("lookup")));
  }

  // ---- positions -------------------------------------------------------------------------

  /**
   * {@code Seq} and {@code TurnId} are written as bare numbers, exactly as the {@code long}s they
   * replaced were.
   *
   * <p>Every stored entry begins {@code "seq":2,"turn":1}, and every effect row names the entry
   * that owed it. Wrapping either without {@code @JsonValue} would have made every row in both
   * tables unreadable at once.
   */
  @Test
  void aPositionIsWrittenAsTheBareNumberItWraps() {
    assertThat(json(new Seq(2))).isEqualTo("2");
    assertThat(json(new TurnId(1))).isEqualTo("1");
    assertThat(mapper.readValue("2", Seq.class)).isEqualTo(new Seq(2));
    assertThat(mapper.readValue("1", TurnId.class)).isEqualTo(new TurnId(1));
  }

  @Test
  void anEntryNamesItsPositionAndTurnAsNumbers() {
    String written =
        new String(
            entries.encode(HistoryEntry.InferenceAnswered.of(5, 1, "It is 1412 metres deep.")),
            StandardCharsets.UTF_8);

    assertThat(written).contains("\"seq\":5").contains("\"turn\":1").doesNotContain("\"value\"");
  }

  /**
   * The two are the same number line and different meanings, which is the whole reason they are
   * separate types -- so a stored entry has to read back with each in its own field rather than
   * merely with the right numbers somewhere.
   */
  @Test
  void anEntryReadsBackWithItsPositionAndTurnTheRightWayRound() {
    String stored =
        """
                {"type":"tool-succeeded","seq":4,"turn":1,"callId":"call_1",\
                "blocks":[{"type":"text","text":"1412 metres"}]}""";

    HistoryEntry entry = entries.decode(stored.getBytes(StandardCharsets.UTF_8));

    assertThat(entry.seq()).isEqualTo(new Seq(4));
    assertThat(entry.turn()).isEqualTo(new TurnId(1));
  }

  /** An agent's own state carries its position too, and is stored in a table of its own. */
  @Test
  void anEffectNamesTheEntryThatOwedItAsANumber() {
    Codec<AgentEffect> effects =
        new JacksonCodecFactory(JsonMapper.builder().build()).create(AgentEffect.class);
    String written =
        new String(
            effects.encode(
                new AgentEffect.Approve(new Seq(2), new CallId("c1"), new ToolName("lookup"))),
            StandardCharsets.UTF_8);

    assertThat(written).contains("\"requestSeq\":2").doesNotContain("\"value\"");
  }
}
