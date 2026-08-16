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
package org.jwcarman.nessy.model.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.nessy.api.message.ContentBlock;
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
import org.jwcarman.nessy.spi.model.ModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultStatus;

class BedrockRequestsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static ModelRequest request(List<Message> messages) {
    return new ModelRequest(
        Context.of(messages),
        "you are a helpful assistant",
        "us.anthropic.claude-haiku-4-5-20251001-v1:0",
        1024,
        List.of(),
        Set.of(),
        null);
  }

  private static ModelRequest request(List<Message> messages, List<ToolSpec> tools) {
    return new ModelRequest(
        Context.of(messages),
        "you are a helpful assistant",
        "us.anthropic.claude-haiku-4-5-20251001-v1:0",
        1024,
        tools,
        Set.of(),
        null);
  }

  private static ModelRequest requestWithSystemPrompt(String systemPrompt) {
    return new ModelRequest(
        Context.of(List.of()),
        systemPrompt,
        "us.anthropic.claude-haiku-4-5-20251001-v1:0",
        1024,
        List.of(),
        Set.of(),
        null);
  }

  @Nested
  class SystemPrompt {

    @Test
    void becomes_a_system_content_block() {
      var built = BedrockRequests.toRequest(request(List.of()));

      assertThat(built.system()).hasSize(1);
      assertThat(built.system().get(0).text()).isEqualTo("you are a helpful assistant");
    }

    @Test
    void a_blank_system_prompt_omits_the_system_field_entirely() {
      var built = BedrockRequests.toRequest(requestWithSystemPrompt(""));

      assertThat(built.hasSystem()).isFalse();
    }

    @Test
    void a_whitespace_only_system_prompt_omits_the_system_field_entirely() {
      var built = BedrockRequests.toRequest(requestWithSystemPrompt("   "));

      assertThat(built.hasSystem()).isFalse();
    }
  }

  @Nested
  class MaxTokens {

    @Test
    void passes_through_unchanged() {
      var built = BedrockRequests.toRequest(request(List.of()));

      assertThat(built.inferenceConfig().maxTokens()).isEqualTo(1024);
    }
  }

  @Nested
  class UserTextMessages {

    @Test
    void become_a_user_message_with_a_text_block() {
      var built = BedrockRequests.toRequest(request(List.of(Message.user("hello there"))));

      assertThat(built.messages()).hasSize(1);
      var message = built.messages().get(0);
      assertThat(message.roleAsString()).isEqualTo("user");
      assertThat(message.content()).hasSize(1);
      assertThat(message.content().get(0).text()).isEqualTo("hello there");
    }

    @Test
    void multiple_text_blocks_become_sibling_content_blocks_in_order() {
      var first = new TextBlock("first ");
      var second = new TextBlock("second");
      var built = BedrockRequests.toRequest(request(List.of(Message.user(List.of(first, second)))));

      var content = built.messages().get(0).content();
      assertThat(content).hasSize(2);
      assertThat(content.get(0).text()).isEqualTo("first ");
      assertThat(content.get(1).text()).isEqualTo("second");
    }
  }

  @Nested
  class UnsupportedUserContent {

    @Test
    void an_image_block_fails_loudly() {
      var image = new ImageBlock("image/png", "aGVsbG8=");
      var request = request(List.of(Message.user(List.of(image))));

      assertThatThrownBy(() -> BedrockRequests.toRequest(request))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unsupported content block");
    }
  }

  @Nested
  class AssistantTextMessages {

    @Test
    void becomes_an_assistant_message_with_text_blocks() {
      var first = new TextBlock("hello ");
      var second = new TextBlock("world");
      var built =
          BedrockRequests.toRequest(request(List.of(Message.assistant(List.of(first, second)))));

      assertThat(built.messages()).hasSize(1);
      var message = built.messages().get(0);
      assertThat(message.roleAsString()).isEqualTo("assistant");
      assertThat(message.content()).hasSize(2);
      assertThat(message.content().get(0).text()).isEqualTo("hello ");
      assertThat(message.content().get(1).text()).isEqualTo("world");
    }
  }

  @Nested
  class AssistantToolCalls {

    private static ToolCall call(String id, String name, String argKey, String argValue) {
      ObjectNode arguments = MAPPER.createObjectNode();
      arguments.put(argKey, argValue);
      return new ToolCall(id, name, arguments);
    }

    @Test
    void becomes_a_tool_use_block_with_id_name_and_input() {
      var toolUse = new ToolUseBlock(call("call-1", "read_file", "path", "README.md"));
      var assistantTurn = Message.assistant(List.of(toolUse));
      var toolResultTurn = Message.toolResults(List.of(new ToolResultBlock("call-1", "ok", false)));
      var built = BedrockRequests.toRequest(request(List.of(assistantTurn, toolResultTurn)));

      var content = built.messages().get(0).content();
      assertThat(content).hasSize(1);
      var block = content.get(0).toolUse();
      assertThat(block.toolUseId()).isEqualTo("call-1");
      assertThat(block.name()).isEqualTo("read_file");
      assertThat(block.input().asMap().get("path").asString()).isEqualTo("README.md");
    }

    @Test
    void a_multi_tool_turn_preserves_call_order_alongside_the_text() {
      var text = new TextBlock("running two tools");
      var first = new ToolUseBlock(call("call-1", "read_file", "path", "a.txt"));
      var second = new ToolUseBlock(call("call-2", "read_file", "path", "b.txt"));
      var assistantTurn = Message.assistant(List.of(text, first, second));
      var toolResultTurn =
          Message.toolResults(
              List.of(
                  new ToolResultBlock("call-1", "ok", false),
                  new ToolResultBlock("call-2", "ok", false)));
      var built = BedrockRequests.toRequest(request(List.of(assistantTurn, toolResultTurn)));

      var content = built.messages().get(0).content();
      assertThat(content).hasSize(3);
      assertThat(content.get(0).text()).isEqualTo("running two tools");
      assertThat(content.get(1).toolUse().name()).isEqualTo("read_file");
      assertThat(content.get(2).toolUse().name()).isEqualTo("read_file");
    }

    @Test
    void number_and_boolean_arguments_convert_to_the_matching_document_kinds() {
      ObjectNode arguments = MAPPER.createObjectNode();
      arguments.put("lineCount", 25);
      arguments.put("ratio", 3.5);
      arguments.put("verbose", true);
      var toolUse = new ToolUseBlock(new ToolCall("call-1", "read_file", arguments));
      var assistantTurn = Message.assistant(List.of(toolUse));
      var toolResultTurn = Message.toolResults(List.of(new ToolResultBlock("call-1", "ok", false)));
      var built = BedrockRequests.toRequest(request(List.of(assistantTurn, toolResultTurn)));

      var input = built.messages().get(0).content().get(0).toolUse().input().asMap();
      // Node equality on document kind, not just string form: a wrong implementation that
      // stringified everything (Document.fromString(node.asText())) would still show "25", "3.5",
      // and "true" as text — asserting isNumber()/isBoolean() catches that a schema promising an
      // integer or a boolean would otherwise silently receive a JSON string instead.
      assertThat(input.get("lineCount").isNumber()).isTrue();
      assertThat(input.get("lineCount").asNumber().intValue()).isEqualTo(25);
      assertThat(input.get("ratio").isNumber()).isTrue();
      assertThat(input.get("ratio").asNumber().doubleValue()).isEqualTo(3.5);
      assertThat(input.get("verbose").isBoolean()).isTrue();
      assertThat(input.get("verbose").asBoolean()).isTrue();
    }

    @Test
    void a_tool_use_block_s_stored_signature_is_ignored_on_replay() {
      var toolUse =
          new ToolUseBlock(
              new ToolCall("call-1", "read_file", MAPPER.createObjectNode()), "some-signature");
      var assistantTurn = Message.assistant(List.of(toolUse));
      var toolResultTurn = Message.toolResults(List.of(new ToolResultBlock("call-1", "ok", false)));

      // No exception, and the rebuilt block carries the call unchanged — Bedrock's ToolUseBlock
      // has no signature-shaped field for GeminiRequests' equivalent to replay onto.
      var built = BedrockRequests.toRequest(request(List.of(assistantTurn, toolResultTurn)));

      var block = built.messages().get(0).content().get(0).toolUse();
      assertThat(block.toolUseId()).isEqualTo("call-1");
    }
  }

  @Nested
  class ThinkingBlocksAreDropped {

    @Test
    void a_thinking_block_is_dropped_leaving_its_siblings_in_order() {
      var thinking = new ThinkingBlock("reasoning about the answer", "sig-123");
      var text = new TextBlock("the visible answer");
      var toolUse = new ToolUseBlock(new ToolCall("call-1", "noop", MAPPER.createObjectNode()));
      var assistantTurn = Message.assistant(List.of(thinking, text, toolUse));
      var toolResultTurn = Message.toolResults(List.of(new ToolResultBlock("call-1", "ok", false)));
      var built = BedrockRequests.toRequest(request(List.of(assistantTurn, toolResultTurn)));

      var content = built.messages().get(0).content();
      assertThat(content).hasSize(2);
      assertThat(content.get(0).text()).isEqualTo("the visible answer");
      assertThat(content.get(1).toolUse()).isNotNull();
    }

    @Test
    void a_redacted_thinking_block_is_dropped_leaving_its_siblings_in_order() {
      var redacted = new RedactedThinkingBlock("opaque-encrypted-payload");
      var text = new TextBlock("the visible answer");
      var built =
          BedrockRequests.toRequest(request(List.of(Message.assistant(List.of(redacted, text)))));

      var content = built.messages().get(0).content();
      assertThat(content).hasSize(1);
      assertThat(content.get(0).text()).isEqualTo("the visible answer");
    }

    /**
     * Reachable scenario: thinking cut off by {@code max_tokens} before its signature arrived
     * settles as a single-block assistant message ({@code ThinkingBlock}). This wire has no home
     * for a {@code ThinkingBlock} at all (see {@code BedrockRequests}' class javadoc), so this
     * message translates to no content blocks and must be elided outright rather than sent as an
     * otherwise-empty message.
     */
    @Test
    void an_assistant_message_of_only_a_thinking_block_produces_no_message() {
      var thinking = new ThinkingBlock("cut off before signing", "");
      var built = BedrockRequests.toRequest(request(List.of(Message.assistant(List.of(thinking)))));

      assertThat(built.messages()).isEmpty();
    }
  }

  @Nested
  class ToolResultBlocks {

    @Test
    void becomes_a_tool_result_block_addressed_by_the_matching_call_s_id() {
      var toolUse =
          new ToolUseBlock(new ToolCall("call-1", "read_file", MAPPER.createObjectNode()));
      var result = new ToolResultBlock("call-1", "42", false);
      var built =
          BedrockRequests.toRequest(
              request(
                  List.of(
                      Message.assistant(List.of(toolUse)), Message.toolResults(List.of(result)))));

      var responseContent = built.messages().get(1).content();
      assertThat(built.messages().get(1).roleAsString()).isEqualTo("user");
      assertThat(responseContent).hasSize(1);
      var toolResult = responseContent.get(0).toolResult();
      assertThat(toolResult.toolUseId()).isEqualTo("call-1");
      assertThat(toolResult.content()).hasSize(1);
      assertThat(toolResult.content().get(0).text()).isEqualTo("42");
      assertThat(toolResult.status()).isEqualTo(ToolResultStatus.SUCCESS);
    }

    @Test
    void an_error_result_carries_the_error_status() {
      var toolUse =
          new ToolUseBlock(new ToolCall("call-1", "read_file", MAPPER.createObjectNode()));
      var result = new ToolResultBlock("call-1", "file not found", true);
      var built =
          BedrockRequests.toRequest(
              request(
                  List.of(
                      Message.assistant(List.of(toolUse)), Message.toolResults(List.of(result)))));

      var toolResult = built.messages().get(1).content().get(0).toolResult();
      assertThat(toolResult.status()).isEqualTo(ToolResultStatus.ERROR);
      assertThat(toolResult.content().get(0).text()).isEqualTo("file not found");
    }

    @Test
    void multiple_results_become_sibling_content_blocks_on_one_message_in_order() {
      var firstUse = new ToolUseBlock(new ToolCall("call-1", "noop", MAPPER.createObjectNode()));
      var secondUse = new ToolUseBlock(new ToolCall("call-2", "noop", MAPPER.createObjectNode()));
      var first = new ToolResultBlock("call-1", "first", false);
      var second = new ToolResultBlock("call-2", "second", false);
      var built =
          BedrockRequests.toRequest(
              request(
                  List.of(
                      Message.assistant(List.of(firstUse, secondUse)),
                      Message.toolResults(List.of(first, second)))));

      var content = built.messages().get(1).content();
      assertThat(content).hasSize(2);
      assertThat(content.get(0).toolResult().content().get(0).text()).isEqualTo("first");
      assertThat(content.get(1).toolResult().content().get(0).text()).isEqualTo("second");
    }
  }

  @Nested
  class MixedToolResultsAndOtherBlocks {

    @Test
    void a_tool_result_followed_by_text_becomes_one_user_message_with_both_blocks_in_order() {
      var toolUse =
          new ToolUseBlock(new ToolCall("call-1", "read_file", MAPPER.createObjectNode()));
      var result = new ToolResultBlock("call-1", "13", false);
      var text = new TextBlock("try again");
      var built =
          BedrockRequests.toRequest(
              request(
                  List.of(
                      Message.assistant(List.of(toolUse)), Message.user(List.of(result, text)))));

      var responseMessage = built.messages().get(1);
      assertThat(responseMessage.roleAsString()).isEqualTo("user");
      var content = responseMessage.content();
      assertThat(content).hasSize(2);
      assertThat(content.get(0).toolResult().toolUseId()).isEqualTo("call-1");
      assertThat(content.get(1).text()).isEqualTo("try again");
    }

    static Stream<Arguments> pure_content_messages() {
      return Stream.of(
          Arguments.of(List.of(new ToolResultBlock("call-1", "ok", false)), true),
          Arguments.of(List.of(new TextBlock("hello there")), false));
    }

    @ParameterizedTest
    @MethodSource("pure_content_messages")
    void a_pure_content_message_is_pinned_unchanged(
        List<ContentBlock> content, boolean expectToolResult) {
      List<Message> messages =
          expectToolResult
              ? List.of(
                  Message.assistant(
                      List.of(
                          new ToolUseBlock(
                              new ToolCall("call-1", "noop", MAPPER.createObjectNode())))),
                  Message.user(content))
              : List.of(Message.user(content));

      var built = BedrockRequests.toRequest(request(messages));

      var lastMessage = built.messages().get(built.messages().size() - 1);
      assertThat(lastMessage.roleAsString()).isEqualTo("user");
      var blocks = lastMessage.content();
      assertThat(blocks).hasSize(1);
      assertThat(blocks.get(0).toolResult() != null).isEqualTo(expectToolResult);
      assertThat(blocks.get(0).text() != null).isEqualTo(!expectToolResult);
    }
  }

  @Nested
  class Tools {

    private static ToolSpec toolSpec(String name) {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("type", "object");
      var properties = schema.putObject("properties");
      properties.putObject("path").put("type", "string");
      properties.putObject("lineCount").put("type", "integer");
      schema.putArray("required").add("path");
      return new ToolSpec(name, "does things called " + name, schema);
    }

    @Test
    void becomes_a_tool_specification_carrying_the_schema_as_a_document() {
      var spec = toolSpec("read_file");
      var built = BedrockRequests.toRequest(request(List.of(), List.of(spec)));

      assertThat(built.toolConfig().tools()).hasSize(1);
      var toolSpecification = built.toolConfig().tools().get(0).toolSpec();
      assertThat(toolSpecification.name()).isEqualTo("read_file");
      assertThat(toolSpecification.description()).isEqualTo("does things called read_file");
      var schemaMap = toolSpecification.inputSchema().json().asMap();
      assertThat(schemaMap.get("type").asString()).isEqualTo("object");
      var properties = schemaMap.get("properties").asMap();
      assertThat(properties.get("path").asMap().get("type").asString()).isEqualTo("string");
      var required = schemaMap.get("required").asList();
      assertThat(required).hasSize(1);
      assertThat(required.get(0).asString()).isEqualTo("path");
    }

    @Test
    void no_tools_means_the_request_carries_no_tool_config() {
      var built = BedrockRequests.toRequest(request(List.of()));

      assertThat(built.toolConfig()).isNull();
    }
  }
}
