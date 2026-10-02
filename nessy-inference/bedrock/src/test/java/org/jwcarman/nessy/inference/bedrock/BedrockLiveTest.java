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
class BedrockLiveTest {

  /**
   * The cheapest model that streams, because this runs on somebody's bill.
   *
   * <p>Overridable, because structured output on Converse is a <em>per-model</em> capability: Nova
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
                    new Input(new Seq(1), List.of(new Block.Text(question))),
                    List.of(),
                    null,
                    0))),
        Toolset.none(),
        new InferenceOptions(MODEL, 512));
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
   * JsonSchema} holds goes on the wire unchanged. What is asserted is only the contract every
   * adapter shares: the answer is JSON matching the schema.
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
    JsonSchema shape =
        new JsonSchema(
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

  // ---- a reply cut off at the output limit ----------------------------------------------

  private static final String LONG_ANSWER =
      "Write an essay of at least 3,000 words on the history of lighthouses, from antiquity to"
          + " the present day. Begin the essay at once, with no preamble.";

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
   * <p>The essay asked for needs far more than the 1500 tokens allowed, and 1500 leaves room for a
   * model that reasons a little before it writes. It is an essay and not a list of numbers because
   * Nova Lite refuses a long numbered listing: measured 2026-10-02, it returned {@code
   * content_filtered} with no output for one, which this adapter reports as a refusal.
   */
  @Test
  void an_answer_cut_off_at_the_output_limit_is_truncated_and_keeps_what_was_written() {
    try (BedrockInferenceProvider provider = provider()) {
      InferenceResult result = provider.infer(limited(LONG_ANSWER, Toolset.none()));

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
              });
      assertThat(truncated.usage().model())
          .as("the vendor named the model that wrote it")
          .isNotBlank();
    }
  }

  /**
   * A tool call cut off at the output limit is a permanent fault and is never a call to run.
   *
   * <p>One tool, {@code save_note}, is offered and the model is required to call it with {@link
   * ToolChoice.Named}; this class has no other test that requires a tool. The text it is asked to
   * carry cannot fit in 1500 tokens, so the call is cut off mid-argument. What matters is the first
   * assertion: a call whose arguments are incomplete, or empty, must never be handed over to run.
   *
   * <p>How this wire reports the cut-off varies by model, and each way is a permanent fault. A
   * cut-off call may surface as {@code max_tokens} with a call present, or as arguments that {@code
   * did not parse}; both give a reason that says {@code tool call}. Measured 2026-10-02 through
   * this test, Nova Lite did neither: the service itself rejected the reply with a 424, "Model
   * produced invalid sequence as part of ToolUse", which this adapter reports as a permanent fault
   * carrying that message. So the reason is one that names a tool call, or the service's own
   * complaint about {@code ToolUse}.
   */
  @Test
  void a_tool_call_cut_off_at_the_output_limit_is_a_fault_and_is_never_a_call_to_run() {
    try (BedrockInferenceProvider provider = provider()) {
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
          .as("the reply, whole: %s", result)
          .isInstanceOfSatisfying(
              Failure.Permanent.class,
              failure -> assertThat(failure.reason()).containsAnyOf("tool call", "ToolUse"));
    }
  }
}
