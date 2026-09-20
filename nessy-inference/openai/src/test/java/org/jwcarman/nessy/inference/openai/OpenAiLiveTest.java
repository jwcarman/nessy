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

import java.util.ArrayList;
import java.util.List;
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
import org.jwcarman.nessy.spi.inference.InferenceContext;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.inference.ToolChoice;
import org.jwcarman.nessy.spi.inference.ToolOffer;

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
 * OPENAI_API_KEY=sk-... ./mvnw -pl nessy-inference/openai test -Dtest=OpenAiLiveTest
 * }</pre>
 */
class OpenAiLiveTest {

  /** The cheapest model that still calls tools, because this runs on somebody's bill. */
  private static final String MODEL = "gpt-4o-mini";

  private static InferenceRequest asking(String question, List<ToolOffer> tools) {
    return asking(question, tools, ToolChoice.auto());
  }

  private static InferenceRequest asking(
      String question, List<ToolOffer> tools, ToolChoice choice) {
    return new InferenceRequest(
        new SystemPrompt("You are a terse assistant. Answer in one short sentence."),
        InferenceContext.of(
            List.of(
                new Turn(
                    new TurnId(1),
                    new Observation(new Seq(1), List.of(new Block.Text(question))),
                    List.of(),
                    null,
                    0))),
        tools,
        choice,
        InferenceOptions.of(MODEL));
  }

  private static OpenAiInferenceProvider provider() {
    assumeTrue(System.getenv("OPENAI_API_KEY") != null, "OPENAI_API_KEY is not set");
    return OpenAiInferenceProvider.fromEnv();
  }

  @Test
  void a_real_question_gets_a_real_answer() {
    try (OpenAiInferenceProvider provider = provider()) {
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
    try (OpenAiInferenceProvider provider = provider()) {
      List<AgentEvent> narrated = new ArrayList<>();

      InferenceResult result =
          provider.infer(
              asking("List the seven days of the week, one per line.", List.of()), narrated::add);

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
      String answer = ((Block.Text) ((InferenceResult.Answer) result).blocks().getFirst()).text();
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
   * The schema this project generates, accepted by the vendor and answered against.
   *
   * <p>This is the assertion the offline tests most need backing: {@code InputSchema} carries JSON
   * text that only OpenAI can say is well-formed enough to bind a call to.
   */
  @Test
  void a_tool_offer_is_accepted_and_called_with_arguments_that_fit_its_schema() {
    try (OpenAiInferenceProvider provider = provider()) {
      ToolOffer lookup =
          new ToolOffer(
              new ToolName("lake_depth"),
              "returns the maximum depth of a named lake, in metres",
              new InputSchema(
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
    try (OpenAiInferenceProvider provider = provider()) {
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
    try (OpenAiInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(asking("Say hello.", twoTools(), new ToolChoice.Any()));

      assertThat(result)
          .as("nothing here needs a tool, so an Answer would mean the requirement was dropped")
          .isInstanceOf(InferenceResult.Actions.class);
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
    try (OpenAiInferenceProvider provider = provider()) {
      InferenceResult result =
          provider.infer(asking("How deep is Loch Ness?", twoTools(), new ToolChoice.None()));

      assertThat(result.usage().known())
          .as("the call reached the model, so what came back is behaviour and not a failed send")
          .isTrue();
      assertThat(result)
          .as("the lake tool was right there, so a call would mean the ban was dropped")
          .isNotInstanceOf(InferenceResult.Actions.class);
    }
  }
}
