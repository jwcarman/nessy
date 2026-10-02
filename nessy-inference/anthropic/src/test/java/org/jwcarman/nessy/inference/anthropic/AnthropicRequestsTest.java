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
package org.jwcarman.nessy.inference.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * The projection onto Anthropic's Messages wire, with no network anywhere near it.
 *
 * <p>Two things live here that no other adapter has to think about: cache breakpoints, which are
 * money rather than correctness, and extended thinking, which only comes back to the vendor intact
 * if this code never touches it.
 */
class AnthropicRequestsTest {

  private static Chapter chapter(long from, long through) {
    return new Chapter(
        new AgentType("chat"), AgentId.random(), new TurnId(from), new TurnId(through));
  }

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Map<String, String> NONE = Map.of();
  private static final SystemPrompt SYSTEM = new SystemPrompt("you are a helpful assistant");

  private static InferenceOptions options() {
    return new InferenceOptions("claude-sonnet", 1024);
  }

  private static InferenceRequest request(List<Turn> turns) {
    return new InferenceRequest(SYSTEM, InferenceContext.of(turns), Toolset.none(), options());
  }

  private static Map<String, String> caching(AnthropicCacheTtl ttl) {
    return Map.of(AnthropicProperties.CACHE_TTL.name(), AnthropicProperties.CACHE_TTL.format(ttl));
  }

  private static MessageCreateParams params(List<Turn> turns) {
    return AnthropicRequests.toParams(request(turns), NONE, MAPPER);
  }

  /** A request that thinks: the setting is what makes replayed reasoning worth sending. */
  private static final Map<String, String> THINKING = Map.of("anthropic.thinking.type", "adaptive");

  private static MessageCreateParams thinkingParams(List<Turn> turns) {
    return AnthropicRequests.toParams(request(turns), THINKING, MAPPER);
  }

  private static MessageCreateParams params(List<Turn> turns, AnthropicCacheTtl ttl) {
    return AnthropicRequests.toParams(request(turns), caching(ttl), MAPPER);
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

  /** Anthropic's own opaque payload, as its own reply would have carried it. */
  private static Block.Provider thinking(String text, String signature) {
    return new Block.Provider(
        "anthropic",
        MAPPER.writeValueAsString(
            java.util.Map.of("type", "thinking", "thinking", text, "signature", signature)));
  }

  private static List<ContentBlockParam> blocksOf(MessageCreateParams params) {
    return params.messages().stream()
        .flatMap(message -> message.content().asBlockParams().stream())
        .toList();
  }

  @Nested
  class TheSystemField {

    /**
     * A top-level field on this wire rather than a leading message, which is the difference that
     * makes an adapter necessary. There is no blank case: {@link SystemPrompt} refuses one.
     */
    @Test
    void carries_the_standing_instruction() {
      var system = params(List.of(open(1, "hello"))).system().orElseThrow().asTextBlockParams();

      assertThat(system).hasSize(1);
      assertThat(system.getFirst().text()).isEqualTo("you are a helpful assistant");
    }

    /** Background follows the last message, so a change in it never changes this field. */
    @Test
    void does_not_carry_ambient_sections() {
      InferenceRequest request =
          new InferenceRequest(
              SYSTEM,
              new InferenceContext(
                  List.of(open(1, "hello")),
                  List.of(
                      Ambient.text("notebook", "the deploy is frozen"),
                      Ambient.text("clock", "it is Tuesday"))),
              Toolset.none(),
              options());

      var system =
          AnthropicRequests.toParams(request, NONE, MAPPER)
              .system()
              .orElseThrow()
              .asTextBlockParams();

      assertThat(system).hasSize(1);
      assertThat(system.getFirst().text()).isEqualTo("you are a helpful assistant");
    }

    @Test
    void is_marked_for_caching_only_when_asked_for() {
      assertThat(
              params(List.of(open(1, "hi")))
                  .system()
                  .orElseThrow()
                  .asTextBlockParams()
                  .getFirst()
                  .cacheControl())
          .isEmpty();
      assertThat(
              params(List.of(open(1, "hi")), AnthropicCacheTtl.FIVE_MINUTES)
                  .system()
                  .orElseThrow()
                  .asTextBlockParams()
                  .getFirst()
                  .cacheControl())
          .isPresent();
    }

    @Test
    void takes_the_long_retention_when_that_is_what_was_asked_for() {
      var marker =
          params(List.of(open(1, "hi")), AnthropicCacheTtl.ONE_HOUR)
              .system()
              .orElseThrow()
              .asTextBlockParams()
              .getFirst()
              .cacheControl()
              .orElseThrow();

      assertThat(marker.ttl())
          .contains(com.anthropic.models.messages.CacheControlEphemeral.Ttl.TTL_1H);
    }
  }

  @Nested
  class PlacingTheStrata {

    private static final Memory RECALLED = Memory.text("recalled", "  the lake is deep \n");
    private static final Memory TRIVIA = Memory.text("trivia", "Nessie is shy");
    private static final State SITUATION = State.text("situation", "the deploy is frozen");
    private static final Ambient CLOCK = Ambient.text("clock", "it is Tuesday");
    private static final Ambient WEATHER = Ambient.text("weather", "rain");

    private static InferenceContext context(
        List<Turn> tail,
        List<Memory> memory,
        List<State> state,
        Turn active,
        List<Ambient> ambient) {
      return new InferenceContext(List.of(), tail, memory, state, active, ambient);
    }

    private static MessageCreateParams built(InferenceContext context, Map<String, String> props) {
      return AnthropicRequests.toParams(
          new InferenceRequest(SYSTEM, context, Toolset.none(), options()), props, MAPPER);
    }

    private static List<String> textsOf(MessageParam message) {
      return message.content().asBlockParams().stream()
          .map(
              block -> {
                if (block.isText()) {
                  return block.asText().text();
                }
                return block.isToolResult() ? "<tool_result>" : "<other>";
              })
          .toList();
    }

    private static Turn calling(long id, String question) {
      Exchange exchange =
          new Exchange(
              new Seq(100L),
              List.of(
                  new Block.ToolCall(
                      new CallId("call_1"), new ToolName("lookup"), "{\"q\":\"x\"}")),
              List.of(
                  new ToolOutcome.Succeeded(
                      new CallId("call_1"), List.of(new Block.Text("result 1")))));
      return new Turn(new TurnId(id), asked(id, question), List.of(exchange), null, 0);
    }

    @Test
    void the_system_field_holds_only_the_instructions() {
      var built =
          built(
              context(
                  List.of(),
                  List.of(RECALLED),
                  List.of(SITUATION),
                  open(1, "hello"),
                  List.of(CLOCK)),
              NONE);

      var system = built.system().orElseThrow().asTextBlockParams();

      assertThat(system).hasSize(1);
      assertThat(system.getFirst().text()).isEqualTo("you are a helpful assistant");
    }

    @Test
    void memory_and_state_lead_the_active_turns_first_message() {
      var built =
          built(
              context(
                  List.of(),
                  List.of(RECALLED, TRIVIA),
                  List.of(SITUATION),
                  open(1, "hello"),
                  List.of()),
              NONE);

      assertThat(built.messages()).hasSize(1);
      assertThat(textsOf(built.messages().getFirst()))
          .containsExactly(
              "<memory kind=\"recalled\">\nthe lake is deep\n</memory>",
              "<memory kind=\"trivia\">\nNessie is shy\n</memory>",
              "<state kind=\"situation\">\nthe deploy is frozen\n</state>",
              "hello");
    }

    @Test
    void memory_and_state_are_not_attached_to_a_turn_in_the_tail() {
      var built =
          built(
              context(
                  List.of(answered(1, "question 1", "answer 1")),
                  List.of(RECALLED),
                  List.of(SITUATION),
                  open(2, "question 2"),
                  List.of()),
              NONE);

      assertThat(built.messages()).hasSize(3);
      assertThat(textsOf(built.messages().get(0))).containsExactly("question 1");
      assertThat(textsOf(built.messages().get(1))).containsExactly("answer 1");
      assertThat(textsOf(built.messages().get(2)))
          .containsExactly(
              "<memory kind=\"recalled\">\nthe lake is deep\n</memory>",
              "<state kind=\"situation\">\nthe deploy is frozen\n</state>",
              "question 2");
    }

    @Test
    void ambient_ends_the_request_after_the_active_turns_input() {
      var built =
          built(
              context(List.of(), List.of(), List.of(), open(1, "hello"), List.of(CLOCK, WEATHER)),
              NONE);

      assertThat(built.messages()).hasSize(1);
      assertThat(textsOf(built.messages().getFirst()))
          .containsExactly(
              "hello", "<clock>\nit is Tuesday\n</clock>", "<weather>\nrain\n</weather>");
    }

    @Test
    void ambient_ends_the_request_after_the_last_tool_results() {
      var built =
          built(
              context(List.of(), List.of(), List.of(), calling(1, "hello"), List.of(CLOCK)), NONE);

      assertThat(built.messages()).hasSize(3);
      assertThat(built.messages().getLast().role()).isEqualTo(MessageParam.Role.USER);
      assertThat(textsOf(built.messages().getLast()))
          .containsExactly("<tool_result>", "<clock>\nit is Tuesday\n</clock>");
    }

    @Test
    void ambient_gets_a_user_message_of_its_own_when_the_request_ends_on_the_assistant() {
      Turn answeredOnce = answered(1, "question 1", "answer 1");
      var built =
          built(context(List.of(), List.of(), List.of(), answeredOnce, List.of(CLOCK)), NONE);

      assertThat(built.messages()).hasSize(3);
      assertThat(built.messages().getLast().role()).isEqualTo(MessageParam.Role.USER);
      assertThat(textsOf(built.messages().getLast()))
          .containsExactly("<clock>\nit is Tuesday\n</clock>");
    }

    @Test
    void the_cache_marker_sits_on_the_block_before_ambient() {
      var built =
          built(
              context(
                  List.of(),
                  List.of(RECALLED),
                  List.of(),
                  calling(1, "hello"),
                  List.of(CLOCK, WEATHER)),
              caching(AnthropicCacheTtl.FIVE_MINUTES));

      var last = built.messages().getLast().content().asBlockParams();
      assertThat(last).hasSize(3);
      assertThat(last.get(0).isToolResult()).isTrue();
      assertThat(last.get(0).cacheControl()).isPresent();
      assertThat(last.get(1).asText().cacheControl()).isEmpty();
      assertThat(last.get(2).asText().cacheControl()).isEmpty();
      var ambientTexts = last.subList(1, 3);
      assertThat(ambientTexts).isNotEmpty();
      assertThat(ambientTexts).noneMatch(block -> block.cacheControl().isPresent());
    }

    @Test
    void a_context_with_no_memory_state_or_ambient_renders_as_before() {
      var built =
          built(
              context(
                  List.of(answered(1, "question 1", "answer 1")),
                  List.of(),
                  List.of(),
                  calling(2, "question 2"),
                  List.of()),
              caching(AnthropicCacheTtl.FIVE_MINUTES));

      assertThat(built.messages()).hasSize(5);
      assertThat(textsOf(built.messages().get(0))).containsExactly("question 1");
      assertThat(textsOf(built.messages().get(1))).containsExactly("answer 1");
      assertThat(textsOf(built.messages().get(2))).containsExactly("question 2");
      assertThat(textsOf(built.messages().get(3))).containsExactly("<other>");
      assertThat(textsOf(built.messages().get(4))).containsExactly("<tool_result>");
      var marked =
          IntStream.range(0, built.messages().size())
              .boxed()
              .flatMap(
                  message -> {
                    var blocks = built.messages().get(message).content().asBlockParams();
                    return IntStream.range(0, blocks.size())
                        .filter(block -> blocks.get(block).cacheControl().isPresent())
                        .mapToObj(block -> message + ":" + block);
                  })
              .toList();
      assertThat(marked).containsExactly("2:0", "4:0");
    }

    @Test
    void a_refused_active_turn_sends_no_memory_or_state() {
      Turn refused =
          new Turn(new TurnId(2), asked(2, "rude"), List.of(), new TurnResult.Refused(), 0);
      var built =
          built(
              context(
                  List.of(answered(1, "question 1", "answer 1")),
                  List.of(RECALLED),
                  List.of(SITUATION),
                  refused,
                  List.of()),
              NONE);

      assertThat(built.messages()).hasSize(2);
      assertThat(blocksOf(built)).noneMatch(block -> block.asText().text().contains("<memory"));
      assertThat(blocksOf(built)).noneMatch(block -> block.asText().text().contains("<state"));
    }

    @Test
    void blank_memory_state_and_ambient_are_left_out() {
      var built =
          built(
              context(
                  List.of(),
                  List.of(Memory.text("recalled", "   ")),
                  List.of(State.text("situation", "\n ")),
                  open(1, "hello"),
                  List.of(Ambient.text("clock", "  "))),
              NONE);

      assertThat(built.messages()).hasSize(1);
      assertThat(textsOf(built.messages().getFirst())).containsExactly("hello");
    }
  }

  @Nested
  class TheModelAndItsCeiling {

    @Test
    void come_from_the_options_rather_than_from_the_adapter() {
      var built = params(List.of(open(1, "hi")));

      assertThat(built.model().asString()).isEqualTo("claude-sonnet");
      assertThat(built.maxTokens()).isEqualTo(1024L);
    }
  }

  @Nested
  class ATurn {

    @Test
    void becomes_a_user_message_and_the_assistant_answer_that_followed_it() {
      var messages = params(List.of(answered(1, "how deep?", "1412 metres"))).messages();

      assertThat(messages).hasSize(2);
      assertThat(messages.get(0).role()).isEqualTo(MessageParam.Role.USER);
      assertThat(messages.get(0).content().asBlockParams().getFirst().asText().text())
          .isEqualTo("how deep?");
      assertThat(messages.get(1).role()).isEqualTo(MessageParam.Role.ASSISTANT);
    }

    /**
     * Roles alternate on this wire. Without something standing in, a failed turn's question and the
     * next turn's question are two user messages running together, which the model reads as having
     * been ignored -- and there is no system role here to explain the gap with, so the assistant
     * has to answer for it.
     */
    @Test
    void that_failed_gets_an_assistant_message_because_there_is_no_role_that_could_narrate() {
      Turn failed =
          new Turn(
              new TurnId(1), asked(1, "what happened?"), List.of(), new TurnResult.Failed(), 0);

      var messages = params(List.of(failed, open(3, "again?"))).messages();

      assertThat(messages).hasSize(3);
      assertThat(messages.get(1).role()).isEqualTo(MessageParam.Role.ASSISTANT);
      assertThat(messages.get(1).content().asBlockParams().getFirst().asText().text())
          .as("did not complete, rather than returned an error: it may never have run at all")
          .contains("did not complete");
      assertThat(messages.get(2).role()).isEqualTo(MessageParam.Role.USER);
    }

    /**
     * Omitted whole, and nothing put in its place. The question is what caused the refusal, and
     * there is no role here that could say "something was withdrawn" without putting words in the
     * assistant's mouth -- a fabricated utterance is worse than a gap.
     */
    @Test
    void that_was_refused_is_omitted_entirely_rather_than_explained() {
      Turn refused =
          new Turn(
              new TurnId(1),
              asked(1, "something disallowed"),
              List.of(),
              new TurnResult.Refused(),
              0);

      var messages = params(List.of(refused, open(3, "again?"))).messages();

      assertThat(messages).hasSize(1);
      assertThat(messages.getFirst().content().asBlockParams().getFirst().asText().text())
          .isEqualTo("again?");
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

    private static Block.ToolCall call(String id) {
      return new Block.ToolCall(new CallId(id), new ToolName("lookup"), "{\"q\":\"loch ness\"}");
    }

    @Test
    void asks_as_the_assistant_and_answers_as_the_user() {
      var messages =
          params(
                  List.of(
                      withCalls(
                          List.of(call("call_1")),
                          List.of(
                              new ToolOutcome.Succeeded(
                                  new CallId("call_1"), List.of(new Block.Text("1412 metres")))))))
              .messages();

      assertThat(messages.get(1).role()).isEqualTo(MessageParam.Role.ASSISTANT);
      var use = messages.get(1).content().asBlockParams().getFirst().asToolUse();
      assertThat(use.id()).isEqualTo("call_1");
      assertThat(use.name()).isEqualTo("lookup");
      assertThat(use.input()._additionalProperties()).containsKey("q");

      assertThat(messages.get(2).role())
          .as("tool results are user content on this wire, which is not obvious")
          .isEqualTo(MessageParam.Role.USER);
      var result = messages.get(2).content().asBlockParams().getFirst().asToolResult();
      assertThat(result.toolUseId()).isEqualTo("call_1");
      assertThat(result.isError()).contains(false);
    }

    /**
     * This wire can say a call went wrong, and OpenAI's cannot. Marking a denial as a success would
     * offer its explanation to the model as the lake's depth.
     */
    @Test
    void marks_a_failure_and_a_denial_as_errors_because_neither_is_the_tools_answer() {
      var failed =
          params(
                  List.of(
                      withCalls(
                          List.of(call("call_1")),
                          List.of(
                              new ToolOutcome.Failed(
                                  new CallId("call_1"), "the service was down")))))
              .messages()
              .get(2)
              .content()
              .asBlockParams()
              .getFirst()
              .asToolResult();
      assertThat(failed.isError()).contains(true);

      var denied =
          params(
                  List.of(
                      withCalls(
                          List.of(call("call_1")),
                          List.of(new ToolOutcome.Denied(new CallId("call_1"), "out of hours")))))
              .messages()
              .get(2)
              .content()
              .asBlockParams()
              .getFirst()
              .asToolResult();
      assertThat(denied.isError()).contains(true);
      assertThat(denied.content().orElseThrow().blocks().orElseThrow().getFirst().asText().text())
          .as("what it was is still said in words, even where the wire cannot carry it")
          .contains("not permitted")
          .contains("out of hours");
    }
  }

  @Nested
  class ExtendedThinking {

    /** Only accepted back with the signature it was issued with, so both travel together. */
    @Test
    void round_trips_with_its_signature_untouched() {
      Turn turn =
          new Turn(
              new TurnId(1),
              asked(1, "how deep?"),
              List.of(),
              new TurnResult.Answered(
                  List.of(thinking("let me recall", "sig-abc"), new Block.Text("1412 metres"))),
              0);

      var blocks = thinkingParams(List.of(turn)).messages().get(1).content().asBlockParams();

      assertThat(blocks).hasSize(2);
      assertThat(blocks.getFirst().asThinking().thinking()).isEqualTo("let me recall");
      assertThat(blocks.getFirst().asThinking().signature()).isEqualTo("sig-abc");
    }

    /** Unsigned reasoning is rejected by the vendor, so it is dropped rather than sent. */
    @Test
    void unsigned_reasoning_is_dropped_leaving_its_siblings_in_order() {
      Turn turn =
          new Turn(
              new TurnId(1),
              asked(1, "how deep?"),
              List.of(),
              new TurnResult.Answered(
                  List.of(thinking("let me recall", ""), new Block.Text("1412 metres"))),
              0);

      var blocks = thinkingParams(List.of(turn)).messages().get(1).content().asBlockParams();

      assertThat(blocks).hasSize(1);
      assertThat(blocks.getFirst().asText().text()).isEqualTo("1412 metres");
    }

    @Test
    void redacted_reasoning_round_trips_its_opaque_data() {
      Block.Provider redacted =
          new Block.Provider(
              "anthropic",
              MAPPER.writeValueAsString(
                  java.util.Map.of("type", "redacted_thinking", "data", "opaque-bytes")));
      Turn turn =
          new Turn(
              new TurnId(1),
              asked(1, "how deep?"),
              List.of(),
              new TurnResult.Answered(List.of(redacted, new Block.Text("1412 metres"))),
              0);

      var blocks = thinkingParams(List.of(turn)).messages().get(1).content().asBlockParams();

      assertThat(blocks.getFirst().asRedactedThinking().data()).isEqualTo("opaque-bytes");
    }

    /**
     * Another vendor's reasoning is not ours to send. It would at best be ignored and at worst
     * rejected, and the vendor tag on the block is what makes that decidable.
     */
    @Test
    void another_vendors_reasoning_is_never_sent_here() {
      Block.Provider theirs =
          new Block.Provider(
              "openai",
              MAPPER.writeValueAsString(
                  java.util.Map.of("type", "thinking", "thinking", "hmm", "signature", "sig")));
      Turn turn =
          new Turn(
              new TurnId(1),
              asked(1, "how deep?"),
              List.of(),
              new TurnResult.Answered(List.of(theirs, new Block.Text("1412 metres"))),
              0);

      var blocks = thinkingParams(List.of(turn)).messages().get(1).content().asBlockParams();

      assertThat(blocks).hasSize(1);
      assertThat(blocks.getFirst().asText().text()).isEqualTo("1412 metres");
    }

    @Test
    void our_own_state_of_an_unrecognised_type_is_dropped() {
      Block.Provider odd =
          new Block.Provider(
              "anthropic", MAPPER.writeValueAsString(java.util.Map.of("type", "something_new")));
      Turn turn =
          new Turn(
              new TurnId(1),
              asked(1, "how deep?"),
              List.of(),
              new TurnResult.Answered(List.of(odd, new Block.Text("1412 metres"))),
              0);

      assertThat(thinkingParams(List.of(turn)).messages().get(1).content().asBlockParams())
          .hasSize(1);
    }

    @Test
    void enabled_asks_for_a_budget_and_disabled_asks_for_nothing() {
      var thinking =
          AnthropicRequests.toParams(
              request(List.of(open(1, "hi"))),
              Map.of(
                  AnthropicProperties.THINKING_TYPE.name(), "enabled",
                  AnthropicProperties.THINKING_BUDGET.name(), "512"),
              MAPPER);
      assertThat(thinking.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(512L);

      assertThat(params(List.of(open(1, "hi"))).thinking()).isEmpty();
    }

    @Test
    void enabled_without_a_budget_sends_the_default_of_1024() {
      var request =
          new InferenceRequest(
              SYSTEM,
              InferenceContext.of(List.of(open(1, "hi"))),
              Toolset.none(),
              new InferenceOptions("claude-sonnet", 4096));
      var params =
          AnthropicRequests.toParams(
              request, Map.of(AnthropicProperties.THINKING_TYPE.name(), "enabled"), MAPPER);

      assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(1024L);
    }

    @Test
    void enabled_without_a_budget_under_a_ceiling_of_1024_is_refused_for_headroom() {
      var request = request(List.of(open(1, "hi")));
      var enabled = Map.of(AnthropicProperties.THINKING_TYPE.name(), "enabled");

      assertThatThrownBy(() -> AnthropicRequests.toParams(request, enabled, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("maxTokens (1024) must be greater than the thinking budget (1024)");
    }

    /**
     * The budget is spent out of maxTokens, so a ceiling at or below it leaves nothing to answer
     * with. Refused here rather than at the wire, where it is a 400 with no hint.
     */
    @Test
    void a_budget_with_no_headroom_under_the_ceiling_is_refused_before_the_call() {
      var request = request(List.of(open(1, "hi")));
      var noHeadroom =
          Map.of(
              AnthropicProperties.THINKING_TYPE.name(), "enabled",
              AnthropicProperties.THINKING_BUDGET.name(), "1024");
      assertThatThrownBy(() -> AnthropicRequests.toParams(request, noHeadroom, MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("1024");
    }
  }

  @Nested
  class ABoundTool {

    private static ToolOffer offer(String name) {
      return new ToolOffer(
          new ToolName(name),
          "looks a thing up",
          new JsonSchema("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}"));
    }

    private static MessageCreateParams withTools(List<ToolOffer> tools) {
      return withTools(tools, NONE);
    }

    private static MessageCreateParams withTools(
        List<ToolOffer> tools, Map<String, String> properties) {
      return AnthropicRequests.toParams(
          new InferenceRequest(
              SYSTEM, InferenceContext.of(List.of(open(1, "hi"))), Toolset.of(tools), options()),
          properties,
          MAPPER);
    }

    @Test
    void arrives_carrying_its_schema() {
      var tools = withTools(List.of(offer("lookup"))).tools().orElseThrow();

      assertThat(tools).hasSize(1);
      var tool = tools.getFirst().asTool();
      assertThat(tool.name()).isEqualTo("lookup");
      assertThat(tool.inputSchema().properties().orElseThrow()._additionalProperties())
          .containsKey("q");
    }

    /**
     * The declarations are one prefix, so the marker goes on the last of them and covers everything
     * before it. Marking every tool would spend four breakpoints on one prefix.
     */
    @Test
    void only_the_last_is_marked_for_caching_and_only_when_asked_for() {
      var cached =
          withTools(
                  List.of(offer("first"), offer("second")), caching(AnthropicCacheTtl.FIVE_MINUTES))
              .tools()
              .orElseThrow();
      assertThat(cached.get(0).asTool().cacheControl()).isEmpty();
      assertThat(cached.get(1).asTool().cacheControl()).isPresent();

      var plain = withTools(List.of(offer("first"), offer("second"))).tools().orElseThrow();
      assertThat(plain)
          .isNotEmpty()
          .allSatisfy(tool -> assertThat(tool.asTool().cacheControl()).isEmpty());
    }
  }

  @Nested
  class ConversationCaching {

    private static List<Turn> conversation(int turns) {
      return IntStream.rangeClosed(1, turns)
          .mapToObj(i -> answered(i, "question " + i, "answer " + i))
          .toList();
    }

    /** One call and its result, as many times over as asked. */
    private static List<Exchange> rounds(int count) {
      return IntStream.rangeClosed(1, count)
          .mapToObj(
              i ->
                  new Exchange(
                      new Seq(100L + i),
                      List.of(
                          new Block.ToolCall(
                              new CallId("call_" + i), new ToolName("lookup"), "{\"q\":\"x\"}")),
                      List.of(
                          new ToolOutcome.Succeeded(
                              new CallId("call_" + i), List.of(new Block.Text("result " + i))))))
          .toList();
    }

    private static Turn looping(long id, int rounds, TurnResult result) {
      return new Turn(new TurnId(id), asked(id, "question " + id), rounds(rounds), result, 0);
    }

    private static List<ContentBlockParam> cached(List<Turn> turns) {
      return blocksOf(params(turns, AnthropicCacheTtl.FIVE_MINUTES));
    }

    private static List<Integer> markedIn(List<ContentBlockParam> blocks) {
      return IntStream.range(0, blocks.size())
          .filter(i -> blocks.get(i).cacheControl().isPresent())
          .boxed()
          .toList();
    }

    /** Caching costs more to write than an ordinary token, so it is never on by default. */
    @Test
    void nothing_is_marked_when_caching_was_not_asked_for() {
      var blocks = blocksOf(params(conversation(15)));

      assertThat(blocks).isNotEmpty();
      assertThat(markedIn(blocks)).isEmpty();
    }

    @Test
    void a_first_question_is_marked_and_nothing_else_is() {
      var blocks = cached(List.of(open(1, "hi")));

      assertThat(markedIn(blocks)).containsExactly(0);
    }

    /**
     * Two markers, both on something settled: where this request ends, and where the last one did.
     * The second sits on the prefix the last request wrote, so the vendor can read it back however
     * much the round in between added.
     */
    @Test
    void the_end_of_this_request_and_the_end_of_the_last_one_are_marked() {
      // question 1, answer 1, question 2
      var blocks = cached(List.of(answered(1, "question 1", "answer 1"), open(2, "question 2")));

      assertThat(markedIn(blocks)).containsExactly(0, 2);
    }

    @Test
    void a_long_conversation_carries_the_same_two_and_no_more() {
      List<Turn> turns = new ArrayList<>(conversation(15));
      turns.add(open(16, "question 16"));

      var blocks = cached(turns);

      int last = blocks.size() - 1;
      assertThat(markedIn(blocks)).containsExactly(last - 2, last);
    }

    /** A result is as settled as a question: no call to the model is made until every one is in. */
    @Test
    void after_one_round_the_result_is_marked_and_so_is_the_question() {
      // question, call, result
      var blocks = cached(List.of(looping(1, 1, null)));

      assertThat(blocks.get(2).isToolResult()).isTrue();
      assertThat(markedIn(blocks)).containsExactly(0, 2);
    }

    @Test
    void in_a_tool_loop_the_newest_result_is_marked_and_so_is_the_one_before() {
      // question, call 1, result 1, call 2, result 2
      var blocks = cached(List.of(looping(1, 2, null)));

      assertThat(markedIn(blocks)).containsExactly(2, 4);
    }

    @Test
    void a_longer_loop_still_marks_only_its_last_two_results() {
      // question, then three rounds of call and result
      var blocks = cached(List.of(looping(1, 3, null)));

      assertThat(markedIn(blocks)).containsExactly(4, 6);
    }

    /** Several calls in one round come back as one message; its last result carries the marker. */
    @Test
    void results_that_come_back_together_are_marked_once_at_their_end() {
      Turn turn =
          new Turn(
              new TurnId(1),
              asked(1, "look both up"),
              List.of(
                  new Exchange(
                      new Seq(2),
                      List.of(
                          new Block.ToolCall(new CallId("a"), new ToolName("lookup"), "{}"),
                          new Block.ToolCall(new CallId("b"), new ToolName("lookup"), "{}")),
                      List.of(
                          new ToolOutcome.Succeeded(new CallId("a"), List.of(new Block.Text("1"))),
                          new ToolOutcome.Succeeded(
                              new CallId("b"), List.of(new Block.Text("2")))))),
              null,
              0);

      // question, call a, call b, result a, result b
      var blocks = cached(List.of(turn));

      assertThat(markedIn(blocks)).containsExactly(0, 4);
    }

    @Test
    void a_new_question_after_a_turn_that_used_tools_is_marked_with_that_turn_s_last_result() {
      // question 1, call 1, result 1, call 2, result 2, answer, question 2
      var blocks =
          cached(
              List.of(
                  looping(1, 2, new TurnResult.Answered(List.of(new Block.Text("done")))),
                  open(2, "question 2")));

      assertThat(markedIn(blocks)).containsExactly(4, 6);
    }

    @Test
    void a_new_question_after_a_turn_that_failed_is_marked_with_the_question_before_it() {
      // question 1, "(did not complete)", question 2
      var blocks =
          cached(
              List.of(
                  new Turn(
                      new TurnId(1), asked(1, "question 1"), List.of(), new TurnResult.Failed(), 0),
                  open(2, "question 2")));

      assertThat(markedIn(blocks)).containsExactly(0, 2);
    }

    /** A summary stands where turns once stood, and is marked like the user-side message it is. */
    @Test
    void a_summary_before_the_first_question_carries_the_second_marker() {
      Summary summary = new Summary(chapter(1, 5), "what came before");
      InferenceRequest request =
          new InferenceRequest(
              SYSTEM,
              new InferenceContext(List.of(summary), List.of(open(6, "question 6")), List.of()),
              Toolset.none(),
              options());

      // summary, question 6
      var blocks =
          blocksOf(
              AnthropicRequests.toParams(request, caching(AnthropicCacheTtl.FIVE_MINUTES), MAPPER));

      assertThat(markedIn(blocks)).containsExactly(0, 1);
    }

    /** A refused turn is left out whole, so the markers fall on what is actually sent. */
    @Test
    void a_refused_turn_in_between_is_skipped_over() {
      // question 1, answer 1, (turn 2 refused and omitted), question 3
      var blocks =
          cached(
              List.of(
                  answered(1, "question 1", "answer 1"),
                  new Turn(
                      new TurnId(2),
                      asked(2, "question 2"),
                      List.of(),
                      new TurnResult.Refused(),
                      0),
                  open(3, "question 3")));

      assertThat(blocks).hasSize(3);
      assertThat(markedIn(blocks)).containsExactly(0, 2);
    }

    /** The vendor rejects cache control on a thinking block, so the marker falls back. */
    @Test
    void a_breakpoint_never_lands_on_reasoning() {
      Turn turn =
          new Turn(
              new TurnId(1),
              asked(1, "how deep?"),
              List.of(),
              new TurnResult.Answered(
                  List.of(new Block.Text("1412 metres"), thinking("let me recall", "sig-abc"))),
              0);

      var blocks =
          blocksOf(
              AnthropicRequests.toParams(
                  request(List.of(turn)),
                  Map.of(
                      "anthropic.thinking.type", "adaptive",
                      "anthropic.cache_control.ttl", "FIVE_MINUTES"),
                  MAPPER));

      // question, answer text, reasoning
      assertThat(blocks.get(2).isThinking()).isTrue();
      assertThat(markedIn(blocks)).containsExactly(0, 1);
    }
  }

  @Nested
  class TheEdges {

    @Test
    void a_summary_stands_first_as_a_bracketed_user_message() {
      InferenceContext context =
          new InferenceContext(
              List.of(new Summary(chapter(1, 9), "they talked about lakes")),
              List.of(open(11, "and monsters?")),
              List.of());
      MessageCreateParams params =
          AnthropicRequests.toParams(
              new InferenceRequest(SYSTEM, context, Toolset.none(), options()), NONE, MAPPER);

      assertThat(params.messages()).hasSize(2);
      assertThat(params.messages().getFirst().role()).isEqualTo(MessageParam.Role.USER);
      assertThat(
              params
                  .messages()
                  .getFirst()
                  .content()
                  .blockParams()
                  .orElseThrow()
                  .getFirst()
                  .text()
                  .orElseThrow()
                  .text())
          .startsWith("<summary from=\"1\" through=\"9\">")
          .contains("they talked about lakes");
    }

    @Test
    void a_refused_turn_is_left_out_whole() {
      Turn refused =
          new Turn(new TurnId(1), asked(1, "rude"), List.of(), new TurnResult.Refused(), 0);
      Turn blank =
          new Turn(
              new TurnId(3),
              new Input(new Seq(3), List.of(new Block.Text("hi"))),
              List.of(),
              null,
              0);

      MessageCreateParams params = params(List.of(refused, blank));

      assertThat(params.messages()).hasSize(1);
      assertThat(params.messages().getFirst().content().blockParams().orElseThrow()).hasSize(1);
    }

    @Test
    void a_call_still_awaiting_its_results_is_sent_without_a_results_message() {
      Exchange asking =
          new Exchange(
              new Seq(2),
              List.of(
                  new Block.Commentary("thinking aloud"), new Block.ToolCall("c1", "lookup", "{}")),
              List.of());
      Turn turn = new Turn(new TurnId(1), asked(1, "go"), List.of(asking), null, 0);

      MessageCreateParams params = params(List.of(turn), AnthropicCacheTtl.FIVE_MINUTES);

      assertThat(params.messages()).hasSize(2);
      assertThat(params.messages().get(1).role()).isEqualTo(MessageParam.Role.ASSISTANT);
      assertThat(params.messages().get(1).content().blockParams().orElseThrow()).hasSize(2);
    }

    /** Another vendor's state is not ours to send, so the question is all there is to mark. */
    @Test
    void an_answer_of_nothing_but_another_vendor_s_state_leaves_the_question_marked() {
      Turn turn =
          new Turn(
              new TurnId(1),
              asked(1, "hi"),
              List.of(),
              new TurnResult.Answered(
                  List.of(new Block.Provider("someone-else", "{\"type\":\"thinking\"}"))),
              0);

      MessageCreateParams params = params(List.of(turn), AnthropicCacheTtl.ONE_HOUR);

      assertThat(params.messages()).hasSize(1);
      assertThat(blocksOf(params))
          .singleElement()
          .satisfies(block -> assertThat(block.cacheControl()).isPresent());
    }
  }

  /**
   * Whether the model may reach for what it was offered.
   *
   * <p>The default says nothing at all, because an absent field already means auto -- a request
   * written before this existed has to go out unchanged.
   */
  @Nested
  class ChoosingATool {

    private static ToolOffer offer(String name) {
      return new ToolOffer(
          new ToolName(name),
          "looks a thing up",
          new JsonSchema("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}"));
    }

    private static MessageCreateParams choosing(ToolChoice choice) {
      return AnthropicRequests.toParams(
          new InferenceRequest(
              SYSTEM,
              InferenceContext.of(List.of(open(1, "hello"))),
              new Toolset(List.of(offer("lookup")), choice),
              options()),
          NONE,
          MAPPER);
    }

    @Test
    void by_default_nothing_is_said_about_choosing() {
      assertThat(choosing(ToolChoice.auto()).toolChoice()).isEmpty();
    }

    @Test
    void a_ban_is_sent_as_a_ban() {
      assertThat(choosing(new ToolChoice.None()).toolChoice()).get().extracting("none").isNotNull();
    }

    @Test
    void requiring_some_tool_is_sent_as_any() {
      assertThat(choosing(new ToolChoice.Any()).toolChoice()).get().extracting("any").isNotNull();
    }

    @Test
    void requiring_one_tool_names_it() {
      assertThat(choosing(new ToolChoice.Named(new ToolName("lookup"))).toolChoice())
          .get()
          .extracting("tool")
          .isNotNull();
    }

    /**
     * Nothing to choose between, so nothing is said -- and the wire is not sent an empty rule.
     *
     * <p>This used to be asked with an empty offer and a required call, which Toolset now refuses
     * to build: requiring a call with nothing to call is unsatisfiable on every wire, so it is
     * caught once at construction rather than defended against four times here.
     */
    @Test
    void a_request_with_no_tools_says_nothing_about_choosing() {
      MessageCreateParams params =
          AnthropicRequests.toParams(
              new InferenceRequest(
                  SYSTEM,
                  InferenceContext.of(List.of(open(1, "hello"))),
                  Toolset.none(),
                  options()),
              NONE,
              MAPPER);

      assertThat(params.toolChoice()).isEmpty();
    }
  }

  @Nested
  class TheVendorProperties {

    private static InferenceRequest carrying(Map<String, String> agentType) {
      return new InferenceRequest(
          SYSTEM,
          InferenceContext.of(List.of(open(1, "hi"))),
          Toolset.none(),
          new InferenceOptions("claude-sonnet", 1024, agentType));
    }

    private static MessageCreateParams paramsFor(
        Map<String, String> provider, Map<String, String> agentType) {
      return AnthropicRequests.toParams(carrying(agentType), provider, MAPPER);
    }

    private static MessageCreateParams paramsFor(Map<String, String> agentType) {
      return paramsFor(Map.of(), agentType);
    }

    @Test
    void a_budget_alone_turns_thinking_on_with_that_budget() {
      MessageCreateParams params = paramsFor(Map.of("anthropic.thinking.budget_tokens", "512"));

      assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(512L);
    }

    @Test
    void enabled_with_a_budget_asks_for_that_budget() {
      MessageCreateParams params =
          paramsFor(
              Map.of(
                  "anthropic.thinking.type", "enabled", "anthropic.thinking.budget_tokens", "600"));

      assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(600L);
    }

    /** Plan ruling 9: disabled over a provider that thinks sends no thinking object at all. */
    @Test
    void disabled_sends_no_thinking_object_over_a_provider_that_thinks() {
      MessageCreateParams params =
          paramsFor(
              Map.of(
                  "anthropic.thinking.type", "enabled", "anthropic.thinking.budget_tokens", "512"),
              Map.of("anthropic.thinking.type", "disabled"));

      assertThat(params.thinking()).isEmpty();
      assertThat(params._additionalBodyProperties()).isEmpty();
    }

    @Test
    void adaptive_sends_the_adaptive_config() {
      MessageCreateParams params = paramsFor(Map.of("anthropic.thinking.type", "adaptive"));

      assertThat(params.thinking().orElseThrow().isAdaptive()).isTrue();
    }

    /** A thinking type is one of the three the wire knows; anything else is refused. */
    @Test
    void a_thinking_type_that_is_not_enabled_adaptive_or_disabled_is_refused() {
      Map<String, String> properties =
          Map.of(
              "anthropic.thinking.type", "interleaved", "anthropic.thinking.budget_tokens", "600");
      InferenceRequest request = carrying(properties);

      assertThatThrownBy(() -> AnthropicRequests.toParams(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "property 'anthropic.thinking.type' must be one of [ENABLED, DISABLED, ADAPTIVE],"
                  + " was 'interleaved'");
    }

    @Test
    void a_budget_with_no_headroom_under_the_ceiling_is_refused() {
      InferenceRequest request = carrying(Map.of("anthropic.thinking.budget_tokens", "1024"));

      assertThatThrownBy(() -> AnthropicRequests.toParams(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("maxTokens (1024) must be greater than the thinking budget (1024)");
    }

    @Test
    void a_one_hour_ttl_marks_the_prefix_for_an_hour() {
      CacheControlEphemeral marker =
          paramsFor(Map.of("anthropic.cache_control.ttl", "ONE_HOUR"))
              .system()
              .orElseThrow()
              .asTextBlockParams()
              .getFirst()
              .cacheControl()
              .orElseThrow();

      assertThat(marker.ttl()).contains(CacheControlEphemeral.Ttl.TTL_1H);
    }

    @Test
    void a_five_minute_ttl_marks_the_prefix_as_today() {
      CacheControlEphemeral marker =
          paramsFor(Map.of("anthropic.cache_control.ttl", "FIVE_MINUTES"))
              .system()
              .orElseThrow()
              .asTextBlockParams()
              .getFirst()
              .cacheControl()
              .orElseThrow();

      assertThat(marker.ttl()).isEmpty();
    }

    @Test
    void a_service_tier_lands_in_its_typed_field() {
      assertThat(
              paramsFor(Map.of("anthropic.service_tier", "auto"))
                  .serviceTier()
                  .map(MessageCreateParams.ServiceTier::asString))
          .contains("auto");
    }

    /** Names that were once refused as clashes are simply unsupported now: ignored, not sent. */
    @ParameterizedTest
    @ValueSource(
        strings = {
          "model",
          "max_tokens",
          "messages",
          "system",
          "tools",
          "tool_choice",
          "output_config",
          "output_config.effort",
          "stream",
          "thinking",
          "cache_control.type",
          "top_k",
          "metadata.user_id",
          "stop_sequences"
        })
    void an_unsupported_name_is_not_sent_and_the_request_is_as_if_it_were_not_given(String name) {
      MessageCreateParams with = paramsFor(Map.of("anthropic." + name, "1"));

      assertThat(with._additionalBodyProperties()).isEmpty();
      assertThat(with).isEqualTo(paramsFor(Map.of()));
      assertThat(with.maxTokens()).isEqualTo(1024L);
      assertThat(with.model().asString()).isEqualTo("claude-sonnet");
    }

    @Test
    void an_unsupported_name_beside_a_supported_one_leaves_the_supported_one_in_force() {
      MessageCreateParams params =
          paramsFor(
              Map.of(
                  "anthropic.max_tokens", "10",
                  "anthropic.thinking", "{\"type\":\"disabled\"}",
                  "anthropic.thinking.budget_tokens", "512"));

      assertThat(params.maxTokens()).isEqualTo(1024L);
      assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(512L);
      assertThat(params._additionalBodyProperties()).isEmpty();
    }

    @Test
    void reading_a_request_says_nothing_about_an_unsupported_name() {
      InferenceRequest request = carrying(Map.of("anthropic.top_k", "5"));

      List<ILoggingEvent> events =
          LogCapture.during(
              AnthropicPropertyReader.class,
              () -> {
                AnthropicRequests.toParams(request, Map.of(), MAPPER);
                AnthropicRequests.toParams(request, Map.of(), MAPPER);
              });

      assertThat(events).isEmpty();
    }

    @Test
    void an_unsupported_name_is_warned_once_naming_it_and_what_is_supported() {
      List<ILoggingEvent> events =
          LogCapture.during(
              AnthropicPropertyReader.class,
              () ->
                  AnthropicPropertyReader.warnUnsupported(
                      Map.of("anthropic.top_k", "5", "anthropic.service_tier", "auto")));

      assertThat(LogCapture.warnings(events))
          .containsExactly(
              "NESSY INFERENCE: property 'anthropic.top_k' is not supported by anthropic and is"
                  + " ignored; supported: [anthropic.cache_control.ttl, anthropic.service_tier,"
                  + " anthropic.thinking.budget_tokens, anthropic.thinking.type]");
    }

    @Test
    void another_prefix_is_not_sent() {
      MessageCreateParams params = paramsFor(Map.of("openai.reasoning.effort", "high"));

      assertThat(params._additionalBodyProperties()).isEmpty();
      assertThat(params.thinking()).isEmpty();
    }

    @Test
    void an_agent_type_budget_overrides_the_provider_s() {
      MessageCreateParams params =
          paramsFor(
              Map.of(
                  "anthropic.thinking.type", "enabled", "anthropic.thinking.budget_tokens", "512"),
              Map.of("anthropic.thinking.budget_tokens", "768"));

      assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(768L);
    }

    @Test
    void a_bad_budget_is_refused_naming_the_property_and_the_value() {
      InferenceRequest request = carrying(Map.of("anthropic.thinking.budget_tokens", "lots"));

      assertThatThrownBy(() -> AnthropicRequests.toParams(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property 'anthropic.thinking.budget_tokens' must be an integer, was 'lots'");
    }
  }

  /**
   * A request that does not think has no use for reasoning, and replaying it under a prefix that
   * changed is the one thing the vendor may refuse. Summarisers are the usual case: they send an
   * agent's turns, thinking included, under a prompt of their own and ask for no thinking.
   */
  @Nested
  class WhenTheRequestDoesNotThink {

    private static Turn thoughtThenAnswered() {
      return new Turn(
          new TurnId(1),
          asked(1, "how deep?"),
          List.of(),
          new TurnResult.Answered(
              List.of(
                  thinking("let me recall", "sig-abc"),
                  new Block.Text("1412 metres"),
                  new Block.Text("at its deepest"))),
          0);
    }

    private static Block.Provider redacted() {
      return new Block.Provider(
          "anthropic",
          MAPPER.writeValueAsString(Map.of("type", "redacted_thinking", "data", "opaque-bytes")));
    }

    @Test
    void signed_thinking_is_not_replayed_and_its_siblings_keep_their_order() {
      var blocks =
          params(List.of(thoughtThenAnswered())).messages().get(1).content().asBlockParams();

      assertThat(blocks).hasSize(2);
      assertThat(blocks.getFirst().asText().text()).isEqualTo("1412 metres");
      assertThat(blocks.getLast().asText().text()).isEqualTo("at its deepest");
    }

    @Test
    void redacted_thinking_is_not_replayed_either() {
      Turn turn =
          new Turn(
              new TurnId(1),
              asked(1, "how deep?"),
              List.of(),
              new TurnResult.Answered(List.of(redacted(), new Block.Text("1412 metres"))),
              0);

      var blocks = params(List.of(turn)).messages().get(1).content().asBlockParams();

      assertThat(blocks).hasSize(1);
      assertThat(blocks.getFirst().asText().text()).isEqualTo("1412 metres");
    }

    @Test
    void an_agent_type_that_switched_thinking_off_replays_none_of_the_history() {
      var params =
          AnthropicRequests.toParams(
              new InferenceRequest(
                  SYSTEM,
                  InferenceContext.of(List.of(thoughtThenAnswered())),
                  Toolset.none(),
                  new InferenceOptions(
                      "claude-sonnet", 1024, Map.of("anthropic.thinking.type", "disabled"))),
              THINKING,
              MAPPER);

      assertThat(params.thinking()).isEmpty();
      assertThat(blocksOf(params)).hasSize(3).noneMatch(ContentBlockParam::isThinking);
    }

    @Test
    void
        the_same_turn_replays_its_thinking_with_its_signature_when_the_request_thinks_adaptively() {
      var blocks =
          thinkingParams(List.of(thoughtThenAnswered()))
              .messages()
              .get(1)
              .content()
              .asBlockParams();

      assertThat(blocks).hasSize(3);
      assertThat(blocks.getFirst().asThinking().thinking()).isEqualTo("let me recall");
      assertThat(blocks.getFirst().asThinking().signature()).isEqualTo("sig-abc");
    }

    @Test
    void the_same_turn_replays_its_thinking_with_its_signature_when_the_request_has_a_budget() {
      var params =
          AnthropicRequests.toParams(
              new InferenceRequest(
                  SYSTEM,
                  InferenceContext.of(List.of(thoughtThenAnswered())),
                  Toolset.none(),
                  new InferenceOptions(
                      "claude-sonnet", 2048, Map.of("anthropic.thinking.budget_tokens", "512"))),
              NONE,
              MAPPER);

      var blocks = params.messages().get(1).content().asBlockParams();

      assertThat(blocks).hasSize(3);
      assertThat(blocks.getFirst().asThinking().signature()).isEqualTo("sig-abc");
    }

    /** A message of nothing is rejected by the vendor, so one left empty is not sent at all. */
    @Test
    void a_message_that_was_only_thinking_is_left_out_rather_than_sent_empty() {
      Turn turn =
          new Turn(
              new TurnId(1),
              asked(1, "how deep?"),
              List.of(),
              new TurnResult.Answered(List.of(thinking("let me recall", "sig-abc"))),
              0);

      var messages = params(List.of(turn)).messages();

      assertThat(messages).hasSize(1);
      assertThat(messages.getFirst().role()).isEqualTo(MessageParam.Role.USER);
    }

    @Test
    void an_exchange_that_was_only_thinking_before_its_results_leaves_no_empty_message() {
      Turn turn =
          new Turn(
              new TurnId(1),
              asked(1, "how deep?"),
              List.of(
                  new Exchange(
                      new Seq(2),
                      List.of(thinking("let me recall", "sig-abc")),
                      List.of(
                          new ToolOutcome.Succeeded(
                              new CallId("call_1"), List.of(new Block.Text("1412 metres")))))),
              null,
              0);

      var messages = params(List.of(turn)).messages();

      assertThat(messages).isNotEmpty();
      assertThat(messages)
          .allSatisfy(message -> assertThat(message.content().asBlockParams()).isNotEmpty());
      assertThat(blocksOf(params(List.of(turn)))).noneMatch(ContentBlockParam::isThinking);
    }
  }

  /**
   * Anthropic binds a thinking block to everything that came before it, and rejects the request
   * when that prefix has changed. Asked to, it drops the block instead and answers.
   */
  @Nested
  class WhenThePrefixChangesUnderReplayedThinking {

    private static final JsonValue DROP_MISMATCHED =
        JsonValue.from(Map.of("prefix_mismatch_behavior", "drop_block"));

    private static MessageCreateParams thinkingWith(Map<String, String> properties) {
      return AnthropicRequests.toParams(
          new InferenceRequest(
              SYSTEM,
              InferenceContext.of(List.of(open(1, "hi"))),
              Toolset.none(),
              new InferenceOptions("claude-sonnet", 2048, properties)),
          NONE,
          MAPPER);
    }

    @Test
    void adaptive_thinking_asks_for_mismatched_blocks_to_be_dropped() {
      MessageCreateParams params = thinkingWith(Map.of("anthropic.thinking.type", "adaptive"));

      assertThat(params.thinking().orElseThrow().asAdaptive()._additionalProperties())
          .containsEntry("block_binding", DROP_MISMATCHED);
    }

    /** Haiku 4.5 takes a budget and refuses adaptive, so the setting has to ride on both. */
    @Test
    void budgeted_thinking_asks_for_the_same() {
      MessageCreateParams params = thinkingWith(Map.of("anthropic.thinking.budget_tokens", "512"));

      assertThat(params.thinking().orElseThrow().asEnabled()._additionalProperties())
          .containsEntry("block_binding", DROP_MISMATCHED);
      assertThat(params.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(512L);
    }

    /**
     * The beta header the setting needs is applied by the provider, where it is added beside the
     * client's own. On the params it would replace them.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {"anthropic.thinking.type=adaptive", "anthropic.thinking.budget_tokens=512"})
    void the_params_leave_the_beta_header_to_the_provider(String property) {
      String[] pair = property.split("=");

      MessageCreateParams params = thinkingWith(Map.of(pair[0], pair[1]));

      assertThat(params._additionalHeaders().names()).doesNotContain("anthropic-beta");
    }

    @Test
    void a_request_that_does_not_think_sends_neither() {
      MessageCreateParams params = thinkingWith(Map.of());

      assertThat(params.thinking()).isEmpty();
      assertThat(params._additionalHeaders().names()).doesNotContain("anthropic-beta");
    }

    @Test
    void thinking_switched_off_by_the_agent_type_sends_neither() {
      MessageCreateParams params =
          AnthropicRequests.toParams(
              new InferenceRequest(
                  SYSTEM,
                  InferenceContext.of(List.of(open(1, "hi"))),
                  Toolset.none(),
                  new InferenceOptions(
                      "claude-sonnet", 2048, Map.of("anthropic.thinking.type", "disabled"))),
              Map.of("anthropic.thinking.type", "adaptive"),
              MAPPER);

      assertThat(params.thinking()).isEmpty();
      assertThat(params._additionalHeaders().names()).doesNotContain("anthropic-beta");
    }
  }

  /**
   * Inside one turn, each call must be the last one plus what happened since, for the blocks up to
   * the marked one; these requests carry no ambient, which is the only thing that moves. Anything
   * else is an edit to the prefix, and an edit costs the model the reasoning it did earlier in the
   * turn.
   *
   * <p>These requests are uncached, and say nothing about cached ones: with {@code
   * anthropic.cache_control.ttl} set the cache marker moves to a later block as the turn grows, so
   * a cached request is not byte-identical to the last one. Anthropic documents adding, moving or
   * removing cache markers as valid under replayed thinking, and a tool loop with the tail marker
   * moving on every call, thinking replayed and the prefix check enforced was run live on
   * 2026-10-01 without a rejection on Sonnet 5.5 or Fable 5.1.
   */
  @Nested
  class ATurnThatGrows {

    private static final Block.ToolCall LOOKUP =
        new Block.ToolCall(new CallId("call_1"), new ToolName("lookup"), "{\"q\":\"loch ness\"}");

    private static Turn asking() {
      return new Turn(new TurnId(1), asked(1, "how deep is it?"), List.of(), null, 0);
    }

    private static Turn afterOneLookup() {
      return new Turn(
          new TurnId(1),
          asked(1, "how deep is it?"),
          List.of(
              new Exchange(
                  new Seq(2),
                  List.of(thinking("check the survey first", "sig-1"), LOOKUP),
                  List.of(
                      new ToolOutcome.Succeeded(
                          new CallId("call_1"), List.of(new Block.Text("1412 metres")))))),
          null,
          0);
    }

    @Test
    void the_next_call_starts_with_every_message_of_the_last_one() {
      List<MessageParam> before = params(List.of(asking())).messages();
      List<MessageParam> after = params(List.of(afterOneLookup())).messages();

      assertThat(before).isNotEmpty();
      assertThat(after).hasSizeGreaterThan(before.size());
      assertThat(after.subList(0, before.size())).isEqualTo(before);
    }

    @Test
    void the_turn_in_flight_keeps_the_reasoning_it_did_before_the_call() {
      List<ContentBlockParam> asked =
          thinkingParams(List.of(afterOneLookup())).messages().get(1).content().asBlockParams();

      assertThat(asked).hasSize(2);
      assertThat(asked.getFirst().asThinking().signature()).isEqualTo("sig-1");
      assertThat(asked.getLast().asToolUse().id()).isEqualTo("call_1");
    }

    /** An earlier turn is history by then, and must not be re-rendered either. */
    @Test
    void a_finished_turn_before_it_is_rendered_the_same_on_both_calls() {
      Turn earlier = answered(1, "what is the loch called?", "Loch Ness");
      Turn open = new Turn(new TurnId(2), asked(3, "how deep is it?"), List.of(), null, 0);
      Turn grown =
          new Turn(
              new TurnId(2),
              asked(3, "how deep is it?"),
              List.of(
                  new Exchange(
                      new Seq(4),
                      List.of(LOOKUP),
                      List.of(
                          new ToolOutcome.Succeeded(
                              new CallId("call_1"), List.of(new Block.Text("1412 metres")))))),
              null,
              0);

      List<MessageParam> before = params(List.of(earlier, open)).messages();
      List<MessageParam> after = params(List.of(earlier, grown)).messages();

      assertThat(before).hasSize(3);
      assertThat(after.subList(0, before.size())).isEqualTo(before);
    }
  }
}
