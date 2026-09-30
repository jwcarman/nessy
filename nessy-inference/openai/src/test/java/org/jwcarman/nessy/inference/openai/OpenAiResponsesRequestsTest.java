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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.openai.core.ObjectMappers;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ToolChoiceOptions;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The projection onto the Responses wire, with no network anywhere near it.
 *
 * <p>This is where the adapter's judgement lives -- the prompt in {@code instructions}, one input
 * item per stored block, which reasoning items travel back, the strict rewrite -- so it is asserted
 * on the built params rather than only on what a live call happens to accept.
 */
class OpenAiResponsesRequestsTest {

  private static final SystemPrompt SYSTEM = new SystemPrompt("you are a helpful assistant");
  private static final InferenceOptions OPTIONS = new InferenceOptions("gpt-4o", 1024);
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String VENDOR = "openai";

  /** An encrypted reasoning item as the provider stores it (Task 4 writes this shape). */
  private static final String REASONING =
      "{\"id\":\"rs_1\",\"encrypted_content\":\"AAAA\","
          + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"weighing it\"}]}";

  private static InferenceRequest request(List<Turn> turns) {
    return new InferenceRequest(SYSTEM, InferenceContext.of(turns), Toolset.none(), OPTIONS);
  }

  private static ResponseCreateParams params(InferenceRequest request) {
    return OpenAiResponsesRequests.toParams(request, VENDOR, MAPPER);
  }

  private static List<ResponseInputItem> itemsOf(List<Turn> turns) {
    return params(request(turns)).input().orElseThrow().asResponse();
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

  private static Turn inFlight(List<Exchange> exchanges) {
    return new Turn(new TurnId(1), asked(1, "look it up"), exchanges, null, 0);
  }

  private static Exchange exchange(
      long seq, List<Block.ActionRequestContent> request, String callId) {
    return new Exchange(
        new Seq(seq),
        request,
        List.of(new ToolOutcome.Succeeded(new CallId(callId), List.of(new Block.Text("ok")))));
  }

  private static Block.ToolCall call(String id) {
    return new Block.ToolCall(new CallId(id), new ToolName("lookup"), "{\"q\":\"a\"}");
  }

  private static EasyInputMessage message(ResponseInputItem item) {
    return item.asEasyInputMessage();
  }

  private static ToolOffer offer(String name, String schema) {
    return new ToolOffer(new ToolName(name), "does " + name, new JsonSchema(schema));
  }

  private static final String LOOKUP_SCHEMA =
      """
      {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
       "properties":{"q":{"type":"string"},"reason":{"type":["string","null"]}},
       "required":["q"]}""";

  private static final String MAP_SCHEMA =
      """
      {"type":"object","properties":{"labels":{"type":"object","additionalProperties":{"type":"string"}}},
       "required":["labels"]}""";

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
  class TheInstructions {

    @Test
    void carry_the_system_prompt_and_nothing_else_leads_the_input() {
      ResponseCreateParams params = params(request(List.of(open(1, "hello"))));

      assertThat(params.instructions()).contains("you are a helpful assistant");
      assertThat(params.input().orElseThrow().asResponse().getFirst().asEasyInputMessage().role())
          .isEqualTo(EasyInputMessage.Role.USER);
    }

    @Test
    void carry_ambient_background_in_labelled_sections() {
      InferenceRequest request =
          new InferenceRequest(
              SYSTEM,
              new InferenceContext(
                  List.of(open(1, "hello")), List.of(Ambient.text("clock", "it is Tuesday"))),
              Toolset.none(),
              OPTIONS);

      assertThat(params(request).instructions().orElseThrow())
          .isEqualTo("you are a helpful assistant\n\n<clock>\nit is Tuesday\n</clock>");
    }
  }

  @Nested
  class TheModelAndCeiling {

    @Test
    void come_from_the_options_rather_than_from_the_adapter() {
      ResponseCreateParams params = params(request(List.of(open(1, "hi"))));

      assertThat(params.model().orElseThrow().asString()).isEqualTo("gpt-4o");
      assertThat(params.maxOutputTokens()).contains(1024L);
    }

    @Test
    void the_ceiling_is_omitted_entirely_when_none_was_asked_for() {
      InferenceRequest request =
          new InferenceRequest(
              SYSTEM,
              InferenceContext.of(List.of(open(1, "hi"))),
              Toolset.none(),
              InferenceOptions.of("gpt-4o"));

      assertThat(params(request).maxOutputTokens()).isEmpty();
    }
  }

  @Nested
  class ATurn {

    @Test
    void becomes_a_user_item_and_the_assistant_item_that_followed_it() {
      List<ResponseInputItem> items = itemsOf(List.of(answered(1, "hello", "hi there")));

      assertThat(items).hasSize(2);
      assertThat(message(items.get(0)).role()).isEqualTo(EasyInputMessage.Role.USER);
      assertThat(message(items.get(0)).content().asTextInput()).isEqualTo("hello");
      assertThat(message(items.get(1)).role()).isEqualTo(EasyInputMessage.Role.ASSISTANT);
      assertThat(message(items.get(1)).content().asTextInput()).isEqualTo("hi there");
    }

    @Test
    void still_in_flight_is_sent_with_no_answer_after_it() {
      assertThat(itemsOf(List.of(open(1, "hello")))).hasSize(1);
    }

    @Test
    void that_failed_is_explained_by_a_system_item_rather_than_left_silent() {
      Turn failed =
          new Turn(new TurnId(1), asked(1, "hello"), List.of(), new TurnResult.Failed(), 0);

      List<ResponseInputItem> items = itemsOf(List.of(failed, open(2, "again")));

      assertThat(message(items.get(1)).role()).isEqualTo(EasyInputMessage.Role.SYSTEM);
      assertThat(message(items.get(1)).content().asTextInput())
          .isEqualTo("The previous attempt to answer did not complete.");
    }

    @Test
    void that_was_refused_drops_its_question_and_says_so_in_its_place() {
      Turn refused =
          new Turn(
              new TurnId(1),
              asked(1, "something disallowed"),
              List.of(),
              new TurnResult.Refused(),
              0);

      List<ResponseInputItem> items = itemsOf(List.of(refused, open(2, "next")));

      assertThat(items).hasSize(2);
      assertThat(message(items.get(0)).role()).isEqualTo(EasyInputMessage.Role.SYSTEM);
      assertThat(message(items.get(0)).content().asTextInput())
          .isEqualTo(
              "A previous message was withdrawn from this conversation and is no longer available.");
    }
  }

  @Test
  void a_summary_leads_the_input_as_a_tagged_user_item() {
    InferenceRequest request =
        new InferenceRequest(
            SYSTEM,
            new InferenceContext(
                List.of(Summary.text(new TurnId(1), new TurnId(3), "they met")),
                List.of(open(4, "hi")),
                List.of()),
            Toolset.none(),
            OPTIONS);

    ResponseInputItem first = params(request).input().orElseThrow().asResponse().getFirst();

    assertThat(message(first).role()).isEqualTo(EasyInputMessage.Role.USER);
    assertThat(message(first).content().asTextInput())
        .isEqualTo("<summary from=\"1\" through=\"3\">\nthey met\n</summary>");
  }

  @Nested
  class AnExchange {

    @Test
    void becomes_one_item_per_block_in_stored_order_then_one_output_per_call() {
      Exchange exchange =
          new Exchange(
              new Seq(2),
              List.of(new Block.Commentary("Let me look."), call("call_1"), call("call_2")),
              List.of(
                  new ToolOutcome.Succeeded(new CallId("call_1"), List.of(new Block.Text("first"))),
                  new ToolOutcome.Succeeded(
                      new CallId("call_2"), List.of(new Block.Text("second")))));

      List<ResponseInputItem> items = itemsOf(List.of(inFlight(List.of(exchange))));

      assertThat(items).hasSize(6);
      assertThat(message(items.get(1)).role()).isEqualTo(EasyInputMessage.Role.ASSISTANT);
      assertThat(message(items.get(1)).content().asTextInput()).isEqualTo("Let me look.");
      assertThat(items.get(2).asFunctionCall().callId()).isEqualTo("call_1");
      assertThat(items.get(2).asFunctionCall().name()).isEqualTo("lookup");
      assertThat(items.get(2).asFunctionCall().arguments()).isEqualTo("{\"q\":\"a\"}");
      assertThat(items.get(3).asFunctionCall().callId()).isEqualTo("call_2");
      assertThat(items.get(4).asFunctionCallOutput().callId()).contains("call_1");
      assertThat(items.get(4).asFunctionCallOutput().output().asString()).isEqualTo("first");
      assertThat(items.get(5).asFunctionCallOutput().callId()).contains("call_2");
    }

    @Test
    void reports_a_failed_call_in_words() {
      Exchange exchange =
          new Exchange(
              new Seq(2),
              List.of(call("call_1")),
              List.of(new ToolOutcome.Failed(new CallId("call_1"), "the service was down")));

      List<ResponseInputItem> items = itemsOf(List.of(inFlight(List.of(exchange))));

      assertThat(items.get(2).asFunctionCallOutput().output().asString())
          .isEqualTo("Error: the service was down");
    }

    @Test
    void tells_the_model_a_denied_call_was_not_permitted_rather_than_that_it_broke() {
      Exchange exchange =
          new Exchange(
              new Seq(2),
              List.of(call("call_1")),
              List.of(new ToolOutcome.Denied(new CallId("call_1"), "out of hours")));

      List<ResponseInputItem> items = itemsOf(List.of(inFlight(List.of(exchange))));

      assertThat(items.get(2).asFunctionCallOutput().output().asString())
          .isEqualTo("This call was not run because it was not permitted: out of hours");
    }
  }

  @Nested
  class AReasoningItem {

    @Test
    void in_the_exchange_being_answered_is_replayed_ahead_of_the_call_it_arrived_with() {
      Exchange exchange =
          exchange(2, List.of(new Block.Provider(VENDOR, REASONING), call("call_1")), "call_1");

      List<ResponseInputItem> items = itemsOf(List.of(inFlight(List.of(exchange))));

      assertThat(items).hasSize(4);
      assertThat(items.get(1).isReasoning()).isTrue();
      assertThat(items.get(1).asReasoning().id()).isEqualTo("rs_1");
      assertThat(items.get(1).asReasoning().encryptedContent()).contains("AAAA");
      assertThat(items.get(1).asReasoning().summary())
          .extracting(part -> part.text())
          .containsExactly("weighing it");
      assertThat(items.get(2).asFunctionCall().callId()).isEqualTo("call_1");
    }

    @Test
    void in_an_earlier_exchange_of_the_same_turn_is_not_replayed_under_the_starting_rule() {
      Exchange first =
          exchange(2, List.of(new Block.Provider(VENDOR, REASONING), call("call_1")), "call_1");
      Exchange second = exchange(3, List.of(call("call_2")), "call_2");

      List<ResponseInputItem> items = itemsOf(List.of(inFlight(List.of(first, second))));

      assertThat(items).isNotEmpty().noneMatch(ResponseInputItem::isReasoning);
    }

    @Test
    void in_an_earlier_turn_s_exchange_is_not_replayed() {
      Turn earlier =
          new Turn(
              new TurnId(1),
              asked(1, "look it up"),
              List.of(
                  exchange(
                      2, List.of(new Block.Provider(VENDOR, REASONING), call("call_1")), "call_1")),
              new TurnResult.Answered(List.of(new Block.Text("done"))),
              0);

      List<ResponseInputItem> items = itemsOf(List.of(earlier, open(3, "and now?")));

      assertThat(items).isNotEmpty().noneMatch(ResponseInputItem::isReasoning);
      assertThat(items).anyMatch(ResponseInputItem::isFunctionCall);
    }

    @Test
    void beside_an_earlier_turn_s_answer_is_not_replayed() {
      Turn earlier =
          new Turn(
              new TurnId(1),
              asked(1, "hello"),
              List.of(),
              new TurnResult.Answered(
                  List.of(new Block.Provider(VENDOR, REASONING), new Block.Text("the answer"))),
              0);

      List<ResponseInputItem> items = itemsOf(List.of(earlier, open(2, "and now?")));

      assertThat(items).isNotEmpty().noneMatch(ResponseInputItem::isReasoning);
      assertThat(message(items.get(1)).content().asTextInput()).isEqualTo("the answer");
    }

    @Test
    void tagged_by_another_vendor_is_dropped_leaving_its_siblings_in_order() {
      Exchange exchange =
          exchange(
              2,
              List.of(new Block.Provider("anthropic", "{\"type\":\"thinking\"}"), call("call_1")),
              "call_1");

      List<ResponseInputItem> items = itemsOf(List.of(inFlight(List.of(exchange))));

      assertThat(items).hasSize(3);
      assertThat(items.get(1).asFunctionCall().callId()).isEqualTo("call_1");
    }

    @Test
    void tagged_openai_is_not_replayed_by_a_provider_answering_for_x_ai() {
      Exchange exchange =
          exchange(2, List.of(new Block.Provider(VENDOR, REASONING), call("call_1")), "call_1");

      List<ResponseInputItem> items =
          OpenAiResponsesRequests.toParams(
                  request(List.of(inFlight(List.of(exchange)))), "x_ai", MAPPER)
              .input()
              .orElseThrow()
              .asResponse();

      assertThat(items).isNotEmpty().noneMatch(ResponseInputItem::isReasoning);
    }

    /** Review Focus 3: half a reasoning item is worse than none. */
    @Test
    void an_openai_block_without_encrypted_content_is_not_replayed() {
      Exchange exchange =
          exchange(
              2,
              List.of(
                  new Block.Provider(VENDOR, "{\"id\":\"rs_1\",\"summary\":[]}"), call("call_1")),
              "call_1");

      List<ResponseInputItem> items = itemsOf(List.of(inFlight(List.of(exchange))));

      assertThat(items).isNotEmpty().noneMatch(ResponseInputItem::isReasoning);
    }
  }

  @Nested
  class TheStatelessContract {

    @Test
    void every_request_says_store_false_and_asks_for_encrypted_reasoning() {
      ResponseCreateParams params = params(request(List.of(open(1, "hi"))));

      assertThat(params.store()).contains(false);
      assertThat(params.include().orElseThrow())
          .containsExactly(ResponseIncludable.REASONING_ENCRYPTED_CONTENT);
    }

    @Test
    void no_request_names_a_previous_response_or_a_conversation() {
      ResponseCreateParams params = params(request(List.of(open(1, "hi"))));

      assertThat(params.previousResponseId()).isEmpty();
      assertThat(params.conversation()).isEmpty();
    }

    /**
     * No reasoning object goes out unless {@code openai.reasoning.effort} or {@code
     * openai.reasoning.summary} is set: one is a 400 on a model that does not reason.
     */
    @Test
    void no_reasoning_object_is_sent() {
      assertThat(params(request(List.of(open(1, "hi")))).reasoning()).isEmpty();
    }
  }

  @Nested
  class TheVendorProperties {

    private static InferenceRequest carrying(Map<String, String> agentType) {
      return new InferenceRequest(
          SYSTEM,
          InferenceContext.of(List.of(open(1, "hi"))),
          Toolset.none(),
          new InferenceOptions("gpt-6-sol", 1024, agentType));
    }

    private static ResponseCreateParams paramsFor(Map<String, String> agentType) {
      return params(carrying(agentType));
    }

    @Test
    void an_effort_builds_the_reasoning_object() {
      Reasoning reasoning =
          paramsFor(Map.of("openai.reasoning.effort", "high")).reasoning().orElseThrow();

      assertThat(reasoning.effort().map(ReasoningEffort::asString)).contains("high");
      assertThat(reasoning.summary()).isEmpty();
    }

    @Test
    void a_summary_joins_the_same_reasoning_object() {
      Reasoning reasoning =
          paramsFor(Map.of("openai.reasoning.effort", "low", "openai.reasoning.summary", "auto"))
              .reasoning()
              .orElseThrow();

      assertThat(reasoning.effort().map(ReasoningEffort::asString)).contains("low");
      assertThat(reasoning.summary().map(Reasoning.Summary::asString)).contains("auto");
    }

    /** §5g: no reasoning object unless one of the two names asks for it. */
    @Test
    void without_either_reasoning_name_no_reasoning_object_is_sent() {
      assertThat(paramsFor(Map.of("openai.seed", "1")).reasoning()).isEmpty();
    }

    @Test
    void a_service_tier_lands_in_its_typed_field() {
      assertThat(
              paramsFor(Map.of("openai.service_tier", "flex"))
                  .serviceTier()
                  .map(ResponseCreateParams.ServiceTier::asString))
          .contains("flex");
    }

    /** Strict is what this wire always does, so asking for it is accepted and changes nothing. */
    @Test
    void strict_true_is_accepted_as_what_this_wire_already_does() {
      InferenceRequest request = carrying(Map.of("openai.tools.strict", "true"));

      assertThatCode(() -> OpenAiResponsesRequests.toParams(request, VENDOR, MAPPER))
          .doesNotThrowAnyException();
      assertThat(params(request)._additionalBodyProperties()).isEmpty();
    }

    @Test
    void strict_false_is_refused_because_this_wire_is_strict_regardless() {
      InferenceRequest request = carrying(Map.of("openai.tools.strict", "false"));

      assertThatThrownBy(() -> OpenAiResponsesRequests.toParams(request, VENDOR, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'openai.tools.strict'")
          .hasMessageContaining("strict regardless");
    }

    /** Names that were once refused as clashes are simply unsupported now: ignored, not sent. */
    @ParameterizedTest
    @ValueSource(
        strings = {
          "model",
          "input",
          "instructions",
          "max_output_tokens",
          "tools",
          "tool_choice",
          "text",
          "text.verbosity",
          "stream",
          "store",
          "include",
          "previous_response_id",
          "conversation",
          "background",
          "top_p",
          "metadata.team",
          "reasoning.generate_summary"
        })
    void an_unsupported_name_is_not_sent_and_the_request_is_as_if_it_were_not_given(String name) {
      ResponseCreateParams with = paramsFor(Map.of("openai." + name, "true"));

      assertThat(with._additionalBodyProperties()).isEmpty();
      assertThat(with).isEqualTo(paramsFor(Map.of()));
      assertThat(with.store()).contains(false);
      assertThat(with.maxOutputTokens()).contains(1024L);
    }

    @Test
    void an_unsupported_reasoning_name_beside_a_supported_one_is_dropped_and_the_object_is_typed() {
      Reasoning reasoning =
          paramsFor(
                  Map.of(
                      "openai.reasoning.effort", "high",
                      "openai.reasoning.generate_summary", "auto"))
              .reasoning()
              .orElseThrow();

      assertThat(reasoning.effort().map(ReasoningEffort::asString)).contains("high");
      assertThat(reasoning._additionalProperties()).isEmpty();
    }

    @Test
    void reading_a_request_says_nothing_about_an_unsupported_name() {
      InferenceRequest request = carrying(Map.of("openai.store", "true"));

      List<ILoggingEvent> events =
          LogCapture.during(
              OpenAiProperties.class,
              () -> {
                params(request);
                params(request);
              });

      assertThat(events).isEmpty();
    }

    @Test
    void another_prefix_is_not_sent() {
      assertThat(paramsFor(Map.of("anthropic.top_k", "5"))._additionalBodyProperties()).isEmpty();
    }

    @Test
    void an_agent_type_entry_overrides_the_same_name_given_to_the_provider() {
      ResponseCreateParams params =
          OpenAiResponsesRequests.toParams(
              carrying(Map.of("openai.reasoning.effort", "high")),
              VENDOR,
              Map.of("openai.reasoning.effort", "low"),
              MAPPER);

      assertThat(params.reasoning().orElseThrow().effort().map(ReasoningEffort::asString))
          .contains("high");
    }
  }

  @Nested
  class ABoundTool {

    private static InferenceRequest offering(List<ToolOffer> offers) {
      return new InferenceRequest(
          SYSTEM, InferenceContext.of(List.of(open(1, "hi"))), Toolset.of(offers), OPTIONS);
    }

    @Test
    void becomes_a_strict_function_tool_carrying_the_rewritten_schema() {
      FunctionTool tool =
          params(offering(List.of(offer("lookup", LOOKUP_SCHEMA))))
              .tools()
              .orElseThrow()
              .getFirst()
              .asFunction();

      assertThat(tool.name()).isEqualTo("lookup");
      assertThat(tool.description()).contains("does lookup");
      assertThat(tool.strict()).contains(true);
      Map<String, Object> schema = sent(tool.parameters().orElseThrow());
      assertThat(schema).containsEntry("required", List.of("q", "reason"));
      assertThat(schema).containsEntry("additionalProperties", false);
    }

    @Test
    void the_schema_the_offer_carries_is_unchanged_by_the_rewrite() {
      ToolOffer lookup = offer("lookup", LOOKUP_SCHEMA);

      params(offering(List.of(lookup)));

      assertThat(lookup.schema().json()).isEqualTo(LOOKUP_SCHEMA);
    }

    @Test
    void
        a_schema_strict_mode_cannot_express_goes_as_generated_with_a_warning_and_its_neighbour_stays_strict() {
      Logger logger = (Logger) LoggerFactory.getLogger(OpenAiResponsesRequests.class);
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
      List<FunctionTool> tools;
      try {
        tools =
            params(offering(List.of(offer("tagging", MAP_SCHEMA), offer("lookup", LOOKUP_SCHEMA))))
                .tools()
                .orElseThrow()
                .stream()
                .map(tool -> tool.asFunction())
                .toList();
      } finally {
        logger.detachAppender(appender);
      }

      assertThat(tools.get(0).strict()).contains(false);
      assertThat(sent(tools.get(0).parameters().orElseThrow()))
          .doesNotContainKey("additionalProperties");
      assertThat(tools.get(1).strict()).contains(true);
      assertThat(appender.list)
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                    .contains("tagging")
                    .contains("additionalProperties");
              });
    }

    @Test
    void is_absent_entirely_when_none_were_bound() {
      assertThat(params(request(List.of(open(1, "hi")))).tools()).isEmpty();
    }
  }

  @Nested
  class ChoosingATool {

    private static ResponseCreateParams choosing(ToolChoice choice) {
      return params(
          new InferenceRequest(
              SYSTEM,
              InferenceContext.of(List.of(open(1, "hi"))),
              new Toolset(List.of(offer("lookup", LOOKUP_SCHEMA)), choice),
              OPTIONS));
    }

    @Test
    void by_default_nothing_is_said_about_choosing() {
      assertThat(choosing(ToolChoice.auto()).toolChoice()).isEmpty();
    }

    @Test
    void a_ban_is_sent_as_none() {
      assertThat(choosing(new ToolChoice.None()).toolChoice().orElseThrow().options())
          .contains(ToolChoiceOptions.NONE);
    }

    /** Emulated, as on the chat wire: "none" is documented as "generate a message instead". */
    @Test
    void answering_now_is_sent_as_none_with_the_offers_left_in_place() {
      ResponseCreateParams params = choosing(new ToolChoice.Answer());

      assertThat(params.toolChoice().orElseThrow().options()).contains(ToolChoiceOptions.NONE);
      assertThat(params.tools().orElseThrow()).hasSize(1);
    }

    @Test
    void requiring_some_tool_is_sent_as_required() {
      assertThat(choosing(new ToolChoice.Any()).toolChoice().orElseThrow().options())
          .contains(ToolChoiceOptions.REQUIRED);
    }

    @Test
    void requiring_one_tool_names_it() {
      assertThat(
              choosing(new ToolChoice.Named(new ToolName("lookup")))
                  .toolChoice()
                  .orElseThrow()
                  .function()
                  .orElseThrow()
                  .name())
          .isEqualTo("lookup");
    }

    @Test
    void nothing_is_said_when_nothing_is_on_offer() {
      assertThat(params(request(List.of(open(1, "hi")))).toolChoice()).isEmpty();
    }
  }

  @Test
  void an_answer_s_shape_becomes_a_strict_text_format_named_answer() {
    InferenceRequest request =
        new InferenceRequest(
            SYSTEM,
            InferenceContext.of(List.of(open(1, "hi"))),
            Toolset.none(),
            OPTIONS,
            Optional.of(new JsonSchema(LOOKUP_SCHEMA)));

    var format = params(request).text().orElseThrow().format().orElseThrow().asJsonSchema();

    assertThat(format.name()).isEqualTo("answer");
    assertThat(format.strict()).contains(true);
    assertThat(sent(format.schema())).containsEntry("required", List.of("q", "reason"));
  }

  @Test
  void a_list_answer_is_asked_for_without_strict_mode_and_says_so() {
    InferenceRequest request =
        new InferenceRequest(
            SYSTEM,
            InferenceContext.of(List.of(open(1, "hi"))),
            Toolset.none(),
            OPTIONS,
            Optional.of(
                new JsonSchema(
                    """
                    {"$schema":"https://json-schema.org/draft/2020-12/schema",
       "$defs":{"Item":{"type":"object","properties":{"name":{"type":"string"},"count":{"type":"integer"}},
                        "required":["name","count"]}},
       "type":"array","items":{"$ref":"#/$defs/Item"}}""")));

    List<ILoggingEvent> events =
        LogCapture.during(OpenAiResponsesRequests.class, () -> params(request));
    var format = params(request).text().orElseThrow().format().orElseThrow().asJsonSchema();

    assertThat(format.strict()).contains(false);
    assertThat(sent(format.schema())).containsEntry("type", "array");
    assertThat(LogCapture.warnings(events))
        .containsExactly(
            "The answer's shape is asked for without strict mode: its schema uses array at the"
                + " root, which strict mode cannot express");
  }
}
