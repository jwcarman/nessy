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

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.Memory;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.State;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Chapter;
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

  private static Exchange exchangeOf(
      Seq seq, List<Block.ActionRequestContent> request, List<ToolOutcome> outcomes) {
    Map<CallId, String> actions = new LinkedHashMap<>();
    for (Block.ActionRequestContent block : request) {
      if (block instanceof Block.ToolCall call) {
        actions.put(call.id(), "did " + call.name().value());
      }
    }
    Map<CallId, String> results = new LinkedHashMap<>();
    for (ToolOutcome outcome : outcomes) {
      if (outcome instanceof ToolOutcome.Succeeded done && actions.containsKey(done.callId())) {
        results.put(done.callId(), "returned for " + done.callId().value());
      }
    }
    return new Exchange(seq, request, outcomes, actions, results);
  }

  private static Chapter chapter(long from, long through) {
    return new Chapter(
        new AgentType("chat"), AgentId.random(), new TurnId(from), new TurnId(through));
  }

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
        new TurnResult.Answered(List.of(new Block.Text(answer))));
  }

  private static Turn open(long id, String question) {
    return new Turn(new TurnId(id), asked(id, question), List.of(), null);
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
              List.of(new Summary(chapter(1, 9), "they talked about lakes")),
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
      Turn failed = new Turn(new TurnId(1), asked(1, "one"), List.of(), new TurnResult.Failed());
      Turn refused = new Turn(new TurnId(3), asked(3, "two"), List.of(), new TurnResult.Refused());

      ConverseStreamRequest converse =
          BedrockRequests.toRequest(request(List.of(failed, refused, open(5, "three"))), MAPPER);

      assertThat(converse.messages())
          .extracting(BedrockRequestsTest::textOf)
          .containsExactly("one", "(The previous attempt to answer did not complete.)", "three");
    }
  }

  @Nested
  class PlacingTheStrata {

    private InferenceContext context(
        List<Turn> tail,
        List<Memory> memory,
        List<State> state,
        Turn active,
        List<Ambient> ambient) {
      return new InferenceContext(List.of(), tail, memory, state, active, ambient);
    }

    private ConverseStreamRequest render(InferenceContext context) {
      return BedrockRequests.toRequest(request(context, List.of()), MAPPER);
    }

    private List<String> texts(Message message) {
      return message.content().stream().map(ContentBlock::text).toList();
    }

    @Test
    void the_system_list_holds_only_the_instructions() {
      ConverseStreamRequest converse =
          render(
              context(
                  List.of(),
                  List.of(Memory.text("notes", "likes lakes")),
                  List.of(State.text("plan", "step one")),
                  open(1, "hi"),
                  List.of(Ambient.text("clock", "it is Tuesday"))));

      assertThat(converse.system())
          .extracting(block -> block.text())
          .containsExactly("you are a helpful assistant");
    }

    @Test
    void memory_and_state_lead_the_active_turns_first_user_message() {
      ConverseStreamRequest converse =
          render(
              context(
                  List.of(),
                  List.of(Memory.text("notes", "  likes lakes  "), Memory.text("episodes", "a")),
                  List.of(State.text("plan", "step one")),
                  open(1, "hi"),
                  List.of()));

      assertThat(converse.messages()).hasSize(1);
      Message message = converse.messages().getFirst();
      assertThat(message.role()).isEqualTo(ConversationRole.USER);
      assertThat(texts(message))
          .containsExactly(
              "<memory kind=\"notes\">\nlikes lakes\n</memory>",
              "<memory kind=\"episodes\">\na\n</memory>",
              "<state kind=\"plan\">\nstep one\n</state>",
              "hi");
    }

    @Test
    void memory_and_state_are_not_attached_to_a_turn_in_the_tail() {
      ConverseStreamRequest converse =
          render(
              context(
                  List.of(answered(1, "first", "reply")),
                  List.of(Memory.text("notes", "likes lakes")),
                  List.of(State.text("plan", "step one")),
                  open(3, "second"),
                  List.of()));

      assertThat(converse.messages()).hasSize(3);
      assertThat(texts(converse.messages().get(0))).containsExactly("first");
      assertThat(texts(converse.messages().get(1))).containsExactly("reply");
      assertThat(texts(converse.messages().get(2)))
          .containsExactly(
              "<memory kind=\"notes\">\nlikes lakes\n</memory>",
              "<state kind=\"plan\">\nstep one\n</state>",
              "second");
    }

    @Test
    void ambient_ends_the_request_after_the_active_turns_input() {
      ConverseStreamRequest converse =
          render(
              context(
                  List.of(),
                  List.of(),
                  List.of(),
                  open(1, "hi"),
                  List.of(Ambient.text("clock", " it is Tuesday "))));

      assertThat(converse.messages()).hasSize(1);
      assertThat(converse.messages().getFirst().role()).isEqualTo(ConversationRole.USER);
      assertThat(texts(converse.messages().getFirst()))
          .containsExactly("hi", "<clock>\nit is Tuesday\n</clock>");
    }

    @Test
    void ambient_ends_the_request_after_the_last_tool_results() {
      Exchange exchange =
          exchangeOf(
              new Seq(2),
              List.of(new Block.ToolCall("call_1", "depth", "{}")),
              List.of(
                  new ToolOutcome.Succeeded(
                      new CallId("call_1"), List.of(new Block.Text("230m")))));
      Turn active = new Turn(new TurnId(1), asked(1, "how deep?"), List.of(exchange), null);

      ConverseStreamRequest converse =
          render(
              context(
                  List.of(),
                  List.of(),
                  List.of(),
                  active,
                  List.of(Ambient.text("clock", "it is Tuesday"))));

      assertThat(converse.messages())
          .extracting(Message::role)
          .containsExactly(
              ConversationRole.USER, ConversationRole.ASSISTANT, ConversationRole.USER);
      List<ContentBlock> last = converse.messages().getLast().content();
      assertThat(last).hasSize(2);
      assertThat(last.getFirst().toolResult().toolUseId()).isEqualTo("call_1");
      assertThat(last.get(1).text()).isEqualTo("<clock>\nit is Tuesday\n</clock>");
    }

    @Test
    void ambient_after_an_assistant_message_is_a_user_message_of_its_own() {
      Turn answeredActive =
          new Turn(
              new TurnId(1),
              asked(1, "hi"),
              List.of(),
              new TurnResult.Answered(List.of(new Block.Text("hello"))));

      ConverseStreamRequest converse =
          render(
              context(
                  List.of(),
                  List.of(),
                  List.of(),
                  answeredActive,
                  List.of(Ambient.text("clock", "it is Tuesday"))));

      assertThat(converse.messages())
          .extracting(Message::role)
          .containsExactly(
              ConversationRole.USER, ConversationRole.ASSISTANT, ConversationRole.USER);
      assertThat(texts(converse.messages().getLast()))
          .containsExactly("<clock>\nit is Tuesday\n</clock>");
    }

    @Test
    void a_context_with_no_memory_state_or_ambient_renders_as_before() {
      List<Turn> turns = List.of(answered(1, "hi", "hello"), open(3, "bye"));

      ConverseStreamRequest converse =
          render(
              context(List.of(turns.getFirst()), List.of(), List.of(), turns.getLast(), List.of()));

      assertThat(converse.system()).hasSize(1);
      assertThat(converse.messages())
          .extracting(Message::role)
          .containsExactly(
              ConversationRole.USER, ConversationRole.ASSISTANT, ConversationRole.USER);
      assertThat(converse.messages().stream().map(this::texts).toList())
          .containsExactly(List.of("hi"), List.of("hello"), List.of("bye"));
    }

    @Test
    void blank_memory_state_and_ambient_are_left_out() {
      ConverseStreamRequest converse =
          render(
              context(
                  List.of(),
                  List.of(Memory.text("notes", "   ")),
                  List.of(State.text("plan", "\n")),
                  open(1, "hi"),
                  List.of(Ambient.text("clock", " "))));

      assertThat(converse.messages()).hasSize(1);
      assertThat(texts(converse.messages().getFirst())).containsExactly("hi");
    }
  }

  @Nested
  class AnExchange {

    private Turn withCall(List<ToolOutcome> outcomes, Block.ActionRequestContent... extra) {
      List<Block.ActionRequestContent> request = new java.util.ArrayList<>();
      request.add(new Block.Commentary("let me look"));
      request.add(new Block.ToolCall("call_1", "depth", "{\"lake\":\"ness\",\"metres\":true}"));
      request.addAll(List.of(extra));
      Exchange exchange = exchangeOf(new Seq(2), request, outcomes);
      return new Turn(new TurnId(1), asked(1, "how deep?"), List.of(exchange), null);
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
          exchangeOf(
              new Seq(2),
              List.of(new Block.Commentary("aloud"), new Block.ToolCall("c1", "lookup", "{}")),
              List.of());
      Turn turn = new Turn(new TurnId(1), asked(1, "go"), List.of(asking), null);

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
                      new Block.Text("done"))));

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
    void the_two_converse_names_land_in_the_typed_inference_config_beside_the_ceiling() {
      ConverseStreamRequest request =
          requestFor(
              Map.of(
                  "bedrock.inferenceConfig.temperature", "0.2",
                  "bedrock.inferenceConfig.topP", "0.9"));

      assertThat(request.inferenceConfig().temperature()).isEqualTo(0.2f);
      assertThat(request.inferenceConfig().topP()).isEqualTo(0.9f);
      assertThat(request.inferenceConfig().maxTokens()).isEqualTo(1024);
      assertThat(request.additionalModelRequestFields()).isNull();
    }

    @Test
    void without_properties_no_model_document_is_sent() {
      assertThat(requestFor(Map.of()).additionalModelRequestFields()).isNull();
    }

    /** Names that were once refused or passed through are simply unsupported now: ignored. */
    @ParameterizedTest
    @ValueSource(
        strings = {
          "modelId",
          "messages",
          "system",
          "toolConfig",
          "inferenceConfig",
          "inferenceConfig.maxTokens",
          "inferenceConfig.topK",
          "thinking.type",
          "thinking.budget_tokens",
          "top_k"
        })
    void an_unsupported_name_is_not_sent_and_the_request_is_as_if_it_were_not_given(String name) {
      ConverseStreamRequest with = requestFor(Map.of("bedrock." + name, "5"));

      assertThat(with.additionalModelRequestFields()).isNull();
      assertThat(with).isEqualTo(requestFor(Map.of()));
      assertThat(with.modelId()).isEqualTo("us.anthropic.claude-haiku");
      assertThat(with.inferenceConfig().maxTokens()).isEqualTo(1024);
      assertThat(with.inferenceConfig().temperature()).isNull();
    }

    @Test
    void an_unsupported_name_beside_a_supported_one_leaves_the_supported_one_in_force() {
      ConverseStreamRequest request =
          requestFor(
              Map.of(
                  "bedrock.inferenceConfig.maxTokens", "9",
                  "bedrock.inferenceConfig.temperature", "0.3"));

      assertThat(request.inferenceConfig().maxTokens()).isEqualTo(1024);
      assertThat(request.inferenceConfig().temperature()).isEqualTo(0.3f);
      assertThat(request.additionalModelRequestFields()).isNull();
    }

    @Test
    void reading_a_request_says_nothing_about_an_unsupported_name() {
      InferenceRequest request = carrying(Map.of("bedrock.thinking.type", "enabled"));

      List<ILoggingEvent> events =
          LogCapture.during(
              BedrockPropertyReader.class,
              () -> {
                BedrockRequests.toRequest(request, Map.of(), MAPPER);
                BedrockRequests.toRequest(request, Map.of(), MAPPER);
              });

      assertThat(events).isEmpty();
    }

    @Test
    void an_unsupported_name_is_warned_once_naming_it_and_what_is_supported() {
      List<ILoggingEvent> events =
          LogCapture.during(
              BedrockPropertyReader.class,
              () ->
                  BedrockPropertyReader.warnUnsupported(
                      Map.of(
                          "bedrock.thinking.type", "enabled",
                          "bedrock.inferenceConfig.topP", "0.5")));

      assertThat(LogCapture.warnings(events))
          .containsExactly(
              "NESSY INFERENCE: property 'bedrock.thinking.type' is not supported by bedrock and"
                  + " is ignored; supported: [bedrock.inferenceConfig.temperature,"
                  + " bedrock.inferenceConfig.topP]");
    }

    @Test
    void stop_sequences_are_an_unsupported_name_ignored_and_warned_once() {
      ConverseStreamRequest request =
          requestFor(Map.of("bedrock.inferenceConfig.stopSequences", "[\"END\"]"));

      List<ILoggingEvent> events =
          LogCapture.during(
              BedrockPropertyReader.class,
              () ->
                  BedrockPropertyReader.warnUnsupported(
                      Map.of("bedrock.inferenceConfig.stopSequences", "[\"END\"]")));

      assertThat(request.inferenceConfig().hasStopSequences()).isFalse();
      assertThat(request).isEqualTo(requestFor(Map.of()));
      assertThat(LogCapture.warnings(events))
          .containsExactly(
              "NESSY INFERENCE: property 'bedrock.inferenceConfig.stopSequences' is not supported"
                  + " by bedrock and is ignored; supported: [bedrock.inferenceConfig.temperature,"
                  + " bedrock.inferenceConfig.topP]");
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
    void typed_properties_set_in_code_reach_the_request_as_the_sdk_values() {
      Map<String, String> typed =
          Map.of(
              BedrockProperties.TEMPERATURE.name(), BedrockProperties.TEMPERATURE.format(0.25f),
              BedrockProperties.TOP_P.name(), BedrockProperties.TOP_P.format(0.5f));

      ConverseStreamRequest request = requestFor(typed);

      assertThat(request.inferenceConfig().temperature()).isEqualTo(0.25f);
      assertThat(request.inferenceConfig().topP()).isEqualTo(0.5f);
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
  }
}
