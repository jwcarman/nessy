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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.genai.types.Content;
import com.google.genai.types.FunctionCallingConfig;
import com.google.genai.types.FunctionCallingConfigMode;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.ToolConfig;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.ToolChoice;
import org.jwcarman.nessy.inference.ToolOffer;
import org.jwcarman.nessy.inference.Toolset;
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
        SYSTEM, context, Toolset.of(tools), new InferenceOptions("gemini-3.6-pro", 1024));
  }

  private static Input asked(long seq, String text) {
    return new Input(new Seq(seq), List.of(new Block.Text(text)));
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
    void an_input_is_a_user_turn_and_an_answer_is_a_model_turn() {
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
              GeminiInferenceProvider.VENDOR,
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
              new JsonSchema(
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
              GeminiInferenceProvider.VENDOR,
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

  /** Whether the model may reach for what it was offered. */
  @Nested
  class ChoosingATool {

    private static GenerateContentConfig choosing(ToolChoice choice) {
      return GeminiRequests.toConfig(
          new InferenceRequest(
              SYSTEM,
              new InferenceContext(List.of(open(1, "hello")), List.of()),
              new Toolset(List.of(offer()), choice),
              new InferenceOptions("gemini-3.6-pro", 1024)),
          MAPPER);
    }

    private static ToolOffer offer() {
      return new ToolOffer(
          new ToolName("lookup"),
          "looks a thing up",
          new JsonSchema("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}"));
    }

    /** An absent config already means auto, so nothing is sent. */
    @Test
    void by_default_no_tool_config_is_sent() {
      assertThat(choosing(ToolChoice.auto()).toolConfig()).isEmpty();
    }

    @Test
    void a_ban_is_sent_as_the_none_mode() {
      assertThat(mode(choosing(new ToolChoice.None()))).isEqualTo("NONE");
    }

    @Test
    void requiring_some_tool_is_sent_as_the_any_mode() {
      assertThat(mode(choosing(new ToolChoice.Any()))).isEqualTo("ANY");
    }

    /** Gemini names the one it must call by allowing only that name. */
    @Test
    void requiring_one_tool_allows_only_that_name() {
      GenerateContentConfig config = choosing(new ToolChoice.Named(new ToolName("lookup")));

      assertThat(mode(config)).isEqualTo("ANY");
      assertThat(
              config
                  .toolConfig()
                  .flatMap(GeminiRequestsTest::calling)
                  .flatMap(FunctionCallingConfig::allowedFunctionNames))
          .contains(List.of("lookup"));
    }

    private static String mode(GenerateContentConfig config) {
      return config
          .toolConfig()
          .flatMap(GeminiRequestsTest::calling)
          .flatMap(FunctionCallingConfig::mode)
          .map(FunctionCallingConfigMode::toString)
          .orElse("(none)");
    }
  }

  private static java.util.Optional<FunctionCallingConfig> calling(ToolConfig config) {
    return config.functionCallingConfig();
  }

  @Nested
  class TheVendorProperties {

    private static final String THINKING = "gemini.generationConfig.thinkingConfig.";

    private static InferenceRequest carrying(Map<String, String> agentType) {
      return new InferenceRequest(
          SYSTEM,
          InferenceContext.of(List.of(open(1, "hi"))),
          Toolset.none(),
          new InferenceOptions("gemini-3.6-pro", 1024, agentType));
    }

    private static GenerateContentConfig configFor(Map<String, String> agentType) {
      return GeminiRequests.toConfig(carrying(agentType), Map.of(), MAPPER);
    }

    @Test
    void the_thinking_names_land_in_the_typed_thinking_config() {
      ThinkingConfig thinking =
          configFor(
                  Map.of(
                      THINKING + "thinkingBudget", "2048",
                      THINKING + "includeThoughts", "true",
                      THINKING + "thinkingLevel", "low"))
              .thinkingConfig()
              .orElseThrow();

      assertThat(thinking.thinkingBudget()).contains(2048);
      assertThat(thinking.includeThoughts()).contains(true);
      assertThat(thinking.thinkingLevel().orElseThrow().toString()).isEqualToIgnoringCase("low");
    }

    @Test
    void without_them_no_thinking_config_is_set() {
      assertThat(configFor(Map.of()).thinkingConfig()).isEmpty();
      assertThat(configFor(Map.of()).httpOptions()).isEmpty();
    }

    @Test
    void an_unknown_name_passes_through_in_the_extra_body_nested_by_path() {
      GenerateContentConfig config =
          configFor(
              Map.of(
                  "gemini.generationConfig.temperature", "0.2",
                  "gemini.labels.team", "billing"));

      Map<String, Object> body = config.httpOptions().orElseThrow().extraBody().orElseThrow();
      assertThat(body)
          .extractingByKey("generationConfig", InstanceOfAssertFactories.MAP)
          .containsEntry("temperature", 0.2);
      assertThat(body)
          .extractingByKey("labels", InstanceOfAssertFactories.MAP)
          .containsEntry("team", "billing");
    }

    @Test
    void a_pass_through_value_keeps_its_json_type() {
      GenerateContentConfig config =
          configFor(
              Map.of(
                  "gemini.generationConfig.topK", "40",
                  "gemini.generationConfig.candidateCount", "1"));

      Map<String, Object> body = config.httpOptions().orElseThrow().extraBody().orElseThrow();
      assertThat(body)
          .extractingByKey("generationConfig", InstanceOfAssertFactories.MAP)
          .containsEntry("topK", 40)
          .containsEntry("candidateCount", 1);
    }

    @Test
    void another_prefix_is_not_sent() {
      assertThat(configFor(Map.of("openai.seed", "1")).httpOptions()).isEmpty();
    }

    @Test
    void an_agent_type_entry_overrides_the_same_name_given_to_the_provider() {
      GenerateContentConfig config =
          GeminiRequests.toConfig(
              carrying(Map.of(THINKING + "thinkingBudget", "512")),
              Map.of(THINKING + "thinkingBudget", "4096"),
              MAPPER);

      assertThat(config.thinkingConfig().orElseThrow().thinkingBudget()).contains(512);
    }

    @Test
    void the_providers_entry_applies_when_the_agent_type_says_nothing() {
      GenerateContentConfig config =
          GeminiRequests.toConfig(
              carrying(Map.of()), Map.of(THINKING + "thinkingBudget", "4096"), MAPPER);

      assertThat(config.thinkingConfig().orElseThrow().thinkingBudget()).contains(4096);
    }

    @Test
    void a_thinking_name_beside_a_lookalike_in_another_object_is_accepted() {
      Map<String, String> properties =
          Map.of(
              THINKING + "thinkingBudget",
              "512",
              "gemini.generationConfig.thinkingConfigs.mode",
              "deep");

      assertThatCode(() -> configFor(properties)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
      "contents,conversation",
      "systemInstruction,system prompt",
      "tools,tools the harness binds",
      "toolConfig,tool choice",
      "generationConfig.maxOutputTokens,InferenceConfig.maxTokens",
      "generationConfig.responseMimeType,answer's shape",
      "generationConfig.responseJsonSchema,answer's shape",
      "generationConfig.responseSchema,answer's shape"
    })
    void every_name_the_adapter_decides_is_refused_naming_what_decides_it(
        String name, String decidedBy) {
      InferenceRequest request = carrying(Map.of("gemini." + name, "x"));

      assertThatThrownBy(() -> GeminiRequests.toConfig(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'gemini." + name + "'")
          .hasMessageContaining(decidedBy);
    }

    @Test
    void the_conversation_is_refused_as_what_the_engine_assembles() {
      InferenceRequest request = carrying(Map.of("gemini.contents", "[]"));

      assertThatThrownBy(() -> GeminiRequests.toConfig(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'gemini.contents'");
    }

    @Test
    void the_ceiling_is_refused_under_its_generation_config_spelling() {
      InferenceRequest request = carrying(Map.of("gemini.generationConfig.maxOutputTokens", "9"));

      assertThatThrownBy(() -> GeminiRequests.toConfig(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("InferenceConfig.maxTokens");
    }

    @Test
    void a_field_inside_the_tool_config_the_engine_decides_is_refused() {
      InferenceRequest request =
          carrying(Map.of("gemini.toolConfig.functionCallingConfig.mode", "ANY"));

      assertThatThrownBy(() -> GeminiRequests.toConfig(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'gemini.toolConfig.functionCallingConfig.mode'")
          .hasMessageContaining("'gemini.toolConfig'");
    }

    @Test
    void the_whole_generation_config_would_replace_the_ceiling_and_is_refused() {
      InferenceRequest request = carrying(Map.of("gemini.generationConfig", "{\"topK\":10}"));

      assertThatThrownBy(() -> GeminiRequests.toConfig(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'gemini.generationConfig' would replace")
          .hasMessageContaining("set the fields one by one");
    }

    @Test
    void a_pass_through_under_the_thinking_config_beside_a_known_name_is_refused() {
      InferenceRequest request =
          carrying(Map.of(THINKING + "thinkingBudget", "512", THINKING + "mode", "deep"));

      assertThatThrownBy(() -> GeminiRequests.toConfig(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'" + THINKING + "mode'")
          .hasMessageContaining("'" + THINKING + "thinkingBudget'");
    }

    @Test
    void a_bad_budget_is_refused_naming_the_property_and_the_value() {
      InferenceRequest request = carrying(Map.of(THINKING + "thinkingBudget", "lots"));

      assertThatThrownBy(() -> GeminiRequests.toConfig(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property '" + THINKING + "thinkingBudget' must be an integer, was 'lots'");
    }

    @Test
    void a_bad_include_thoughts_is_refused_naming_the_property_and_the_value() {
      InferenceRequest request = carrying(Map.of(THINKING + "includeThoughts", "maybe"));

      assertThatThrownBy(() -> GeminiRequests.toConfig(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'" + THINKING + "includeThoughts'")
          .hasMessageContaining("'maybe'");
    }
  }
}
