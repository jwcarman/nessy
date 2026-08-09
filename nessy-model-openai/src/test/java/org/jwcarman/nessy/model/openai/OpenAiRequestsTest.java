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
package org.jwcarman.nessy.model.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ImageBlock;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.RedactedThinkingBlock;
import org.jwcarman.nessy.api.TextBlock;
import org.jwcarman.nessy.api.ThinkingBlock;
import org.jwcarman.nessy.api.ToolCall;
import org.jwcarman.nessy.api.ToolResultBlock;
import org.jwcarman.nessy.api.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.spi.model.ModelRequest;

class OpenAiRequestsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static ModelRequest request(List<Message> messages) {
    return new ModelRequest(
        messages, "you are a helpful assistant", "gpt-4o", 1024, List.of(), Set.of());
  }

  private static ModelRequest request(List<Message> messages, List<ToolSpec> tools) {
    return new ModelRequest(
        messages, "you are a helpful assistant", "gpt-4o", 1024, tools, Set.of());
  }

  private static ModelRequest requestWithSystemPrompt(String systemPrompt) {
    return new ModelRequest(List.of(), systemPrompt, "gpt-4o", 1024, List.of(), Set.of());
  }

  @Nested
  class SystemPrompt {

    @Test
    void becomes_the_leading_system_message() {
      var params = OpenAiRequests.toParams(request(List.of()));

      var messages = params.messages();
      assertThat(messages).isNotEmpty();
      var first = messages.get(0);
      assertThat(first.isSystem()).isTrue();
      assertThat(first.asSystem().content().asText()).isEqualTo("you are a helpful assistant");
    }

    @Test
    void a_blank_system_prompt_omits_the_system_message_entirely() {
      var params = OpenAiRequests.toParams(requestWithSystemPrompt(""));

      assertThat(params.messages()).noneMatch(ChatCompletionMessageParam::isSystem);
    }

    @Test
    void a_whitespace_only_system_prompt_omits_the_system_message_entirely() {
      var params = OpenAiRequests.toParams(requestWithSystemPrompt("   "));

      assertThat(params.messages()).noneMatch(ChatCompletionMessageParam::isSystem);
    }
  }

  @Nested
  class ModelAndMaxTokens {

    @Test
    void pass_through_unchanged() {
      var params = OpenAiRequests.toParams(request(List.of()));

      assertThat(params.model().asString()).isEqualTo("gpt-4o");
      assertThat(params.maxCompletionTokens()).contains(1024L);
    }
  }

  @Nested
  class StreamOptions {

    @Test
    void always_includes_usage() {
      var params = OpenAiRequests.toParams(request(List.of()));

      var streamOptions = params.streamOptions().orElseThrow();
      assertThat(streamOptions.includeUsage()).contains(true);
    }
  }

  @Nested
  class UserTextMessages {

    @Test
    void become_a_user_message_with_string_content() {
      var params = OpenAiRequests.toParams(request(List.of(Message.user("hello there"))));

      var messages = params.messages();
      assertThat(messages).hasSize(2);
      var userMessage = messages.get(1);
      assertThat(userMessage.isUser()).isTrue();
      assertThat(userMessage.asUser().content().asText()).isEqualTo("hello there");
    }
  }

  @Nested
  class ImageBlocks {

    @Test
    void become_a_content_part_with_a_data_uri() {
      var image = new ImageBlock("image/png", "aGVsbG8=");
      var params = OpenAiRequests.toParams(request(List.of(Message.user(List.of(image)))));

      var userMessage = params.messages().get(1).asUser();
      var parts = userMessage.content().asArrayOfContentParts();
      assertThat(parts).hasSize(1);
      assertThat(parts.get(0).isImageUrl()).isTrue();
      assertThat(parts.get(0).asImageUrl().imageUrl().url())
          .isEqualTo("data:image/png;base64,aGVsbG8=");
    }

    @Test
    void a_text_block_alongside_an_image_becomes_a_sibling_content_part() {
      var text = new TextBlock("what is this?");
      var image = new ImageBlock("image/jpeg", "Zm9v");
      var params = OpenAiRequests.toParams(request(List.of(Message.user(List.of(text, image)))));

      var parts = params.messages().get(1).asUser().content().asArrayOfContentParts();
      assertThat(parts).hasSize(2);
      assertThat(parts.get(0).isText()).isTrue();
      assertThat(parts.get(0).asText().text()).isEqualTo("what is this?");
      assertThat(parts.get(1).isImageUrl()).isTrue();
    }
  }

  @Nested
  class AssistantTextMessages {

    @Test
    void concatenates_text_blocks_into_the_content_string() {
      var first = new TextBlock("hello ");
      var second = new TextBlock("world");
      var params =
          OpenAiRequests.toParams(request(List.of(Message.assistant(List.of(first, second)))));

      var assistantMessage = params.messages().get(1).asAssistant();
      assertThat(assistantMessage.content().orElseThrow().asText()).isEqualTo("hello world");
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
    void becomes_a_tool_call_with_id_name_and_raw_json_arguments() {
      var toolUse = new ToolUseBlock(call("call-1", "read_file", "path", "README.md"));
      var params = OpenAiRequests.toParams(request(List.of(Message.assistant(List.of(toolUse)))));

      var assistantMessage = params.messages().get(1).asAssistant();
      var toolCalls = assistantMessage.toolCalls().orElseThrow();
      assertThat(toolCalls).hasSize(1);
      var functionCall = toolCalls.get(0).asFunction();
      assertThat(functionCall.id()).isEqualTo("call-1");
      assertThat(functionCall.function().name()).isEqualTo("read_file");
      assertThat(functionCall.function().arguments()).isEqualTo("{\"path\":\"README.md\"}");
    }

    @Test
    void a_multi_tool_turn_preserves_call_order_alongside_the_concatenated_text() {
      var text = new TextBlock("running two tools");
      var first = new ToolUseBlock(call("call-1", "read_file", "path", "a.txt"));
      var second = new ToolUseBlock(call("call-2", "read_file", "path", "b.txt"));
      var params =
          OpenAiRequests.toParams(
              request(List.of(Message.assistant(List.of(text, first, second)))));

      var assistantMessage = params.messages().get(1).asAssistant();
      assertThat(assistantMessage.content().orElseThrow().asText()).isEqualTo("running two tools");
      var toolCalls = assistantMessage.toolCalls().orElseThrow();
      assertThat(toolCalls).hasSize(2);
      assertThat(toolCalls.get(0).asFunction().id()).isEqualTo("call-1");
      assertThat(toolCalls.get(1).asFunction().id()).isEqualTo("call-2");
    }

    @Test
    void an_assistant_message_with_only_tool_calls_has_no_content() {
      var toolUse = new ToolUseBlock(call("call-1", "read_file", "path", "a.txt"));
      var params = OpenAiRequests.toParams(request(List.of(Message.assistant(List.of(toolUse)))));

      var assistantMessage = params.messages().get(1).asAssistant();
      assertThat(assistantMessage.content()).isEmpty();
    }
  }

  @Nested
  class ThinkingBlocksAreDropped {

    @Test
    void a_thinking_block_is_dropped_leaving_its_siblings_in_order() {
      var thinking = new ThinkingBlock("reasoning about the answer", "sig-123");
      var text = new TextBlock("the visible answer");
      var toolUse = new ToolUseBlock(new ToolCall("call-1", "noop", MAPPER.createObjectNode()));
      var params =
          OpenAiRequests.toParams(
              request(List.of(Message.assistant(List.of(thinking, text, toolUse)))));

      var assistantMessage = params.messages().get(1).asAssistant();
      assertThat(assistantMessage.content().orElseThrow().asText()).isEqualTo("the visible answer");
      assertThat(assistantMessage.toolCalls().orElseThrow()).hasSize(1);
    }

    @Test
    void a_redacted_thinking_block_is_dropped_leaving_its_siblings_in_order() {
      var redacted = new RedactedThinkingBlock("opaque-encrypted-payload");
      var text = new TextBlock("the visible answer");
      var params =
          OpenAiRequests.toParams(request(List.of(Message.assistant(List.of(redacted, text)))));

      var assistantMessage = params.messages().get(1).asAssistant();
      assertThat(assistantMessage.content().orElseThrow().asText()).isEqualTo("the visible answer");
      assertThat(assistantMessage.toolCalls()).isEmpty();
    }

    /**
     * Reachable scenario: thinking cut off by {@code max_tokens} before its signature arrived
     * settles as a single-block assistant message ({@code ThinkingBlock}). Chat Completions has no
     * home for a {@code ThinkingBlock} at all (see the class javadoc), so this message translates
     * to neither text nor tool calls; it must be elided outright rather than sent as an
     * otherwise-empty assistant param.
     */
    @Test
    void an_assistant_message_of_only_a_thinking_block_produces_no_message_param() {
      var thinking = new ThinkingBlock("cut off before signing", "");
      var params = OpenAiRequests.toParams(request(List.of(Message.assistant(List.of(thinking)))));

      // Only the leading system message survives; the assistant message translated to nothing.
      assertThat(params.messages()).hasSize(1);
      assertThat(params.messages().get(0).isSystem()).isTrue();
    }
  }

  @Nested
  class ToolResultBlocks {

    @Test
    void become_a_tool_role_message_carrying_the_tool_call_id() {
      var result = new ToolResultBlock("call-1", "42", false);
      var params = OpenAiRequests.toParams(request(List.of(Message.toolResults(List.of(result)))));

      var toolMessage = params.messages().get(1);
      assertThat(toolMessage.isTool()).isTrue();
      assertThat(toolMessage.asTool().toolCallId()).isEqualTo("call-1");
      assertThat(toolMessage.asTool().content().asText()).isEqualTo("42");
    }

    @Test
    void an_error_result_gets_the_error_prefix_on_its_content() {
      var result = new ToolResultBlock("call-1", "file not found", true);
      var params = OpenAiRequests.toParams(request(List.of(Message.toolResults(List.of(result)))));

      var toolMessage = params.messages().get(1);
      assertThat(toolMessage.asTool().content().asText()).isEqualTo("ERROR: file not found");
    }

    @Test
    void multiple_results_become_separate_messages_in_order() {
      var first = new ToolResultBlock("call-1", "first", false);
      var second = new ToolResultBlock("call-2", "second", false);
      var params =
          OpenAiRequests.toParams(request(List.of(Message.toolResults(List.of(first, second)))));

      var messages = params.messages();
      assertThat(messages).hasSize(3);
      assertThat(messages.get(1).asTool().toolCallId()).isEqualTo("call-1");
      assertThat(messages.get(2).asTool().toolCallId()).isEqualTo("call-2");
    }
  }

  @Nested
  class Tools {

    private static ToolSpec toolSpec(String name) {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("type", "object");
      schema.putObject("properties").putObject("path").put("type", "string");
      return new ToolSpec(name, "does things called " + name, schema);
    }

    @Test
    void becomes_a_function_tool_carrying_the_schema_as_is() {
      var params = OpenAiRequests.toParams(request(List.of(), List.of(toolSpec("read_file"))));

      var tools = params.tools().orElseThrow();
      assertThat(tools).hasSize(1);
      var function = tools.get(0).asFunction().function();
      assertThat(function.name()).isEqualTo("read_file");
      assertThat(function.description()).contains("does things called read_file");
      assertThat(function.strict()).isEmpty();
      var parameters = function.parameters().orElseThrow();
      assertThat(parameters._additionalProperties()).containsKey("type");
      assertThat(parameters._additionalProperties()).containsKey("properties");
    }
  }
}
