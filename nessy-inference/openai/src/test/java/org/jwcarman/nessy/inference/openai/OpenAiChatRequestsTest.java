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
package org.jwcarman.nessy.inference.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.openai.core.ObjectMappers;
import com.openai.models.FunctionDefinition;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.ToolChoice;
import org.jwcarman.nessy.inference.ToolOffer;
import org.jwcarman.nessy.inference.Toolset;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The projection onto OpenAI's wire, with no network anywhere near it.
 *
 * <p>This is where the adapter's judgement lives -- what a refused turn looks like, where ambient
 * background goes, what a denied call is told to the model -- so it is worth asserting on the built
 * params rather than only on what a live call happens to accept.
 */
class OpenAiChatRequestsTest {

  private static final SystemPrompt SYSTEM = new SystemPrompt("you are a helpful assistant");
  private static final InferenceOptions OPTIONS = new InferenceOptions("gpt-4o", 1024);

  /** The schema parser this adapter is given; an application would hand over its own. */
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static InferenceRequest request(List<Turn> turns) {
    return new InferenceRequest(SYSTEM, InferenceContext.of(turns), Toolset.none(), OPTIONS);
  }

  private static Input asked(long seq, String text) {
    return new Input(new Seq(seq), List.of(new Block.Text(text)));
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
    return OpenAiChatRequests.toParams(request(turns), MAPPER).messages();
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
              Toolset.none(),
              OPTIONS);

      String system =
          OpenAiChatRequests.toParams(request, MAPPER)
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
          OpenAiChatRequests.toParams(request(List.of(open(1, "hi"))), MAPPER);

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
              Toolset.none(),
              InferenceOptions.of("gpt-4o"));

      assertThat(OpenAiChatRequests.toParams(request, MAPPER).maxCompletionTokens()).isEmpty();
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
     * The refused input is the thing that caused the refusal. Re-sending it keeps the conversation
     * refused for as long as it is still in the request.
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
              Toolset.of(
                  List.of(
                      new ToolOffer(
                          new ToolName("lookup"),
                          "looks a thing up",
                          new JsonSchema(
                              "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}")))),
              OPTIONS);

      var tools = OpenAiChatRequests.toParams(request, MAPPER).tools().orElseThrow();

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
      assertThat(OpenAiChatRequests.toParams(request(List.of(open(1, "hi"))), MAPPER).tools())
          .isEmpty();
    }
  }

  @Test
  void usage_is_asked_for_on_the_stream() {
    ChatCompletionCreateParams params =
        OpenAiChatRequests.toParams(
            new InferenceRequest(
                new SystemPrompt("s"),
                InferenceContext.of(List.of()),
                Toolset.none(),
                InferenceOptions.of("m")),
            MAPPER);

    assertThat(params.streamOptions().flatMap(o -> o.includeUsage())).contains(true);
  }

  /** Whether the model may reach for what it was offered. */
  @Nested
  class ChoosingATool {

    private static ChatCompletionCreateParams choosing(ToolChoice choice) {
      return OpenAiChatRequests.toParams(
          new InferenceRequest(
              SYSTEM,
              InferenceContext.of(List.of(open(1, "hi"))),
              new Toolset(
                  List.of(
                      new ToolOffer(
                          new ToolName("lookup"),
                          "looks a thing up",
                          new JsonSchema(
                              "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}"))),
                  choice),
              OPTIONS),
          MAPPER);
    }

    /** An absent field already means auto, and several compatible servers prefer it absent. */
    @Test
    void by_default_nothing_is_said_about_choosing() {
      assertThat(choosing(ToolChoice.auto()).toolChoice()).isEmpty();
    }

    @Test
    void a_ban_is_sent_as_none() {
      assertThat(choosing(new ToolChoice.None()).toolChoice().orElseThrow().auto())
          .contains(ChatCompletionToolChoiceOption.Auto.NONE);
    }

    @Test
    void requiring_some_tool_is_sent_as_required() {
      assertThat(choosing(new ToolChoice.Any()).toolChoice().orElseThrow().auto())
          .contains(ChatCompletionToolChoiceOption.Auto.REQUIRED);
    }

    @Test
    void requiring_one_tool_names_it() {
      assertThat(
              choosing(new ToolChoice.Named(new ToolName("lookup")))
                  .toolChoice()
                  .orElseThrow()
                  .namedToolChoice()
                  .orElseThrow()
                  .function()
                  .name())
          .isEqualTo("lookup");
    }
  }

  /** What the SDK would put on the wire for {@code value}, read back as plain maps and lists. */
  private static Map<String, Object> sent(Object value) {
    try {
      return MAPPER.readValue(
          ObjectMappers.jsonMapper().writeValueAsString(value), new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Nested
  class TheVendorProperties {

    private static InferenceRequest carrying(Map<String, String> properties) {
      return new InferenceRequest(
          SYSTEM,
          InferenceContext.of(List.of(open(1, "hi"))),
          Toolset.none(),
          new InferenceOptions("gpt-4o", 1024, properties));
    }

    private static ChatCompletionCreateParams paramsFor(Map<String, String> agentType) {
      return OpenAiChatRequests.toParams(carrying(agentType), MAPPER);
    }

    @Test
    void a_reasoning_effort_lands_in_the_flat_reasoning_effort_field() {
      ChatCompletionCreateParams params = paramsFor(Map.of("openai.reasoning.effort", "high"));

      assertThat(params.reasoningEffort().map(ReasoningEffort::asString)).contains("high");
      assertThat(params._additionalBodyProperties()).isEmpty();
    }

    /** The vocabulary is the vendor's: a level Nessy has never heard of is sent as written. */
    @Test
    void an_effort_level_nessy_does_not_know_is_sent_as_written() {
      ChatCompletionCreateParams params = paramsFor(Map.of("openai.reasoning.effort", "ultra"));

      assertThat(params.reasoningEffort().map(ReasoningEffort::asString)).contains("ultra");
    }

    @Test
    void a_service_tier_lands_in_its_typed_field() {
      ChatCompletionCreateParams params = paramsFor(Map.of("openai.service_tier", "flex"));

      assertThat(params.serviceTier().map(ChatCompletionCreateParams.ServiceTier::asString))
          .contains("flex");
    }

    @Test
    void another_prefix_is_not_sent() {
      ChatCompletionCreateParams params = paramsFor(Map.of("anthropic.top_k", "5"));

      assertThat(params._additionalBodyProperties()).isEmpty();
      assertThat(params.reasoningEffort()).isEmpty();
    }

    @Test
    void an_agent_type_entry_overrides_the_same_name_given_to_the_provider() {
      ChatCompletionCreateParams params =
          OpenAiChatRequests.toParams(
              carrying(Map.of("openai.reasoning.effort", "high")),
              Map.of("openai.reasoning.effort", "low"),
              MAPPER);

      assertThat(params.reasoningEffort().map(ReasoningEffort::asString)).contains("high");
    }

    /** Names that were once refused as clashes are simply unsupported now: ignored, not sent. */
    @ParameterizedTest
    @ValueSource(
        strings = {
          "model",
          "messages",
          "max_completion_tokens",
          "max_tokens",
          "tools",
          "tool_choice",
          "response_format",
          "stream",
          "stream_options",
          "stream_options.include_obfuscation",
          "store",
          "seed",
          "temperature",
          "metadata.team",
          "reasoning_effort"
        })
    void an_unsupported_name_is_not_sent_and_the_request_is_as_if_it_were_not_given(String name) {
      ChatCompletionCreateParams with = paramsFor(Map.of("openai." + name, "1"));

      assertThat(with._additionalBodyProperties()).isEmpty();
      assertThat(with).isEqualTo(paramsFor(Map.of()));
      assertThat(with.model().asString()).isEqualTo("gpt-4o");
      assertThat(with.maxCompletionTokens()).contains(1024L);
    }

    @Test
    void an_unsupported_name_beside_a_supported_one_leaves_the_supported_one_in_force() {
      ChatCompletionCreateParams params =
          paramsFor(Map.of("openai.max_completion_tokens", "10", "openai.reasoning.effort", "low"));

      assertThat(params.maxCompletionTokens()).contains(1024L);
      assertThat(params.reasoningEffort().map(ReasoningEffort::asString)).contains("low");
    }

    @Test
    void reading_a_request_says_nothing_about_an_unsupported_name() {
      InferenceRequest request = carrying(Map.of("openai.seed", "1"));

      List<ILoggingEvent> events =
          LogCapture.during(
              OpenAiProperties.class,
              () -> {
                OpenAiChatRequests.toParams(request, MAPPER);
                OpenAiChatRequests.toParams(request, MAPPER);
              });

      assertThat(events).isEmpty();
    }

    @Test
    void an_unsupported_name_is_warned_once_naming_it_and_what_is_supported() {
      List<ILoggingEvent> events =
          LogCapture.during(
              OpenAiProperties.class,
              () ->
                  OpenAiProperties.warnUnsupported(
                      Map.of("openai.seed", "1", "openai.tools.strict", "true")));

      assertThat(LogCapture.warnings(events))
          .containsExactly(
              "NESSY INFERENCE: property 'openai.seed' is not supported by openai and is ignored; supported:"
                  + " [openai.reasoning.effort, openai.reasoning.summary, openai.service_tier,"
                  + " openai.tools.strict]");
    }

    @Test
    void the_strict_name_under_the_tools_the_harness_binds_is_still_accepted() {
      ChatCompletionCreateParams params = paramsFor(Map.of("openai.tools.strict", "true"));

      assertThat(params.tools()).isEmpty();
      assertThat(params._additionalBodyProperties()).isEmpty();
    }

    @Test
    void a_reasoning_summary_is_refused_because_this_wire_cannot_carry_one() {
      InferenceRequest request = carrying(Map.of("openai.reasoning.summary", "auto"));

      assertThatThrownBy(() -> OpenAiChatRequests.toParams(request, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'openai.reasoning.summary'")
          .hasMessageContaining("openai-responses");
    }

    @Test
    void a_non_boolean_strict_is_refused_naming_the_value() {
      InferenceRequest request = carrying(Map.of("openai.tools.strict", "yes"));

      assertThatThrownBy(() -> OpenAiChatRequests.toParams(request, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property 'openai.tools.strict' must be true or false, was 'yes'");
    }

    @Test
    void another_prefix_is_named_at_debug_and_no_louder() {
      Logger logger = (Logger) LoggerFactory.getLogger(OpenAiProperties.class);
      Level before = logger.getLevel();
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
      logger.setLevel(Level.DEBUG);
      try {
        OpenAiProperties.logIgnored(Map.of("anthropic.top_k", "5", "openai.seed", "1"));
      } finally {
        logger.detachAppender(appender);
        logger.setLevel(before);
      }

      assertThat(appender.list)
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage())
                    .contains("anthropic.top_k")
                    .doesNotContain("openai.seed");
              });
    }

    @Test
    void an_explicit_strict_false_sends_no_strict_field() {
      InferenceRequest request =
          new InferenceRequest(
              SYSTEM,
              InferenceContext.of(List.of(open(1, "hi"))),
              Toolset.of(
                  List.of(
                      new ToolOffer(
                          new ToolName("lookup"),
                          "does lookup",
                          new JsonSchema("{\"type\":\"object\",\"properties\":{}}")))),
              new InferenceOptions("gpt-4o", 1024, Map.of("openai.tools.strict", "false")));

      FunctionDefinition function =
          OpenAiChatRequests.toParams(request, MAPPER).tools().orElseThrow().stream()
              .map(tool -> tool.asFunction().function())
              .findFirst()
              .orElseThrow();

      assertThat(function.strict()).isEmpty();
    }

    @Test
    void a_name_with_no_prefix_is_refused() {
      InferenceRequest request = carrying(Map.of("temperature", "0.2"));

      assertThatThrownBy(() -> OpenAiChatRequests.toParams(request, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'temperature' has no prefix");
    }
  }

  /** Spec §10: the Responses record's strict rewrite, on this wire behind openai.tools.strict. */
  @Nested
  class StrictTools {

    private static final String LOOKUP_SCHEMA =
        """
        {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
         "properties":{"q":{"type":"string"},"n":{"type":"integer"}},"required":["q"]}""";

    private static final String NESTED_SCHEMA =
        """
        {"type":"object","properties":{"where":{"$ref":"#/$defs/Place"}},"required":["where"],
         "$defs":{"Place":{"type":"object",
                           "properties":{"city":{"type":"string"},"zip":{"type":"string"}},
                           "required":["city"]}}}""";

    private static final String SEALED_SCHEMA =
        """
        {"oneOf":[{"type":"object","properties":{"type":{"const":"Restart"}},"required":["type"]},
                  {"type":"object","properties":{"type":{"const":"Shutdown"}},"required":["type"]}]}""";

    private static ToolOffer offer(String name, String schema) {
      return new ToolOffer(new ToolName(name), "does " + name, new JsonSchema(schema));
    }

    private static List<FunctionDefinition> functionsFor(
        List<ToolOffer> offers, Map<String, String> properties) {
      InferenceRequest request =
          new InferenceRequest(
              SYSTEM,
              InferenceContext.of(List.of(open(1, "hi"))),
              Toolset.of(offers),
              new InferenceOptions("gpt-4o", 1024, properties));
      return OpenAiChatRequests.toParams(request, MAPPER).tools().orElseThrow().stream()
          .map(tool -> tool.asFunction().function())
          .toList();
    }

    private static final Map<String, String> STRICT = Map.of("openai.tools.strict", "true");

    @Test
    void without_the_property_a_tool_goes_as_generated_and_says_nothing_about_strict() {
      FunctionDefinition function =
          functionsFor(List.of(offer("lookup", LOOKUP_SCHEMA)), Map.of()).getFirst();

      assertThat(function.strict()).isEmpty();
      assertThat(sent(function.parameters().orElseThrow()))
          .containsEntry("required", List.of("q"))
          .doesNotContainKey("additionalProperties");
    }

    @Test
    void with_it_a_tool_is_strict_over_the_rewritten_schema() {
      FunctionDefinition function =
          functionsFor(List.of(offer("lookup", LOOKUP_SCHEMA)), STRICT).getFirst();

      assertThat(function.strict()).contains(true);
      Map<String, Object> schema = sent(function.parameters().orElseThrow());
      assertThat(schema).containsEntry("required", List.of("q", "n"));
      assertThat(schema).containsEntry("additionalProperties", false);
    }

    @Test
    void an_optional_component_is_widened_to_admit_null() {
      Map<String, Object> schema =
          sent(
              functionsFor(List.of(offer("lookup", LOOKUP_SCHEMA)), STRICT)
                  .getFirst()
                  .parameters()
                  .orElseThrow());

      assertThat(schema)
          .extractingByKey("properties", InstanceOfAssertFactories.MAP)
          .extractingByKey("n")
          .isEqualTo(Map.of("type", List.of("integer", "null")));
    }

    @Test
    void definitions_are_walked_too() {
      Map<String, Object> schema =
          sent(
              functionsFor(List.of(offer("locate", NESTED_SCHEMA)), STRICT)
                  .getFirst()
                  .parameters()
                  .orElseThrow());

      assertThat(schema)
          .extractingByKey("$defs", InstanceOfAssertFactories.MAP)
          .extractingByKey("Place", InstanceOfAssertFactories.MAP)
          .containsEntry("required", List.of("city", "zip"))
          .containsEntry("additionalProperties", false);
    }

    @Test
    void
        a_schema_strict_mode_cannot_express_goes_as_generated_with_a_warning_and_its_neighbour_stays_strict() {
      Logger logger = (Logger) LoggerFactory.getLogger(OpenAiChatRequests.class);
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
      List<FunctionDefinition> functions;
      try {
        functions =
            functionsFor(
                List.of(offer("restart", SEALED_SCHEMA), offer("lookup", LOOKUP_SCHEMA)), STRICT);
      } finally {
        logger.detachAppender(appender);
      }

      assertThat(functions.get(0).strict()).contains(false);
      assertThat(sent(functions.get(0).parameters().orElseThrow())).containsKey("oneOf");
      assertThat(functions.get(1).strict()).contains(true);
      assertThat(appender.list)
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("restart").contains("oneOf");
              });
    }

    @Test
    void the_schema_the_offer_carries_is_unchanged_by_the_rewrite() {
      ToolOffer lookup = offer("lookup", LOOKUP_SCHEMA);

      functionsFor(List.of(lookup), STRICT);

      assertThat(lookup.schema().json()).isEqualTo(LOOKUP_SCHEMA);
    }
  }
}
