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
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ImageBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.model.anthropic.AnthropicRequests.ThinkingConfig;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelRequest;

class AnthropicRequestsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ThinkingConfig THINKING_DISABLED = new ThinkingConfig(false, 0);

  private static ModelRequest request(List<Message> messages, Set<Capability> requested) {
    return new ModelRequest(
        Context.of(messages), "you are a helpful assistant", 1024, List.of(), requested, null);
  }

  private static ModelRequest request(List<Message> messages) {
    return request(messages, Set.of());
  }

  private static ModelRequest requestWithSystemPrompt(String systemPrompt) {
    return new ModelRequest(Context.of(List.of()), systemPrompt, 1024, List.of(), Set.of(), null);
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
              request(List.of(Message.user("hello there"))), "claude-sonnet", THINKING_DISABLED);

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
              request(List.of(Message.user(List.of(new TextBlock(""))))),
              "claude-sonnet",
              THINKING_DISABLED);

      assertThat(params.messages()).isEmpty();
    }

    @Test
    void an_empty_text_block_alongside_other_content_is_dropped_leaving_its_sibling() {
      var image = new org.jwcarman.nessy.api.message.ImageBlock("image/png", "aGVsbG8=");
      var params =
          AnthropicRequests.toParams(
              request(List.of(Message.user(List.of(new TextBlock(""), image)))),
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
              request(List.of(Message.user(List.of(image)))), "claude-sonnet", THINKING_DISABLED);

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
      var thinking = new ThinkingBlock("reasoning about the answer", "sig-123");
      var params =
          AnthropicRequests.toParams(
              request(List.of(Message.assistant(List.of(thinking)))),
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
      var unsigned = new ThinkingBlock("reasoning that predates signing", "");
      var text = new TextBlock("the visible answer");
      var params =
          AnthropicRequests.toParams(
              request(List.of(Message.assistant(List.of(unsigned, text)))),
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
      var unsigned = new ThinkingBlock("cut off before signing", "");
      var params =
          AnthropicRequests.toParams(
              request(List.of(Message.assistant(List.of(unsigned)))),
              "claude-sonnet",
              THINKING_DISABLED);

      assertThat(params.messages()).isEmpty();
    }

    @Test
    void a_mixed_message_keeps_its_surviving_blocks_in_order() {
      var unsigned = new ThinkingBlock("cut off before signing", "");
      var toolUse =
          new ToolUseBlock(new ToolCall("call-1", "read_file", MAPPER.createObjectNode()));
      var text = new TextBlock("the visible answer");
      var assistantMessage = Message.assistant(List.of(unsigned, toolUse, text));
      var toolResultMessage =
          Message.toolResults(List.of(new ToolResultBlock("call-1", "ok", false)));
      var params =
          AnthropicRequests.toParams(
              request(List.of(assistantMessage, toolResultMessage)),
              "claude-sonnet",
              THINKING_DISABLED);

      var blocks = params.messages().get(0).content().asBlockParams();
      assertThat(blocks).hasSize(2);
      assertThat(blocks.get(0).isToolUse()).isTrue();
      assertThat(blocks.get(1).isText()).isTrue();
    }
  }

  @Nested
  class AssistantToolUseBlocks {

    @Test
    void become_a_tool_use_block_with_the_call_id_name_and_arguments() {
      ObjectNode arguments = MAPPER.createObjectNode();
      arguments.put("path", "README.md");
      var toolUse = new ToolUseBlock(new ToolCall("call-1", "read_file", arguments));
      var assistantMessage = Message.assistant(List.of(toolUse));
      var toolResultMessage =
          Message.toolResults(List.of(new ToolResultBlock("call-1", "ok", false)));
      var params =
          AnthropicRequests.toParams(
              request(List.of(assistantMessage, toolResultMessage)),
              "claude-sonnet",
              THINKING_DISABLED);

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
      var toolUse = new ToolUseBlock(new ToolCall("call-1", "read_file", arguments));
      var assistantMessage = Message.assistant(List.of(toolUse));
      var toolResultMessage =
          Message.toolResults(List.of(new ToolResultBlock("call-1", "ok", false)));
      var params =
          AnthropicRequests.toParams(
              request(List.of(assistantMessage, toolResultMessage)),
              "claude-sonnet",
              THINKING_DISABLED);

      var block = params.messages().get(0).content().asBlockParams().get(0);
      assertThat(block.asToolUse().input()._additionalProperties()).isEmpty();
    }
  }

  @Nested
  class RedactedThinkingBlocks {

    @Test
    void round_trip_their_opaque_data() {
      var redacted = new RedactedThinkingBlock("opaque-encrypted-payload");
      var params =
          AnthropicRequests.toParams(
              request(List.of(Message.assistant(List.of(redacted)))),
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
          new ToolUseBlock(new ToolCall("call-1", "read_file", MAPPER.createObjectNode()));
      var result = new ToolResultBlock("call-1", "file not found", true);
      var params =
          AnthropicRequests.toParams(
              request(
                  List.of(
                      Message.assistant(List.of(toolUse)), Message.toolResults(List.of(result)))),
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
          new ToolUseBlock(new ToolCall("call-2", "read_file", MAPPER.createObjectNode()));
      var result = new ToolResultBlock("call-2", "42", false);
      var params =
          AnthropicRequests.toParams(
              request(
                  List.of(
                      Message.assistant(List.of(toolUse)), Message.toolResults(List.of(result)))),
              "claude-sonnet",
              THINKING_DISABLED);

      var block = params.messages().get(1).content().asBlockParams().get(0);
      assertThat(block.asToolResult().isError()).contains(false);
    }
  }

  @Nested
  class Tools {

    private static ToolSpec toolSpec(String name) {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("type", "object");
      schema.putObject("properties");
      return new ToolSpec(name, "does things called " + name, schema);
    }

    @Test
    void convert_via_anthropic_schemas() {
      var request =
          new ModelRequest(
              Context.of(List.of()), "sys", 1024, List.of(toolSpec("read_file")), Set.of(), null);
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
              Set.of(Capability.PROMPT_CACHING),
              null);
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
              Set.of(),
              null);
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
      var request = new ModelRequest(Context.of(List.of()), "sys", 512, List.of(), Set.of(), null);

      var thinkingConfig = new ThinkingConfig(true, 512);

      assertThatThrownBy(() -> AnthropicRequests.toParams(request, "claude-sonnet", thinkingConfig))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_a_budget_larger_than_max_tokens() {
      var request = new ModelRequest(Context.of(List.of()), "sys", 512, List.of(), Set.of(), null);
      var thinkingConfig = new ThinkingConfig(true, 1024);

      assertThatThrownBy(() -> AnthropicRequests.toParams(request, "claude-sonnet", thinkingConfig))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accepts_a_budget_strictly_below_max_tokens() {
      var request = new ModelRequest(Context.of(List.of()), "sys", 513, List.of(), Set.of(), null);

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
      var request = new ModelRequest(Context.of(List.of()), "sys", 4096, List.of(), Set.of(), null);

      var params =
          AnthropicRequests.toParams(request, "claude-sonnet", new ThinkingConfig(true, 1024));

      assertThat(params.thinking()).isPresent();
    }
  }
}
