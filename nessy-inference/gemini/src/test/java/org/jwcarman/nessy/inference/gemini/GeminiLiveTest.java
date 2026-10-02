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
    try (GeminiInferenceProvider provider = provider()) {
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
   * ToolChoice.Named}; this class has no other test that requires a tool. The text it is asked to
   * carry cannot fit in 1500 tokens, so the call is cut off mid-argument. What matters is the first
   * assertion: a call whose arguments are incomplete, or empty, must never be handed over to run.
   * The wire's own wording for the cut-off varies by model, so only {@code tool call} is asserted
   * of the reason.
   *
   * <p>Measured 2026-10-02 by a raw-HTTP probe: Gemini 3.1 Pro returned {@code
   * MALFORMED_FUNCTION_CALL} with no call at all, so the fault may read {@code tool call was
   * malformed} where a model that does finish its call's JSON gives {@code MAX_TOKENS}. Both
   * contain {@code tool call}, which is all this asserts.
   */
  @Test
  void a_tool_call_cut_off_at_the_output_limit_is_a_fault_and_is_never_a_call_to_run() {
    try (GeminiInferenceProvider provider = provider()) {
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
