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
package org.jwcarman.nessy.inference.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.OutputSchema;
import org.jwcarman.nessy.inference.Seq;
import org.jwcarman.nessy.inference.SystemPrompt;
import org.jwcarman.nessy.inference.ToolChoice;
import org.jwcarman.nessy.inference.TurnId;
import org.jwcarman.nessy.inference.block.Block;
import org.jwcarman.nessy.inference.turn.Observation;
import org.jwcarman.nessy.inference.turn.Turn;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Against the real service, when the environment carries a credential; skipped otherwise. Spends
 * real tokens, so it asks small questions of a cheap model.
 */
class BedrockLiveTest {

  /** The cheapest model that streams, because this runs on somebody's bill. */
  /**
   * Overridable, because structured output on Converse is a <em>per-model</em> capability: Nova
   * Lite answers "This model doesn't support the outputConfig field", where a Claude model on the
   * same wire accepts it. Point the suite at another with {@code NESSY_LIVE_MODEL}.
   */
  private static final String MODEL =
      System.getenv().getOrDefault("NESSY_LIVE_MODEL", "us.amazon.nova-lite-v1:0");

  private static InferenceRequest asking(String question) {
    return new InferenceRequest(
        new SystemPrompt("You are a terse assistant."),
        InferenceContext.of(
            List.of(
                new Turn(
                    new TurnId(1),
                    new Observation(new Seq(1), List.of(new Block.Text(question))),
                    List.of(),
                    null,
                    0))),
        List.of(),
        new InferenceOptions(MODEL, 512));
  }

  private static InferenceRequest askingFor(String question, OutputSchema shape) {
    return new InferenceRequest(
        new SystemPrompt("You are a terse assistant."),
        InferenceContext.of(
            List.of(
                new Turn(
                    new TurnId(1),
                    new Observation(new Seq(1), List.of(new Block.Text(question))),
                    List.of(),
                    null,
                    0))),
        List.of(),
        ToolChoice.auto(),
        new InferenceOptions(MODEL, 512),
        Optional.of(shape));
  }

  private static BedrockInferenceProvider provider() {
    assumeTrue(
        System.getenv("AWS_BEARER_TOKEN_BEDROCK") != null
            || System.getenv("AWS_ACCESS_KEY_ID") != null,
        "neither AWS_BEARER_TOKEN_BEDROCK nor AWS_ACCESS_KEY_ID is set");
    assumeTrue(
        System.getenv("AWS_REGION") != null || System.getenv("AWS_DEFAULT_REGION") != null,
        "AWS_REGION is not set");
    return BedrockInferenceProvider.fromEnv();
  }

  private static String text(InferenceResult result) {
    assertThat(result).isInstanceOf(InferenceResult.Answer.class);
    return ((InferenceResult.Answer) result)
        .blocks().stream()
            .filter(Block.Text.class::isInstance)
            .map(block -> ((Block.Text) block).text())
            .collect(Collectors.joining());
  }

  @Test
  void a_real_question_gets_a_real_answer() {
    try (BedrockInferenceProvider provider = provider()) {
      assertThat(text(provider.infer(asking("What is the capital of France? One word."))))
          .containsIgnoringCase("Paris");
    }
  }

  /**
   * The narrator hears the answer in pieces before the result carries it whole: several deltas, and
   * their concatenation is exactly the text the engine is handed.
   */
  @Test
  void the_answer_is_narrated_as_it_streams() {
    try (BedrockInferenceProvider provider = provider()) {
      Narration narrated = new Narration();

      InferenceResult result =
          provider.infer(asking("List the seven days of the week, one per line."), narrated);

      List<String> deltas = narrated.text();
      assertThat(deltas).as("a real stream arrives in more than one piece").hasSizeGreaterThan(1);
      assertThat(String.join("", deltas)).isEqualTo(text(result));
    }
  }

  /**
   * A shape asked for, and a shape that comes back.
   *
   * <p>Converse takes the schema as a string, so this is the one adapter where what {@code
   * OutputSchema} holds goes on the wire unchanged. What is asserted is only the contract every
   * adapter shares: the answer is JSON matching the schema.
   */
  @Test
  void an_answer_can_be_asked_for_in_a_shape() {
    OutputSchema shape =
        new OutputSchema(
            """
            {"type":"object",
             "properties":{"city":{"type":"string"},"country":{"type":"string"}},
             "required":["city","country"],
             "additionalProperties":false}""");

    try (BedrockInferenceProvider provider = provider()) {
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
    OutputSchema shape =
        new OutputSchema(
            """
            {"type":"object",
             "properties":{"answer":{"type":"string"},"confident":{"type":"boolean"}},
             "required":["answer","confident"],
             "additionalProperties":false}""");

    try (BedrockInferenceProvider provider = provider()) {
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
}
