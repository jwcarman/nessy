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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Chapter;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.effect.FailedAttempt;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.RequestManifest;
import org.jwcarman.nessy.inference.Failure;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
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
 * <p>So most of these pin the <em>exact bytes</em>: a round trip passes perfectly well against a
 * format nobody else can read. They check the written form for what it must contain, and read back
 * literal JSON written out by hand in the stored shape. A few are round trips as well, for the
 * events that carry the lines of text a tool call leaves behind, where what matters is that the
 * text survives the trip.
 */
class ValueTypeCodecTest {

  /** Any key: the tests here are not about which one a call gets. */
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));

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

  // ---- stored shapes, written out by hand ---------------------------------------------

  @Test
  void aStoredRequestForActionsReadsBackWithItsValueTypes() {
    String stored =
        """
                {"type":"actions-requested","seq":2,"turn":1,\
                "request":"c7f1e2a9","actions":[\
                {"type":"tool-call","id":"729606640","name":"lake_depth",\
                "action":"how deep is Lake Tahoe",\
                "idempotencyKey":"01999999-0000-7000-8000-000000000001"}]}""";

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
              assertThat(call.action()).isEqualTo("how deep is Lake Tahoe");
              assertThat(call.idempotencyKey()).isEqualTo(KEY);
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
                            new CallId("729606640"),
                            new ToolName("lake_depth"),
                            "how deep is Lake Tahoe",
                            KEY)),
                    Usage.unreported(),
                    Optional.empty())),
            StandardCharsets.UTF_8);

    assertThat(written)
        .contains("\"request\":\"c7f1e2a9\"")
        .contains("\"id\":\"729606640\"")
        .contains("\"name\":\"lake_depth\"")
        .contains("\"type\":\"tool-call\"")
        .contains("\"action\":\"how deep is Lake Tahoe\"")
        .contains("\"idempotencyKey\":\"01999999-0000-7000-8000-000000000001\"")
        .as("no value type may nest an object where a bare string belongs")
        .doesNotContain("\"value\"");
  }

  @Test
  void an_actions_requested_event_reads_back_with_its_actions() {
    AgentEvent.ActionsRequested written =
        new AgentEvent.ActionsRequested(
            new Seq(2),
            new TurnId(1),
            PayloadRef.of("c7f1e2a9"),
            List.of(
                new ActionRequest.ToolCall(
                    new CallId("a"), new ToolName("lake_depth"), "how deep is Lake Tahoe", KEY),
                new ActionRequest.ToolCall(
                    new CallId("b"), new ToolName("lake_area"), "lake_area (no such tool)", KEY)),
            Usage.unreported(),
            Optional.empty());

    byte[] bytes = entries.encode(written);

    assertThat(new String(bytes, StandardCharsets.UTF_8))
        .contains("\"action\":\"how deep is Lake Tahoe\"")
        .contains("\"action\":\"lake_area (no such tool)\"");
    assertThat(entries.decode(bytes)).isEqualTo(written);
  }

  @Test
  void aResultEntryNamesItsCallAsAString() {
    String written =
        new String(
            entries.encode(
                new AgentEvent.ToolSucceeded(
                    new Seq(4),
                    new TurnId(1),
                    new CallId("729606640"),
                    PayloadRef.of("a3d9f0b1"),
                    "reindexed 91",
                    KEY)),
            StandardCharsets.UTF_8);

    assertThat(written).contains("\"callId\":\"729606640\"").doesNotContain("\"value\"");
  }

  @Test
  void a_stored_policy_stop_is_written_as_turn_stopped() {
    byte[] literal =
        "{\"type\":\"turn-stopped\",\"seq\":3,\"turn\":1,\"reason\":\"too many calls\"}"
            .getBytes(StandardCharsets.UTF_8);
    AgentEvent.TurnStopped expected =
        new AgentEvent.TurnStopped(new Seq(3), new TurnId(1), "too many calls");

    AgentEvent read = entries.decode(literal);
    String written = new String(entries.encode(expected), StandardCharsets.UTF_8);

    assertThat(read).isEqualTo(expected);
    assertThat(written)
        .contains("\"type\":\"turn-stopped\"")
        .contains("\"seq\":3")
        .contains("\"turn\":1")
        .contains("\"reason\":\"too many calls\"");
  }

  @Test
  void a_tool_succeeded_event_reads_back_with_its_rendered_line() {
    AgentEvent.ToolSucceeded written =
        new AgentEvent.ToolSucceeded(
            new Seq(4), new TurnId(1), new CallId("c1"), PayloadRef.of("a3d9f0b1"), "80 days", KEY);

    byte[] bytes = entries.encode(written);

    assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("\"rendered\":\"80 days\"");
    assertThat(entries.decode(bytes)).isEqualTo(written);
  }

  @Test
  void a_tool_succeeded_event_with_an_empty_rendered_line_reads_back_empty() {
    AgentEvent.ToolSucceeded written =
        new AgentEvent.ToolSucceeded(
            new Seq(4), new TurnId(1), new CallId("c1"), PayloadRef.of("a3d9f0b1"), "", KEY);

    byte[] bytes = entries.encode(written);

    assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("\"rendered\":\"\"");
    assertThat(entries.decode(bytes)).isEqualTo(written);
    assertThat(((AgentEvent.ToolSucceeded) entries.decode(bytes)).rendered()).isEmpty();
  }

  @Test
  void a_tool_succeeded_outcome_with_an_empty_rendered_line_reads_back_empty() {
    Codec<EffectOutcome> outcomes =
        new JacksonCodecFactory(JsonMapper.builder().build()).create(EffectOutcome.class);
    EffectOutcome.ToolSucceeded written =
        new EffectOutcome.ToolSucceeded(new CallId("c1"), PayloadRef.of("a3d9f0b1"), "");

    byte[] bytes = outcomes.encode(written);

    assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("\"rendered\":\"\"");
    assertThat(outcomes.decode(bytes)).isEqualTo(written);
    assertThat(((EffectOutcome.ToolSucceeded) outcomes.decode(bytes)).rendered()).isEmpty();
  }

  @Test
  void aGrantNamesItsCallAsAStringAndKeepsAnAbsentDecidedByAbsent() {
    String written =
        new String(
            entries.encode(
                new AgentEvent.ToolApproved(
                    new Seq(3), new TurnId(1), new CallId("c1"), Optional.of("jcarman"), KEY)),
            StandardCharsets.UTF_8);

    assertThat(written).contains("\"callId\":\"c1\"").contains("\"decidedBy\":\"jcarman\"");
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
                    new TurnId(1), new Seq(2), new CallId("c1"), new ToolName("lookup"), KEY)),
            StandardCharsets.UTF_8);

    assertThat(written)
        .contains("\"callId\":\"c1\"")
        .contains("\"toolName\":\"lookup\"")
        .doesNotContain("\"value\"");
    assertThat(effects.decode(written.getBytes(StandardCharsets.UTF_8)))
        .isEqualTo(
            new AgentEffect.CallTool(
                new TurnId(1), new Seq(2), new CallId("c1"), new ToolName("lookup"), KEY));
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
                    new Seq(5),
                    new TurnId(1),
                    PayloadRef.of("a3d9f0b1"),
                    false,
                    Usage.unreported(),
                    Optional.empty())),
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
                "result":"a3d9f0b1","rendered":"reindexed 91",\
                "idempotencyKey":"01999999-0000-7000-8000-000000000001"}""";

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
                    new TurnId(1), new Seq(2), new CallId("c1"), new ToolName("lookup"), KEY)),
            StandardCharsets.UTF_8);

    assertThat(written).contains("\"requestSeq\":2").doesNotContain("\"value\"");
  }

  // ---- the key every call event carries ---------------------------------------------------

  private AgentEvent readStored(String json) {
    return entries.decode(json.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void aTruncatedAnswerIsStoredAsTruncated() {
    String stored =
        """
        {"type":"inference-answered","seq":2,"turn":1,"answer":"a3d9f0b1","truncated":true}""";
    AgentEvent.InferenceAnswered written =
        new AgentEvent.InferenceAnswered(
            new Seq(2),
            new TurnId(1),
            PayloadRef.of("a3d9f0b1"),
            true,
            Usage.unreported(),
            Optional.empty());

    AgentEvent.InferenceAnswered read = (AgentEvent.InferenceAnswered) readStored(stored);

    assertThat(read.truncated()).isTrue();
    assertThat(read).isEqualTo(written);
    assertThat(new String(entries.encode(written), StandardCharsets.UTF_8))
        .contains("\"truncated\":true");
  }

  @Test
  void aGrantIsStoredWithTheKeyOfItsCall() {
    String stored =
        """
        {"type":"tool-approved","seq":3,"turn":1,"callId":"c1","decidedBy":"jcarman",\
        "idempotencyKey":"01999999-0000-7000-8000-000000000001"}""";
    AgentEvent.ToolApproved written =
        new AgentEvent.ToolApproved(
            new Seq(3), new TurnId(1), new CallId("c1"), Optional.of("jcarman"), KEY);

    assertThat(readStored(stored)).isEqualTo(written);
    assertThat(new String(entries.encode(written), StandardCharsets.UTF_8))
        .contains("\"idempotencyKey\":\"01999999-0000-7000-8000-000000000001\"");
  }

  @Test
  void aDenialIsStoredWithTheKeyOfItsCall() {
    String stored =
        """
        {"type":"tool-denied","seq":3,"turn":1,"callId":"c1","reason":"no","decidedBy":"u_dave",\
        "idempotencyKey":"01999999-0000-7000-8000-000000000001"}""";
    AgentEvent.ToolDenied written =
        new AgentEvent.ToolDenied(
            new Seq(3), new TurnId(1), new CallId("c1"), "no", Optional.of("u_dave"), KEY);

    assertThat(readStored(stored)).isEqualTo(written);
    assertThat(new String(entries.encode(written), StandardCharsets.UTF_8))
        .contains("\"decidedBy\":\"u_dave\"")
        .contains("\"idempotencyKey\":\"01999999-0000-7000-8000-000000000001\"");
  }

  @Test
  void aResultIsStoredWithTheKeyOfItsCall() {
    String stored =
        """
        {"type":"tool-succeeded","seq":4,"turn":1,"callId":"c1","result":"a3d9f0b1",\
        "rendered":"ok","idempotencyKey":"01999999-0000-7000-8000-000000000001"}""";
    AgentEvent.ToolSucceeded written =
        new AgentEvent.ToolSucceeded(
            new Seq(4), new TurnId(1), new CallId("c1"), PayloadRef.of("a3d9f0b1"), "ok", KEY);

    assertThat(readStored(stored)).isEqualTo(written);
    assertThat(new String(entries.encode(written), StandardCharsets.UTF_8))
        .contains("\"idempotencyKey\":\"01999999-0000-7000-8000-000000000001\"");
  }

  @Test
  void aFailureIsStoredWithTheKeyOfItsCall() {
    String stored =
        """
        {"type":"tool-failed","seq":4,"turn":1,"callId":"c1","kind":"PAST_DEADLINE",\
        "message":"boom","idempotencyKey":"01999999-0000-7000-8000-000000000001"}""";
    AgentEvent.ToolFailed written =
        new AgentEvent.ToolFailed(
            new Seq(4), new TurnId(1), new CallId("c1"), CallFailure.PAST_DEADLINE, "boom", KEY);

    assertThat(readStored(stored)).isEqualTo(written);
    assertThat(new String(entries.encode(written), StandardCharsets.UTF_8))
        .contains("\"idempotencyKey\":\"01999999-0000-7000-8000-000000000001\"")
        .contains("\"kind\":\"PAST_DEADLINE\"");
  }

  @Test
  void aTurnStartIsStoredWithWhatStartedItAndWhenItsInputArrived() {
    String stored =
        """
        {"type":"turn-started","seq":1,"turn":1,"input":"a3d9f0b1","label":"Invoice",\
        "arrivedAt":"2026-03-04T05:06:07Z","startedAt":"2026-03-04T05:06:09Z"}""";
    AgentEvent.TurnStarted written =
        new AgentEvent.TurnStarted(
            new Seq(1),
            new TurnId(1),
            PayloadRef.of("a3d9f0b1"),
            "Invoice",
            Instant.parse("2026-03-04T05:06:07Z"),
            Instant.parse("2026-03-04T05:06:09Z"));

    assertThat(readStored(stored)).isEqualTo(written);
    assertThat(new String(entries.encode(written), StandardCharsets.UTF_8))
        .contains("\"label\":\"Invoice\"")
        .contains("\"arrivedAt\":\"2026-03-04T05:06:07Z\"")
        .contains("\"startedAt\":\"2026-03-04T05:06:09Z\"");
  }

  // ---- the request manifest ------------------------------------------------------------

  private static final String AGENT_A = "01999999-0000-7000-8000-0000000000a1";

  private final Codec<RequestManifest> manifests =
      new JacksonCodecFactory(JsonMapper.builder().build()).create(RequestManifest.class);

  private static final String WHOLE_MANIFEST =
      """
      {"engineVersion":"0.5.0-SNAPSHOT",
       "instructions":"1111aaaa",
       "tools":"2222bbbb",
       "answerShape":"3333cccc",
       "options":"4444dddd",
       "summaries":[
         {"chapter":{"agentType":{"value":"support"},"agentId":{"value":"%1$s"},"from":1,"through":4},
          "text":"5555eeee"},
         {"chapter":{"agentType":{"value":"support"},"agentId":{"value":"%1$s"},"from":6,"through":9},
          "text":"6666ffff"}],
       "tail":{"from":11,"through":13},
       "memory":[{"kind":"facts","content":"7777aaaa"}],
       "state":[{"kind":"notebook","content":"8888bbbb"}],
       "ambient":[{"kind":"clock","content":"9999cccc"}]}"""
          .formatted(AGENT_A);

  private static final String MINIMAL_MANIFEST =
      """
      {"engineVersion":"0.5.0-SNAPSHOT",
       "instructions":"1111aaaa",
       "tools":"2222bbbb",
       "answerShape":null,
       "options":"4444dddd",
       "summaries":[],
       "tail":null,
       "memory":[],
       "state":[],
       "ambient":[]}""";

  private static RequestManifest wholeManifest() {
    AgentType type = new AgentType("support");
    AgentId agent = new AgentId(UUID.fromString(AGENT_A));
    return new RequestManifest(
        "0.5.0-SNAPSHOT",
        new PayloadRef("1111aaaa"),
        new PayloadRef("2222bbbb"),
        Optional.of(new PayloadRef("3333cccc")),
        new PayloadRef("4444dddd"),
        List.of(
            new RequestManifest.SummarySection(
                new Chapter(type, agent, new TurnId(1), new TurnId(4)), new PayloadRef("5555eeee")),
            new RequestManifest.SummarySection(
                new Chapter(type, agent, new TurnId(6), new TurnId(9)),
                new PayloadRef("6666ffff"))),
        Optional.of(new RequestManifest.TurnRange(new TurnId(11), new TurnId(13))),
        List.of(new RequestManifest.Section("facts", new PayloadRef("7777aaaa"))),
        List.of(new RequestManifest.Section("notebook", new PayloadRef("8888bbbb"))),
        List.of(new RequestManifest.Section("clock", new PayloadRef("9999cccc"))));
  }

  private static RequestManifest minimalManifest() {
    return new RequestManifest(
        "0.5.0-SNAPSHOT",
        new PayloadRef("1111aaaa"),
        new PayloadRef("2222bbbb"),
        Optional.empty(),
        new PayloadRef("4444dddd"),
        List.of(),
        Optional.empty(),
        List.of(),
        List.of(),
        List.of());
  }

  private RequestManifest readManifest(String json) {
    return manifests.decode(json.getBytes(StandardCharsets.UTF_8));
  }

  private JsonNode writtenTree(RequestManifest manifest) {
    return mapper.readTree(new String(manifests.encode(manifest), StandardCharsets.UTF_8));
  }

  @Test
  void aManifestIsStoredAsItsReferences() {
    assertThat(readManifest(WHOLE_MANIFEST)).isEqualTo(wholeManifest());
    assertThat(writtenTree(wholeManifest())).isEqualTo(mapper.readTree(WHOLE_MANIFEST));
  }

  @Test
  void aMinimalManifestIsStoredWithNothingWhereThereIsNothing() {
    assertThat(readManifest(MINIMAL_MANIFEST)).isEqualTo(minimalManifest());
    assertThat(writtenTree(minimalManifest())).isEqualTo(mapper.readTree(MINIMAL_MANIFEST));
  }

  @Test
  void aManifestWithTheOptionalKeysAbsentReadsThemAsEmpty() {
    String stored =
        """
        {"engineVersion":"0.5.0-SNAPSHOT","instructions":"1111aaaa","tools":"2222bbbb","options":"4444dddd"}""";

    assertThat(readManifest(stored)).isEqualTo(minimalManifest());
  }

  // ---- the manifest on a stored model-call event ---------------------------------------

  private static final String NO_USAGE =
      """
      {"model":null,"inputTokens":null,"outputTokens":null,"cacheReadTokens":null,"cacheWriteTokens":null,"reasoningTokens":null}""";

  private static String event(String type, String fields, String manifestKey, String manifest) {
    return "{\"type\":\"%s\",\"seq\":3,\"turn\":1,%s\"usage\":%s,\"%s\":%s}"
        .formatted(type, fields, NO_USAGE, manifestKey, manifest);
  }

  private void assertStoredBothWays(String stored, AgentEvent expected) {
    assertThat(readStored(stored)).isEqualTo(expected);
    assertThat(mapper.readTree(new String(entries.encode(expected), StandardCharsets.UTF_8)))
        .isEqualTo(mapper.readTree(stored));
  }

  @Test
  void aStoredAnswerCarriesWhatItsRequestWasMadeOf() {
    assertStoredBothWays(
        event(
            "inference-answered",
            "\"answer\":\"a3d9f0b1\",\"truncated\":false,",
            "request",
            WHOLE_MANIFEST),
        new AgentEvent.InferenceAnswered(
            new Seq(3),
            new TurnId(1),
            PayloadRef.of("a3d9f0b1"),
            false,
            Usage.unreported(),
            Optional.of(wholeManifest())));
  }

  @Test
  void aStoredRefusalCarriesWhatItsRequestWasMadeOf() {
    assertStoredBothWays(
        event("inference-refused", "\"category\":\"safety\",", "request", WHOLE_MANIFEST),
        new AgentEvent.InferenceRefused(
            new Seq(3), new TurnId(1), "safety", Usage.unreported(), Optional.of(wholeManifest())));
  }

  @Test
  void aStoredFailureCarriesWhatItsRequestWasMadeOf() {
    assertStoredBothWays(
        event(
            "inference-failed",
            "\"failure\":{\"type\":\"permanent\",\"reason\":\"no\"},",
            "request",
            WHOLE_MANIFEST),
        new AgentEvent.InferenceFailed(
            new Seq(3),
            new TurnId(1),
            new Failure.Permanent("no"),
            Usage.unreported(),
            Optional.of(wholeManifest())));
  }

  @Test
  void aStoredAttemptCarriesWhatItsRequestWasMadeOf() {
    assertStoredBothWays(
        event(
            "inference-attempted",
            "\"failure\":{\"type\":\"transient\",\"reason\":\"busy\"},",
            "request",
            WHOLE_MANIFEST),
        new AgentEvent.InferenceAttempted(
            new Seq(3),
            new TurnId(1),
            new Failure.Transient("busy"),
            Usage.unreported(),
            Optional.of(wholeManifest())));
  }

  /** The key is named {@code manifest} here because {@code request} is what the model wrote. */
  @Test
  void storedRequestedActionsCarryWhatTheirRequestWasMadeOfUnderTheNameManifest() {
    assertStoredBothWays(
        event(
            "actions-requested",
            "\"request\":\"c7f1e2a9\",\"actions\":[],",
            "manifest",
            WHOLE_MANIFEST),
        new AgentEvent.ActionsRequested(
            new Seq(3),
            new TurnId(1),
            PayloadRef.of("c7f1e2a9"),
            List.of(),
            Usage.unreported(),
            Optional.of(wholeManifest())));
  }

  /** An entry written with no request in hand, or by a build that kept none, reads as empty. */
  @Test
  void aStoredFailureWithoutTheKeyReadsAsHavingNoRequest() {
    String stored =
        """
        {"type":"inference-failed","seq":3,"turn":1,"failure":{"type":"unknown","reason":"no answer"}}""";

    assertThat(readStored(stored))
        .isEqualTo(
            new AgentEvent.InferenceFailed(
                new Seq(3),
                new TurnId(1),
                new Failure.Unknown("no answer"),
                Usage.unreported(),
                Optional.empty()));
  }

  @Test
  void anEmptyRequestIsWrittenAsNullAndReadBackEmpty() {
    AgentEvent.InferenceFailed written =
        new AgentEvent.InferenceFailed(
            new Seq(3),
            new TurnId(1),
            new Failure.Unknown("no answer"),
            Usage.unreported(),
            Optional.empty());

    byte[] bytes = entries.encode(written);

    assertThat(mapper.readTree(new String(bytes, StandardCharsets.UTF_8)).get("request").isNull())
        .isTrue();
    assertThat(entries.decode(bytes)).isEqualTo(written);
  }

  @Test
  void anInferenceFailureOutcomeStoredWithoutTheKeyReadsAsHavingNoRequest() {
    Codec<EffectOutcome> outcomes =
        new JacksonCodecFactory(JsonMapper.builder().build()).create(EffectOutcome.class);
    String stored =
        """
        {"type":"inference-failed","failure":{"type":"unknown","reason":"no answer"}}""";

    EffectOutcome read = outcomes.decode(stored.getBytes(StandardCharsets.UTF_8));

    assertThat(read)
        .isEqualTo(
            new EffectOutcome.InferenceFailed(
                new Failure.Unknown("no answer"), Usage.unreported(), Optional.empty()));
  }

  // ---- the attempts kept on an effect row -----------------------------------------------

  private final Codec<List<FailedAttempt>> attempts =
      new JacksonCodecFactory(JsonMapper.builder().build())
          .create(new TypeRef<List<FailedAttempt>>() {});

  @Test
  void aKeptAttemptCarriesWhatItsRequestWasMadeOf() {
    String stored =
        """
        [{"failure":{"type":"transient","reason":"busy"},"usage":%s,"request":%s}]"""
            .formatted(NO_USAGE, WHOLE_MANIFEST);
    List<FailedAttempt> expected =
        List.of(
            new FailedAttempt(
                new Failure.Transient("busy"), Usage.unreported(), Optional.of(wholeManifest())));

    assertThat(attempts.decode(stored.getBytes(StandardCharsets.UTF_8))).isEqualTo(expected);
    assertThat(mapper.readTree(new String(attempts.encode(expected), StandardCharsets.UTF_8)))
        .isEqualTo(mapper.readTree(stored));
  }

  @Test
  void aKeptAttemptWrittenWithoutTheKeyReadsAsHavingNoRequest() {
    String stored =
        """
        [{"failure":{"type":"transient","reason":"busy"},"usage":%s}]"""
            .formatted(NO_USAGE);

    assertThat(attempts.decode(stored.getBytes(StandardCharsets.UTF_8)))
        .containsExactly(
            new FailedAttempt(new Failure.Transient("busy"), Usage.unreported(), Optional.empty()));
  }
}
