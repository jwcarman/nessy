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
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.inference.Usage;
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
  private final Codec<AgentEvent> entries =
      new JacksonCodecFactory(JsonMapper.builder().build()).create(AgentEvent.class);

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

  @Test
  void aStoredRequestForActionsReadsBackWithItsValueTypes() {
    String stored =
        """
                {"type":"actions-requested","seq":2,"turn":1,\
                "request":"c7f1e2a9","actions":[\
                {"type":"tool-call","id":"729606640","name":"lake_depth"}]}""";

    AgentEvent.ActionsRequested entry =
        (AgentEvent.ActionsRequested) entries.decode(stored.getBytes(StandardCharsets.UTF_8));

    assertThat(entry.request())
        .as("a reference is stored as the bare string it wraps, like every other value type here")
        .isEqualTo(PayloadRef.of("c7f1e2a9"));
    assertThat(entry.actions())
        .singleElement()
        .asInstanceOf(InstanceOfAssertFactories.type(ActionRequest.ToolCall.class))
        .satisfies(
            call -> {
              assertThat(call.id()).isEqualTo(new CallId("729606640"));
              assertThat(call.name()).isEqualTo(new ToolName("lake_depth"));
            });
  }

  /** And writing one produces exactly those bytes back. */
  @Test
  void writingARequestForActionsProducesThoseSameBytes() {
    String written =
        new String(
            entries.encode(
                new AgentEvent.ActionsRequested(
                    new Seq(2),
                    new TurnId(1),
                    PayloadRef.of("c7f1e2a9"),
                    List.of(
                        new ActionRequest.ToolCall(
                            new CallId("729606640"), new ToolName("lake_depth"))),
                    Usage.unreported())),
            StandardCharsets.UTF_8);

    assertThat(written)
        .contains("\"request\":\"c7f1e2a9\"")
        .contains("\"id\":\"729606640\"")
        .contains("\"name\":\"lake_depth\"")
        .contains("\"type\":\"tool-call\"")
        .as("no value type may nest an object where a bare string belongs")
        .doesNotContain("\"value\"");
  }

  @Test
  void aResultEntryNamesItsCallAsAString() {
    String written =
        new String(
            entries.encode(
                new AgentEvent.ToolSucceeded(
                    new Seq(4), new TurnId(1), new CallId("729606640"), PayloadRef.of("a3d9f0b1"))),
            StandardCharsets.UTF_8);

    assertThat(written).contains("\"callId\":\"729606640\"").doesNotContain("\"value\"");
  }

  @Test
  void aGrantNamesItsCallAsAStringAndKeepsAnAbsentReferenceAbsent() {
    String written =
        new String(
            entries.encode(
                new AgentEvent.ToolApproved(
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
                new AgentEffect.CallTool(
                    new TurnId(1), new Seq(2), new CallId("c1"), new ToolName("lookup"))),
            StandardCharsets.UTF_8);

    assertThat(written)
        .contains("\"callId\":\"c1\"")
        .contains("\"toolName\":\"lookup\"")
        .doesNotContain("\"value\"");
    assertThat(effects.decode(written.getBytes(StandardCharsets.UTF_8)))
        .isEqualTo(
            new AgentEffect.CallTool(
                new TurnId(1), new Seq(2), new CallId("c1"), new ToolName("lookup")));
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
            entries.encode(
                new AgentEvent.InferenceAnswered(
                    new Seq(5), new TurnId(1), PayloadRef.of("a3d9f0b1"), Usage.unreported())),
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
                "result":"a3d9f0b1"}""";

    AgentEvent.ToolSucceeded entry =
        (AgentEvent.ToolSucceeded) entries.decode(stored.getBytes(StandardCharsets.UTF_8));

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
                new AgentEffect.Approve(
                    new TurnId(1), new Seq(2), new CallId("c1"), new ToolName("lookup"))),
            StandardCharsets.UTF_8);

    assertThat(written).contains("\"requestSeq\":2").doesNotContain("\"value\"");
  }
}
