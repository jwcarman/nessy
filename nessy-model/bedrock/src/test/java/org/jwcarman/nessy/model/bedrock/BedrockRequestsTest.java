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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.block.CommentaryBlock;
import org.jwcarman.nessy.api.block.ImageBlock;
import org.jwcarman.nessy.api.block.ProviderBlock;
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
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultStatus;

class BedrockRequestsTest {
  /** Opaque state from another provider: nothing here should read or replay it. */
  private static ProviderBlock providerState(String type, String thinking, String signature) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("type", type);
    payload.put("thinking", thinking);
    payload.put("signature", signature);
    return new ProviderBlock("anthropic", payload);
  }

  private static ProviderBlock redactedState(String data) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("type", "redacted_thinking");
    payload.put("data", data);
    return new ProviderBlock("anthropic", payload);
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String MODEL_ID = "us.anthropic.claude-haiku-4-5-20251001-v1:0";

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
    void becomes_a_system_content_block() {
      var built = BedrockRequests.toRequest(request(List.of()), MODEL_ID);

      assertThat(built.system()).hasSize(1);
      assertThat(built.system().get(0).text()).isEqualTo("you are a helpful assistant");
    }

    @Test
    void a_blank_system_prompt_omits_the_system_field_entirely() {
      var built = BedrockRequests.toRequest(requestWithSystemPrompt(""), MODEL_ID);

      assertThat(built.hasSystem()).isFalse();
    }

    @Test
    void a_whitespace_only_system_prompt_omits_the_system_field_entirely() {
      var built = BedrockRequests.toRequest(requestWithSystemPrompt("   "), MODEL_ID);

      assertThat(built.hasSystem()).isFalse();
    }
  }

  @Nested
  class MaxTokens {

    @Test
    void passes_through_unchanged() {
      var built = BedrockRequests.toRequest(request(List.of()), MODEL_ID);

      assertThat(built.inferenceConfig().maxTokens()).isEqualTo(1024);
    }
  }

  @Nested
  class UserTextMessages {

    @Test
    void become_a_user_message_with_a_text_block() {
      var built =
          BedrockRequests.toRequest(request(List.of(UserMessage.of("hello there"))), MODEL_ID);

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
      var built =
          BedrockRequests.toRequest(
              request(List.of(new UserMessage(List.of(first, second)))), MODEL_ID);

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
      var request = request(List.of(new UserMessage(List.of(image))));

      assertThatThrownBy(() -> BedrockRequests.toRequest(request, MODEL_ID))
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
          BedrockRequests.toRequest(
              request(List.of(new AnswerMessage(List.of(first, second)))), MODEL_ID);

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
      return new ToolCall(CallId.of(id), name, arguments);
    }

    @Test
    void becomes_a_tool_use_block_with_id_name_and_input() {
      var toolUse = new ToolCallBlock(call("call-1", "read_file", "path", "README.md"));
      var assistantTurn =
          new ExchangeMessage(
              List.of(toolUse),
              List.of(ToolResultBlock.of(CallId.of("call-1"), ToolResult.ok("ok"))));
      var built = BedrockRequests.toRequest(request(List.of(assistantTurn)), MODEL_ID);

      var content = built.messages().get(0).content();
      assertThat(content).hasSize(1);
      var block = content.get(0).toolUse();
      assertThat(block.toolUseId()).isEqualTo("call-1");
      assertThat(block.name()).isEqualTo("read_file");
      assertThat(block.input().asMap().get("path").asString()).isEqualTo("README.md");
    }

    @Test
    void a_multi_tool_turn_preserves_call_order_alongside_the_text() {
      var text = new CommentaryBlock("running two tools");
      var first = new ToolCallBlock(call("call-1", "read_file", "path", "a.txt"));
      var second = new ToolCallBlock(call("call-2", "read_file", "path", "b.txt"));
      var assistantTurn =
          new ExchangeMessage(
              List.of(text, first, second),
              List.of(
                  ToolResultBlock.of(CallId.of("call-1"), ToolResult.ok("ok")),
                  ToolResultBlock.of(CallId.of("call-2"), ToolResult.ok("ok"))));
      var built = BedrockRequests.toRequest(request(List.of(assistantTurn)), MODEL_ID);

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
      var toolUse = new ToolCallBlock(new ToolCall(CallId.of("call-1"), "read_file", arguments));
      var assistantTurn =
          new ExchangeMessage(
              List.of(toolUse),
              List.of(ToolResultBlock.of(CallId.of("call-1"), ToolResult.ok("ok"))));
      var built = BedrockRequests.toRequest(request(List.of(assistantTurn)), MODEL_ID);

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

    /** Opaque state belongs to whoever issued it; this adapter issues none and reads none. */
    @Test
    void another_providers_state_is_ignored_on_replay() {
      var foreign = providerState("thinking", "someone else's reasoning", "sig");
      var toolUse =
          new ToolCallBlock(
              new ToolCall(CallId.of("call-1"), "read_file", MAPPER.createObjectNode()));
      var assistantTurn =
          new ExchangeMessage(
              List.of(toolUse),
              List.of(ToolResultBlock.of(CallId.of("call-1"), ToolResult.ok("ok"))));

      // No exception, and the rebuilt block carries the call unchanged — Bedrock's ToolCallBlock
      // has no signature-shaped field for GeminiRequests' equivalent to replay onto.
      var built = BedrockRequests.toRequest(request(List.of(assistantTurn)), MODEL_ID);

      var block = built.messages().get(0).content().get(0).toolUse();
      assertThat(block.toolUseId()).isEqualTo("call-1");
    }
  }

  @Nested
  class ThinkingBlocksAreDropped {

    @Test
    void a_thinking_block_is_dropped_leaving_its_siblings_in_order() {
      var thinking = providerState("thinking", "reasoning about the answer", "sig-123");
      var text = new CommentaryBlock("the visible answer");
      var toolUse =
          new ToolCallBlock(new ToolCall(CallId.of("call-1"), "noop", MAPPER.createObjectNode()));
      var assistantTurn =
          new ExchangeMessage(
              List.of(thinking, text, toolUse),
              List.of(ToolResultBlock.of(CallId.of("call-1"), ToolResult.ok("ok"))));
      var built = BedrockRequests.toRequest(request(List.of(assistantTurn)), MODEL_ID);

      var content = built.messages().get(0).content();
      assertThat(content).hasSize(2);
      assertThat(content.get(0).text()).isEqualTo("the visible answer");
      assertThat(content.get(1).toolUse()).isNotNull();
    }

    @Test
    void a_redacted_thinking_block_is_dropped_leaving_its_siblings_in_order() {
      var redacted = redactedState("opaque-encrypted-payload");
      var text = new TextBlock("the visible answer");
      var built =
          BedrockRequests.toRequest(
              request(List.of(new AnswerMessage(List.of(redacted, text)))), MODEL_ID);

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
      var thinking = providerState("thinking", "cut off before signing", "");
      var built =
          BedrockRequests.toRequest(
              request(List.of(new AnswerMessage(List.of(thinking)))), MODEL_ID);

      assertThat(built.messages()).isEmpty();
    }
  }

  @Nested
  class ToolResultBlocks {

    @Test
    void becomes_a_tool_result_block_addressed_by_the_matching_call_s_id() {
      var toolUse =
          new ToolCallBlock(
              new ToolCall(CallId.of("call-1"), "read_file", MAPPER.createObjectNode()));
      var result = ToolResultBlock.of(CallId.of("call-1"), ToolResult.ok("42"));
      var built =
          BedrockRequests.toRequest(
              request(List.of(new ExchangeMessage(List.of(toolUse), List.of(result)))), MODEL_ID);

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
          new ToolCallBlock(
              new ToolCall(CallId.of("call-1"), "read_file", MAPPER.createObjectNode()));
      var result = ToolResultBlock.of(CallId.of("call-1"), ToolResult.error("file not found"));
      var built =
          BedrockRequests.toRequest(
              request(List.of(new ExchangeMessage(List.of(toolUse), List.of(result)))), MODEL_ID);

      var toolResult = built.messages().get(1).content().get(0).toolResult();
      assertThat(toolResult.status()).isEqualTo(ToolResultStatus.ERROR);
      assertThat(toolResult.content().get(0).text()).isEqualTo("file not found");
    }

    @Test
    void multiple_results_become_sibling_content_blocks_on_one_message_in_order() {
      var firstUse =
          new ToolCallBlock(new ToolCall(CallId.of("call-1"), "noop", MAPPER.createObjectNode()));
      var secondUse =
          new ToolCallBlock(new ToolCall(CallId.of("call-2"), "noop", MAPPER.createObjectNode()));
      var first = ToolResultBlock.of(CallId.of("call-1"), ToolResult.ok("first"));
      var second = ToolResultBlock.of(CallId.of("call-2"), ToolResult.ok("second"));
      var built =
          BedrockRequests.toRequest(
              request(
                  List.of(
                      new ExchangeMessage(List.of(firstUse, secondUse), List.of(first, second)))),
              MODEL_ID);

      var content = built.messages().get(1).content();
      assertThat(content).hasSize(2);
      assertThat(content.get(0).toolResult().content().get(0).text()).isEqualTo("first");
      assertThat(content.get(1).toolResult().content().get(0).text()).isEqualTo("second");
    }
  }

  @Nested
  class MixedToolResultsAndOtherBlocks {

    @Test
    void a_tool_result_and_a_following_text_become_two_user_messages_in_order() {
      var toolUse =
          new ToolCallBlock(
              new ToolCall(CallId.of("call-1"), "read_file", MAPPER.createObjectNode()));
      var result = ToolResultBlock.of(CallId.of("call-1"), ToolResult.ok("13"));
      var text = new TextBlock("try again");
      var built =
          BedrockRequests.toRequest(
              request(
                  List.of(
                      new ExchangeMessage(List.of(toolUse), List.of(result)),
                      new UserMessage(List.of(text)))),
              MODEL_ID);

      // Two messages rather than one, because a tool result is its own message arm now instead
      // of a block sharing a user message with text. Both still go up as role "user", in order;
      // only the grouping changed.
      assertThat(built.messages()).hasSize(3);

      var responseMessage = built.messages().get(1);
      assertThat(responseMessage.roleAsString()).isEqualTo("user");
      assertThat(responseMessage.content()).hasSize(1);
      assertThat(responseMessage.content().get(0).toolResult().toolUseId()).isEqualTo("call-1");

      var followUp = built.messages().get(2);
      assertThat(followUp.roleAsString()).isEqualTo("user");
      assertThat(followUp.content()).hasSize(1);
      assertThat(followUp.content().get(0).text()).isEqualTo("try again");
    }

    /**
     * Results are no longer a message of their own, so the pair this compared has one member left.
     * Each still lands as the right kind of block, which is what it was really pinning.
     */
    @Test
    void a_user_message_lands_as_a_text_block() {
      var built =
          BedrockRequests.toRequest(
              request(List.of(new UserMessage(List.of(new TextBlock("hello there"))))), MODEL_ID);

      var last = built.messages().get(built.messages().size() - 1);
      assertThat(last.roleAsString()).isEqualTo("user");
      assertThat(last.content().get(0).text()).isNotNull();
    }

    @Test
    void an_exchange_lands_its_results_as_a_tool_result_block() {
      var call =
          new ToolCallBlock(new ToolCall(CallId.of("call-1"), "noop", MAPPER.createObjectNode()));
      var exchange =
          new ExchangeMessage(
              List.of(call), List.of(ToolResultBlock.of(CallId.of("call-1"), ToolResult.ok("ok"))));

      var built = BedrockRequests.toRequest(request(List.of(exchange)), MODEL_ID);

      var last = built.messages().get(built.messages().size() - 1);
      assertThat(last.roleAsString()).isEqualTo("user");
      assertThat(last.content().get(0).toolResult()).isNotNull();
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
    void becomes_a_tool_specification_carrying_the_schema_as_a_document() {
      var spec = toolSpec("read_file");
      var built = BedrockRequests.toRequest(request(List.of(), List.of(spec)), MODEL_ID);

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
      var built = BedrockRequests.toRequest(request(List.of()), MODEL_ID);

      assertThat(built.toolConfig()).isNull();
    }
  }
}
