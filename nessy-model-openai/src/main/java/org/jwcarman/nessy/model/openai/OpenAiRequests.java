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

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.ImageBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ResultBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles a wire-neutral {@link ModelRequest} into the openai-java SDK's {@link
 * ChatCompletionCreateParams}.
 *
 * <p>This is pure request assembly: it builds params from a request already fully formed by the
 * harness. It never talks to a client and never sees a key.
 *
 * <p>{@link org.jwcarman.nessy.api.message.ThinkingBlock} and {@link
 * org.jwcarman.nessy.api.message.RedactedThinkingBlock} are dropped outright: Chat Completions has
 * no assistant content type that carries opaque or extended-reasoning payloads, so there is nothing
 * on this wire to round-trip them through.
 */
public final class OpenAiRequests {

  private static final Logger LOG = LoggerFactory.getLogger(OpenAiRequests.class);

  private static final String ERROR_PREFIX = "ERROR: ";

  private OpenAiRequests() {}

  /**
   * A blank {@code systemPrompt} omits the leading system message entirely, mirroring the Anthropic
   * module's precedent: an absent system prompt is the correct encoding of "no system prompt", not
   * an empty one.
   */
  public static ChatCompletionCreateParams toParams(ModelRequest request, String modelId) {
    var messages = new ArrayList<ChatCompletionMessageParam>();
    if (!request.systemPrompt().isBlank()) {
      messages.add(
          ChatCompletionMessageParam.ofSystem(
              ChatCompletionSystemMessageParam.builder().content(request.systemPrompt()).build()));
    }
    for (Message message : request.context().messages()) {
      messages.addAll(toMessageParams(message));
    }

    var builder =
        ChatCompletionCreateParams.builder()
            .model(modelId)
            .maxCompletionTokens(request.maxTokens())
            .messages(messages)
            .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());

    request.tools().forEach(spec -> builder.addTool(toFunctionTool(spec)));

    return builder.build();
  }

  private static List<ChatCompletionMessageParam> toMessageParams(Message message) {
    return switch (message.role()) {
      case USER -> toUserRoleMessageParams(message.content());
      case ASSISTANT ->
          toAssistantMessageParam(message.content()).map(List::of).orElseGet(List::of);
    };
  }

  /**
   * A {@code USER}-role {@link Message} may legally mix {@link ToolResultBlock}s with other blocks
   * — the reducer's told-notes flush builds exactly that shape. Every {@link ToolResultBlock}
   * becomes its own {@code tool}-role message, placed FIRST: OpenAI requires tool-role messages to
   * appear directly after the assistant {@code tool_calls} message they answer. Any remaining,
   * non-tool-result blocks follow as one {@code user}-role message via {@link #toUserMessageParam},
   * preserving their original relative order; if none remain, no user message is emitted.
   */
  private static List<ChatCompletionMessageParam> toUserRoleMessageParams(
      List<ContentBlock> content) {
    var toolMessages =
        content.stream()
            .filter(ToolResultBlock.class::isInstance)
            .map(ToolResultBlock.class::cast)
            .map(OpenAiRequests::toToolMessageParam)
            .toList();
    var remainder = content.stream().filter(block -> !(block instanceof ToolResultBlock)).toList();
    if (remainder.isEmpty()) {
      return toolMessages;
    }
    var messages = new ArrayList<ChatCompletionMessageParam>(toolMessages);
    messages.add(ChatCompletionMessageParam.ofUser(toUserMessageParam(remainder)));
    return messages;
  }

  private static ChatCompletionUserMessageParam toUserMessageParam(List<ContentBlock> content) {
    var builder = ChatCompletionUserMessageParam.builder();
    if (content.stream().anyMatch(ImageBlock.class::isInstance)) {
      var parts = content.stream().map(OpenAiRequests::toContentPart).toList();
      builder.content(ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(parts));
    } else {
      builder.content(concatenateText(content));
    }
    return builder.build();
  }

  private static ChatCompletionContentPart toContentPart(ContentBlock block) {
    return switch (block) {
      case TextBlock(String text) ->
          ChatCompletionContentPart.ofText(
              ChatCompletionContentPartText.builder().text(text).build());
      case ImageBlock image ->
          ChatCompletionContentPart.ofImageUrl(
              ChatCompletionContentPartImage.builder()
                  .imageUrl(
                      ChatCompletionContentPartImage.ImageUrl.builder().url(dataUri(image)).build())
                  .build());
      default ->
          throw new IllegalArgumentException(
              "unsupported content block in a user message: " + block);
    };
  }

  private static String dataUri(ImageBlock image) {
    return "data:%s;base64,%s".formatted(image.mediaType(), image.base64Data());
  }

  /**
   * A tool's answer as the one string this wire has room for.
   *
   * <p>The chat-completions tool message takes text and nothing else, so an image a tool returned
   * cannot travel. <b>It is replaced by a visible placeholder and logged, never dropped in
   * silence</b> — a model reasoning about a screenshot it was never shown produces confident
   * nonsense, and the only thing worse than losing the image is losing it invisibly.
   */
  private static String flatten(ToolResultBlock result) {
    var rendered = new ArrayList<String>(result.content().size());
    for (ResultBlock block : result.content()) {
      switch (block) {
        case TextBlock(String text) -> rendered.add(text);
        case ImageBlock(String mediaType, String data) -> {
          LOG.warn(
              "tool result {} carried a {} image ({} base64 chars) that this wire cannot send;"
                  + " substituting a placeholder",
              result.toolUseId(),
              mediaType,
              data.length());
          rendered.add("[image omitted: %s, not supported by this provider]".formatted(mediaType));
        }
      }
    }
    return String.join("\n", rendered);
  }

  private static ChatCompletionMessageParam toToolMessageParam(ToolResultBlock result) {
    var content = result.isError() ? ERROR_PREFIX + flatten(result) : flatten(result);
    return ChatCompletionMessageParam.ofTool(
        ChatCompletionToolMessageParam.builder()
            .toolCallId(result.toolUseId())
            .content(content)
            .build());
  }

  /**
   * Maps an assistant {@link Message}'s content to its param form, or nothing at all if it carries
   * no text and no tool calls once {@link org.jwcarman.nessy.api.message.ThinkingBlock} and {@link
   * org.jwcarman.nessy.api.message.RedactedThinkingBlock} content (this wire has no home for
   * either, per the class javadoc) is left behind.
   *
   * <p>The scenario this guards is a lone unsigned {@code ThinkingBlock}: a resumed session whose
   * thinking was cut off before it was signed settles as an assistant message containing only that
   * one block, which has nothing to translate to here, leaving an otherwise-empty param that would
   * carry no information for the model to see.
   */
  private static Optional<ChatCompletionMessageParam> toAssistantMessageParam(
      List<ContentBlock> content) {
    var builder = ChatCompletionAssistantMessageParam.builder();
    var text = concatenateText(content);
    if (!text.isEmpty()) {
      builder.content(text);
    }
    var toolCalls =
        content.stream()
            .filter(ToolUseBlock.class::isInstance)
            .map(ToolUseBlock.class::cast)
            .map(OpenAiRequests::toToolCall)
            .toList();
    if (text.isEmpty() && toolCalls.isEmpty()) {
      return Optional.empty();
    }
    toolCalls.forEach(builder::addToolCall);
    return Optional.of(ChatCompletionMessageParam.ofAssistant(builder.build()));
  }

  private static String concatenateText(List<ContentBlock> content) {
    var builder = new StringBuilder();
    for (ContentBlock block : content) {
      if (block instanceof TextBlock(String text)) {
        builder.append(text);
      }
    }
    return builder.toString();
  }

  private static ChatCompletionMessageFunctionToolCall toToolCall(ToolUseBlock toolUse) {
    var call = toolUse.call();
    return ChatCompletionMessageFunctionToolCall.builder()
        .id(call.id())
        .function(
            ChatCompletionMessageFunctionToolCall.Function.builder()
                .name(call.name())
                .arguments(call.arguments().toString())
                .build())
        .build();
  }

  /**
   * Converts a {@link ToolSpec} to a Chat Completions function tool.
   *
   * <p>{@code strict: true} is deliberately never set here. Strict mode imposes its own rules on
   * the schema (every property required, unions expressed as nullable types) that {@code
   * ToolSpec.inputSchema()} was not built to satisfy; wiring it up is a later, deliberate feature.
   */
  private static ChatCompletionTool toFunctionTool(ToolSpec spec) {
    return ChatCompletionTool.ofFunction(
        ChatCompletionFunctionTool.builder()
            .function(
                FunctionDefinition.builder()
                    .name(spec.name())
                    .description(spec.description())
                    .parameters(toFunctionParameters(spec.inputSchema()))
                    .build())
            .build());
  }

  /** Copies the schema's top-level fields onto the SDK's parameters object as-is. */
  private static FunctionParameters toFunctionParameters(ObjectNode schema) {
    var builder = FunctionParameters.builder();
    for (var property : schema.properties()) {
      builder.putAdditionalProperty(property.getKey(), JsonValue.fromJsonNode(property.getValue()));
    }
    return builder.build();
  }
}
