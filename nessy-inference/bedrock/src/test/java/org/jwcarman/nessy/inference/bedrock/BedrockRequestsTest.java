package org.jwcarman.nessy.inference.bedrock;

import static org.assertj.core.api.Assertions.assertThat;

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
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
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
        SYSTEM, context, tools, new InferenceOptions("us.anthropic.claude-haiku", 1024));
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

  private static String textOf(Message message) {
    return message.content().stream()
        .map(block -> block.text() == null ? "" : block.text())
        .reduce("", String::concat);
  }

  @Nested
  class TheConversation {

    @Test
    void an_observation_is_a_user_message_and_an_answer_an_assistant_one() {
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
    void
        a_summary_and_the_observation_after_it_share_one_user_message_because_roles_must_alternate() {
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
              BedrockInferenceProvider.PROVIDER_NAME,
              "{\"type\":\"reasoning\",\"text\":\"hmm\",\"signature\":\"sig\"}");
      Block.Provider unsigned =
          new Block.Provider(
              BedrockInferenceProvider.PROVIDER_NAME, "{\"type\":\"reasoning\",\"text\":\"hmm\"}");
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
              new InputSchema(
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
                      new Block.Provider(BedrockInferenceProvider.PROVIDER_NAME, redacted),
                      new Block.Provider(
                          BedrockInferenceProvider.PROVIDER_NAME, "{\"type\":\"other\"}"),
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
}
