package org.jwcarman.nessy.inference.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import java.util.List;
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
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.spi.inference.InferenceContext;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.ToolOffer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The projection onto OpenAI's wire, with no network anywhere near it.
 *
 * <p>This is where the adapter's judgement lives -- what a refused turn looks like, where ambient
 * background goes, what a denied call is told to the model -- so it is worth asserting on the built
 * params rather than only on what a live call happens to accept.
 */
class OpenAiRequestsTest {

  private static final SystemPrompt SYSTEM = new SystemPrompt("you are a helpful assistant");
  private static final InferenceOptions OPTIONS = new InferenceOptions("gpt-4o", 1024);

  /** The schema parser this adapter is given; an application would hand over its own. */
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static InferenceRequest request(List<Turn> turns) {
    return new InferenceRequest(SYSTEM, InferenceContext.of(turns), List.of(), OPTIONS);
  }

  private static Observation asked(long seq, String text) {
    return new Observation(new Seq(seq), List.of(new Block.Text(text)));
  }

  /** A finished turn: a question and the answer it got. */
  private static Turn answered(long id, String question, String answer) {
    return new Turn(
        new TurnId(id),
        asked(id, question),
        List.of(),
        new TurnResult.Answered(List.of(new Block.Text(answer))),
        0);
  }

  /** The turn in flight: asked, not yet answered. */
  private static Turn open(long id, String question) {
    return new Turn(new TurnId(id), asked(id, question), List.of(), null, 0);
  }

  private static List<ChatCompletionMessageParam> messagesOf(List<Turn> turns) {
    return OpenAiRequests.toParams(request(turns), MAPPER).messages();
  }

  @Nested
  class TheSystemMessage {

    @Test
    void leads_the_conversation() {
      List<ChatCompletionMessageParam> messages = messagesOf(List.of(open(1, "hello")));

      assertThat(messages.getFirst().isSystem()).isTrue();
      assertThat(messages.getFirst().asSystem().content().asText())
          .isEqualTo("you are a helpful assistant");
    }

    /**
     * There is no blank case to cover any more: {@link SystemPrompt} refuses one at construction,
     * so an adapter cannot be handed an empty instruction and does not have to decide what to do
     * about it.
     */
    @Test
    void carries_ambient_background_in_labelled_sections() {
      InferenceRequest request =
          new InferenceRequest(
              SYSTEM,
              new InferenceContext(
                  List.of(open(1, "hello")),
                  List.of(
                      Ambient.text("notebook", "the deploy is frozen"),
                      Ambient.text("clock", "it is Tuesday"))),
              List.of(),
              OPTIONS);

      String system =
          OpenAiRequests.toParams(request, MAPPER)
              .messages()
              .getFirst()
              .asSystem()
              .content()
              .asText();

      assertThat(system)
          .as("the standing instruction first, then each section under its own label")
          .isEqualTo(
              """
              you are a helpful assistant

              <notebook>
              the deploy is frozen
              </notebook>

              <clock>
              it is Tuesday
              </clock>""");
    }

    /** A heading with nothing under it tells a model its notebook is empty, which is a claim. */
    @Test
    void says_nothing_at_all_when_there_is_no_background() {
      String system =
          messagesOf(List.of(open(1, "hello"))).getFirst().asSystem().content().asText();

      assertThat(system).doesNotContain("<");
    }
  }

  @Nested
  class TheModelAndItsCeiling {

    @Test
    void come_from_the_options_rather_than_from_the_adapter() {
      ChatCompletionCreateParams params =
          OpenAiRequests.toParams(request(List.of(open(1, "hi"))), MAPPER);

      assertThat(params.model().asString()).isEqualTo("gpt-4o");
      assertThat(params.maxCompletionTokens()).contains(1024L);
    }

    /** Zero is a real value to this API and would ask for an empty answer. */
    @Test
    void the_ceiling_is_omitted_entirely_when_none_was_asked_for() {
      InferenceRequest request =
          new InferenceRequest(
              SYSTEM,
              InferenceContext.of(List.of(open(1, "hi"))),
              List.of(),
              InferenceOptions.of("gpt-4o"));

      assertThat(OpenAiRequests.toParams(request, MAPPER).maxCompletionTokens()).isEmpty();
    }
  }

  @Nested
  class ATurn {

    @Test
    void becomes_a_user_message_and_the_assistant_answer_that_followed_it() {
      List<ChatCompletionMessageParam> messages =
          messagesOf(List.of(answered(1, "how deep is Loch Ness?", "1412 metres")));

      assertThat(messages).hasSize(3);
      assertThat(messages.get(1).asUser().content().asText()).isEqualTo("how deep is Loch Ness?");
      assertThat(messages.get(2).asAssistant().content().orElseThrow().asText())
          .isEqualTo("1412 metres");
    }

    @Test
    void still_in_flight_is_sent_with_no_answer_after_it() {
      List<ChatCompletionMessageParam> messages = messagesOf(List.of(open(1, "still thinking")));

      assertThat(messages).hasSize(2);
      assertThat(messages.get(1).isUser()).isTrue();
    }

    /**
     * Two user messages adjacent with nothing between them reads as the model having ignored the
     * first, so a turn that produced nothing has to be explained. On this wire a mid-conversation
     * system line is the natural way to do it.
     */
    @Test
    void that_failed_is_explained_rather_than_left_silent() {
      Turn failed =
          new Turn(
              new TurnId(1), asked(1, "what happened?"), List.of(), new TurnResult.Failed(), 0);

      List<ChatCompletionMessageParam> messages = messagesOf(List.of(failed, open(3, "again?")));

      assertThat(messages.get(2).asSystem().content().asText())
          .as("did not complete, rather than returned an error: it may never have run at all")
          .isEqualTo("The previous attempt to answer did not complete.");
    }

    /**
     * The refused observation is the thing that caused the refusal. Re-sending it keeps the
     * conversation refused for as long as it is still in the request.
     */
    @Test
    void that_was_refused_drops_its_question_and_says_so_in_its_place() {
      Turn refused =
          new Turn(
              new TurnId(1),
              asked(1, "something disallowed"),
              List.of(),
              new TurnResult.Refused(),
              0);

      List<ChatCompletionMessageParam> messages = messagesOf(List.of(refused, open(3, "again?")));

      assertThat(messages).hasSize(3);
      assertThat(messages.get(1).asSystem().content().asText())
          .isEqualTo(
              "A previous message was withdrawn from this conversation and is no longer available.");
      assertThat(messages.stream().filter(ChatCompletionMessageParam::isUser).toList())
          .extracting(message -> message.asUser().content().asText())
          .as("the withdrawn question is gone, not merely relabelled")
          .doesNotContain("something disallowed");
    }
  }

  @Nested
  class AnExchange {

    private static Turn withCalls(
        List<Block.ActionRequestContent> request, List<ToolOutcome> outcomes) {
      return new Turn(
          new TurnId(1),
          asked(1, "look it up"),
          List.of(new Exchange(new Seq(2), request, outcomes)),
          new TurnResult.Answered(List.of(new Block.Text("done"))),
          0);
    }

    @Test
    void becomes_an_assistant_message_of_calls_followed_by_one_result_each() {
      List<ChatCompletionMessageParam> messages =
          messagesOf(
              List.of(
                  withCalls(
                      List.of(
                          new Block.Commentary("Let me look."),
                          new Block.ToolCall(
                              new CallId("call_1"), new ToolName("lookup"), "{\"q\":\"a\"}"),
                          new Block.ToolCall(
                              new CallId("call_2"), new ToolName("lookup"), "{\"q\":\"b\"}")),
                      List.of(
                          new ToolOutcome.Succeeded(
                              new CallId("call_1"), List.of(new Block.Text("first"))),
                          new ToolOutcome.Succeeded(
                              new CallId("call_2"), List.of(new Block.Text("second")))))));

      var asking = messages.get(2).asAssistant();
      assertThat(asking.content().orElseThrow().asText())
          .as("prose beside the calls is part of what the assistant said")
          .isEqualTo("Let me look.");
      assertThat(asking.toolCalls().orElseThrow())
          .extracting(call -> call.asFunction().id())
          .as("in the order the model asked for them")
          .containsExactly("call_1", "call_2");

      assertThat(messages.get(3).asTool().toolCallId()).isEqualTo("call_1");
      assertThat(messages.get(3).asTool().content().asText()).isEqualTo("first");
      assertThat(messages.get(4).asTool().toolCallId()).isEqualTo("call_2");
    }

    /** This wire has no error flag on a tool message, so a failure has to be said in words. */
    @Test
    void reports_a_failed_call_in_the_only_field_this_wire_has() {
      List<ChatCompletionMessageParam> messages =
          messagesOf(
              List.of(
                  withCalls(
                      List.of(
                          new Block.ToolCall(new CallId("call_1"), new ToolName("lookup"), "{}")),
                      List.of(
                          new ToolOutcome.Failed(new CallId("call_1"), "the service was down")))));

      assertThat(messages.get(3).asTool().content().asText())
          .isEqualTo("Error: the service was down");
    }

    /**
     * A denial is not a failure, and telling the model it was one invites a retry of something it
     * was refused. The wire cannot express the difference, so the words do.
     */
    @Test
    void tells_the_model_a_denied_call_was_not_permitted_rather_than_that_it_broke() {
      List<ChatCompletionMessageParam> messages =
          messagesOf(
              List.of(
                  withCalls(
                      List.of(
                          new Block.ToolCall(new CallId("call_1"), new ToolName("lookup"), "{}")),
                      List.of(new ToolOutcome.Denied(new CallId("call_1"), "out of hours")))));

      assertThat(messages.get(3).asTool().content().asText())
          .isEqualTo("This call was not run because it was not permitted: out of hours");
    }

    /** An assistant message whose whole point is its calls carries no content at all. */
    @Test
    void with_nothing_said_alongside_the_calls_sends_no_content() {
      List<ChatCompletionMessageParam> messages =
          messagesOf(
              List.of(
                  withCalls(
                      List.of(
                          new Block.ToolCall(new CallId("call_1"), new ToolName("lookup"), "{}")),
                      List.of(
                          new ToolOutcome.Succeeded(
                              new CallId("call_1"), List.of(new Block.Text("ok")))))));

      assertThat(messages.get(2).asAssistant().content()).isEmpty();
    }
  }

  @Nested
  class AProviderBlock {

    /**
     * Another vendor's opaque state is dropped rather than translated. Handing Anthropic's
     * reasoning bytes to OpenAI would at best be ignored and at worst rejected.
     */
    @Test
    void is_dropped_leaving_its_siblings_in_order() {
      Turn turn =
          new Turn(
              new TurnId(1),
              asked(1, "hello"),
              List.of(),
              new TurnResult.Answered(
                  List.of(
                      new Block.Provider("anthropic", "{\"type\":\"thinking\"}"),
                      new Block.Text("the answer"))),
              0);

      assertThat(messagesOf(List.of(turn)).get(2).asAssistant().content().orElseThrow().asText())
          .isEqualTo("the answer");
    }
  }

  @Nested
  class ABoundTool {

    @Test
    void becomes_a_function_tool_carrying_its_schema_as_a_document() {
      InferenceRequest request =
          new InferenceRequest(
              SYSTEM,
              InferenceContext.of(List.of(open(1, "hi"))),
              List.of(
                  new ToolOffer(
                      new ToolName("lookup"),
                      "looks a thing up",
                      new InputSchema(
                          "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}"))),
              OPTIONS);

      var tools = OpenAiRequests.toParams(request, MAPPER).tools().orElseThrow();

      assertThat(tools).hasSize(1);
      var function = tools.getFirst().asFunction().function();
      assertThat(function.name()).isEqualTo("lookup");
      assertThat(function.description()).contains("looks a thing up");
      assertThat(function.parameters().orElseThrow()._additionalProperties())
          .as("written through as a document, not as a string containing one")
          .containsKey("properties");
    }

    /** A model offered nothing is asked exactly the way it was asked before tools existed. */
    @Test
    void is_absent_entirely_when_none_were_bound() {
      assertThat(OpenAiRequests.toParams(request(List.of(open(1, "hi"))), MAPPER).tools())
          .isEmpty();
    }
  }
}
