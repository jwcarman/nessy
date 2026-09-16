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
}
