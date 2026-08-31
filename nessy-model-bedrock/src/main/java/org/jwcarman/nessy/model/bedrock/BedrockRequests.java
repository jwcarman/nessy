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
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.block.ImageBlock;
import org.jwcarman.nessy.api.block.RedactedThinkingBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ThinkingBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.block.ToolResultBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * wire yet to round-trip them through. {@link ToolCallBlock#signature()} is likewise ignored on
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

  private static final Logger LOG = LoggerFactory.getLogger(BedrockRequests.class);

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

  /**
   * A tool's answer as text.
   *
   * <p>This wire could carry {@code ToolResultContentBlock.fromImage}, but this module does not
   * claim {@code IMAGE_INPUT} at all (see the class javadoc), so an image would be a capability it
   * never advertised.
   *
   * <p><b>An image in a tool result degrades; an image in a prompt still throws.</b> That looks
   * inconsistent and is deliberate: a prompt image is written by a developer and is a mistake worth
   * failing fast on, while a tool image arrives at runtime from a tool that may have had no idea
   * which provider it was feeding. Killing a whole turn over it is the worse of two bad outcomes —
   *
   * <p>Text only: our own ToolResultContentBlock permits nothing else, so there is no longer an
   * image here to substitute a placeholder for.
   */
  private static String flatten(ToolResultBlock result) {
    var rendered = new ArrayList<String>(result.content().size());
    for (org.jwcarman.nessy.api.block.ToolResultContentBlock block : result.content()) {
      switch (block) {
        case TextBlock(String text) -> rendered.add(text);
      }
    }
    return String.join("\n", rendered);
  }

  private static Tool toTool(org.jwcarman.nessy.api.tool.Tool<?> spec) {
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
  /** The blocks of a message, whichever arm it is. */
  private static List<? extends Block> contentOf(org.jwcarman.nessy.api.message.Message message) {
    return switch (message) {
      case org.jwcarman.nessy.api.message.UserMessage user -> user.content();
      case org.jwcarman.nessy.api.message.AssistantMessage assistant -> assistant.content();
      case org.jwcarman.nessy.api.message.ToolResultMessage results -> results.blocks();
    };
  }

  private static Optional<software.amazon.awssdk.services.bedrockruntime.model.Message> toMessage(
      org.jwcarman.nessy.api.message.Message message) {
    // Role is no longer carried on the message; the arm IS the role. Tool results go up as a
    // USER message, which is Bedrock's encoding for them.
    var role =
        message instanceof org.jwcarman.nessy.api.message.AssistantMessage
            ? ConversationRole.ASSISTANT
            : ConversationRole.USER;
    List<? extends Block> content = contentOf(message);
    var blocks =
        new ArrayList<software.amazon.awssdk.services.bedrockruntime.model.ContentBlock>(
            content.size());
    for (Block block : content) {
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
   * Maps one wire-neutral {@link Block} to its SDK form, or nothing at all.
   *
   * <p>A blank {@link TextBlock} is dropped: Bedrock rejects an empty text block, the same reason
   * {@code AnthropicRequests} drops one. {@link ThinkingBlock} and {@link RedactedThinkingBlock}
   * are dropped per the class javadoc. Every other block round-trips; {@link ImageBlock} fails
   * loudly since this module claims no {@code IMAGE_INPUT} capability.
   */
  private static Optional<software.amazon.awssdk.services.bedrockruntime.model.ContentBlock>
      toContentBlock(Block block) {
    return switch (block) {
      case TextBlock(String text) ->
          text.isEmpty()
              ? Optional.empty()
              : Optional.of(
                  software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromText(text));
      case ToolCallBlock(ToolCall call, _) ->
          Optional.of(
              software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromToolUse(
                  software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock.builder()
                      .toolUseId(call.id())
                      .name(call.name())
                      .input(toDocument(call.arguments()))
                      .build()));
      case ToolResultBlock result ->
          Optional.of(
              software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromToolResult(
                  software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock.builder()
                      .toolUseId(result.toolUseId())
                      .content(ToolResultContentBlock.fromText(flatten(result)))
                      .status(result.isError() ? ToolResultStatus.ERROR : ToolResultStatus.SUCCESS)
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
   * ToolCallBlock}'s {@code input} and {@link ToolInputSchema}'s {@code json} both take a {@code
   * Document}, and {@link org.jwcarman.nessy.api.tool.Tool<?>#inputSchema()} / {@link
   * ToolCall#arguments()} are both plain Jackson nodes — so this method is the one place that
   * bridges the two tree shapes.
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
