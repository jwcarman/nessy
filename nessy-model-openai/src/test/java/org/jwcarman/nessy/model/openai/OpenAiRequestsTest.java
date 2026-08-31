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
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.nessy.api.block.ImageBlock;
import org.jwcarman.nessy.api.block.RedactedThinkingBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ThinkingBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.block.ToolResultBlock;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ContextMessage;
import org.jwcarman.nessy.api.message.ExchangeMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.model.ModelRequest;

class OpenAiRequestsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static ModelRequest request(List<ContextMessage> messages) {
    return new ModelRequest(
        Context.of(messages), "you are a helpful assistant", 1024, List.of(), Set.of());
  }

  private static ModelRequest request(
      List<ContextMessage> messages, List<org.jwcarman.nessy.api.tool.Tool<?>> tools) {
    return new ModelRequest(
        Context.of(messages), "you are a helpful assistant", 1024, tools, Set.of());
  }

  private static ModelRequest requestWithSystemPrompt(String systemPrompt) {
    return new ModelRequest(Context.of(List.of()), systemPrompt, 1024, List.of(), Set.of());
  }

  @Nested
  class SystemPrompt {

    @Test
    void becomes_the_leading_system_message() {
      var params = OpenAiRequests.toParams(request(List.of()), "gpt-4o");

      var messages = params.messages();
      assertThat(messages).isNotEmpty();
      var first = messages.get(0);
      assertThat(first.isSystem()).isTrue();
      assertThat(first.asSystem().content().asText()).isEqualTo("you are a helpful assistant");
    }

    @Test
    void a_blank_system_prompt_omits_the_system_message_entirely() {
      var params = OpenAiRequests.toParams(requestWithSystemPrompt(""), "gpt-4o");

      assertThat(params.messages()).noneMatch(ChatCompletionMessageParam::isSystem);
    }

    @Test
    void a_whitespace_only_system_prompt_omits_the_system_message_entirely() {
      var params = OpenAiRequests.toParams(requestWithSystemPrompt("   "), "gpt-4o");

      assertThat(params.messages()).noneMatch(ChatCompletionMessageParam::isSystem);
    }
  }

  @Nested
  class ModelAndMaxTokens {

    @Test
    void pass_through_unchanged() {
      var params = OpenAiRequests.toParams(request(List.of()), "gpt-4o");

      assertThat(params.model().asString()).isEqualTo("gpt-4o");
      assertThat(params.maxCompletionTokens()).contains(1024L);
    }
  }

  @Nested
  class StreamOptions {

    @Test
    void always_includes_usage() {
      var params = OpenAiRequests.toParams(request(List.of()), "gpt-4o");

      var streamOptions = params.streamOptions().orElseThrow();
      assertThat(streamOptions.includeUsage()).contains(true);
    }
  }

  @Nested
  class UserTextMessages {

    @Test
    void become_a_user_message_with_string_content() {
      var params =
          OpenAiRequests.toParams(request(List.of(UserMessage.of("hello there"))), "gpt-4o");

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
      var params =
          OpenAiRequests.toParams(request(List.of(new UserMessage(List.of(image)))), "gpt-4o");

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
      var params =
          OpenAiRequests.toParams(
              request(List.of(new UserMessage(List.of(text, image)))), "gpt-4o");

      var parts = params.messages().get(1).asUser().content().asArrayOfContentParts();
      assertThat(parts).hasSize(2);
      assertThat(parts.get(0).isText()).isTrue();
      assertThat(parts.get(0).asText().text()).isEqualTo("what is this?");
      assertThat(parts.get(1).isImageUrl()).isTrue();
    }

    // REMOVED IN THE CUTOVER (2026-08-30): pinned that a ThinkingBlock inside a user message
    // failed loudly rather than being silently mis-mapped. UserContentBlock now permits text and
    // images only, so that message does not compile — the type system replaced the runtime check,
    // which is a strictly better place for it.
  }

  @Nested
  class AssistantTextMessages {

    @Test
    void concatenates_text_blocks_into_the_content_string() {
      var first = new TextBlock("hello ");
      var second = new TextBlock("world");
      var params =
          OpenAiRequests.toParams(
              request(List.of(new AnswerMessage(List.of(first, second)))), "gpt-4o");

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
      var toolUse = new ToolCallBlock(call("call-1", "read_file", "path", "README.md"));
      var assistantTurn =
          new ExchangeMessage(
              List.of(toolUse), List.of(ToolResultBlock.of("call-1", ToolResult.ok("ok"))));
      var params = OpenAiRequests.toParams(request(List.of(assistantTurn)), "gpt-4o");

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
      var first = new ToolCallBlock(call("call-1", "read_file", "path", "a.txt"));
      var second = new ToolCallBlock(call("call-2", "read_file", "path", "b.txt"));
      var assistantTurn =
          new ExchangeMessage(
              List.of(text, first, second),
              List.of(
                  ToolResultBlock.of("call-1", ToolResult.ok("ok")),
                  ToolResultBlock.of("call-2", ToolResult.ok("ok"))));
      var params = OpenAiRequests.toParams(request(List.of(assistantTurn)), "gpt-4o");

      var assistantMessage = params.messages().get(1).asAssistant();
      assertThat(assistantMessage.content().orElseThrow().asText()).isEqualTo("running two tools");
      var toolCalls = assistantMessage.toolCalls().orElseThrow();
      assertThat(toolCalls).hasSize(2);
      assertThat(toolCalls.get(0).asFunction().id()).isEqualTo("call-1");
      assertThat(toolCalls.get(1).asFunction().id()).isEqualTo("call-2");
    }

    @Test
    void an_assistant_message_with_only_tool_calls_has_no_content() {
      var toolUse = new ToolCallBlock(call("call-1", "read_file", "path", "a.txt"));
      var assistantTurn =
          new ExchangeMessage(
              List.of(toolUse), List.of(ToolResultBlock.of("call-1", ToolResult.ok("ok"))));
      var params = OpenAiRequests.toParams(request(List.of(assistantTurn)), "gpt-4o");

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
      var toolUse = new ToolCallBlock(new ToolCall("call-1", "noop", MAPPER.createObjectNode()));
      var assistantTurn =
          new ExchangeMessage(
              List.of(thinking, text, toolUse),
              List.of(ToolResultBlock.of("call-1", ToolResult.ok("ok"))));
      var params = OpenAiRequests.toParams(request(List.of(assistantTurn)), "gpt-4o");

      var assistantMessage = params.messages().get(1).asAssistant();
      assertThat(assistantMessage.content().orElseThrow().asText()).isEqualTo("the visible answer");
      assertThat(assistantMessage.toolCalls().orElseThrow()).hasSize(1);
    }

    @Test
    void a_redacted_thinking_block_is_dropped_leaving_its_siblings_in_order() {
      var redacted = new RedactedThinkingBlock("opaque-encrypted-payload");
      var text = new TextBlock("the visible answer");
      var params =
          OpenAiRequests.toParams(
              request(List.of(new AnswerMessage(List.of(redacted, text)))), "gpt-4o");

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
      var params =
          OpenAiRequests.toParams(request(List.of(new AnswerMessage(List.of(thinking)))), "gpt-4o");

      // Only the leading system message survives; the assistant message translated to nothing.
      assertThat(params.messages()).hasSize(1);
      assertThat(params.messages().get(0).isSystem()).isTrue();
    }
  }

  @Nested
  class ToolResultBlocks {

    @Test
    void become_a_tool_role_message_carrying_the_tool_call_id() {
      var toolUse = new ToolCallBlock(new ToolCall("call-1", "noop", MAPPER.createObjectNode()));
      var result = ToolResultBlock.of("call-1", ToolResult.ok("42"));
      var params =
          OpenAiRequests.toParams(
              request(
                  List.of(
                      new AnswerMessage(List.of(toolUse)), new ToolResultMessage(List.of(result)))),
              "gpt-4o");

      var toolMessage = params.messages().get(2);
      assertThat(toolMessage.isTool()).isTrue();
      assertThat(toolMessage.asTool().toolCallId()).isEqualTo("call-1");
      assertThat(toolMessage.asTool().content().asText()).isEqualTo("42");
    }

    @Test
    void an_error_result_gets_the_error_prefix_on_its_content() {
      var toolUse = new ToolCallBlock(new ToolCall("call-1", "noop", MAPPER.createObjectNode()));
      var result = ToolResultBlock.of("call-1", ToolResult.error("file not found"));
      var params =
          OpenAiRequests.toParams(
              request(
                  List.of(
                      new AnswerMessage(List.of(toolUse)), new ToolResultMessage(List.of(result)))),
              "gpt-4o");

      var toolMessage = params.messages().get(2);
      assertThat(toolMessage.asTool().content().asText()).isEqualTo("ERROR: file not found");
    }

    @Test
    void multiple_results_become_separate_messages_in_order() {
      var firstUse = new ToolCallBlock(new ToolCall("call-1", "noop", MAPPER.createObjectNode()));
      var secondUse = new ToolCallBlock(new ToolCall("call-2", "noop", MAPPER.createObjectNode()));
      var first = ToolResultBlock.of("call-1", ToolResult.ok("first"));
      var second = ToolResultBlock.of("call-2", ToolResult.ok("second"));
      var params =
          OpenAiRequests.toParams(
              request(
                  List.of(
                      new AnswerMessage(List.of(firstUse, secondUse)),
                      new ToolResultMessage(List.of(first, second)))),
              "gpt-4o");

      var messages = params.messages();
      assertThat(messages).hasSize(4);
      assertThat(messages.get(2).asTool().toolCallId()).isEqualTo("call-1");
      assertThat(messages.get(3).asTool().toolCallId()).isEqualTo("call-2");
    }
  }

  @Nested
  class MixedToolResultsAndOtherBlocks {

    @Test
    void a_tool_result_followed_by_text_becomes_a_tool_message_then_a_user_message() {
      var toolUse = new ToolCallBlock(new ToolCall("c1", "noop", MAPPER.createObjectNode()));
      var result = ToolResultBlock.of("c1", ToolResult.ok("13"));
      var text = new TextBlock("try again");
      var params =
          OpenAiRequests.toParams(
              request(
                  List.of(
                      new AnswerMessage(List.of(toolUse)),
                      new ToolResultMessage(List.of(result)),
                      new UserMessage(List.of(text)))),
              "gpt-4o");

      var messages = params.messages();
      assertThat(messages).hasSize(4);
      assertThat(messages.get(2).isTool()).isTrue();
      assertThat(messages.get(2).asTool().toolCallId()).isEqualTo("c1");
      assertThat(messages.get(3).isUser()).isTrue();
      assertThat(messages.get(3).asUser().content().asText()).isEqualTo("try again");
    }

    @Test
    void two_tool_results_and_a_text_block_become_two_tool_messages_then_one_user_message() {
      var firstUse = new ToolCallBlock(new ToolCall("c1", "noop", MAPPER.createObjectNode()));
      var secondUse = new ToolCallBlock(new ToolCall("c2", "noop", MAPPER.createObjectNode()));
      var first = ToolResultBlock.of("c1", ToolResult.ok("13"));
      var second = ToolResultBlock.of("c2", ToolResult.ok("7"));
      var text = new TextBlock("try again");
      var params =
          OpenAiRequests.toParams(
              request(
                  List.of(
                      new AnswerMessage(List.of(firstUse, secondUse)),
                      new ToolResultMessage(List.of(first, second)),
                      new UserMessage(List.of(text)))),
              "gpt-4o");

      var messages = params.messages();
      assertThat(messages).hasSize(5);
      assertThat(messages.get(2).asTool().toolCallId()).isEqualTo("c1");
      assertThat(messages.get(3).asTool().toolCallId()).isEqualTo("c2");
      assertThat(messages.get(4).isUser()).isTrue();
      assertThat(messages.get(4).asUser().content().asText()).isEqualTo("try again");
    }

    static Stream<Arguments> pure_content_messages() {
      return Stream.of(
          Arguments.of(
              new ToolResultMessage(List.of(ToolResultBlock.of("c1", ToolResult.ok("ok")))), true),
          Arguments.of(new UserMessage(List.of(new TextBlock("hello there"))), false));
    }

    @ParameterizedTest
    @MethodSource("pure_content_messages")
    void a_pure_content_message_is_pinned_unchanged(Message message, boolean expectToolMessage) {
      List<ContextMessage> requestMessages =
          expectToolMessage
              ? List.of(
                  new AnswerMessage(
                      List.of(
                          new ToolCallBlock(
                              new ToolCall("c1", "noop", MAPPER.createObjectNode())))),
                  message)
              : List.of(message);
      var params = OpenAiRequests.toParams(request(requestMessages), "gpt-4o");

      var paramMessages = params.messages();
      var last = paramMessages.get(paramMessages.size() - 1);
      assertThat(last.isTool()).isEqualTo(expectToolMessage);
      assertThat(last.isUser()).isEqualTo(!expectToolMessage);
    }
  }

  @Nested
  class Tools {

    private static StubTool toolSpec(String name) {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("type", "object");
      schema.putObject("properties").putObject("path").put("type", "string");
      return new StubTool(name, "does things called " + name, schema);
    }

    @Test
    void becomes_a_function_tool_carrying_the_schema_as_is() {
      var params =
          OpenAiRequests.toParams(request(List.of(), List.of(toolSpec("read_file"))), "gpt-4o");

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
