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

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
import org.jwcarman.nessy.inference.anthropic.AnthropicRequests.Features;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The projection onto Anthropic's Messages wire, with no network anywhere near it.
 *
 * <p>Two things live here that no other adapter has to think about: cache breakpoints, which are
 * money rather than correctness, and extended thinking, which only comes back to the vendor intact
 * if this code never touches it.
 */
class AnthropicRequestsTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Features NONE = Features.none();
  private static final SystemPrompt SYSTEM = new SystemPrompt("you are a helpful assistant");

  private static InferenceOptions options() {
    return new InferenceOptions("claude-sonnet", 1024);
  }

  private static InferenceRequest request(List<Turn> turns) {
    return new InferenceRequest(SYSTEM, InferenceContext.of(turns), Toolset.none(), options());
  }

  private static Features caching(PromptCaching caching) {
    return new Features(false, 0, caching);
  }

  private static MessageCreateParams params(List<Turn> turns) {
    return AnthropicRequests.toParams(request(turns), NONE, MAPPER);
  }

  private static MessageCreateParams params(List<Turn> turns, PromptCaching caching) {
    return AnthropicRequests.toParams(request(turns), caching(caching), MAPPER);
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

    /**
     * Its own block rather than concatenated, because this wire takes a list. An OpenAI-compatible
     * adapter has one string and must run them together; here they stay separable.
     */
    @Test
    void carries_each_ambient_section_as_its_own_labelled_block() {
      InferenceRequest request =
          new InferenceRequest(
              SYSTEM,
              new InferenceContext(
                  List.of(open(1, "hello")),
                  List.of(Ambient.text("notebook", "the deploy is frozen"))),
              Toolset.none(),
              options());

      var system =
          AnthropicRequests.toParams(request, NONE, MAPPER)
              .system()
              .orElseThrow()
              .asTextBlockParams();

      assertThat(system).hasSize(2);
      assertThat(system.get(1).text()).isEqualTo("<notebook>\nthe deploy is frozen\n</notebook>");
    }

    /** A heading with nothing under it tells a model its notebook is empty, which is a claim. */
    @Test
    void omits_an_ambient_section_that_says_nothing() {
      InferenceRequest request =
          new InferenceRequest(
              SYSTEM,
              new InferenceContext(
                  List.of(open(1, "hello")),
                  List.of(new Ambient("notebook", List.of(new Block.Text("   "))))),
              Toolset.none(),
              options());

      assertThat(
              AnthropicRequests.toParams(request, NONE, MAPPER)
                  .system()
                  .orElseThrow()
                  .asTextBlockParams())
          .hasSize(1);
    }

    @Test
    void is_never_a_message_in_the_conversation_itself() {
      InferenceRequest request =
          new InferenceRequest(
              SYSTEM,
              new InferenceContext(
                  List.of(open(1, "hello")), List.of(Ambient.text("clock", "it is Tuesday"))),
              Toolset.none(),
              options());

      assertThat(blocksOf(AnthropicRequests.toParams(request, NONE, MAPPER)))
          .noneSatisfy(block -> assertThat(block.asText().text()).contains("it is Tuesday"));
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
              params(List.of(open(1, "hi")), PromptCaching.FIVE_MINUTES)
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
          params(List.of(open(1, "hi")), PromptCaching.ONE_HOUR)
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

      var blocks = params(List.of(turn)).messages().get(1).content().asBlockParams();

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

      var blocks = params(List.of(turn)).messages().get(1).content().asBlockParams();

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

      var blocks = params(List.of(turn)).messages().get(1).content().asBlockParams();

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

      var blocks = params(List.of(turn)).messages().get(1).content().asBlockParams();

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

      assertThat(params(List.of(turn)).messages().get(1).content().asBlockParams()).hasSize(1);
    }

    @Test
    void enabled_asks_for_a_budget_and_disabled_asks_for_nothing() {
      var thinking =
          AnthropicRequests.toParams(
              request(List.of(open(1, "hi"))), new Features(true, 512, PromptCaching.OFF), MAPPER);
      assertThat(thinking.thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(512L);

      assertThat(params(List.of(open(1, "hi"))).thinking()).isEmpty();
    }

    /**
     * The budget is spent out of maxTokens, so a ceiling at or below it leaves nothing to answer
     * with. Refused here rather than at the wire, where it is a 400 with no hint.
     */
    @Test
    void a_budget_with_no_headroom_under_the_ceiling_is_refused_before_the_call() {
      var request = request(List.of(open(1, "hi")));
      var noHeadroom = new Features(true, 1024, PromptCaching.OFF);
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
      return withTools(tools, PromptCaching.OFF);
    }

    private static MessageCreateParams withTools(List<ToolOffer> tools, PromptCaching caching) {
      return AnthropicRequests.toParams(
          new InferenceRequest(
              SYSTEM, InferenceContext.of(List.of(open(1, "hi"))), Toolset.of(tools), options()),
          caching(caching),
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
          withTools(List.of(offer("first"), offer("second")), PromptCaching.FIVE_MINUTES)
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

    private static final int LOOKBACK = 20;

    private static List<Turn> conversation(int turns) {
      return IntStream.rangeClosed(1, turns)
          .mapToObj(i -> answered(i, "question " + i, "answer " + i))
          .toList();
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
    void a_short_conversation_is_marked_only_at_its_end() {
      var blocks = blocksOf(params(conversation(2), PromptCaching.FIVE_MINUTES));

      assertThat(markedIn(blocks)).containsExactly(blocks.size() - 1);
    }

    /**
     * The trailing marker is what actually earns anything. A single moving marker writes a new
     * prefix every turn and reads none of it back, because by the next turn the conversation has
     * moved past it.
     */
    @Test
    void a_long_one_is_also_marked_a_lookback_window_behind_the_end() {
      var blocks = blocksOf(params(conversation(15), PromptCaching.FIVE_MINUTES));

      int last = blocks.size() - 1;
      assertThat(markedIn(blocks)).containsExactly(last - LOOKBACK, last);
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

      var blocks = blocksOf(params(List.of(turn), PromptCaching.FIVE_MINUTES));

      assertThat(markedIn(blocks))
          .allSatisfy(i -> assertThat(blocks.get(i).isThinking()).isFalse());
      assertThat(markedIn(blocks)).isNotEmpty();
    }
  }

  @Nested
  class TheEdges {

    @Test
    void a_summary_stands_first_as_a_bracketed_user_message() {
      InferenceContext context =
          new InferenceContext(
              List.of(Summary.text(new TurnId(1), new TurnId(9), "they talked about lakes")),
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

      MessageCreateParams params = params(List.of(turn), PromptCaching.FIVE_MINUTES);

      assertThat(params.messages()).hasSize(2);
      assertThat(params.messages().get(1).role()).isEqualTo(MessageParam.Role.ASSISTANT);
      assertThat(params.messages().get(1).content().blockParams().orElseThrow()).hasSize(2);
    }

    @Test
    void a_conversation_of_nothing_but_provider_state_gets_no_breakpoint() {
      Turn turn =
          new Turn(
              new TurnId(1),
              asked(1, "hi"),
              List.of(),
              new TurnResult.Answered(
                  List.of(new Block.Provider("someone-else", "{\"type\":\"thinking\"}"))),
              0);

      MessageCreateParams params = params(List.of(turn), PromptCaching.ONE_HOUR);

      assertThat(params.messages()).hasSize(1);
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

    /** The pass-through body as it goes on the wire; the SDK's accessors erase the generics. */
    private static JsonNode additionalBody(MessageCreateParams params) throws Exception {
      return MAPPER.readTree(
          ObjectMappers.jsonMapper().writeValueAsString(params._additionalBodyProperties()));
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

    @Test
    void enabled_without_a_budget_is_refused_naming_both_properties() {
      InferenceRequest request = carrying(Map.of("anthropic.thinking.type", "enabled"));

      assertThatThrownBy(() -> AnthropicRequests.toParams(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'anthropic.thinking.type'")
          .hasMessageContaining("'anthropic.thinking.budget_tokens'");
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

    /** Plan ruling 9: the type is checked, the vocabulary is the vendor's. */
    @Test
    void a_type_nessy_does_not_know_goes_as_a_raw_thinking_object() throws Exception {
      MessageCreateParams params =
          paramsFor(
              Map.of(
                  "anthropic.thinking.type", "interleaved",
                  "anthropic.thinking.budget_tokens", "600"));

      assertThat(params.thinking()).isEmpty();
      JsonNode thinking = additionalBody(params).get("thinking");
      assertThat(thinking.get("type").isString()).isTrue();
      assertThat(thinking.get("type").asString()).isEqualTo("interleaved");
      assertThat(thinking.get("budget_tokens").isInt()).isTrue();
      assertThat(thinking.get("budget_tokens").intValue()).isEqualTo(600);
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
          paramsFor(Map.of("anthropic.cache_control.ttl", "1h"))
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
          paramsFor(Map.of("anthropic.cache_control.ttl", "5m"))
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

    @Test
    void an_unknown_name_passes_through_as_a_typed_literal_nested_by_path() throws Exception {
      JsonNode body =
          additionalBody(
              paramsFor(
                  Map.of(
                      "anthropic.top_k", "5",
                      "anthropic.metadata.user_id", "u-1",
                      "anthropic.stop_sequences", "[\"\\n\\n\"]")));

      assertThat(body.get("top_k").isInt()).isTrue();
      assertThat(body.get("top_k").intValue()).isEqualTo(5);
      assertThat(body.get("metadata").get("user_id").asString()).isEqualTo("u-1");
      assertThat(body.get("stop_sequences").isArray()).isTrue();
      assertThat(body.get("stop_sequences")).hasSize(1);
      assertThat(body.get("stop_sequences").get(0).asString()).isEqualTo("\n\n");
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
    void the_ceiling_is_refused_as_what_a_typed_setting_decides() {
      InferenceRequest request = carrying(Map.of("anthropic.max_tokens", "10"));

      assertThatThrownBy(() -> AnthropicRequests.toParams(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'anthropic.max_tokens'")
          .hasMessageContaining("InferenceConfig.maxTokens");
    }

    @Test
    void the_raw_thinking_object_beside_a_known_thinking_name_is_refused() {
      InferenceRequest request =
          carrying(
              Map.of(
                  "anthropic.thinking.budget_tokens", "512",
                  "anthropic.thinking", "{\"type\":\"enabled\"}"));

      assertThatThrownBy(() -> AnthropicRequests.toParams(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'anthropic.thinking'")
          .hasMessageContaining("'anthropic.thinking.budget_tokens'");
    }

    @Test
    void a_bad_budget_is_refused_naming_the_property_and_the_value() {
      InferenceRequest request = carrying(Map.of("anthropic.thinking.budget_tokens", "lots"));

      assertThatThrownBy(() -> AnthropicRequests.toParams(request, Map.of(), MAPPER))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property 'anthropic.thinking.budget_tokens' must be an integer, was 'lots'");
    }
  }
}
