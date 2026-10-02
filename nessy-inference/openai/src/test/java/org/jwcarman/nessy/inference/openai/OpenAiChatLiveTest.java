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
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.inference.Failure;
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
 * The one thing the offline tests cannot tell you: whether OpenAI accepts what this adapter builds.
 *
 * <p>Every other test here asserts on params this code produced, which proves the projection is
 * what was intended and nothing at all about whether it is <em>right</em>. A schema shape the
 * vendor rejects, a message ordering it refuses, a field it has since renamed -- all of those pass
 * offline and fail on first contact.
 *
 * <p><b>Skipped, not failed, without {@code OPENAI_API_KEY}.</b> A test that costs money and needs
 * a network cannot be one CI runs by default, and one that fails when unconfigured teaches everyone
 * to ignore it. Run it deliberately:
 *
 * <pre>{@code
 * OPENAI_API_KEY=sk-... ./mvnw -pl nessy-inference/openai test -Dtest=OpenAiChatLiveTest
 * }</pre>
 */
@Tag("live")
class OpenAiChatLiveTest {

  /**
   * The cheapest model that still calls tools, because this runs on somebody's bill.
   *
   * <p>Overridable, so the same tests can be pointed at a local runtime: {@code
   * NESSY_LIVE_MODEL=google/gemma-4-e4b OPENAI_BASE_URL=http://localhost:1234/v1}.
   */
  private static final String MODEL =
      System.getenv().getOrDefault("NESSY_LIVE_MODEL", "gpt-4o-mini");

  private static InferenceRequest asking(String question, List<ToolOffer> tools) {
    return asking(question, tools, ToolChoice.auto());
  }

  private static InferenceRequest askingFor(String question, JsonSchema shape) {
    return new InferenceRequest(
        new SystemPrompt("You are a terse assistant."),
        InferenceContext.of(
            List.of(
                new Turn(
                    new TurnId(1),
                    new Input(new Seq(1), List.of(new Block.Text(question))),
                    List.of(),
                    null,
                    0))),
        Toolset.none(),
        InferenceOptions.of(MODEL),
        Optional.of(shape));
  }

  private static InferenceRequest asking(
      String question, List<ToolOffer> tools, ToolChoice choice) {
    return new InferenceRequest(
        new SystemPrompt("You are a terse assistant. Answer in one short sentence."),
        InferenceContext.of(
            List.of(
                new Turn(
                    new TurnId(1),
                    new Input(new Seq(1), List.of(new Block.Text(question))),
                    List.of(),
                    null,
                    0))),
        new Toolset(tools, choice),
        InferenceOptions.of(MODEL));
  }

  private static OpenAiChatInferenceProvider provider() {
    assumeTrue(System.getenv("OPENAI_API_KEY") != null, "OPENAI_API_KEY is not set");
    return OpenAiChatInferenceProvider.fromEnv();
  }

  @Test
  void a_real_question_gets_a_real_answer() {
    try (OpenAiChatInferenceProvider provider = provider()) {
      InferenceResult result = provider.infer(asking("What is the capital of France?", List.of()));

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
      assertThat(((InferenceResult.Answer) result).blocks())
          .singleElement()
          .isInstanceOfSatisfying(
              Block.Text.class, text -> assertThat(text.text()).containsIgnoringCase("Paris"));
    }
  }

  /**
   * The narrator hears the answer in pieces before the result carries it whole: several deltas, and
   * their concatenation is exactly the text the engine is handed.
   */
  @Test
  void the_answer_is_narrated_as_it_streams() {
    try (OpenAiChatInferenceProvider provider = provider()) {
      Narration narrated = new Narration();

      InferenceResult result =
          provider.infer(
              asking("List the seven days of the week, one per line.", List.of()), narrated);

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
      String answer = ((Block.Text) ((InferenceResult.Answer) result).blocks().getFirst()).text();
      List<String> deltas = narrated.text();
      assertThat(deltas).as("a real stream arrives in more than one piece").hasSizeGreaterThan(1);
      assertThat(String.join("", deltas)).isEqualTo(answer);
    }
  }

  /**
   * The schema this project generates, accepted by the vendor and answered against.
   *
   * <p>This is the assertion the offline tests most need backing: {@code JsonSchema} carries JSON
   * text that only OpenAI can say is well-formed enough to bind a call to.
   */
  @Test
  void a_tool_offer_is_accepted_and_called_with_arguments_that_fit_its_schema() {
    try (OpenAiChatInferenceProvider provider = provider()) {
      ToolOffer lookup =
          new ToolOffer(
              new ToolName("lake_depth"),
              "returns the maximum depth of a named lake, in metres",
              new JsonSchema(
                  """
                  {"type":"object","properties":{"name":{"type":"string",\
                  "description":"the lake to look up"}},"required":["name"]}"""));

      InferenceResult result = provider.infer(asking("How deep is Loch Ness?", List.of(lookup)));

      assertThat(result)
          .as("a model offered exactly the tool that answers the question should reach for it")
          .isInstanceOf(InferenceResult.Actions.class);
      assertThat(((InferenceResult.Actions) result).blocks())
          .filteredOn(Block.ToolCall.class::isInstance)
          .singleElement()
          .isInstanceOfSatisfying(
              Block.ToolCall.class,
              call -> {
                assertThat(call.name()).isEqualTo(new ToolName("lake_depth"));
                assertThat(call.arguments())
                    .as("written against the schema this project generated")
                    .contains("name")
                    .contains("Loch Ness");
              });
    }
  }

  // ---- whether the vendor honours a tool choice ----------------------------------------

  /** Two tools, so naming one is a choice the model did not make for itself. */
  private static List<ToolOffer> twoTools() {
    return List.of(
        new ToolOffer(
            new ToolName("lake_depth"),
            "returns the maximum depth of a named lake, in metres",
            new JsonSchema(
                """
                {"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")),
        new ToolOffer(
            new ToolName("weather"),
            "returns today's weather for a named place",
            new JsonSchema(
                """
                {"type":"object","properties":{"place":{"type":"string"}},"required":["place"]}""")));
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

  /**
   * Naming the tool the question does not call for.
   *
   * <p>Asked about a lake with a lake tool on offer, a model left to itself reaches for that one.
   * Requiring the weather tool instead is the only way to tell a vendor that honoured the choice
   * from one that ignored the field and happened to agree.
   */
  @Test
  void requiring_one_tool_by_name_overrides_what_the_model_would_have_picked() {
    try (OpenAiChatInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              asking(
                  "How deep is Loch Ness?",
                  twoTools(),
                  new ToolChoice.Named(new ToolName("weather"))));

      assertThat(calledIn(result))
          .as("the vendor was told which tool, and this is whether it listened")
          .isEqualTo(new ToolName("weather"));
    }
  }

  /** Requiring some tool, where an answer would otherwise have done. */
  @Test
  void requiring_some_tool_leaves_no_room_for_an_answer() {
    try (OpenAiChatInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(asking("Say hello.", twoTools(), new ToolChoice.Any()));

      assertThat(result)
          .as("nothing here needs a tool, so an Answer would mean the requirement was dropped")
          .isInstanceOf(InferenceResult.Actions.class);
    }
  }

  /**
   * Answering now, where the model would plainly have reached for a tool.
   *
   * <p><b>The one arm this adapter emulates.</b> {@code ToolChoice.Answer} is an intent rather than
   * a wire value, and what it must deliver is prose -- so unlike a ban, where only the absence of a
   * call is asserted, here the answer itself is the contract. A turn told to wrap up and handed
   * nothing back would be a bound that destroys the work it was meant to rescue.
   *
   * <p>The offers stay in the request. Dropping them would work too and would cost the cached
   * prefix, which is the whole reason this choice belongs to the adapter rather than the engine.
   */
  @Test
  void answering_now_produces_prose_with_the_tools_still_on_offer() {
    try (OpenAiChatInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(asking("How deep is Loch Ness?", twoTools(), new ToolChoice.Answer()));

      assertThat(result)
          .as("a turn told to answer must answer; empty content would be a bound that broke it")
          .isInstanceOf(InferenceResult.Answer.class);
      assertThat(result)
          .as("the lake tool was right there, so a call would mean the intent was dropped")
          .isNotInstanceOf(InferenceResult.Actions.class);
      assertThat(result.usage().counted())
          .as("the call reached the model, so this is behaviour and not a failed send")
          .isTrue();
    }
  }

  /**
   * Forbidding tools, where the model would plainly have reached for one.
   *
   * <p>What is asserted is that no call was made, not that an answer arrived in its place: the
   * first is the ban holding, the second is the model's own choice about whether to speak. The
   * vendors differ there, and measured 2026-09-20 this one answers, where Anthropic ends the turn
   * with no content at all.
   */
  @Test
  void forbidding_tools_means_no_call_is_made() {
    try (OpenAiChatInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(asking("How deep is Loch Ness?", twoTools(), new ToolChoice.None()));

      assertThat(result.usage().counted())
          .as("the call reached the model, so what came back is behaviour and not a failed send")
          .isTrue();
      assertThat(result)
          .as("the lake tool was right there, so a call would mean the ban was dropped")
          .isNotInstanceOf(InferenceResult.Actions.class);
    }
  }

  /**
   * A shape asked for, and a shape that comes back.
   *
   * <p>The adapter is free to satisfy this natively or by offering a hidden tool and unwrapping the
   * call; what is asserted is only the contract -- that the answer is JSON matching the schema.
   * Which mechanism ran is exactly what a caller must never have to know.
   */
  @Test
  void an_answer_can_be_asked_for_in_a_shape() {
    JsonSchema shape =
        new JsonSchema(
            """
            {"type":"object",
             "properties":{"city":{"type":"string"},"country":{"type":"string"}},
             "required":["city","country"],
             "additionalProperties":false}""");

    try (OpenAiChatInferenceProvider provider = provider()) {
      InferenceResult result = provider.infer(askingFor("What is the capital of France?", shape));

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
      String json = textOf((InferenceResult.Answer) result);

      JsonNode parsed = JsonMapper.builder().build().readTree(json);
      assertThat(parsed.get("city").asString()).containsIgnoringCase("Paris");
      assertThat(parsed.get("country").asString()).containsIgnoringCase("France");
    }
  }

  /** Asking for a shape the question fits badly still comes back as that shape. */
  @Test
  void the_shape_is_honoured_even_when_the_question_fits_it_badly() {
    JsonSchema shape =
        new JsonSchema(
            """
            {"type":"object",
             "properties":{"answer":{"type":"string"},"confident":{"type":"boolean"}},
             "required":["answer","confident"],
             "additionalProperties":false}""");

    try (OpenAiChatInferenceProvider provider = provider()) {
      InferenceResult result = provider.infer(askingFor("Tell me a joke.", shape));

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
      JsonNode parsed =
          JsonMapper.builder().build().readTree(textOf((InferenceResult.Answer) result));
      assertThat(parsed.has("answer")).isTrue();
      assertThat(parsed.has("confident")).isTrue();
    }
  }

  private static String textOf(InferenceResult.Answer answer) {
    return answer.blocks().stream()
        .filter(Block.Text.class::isInstance)
        .map(Block.Text.class::cast)
        .map(Block.Text::text)
        .collect(Collectors.joining());
  }

  /** A model that reasons, for the effort case; the Responses live test's default. */
  private static final String REASONING_MODEL =
      System.getenv().getOrDefault("NESSY_LIVE_REASONING_MODEL", "gpt-6-sol");

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final Map<String, String> STRICT = Map.of("openai.tools.strict", "true");

  private static InferenceRequest carrying(
      String question,
      List<ToolOffer> tools,
      ToolChoice choice,
      String model,
      Map<String, String> properties) {
    return new InferenceRequest(
        new SystemPrompt("You are a terse assistant. Answer in one short sentence."),
        InferenceContext.of(
            List.of(
                new Turn(
                    new TurnId(1),
                    new Input(new Seq(1), List.of(new Block.Text(question))),
                    List.of(),
                    null,
                    0))),
        new Toolset(tools, choice),
        new InferenceOptions(model, 1024, properties));
  }

  record LakeQuery(String name, Optional<String> unit) {}

  /**
   * Section 10 on a real wire: under strict mode the optional component is written, and still
   * binds.
   */
  @Test
  void under_strict_tools_an_optional_component_is_written_and_binds() {
    ToolOffer lookup =
        new ToolOffer(
            new ToolName("lake_depth"),
            "returns the maximum depth of a named lake",
            new JsonSchema(
                """
                {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                 "properties":{"name":{"type":"string"},"unit":{"type":["string","null"]}},
                 "required":["name"]}"""));

    try (OpenAiChatInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              carrying(
                  "How deep is Loch Ness?", List.of(lookup), ToolChoice.auto(), MODEL, STRICT));

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
      assertThat(MAPPER.readValue(arguments, LakeQuery.class).name()).containsIgnoringCase("Ness");
    }
  }

  /**
   * A record whose field is a sealed vocabulary, as the generator writes it (a bare {@code const}
   * discriminator): strict accepts it, no fallback is logged, and the call goes through.
   */
  @Test
  void under_strict_tools_a_record_with_a_sealed_field_stays_strict_and_is_called() {
    ToolOffer command =
        new ToolOffer(
            new ToolName("server_command"),
            "restarts a host or shuts down, as asked",
            new JsonSchema(
                """
                {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                 "properties":{"command":{"oneOf":[
                   {"type":"object","properties":{"host":{"type":"string"},
                                                  "type":{"const":"Restart"}},
                    "required":["host","type"]},
                   {"type":"object","properties":{"reason":{"type":["string","null"]},
                                                  "type":{"const":"Shutdown"}},
                    "required":["type"]}]}},
                 "required":["command"]}"""));
    Logger logger = (Logger) LoggerFactory.getLogger(OpenAiChatRequests.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    try (OpenAiChatInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              carrying(
                  "Restart the host called web-1.",
                  List.of(command),
                  new ToolChoice.Any(),
                  MODEL,
                  STRICT));

      assertThat(calledIn(result)).isEqualTo(new ToolName("server_command"));
      assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage))
          .as("no strict fallback was logged for this tool")
          .noneMatch(message -> message.contains("server_command"));
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void a_reasoning_effort_reaches_a_reasoning_model() {
    try (OpenAiChatInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              carrying(
                  "What is 17 times 23?",
                  List.of(),
                  ToolChoice.auto(),
                  REASONING_MODEL,
                  Map.of("openai.reasoning.effort", "low")));

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
    }
  }

  /** A name the adapter does not support is not sent, so the vendor has nothing to refuse. */
  @Test
  void an_unsupported_property_is_ignored_and_the_request_still_goes_through() {
    try (OpenAiChatInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              carrying(
                  "Say hello.",
                  List.of(),
                  ToolChoice.auto(),
                  MODEL,
                  Map.of("openai.temperatur", "0.2")));

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
    }
  }

  // ---- a reply cut off at the output limit ----------------------------------------------

  private static final String LISTING =
      "Write the numbers 1 to 2000, one per line, each followed by a colon and its square,"
          + " for example '12: 144'. Output only those lines.";

  private static final String NOTE_REQUEST =
      "Call save_note once, now. Its text must be the numbers 1 to 2000, one per line, each"
          + " followed by a colon and its square.";

  private static final ToolOffer SAVE_NOTE =
      new ToolOffer(
          new ToolName("save_note"),
          "saves a note",
          new JsonSchema(
              """
              {"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""));

  private static InferenceRequest limited(String question, Toolset toolset) {
    return new InferenceRequest(
        new SystemPrompt("You are a terse assistant."),
        InferenceContext.of(
            List.of(
                new Turn(
                    new TurnId(1),
                    new Input(new Seq(1), List.of(new Block.Text(question))),
                    List.of(),
                    null,
                    0))),
        toolset,
        new InferenceOptions(MODEL, 1500));
  }

  /**
   * A reply the vendor cut off at the output limit comes back as {@code Truncated}, holding what
   * was written, and is neither an answer nor a fault.
   *
   * <p>The full listing needs far more than the 1500 tokens allowed, and 1500 leaves room for a
   * model that reasons a little before it writes.
   */
  @Test
  void an_answer_cut_off_at_the_output_limit_is_truncated_and_keeps_what_was_written() {
    try (OpenAiChatInferenceProvider provider = provider()) {
      InferenceResult result = provider.infer(limited(LISTING, Toolset.none()));

      assertThat(result)
          .as("the reply, whole: %s", result)
          .isInstanceOf(InferenceResult.Truncated.class);
      InferenceResult.Truncated truncated = (InferenceResult.Truncated) result;
      assertThat(truncated.blocks()).isNotEmpty();
      assertThat(truncated.blocks())
          .filteredOn(Block.Text.class::isInstance)
          .isNotEmpty()
          .anySatisfy(
              block -> {
                String written = ((Block.Text) block).text();
                assertThat(written).isNotBlank();
                assertThat(written.strip()).startsWith("1: 1");
              });
      assertThat(truncated.usage().outputTokens().orZero())
          .as("the reply was written, and the vendor counted it")
          .isPositive();
    }
  }

  /**
   * A tool call cut off at the output limit is a permanent fault and is never a call to run.
   *
   * <p>One tool, {@code save_note}, is offered and the model is required to call it with {@link
   * ToolChoice.Named}, the same way {@code
   * requiring_one_tool_by_name_overrides_what_the_model_would_have_picked} does. The text it is
   * asked to carry cannot fit in 1500 tokens, so the call is cut off mid-argument. What matters is
   * the first assertion: a call whose arguments are incomplete, or empty, must never be handed over
   * to run. The wire's own wording for the cut-off varies by model, so only {@code tool call} is
   * asserted of the reason.
   *
   * <p>Measured 2026-10-02 by a raw-HTTP probe: the chat wire returned {@code finish_reason:
   * length} with the call's arguments a JSON fragment that does not parse.
   */
  @Test
  void a_tool_call_cut_off_at_the_output_limit_is_a_fault_and_is_never_a_call_to_run() {
    try (OpenAiChatInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              limited(
                  NOTE_REQUEST,
                  new Toolset(
                      List.of(SAVE_NOTE), new ToolChoice.Named(new ToolName("save_note")))));

      assertThat(result)
          .as(
              "a call cut off at the output limit must never be handed over to run; result: %s",
              result)
          .isNotInstanceOf(InferenceResult.Actions.class);
      assertThat(result)
          .as("the reply, whole: %s", result)
          .isInstanceOf(InferenceResult.Fault.class);
      assertThat(((InferenceResult.Fault) result).failure())
          .isInstanceOfSatisfying(
              Failure.Permanent.class,
              failure -> assertThat(failure.reason()).contains("tool call"));
    }
  }
}
