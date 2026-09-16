package org.jwcarman.nessy.inference.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Observation;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.spi.inference.InferenceContext;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;

/**
 * Against the real service, when the environment carries a credential; skipped otherwise. Spends
 * real tokens, so it asks small questions of a cheap model.
 */
class BedrockLiveTest {

  /** The cheapest model that streams, because this runs on somebody's bill. */
  private static final String MODEL = "us.amazon.nova-lite-v1:0";

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
      List<AgentEvent> narrated = new ArrayList<>();

      InferenceResult result =
          provider.infer(asking("List the seven days of the week, one per line."), narrated::add);

      List<String> deltas =
          narrated.stream()
              .filter(AgentEvent.ContentDelta.class::isInstance)
              .map(event -> ((AgentEvent.ContentDelta) event).text())
              .toList();
      assertThat(deltas).as("a real stream arrives in more than one piece").hasSizeGreaterThan(1);
      assertThat(String.join("", deltas)).isEqualTo(text(result));
    }
  }
}
