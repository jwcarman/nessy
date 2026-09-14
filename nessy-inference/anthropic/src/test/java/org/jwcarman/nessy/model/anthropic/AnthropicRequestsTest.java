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
package org.jwcarman.nessy.model.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.block.CommentaryBlock;
import org.jwcarman.nessy.api.block.ImageBlock;
import org.jwcarman.nessy.api.block.ProviderBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.block.ToolResultBlock;
import org.jwcarman.nessy.api.message.AmbientMessage;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ContextMessage;
import org.jwcarman.nessy.api.message.ExchangeMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.model.anthropic.AnthropicRequests.ThinkingConfig;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelRequest;

class AnthropicRequestsTest {
  /** Anthropic's own opaque payload, as its stream would have emitted it. */
  private static ProviderBlock providerState(String type, String thinking, String signature) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("type", type);
    payload.put("thinking", thinking);
    payload.put("signature", signature);
    return new ProviderBlock("anthropic", payload);
  }

  private static ProviderBlock redactedState(String data) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("type", "redacted_thinking");
    payload.put("data", data);
    return new ProviderBlock("anthropic", payload);
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ThinkingConfig THINKING_DISABLED = new ThinkingConfig(false, 0);

  private static ModelRequest request(List<ContextMessage> messages, Set<Capability> requested) {
    return new ModelRequest(
        Context.of(messages), "you are a helpful assistant", 1024, List.of(), requested);
  }

  private static ModelRequest request(List<ContextMessage> messages) {
    return request(messages, Set.of());
  }

  private static ModelRequest requestWithSystemPrompt(String systemPrompt) {
    return new ModelRequest(Context.of(List.of()), systemPrompt, 1024, List.of(), Set.of());
  }

  @Nested
  class SystemPrompt {

    @Test
    void becomes_a_system_text_block() {
      var params =
          AnthropicRequests.toParams(request(List.of()), "claude-sonnet", THINKING_DISABLED);

      var systemBlocks = params.system().orElseThrow().asTextBlockParams();
      assertThat(systemBlocks).hasSize(1);
      assertThat(systemBlocks.get(0).text()).isEqualTo("you are a helpful assistant");
    }

    @Test
    void has_no_cache_control_when_prompt_caching_is_not_requested() {
      var params =
          AnthropicRequests.toParams(request(List.of()), "claude-sonnet", THINKING_DISABLED);

      var systemBlock = params.system().orElseThrow().asTextBlockParams().get(0);
      assertThat(systemBlock.cacheControl()).isEmpty();
    }

    @Test
    void gets_ephemeral_cache_control_when_prompt_caching_is_requested() {
      var params =
          AnthropicRequests.toParams(
              request(List.of(), Set.of(Capability.PROMPT_CACHING)),
              "claude-sonnet",
              THINKING_DISABLED);

      var systemBlock = params.system().orElseThrow().asTextBlockParams().get(0);
      assertThat(systemBlock.cacheControl()).isPresent();
    }

    @Test
    void a_blank_system_prompt_omits_the_system_field_entirely() {
      var params =
          AnthropicRequests.toParams(
              requestWithSystemPrompt(""), "claude-sonnet", THINKING_DISABLED);

      assertThat(params.system()).isEmpty();
    }

    @Test
    void a_whitespace_only_system_prompt_omits_the_system_field_entirely() {
      var params =
          AnthropicRequests.toParams(
              requestWithSystemPrompt("   "), "claude-sonnet", THINKING_DISABLED);

      assertThat(params.system()).isEmpty();
    }
  }

  /**
   * Background the caller never said, folded into the top-level {@code system} field alongside the
   * standing instruction — Anthropic's prompt guidance asks for XML tags to mark a section, and it
   * never appears among the conversation's own messages.
   */
  @Nested
  class AmbientMessages {

    @Test
    void a_non_blank_ambient_message_becomes_its_own_xml_tagged_system_block() {
      var ambient = new AmbientMessage("standing-plan", List.of(new TextBlock("water the plants")));
      var params =
          AnthropicRequests.toParams(request(List.of(ambient)), "claude-sonnet", THINKING_DISABLED);

      var systemBlocks = params.system().orElseThrow().asTextBlockParams();
      assertThat(systemBlocks).hasSize(2);
      assertThat(systemBlocks.get(0).text()).isEqualTo("you are a helpful assistant");
      assertThat(systemBlocks.get(1).text())
          .isEqualTo("<standing-plan>\nwater the plants\n</standing-plan>");
    }

    @Test
    void a_blank_ambient_message_contributes_no_system_block() {
      var ambient = new AmbientMessage("empty-note", List.of(new TextBlock("   ")));
      var params =
          AnthropicRequests.toParams(request(List.of(ambient)), "claude-sonnet", THINKING_DISABLED);

      var systemBlocks = params.system().orElseThrow().asTextBlockParams();
      assertThat(systemBlocks).hasSize(1);
      assertThat(systemBlocks.get(0).text()).isEqualTo("you are a helpful assistant");
    }

    @Test
    void an_ambient_message_produces_no_message_in_the_conversation_itself() {
      var ambient = new AmbientMessage("standing-plan", List.of(new TextBlock("water the plants")));
      var params =
          AnthropicRequests.toParams(request(List.of(ambient)), "claude-sonnet", THINKING_DISABLED);

      assertThat(params.messages()).isEmpty();
    }
  }

  @Nested
  class ModelAndMaxTokens {

    @Test
    void pass_through_unchanged() {
      var params =
          AnthropicRequests.toParams(request(List.of()), "claude-sonnet", THINKING_DISABLED);

      assertThat(params.model().asString()).isEqualTo("claude-sonnet");
      assertThat(params.maxTokens()).isEqualTo(1024L);
    }
  }

  @Nested
  class UserTextMessages {

    @Test
    void become_a_user_message_with_a_text_block() {
      var params =
          AnthropicRequests.toParams(
              request(List.of(UserMessage.of("hello there"))), "claude-sonnet", THINKING_DISABLED);

      assertThat(params.messages()).hasSize(1);
      var message = params.messages().get(0);
      assertThat(message.role()).isEqualTo(MessageParam.Role.USER);
      var blocks = message.content().asBlockParams();
      assertThat(blocks).hasSize(1);
      assertThat(blocks.get(0).isText()).isTrue();
      assertThat(blocks.get(0).asText().text()).isEqualTo("hello there");
    }

    @Test
    void an_empty_text_block_is_dropped_leaving_the_message_elided() {
      // Anthropic rejects empty text blocks, so toContentBlockParam drops one outright; with no
      // other content, the whole message translates to nothing.
      var params =
          AnthropicRequests.toParams(
              request(List.of(new UserMessage(List.of(new TextBlock(""))))),
              "claude-sonnet",
              THINKING_DISABLED);

      assertThat(params.messages()).isEmpty();
    }

    @Test
    void an_empty_text_block_alongside_other_content_is_dropped_leaving_its_sibling() {
      var image = new org.jwcarman.nessy.api.block.ImageBlock("image/png", "aGVsbG8=");
      var params =
          AnthropicRequests.toParams(
              request(List.of(new UserMessage(List.of(new TextBlock(""), image)))),
              "claude-sonnet",
              THINKING_DISABLED);

      var blocks = params.messages().get(0).content().asBlockParams();
      assertThat(blocks).hasSize(1);
      assertThat(blocks.get(0).isImage()).isTrue();
    }
  }

  @Nested
  class ImageBlocks {

    @Test
    void become_a_user_image_block_with_a_base64_source() {
      var image = new ImageBlock("image/png", "aGVsbG8=");
      var params =
          AnthropicRequests.toParams(
              request(List.of(new UserMessage(List.of(image)))),
              "claude-sonnet",
              THINKING_DISABLED);

      var block = params.messages().get(0).content().asBlockParams().get(0);
      assertThat(block.isImage()).isTrue();
      var source = block.asImage().source().base64().orElseThrow();
      assertThat(source.mediaType()).isEqualTo(Base64ImageSource.MediaType.IMAGE_PNG);
      assertThat(source.data()).isEqualTo("aGVsbG8=");
    }
  }

  @Nested
  class AssistantThinkingBlocks {

    @Test
    void a_signed_thinking_block_round_trips_with_its_signature() {
      var thinking = providerState("thinking", "reasoning about the answer", "sig-123");
      var params =
          AnthropicRequests.toParams(
              request(List.of(new AnswerMessage(List.of(thinking)))),
              "claude-sonnet",
              THINKING_DISABLED);

      var blocks = params.messages().get(0).content().asBlockParams();
      assertThat(blocks).hasSize(1);
      assertThat(blocks.get(0).isThinking()).isTrue();
      assertThat(blocks.get(0).asThinking().thinking()).isEqualTo("reasoning about the answer");
      assertThat(blocks.get(0).asThinking().signature()).isEqualTo("sig-123");
    }

    @Test
    void an_unsigned_thinking_block_is_dropped_on_replay() {
      var unsigned = providerState("thinking", "reasoning that predates signing", "");
      var text = new TextBlock("the visible answer");
      var params =
          AnthropicRequests.toParams(
              request(List.of(new AnswerMessage(List.of(unsigned, text)))),
              "claude-sonnet",
              THINKING_DISABLED);

      var blocks = params.messages().get(0).content().asBlockParams();
      assertThat(blocks).hasSize(1);
      assertThat(blocks.get(0).isText()).isTrue();
    }

    /**
     * Reachable scenario: thinking cut off by {@code max_tokens} before its signature arrived
     * settles as a single-block assistant message ({@code ThinkingBlock} with an empty signature).
     * {@code toContentBlockParam} drops that one block, so the message itself must be elided
     * outright rather than sent as a param with an empty {@code content} array (which Anthropic
     * rejects).
     */
    @Test
    void an_assistant_message_of_only_an_unsigned_thinking_block_produces_no_message_param() {
      var unsigned = providerState("thinking", "cut off before signing", "");
      var params =
          AnthropicRequests.toParams(
              request(List.of(new AnswerMessage(List.of(unsigned)))),
              "claude-sonnet",
              THINKING_DISABLED);

      assertThat(params.messages()).isEmpty();
    }

    @Test
    void a_mixed_message_keeps_its_surviving_blocks_in_order() {
      var unsigned = providerState("thinking", "cut off before signing", "");
      var toolUse =
          new ToolCallBlock(
              new ToolCall(CallId.of("call-1"), "read_file", MAPPER.createObjectNode()));
      var said = new CommentaryBlock("the visible answer");
      var assistantMessage =
          new ExchangeMessage(
              List.of(unsigned, toolUse, said),
              List.of(ToolResultBlock.of(CallId.of("call-1"), ToolResult.ok("ok"))));
      var params =
          AnthropicRequests.toParams(
              request(List.of(assistantMessage)), "claude-sonnet", THINKING_DISABLED);

      var blocks = params.messages().get(0).content().asBlockParams();
      assertThat(blocks).hasSize(2);
      assertThat(blocks.get(0).isToolUse()).isTrue();
      assertThat(blocks.get(1).isText()).isTrue();
    }

    @Test
    void an_empty_commentary_block_is_dropped_leaving_its_siblings_in_order() {
      var empty = new CommentaryBlock("");
      var toolUse =
          new ToolCallBlock(
              new ToolCall(CallId.of("call-1"), "read_file", MAPPER.createObjectNode()));
      var said = new CommentaryBlock("the visible answer");
      var assistantMessage =
          new ExchangeMessage(
              List.of(empty, said, toolUse),
              List.of(ToolResultBlock.of(CallId.of("call-1"), ToolResult.ok("ok"))));
      var params =
          AnthropicRequests.toParams(
              request(List.of(assistantMessage)), "claude-sonnet", THINKING_DISABLED);

      var blocks = params.messages().get(0).content().asBlockParams();
      assertThat(blocks).hasSize(2);
      assertThat(blocks.get(0).isText()).isTrue();
      assertThat(blocks.get(0).asText().text()).isEqualTo("the visible answer");
      assertThat(blocks.get(1).isToolUse()).isTrue();
    }

    /** Opaque state belongs to whoever issued it; a rival provider's payload means nothing here. */
    @Test
    void another_providers_thinking_shaped_state_is_ignored_on_replay() {
      var foreign = providerState("thinking", "reasoning about the answer", "sig-123");
      var foreignBlock = new ProviderBlock("gcp.gemini", foreign.data());
      var text = new TextBlock("the visible answer");
      var params =
          AnthropicRequests.toParams(
              request(List.of(new AnswerMessage(List.of(foreignBlock, text)))),
              "claude-sonnet",
              THINKING_DISABLED);

      var blocks = params.messages().get(0).content().asBlockParams();
      assertThat(blocks).hasSize(1);
      assertThat(blocks.get(0).isText()).isTrue();
    }

    /** Our own provider, but a payload shape this adapter has no case for. */
    @Test
    void our_own_state_of_an_unrecognized_type_is_dropped() {
      var unrecognized = providerState("summary", "not a thinking block", "sig-123");
      var text = new TextBlock("the visible answer");
      var params =
          AnthropicRequests.toParams(
              request(List.of(new AnswerMessage(List.of(unrecognized, text)))),
              "claude-sonnet",
              THINKING_DISABLED);

      var blocks = params.messages().get(0).content().asBlockParams();
      assertThat(blocks).hasSize(1);
      assertThat(blocks.get(0).isText()).isTrue();
    }
  }

  @Nested
  class AssistantToolUseBlocks {

    @Test
    void become_a_tool_use_block_with_the_call_id_name_and_arguments() {
      ObjectNode arguments = MAPPER.createObjectNode();
      arguments.put("path", "README.md");
      var toolUse = new ToolCallBlock(new ToolCall(CallId.of("call-1"), "read_file", arguments));
      var assistantMessage =
          new ExchangeMessage(
              List.of(toolUse),
              List.of(ToolResultBlock.of(CallId.of("call-1"), ToolResult.ok("ok"))));
      var params =
          AnthropicRequests.toParams(
              request(List.of(assistantMessage)), "claude-sonnet", THINKING_DISABLED);

      var block = params.messages().get(0).content().asBlockParams().get(0);
      assertThat(block.isToolUse()).isTrue();
      assertThat(block.asToolUse().id()).isEqualTo("call-1");
      assertThat(block.asToolUse().name()).isEqualTo("read_file");
      assertThat(block.asToolUse().input()._additionalProperties()).containsKey("path");
    }

    @Test
    void arguments_that_are_not_a_json_object_produce_no_additional_properties() {
      // ToolCall.arguments() is typed as JsonNode, not ObjectNode; a non-object node (an array,
      // here) must not blow up toInput — it simply carries no additional properties across.
      var arguments = MAPPER.createArrayNode().add("unexpected");
      var toolUse = new ToolCallBlock(new ToolCall(CallId.of("call-1"), "read_file", arguments));
      var assistantMessage =
          new ExchangeMessage(
              List.of(toolUse),
              List.of(ToolResultBlock.of(CallId.of("call-1"), ToolResult.ok("ok"))));
      var params =
          AnthropicRequests.toParams(
              request(List.of(assistantMessage)), "claude-sonnet", THINKING_DISABLED);

      var block = params.messages().get(0).content().asBlockParams().get(0);
      assertThat(block.asToolUse().input()._additionalProperties()).isEmpty();
    }
  }

  @Nested
  class RedactedThinkingBlocks {

    @Test
    void round_trip_their_opaque_data() {
      var redacted = redactedState("opaque-encrypted-payload");
      var params =
          AnthropicRequests.toParams(
              request(List.of(new AnswerMessage(List.of(redacted)))),
              "claude-sonnet",
              THINKING_DISABLED);

      var block = params.messages().get(0).content().asBlockParams().get(0);
      assertThat(block.isRedactedThinking()).isTrue();
      assertThat(block.asRedactedThinking().data()).isEqualTo("opaque-encrypted-payload");
    }
  }

  @Nested
  class ToolResultBlocks {

    @Test
    void become_a_user_tool_result_block_carrying_is_error() {
      var toolUse =
          new ToolCallBlock(
              new ToolCall(CallId.of("call-1"), "read_file", MAPPER.createObjectNode()));
      var result = ToolResultBlock.of(CallId.of("call-1"), ToolResult.error("file not found"));
      var params =
          AnthropicRequests.toParams(
              request(List.of(new ExchangeMessage(List.of(toolUse), List.of(result)))),
              "claude-sonnet",
              THINKING_DISABLED);

      var message = params.messages().get(1);
      assertThat(message.role()).isEqualTo(MessageParam.Role.USER);
      var block = message.content().asBlockParams().get(0);
      assertThat(block.isToolResult()).isTrue();
      assertThat(block.asToolResult().toolUseId()).isEqualTo("call-1");
      assertThat(block.asToolResult().isError()).contains(true);
    }

    @Test
    void a_successful_result_carries_is_error_false() {
      var toolUse =
          new ToolCallBlock(
              new ToolCall(CallId.of("call-2"), "read_file", MAPPER.createObjectNode()));
      var result = ToolResultBlock.of(CallId.of("call-2"), ToolResult.ok("42"));
      var params =
          AnthropicRequests.toParams(
              request(List.of(new ExchangeMessage(List.of(toolUse), List.of(result)))),
              "claude-sonnet",
              THINKING_DISABLED);

      var block = params.messages().get(1).content().asBlockParams().get(0);
      assertThat(block.asToolResult().isError()).contains(false);
    }
  }

  @Nested
  class Tools {

    private static StubTool toolSpec(String name) {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("type", "object");
      schema.putObject("properties");
      return new StubTool(name, "does things called " + name, schema);
    }

    @Test
    void convert_via_anthropic_schemas() {
      var request =
          new ModelRequest(
              Context.of(List.of()), "sys", 1024, List.of(toolSpec("read_file")), Set.of());
      var params = AnthropicRequests.toParams(request, "claude-sonnet", THINKING_DISABLED);

      var tools = params.tools().orElseThrow();
      assertThat(tools).hasSize(1);
      var tool = tools.get(0).asTool();
      assertThat(tool.name()).isEqualTo("read_file");
      assertThat(tool.description()).contains("does things called read_file");
      assertThat(tool.cacheControl()).isEmpty();
    }

    @Test
    void only_the_last_tool_gets_cache_control_when_prompt_caching_is_requested() {
      var request =
          new ModelRequest(
              Context.of(List.of()),
              "sys",
              1024,
              List.of(toolSpec("read_file"), toolSpec("write_file")),
              Set.of(Capability.PROMPT_CACHING));
      var params = AnthropicRequests.toParams(request, "claude-sonnet", THINKING_DISABLED);

      var tools = params.tools().orElseThrow();
      assertThat(tools.get(0).asTool().cacheControl()).isEmpty();
      assertThat(tools.get(1).asTool().cacheControl()).isPresent();
    }

    @Test
    void no_tool_gets_cache_control_when_prompt_caching_is_not_requested() {
      var request =
          new ModelRequest(
              Context.of(List.of()),
              "sys",
              1024,
              List.of(toolSpec("read_file"), toolSpec("write_file")),
              Set.of());
      var params = AnthropicRequests.toParams(request, "claude-sonnet", THINKING_DISABLED);

      var tools = params.tools().orElseThrow();
      assertThat(tools)
          .isNotEmpty()
          .allSatisfy(tool -> assertThat(tool.asTool().cacheControl()).isEmpty());
    }
  }

  @Nested
  class Thinking {

    @Test
    void enabled_sets_a_thinking_config_with_the_budget() {
      var params =
          AnthropicRequests.toParams(
              request(List.of()), "claude-sonnet", new ThinkingConfig(true, 512));

      var thinkingConfig = params.thinking().orElseThrow();
      assertThat(thinkingConfig.isEnabled()).isTrue();
      assertThat(thinkingConfig.asEnabled().budgetTokens()).isEqualTo(512L);
    }

    @Test
    void disabled_sets_no_thinking_config() {
      var params =
          AnthropicRequests.toParams(request(List.of()), "claude-sonnet", THINKING_DISABLED);

      assertThat(params.thinking()).isEmpty();
    }

    @Test
    void rejects_a_budget_that_leaves_no_headroom_under_max_tokens() {
      var request = new ModelRequest(Context.of(List.of()), "sys", 512, List.of(), Set.of());

      var thinkingConfig = new ThinkingConfig(true, 512);

      assertThatThrownBy(() -> AnthropicRequests.toParams(request, "claude-sonnet", thinkingConfig))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_a_budget_larger_than_max_tokens() {
      var request = new ModelRequest(Context.of(List.of()), "sys", 512, List.of(), Set.of());
      var thinkingConfig = new ThinkingConfig(true, 1024);

      assertThatThrownBy(() -> AnthropicRequests.toParams(request, "claude-sonnet", thinkingConfig))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accepts_a_budget_strictly_below_max_tokens() {
      var request = new ModelRequest(Context.of(List.of()), "sys", 513, List.of(), Set.of());

      var params =
          AnthropicRequests.toParams(request, "claude-sonnet", new ThinkingConfig(true, 512));

      assertThat(params.thinking()).isPresent();
    }

    /**
     * Pins the default-vs-default headroom fix: {@code AnthropicModelProvider}'s default thinking
     * budget (1024) must leave room under {@code AgentBuilder}'s default {@code maxTokens} (4096),
     * or THINKING-with-defaults throws on the very first send.
     */
    @Test
    void the_default_thinking_budget_leaves_headroom_under_the_default_max_tokens() {
      var request = new ModelRequest(Context.of(List.of()), "sys", 4096, List.of(), Set.of());

      var params =
          AnthropicRequests.toParams(request, "claude-sonnet", new ThinkingConfig(true, 1024));

      assertThat(params.thinking()).isPresent();
    }
  }

  /**
   * The soak's finding (2026-08-26): marking the system prompt and the last tool caches a prefix
   * that never grows, while the part that does grow — the transcript — was never marked at all, so
   * a long-running agent read nothing back on any round. These pin the conversation breakpoints
   * that fix it, against the rules Anthropic publishes: at most FOUR {@code cache_control}
   * breakpoints per request, and a lookback window of twenty content blocks per breakpoint.
   */
  @Nested
  class ConversationCaching {

    private static final int LOOKBACK = 20;

    private static List<ContextMessage> conversation(int messages) {
      return IntStream.range(0, messages)
          .mapToObj(
              i ->
                  i % 2 == 0
                      ? UserMessage.of("message number " + i)
                      : (ContextMessage)
                          new AnswerMessage(List.of(new TextBlock("message number " + i))))
          .toList();
    }

    private static List<ContentBlockParam> blocksOf(MessageCreateParams params) {
      return params.messages().stream()
          .flatMap(message -> message.content().asBlockParams().stream())
          .toList();
    }

    private static List<Integer> markedIn(List<ContentBlockParam> blocks) {
      return IntStream.range(0, blocks.size())
          .filter(i -> blocks.get(i).cacheControl().isPresent())
          .boxed()
          .toList();
    }

    private static ModelRequest cachedRequest(
        List<ContextMessage> messages, java.util.List<org.jwcarman.nessy.api.tool.Tool<?>> tools) {
      return new ModelRequest(
          Context.of(messages), "sys", 1024, tools, Set.of(Capability.PROMPT_CACHING));
    }

    @Test
    void no_message_block_is_marked_when_prompt_caching_is_not_requested() {
      var params =
          AnthropicRequests.toParams(request(conversation(30)), "claude-sonnet", THINKING_DISABLED);

      var blocks = blocksOf(params);
      assertThat(blocks).isNotEmpty();
      assertThat(markedIn(blocks)).isEmpty();
    }

    @Test
    void a_short_conversation_is_marked_only_on_its_final_block() {
      var params =
          AnthropicRequests.toParams(
              cachedRequest(conversation(4), List.of()), "claude-sonnet", THINKING_DISABLED);

      var blocks = blocksOf(params);
      assertThat(markedIn(blocks)).containsExactly(blocks.size() - 1);
    }

    @Test
    void a_long_conversation_is_also_marked_one_lookback_window_behind_the_final_block() {
      var params =
          AnthropicRequests.toParams(
              cachedRequest(conversation(30), List.of()), "claude-sonnet", THINKING_DISABLED);

      var blocks = blocksOf(params);
      int last = blocks.size() - 1;
      assertThat(markedIn(blocks)).containsExactly(last - LOOKBACK, last);
    }

    @Test
    void a_thinking_block_never_carries_a_breakpoint_so_the_marker_falls_back_to_the_text() {
      var messages =
          List.<ContextMessage>of(
              UserMessage.of("hello"),
              new AnswerMessage(
                  List.of(new TextBlock("answer"), providerState("thinking", "hmm", "signed"))));

      var params =
          AnthropicRequests.toParams(
              cachedRequest(messages, java.util.List.of()), "claude-sonnet", THINKING_DISABLED);

      var blocks = blocksOf(params);
      assertThat(blocks).hasSize(3);
      assertThat(markedIn(blocks)).containsExactly(1);
    }

    /**
     * Every block kind {@link AnthropicRequests#mayCarryCacheControl} calls eligible must actually
     * be chosen when it is the nearest one behind an ineligible tail block — text and thinking are
     * pinned above; this rounds out image, tool result, tool call and commentary.
     */
    @Test
    void an_image_block_may_carry_the_moving_breakpoint() {
      var messages =
          List.<ContextMessage>of(
              new UserMessage(List.of(new ImageBlock("image/png", "aGVsbG8="))),
              new AnswerMessage(List.of(providerState("thinking", "hmm", "signed"))));

      var params =
          AnthropicRequests.toParams(
              cachedRequest(messages, java.util.List.of()), "claude-sonnet", THINKING_DISABLED);

      var blocks = blocksOf(params);
      assertThat(blocks).hasSize(2);
      assertThat(markedIn(blocks)).containsExactly(0);
      assertThat(blocks.get(0).isImage()).isTrue();
    }

    @Test
    void a_tool_result_block_may_carry_the_moving_breakpoint() {
      var toolUse =
          new ToolCallBlock(new ToolCall(CallId.of("call-1"), "noop", MAPPER.createObjectNode()));
      var messages =
          List.<ContextMessage>of(
              new ExchangeMessage(
                  List.of(toolUse),
                  List.of(ToolResultBlock.of(CallId.of("call-1"), ToolResult.ok("ok")))),
              new AnswerMessage(List.of(providerState("thinking", "hmm", "signed"))));

      var params =
          AnthropicRequests.toParams(
              cachedRequest(messages, java.util.List.of()), "claude-sonnet", THINKING_DISABLED);

      var blocks = blocksOf(params);
      assertThat(blocks).hasSize(3);
      assertThat(markedIn(blocks)).containsExactly(1);
      assertThat(blocks.get(1).isToolResult()).isTrue();
    }

    /**
     * Neither a tool call nor commentary can ever be the conversation's absolute last block — each
     * always shares its {@link ExchangeMessage} with a {@link ToolResultBlock}, which lands after
     * it and is itself always eligible, so it always wins the {@code moving} breakpoint first. The
     * ANCHOR breakpoint, one lookback window behind, has no such constraint: this places one
     * directly on a tool-call block and, in the sibling test, on a commentary block, by controlling
     * exactly what sits {@code LOOKBACK} positions behind the tail.
     */
    @Test
    void a_tool_call_block_may_carry_the_anchor_breakpoint() {
      var toolUse =
          new ToolCallBlock(new ToolCall(CallId.of("call-1"), "noop", MAPPER.createObjectNode()));
      var opening =
          new ExchangeMessage(
              List.of(toolUse, providerState("thinking", "hmm", "signed")),
              List.of(ToolResultBlock.of(CallId.of("call-1"), ToolResult.ok("ok"))));
      // opening contributes 3 blocks: toolUse(0), thinking(1, ineligible), toolResult(2).
      var messages = new ArrayList<ContextMessage>();
      messages.add(opening);
      messages.addAll(conversation(19)); // 19 more eligible blocks: indices 3..21
      messages.add(
          new AnswerMessage(
              List.of(providerState("thinking", "trailing", "signed")))); // index 22, ineligible

      var params =
          AnthropicRequests.toParams(
              cachedRequest(messages, java.util.List.of()), "claude-sonnet", THINKING_DISABLED);

      var blocks = blocksOf(params);
      assertThat(blocks).hasSize(23);
      // moving falls back from the trailing thinking block (22) to the last conversation() block
      // (21); anchor, one lookback window behind moving, falls back from the exchange's own
      // thinking block (1) to the tool call that opened it (0).
      assertThat(markedIn(blocks)).containsExactly(0, 21);
      assertThat(blocks.get(0).isToolUse()).isTrue();
    }

    @Test
    void a_commentary_block_may_carry_the_anchor_breakpoint() {
      var toolUse =
          new ToolCallBlock(new ToolCall(CallId.of("call-1"), "noop", MAPPER.createObjectNode()));
      var commentary = new CommentaryBlock("thinking out loud");
      var opening =
          new ExchangeMessage(
              List.of(toolUse, commentary, providerState("thinking", "hmm", "signed")),
              List.of(ToolResultBlock.of(CallId.of("call-1"), ToolResult.ok("ok"))));
      // opening contributes 4 blocks: toolUse(0), commentary(1), thinking(2, ineligible),
      // toolResult(3).
      var messages = new ArrayList<ContextMessage>();
      messages.add(opening);
      messages.addAll(conversation(19)); // 19 more eligible blocks: indices 4..22
      messages.add(
          new AnswerMessage(
              List.of(providerState("thinking", "trailing", "signed")))); // index 23, ineligible

      var params =
          AnthropicRequests.toParams(
              cachedRequest(messages, java.util.List.of()), "claude-sonnet", THINKING_DISABLED);

      var blocks = blocksOf(params);
      assertThat(blocks).hasSize(24);
      // moving falls back from the trailing thinking block (23) to the last conversation() block
      // (22); anchor, one lookback window behind moving, falls back from the exchange's own
      // thinking block (2) to the commentary that preceded it (1).
      assertThat(markedIn(blocks)).containsExactly(1, 22);
      assertThat(blocks.get(1).isText()).isTrue();
    }

    /**
     * The hard ceiling: system (1) + last tool (1) + the two conversation breakpoints is exactly
     * four, which is all the API allows. A fifth is a 400 on every round.
     */
    @Test
    void the_whole_request_never_carries_more_than_four_breakpoints() {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("type", "object");
      schema.putObject("properties");
      var tools =
          List.<org.jwcarman.nessy.api.tool.Tool<?>>of(
              new StubTool("read_file", "reads", schema),
              new StubTool("write_file", "writes", schema));

      var params =
          AnthropicRequests.toParams(
              cachedRequest(conversation(60), tools), "claude-sonnet", THINKING_DISABLED);

      long system =
          params.system().orElseThrow().asTextBlockParams().stream()
              .filter(block -> block.cacheControl().isPresent())
              .count();
      long toolMarkers =
          params.tools().orElseThrow().stream()
              .filter(tool -> tool.asTool().cacheControl().isPresent())
              .count();

      assertThat(system + toolMarkers + markedIn(blocksOf(params)).size()).isEqualTo(4L);
    }
  }

  /**
   * The 30-minute-cron finding (2026-08-26): the default ephemeral entry lives five minutes, so on
   * the watchman's real cadence every entry has expired long before the next round starts and
   * cross-round reads are structurally impossible. Anthropic's answer is {@code "cache_control":
   * {"type": "ephemeral", "ttl": "1h"}}, at "2 times the base input tokens price" on writes and the
   * ordinary cheap rate on reads. {@link Capability#PROMPT_CACHING_1H} is how a caller asks.
   */
  @Nested
  class ExtendedCacheTtl {

    private static ModelRequest cachedRequest(Set<Capability> requested) {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("type", "object");
      schema.putObject("properties");
      return new ModelRequest(
          Context.of(conversation(60)),
          "sys",
          1024,
          List.<org.jwcarman.nessy.api.tool.Tool<?>>of(
              new StubTool("read_file", "reads", schema), new StubTool("write_file", "w", schema)),
          requested);
    }

    private static List<ContextMessage> conversation(int messages) {
      return IntStream.range(0, messages)
          .mapToObj(
              i ->
                  i % 2 == 0
                      ? UserMessage.of("message number " + i)
                      : (ContextMessage)
                          new AnswerMessage(List.of(new TextBlock("message number " + i))))
          .toList();
    }

    /** Every {@code cache_control} the request emits, wherever it sits: system, tools, messages. */
    private static List<CacheControlEphemeral> allCacheControls(MessageCreateParams params) {
      return Stream.of(
              params.system().orElseThrow().asTextBlockParams().stream()
                  .map(TextBlockParam::cacheControl),
              params.tools().orElseThrow().stream().map(tool -> tool.asTool().cacheControl()),
              params.messages().stream()
                  .flatMap(message -> message.content().asBlockParams().stream())
                  .map(ContentBlockParam::cacheControl))
          .flatMap(stream -> stream)
          .flatMap(Optional::stream)
          .toList();
    }

    @Test
    void every_cache_control_block_carries_a_one_hour_ttl_when_the_extended_ttl_is_requested() {
      var params =
          AnthropicRequests.toParams(
              cachedRequest(Set.of(Capability.PROMPT_CACHING, Capability.PROMPT_CACHING_1H)),
              "claude-sonnet",
              THINKING_DISABLED);

      var cacheControls = allCacheControls(params);
      assertThat(cacheControls)
          .hasSize(4)
          .allSatisfy(
              cacheControl ->
                  assertThat(cacheControl.ttl()).contains(CacheControlEphemeral.Ttl.TTL_1H));
    }

    /**
     * Asking for the long entry is asking for caching. Requiring both words would let {@code
     * nessy.capabilities: [prompt-caching-1h]} silently cache nothing at all, which is the one
     * misconfiguration a caller would never think to look for.
     */
    @Test
    void the_extended_ttl_alone_still_turns_caching_on() {
      var params =
          AnthropicRequests.toParams(
              cachedRequest(Set.of(Capability.PROMPT_CACHING_1H)),
              "claude-sonnet",
              THINKING_DISABLED);

      assertThat(allCacheControls(params)).hasSize(4);
    }

    /**
     * The default is the default: no {@code ttl} field at all, which Anthropic reads as the
     * five-minute entry. Sending {@code "5m"} explicitly would mean the same thing and say more
     * than the caller asked.
     */
    @Test
    void cache_control_blocks_carry_no_ttl_field_when_only_plain_caching_is_requested() {
      var params =
          AnthropicRequests.toParams(
              cachedRequest(Set.of(Capability.PROMPT_CACHING)), "claude-sonnet", THINKING_DISABLED);

      var cacheControls = allCacheControls(params);
      assertThat(cacheControls)
          .hasSize(4)
          .allSatisfy(cacheControl -> assertThat(cacheControl.ttl()).isEmpty());
    }

    /** The four-breakpoint ceiling is a property of the request, not of which TTL was asked for. */
    @Test
    void the_extended_ttl_never_pushes_the_request_past_four_breakpoints() {
      var withTtl =
          AnthropicRequests.toParams(
              cachedRequest(Set.of(Capability.PROMPT_CACHING, Capability.PROMPT_CACHING_1H)),
              "claude-sonnet",
              THINKING_DISABLED);
      var withoutTtl =
          AnthropicRequests.toParams(
              cachedRequest(Set.of(Capability.PROMPT_CACHING)), "claude-sonnet", THINKING_DISABLED);

      assertThat(allCacheControls(withTtl)).hasSizeLessThanOrEqualTo(4);
      assertThat(allCacheControls(withoutTtl)).hasSizeLessThanOrEqualTo(4);
    }
  }
}
