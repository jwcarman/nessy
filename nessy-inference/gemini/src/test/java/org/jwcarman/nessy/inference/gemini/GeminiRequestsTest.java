package org.jwcarman.nessy.inference.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.InputSchema;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Observation;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.spi.inference.InferenceContext;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.ToolOffer;
import tools.jackson.databind.json.JsonMapper;

/** The projection onto Gemini's generateContent wire, with no network anywhere near it. */
@DisplayName("Gemini requests")
class GeminiRequestsTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final SystemPrompt SYSTEM = new SystemPrompt("you are a helpful assistant");

  private static InferenceRequest request(List<Turn> turns) {
    return request(new InferenceContext(turns, List.of()), List.of());
  }

  private static InferenceRequest request(InferenceContext context, List<ToolOffer> tools) {
    return new InferenceRequest(
        SYSTEM, context, tools, new InferenceOptions("gemini-2.5-pro", 1024));
  }

  private static Observation asked(long seq, String text) {
    return new Observation(new Seq(seq), List.of(new Block.Text(text)));
  }

  private static Turn answered(long id, String question, String answer) {
    return new Turn(
        new TurnId(id),
        asked(id, question),
        List.of(),
        new TurnResult.Answered(List.of(new Block.Text(answer))),
        0);
  }

  private static Turn open(long id, String question) {
    return new Turn(new TurnId(id), asked(id, question), List.of(), null, 0);
  }

  private static String textOf(Content content) {
    return content.parts().orElseThrow().stream()
        .map(part -> part.text().orElse(""))
        .reduce("", String::concat);
  }

  @Nested
  class TheConversation {

    @Test
    void an_observation_is_a_user_turn_and_an_answer_is_a_model_turn() {
      List<Content> contents =
          GeminiRequests.toContents(
              request(List.of(answered(1, "hi", "hello"), open(3, "bye"))), MAPPER);

      assertThat(contents)
          .extracting(c -> c.role().orElseThrow())
          .containsExactly("user", "model", "user");
      assertThat(contents)
          .extracting(GeminiRequestsTest::textOf)
          .containsExactly("hi", "hello", "bye");
    }

    @Test
    void a_summary_stands_first_as_a_bracketed_user_turn() {
      InferenceContext context =
          new InferenceContext(
              List.of(Summary.text(new TurnId(1), new TurnId(9), "they talked about lakes")),
              List.of(open(11, "and monsters?")),
              List.of());

      List<Content> contents = GeminiRequests.toContents(request(context, List.of()), MAPPER);

      assertThat(contents).hasSize(2);
      assertThat(contents.getFirst().role()).contains("user");
      assertThat(textOf(contents.getFirst()))
          .startsWith("<summary from=\"1\" through=\"9\">")
          .contains("they talked about lakes");
    }

    @Test
    void a_failed_turn_is_answered_for_and_a_refused_one_is_left_out() {
      Turn failed = new Turn(new TurnId(1), asked(1, "one"), List.of(), new TurnResult.Failed(), 0);
      Turn refused =
          new Turn(new TurnId(3), asked(3, "two"), List.of(), new TurnResult.Refused(), 0);

      List<Content> contents =
          GeminiRequests.toContents(request(List.of(failed, refused, open(5, "three"))), MAPPER);

      assertThat(contents)
          .extracting(GeminiRequestsTest::textOf)
          .containsExactly("one", "(The previous attempt to answer did not complete.)", "three");
    }
  }

  @Nested
  class AnExchange {

    private final byte[] signature = "sig".getBytes(StandardCharsets.UTF_8);

    private Turn withCall(Block.Provider... provider) {
      List<Block.ActionRequestContent> request = new java.util.ArrayList<>();
      request.add(new Block.Commentary("let me look"));
      request.add(new Block.ToolCall("call_1", "depth", "{\"lake\":\"ness\"}"));
      request.addAll(List.of(provider));
      Exchange exchange =
          new Exchange(
              new Seq(2),
              request,
              List.of(
                  new ToolOutcome.Succeeded(
                      new CallId("call_1"), List.of(new Block.Text("230m")))));
      return new Turn(new TurnId(1), asked(1, "how deep?"), List.of(exchange), null, 0);
    }

    @Test
    void the_call_goes_out_as_a_model_turn_and_the_result_comes_back_by_function_name() {
      List<Content> contents = GeminiRequests.toContents(request(List.of(withCall())), MAPPER);

      assertThat(contents)
          .extracting(c -> c.role().orElseThrow())
          .containsExactly("user", "model", "user");
      Content asking = contents.get(1);
      Part call = asking.parts().orElseThrow().get(1);
      assertThat(call.functionCall().orElseThrow().name()).contains("depth");
      assertThat(call.functionCall().orElseThrow().args()).contains(Map.of("lake", "ness"));
      Part response = contents.get(2).parts().orElseThrow().getFirst();
      assertThat(response.functionResponse().orElseThrow().name()).contains("depth");
      assertThat(response.functionResponse().orElseThrow().response())
          .contains(Map.of("output", "230m"));
    }

    @Test
    void a_signature_this_vendor_issued_rides_back_on_its_call() {
      Block.Provider ours =
          new Block.Provider(
              GeminiInferenceProvider.PROVIDER_NAME,
              MAPPER.writeValueAsString(
                  Map.of(
                      "type",
                      "thought-signature",
                      "callId",
                      "call_1",
                      "signature",
                      Base64.getEncoder().encodeToString(signature))));

      List<Content> contents = GeminiRequests.toContents(request(List.of(withCall(ours))), MAPPER);

      Part call = contents.get(1).parts().orElseThrow().get(1);
      assertThat(call.thoughtSignature()).contains(signature);
    }

    @Test
    void a_call_with_no_signature_is_replayed_with_the_skip_sentinel_not_refused() {
      List<Content> contents = GeminiRequests.toContents(request(List.of(withCall())), MAPPER);

      Part call = contents.get(1).parts().orElseThrow().get(1);
      assertThat(new String(call.thoughtSignature().orElseThrow(), StandardCharsets.UTF_8))
          .isEqualTo("skip_thought_signature_validator");
    }

    @Test
    void another_vendors_state_is_not_sent() {
      Block.Provider theirs = new Block.Provider("anthropic", "{\"type\":\"thinking\"}");

      List<Content> contents =
          GeminiRequests.toContents(request(List.of(withCall(theirs))), MAPPER);

      assertThat(contents.get(1).parts().orElseThrow()).hasSize(2);
    }

    @Test
    void a_failure_and_a_denial_come_back_as_errors() {
      Exchange exchange =
          new Exchange(
              new Seq(2),
              List.of(new Block.ToolCall("c1", "a", "{}"), new Block.ToolCall("c2", "b", "{}")),
              List.of(
                  new ToolOutcome.Failed(new CallId("c1"), "boom"),
                  new ToolOutcome.Denied(new CallId("c2"), "not today")));
      Turn turn = new Turn(new TurnId(1), asked(1, "go"), List.of(exchange), null, 0);

      List<Content> contents = GeminiRequests.toContents(request(List.of(turn)), MAPPER);

      List<Part> responses = contents.get(2).parts().orElseThrow();
      assertThat(responses.get(0).functionResponse().orElseThrow().response())
          .contains(Map.of("error", "boom"));
      assertThat(responses.get(1).functionResponse().orElseThrow().response().orElseThrow())
          .containsEntry("error", "This call was not run because it was not permitted: not today");
    }
  }

  @Nested
  class TheConfig {

    @Test
    void the_prompt_and_the_ambient_are_the_system_instruction() {
      InferenceContext context =
          new InferenceContext(
              List.of(open(1, "hi")), List.of(Ambient.text("clock", "it is Tuesday")));

      GenerateContentConfig config = GeminiRequests.toConfig(request(context, List.of()), MAPPER);

      List<Part> instruction = config.systemInstruction().orElseThrow().parts().orElseThrow();
      assertThat(instruction)
          .extracting(p -> p.text().orElseThrow())
          .containsExactly("you are a helpful assistant", "<clock>\nit is Tuesday\n</clock>");
      assertThat(config.maxOutputTokens()).contains(1024);
    }

    @Test
    void a_tool_is_a_function_declaration_with_its_schema_whole() {
      ToolOffer offer =
          new ToolOffer(
              new ToolName("depth"),
              "how deep a lake is",
              new InputSchema(
                  "{\"type\":\"object\",\"properties\":{\"lake\":{\"type\":\"string\"}},\"required\":[\"lake\"]}"));

      GenerateContentConfig config =
          GeminiRequests.toConfig(
              request(new InferenceContext(List.of(open(1, "hi")), List.of()), List.of(offer)),
              MAPPER);

      FunctionDeclaration declaration =
          config.tools().orElseThrow().getFirst().functionDeclarations().orElseThrow().getFirst();
      assertThat(declaration.name()).contains("depth");
      assertThat(declaration.description()).contains("how deep a lake is");
      assertThat(declaration.parametersJsonSchema()).isPresent();
      assertThat(declaration.parametersJsonSchema().orElseThrow().toString()).contains("required");
    }
  }

  @Nested
  class TheEdges {

    @Test
    void a_call_still_awaiting_its_results_is_sent_alone() {
      Exchange asking =
          new Exchange(new Seq(2), List.of(new Block.ToolCall("c1", "lookup", "{}")), List.of());
      Turn turn = new Turn(new TurnId(1), asked(1, "go"), List.of(asking), null, 0);

      List<Content> contents = GeminiRequests.toContents(request(List.of(turn)), MAPPER);

      assertThat(contents).hasSize(2);
      assertThat(contents.get(0).parts().orElseThrow()).hasSize(1);
      assertThat(contents.get(1).parts().orElseThrow()).hasSize(1);
    }

    @Test
    void a_result_for_a_call_the_exchange_did_not_make_is_refused() {
      Exchange odd =
          new Exchange(
              new Seq(2),
              List.of(new Block.ToolCall("c1", "lookup", "{}")),
              List.of(new ToolOutcome.Succeeded(new CallId("c9"), List.of(new Block.Text("?")))));
      Turn turn = new Turn(new TurnId(1), asked(1, "go"), List.of(odd), null, 0);
      InferenceRequest request = request(List.of(turn));

      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> GeminiRequests.toContents(request, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("c9");
    }

    @Test
    void a_signature_that_is_not_base64_is_replayed_unsigned() {
      Block.Provider broken =
          new Block.Provider(
              GeminiInferenceProvider.PROVIDER_NAME,
              "{\"type\":\"thought-signature\",\"callId\":\"c1\",\"signature\":\"not base64!\"}");
      Exchange exchange =
          new Exchange(
              new Seq(2),
              List.of(new Block.ToolCall("c1", "lookup", "{}"), broken),
              List.of(new ToolOutcome.Succeeded(new CallId("c1"), List.of(new Block.Text("ok")))));
      Turn turn = new Turn(new TurnId(1), asked(1, "go"), List.of(exchange), null, 0);

      List<Content> contents = GeminiRequests.toContents(request(List.of(turn)), MAPPER);

      Part call = contents.get(1).parts().orElseThrow().getFirst();
      assertThat(new String(call.thoughtSignature().orElseThrow(), StandardCharsets.UTF_8))
          .isEqualTo("skip_thought_signature_validator");
    }

    @Test
    void the_text_of_a_summary_includes_commentary_and_skips_state() {
      assertThat(
              GeminiRequests.text(
                  List.of(
                      new Block.Commentary("a"),
                      new Block.Provider("v", "{}"),
                      new Block.ToolCall("c", "t", "{}"),
                      new Block.Text("b"))))
          .isEqualTo("ab");
    }
  }
}
