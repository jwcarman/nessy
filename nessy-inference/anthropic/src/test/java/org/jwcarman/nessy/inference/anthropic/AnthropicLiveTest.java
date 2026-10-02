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
package org.jwcarman.nessy.inference.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.Tokens;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.ToolChoice;
import org.jwcarman.nessy.inference.ToolOffer;
import org.jwcarman.nessy.inference.Toolset;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one thing the offline tests cannot tell you: whether Anthropic accepts what this adapter
 * builds.
 *
 * <p>Every other test here asserts on params this code produced, which proves the projection is
 * what was intended and nothing at all about whether it is <em>right</em>. Three things in
 * particular fail offline-clean and break on first contact: a schema shape the vendor rejects, a
 * message ordering it refuses, and reasoning replayed with a signature it will not accept.
 *
 * <p><b>Skipped, not failed, without {@code ANTHROPIC_API_KEY}.</b> Run it deliberately:
 *
 * <pre>{@code
 * ANTHROPIC_API_KEY=sk-ant-... ./mvnw -pl nessy-inference/anthropic test -Dtest=AnthropicLiveTest
 * }</pre>
 */
@Tag("live")
class AnthropicLiveTest {

  /** The cheapest model that still calls tools and thinks, because this runs on somebody's bill. */
  private static final String MODEL = "claude-sonnet-4-5";

  private static final SystemPrompt SYSTEM =
      new SystemPrompt("You are a terse assistant. Answer in one short sentence.");

  private static AnthropicInferenceProvider provider() {
    return provider(config -> {});
  }

  /** A provider configured as a deployment would configure it: thinking, caching, or neither. */
  private static AnthropicInferenceProvider provider(Consumer<AnthropicProviderConfig> customizer) {
    assumeTrue(System.getenv("ANTHROPIC_API_KEY") != null, "ANTHROPIC_API_KEY is not set");
    return AnthropicInferenceProvider.of(
        config -> {
          config.fromEnv();
          customizer.accept(config);
        });
  }

  private static Turn open(long id, String question) {
    return new Turn(
        new TurnId(id),
        new Input(new Seq(id), List.of(new Block.Text(question))),
        List.of(),
        null,
        0);
  }

  private static InferenceRequest asking(List<Turn> turns, List<ToolOffer> tools) {
    return asking(turns, tools, ToolChoice.auto());
  }

  private static InferenceRequest asking(
      List<Turn> turns, List<ToolOffer> tools, ToolChoice choice) {
    return new InferenceRequest(
        SYSTEM,
        InferenceContext.of(turns),
        new Toolset(tools, choice),
        new InferenceOptions(MODEL, 2048));
  }

  private static InferenceRequest askingFor(List<Turn> turns, JsonSchema shape) {
    return new InferenceRequest(
        SYSTEM,
        InferenceContext.of(turns),
        Toolset.none(),
        new InferenceOptions(MODEL, 2048),
        Optional.of(shape));
  }

  @Test
  void a_real_question_gets_a_real_answer() {
    try (AnthropicInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(asking(List.of(open(1, "What is the capital of France?")), List.of()));

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
      assertThat(((InferenceResult.Answer) result).blocks())
          .filteredOn(Block.Text.class::isInstance)
          .isNotEmpty()
          .allSatisfy(block -> assertThat(((Block.Text) block).text()).isNotBlank())
          .anySatisfy(
              block -> assertThat(((Block.Text) block).text()).containsIgnoringCase("Paris"));
    }
  }

  /**
   * The narrator hears the answer in pieces before the result carries it whole: several deltas, and
   * their concatenation is exactly the text the engine is handed.
   */
  @Test
  void the_answer_is_narrated_as_it_streams() {
    try (AnthropicInferenceProvider provider = provider()) {
      Narration narrated = new Narration();

      InferenceResult result =
          provider.infer(
              asking(List.of(open(1, "List the seven days of the week, one per line.")), List.of()),
              narrated);

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
      String answer =
          ((InferenceResult.Answer) result)
              .blocks().stream()
                  .filter(Block.Text.class::isInstance)
                  .map(block -> ((Block.Text) block).text())
                  .collect(java.util.stream.Collectors.joining());
      List<String> deltas = narrated.text();
      assertThat(deltas).as("a real stream arrives in more than one piece").hasSizeGreaterThan(1);
      assertThat(String.join("", deltas)).isEqualTo(answer);
    }
  }

  /**
   * The schema this project generates, accepted by the vendor and answered against. This wire takes
   * properties and required as named fields, so {@code AnthropicSchemas} is doing real surgery and
   * only Anthropic can say whether the result binds a call.
   */
  @Test
  void a_tool_offer_is_accepted_and_called_with_arguments_that_fit_its_schema() {
    try (AnthropicInferenceProvider provider = provider()) {
      ToolOffer lookup =
          new ToolOffer(
              new ToolName("lake_depth"),
              "returns the maximum depth of a named lake, in metres",
              new JsonSchema(
                  """
                  {"type":"object","properties":{"name":{"type":"string",\
                  "description":"the lake to look up"}},"required":["name"]}"""));

      InferenceResult result =
          provider.infer(asking(List.of(open(1, "How deep is Loch Ness?")), List.of(lookup)));

      assertThat(result).isInstanceOf(InferenceResult.Actions.class);
      assertThat(((InferenceResult.Actions) result).blocks())
          .filteredOn(Block.ToolCall.class::isInstance)
          .singleElement()
          .isInstanceOfSatisfying(
              Block.ToolCall.class,
              call -> {
                assertThat(call.name()).isEqualTo(new ToolName("lake_depth"));
                assertThat(call.arguments()).contains("name").contains("Loch Ness");
              });
    }
  }

  /**
   * <b>The assertion this whole adapter most needs.</b> Reasoning is only accepted back with the
   * signature it was issued with, and that signature is opaque -- so a round trip is the only thing
   * that can prove this code carries it intact. If {@code Block.Provider} lost or reshaped one
   * byte, the second call below is a 400 and nothing offline would ever have said so.
   */
  @Test
  void reasoning_is_replayed_intact_on_the_next_turn() {
    try (AnthropicInferenceProvider provider =
        provider(
            config ->
                config.property(
                    AnthropicProperties.THINKING_TYPE, AnthropicThinkingType.ENABLED))) {
      InferenceResult first =
          provider.infer(
              asking(
                  List.of(open(1, "Think about it, then say how many continents there are.")),
                  List.of()));

      assertThat(first).isInstanceOf(InferenceResult.Answer.class);
      List<Block.AnswerContent> answered = ((InferenceResult.Answer) first).blocks();
      assertThat(answered)
          .as("thinking was asked for, so the reply should carry this vendor's own state")
          .anyMatch(Block.Provider.class::isInstance);

      // The whole turn, reasoning included, sent straight back as history.
      Turn done =
          new Turn(
              new TurnId(1),
              new Input(
                  new Seq(1),
                  List.of(
                      new Block.Text("Think about it, then say how many continents there are."))),
              List.of(),
              new TurnResult.Answered(answered),
              0);

      InferenceResult second =
          provider.infer(asking(List.of(done, open(3, "And how many oceans?")), List.of()));

      assertThat(second)
          .as("a signature this code altered would come back as a 400 rather than an answer")
          .isInstanceOf(InferenceResult.Answer.class);
    }
  }

  /**
   * Background changes Nessy's system prompt between calls, and on the models that bind thinking to
   * its prefix that used to be a 400 for accounts created since 2026-08-31. With the adapter asking
   * for mismatched blocks to be dropped, the vendor answers.
   *
   * <p>On an older account the check is not enforced without the setting, so this could not have
   * failed there before the fix. What it proves anywhere is that the setting and its beta header
   * are accepted on a model that enforces the check, with thinking replayed under a changed prompt,
   * and that the vendor's report of the drop is read off the stream: this is the one place the real
   * wire shape of that report passes through the SDK.
   */
  @Test
  void a_changed_system_prompt_under_replayed_thinking_is_still_answered() {
    String question =
        "Privately work out 17 x 23 + 41 x 19 and check it twice. Reply with the number only.";
    InferenceOptions thinking =
        new InferenceOptions(
            "claude-sonnet-5-5", 4096, Map.of("anthropic.thinking.type", "adaptive"));
    try (AnthropicInferenceProvider provider = provider()) {
      InferenceResult first =
          provider.infer(
              new InferenceRequest(
                  SYSTEM,
                  InferenceContext.of(List.of(open(1, question))),
                  Toolset.none(),
                  thinking));

      assertThat(first).isInstanceOf(InferenceResult.Answer.class);
      List<Block.AnswerContent> answered = ((InferenceResult.Answer) first).blocks();
      assumeTrue(
          answered.stream().anyMatch(Block.Provider.class::isInstance),
          "adaptive thinking chose not to think, so there is nothing to replay");

      Turn done =
          new Turn(
              new TurnId(1),
              new Input(new Seq(1), List.of(new Block.Text(question))),
              List.of(),
              new TurnResult.Answered(answered),
              0);
      SystemPrompt changed =
          new SystemPrompt(SYSTEM.value() + "\n<plan>\nStep 2 of 3: report the total.\n</plan>");

      InferenceResult[] second = new InferenceResult[1];
      List<ILoggingEvent> logged =
          LogCapture.during(
              AnthropicInferenceProvider.class,
              () ->
                  second[0] =
                      provider.infer(
                          new InferenceRequest(
                              changed,
                              InferenceContext.of(
                                  List.of(done, open(3, "And what is half of that?"))),
                              Toolset.none(),
                              thinking)));

      assertThat(second[0])
          .as("a prefix that changed under replayed thinking is dropped by the vendor, not refused")
          .isInstanceOf(InferenceResult.Answer.class);
      assertThat(logged)
          .as("the drop is reported on message_start, the only place the wire shape is read")
          .anyMatch(
              event ->
                  event.getLevel() == Level.DEBUG
                      && event
                          .getFormattedMessage()
                          .contains("thinking block(s) whose prefix had changed"));
    }
  }

  /** Prompt caching is money rather than correctness, so what matters is that it is accepted. */
  @Test
  void a_cached_request_is_accepted() {
    try (AnthropicInferenceProvider provider =
        provider(
            config ->
                config.property(AnthropicProperties.CACHE_TTL, AnthropicCacheTtl.FIVE_MINUTES))) {
      InferenceResult result =
          provider.infer(asking(List.of(open(1, "What is the capital of France?")), List.of()));

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
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
    try (AnthropicInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              asking(
                  List.of(open(1, "How deep is Loch Ness?")),
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
    try (AnthropicInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(asking(List.of(open(1, "Say hello.")), twoTools(), new ToolChoice.Any()));

      assertThat(result)
          .as("nothing here needs a tool, so an Answer would mean the requirement was dropped")
          .isInstanceOf(InferenceResult.Actions.class);
    }
  }

  /**
   * Forbidding tools, where the model would plainly have reached for one.
   *
   * <p>What is asserted is that no call was made, not that an answer arrived in its place. Measured
   * 2026-09-20: with tools in the request and a ban on using them, Claude ends the turn with an
   * empty content array -- stop_reason end_turn, eight output tokens, no blocks at all. That is the
   * model choosing to say nothing rather than the ban failing, and an adapter that reports it as a
   * fault is reporting what happened.
   *
   * <p>Worth knowing before banning tools to break a loop on this vendor: the turn that comes back
   * may hold nothing to show anyone.
   */
  @Test
  void forbidding_tools_means_no_call_is_made() {
    try (AnthropicInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              asking(
                  List.of(open(1, "How deep is Loch Ness?")), twoTools(), new ToolChoice.None()));

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
   * <p>This adapter satisfies it with {@code output_config.format} on the stable Messages API --
   * the parameter that replaced the beta {@code output_format}. What is asserted is only the
   * contract every adapter shares: the answer is JSON matching the schema. Which mechanism ran is
   * exactly what a caller must never have to know.
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

    try (AnthropicInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(askingFor(List.of(open(1, "What is the capital of France?")), shape));

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
      JsonNode parsed =
          JsonMapper.builder().build().readTree(textOf((InferenceResult.Answer) result));
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

    try (AnthropicInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(askingFor(List.of(open(1, "Tell me a joke.")), shape));

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

  private static InferenceRequest carrying(
      SystemPrompt system, List<Turn> turns, Map<String, String> properties) {
    return new InferenceRequest(
        system,
        InferenceContext.of(turns),
        Toolset.none(),
        new InferenceOptions(MODEL, 2048, properties));
  }

  /**
   * Section 9c's tier rule on the wire: a provider that does not think, an agent type that asks it
   * to.
   */
  @Test
  void an_agent_type_s_budget_makes_a_provider_that_does_not_think_think() {
    try (AnthropicInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(
              carrying(
                  SYSTEM,
                  List.of(open(1, "Think about it, then say how many continents there are.")),
                  Map.of("anthropic.thinking.budget_tokens", "1024")));

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
      assertThat(((InferenceResult.Answer) result).blocks())
          .as("thinking was asked for, so the reply carries this vendor's own state")
          .anyMatch(Block.Provider.class::isInstance);
    }
  }

  /**
   * A tool loop with caching on: the second call must read back what the first one wrote.
   *
   * <p>The standing prompt and the one tool are far too short to cache, so a read here is a read of
   * the conversation. It shows the vendor accepts a marker on a tool result and finds a prefix the
   * last request stored; with only two blocks between the rounds it cannot tell which of the two
   * markers found it.
   */
  @Test
  void a_cached_tool_loop_reads_back_what_the_last_call_wrote() {
    ToolOffer lookup =
        new ToolOffer(
            new ToolName("lake_depth"),
            "returns the maximum depth of a named lake, in metres",
            new JsonSchema(
                """
                {"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""));
    Input question =
        new Input(new Seq(1), List.of(new Block.Text("How deep are Loch Ness and Loch Morar?")));
    Exchange ness = looked(2, "call_ness", "Loch Ness", "230 metres. ");
    Exchange morar = looked(4, "call_morar", "Loch Morar", "310 metres. ");
    Turn afterOneRound = new Turn(new TurnId(1), question, List.of(ness), null, 0);
    Turn afterTwoRounds = new Turn(new TurnId(1), question, List.of(ness, morar), null, 0);
    Map<String, String> cached = Map.of("anthropic.cache_control.ttl", "FIVE_MINUTES");

    try (AnthropicInferenceProvider provider = provider()) {
      InferenceResult first = provider.infer(looping(afterOneRound, lookup, cached));
      InferenceResult second = provider.infer(looping(afterTwoRounds, lookup, cached));

      assertThat(first).isNotInstanceOf(InferenceResult.Fault.class);
      assertThat(second).isNotInstanceOf(InferenceResult.Fault.class);
      assertThat(second.usage().cacheReadTokens())
          .as("the second call starts with everything the first one ended on")
          .isInstanceOfSatisfying(
              Tokens.Counted.class, read -> assertThat(read.count()).isPositive());
    }
  }

  /**
   * Background that differs on every call sits after the cached prefix, so it cannot spoil it: the
   * second and third calls of a tool loop read back what the call before wrote even though the
   * clock said something new each time.
   */
  @Test
  void ambient_that_changes_on_every_call_does_not_spoil_the_cache() {
    ToolOffer lookup =
        new ToolOffer(
            new ToolName("lake_depth"),
            "returns the maximum depth of a named lake, in metres",
            new JsonSchema(
                """
                {"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""));
    Input question =
        new Input(
            new Seq(1),
            List.of(new Block.Text("How deep are Loch Ness, Loch Morar and Loch Lomond?")));
    Exchange ness = looked(2, "call_ness", "Loch Ness", "230 metres. ");
    Exchange morar = looked(4, "call_morar", "Loch Morar", "310 metres. ");
    Exchange lomond = looked(6, "call_lomond", "Loch Lomond", "190 metres. ");
    Map<String, String> cached = Map.of("anthropic.cache_control.ttl", "FIVE_MINUTES");

    try (AnthropicInferenceProvider provider = provider()) {
      InferenceResult first =
          provider.infer(
              withClock(
                  new Turn(new TurnId(1), question, List.of(ness), null, 0),
                  lookup,
                  cached,
                  "it is 10:00"));
      InferenceResult second =
          provider.infer(
              withClock(
                  new Turn(new TurnId(1), question, List.of(ness, morar), null, 0),
                  lookup,
                  cached,
                  "it is 10:01"));
      InferenceResult third =
          provider.infer(
              withClock(
                  new Turn(new TurnId(1), question, List.of(ness, morar, lomond), null, 0),
                  lookup,
                  cached,
                  "it is 10:02"));

      assertThat(first).isNotInstanceOf(InferenceResult.Fault.class);
      assertThat(second).isNotInstanceOf(InferenceResult.Fault.class);
      assertThat(third).isNotInstanceOf(InferenceResult.Fault.class);
      assertThat(second.usage().cacheReadTokens())
          .as("the second call reads the first call's prefix")
          .isInstanceOfSatisfying(
              Tokens.Counted.class, read -> assertThat(read.count()).isPositive());
      assertThat(third.usage().cacheReadTokens())
          .as("the third call reads the second call's prefix")
          .isInstanceOfSatisfying(
              Tokens.Counted.class, read -> assertThat(read.count()).isPositive());
    }
  }

  private static InferenceRequest withClock(
      Turn turn, ToolOffer offer, Map<String, String> properties, String clock) {
    return new InferenceRequest(
        SYSTEM,
        new InferenceContext(List.of(turn), List.of(Ambient.text("clock", clock))),
        new Toolset(List.of(offer), ToolChoice.auto()),
        new InferenceOptions(MODEL, 2048, properties));
  }

  /** One call and a result long enough that the prefix it ends is worth caching. */
  private static Exchange looked(long seq, String callId, String lake, String depth) {
    return new Exchange(
        new Seq(seq),
        List.of(
            new Block.ToolCall(
                new CallId(callId),
                new ToolName("lake_depth"),
                "{\"name\":\"%s\"}".formatted(lake))),
        List.of(
            new ToolOutcome.Succeeded(
                new CallId(callId),
                List.of(
                    new Block.Text(
                        depth
                            + "Survey notes you may ignore: the loch is deep and cold. "
                                .repeat(300))))));
  }

  private static InferenceRequest looping(
      Turn turn, ToolOffer offer, Map<String, String> properties) {
    return new InferenceRequest(
        SYSTEM,
        InferenceContext.of(List.of(turn)),
        new Toolset(List.of(offer), ToolChoice.auto()),
        new InferenceOptions(MODEL, 2048, properties));
  }

  /** A prefix long enough to cache, asked twice: a write, then a read, is reported. */
  @Test
  void a_ttl_property_caches_the_prefix() {
    SystemPrompt longPrompt =
        new SystemPrompt(
            "You are a terse assistant. "
                + "Background you may ignore: the loch is deep and cold. ".repeat(300));
    Map<String, String> cached = Map.of("anthropic.cache_control.ttl", "FIVE_MINUTES");

    try (AnthropicInferenceProvider provider = provider()) {
      InferenceResult first =
          provider.infer(carrying(longPrompt, List.of(open(1, "Say hello.")), cached));
      InferenceResult second =
          provider.infer(carrying(longPrompt, List.of(open(1, "Say hello.")), cached));

      assertThat(List.of(first.usage().cacheWriteTokens(), second.usage().cacheReadTokens()))
          .as("the prefix was written to the cache, or read back from it")
          .anyMatch(count -> count instanceof Tokens.Counted(int value) && value > 0);
    }
  }
}
