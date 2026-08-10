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

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.ContentBlock;
import org.jwcarman.nessy.api.ImageBlock;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.RedactedThinkingBlock;
import org.jwcarman.nessy.api.Role;
import org.jwcarman.nessy.api.TextBlock;
import org.jwcarman.nessy.api.ThinkingBlock;
import org.jwcarman.nessy.api.ToolResultBlock;
import org.jwcarman.nessy.api.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelRequest;

/**
 * Assembles a wire-neutral {@link ModelRequest} into the anthropic-java SDK's {@link
 * MessageCreateParams}.
 *
 * <p>This is pure request assembly: it builds params from a request already fully formed by the
 * harness. It never talks to a client and never sees a key.
 */
public final class AnthropicRequests {

  private AnthropicRequests() {}

  /**
   * @param enabled whether extended thinking was requested for this call
   * @param budgetTokens the thinking token budget; ignored when {@code enabled} is {@code false}
   */
  public record ThinkingConfig(boolean enabled, int budgetTokens) {}

  /**
   * A blank {@code systemPrompt} omits the {@code system} field entirely rather than sending an
   * empty text block: Anthropic rejects empty text blocks, and an absent system is the correct
   * encoding of "no system prompt".
   */
  public static MessageCreateParams toParams(ModelRequest request, ThinkingConfig thinking) {
    if (thinking.enabled() && request.maxTokens() <= thinking.budgetTokens()) {
      throw new IllegalArgumentException(
          "maxTokens (%d) must be greater than the thinking budget (%d)"
              .formatted(request.maxTokens(), thinking.budgetTokens()));
    }

    var cachingRequested = request.requested().contains(Capability.PROMPT_CACHING);

    var builder =
        MessageCreateParams.builder().model(request.model()).maxTokens(request.maxTokens());

    if (!request.systemPrompt().isBlank()) {
      builder.systemOfTextBlockParams(
          List.of(systemBlock(request.systemPrompt(), cachingRequested)));
    }

    builder.messages(
        request.context().messages().stream()
            .map(AnthropicRequests::toMessageParam)
            .flatMap(Optional::stream)
            .toList());

    addTools(builder, request.tools(), cachingRequested);

    if (thinking.enabled()) {
      builder.thinking(
          ThinkingConfigEnabled.builder().budgetTokens(thinking.budgetTokens()).build());
    }

    return builder.build();
  }

  private static TextBlockParam systemBlock(String systemPrompt, boolean cachingRequested) {
    var block = TextBlockParam.builder().text(systemPrompt);
    if (cachingRequested) {
      block.cacheControl(ephemeral());
    }
    return block.build();
  }

  private static void addTools(
      MessageCreateParams.Builder builder, List<ToolSpec> tools, boolean cachingRequested) {
    for (int i = 0; i < tools.size(); i++) {
      var spec = tools.get(i);
      var tool =
          Tool.builder()
              .name(spec.name())
              .description(spec.description())
              .inputSchema(AnthropicSchemas.toInputSchema(spec.inputSchema()));
      if (cachingRequested && i == tools.size() - 1) {
        tool.cacheControl(ephemeral());
      }
      builder.addTool(tool.build());
    }
  }

  private static CacheControlEphemeral ephemeral() {
    return CacheControlEphemeral.builder().build();
  }

  /**
   * Maps one {@link Message} to its param form, or nothing at all if every one of its content
   * blocks was itself dropped (see {@link #toContentBlockParam}).
   *
   * <p>A message with no representable content is elided outright rather than sent as a param with
   * an empty block list: Anthropic rejects an empty {@code content} array, and — more to the point
   * — a message that translated to nothing carries no information for the model to see. The
   * scenario this guards is a lone unsigned {@link ThinkingBlock}: a resumed session whose thinking
   * was cut off before it was signed settles as an assistant message containing only that one
   * block, which {@link #toContentBlockParam} drops, leaving nothing behind.
   */
  private static Optional<MessageParam> toMessageParam(Message message) {
    var role = message.role() == Role.USER ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT;
    var blocks = new ArrayList<ContentBlockParam>();
    for (ContentBlock block : message.content()) {
      toContentBlockParam(block).ifPresent(blocks::add);
    }
    if (blocks.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(MessageParam.builder().role(role).contentOfBlockParams(blocks).build());
  }

  /**
   * Maps one {@link ContentBlock} to its param form, or nothing at all.
   *
   * <p>An unsigned {@link ThinkingBlock} — signature the empty string — is one block dropped
   * outright: it means the transcript predates response signing, and Anthropic rejects unsigned
   * thinking on replay. A blank {@link TextBlock} is dropped for the same reason {@code
   * systemBlock} never sends one: Anthropic rejects empty text blocks. Every other block
   * round-trips.
   */
  private static Optional<ContentBlockParam> toContentBlockParam(ContentBlock block) {
    return switch (block) {
      case TextBlock text ->
          text.text().isEmpty()
              ? Optional.empty()
              : Optional.of(
                  ContentBlockParam.ofText(TextBlockParam.builder().text(text.text()).build()));
      case ImageBlock image ->
          Optional.of(
              ContentBlockParam.ofImage(
                  ImageBlockParam.builder()
                      .source(
                          Base64ImageSource.builder()
                              .mediaType(Base64ImageSource.MediaType.of(image.mediaType()))
                              .data(image.base64Data())
                              .build())
                      .build()));
      case ThinkingBlock thinking ->
          thinking.signature().isEmpty()
              ? Optional.empty()
              : Optional.of(
                  ContentBlockParam.ofThinking(
                      ThinkingBlockParam.builder()
                          .thinking(thinking.text())
                          .signature(thinking.signature())
                          .build()));
      case RedactedThinkingBlock redacted ->
          Optional.of(
              ContentBlockParam.ofRedactedThinking(
                  RedactedThinkingBlockParam.builder().data(redacted.data()).build()));
      case ToolUseBlock toolUse ->
          Optional.of(
              ContentBlockParam.ofToolUse(
                  ToolUseBlockParam.builder()
                      .id(toolUse.call().id())
                      .name(toolUse.call().name())
                      .input(toInput(toolUse.call().arguments()))
                      .build()));
      case ToolResultBlock toolResult ->
          Optional.of(
              ContentBlockParam.ofToolResult(
                  ToolResultBlockParam.builder()
                      .toolUseId(toolResult.toolUseId())
                      .content(toolResult.content())
                      .isError(toolResult.isError())
                      .build()));
    };
  }

  private static ToolUseBlockParam.Input toInput(JsonNode arguments) {
    var input = ToolUseBlockParam.Input.builder();
    if (arguments instanceof ObjectNode object) {
      for (var property : object.properties()) {
        input.putAdditionalProperty(property.getKey(), JsonValue.fromJsonNode(property.getValue()));
      }
    }
    return input.build();
  }
}
