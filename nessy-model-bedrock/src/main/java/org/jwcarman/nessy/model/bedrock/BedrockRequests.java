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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.ImageBlock;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.spi.model.ModelRequest;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultStatus;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;

/**
 * Assembles a wire-neutral {@link ModelRequest} into the AWS SDK's {@link ConverseStreamRequest}.
 *
 * <p>This is pure request assembly: it builds an SDK request from a request already fully formed by
 * the harness. It never talks to a client and never sees a credential.
 *
 * <p>{@link ThinkingBlock} and {@link RedactedThinkingBlock} are dropped outright, the same
 * precedent {@code GeminiRequests} and {@code OpenAiRequests} set: this v1 module does not
 * advertise {@link org.jwcarman.nessy.spi.model.Capability#THINKING}, so there is nothing on this
 * wire yet to round-trip them through. {@link ToolUseBlock#signature()} is likewise ignored on
 * replay — Bedrock's Converse API issues no per-call continuity token for this harness to trust
 * back, so there is nothing to thread onto the rebuilt {@code toolUse} block even when a stored
 * signature happens to be present (for instance, a history a different provider originated).
 *
 * <p>{@link ImageBlock} has no mapping here either — {@code IMAGE_INPUT} is not among the
 * capabilities this v1 provider advertises, so a caller that sends one anyway hits {@link
 * #toContentBlock}'s fail-loudly branch rather than being silently dropped, the same contract
 * {@code GeminiRequests} keeps for its own unsupported block.
 */
public final class BedrockRequests {

  private BedrockRequests() {}

  /**
   * Assembles the full {@link ConverseStreamRequest}: model id, messages, system prompt, inference
   * configuration, and tool configuration.
   *
   * <p>A blank {@code systemPrompt} omits the {@code system} field entirely, the same "absent is
   * the correct encoding of no system prompt" precedent {@code GeminiRequests} and {@code
   * AnthropicRequests} follow.
   *
   * <p>{@code inferenceConfig} carries only {@code maxTokens}: {@link ModelRequest} has no
   * temperature field — no provider module in this harness sets one — so there is nothing to thread
   * onto {@link InferenceConfiguration#temperature()}.
   */
  public static ConverseStreamRequest toRequest(ModelRequest request, String modelId) {
    var builder =
        ConverseStreamRequest.builder()
            .modelId(modelId)
            .inferenceConfig(b -> b.maxTokens(request.maxTokens()));

    if (!request.systemPrompt().isBlank()) {
      builder.system(SystemContentBlock.fromText(request.systemPrompt()));
    }

    builder.messages(
        request.context().messages().stream()
            .map(BedrockRequests::toMessage)
            .flatMap(Optional::stream)
            .toList());

    if (!request.tools().isEmpty()) {
      builder.toolConfig(
          b -> b.tools(request.tools().stream().map(BedrockRequests::toTool).toList()));
    }

    return builder.build();
  }

  private static Tool toTool(ToolSpec spec) {
    return Tool.fromToolSpec(
        ToolSpecification.builder()
            .name(spec.name())
            .description(spec.description())
            .inputSchema(ToolInputSchema.fromJson(toDocument(spec.inputSchema())))
            .build());
  }

  /**
   * Maps one {@link org.jwcarman.nessy.api.message.Message} to its SDK form, or nothing at all if
   * every one of its content blocks was itself dropped (see {@link #toContentBlock}).
   *
   * <p>A message with no representable content is elided outright rather than sent with an empty
   * block list: Bedrock rejects an empty {@code content} array, and — more to the point — a message
   * that translated to nothing carries no information for the model to see. The scenario this
   * guards is the same one {@code AnthropicRequests.toMessageParam} and {@code
   * GeminiRequests.toModelContent} document: a resumed session whose thinking was cut off before it
   * was signed settles as an assistant message containing only that one dropped block.
   */
  private static Optional<software.amazon.awssdk.services.bedrockruntime.model.Message> toMessage(
      org.jwcarman.nessy.api.message.Message message) {
    var role = message.role() == Role.USER ? ConversationRole.USER : ConversationRole.ASSISTANT;
    List<ContentBlock> content = message.content();
    var blocks =
        new ArrayList<software.amazon.awssdk.services.bedrockruntime.model.ContentBlock>(
            content.size());
    for (ContentBlock block : content) {
      toContentBlock(block).ifPresent(blocks::add);
    }
    if (blocks.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        software.amazon.awssdk.services.bedrockruntime.model.Message.builder()
            .role(role)
            .content(blocks)
            .build());
  }

  /**
   * Maps one wire-neutral {@link ContentBlock} to its SDK form, or nothing at all.
   *
   * <p>A blank {@link TextBlock} is dropped: Bedrock rejects an empty text block, the same reason
   * {@code AnthropicRequests} drops one. {@link ThinkingBlock} and {@link RedactedThinkingBlock}
   * are dropped per the class javadoc. Every other block round-trips; {@link ImageBlock} fails
   * loudly since this module claims no {@code IMAGE_INPUT} capability.
   */
  private static Optional<software.amazon.awssdk.services.bedrockruntime.model.ContentBlock>
      toContentBlock(ContentBlock block) {
    return switch (block) {
      case TextBlock(String text) ->
          text.isEmpty()
              ? Optional.empty()
              : Optional.of(
                  software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromText(text));
      case ToolUseBlock(ToolCall call, _) ->
          Optional.of(
              software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromToolUse(
                  software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock.builder()
                      .toolUseId(call.id())
                      .name(call.name())
                      .input(toDocument(call.arguments()))
                      .build()));
      case ToolResultBlock(String toolUseId, String content, boolean isError) ->
          Optional.of(
              software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromToolResult(
                  software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock.builder()
                      .toolUseId(toolUseId)
                      .content(ToolResultContentBlock.fromText(content))
                      .status(isError ? ToolResultStatus.ERROR : ToolResultStatus.SUCCESS)
                      .build()));
      case ThinkingBlock _ -> Optional.empty();
      case RedactedThinkingBlock _ -> Optional.empty();
      case ImageBlock _ ->
          throw new IllegalArgumentException("unsupported content block: " + block);
    };
  }

  /**
   * Recursively converts a Jackson {@link JsonNode} into the AWS SDK's own {@link Document} tree.
   * The AWS SDK for Java v2 has no built-in Jackson bridge for {@code Document} — {@link
   * ToolUseBlock}'s {@code input} and {@link ToolInputSchema}'s {@code json} both take a {@code
   * Document}, and {@link ToolSpec#inputSchema()} / {@link ToolCall#arguments()} are both plain
   * Jackson nodes — so this method is the one place that bridges the two tree shapes.
   *
   * <p>Numbers convert via {@link Document#fromNumber(String)} on the node's own text
   * representation rather than through a narrower {@code Number} accessor: it preserves the literal
   * digits exactly as Jackson parsed them (integer or decimal, any width) without this method
   * having to duplicate Jackson's own number-type dispatch.
   */
  private static Document toDocument(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return Document.fromNull();
    }
    if (node.isObject()) {
      var mapBuilder = Document.mapBuilder();
      node.properties()
          .forEach(entry -> mapBuilder.putDocument(entry.getKey(), toDocument(entry.getValue())));
      return mapBuilder.build();
    }
    if (node.isArray()) {
      var listBuilder = Document.listBuilder();
      node.forEach(element -> listBuilder.addDocument(toDocument(element)));
      return listBuilder.build();
    }
    if (node.isBoolean()) {
      return Document.fromBoolean(node.booleanValue());
    }
    if (node.isNumber()) {
      return Document.fromNumber(node.asText());
    }
    return Document.fromString(node.asText());
  }

  /** Named so the object-schema copy below reads as intentional, not a cast of convenience. */
  private static Document toDocument(ObjectNode schema) {
    return toDocument((JsonNode) schema);
  }
}
