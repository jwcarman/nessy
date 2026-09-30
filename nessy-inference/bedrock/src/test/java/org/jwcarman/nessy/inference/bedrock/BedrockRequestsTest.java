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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
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
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultStatus;
import tools.jackson.databind.json.JsonMapper;

/** The projection onto Bedrock's Converse wire, with no network anywhere near it. */
@DisplayName("Bedrock requests")
class BedrockRequestsTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final SystemPrompt SYSTEM = new SystemPrompt("you are a helpful assistant");

  private static InferenceRequest request(List<Turn> turns) {
    return request(new InferenceContext(turns, List.of()), List.of());
  }

  private static InferenceRequest request(InferenceContext context, List<ToolOffer> tools) {
    return new InferenceRequest(
        SYSTEM,
        context,
        Toolset.of(tools),
        new InferenceOptions("us.anthropic.claude-haiku", 1024));
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

  private static String textOf(Message message) {
    return message.content().stream()
        .map(block -> block.text() == null ? "" : block.text())
        .reduce("", String::concat);
  }

  @Nested
  class TheConversation {

    @Test
    void an_input_is_a_user_message_and_an_answer_an_assistant_one() {
      ConverseStreamRequest converse =
          BedrockRequests.toRequest(
              request(List.of(answered(1, "hi", "hello"), open(3, "bye"))), MAPPER);

      assertThat(converse.modelId()).isEqualTo("us.anthropic.claude-haiku");
      assertThat(converse.inferenceConfig().maxTokens()).isEqualTo(1024);
      assertThat(converse.messages())
          .extracting(Message::role)
          .containsExactly(
              ConversationRole.USER, ConversationRole.ASSISTANT, ConversationRole.USER);
      assertThat(converse.messages())
          .extracting(BedrockRequestsTest::textOf)
          .containsExactly("hi", "hello", "bye");
    }

    @Test
    void a_summary_and_the_input_after_it_share_one_user_message_because_roles_must_alternate() {
      InferenceContext context =
          new InferenceContext(
              List.of(Summary.text(new TurnId(1), new TurnId(9), "they talked about lakes")),
              List.of(open(11, "and monsters?")),
              List.of());

      ConverseStreamRequest converse =
          BedrockRequests.toRequest(request(context, List.of()), MAPPER);

      assertThat(converse.messages()).hasSize(1);
      Message merged = converse.messages().getFirst();
      assertThat(merged.role()).isEqualTo(ConversationRole.USER);
      assertThat(merged.content()).hasSize(2);
      assertThat(merged.content().getFirst().text())
          .startsWith("<summary from=\"1\" through=\"9\">");
      assertThat(merged.content().get(1).text()).isEqualTo("and monsters?");
    }

    @Test
    void a_failed_turn_is_answered_for_and_a_refused_one_is_left_out() {
      Turn failed = new Turn(new TurnId(1), asked(1, "one"), List.of(), new TurnResult.Failed(), 0);
      Turn refused =
          new Turn(new TurnId(3), asked(3, "two"), List.of(), new TurnResult.Refused(), 0);

      ConverseStreamRequest converse =
          BedrockRequests.toRequest(request(List.of(failed, refused, open(5, "three"))), MAPPER);

      assertThat(converse.messages())
          .extracting(BedrockRequestsTest::textOf)
          .containsExactly("one", "(The previous attempt to answer did not complete.)", "three");
    }

    @Test
    void the_prompt_and_the_ambient_are_the_system_field() {
      InferenceContext context =
          new InferenceContext(
              List.of(open(1, "hi")), List.of(Ambient.text("clock", "it is Tuesday")));

      ConverseStreamRequest converse =
          BedrockRequests.toRequest(request(context, List.of()), MAPPER);

      assertThat(converse.system())
          .extracting(block -> block.text())
          .containsExactly("you are a helpful assistant", "<clock>\nit is Tuesday\n</clock>");
    }
  }

  @Nested
  class AnExchange {

    private Turn withCall(List<ToolOutcome> outcomes, Block.ActionRequestContent... extra) {
      List<Block.ActionRequestContent> request = new java.util.ArrayList<>();
      request.add(new Block.Commentary("let me look"));
      request.add(new Block.ToolCall("call_1", "depth", "{\"lake\":\"ness\",\"metres\":true}"));
      request.addAll(List.of(extra));
      Exchange exchange = new Exchange(new Seq(2), request, outcomes);
      return new Turn(new TurnId(1), asked(1, "how deep?"), List.of(exchange), null, 0);
    }

    @Test
    void the_call_goes_out_as_tool_use_and_the_result_comes_back_quoting_it() {
      Turn turn =
          withCall(
              List.of(
                  new ToolOutcome.Succeeded(
                      new CallId("call_1"), List.of(new Block.Text("230m")))));

      ConverseStreamRequest converse = BedrockRequests.toRequest(request(List.of(turn)), MAPPER);

      assertThat(converse.messages())
          .extracting(Message::role)
          .containsExactly(
              ConversationRole.USER, ConversationRole.ASSISTANT, ConversationRole.USER);
      ContentBlock use = converse.messages().get(1).content().get(1);
      assertThat(use.toolUse().toolUseId()).isEqualTo("call_1");
      assertThat(use.toolUse().name()).isEqualTo("depth");
      assertThat(use.toolUse().input().unwrap()).isEqualTo(Map.of("lake", "ness", "metres", true));
      ContentBlock result = converse.messages().get(2).content().getFirst();
      assertThat(result.toolResult().toolUseId()).isEqualTo("call_1");
      assertThat(result.toolResult().status()).isEqualTo(ToolResultStatus.SUCCESS);
      assertThat(result.toolResult().content().getFirst().text()).isEqualTo("230m");
    }

    @Test
    void a_failure_and_a_denial_are_error_results() {
      Turn turn =
          withCall(
              List.of(
                  new ToolOutcome.Failed(new CallId("call_1"), "boom"),
                  new ToolOutcome.Denied(new CallId("call_2"), "not today")),
              new Block.ToolCall("call_2", "prune", "{}"));

      ConverseStreamRequest converse = BedrockRequests.toRequest(request(List.of(turn)), MAPPER);

      List<ContentBlock> results = converse.messages().get(2).content();
      assertThat(results.get(0).toolResult().status()).isEqualTo(ToolResultStatus.ERROR);
      assertThat(results.get(0).toolResult().content().getFirst().text()).isEqualTo("boom");
      assertThat(results.get(1).toolResult().content().getFirst().text())
          .isEqualTo("This call was not run because it was not permitted: not today");
    }

    @Test
    void signed_reasoning_this_vendor_issued_goes_back_and_another_vendors_does_not() {
      Block.Provider ours =
          new Block.Provider(
              BedrockInferenceProvider.VENDOR,
              "{\"type\":\"reasoning\",\"text\":\"hmm\",\"signature\":\"sig\"}");
      Block.Provider unsigned =
          new Block.Provider(
              BedrockInferenceProvider.VENDOR, "{\"type\":\"reasoning\",\"text\":\"hmm\"}");
      Block.Provider theirs = new Block.Provider("anthropic", "{\"type\":\"thinking\"}");
      Turn turn =
          withCall(
              List.of(
                  new ToolOutcome.Succeeded(new CallId("call_1"), List.of(new Block.Text("ok")))),
              ours,
              unsigned,
              theirs);

      ConverseStreamRequest converse = BedrockRequests.toRequest(request(List.of(turn)), MAPPER);

      List<ContentBlock> asking = converse.messages().get(1).content();
      assertThat(asking).hasSize(3);
      assertThat(asking.get(2).reasoningContent().reasoningText().text()).isEqualTo("hmm");
      assertThat(asking.get(2).reasoningContent().reasoningText().signature()).isEqualTo("sig");
    }
  }

  @Nested
  class Tools {

    @Test
    void a_tool_is_a_tool_spec_with_its_schema_as_a_document() {
      ToolOffer offer =
          new ToolOffer(
              new ToolName("depth"),
              "how deep a lake is",
              new JsonSchema(
                  "{\"type\":\"object\",\"properties\":{\"lake\":{\"type\":\"string\"},\"n\":{\"type\":\"integer\",\"minimum\":0}},\"required\":[\"lake\"]}"));

      ConverseStreamRequest converse =
          BedrockRequests.toRequest(
              request(new InferenceContext(List.of(open(1, "hi")), List.of()), List.of(offer)),
              MAPPER);

      var spec = converse.toolConfig().tools().getFirst().toolSpec();
      assertThat(spec.name()).isEqualTo("depth");
      assertThat(spec.description()).isEqualTo("how deep a lake is");
      Map<?, ?> schema = (Map<?, ?>) spec.inputSchema().json().unwrap();
      assertThat(schema.entrySet())
          .anyMatch(e -> "required".equals(e.getKey()) && List.of("lake").equals(e.getValue()));
      assertThat(((Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("n")).get("minimum"))
          .asString()
          .isEqualTo("0");
    }
  }

  @Nested
  class TheEdges {

    @Test
    void a_call_still_awaiting_its_results_is_sent_alone() {
      Exchange asking =
          new Exchange(
              new Seq(2),
              List.of(new Block.Commentary("aloud"), new Block.ToolCall("c1", "lookup", "{}")),
              List.of());
      Turn turn = new Turn(new TurnId(1), asked(1, "go"), List.of(asking), null, 0);

      ConverseStreamRequest converse = BedrockRequests.toRequest(request(List.of(turn)), MAPPER);

      assertThat(converse.messages()).hasSize(2);
      assertThat(converse.messages().get(0).content()).hasSize(1);
      assertThat(converse.messages().get(1).content()).hasSize(2);
    }

    @Test
    void redacted_reasoning_goes_back_as_bytes_and_an_unknown_kind_is_dropped() {
      String redacted =
          "{\"type\":\"redacted\",\"data\":\""
              + java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2, 3})
              + "\"}";
      Turn turn =
          new Turn(
              new TurnId(1),
              asked(1, "go"),
              List.of(),
              new TurnResult.Answered(
                  List.of(
                      new Block.Provider(BedrockInferenceProvider.VENDOR, redacted),
                      new Block.Provider(BedrockInferenceProvider.VENDOR, "{\"type\":\"other\"}"),
                      new Block.Text("done"))),
              0);

      ConverseStreamRequest converse = BedrockRequests.toRequest(request(List.of(turn)), MAPPER);

      List<ContentBlock> answer = converse.messages().get(1).content();
      assertThat(answer).hasSize(2);
      assertThat(answer.getFirst().reasoningContent().redactedContent().asByteArray())
          .containsExactly(1, 2, 3);
    }

    @Test
    void a_document_carries_every_json_shape() {
      var document =
          BedrockRequests.document(
              Map.of("s", "x", "n", 1.5, "b", true, "l", List.of(1, "two"), "o", Map.of()));
      Map<?, ?> back = (Map<?, ?>) document.unwrap();

      assertThat(back.keySet().stream().map(String::valueOf))
          .containsExactlyInAnyOrder("s", "n", "b", "l", "o");
      assertThat(BedrockRequests.document(null).isNull()).isTrue();
    }

    @Test
    void the_text_of_a_summary_includes_commentary_and_skips_state() {
      assertThat(
              BedrockRequests.text(
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

    private static ToolConfiguration choosing(ToolChoice choice) {
      return BedrockRequests.toRequest(
              new InferenceRequest(
                  SYSTEM,
                  new InferenceContext(List.of(open(1, "hi")), List.of()),
                  new Toolset(List.of(offer()), choice),
                  new InferenceOptions("us.anthropic.claude-haiku", 1024)),
              MAPPER)
          .toolConfig();
    }

    private static ToolOffer offer() {
      return new ToolOffer(
          new ToolName("lookup"),
          "looks a thing up",
          new JsonSchema("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}"));
    }

    @Test
    void by_default_nothing_is_said_about_choosing() {
      assertThat(choosing(ToolChoice.auto()).toolChoice()).isNull();
    }

    @Test
    void requiring_some_tool_is_sent_as_any() {
      assertThat(choosing(new ToolChoice.Any()).toolChoice().any()).isNotNull();
    }

    @Test
    void requiring_one_tool_names_it() {
      assertThat(choosing(new ToolChoice.Named(new ToolName("lookup"))).toolChoice().tool().name())
          .isEqualTo("lookup");
    }

    /**
     * Converse has auto, any and a named tool, and no way to say "not this turn". Refused rather
     * than sent as something weaker: a caller that banned tools and got one anyway is worse off
     * than one told this backend cannot do it.
     */
    @Test
    void a_ban_is_refused_because_this_wire_cannot_say_it() {
      ToolChoice ban = new ToolChoice.None();

      assertThatThrownBy(() -> choosing(ban))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cannot forbid tool use");
    }
  }

  @Nested
  class TheVendorProperties {

    private static InferenceRequest carrying(Map<String, String> agentType) {
      return new InferenceRequest(
          SYSTEM,
          new InferenceContext(List.of(open(1, "hi")), List.of()),
          Toolset.none(),
          new InferenceOptions("us.anthropic.claude-haiku", 1024, agentType));
    }

    private static ConverseStreamRequest requestFor(Map<String, String> agentType) {
      return BedrockRequests.toRequest(carrying(agentType), Map.of(), MAPPER);
    }

    @Test
    void the_three_converse_names_land_in_the_typed_inference_config_beside_the_ceiling() {
      ConverseStreamRequest request =
          requestFor(
              Map.of(
                  "bedrock.inferenceConfig.temperature", "0.2",
                  "bedrock.inferenceConfig.topP", "0.9",
                  "bedrock.inferenceConfig.stopSequences", "[\"END\"]"));

      assertThat(request.inferenceConfig().temperature()).isEqualTo(0.2f);
      assertThat(request.inferenceConfig().topP()).isEqualTo(0.9f);
      assertThat(request.inferenceConfig().stopSequences()).containsExactly("END");
      assertThat(request.inferenceConfig().maxTokens()).isEqualTo(1024);
      assertThat(request.additionalModelRequestFields()).isNull();
    }

    /** Claude's extended thinking on Bedrock is two properties and no code (§9e). */
    @Test
    void every_other_name_goes_into_the_model_s_own_document_nested_by_path() {
      ConverseStreamRequest request =
          requestFor(
              Map.of("bedrock.thinking.type", "enabled", "bedrock.thinking.budget_tokens", "4096"));

      Document thinking = request.additionalModelRequestFields().asMap().get("thinking");
      assertThat(thinking.asMap().get("type").asString()).isEqualTo("enabled");
      assertThat(thinking.asMap().get("budget_tokens").asNumber().intValue()).isEqualTo(4096);
      assertThat(request.inferenceConfig().temperature()).isNull();
    }

    @Test
    void without_properties_no_model_document_is_sent() {
      assertThat(requestFor(Map.of()).additionalModelRequestFields()).isNull();
    }

    @Test
    void another_prefix_is_not_sent() {
      assertThat(requestFor(Map.of("anthropic.top_k", "5")).additionalModelRequestFields())
          .isNull();
    }

    @Test
    void an_agent_type_entry_overrides_the_same_name_given_to_the_provider() {
      ConverseStreamRequest request =
          BedrockRequests.toRequest(
              carrying(Map.of("bedrock.inferenceConfig.temperature", "0.1")),
              Map.of("bedrock.inferenceConfig.temperature", "0.9"),
              MAPPER);

      assertThat(request.inferenceConfig().temperature()).isEqualTo(0.1f);
    }

    @Test
    void a_provider_entry_reaches_the_request_when_the_agent_type_is_silent() {
      ConverseStreamRequest request =
          BedrockRequests.toRequest(
              carrying(Map.of()), Map.of("bedrock.thinking.type", "enabled"), MAPPER);

      assertThat(
              request
                  .additionalModelRequestFields()
                  .asMap()
                  .get("thinking")
                  .asMap()
                  .get("type")
                  .asString())
          .isEqualTo("enabled");
    }

    @Test
    void the_model_is_refused_under_its_converse_spelling() {
      InferenceRequest request = carrying(Map.of("bedrock.modelId", "x"));

      assertThatThrownBy(() -> BedrockRequests.toRequest(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'bedrock.modelId'")
          .hasMessageContaining("InferenceConfig.model");
    }

    @Test
    void the_ceiling_is_refused_under_its_converse_spelling() {
      InferenceRequest request = carrying(Map.of("bedrock.inferenceConfig.maxTokens", "9"));

      assertThatThrownBy(() -> BedrockRequests.toRequest(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("InferenceConfig.maxTokens");
    }

    @Test
    void
        an_inference_config_field_that_is_not_a_known_name_is_refused_pointing_at_the_alternative() {
      InferenceRequest request = carrying(Map.of("bedrock.inferenceConfig.topK", "5"));

      assertThatThrownBy(() -> BedrockRequests.toRequest(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'bedrock.inferenceConfig.topK'")
          .hasMessageContaining("inferenceConfig.temperature")
          .hasMessageContaining("bedrock.<field>");
    }

    @ParameterizedTest
    @CsvSource({
      "modelId,InferenceConfig.model",
      "messages,conversation",
      "system,system prompt",
      "toolConfig,tools the harness binds",
      "inferenceConfig.maxTokens,InferenceConfig.maxTokens"
    })
    void every_name_the_adapter_decides_is_refused_naming_what_decides_it(
        String name, String decidedBy) {
      InferenceRequest request = carrying(Map.of("bedrock." + name, "x"));

      assertThatThrownBy(() -> BedrockRequests.toRequest(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'bedrock." + name + "'")
          .hasMessageContaining(decidedBy);
    }

    @Test
    void a_temperature_that_is_not_a_number_is_refused_naming_the_value() {
      InferenceRequest request = carrying(Map.of("bedrock.inferenceConfig.temperature", "hot"));

      assertThatThrownBy(() -> BedrockRequests.toRequest(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property 'bedrock.inferenceConfig.temperature' must be a number, was 'hot'");
    }

    @Test
    void a_top_p_that_is_not_a_number_is_refused_naming_the_value() {
      InferenceRequest request = carrying(Map.of("bedrock.inferenceConfig.topP", "\"high\""));

      assertThatThrownBy(() -> BedrockRequests.toRequest(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'bedrock.inferenceConfig.topP' must be a number");
    }

    @Test
    void stop_sequences_that_are_not_an_array_of_strings_are_refused() {
      InferenceRequest request =
          carrying(Map.of("bedrock.inferenceConfig.stopSequences", "[1, 2]"));

      assertThatThrownBy(() -> BedrockRequests.toRequest(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must be a JSON array of strings");
    }
  }
}
