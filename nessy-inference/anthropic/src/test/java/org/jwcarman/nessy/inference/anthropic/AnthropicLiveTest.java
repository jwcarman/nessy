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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.InputSchema;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Observation;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.spi.inference.InferenceContext;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.inference.ToolChoice;
import org.jwcarman.nessy.spi.inference.ToolOffer;

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
    return AnthropicInferenceProvider.create(
        config -> {
          config.fromEnv();
          customizer.accept(config);
        });
  }

  private static Turn open(long id, String question) {
    return new Turn(
        new TurnId(id),
        new Observation(new Seq(id), List.of(new Block.Text(question))),
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
        SYSTEM, InferenceContext.of(turns), tools, choice, new InferenceOptions(MODEL, 2048));
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
      List<AgentEvent> narrated = new ArrayList<>();

      InferenceResult result =
          provider.infer(
              asking(List.of(open(1, "List the seven days of the week, one per line.")), List.of()),
              narrated::add);

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
      String answer =
          ((InferenceResult.Answer) result)
              .blocks().stream()
                  .filter(Block.Text.class::isInstance)
                  .map(block -> ((Block.Text) block).text())
                  .collect(java.util.stream.Collectors.joining());
      List<String> deltas =
          narrated.stream()
              .filter(AgentEvent.ContentDelta.class::isInstance)
              .map(event -> ((AgentEvent.ContentDelta) event).text())
              .toList();
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
              new InputSchema(
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
    try (AnthropicInferenceProvider provider = provider(config -> config.thinking(true))) {
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
              new Observation(
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

  /** Prompt caching is money rather than correctness, so what matters is that it is accepted. */
  @Test
  void a_cached_request_is_accepted() {
    try (AnthropicInferenceProvider provider =
        provider(config -> config.promptCaching(PromptCaching.FIVE_MINUTES))) {
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
            new InputSchema(
                """
                {"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")),
        new ToolOffer(
            new ToolName("weather"),
            "returns today's weather for a named place",
            new InputSchema(
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

      assertThat(result.usage().known())
          .as("the call reached the model, so what came back is behaviour and not a failed send")
          .isTrue();
      assertThat(result)
          .as("the lake tool was right there, so a call would mean the ban was dropped")
          .isNotInstanceOf(InferenceResult.Actions.class);
    }
  }
}
