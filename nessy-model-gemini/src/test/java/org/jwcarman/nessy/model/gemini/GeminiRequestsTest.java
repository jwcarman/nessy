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
package org.jwcarman.nessy.model.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.nessy.api.block.ImageBlock;
import org.jwcarman.nessy.api.block.TextBlock;
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

class GeminiRequestsTest {

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
    void becomes_the_system_instruction() {
      var config = GeminiRequests.toConfig(request(List.of()));

      var systemInstruction = config.systemInstruction().orElseThrow();
      var parts = systemInstruction.parts().orElseThrow();
      assertThat(parts).hasSize(1);
      assertThat(parts.get(0).text()).contains("you are a helpful assistant");
    }

    @Test
    void a_blank_system_prompt_omits_the_system_instruction_entirely() {
      var config = GeminiRequests.toConfig(requestWithSystemPrompt(""));

      assertThat(config.systemInstruction()).isEmpty();
    }

    @Test
    void a_whitespace_only_system_prompt_omits_the_system_instruction_entirely() {
      var config = GeminiRequests.toConfig(requestWithSystemPrompt("   "));

      assertThat(config.systemInstruction()).isEmpty();
    }
  }

  @Nested
  class MaxTokens {

    @Test
    void passes_through_unchanged() {
      var config = GeminiRequests.toConfig(request(List.of()));

      assertThat(config.maxOutputTokens()).contains(1024);
    }
  }

  @Nested
  class UserTextMessages {

    @Test
    void become_a_user_content_with_a_text_part() {
      var contents = GeminiRequests.toContents(request(List.of(UserMessage.of("hello there"))));

      assertThat(contents).hasSize(1);
      var content = contents.get(0);
      assertThat(content.role()).contains("user");
      var parts = content.parts().orElseThrow();
      assertThat(parts).hasSize(1);
      assertThat(parts.get(0).text()).contains("hello there");
    }

    @Test
    void multiple_text_blocks_become_sibling_parts_in_order() {
      var first = new TextBlock("first ");
      var second = new TextBlock("second");
      var contents =
          GeminiRequests.toContents(request(List.of(new UserMessage(List.of(first, second)))));

      var parts = contents.get(0).parts().orElseThrow();
      assertThat(parts).hasSize(2);
      assertThat(parts.get(0).text()).contains("first ");
      assertThat(parts.get(1).text()).contains("second");
    }
  }

  @Nested
  class UnsupportedUserContent {

    @Test
    void an_image_block_fails_loudly() {
      var image = new ImageBlock("image/png", "aGVsbG8=");
      var request = request(List.of(new UserMessage(List.of(image))));

      assertThatThrownBy(() -> GeminiRequests.toContents(request))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unsupported content block");
    }
  }

  @Nested
  class AssistantTextMessages {

    @Test
    void becomes_a_model_content_with_a_text_part() {
      var first = new TextBlock("hello ");
      var second = new TextBlock("world");
      var contents =
          GeminiRequests.toContents(request(List.of(new AnswerMessage(List.of(first, second)))));

      assertThat(contents).hasSize(1);
      var content = contents.get(0);
      assertThat(content.role()).contains("model");
      var parts = content.parts().orElseThrow();
      assertThat(parts).hasSize(2);
      assertThat(parts.get(0).text()).contains("hello ");
      assertThat(parts.get(1).text()).contains("world");
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
    void becomes_a_function_call_part_with_name_and_args() {
      var toolUse = new ToolCallBlock(call("call-1", "read_file", "path", "README.md"));
      var assistantTurn =
          new ExchangeMessage(
              List.of(toolUse), List.of(ToolResultBlock.of("call-1", ToolResult.ok("ok"))));
      var contents = GeminiRequests.toContents(request(List.of(assistantTurn)));

      var modelContent = contents.get(0);
      var parts = modelContent.parts().orElseThrow();
      assertThat(parts).hasSize(1);
      var functionCall = parts.get(0).functionCall().orElseThrow();
      assertThat(functionCall.name()).contains("read_file");
      assertThat(functionCall.args().orElseThrow()).containsEntry("path", "README.md");
    }

    @Test
    void a_multi_tool_turn_preserves_call_order_alongside_the_text() {
      var text = new TextBlock("running two tools");
      var first = new ToolCallBlock(call("call-1", "read_file", "path", "a.txt"));
      var second = new ToolCallBlock(call("call-2", "read_file", "path", "b.txt"));
      var assistantTurn =
          new ExchangeMessage(
              List.of(text, first, second),
              List.of(
                  ToolResultBlock.of("call-1", ToolResult.ok("ok")),
                  ToolResultBlock.of("call-2", ToolResult.ok("ok"))));
      var contents = GeminiRequests.toContents(request(List.of(assistantTurn)));

      var parts = contents.get(0).parts().orElseThrow();
      assertThat(parts).hasSize(3);
      assertThat(parts.get(0).text()).contains("running two tools");
      assertThat(parts.get(1).functionCall().orElseThrow().name()).contains("read_file");
      assertThat(parts.get(2).functionCall().orElseThrow().name()).contains("read_file");
    }
  }

  @Nested
  class ToolCallSignatures {

    @Test
    void a_signed_tool_use_block_replays_the_same_bytes_as_the_part_s_thought_signature() {
      byte[] rawSignature = "opaque-continuity-token".getBytes(StandardCharsets.UTF_8);
      String encoded = Base64.getEncoder().encodeToString(rawSignature);
      var toolUse =
          new ToolCallBlock(
              new ToolCall("call-1", "read_file", MAPPER.createObjectNode()), encoded);
      var assistantTurn =
          new ExchangeMessage(
              List.of(toolUse), List.of(ToolResultBlock.of("call-1", ToolResult.ok("ok"))));
      var contents = GeminiRequests.toContents(request(List.of(assistantTurn)));

      var parts = contents.get(0).parts().orElseThrow();
      assertThat(parts.get(0).thoughtSignature().orElseThrow()).isEqualTo(rawSignature);
    }

    @Test
    void an_unsigned_tool_use_block_replays_google_s_skip_validation_sentinel() {
      byte[] sentinel = "skip_thought_signature_validator".getBytes(StandardCharsets.UTF_8);
      var toolUse =
          new ToolCallBlock(new ToolCall("call-1", "read_file", MAPPER.createObjectNode()));
      var assistantTurn =
          new ExchangeMessage(
              List.of(toolUse), List.of(ToolResultBlock.of("call-1", ToolResult.ok("ok"))));
      var contents = GeminiRequests.toContents(request(List.of(assistantTurn)));

      var parts = contents.get(0).parts().orElseThrow();
      assertThat(parts.get(0).thoughtSignature().orElseThrow()).isEqualTo(sentinel);
    }

    @Test
    void a_signature_that_is_not_valid_base64_replays_as_unsigned_instead_of_throwing() {
      byte[] sentinel = "skip_thought_signature_validator".getBytes(StandardCharsets.UTF_8);
      var toolUse =
          new ToolCallBlock(
              new ToolCall("call-1", "read_file", MAPPER.createObjectNode()), "not-base64!!");
      var assistantTurn =
          new ExchangeMessage(
              List.of(toolUse), List.of(ToolResultBlock.of("call-1", ToolResult.ok("ok"))));

      var contents = GeminiRequests.toContents(request(List.of(assistantTurn)));

      var parts = contents.get(0).parts().orElseThrow();
      assertThat(parts.get(0).thoughtSignature().orElseThrow()).isEqualTo(sentinel);
    }
  }

  @Nested
  class ThinkingBlocksAreDropped {

    @Test
    void a_thinking_block_is_dropped_leaving_its_siblings_in_order() {
      var thinking = providerState("thinking", "reasoning about the answer", "sig-123");
      var text = new TextBlock("the visible answer");
      var toolUse = new ToolCallBlock(new ToolCall("call-1", "noop", MAPPER.createObjectNode()));
      var assistantTurn =
          new ExchangeMessage(
              List.of(thinking, text, toolUse),
              List.of(ToolResultBlock.of("call-1", ToolResult.ok("ok"))));
      var contents = GeminiRequests.toContents(request(List.of(assistantTurn)));

      var parts = contents.get(0).parts().orElseThrow();
      assertThat(parts).hasSize(2);
      assertThat(parts.get(0).text()).contains("the visible answer");
      assertThat(parts.get(1).functionCall()).isPresent();
    }

    @Test
    void a_redacted_thinking_block_is_dropped_leaving_its_siblings_in_order() {
      var redacted = redactedState("opaque-encrypted-payload");
      var text = new TextBlock("the visible answer");
      var contents =
          GeminiRequests.toContents(request(List.of(new AnswerMessage(List.of(redacted, text)))));

      var parts = contents.get(0).parts().orElseThrow();
      assertThat(parts).hasSize(1);
      assertThat(parts.get(0).text()).contains("the visible answer");
    }

    /**
     * Reachable scenario: thinking cut off by {@code max_tokens} before its signature arrived
     * settles as a single-block assistant message ({@code ThinkingBlock}). This wire has no home
     * for a {@code ThinkingBlock} at all (see the class javadoc), so this message translates to no
     * parts and must be elided outright rather than sent as an otherwise-empty {@code Content}.
     */
    @Test
    void an_assistant_message_of_only_a_thinking_block_produces_no_content() {
      var thinking = providerState("thinking", "cut off before signing", "");
      var contents =
          GeminiRequests.toContents(request(List.of(new AnswerMessage(List.of(thinking)))));

      assertThat(contents).isEmpty();
    }
  }

  @Nested
  class ToolResultBlocks {

    @Test
    void becomes_a_function_response_part_addressed_by_the_matching_call_s_name() {
      var toolUse =
          new ToolCallBlock(new ToolCall("call-1", "read_file", MAPPER.createObjectNode()));
      var result = ToolResultBlock.of("call-1", ToolResult.ok("42"));
      var contents =
          GeminiRequests.toContents(
              request(List.of(new ExchangeMessage(List.of(toolUse), List.of(result)))));

      var responseContent = contents.get(1);
      assertThat(responseContent.role()).contains("user");
      var parts = responseContent.parts().orElseThrow();
      assertThat(parts).hasSize(1);
      var functionResponse = parts.get(0).functionResponse().orElseThrow();
      assertThat(functionResponse.name()).contains("read_file");
      assertThat(functionResponse.response().orElseThrow()).containsEntry("output", "42");
    }

    @Test
    void an_error_result_carries_the_error_key_instead_of_output() {
      var toolUse =
          new ToolCallBlock(new ToolCall("call-1", "read_file", MAPPER.createObjectNode()));
      var result = ToolResultBlock.of("call-1", ToolResult.error("file not found"));
      var contents =
          GeminiRequests.toContents(
              request(List.of(new ExchangeMessage(List.of(toolUse), List.of(result)))));

      var functionResponse =
          contents.get(1).parts().orElseThrow().get(0).functionResponse().orElseThrow();
      assertThat(functionResponse.response().orElseThrow())
          .containsEntry("error", "file not found");
      assertThat(functionResponse.response().orElseThrow()).doesNotContainKey("output");
    }

    @Test
    void multiple_results_become_sibling_parts_on_one_content_in_order() {
      var firstUse = new ToolCallBlock(new ToolCall("call-1", "noop", MAPPER.createObjectNode()));
      var secondUse = new ToolCallBlock(new ToolCall("call-2", "noop", MAPPER.createObjectNode()));
      var first = ToolResultBlock.of("call-1", ToolResult.ok("first"));
      var second = ToolResultBlock.of("call-2", ToolResult.ok("second"));
      var contents =
          GeminiRequests.toContents(
              request(
                  List.of(
                      new ExchangeMessage(List.of(firstUse, secondUse), List.of(first, second)))));

      var parts = contents.get(1).parts().orElseThrow();
      assertThat(parts).hasSize(2);
      assertThat(parts.get(0).functionResponse().orElseThrow().response().orElseThrow())
          .containsEntry("output", "first");
      assertThat(parts.get(1).functionResponse().orElseThrow().response().orElseThrow())
          .containsEntry("output", "second");
    }
  }

  @Nested
  class MixedToolResultsAndOtherBlocks {

    @Test
    void a_tool_result_and_a_following_text_become_two_user_contents_in_order() {
      var toolUse =
          new ToolCallBlock(new ToolCall("call-1", "read_file", MAPPER.createObjectNode()));
      var result = ToolResultBlock.of("call-1", ToolResult.ok("13"));
      var text = new TextBlock("try again");
      var contents =
          GeminiRequests.toContents(
              request(
                  List.of(
                      new ExchangeMessage(List.of(toolUse), List.of(result)),
                      new UserMessage(List.of(text)))));

      // Two contents rather than one, because a tool result is its own message arm now instead
      // of a block sharing a user message with text. Both still go up as role "user", in order,
      // which is what Gemini needs; only the grouping changed.
      assertThat(contents).hasSize(3);

      var response = contents.get(1);
      assertThat(response.role()).contains("user");
      var responseParts = response.parts().orElseThrow();
      assertThat(responseParts).hasSize(1);
      assertThat(responseParts.get(0).functionResponse().orElseThrow().name())
          .contains("read_file");

      var followUp = contents.get(2);
      assertThat(followUp.role()).contains("user");
      var followUpParts = followUp.parts().orElseThrow();
      assertThat(followUpParts).hasSize(1);
      assertThat(followUpParts.get(0).text()).contains("try again");
    }

    static Stream<Arguments> pure_content_messages() {
      return Stream.of(
          Arguments.of(
              new ToolResultMessage(List.of(ToolResultBlock.of("call-1", ToolResult.ok("ok")))),
              true),
          Arguments.of(new UserMessage(List.of(new TextBlock("hello there"))), false));
    }

    @ParameterizedTest
    @MethodSource("pure_content_messages")
    void a_pure_content_message_is_pinned_unchanged(
        Message message, boolean expectFunctionResponse) {
      List<ContextMessage> messages =
          expectFunctionResponse
              ? List.of(
                  new AnswerMessage(
                      List.of(
                          new ToolCallBlock(
                              new ToolCall("call-1", "noop", MAPPER.createObjectNode())))),
                  message)
              : List.of(message);

      var contents = GeminiRequests.toContents(request(messages));

      var lastContent = contents.get(contents.size() - 1);
      assertThat(lastContent.role()).contains("user");
      var parts = lastContent.parts().orElseThrow();
      assertThat(parts).hasSize(1);
      assertThat(parts.get(0).functionResponse().isPresent()).isEqualTo(expectFunctionResponse);
      assertThat(parts.get(0).text().isPresent()).isEqualTo(!expectFunctionResponse);
    }
  }

  @Nested
  class Tools {

    private static StubTool toolSpec(String name) {
      ObjectNode schema = MAPPER.createObjectNode();
      schema.put("type", "object");
      var properties = schema.putObject("properties");
      properties.putObject("path").put("type", "string");
      properties.putObject("lineCount").put("type", "integer");
      schema.putArray("required").add("path");
      return new StubTool(name, "does things called " + name, schema);
    }

    @Test
    void becomes_a_function_declaration_carrying_the_schema_as_is() {
      var spec = toolSpec("read_file");
      var config = GeminiRequests.toConfig(request(List.of(), List.of(spec)));

      var tools = config.tools().orElseThrow();
      assertThat(tools).hasSize(1);
      var declarations = tools.get(0).functionDeclarations().orElseThrow();
      assertThat(declarations).hasSize(1);
      var declaration = declarations.get(0);
      assertThat(declaration.name()).contains("read_file");
      assertThat(declaration.description()).contains("does things called read_file");
      // Node equality, not just presence: a wrong implementation that substituted an empty or
      // reshaped schema would still pass an isPresent()-only check.
      assertThat(declaration.parametersJsonSchema()).contains(spec.inputSchema());
    }

    @Test
    void no_tools_means_the_config_carries_none() {
      var config = GeminiRequests.toConfig(request(List.of()));

      assertThat(config.tools()).isEmpty();
    }
  }
}
