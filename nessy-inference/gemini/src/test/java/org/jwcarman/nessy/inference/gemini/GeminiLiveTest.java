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
package org.jwcarman.nessy.inference.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.Toolset;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Against the real service, when the environment carries a credential; skipped otherwise. Spends
 * real tokens, so it asks small questions of a cheap model.
 */
@Tag("live")
class GeminiLiveTest {

  /** The cheapest model that streams, because this runs on somebody's bill. */
  private static final String MODEL = "gemini-3.6-flash";

  private static InferenceRequest asking(String question) {
    return asking(question, 1024);
  }

  private static InferenceRequest asking(String question, int maxTokens) {
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
        new InferenceOptions(MODEL, maxTokens));
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
        new InferenceOptions(MODEL, 1024),
        Optional.of(shape));
  }

  private static GeminiInferenceProvider provider() {
    assumeTrue(
        System.getenv("GEMINI_API_KEY") != null || System.getenv("GOOGLE_API_KEY") != null,
        "GEMINI_API_KEY (or GOOGLE_API_KEY) is not set");
    return GeminiInferenceProvider.fromEnv();
  }

  private static String text(InferenceResult result) {
    assertThat(result)
        .as("the reply, whole: %s", result)
        .isInstanceOf(InferenceResult.Answer.class);
    return ((InferenceResult.Answer) result)
        .blocks().stream()
            .filter(Block.Text.class::isInstance)
            .map(block -> ((Block.Text) block).text())
            .collect(Collectors.joining());
  }

  @Test
  void a_real_question_gets_a_real_answer() {
    try (GeminiInferenceProvider provider = provider()) {
      assertThat(text(provider.infer(asking("What is the capital of France? One word."))))
          .containsIgnoringCase("Paris");
    }
  }

  /**
   * The narrator hears the answer in pieces before the result carries it whole: several deltas, and
   * their concatenation is exactly the text the engine is handed.
   *
   * <p>Gemini streams by generation step, not by token, so a one-line answer arrives as one partial
   * and would prove nothing. A few paragraphs cannot.
   */
  @Test
  void the_answer_is_narrated_as_it_streams() {
    try (GeminiInferenceProvider provider = provider()) {
      Narration narrated = new Narration();

      InferenceResult result =
          provider.infer(
              asking(
                  "In three paragraphs of about eighty words each, explain how Loch Ness was"
                      + " formed, why it is so deep, and what lives in it.",
                  4096),
              narrated);

      List<String> deltas = narrated.text();
      assertThat(deltas)
          .as(
              "a real stream arrives in more than one piece; result: %s; narrated: %s",
              result, narrated.fragments())
          .hasSizeGreaterThan(1);
      assertThat(String.join("", deltas))
          .as("the narrated text is the answer; result: %s", result)
          .isEqualTo(text(result));
    }
  }

  /**
   * A shape asked for, and a shape that comes back.
   *
   * <p>Satisfied here with {@code responseJsonSchema} plus the JSON MIME type, which this wire
   * wants together. What is asserted is only the contract every adapter shares: the answer is JSON
   * matching the schema.
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

    try (GeminiInferenceProvider provider = provider()) {
      InferenceResult result = provider.infer(askingFor("What is the capital of France?", shape));

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

    try (GeminiInferenceProvider provider = provider()) {
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

  private static InferenceRequest thinking(Map<String, String> properties) {
    return new InferenceRequest(
        new SystemPrompt("You are a terse assistant."),
        InferenceContext.of(
            List.of(
                new Turn(
                    new TurnId(1),
                    new Input(
                        new Seq(1),
                        List.of(
                            // Something to work out: on a trivial prompt the model may think
                            // without returning a thought summary (measured 2026-09-30).
                            new Block.Text(
                                "What is 17 times 23? Work it out, then give the number."))),
                    List.of(),
                    null,
                    0))),
        Toolset.none(),
        new InferenceOptions(MODEL, 2048, properties));
  }

  private void thinkingReachesGemini(Map<String, String> properties) {
    try (GeminiInferenceProvider provider = provider()) {
      Narration narrated = new Narration();
      InferenceResult result = provider.infer(thinking(properties), narrated);
      String answer = text(result);

      // The setting reached Gemini when it reports reasoning tokens. Whether it also returns a
      // thought summary is the model's choice even with includeThoughts (measured 2026-09-30), so
      // what is asserted of thoughts is only that any which arrive are narrated as thinking and
      // never become the answer.
      assertThat(result.usage().reasoningTokens().orZero())
          .as("the model thought; result: %s", result)
          .isPositive();
      assertThat(answer).as("the answer; result: %s", result).contains("391");
      for (Narration.Fragment fragment : narrated.fragments()) {
        if ("thinking".equals(fragment.kind()) && !fragment.text().isBlank()) {
          assertThat(answer)
              .as("a thought that arrived stays out of the answer; result: %s", result)
              .doesNotContain(fragment.text().strip());
        }
      }
    }
  }

  /**
   * The typed thinking budget reaches the vendor: the model thinks, and any thoughts stay out of
   * the answer.
   */
  @Test
  void a_typed_thinking_config_reaches_gemini() {
    thinkingReachesGemini(
        Map.of(
            GeminiProperties.INCLUDE_THOUGHTS.name(),
            GeminiProperties.INCLUDE_THOUGHTS.format(true),
            GeminiProperties.THINKING_BUDGET.name(),
            GeminiProperties.THINKING_BUDGET.format(512)));
  }

  /** The typed thinking level reaches the vendor, the knob Gemini 3 is steered by. */
  @Test
  void a_typed_thinking_level_reaches_gemini() {
    thinkingReachesGemini(
        Map.of(
            GeminiProperties.INCLUDE_THOUGHTS.name(),
            GeminiProperties.INCLUDE_THOUGHTS.format(true),
            GeminiProperties.THINKING_LEVEL.name(),
            GeminiProperties.THINKING_LEVEL.format(GeminiThinkingLevel.HIGH)));
  }
}
