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
package org.jwcarman.nessy.inference.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.ToolChoice;
import org.jwcarman.nessy.inference.ToolOffer;
import org.jwcarman.nessy.inference.Toolset;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Whether OpenAI's Responses API accepts what this adapter builds -- the chat live test's nine
 * cases through the new wire, plus what this wire adds: reasoning items carried across a tool call
 * and across two, strict mode with an optional component, and the strict fallback.
 *
 * <p><b>Skipped, not failed, without {@code OPENAI_API_KEY}</b>, and tagged {@code live} so a build
 * never spends money. Run it deliberately:
 *
 * <pre>{@code
 * OPENAI_API_KEY=sk-... ./mvnw -q -pl :nessy-inference-openai test -Dnessy.excludedGroups= -Dtest=OpenAiResponsesLiveTest
 * }</pre>
 */
@Tag("live")
class OpenAiResponsesLiveTest {

  private static final String MODEL =
      System.getenv().getOrDefault("NESSY_LIVE_MODEL", "gpt-4o-mini");

  /** A model that reasons, and calls tools only over this wire (spec §1). */
  private static final String REASONING_MODEL =
      System.getenv().getOrDefault("NESSY_LIVE_REASONING_MODEL", "gpt-6-sol");

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final ToolOffer LAKE_DEPTH =
      new ToolOffer(
          new ToolName("lake_depth"),
          "returns the maximum depth of a named lake, in metres",
          new JsonSchema(
              """
              {"type":"object","properties":{"name":{"type":"string",\
              "description":"the lake to look up"}},"required":["name"]}"""));

  private static final ToolOffer WEATHER =
      new ToolOffer(
          new ToolName("weather"),
          "returns today's weather for a named place",
          new JsonSchema(
              """
              {"type":"object","properties":{"place":{"type":"string"}},"required":["place"]}"""));

  private static final ToolOffer TO_FEET =
      new ToolOffer(
          new ToolName("to_feet"),
          "converts a length in metres to feet",
          new JsonSchema(
              """
              {"type":"object","properties":{"metres":{"type":"number"}},"required":["metres"]}"""));

  private static OpenAiResponsesInferenceProvider provider() {
    assumeTrue(System.getenv("OPENAI_API_KEY") != null, "OPENAI_API_KEY is not set");
    return OpenAiResponsesInferenceProvider.fromEnv();
  }

  private static Turn turn(String question, List<Exchange> exchanges) {
    return new Turn(
        new TurnId(1),
        new Input(new Seq(1), List.of(new Block.Text(question))),
        exchanges,
        null,
        0);
  }

  private static InferenceRequest asking(
      String question, List<ToolOffer> tools, ToolChoice choice, String model) {
    return asking(question, tools, choice, InferenceOptions.of(model));
  }

  private static InferenceRequest asking(
      String question, List<ToolOffer> tools, ToolChoice choice, InferenceOptions options) {
    return new InferenceRequest(
        new SystemPrompt("You are a terse assistant. Answer in one short sentence."),
        InferenceContext.of(List.of(turn(question, List.of()))),
        new Toolset(tools, choice),
        options);
  }

  /** The reasoning model asked to reason, so its encrypted reasoning items are really produced. */
  private static InferenceOptions reasoning(
      OpenAiReasoningEffort effort, OpenAiReasoningSummary summary) {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put(
        OpenAiProperties.REASONING_EFFORT.name(), OpenAiProperties.REASONING_EFFORT.format(effort));
    if (summary != null) {
      properties.put(
          OpenAiProperties.REASONING_SUMMARY.name(),
          OpenAiProperties.REASONING_SUMMARY.format(summary));
    }
    return new InferenceOptions(REASONING_MODEL, 4096, properties);
  }

  private static InferenceRequest asking(String question, List<ToolOffer> tools) {
    return asking(question, tools, ToolChoice.auto(), MODEL);
  }

  private static InferenceRequest askingFor(String question, JsonSchema shape) {
    return new InferenceRequest(
        new SystemPrompt("You are a terse assistant."),
        InferenceContext.of(List.of(turn(question, List.of()))),
        Toolset.none(),
        InferenceOptions.of(MODEL),
        Optional.of(shape));
  }

  private static String textOf(InferenceResult result) {
    assertThat(result).isInstanceOf(InferenceResult.Answer.class);
    return ((InferenceResult.Answer) result)
        .blocks().stream()
            .filter(Block.Text.class::isInstance)
            .map(Block.Text.class::cast)
            .map(Block.Text::text)
            .collect(Collectors.joining());
  }

  private static ToolName calledIn(InferenceResult result) {
    assertThat(result).isInstanceOf(InferenceResult.Actions.class);
    return ((InferenceResult.Actions) result)
        .blocks().stream()
            .filter(Block.ToolCall.class::isInstance)
            .map(Block.ToolCall.class::cast)
            .findFirst()
            .orElseThrow()
            .name();
  }

  /** Answers every call in {@code actions} the way the real tools would. */
  private static List<ToolOutcome> outcomesFor(InferenceResult.Actions actions) {
    return actions.blocks().stream()
        .filter(Block.ToolCall.class::isInstance)
        .map(Block.ToolCall.class::cast)
        .map(
            call ->
                (ToolOutcome)
                    new ToolOutcome.Succeeded(
                        call.id(),
                        List.of(
                            new Block.Text(
                                switch (call.name().value()) {
                                  case "lake_depth" -> "230 metres";
                                  case "to_feet" -> "754.6 feet";
                                  default -> "no such tool";
                                }))))
        .toList();
  }

  // ---- the chat live test's nine, through this wire --------------------------------------

  @Test
  void a_real_question_gets_a_real_answer() {
    try (OpenAiResponsesInferenceProvider provider = provider()) {
      assertThat(textOf(provider.infer(asking("What is the capital of France?", List.of()))))
          .containsIgnoringCase("Paris");
    }
  }

  @Test
  void the_answer_is_narrated_as_it_streams() {
    try (OpenAiResponsesInferenceProvider provider = provider()) {
      Narration narrated = new Narration();

      InferenceResult result =
          provider.infer(
              asking("List the seven days of the week, one per line.", List.of()), narrated);

      assertThat(narrated.text())
          .as("a real stream arrives in more than one piece")
          .hasSizeGreaterThan(1);
      assertThat(String.join("", narrated.text())).isEqualTo(textOf(result));
    }
  }

  @Test
  void a_tool_offer_is_accepted_strict_and_called_with_arguments_that_fit_its_schema() {
    try (OpenAiResponsesInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(asking("How deep is Loch Ness?", List.of(LAKE_DEPTH)));

      assertThat(calledIn(result)).isEqualTo(new ToolName("lake_depth"));
      assertThat(((InferenceResult.Actions) result).blocks())
          .filteredOn(Block.ToolCall.class::isInstance)
          .singleElement()
          .isInstanceOfSatisfying(
              Block.ToolCall.class, call -> assertThat(call.arguments()).contains("Loch Ness"));
    }
  }

  @Test
  void requiring_one_tool_by_name_overrides_what_the_model_would_have_picked() {
    try (OpenAiResponsesInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              asking(
                  "How deep is Loch Ness?",
                  List.of(LAKE_DEPTH, WEATHER),
                  new ToolChoice.Named(new ToolName("weather")),
                  MODEL));

      assertThat(calledIn(result)).isEqualTo(new ToolName("weather"));
    }
  }

  @Test
  void requiring_some_tool_leaves_no_room_for_an_answer() {
    try (OpenAiResponsesInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              asking("Say hello.", List.of(LAKE_DEPTH, WEATHER), new ToolChoice.Any(), MODEL));

      assertThat(result).isInstanceOf(InferenceResult.Actions.class);
    }
  }

  /** §5e: emulated as tool_choice none; the answer itself is the contract. */
  @Test
  void answering_now_produces_prose_with_the_tools_still_on_offer() {
    try (OpenAiResponsesInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              asking(
                  "How deep is Loch Ness?",
                  List.of(LAKE_DEPTH, WEATHER),
                  new ToolChoice.Answer(),
                  MODEL));

      assertThat(textOf(result)).isNotBlank();
      assertThat(result.usage().counted()).isTrue();
    }
  }

  @Test
  void forbidding_tools_means_no_call_is_made() {
    try (OpenAiResponsesInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              asking(
                  "How deep is Loch Ness?",
                  List.of(LAKE_DEPTH, WEATHER),
                  new ToolChoice.None(),
                  MODEL));

      assertThat(result.usage().counted()).isTrue();
      assertThat(result).isNotInstanceOf(InferenceResult.Actions.class);
    }
  }

  @Test
  void an_answer_can_be_asked_for_in_a_shape() {
    JsonSchema shape =
        new JsonSchema(
            """
            {"type":"object",
             "properties":{"city":{"type":"string"},"country":{"type":"string"}},
             "required":["city","country"]}""");

    try (OpenAiResponsesInferenceProvider provider = provider()) {
      JsonNode parsed =
          MAPPER.readTree(
              textOf(provider.infer(askingFor("What is the capital of France?", shape))));

      assertThat(parsed.get("city").asString()).containsIgnoringCase("Paris");
      assertThat(parsed.get("country").asString()).containsIgnoringCase("France");
    }
  }

  @Test
  void the_shape_is_honoured_even_when_the_question_fits_it_badly() {
    JsonSchema shape =
        new JsonSchema(
            """
            {"type":"object",
             "properties":{"answer":{"type":"string"},"confident":{"type":"boolean"}},
             "required":["answer","confident"]}""");

    try (OpenAiResponsesInferenceProvider provider = provider()) {
      JsonNode parsed =
          MAPPER.readTree(textOf(provider.infer(askingFor("Tell me a joke.", shape))));

      assertThat(parsed.has("answer")).isTrue();
      assertThat(parsed.has("confident")).isTrue();
    }
  }

  // ---- what this wire adds -----------------------------------------------------------------

  /** Spec §1's measurement, and the proof §7c waits for. */
  @Test
  void a_reasoning_model_calls_a_tool_and_answers_once_the_result_goes_back_with_its_reasoning() {
    try (OpenAiResponsesInferenceProvider provider = provider()) {
      String question = "How deep is Loch Ness? Use the lake_depth tool.";
      InferenceResult first =
          provider.infer(
              asking(
                  question,
                  List.of(LAKE_DEPTH),
                  ToolChoice.auto(),
                  reasoning(OpenAiReasoningEffort.MEDIUM, null)));

      assertThat(first).isInstanceOf(InferenceResult.Actions.class);
      InferenceResult.Actions actions = (InferenceResult.Actions) first;
      assertThat(actions.blocks())
          .as("the reasoning item that led to the call is kept: " + actions.blocks())
          .anyMatch(Block.Provider.class::isInstance);

      InferenceResult second =
          provider.infer(
              new InferenceRequest(
                  new SystemPrompt("You are a terse assistant. Answer in one short sentence."),
                  InferenceContext.of(
                      List.of(
                          turn(
                              question,
                              List.of(
                                  new Exchange(
                                      new Seq(2), actions.blocks(), outcomesFor(actions)))))),
                  Toolset.of(List.of(LAKE_DEPTH)),
                  reasoning(OpenAiReasoningEffort.MEDIUM, null)));

      assertThat(textOf(second)).contains("230");
    }
  }

  /**
   * §5j's open decision: two calls in sequence under the starting replay rule (only the last
   * exchange's reasoning goes back). If the API rejects the third request or the answer degrades,
   * widen the rule (Step 4) and record which rule held.
   */
  @Test
  void a_reasoning_model_finishes_a_turn_that_needs_two_calls_in_sequence() {
    try (OpenAiResponsesInferenceProvider provider = provider()) {
      String question =
          "How deep is Loch Ness in feet? Look the depth up in metres with lake_depth first,"
              + " then convert that number with to_feet.";
      List<Exchange> exchanges = new ArrayList<>();
      InferenceResult result = null;
      for (int step = 0; step < 4; step++) {
        result =
            provider.infer(
                new InferenceRequest(
                    new SystemPrompt("You are a terse assistant. Use the tools, one at a time."),
                    InferenceContext.of(List.of(turn(question, exchanges))),
                    Toolset.of(List.of(LAKE_DEPTH, TO_FEET)),
                    InferenceOptions.of(REASONING_MODEL)));
        if (!(result instanceof InferenceResult.Actions actions)) {
          break;
        }
        exchanges.add(
            new Exchange(new Seq(exchanges.size() + 2L), actions.blocks(), outcomesFor(actions)));
      }

      assertThat(exchanges)
          .as("the calls came in sequence, one step each")
          .hasSizeGreaterThanOrEqualTo(2);
      // 230 m is 754.6 ft; a model may round either way.
      assertThat(textOf(result)).containsAnyOf("754", "755");
    }
  }

  /**
   * §5d: under strict, the model writes the optional component -- null or a value -- rather than
   * leaving it out.
   */
  @Test
  void an_optional_component_is_written_under_strict_mode_and_binds() {
    ToolOffer lookup =
        new ToolOffer(
            new ToolName("lake_depth"),
            "returns the maximum depth of a named lake",
            new JsonSchema(
                """
                {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                 "properties":{"name":{"type":"string"},"unit":{"type":["string","null"]}},
                 "required":["name"]}"""));

    try (OpenAiResponsesInferenceProvider provider = provider()) {
      InferenceResult result = provider.infer(asking("How deep is Loch Ness?", List.of(lookup)));

      assertThat(calledIn(result)).isEqualTo(new ToolName("lake_depth"));
      String arguments =
          ((InferenceResult.Actions) result)
              .blocks().stream()
                  .filter(Block.ToolCall.class::isInstance)
                  .map(Block.ToolCall.class::cast)
                  .findFirst()
                  .orElseThrow()
                  .arguments();
      Map<String, Object> written = MAPPER.readValue(arguments, new TypeReference<>() {});
      assertThat(written).as("strict mode requires every property").containsKey("unit");
      LakeQuery bound = MAPPER.readValue(arguments, LakeQuery.class);
      assertThat(bound.name()).containsIgnoringCase("Ness");
    }
  }

  record LakeQuery(String name, Optional<String> unit) {}

  /** A sealed vocabulary whose branches carry a {@code const} discriminator, nested in a record. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Restart.class, name = "Restart"),
    @JsonSubTypes.Type(value = Shutdown.class, name = "Shutdown")
  })
  sealed interface HostAction permits Restart, Shutdown {}

  record Restart(String host) implements HostAction {}

  record Shutdown(Optional<String> reason) implements HostAction {}

  record Note(String text) {}

  record HostRequest(HostAction action, Optional<Note> note) {}

  /**
   * F1: a nested sealed field and an optional record component, both in one tool's input, are
   * accepted in strict mode -- no fallback warning -- and the arguments the model writes bind.
   */
  @Test
  void a_nested_sealed_type_and_an_optional_record_go_strict_and_are_called() {
    ToolOffer hostRequest =
        new ToolOffer(
            new ToolName("host_request"),
            "carries out an action on a host, with an optional note",
            new JsonSchema(
                """
                {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                 "properties":{
                   "action":{"oneOf":[
                     {"type":"object","properties":{"host":{"type":"string"},
                                                    "type":{"const":"Restart"}},
                      "required":["host","type"]},
                     {"type":"object","properties":{"reason":{"type":["string","null"]},
                                                    "type":{"const":"Shutdown"}},
                      "required":["type"]}]},
                   "note":{"oneOf":[{"type":"null"},
                                    {"type":"object","properties":{"text":{"type":"string"}},
                                     "required":["text"]}]}},
                 "required":["action"]}"""));
    Logger logger = (Logger) LoggerFactory.getLogger(OpenAiResponsesRequests.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    try (OpenAiResponsesInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              asking(
                  "Restart the host called web-1, and note that it was unresponsive.",
                  List.of(hostRequest),
                  new ToolChoice.Any(),
                  MODEL));

      assertThat(calledIn(result)).isEqualTo(new ToolName("host_request"));
      assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage))
          .as("no strict fallback was logged for this tool")
          .noneMatch(message -> message.contains("host_request"));
      String arguments =
          ((InferenceResult.Actions) result)
              .blocks().stream()
                  .filter(Block.ToolCall.class::isInstance)
                  .map(Block.ToolCall.class::cast)
                  .findFirst()
                  .orElseThrow()
                  .arguments();
      HostRequest bound = MAPPER.readValue(arguments, HostRequest.class);
      assertThat(bound.action()).isEqualTo(new Restart("web-1"));
    } finally {
      logger.detachAppender(appender);
    }
  }

  /**
   * Section 13d: the case the Responses record could not run -- a summary asked for, and narrated.
   */
  @Test
  void a_reasoning_summary_is_narrated_as_thinking() {
    InferenceRequest request =
        new InferenceRequest(
            new SystemPrompt("You are a careful assistant."),
            InferenceContext.of(List.of(turn("What is 17 times 23? Work it out.", List.of()))),
            Toolset.none(),
            reasoning(OpenAiReasoningEffort.MEDIUM, OpenAiReasoningSummary.DETAILED));

    try (OpenAiResponsesInferenceProvider provider = provider()) {
      Narration narrated = new Narration();
      InferenceResult result = provider.infer(request, narrated);

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
      assertThat(narrated.fragments())
          .as("the summary deltas arrive as thinking")
          .anyMatch(fragment -> "thinking".equals(fragment.kind()));
      assertThat(((InferenceResult.Answer) result).blocks())
          .as("the encrypted reasoning item is kept beside the answer: " + result)
          .anyMatch(Block.Provider.class::isInstance);
    }
  }
}
