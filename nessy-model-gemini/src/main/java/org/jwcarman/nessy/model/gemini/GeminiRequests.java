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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.spi.model.ModelRequest;

/**
 * Assembles a wire-neutral {@link ModelRequest} into the java-genai SDK's {@code
 * generateContentStream} arguments: a {@link List} of {@link Content} and a {@link
 * GenerateContentConfig}.
 *
 * <p>This is pure request assembly: it builds SDK types from a request already fully formed by the
 * harness. It never talks to a client and never sees a key.
 *
 * <p>{@link ThinkingBlock} and {@link RedactedThinkingBlock} are dropped outright, the same
 * precedent the OpenAI module set: Gemini's thinking/thought-part mapping is deferred (see {@link
 * GeminiModelProvider}'s class javadoc), so there is nothing on this wire yet to round-trip them
 * through.
 *
 * <p>{@link org.jwcarman.nessy.api.message.ImageBlock} has no mapping here either — {@code
 * IMAGE_INPUT} is not among the capabilities this v1 provider advertises, so a caller that sends
 * one anyway hits {@link #toContents}'s fail-loudly default case rather than being silently
 * dropped.
 */
public final class GeminiRequests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private GeminiRequests() {}

  /**
   * Maps every {@link Message} in the request's {@link org.jwcarman.nessy.api.message.Context} to a
   * {@link Content}, in order.
   *
   * <p>A tool-result answering turn always maps to exactly one {@code Content} (role {@code
   * "user"}) carrying one {@code functionResponse} part per result, mirroring how the model's own
   * (possibly several) function calls arrived together on one {@code "model"}-role {@code Content}.
   * Every {@link ToolResultBlock} needs the name of the tool it answers — a field {@code
   * ToolResultBlock} itself does not carry — so this method first scans every {@link ToolUseBlock}
   * in the whole context to build a call-id-to-name lookup before translating anything; {@link
   * org.jwcarman.nessy.api.message.Context}'s own pairing invariant guarantees every result's id is
   * in that map.
   *
   * <p>An assistant turn that carries no text and no tool calls once thinking content is dropped (a
   * resumed session's lone unsigned thinking block, the same scenario {@code
   * OpenAiRequests.toAssistantMessageParam} documents) produces no {@code Content} at all, rather
   * than an empty one with nothing for the model to see.
   */
  public static List<Content> toContents(ModelRequest request) {
    Map<String, String> callNamesById = collectCallNames(request.context().messages());
    List<Content> contents = new ArrayList<>();
    for (Message message : request.context().messages()) {
      toContent(message, callNamesById).ifPresent(contents::add);
    }
    return contents;
  }

  /**
   * Assembles the {@link GenerateContentConfig}: system instruction, max output tokens, and
   * function declarations built from each {@link ToolSpec}'s JSON schema.
   *
   * <p>A blank {@code systemPrompt} omits {@code systemInstruction} entirely, the same "absent is
   * the correct encoding of no system prompt" precedent {@code OpenAiRequests} follows.
   */
  public static GenerateContentConfig toConfig(ModelRequest request) {
    var builder = GenerateContentConfig.builder().maxOutputTokens(request.maxTokens());
    if (!request.systemPrompt().isBlank()) {
      builder.systemInstruction(Content.fromParts(Part.fromText(request.systemPrompt())));
    }
    if (!request.tools().isEmpty()) {
      var declarations =
          request.tools().stream().map(GeminiRequests::toFunctionDeclaration).toList();
      builder.tools(List.of(Tool.builder().functionDeclarations(declarations).build()));
    }
    return builder.build();
  }

  /**
   * Copies the schema onto the SDK's {@code parametersJsonSchema} field as-is: {@link
   * FunctionDeclaration} accepts a raw JSON Schema object there (distinct from its structured
   * {@code parameters} field, which this module never populates), and Jackson serializes a plain
   * {@code ObjectNode} through that {@code Object}-typed setter without any further conversion.
   */
  private static FunctionDeclaration toFunctionDeclaration(ToolSpec spec) {
    return FunctionDeclaration.builder()
        .name(spec.name())
        .description(spec.description())
        .parametersJsonSchema(spec.inputSchema())
        .build();
  }

  private static Map<String, String> collectCallNames(List<Message> messages) {
    Map<String, String> names = new HashMap<>();
    for (Message message : messages) {
      if (message.role() != Role.ASSISTANT) {
        continue;
      }
      for (ContentBlock block : message.content()) {
        if (block instanceof ToolUseBlock(ToolCall call)) {
          names.put(call.id(), call.name());
        }
      }
    }
    return names;
  }

  private static Optional<Content> toContent(Message message, Map<String, String> callNamesById) {
    return switch (message.role()) {
      case USER -> toUserContent(message.content(), callNamesById);
      case ASSISTANT -> toModelContent(message.content());
    };
  }

  /**
   * A {@code USER}-role {@link Message} is, by grammar construction, either an actual user turn
   * (text) or a batch of tool results — never both. See the class javadoc for how tool results are
   * addressed back to the function they answer.
   */
  private static Optional<Content> toUserContent(
      List<ContentBlock> content, Map<String, String> callNamesById) {
    if (content.stream().anyMatch(ToolResultBlock.class::isInstance)) {
      var parts =
          content.stream()
              .map(ToolResultBlock.class::cast)
              .map(result -> toFunctionResponsePart(result, callNamesById))
              .toList();
      return Optional.of(Content.builder().role("user").parts(parts).build());
    }
    var parts = content.stream().map(GeminiRequests::toUserPart).toList();
    return Optional.of(Content.builder().role("user").parts(parts).build());
  }

  private static Part toUserPart(ContentBlock block) {
    return switch (block) {
      case TextBlock(String text) -> Part.fromText(text);
      default ->
          throw new IllegalArgumentException(
              "unsupported content block in a user message: " + block);
    };
  }

  private static Part toFunctionResponsePart(
      ToolResultBlock result, Map<String, String> callNamesById) {
    String name = callNamesById.get(result.toolUseId());
    if (name == null) {
      throw new IllegalArgumentException(
          "tool result for an unknown call id: " + result.toolUseId());
    }
    Map<String, Object> response =
        result.isError() ? Map.of("error", result.content()) : Map.of("output", result.content());
    return Part.fromFunctionResponse(name, response);
  }

  private static Optional<Content> toModelContent(List<ContentBlock> content) {
    List<Part> parts = new ArrayList<>();
    for (ContentBlock block : content) {
      toModelPart(block).ifPresent(parts::add);
    }
    if (parts.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(Content.builder().role("model").parts(parts).build());
  }

  private static Optional<Part> toModelPart(ContentBlock block) {
    return switch (block) {
      case TextBlock(String text) -> Optional.of(Part.fromText(text));
      case ToolUseBlock(ToolCall call) ->
          Optional.of(Part.fromFunctionCall(call.name(), argumentsOf(call)));
      case ThinkingBlock _ -> Optional.empty();
      case RedactedThinkingBlock _ -> Optional.empty();
      default ->
          throw new IllegalArgumentException(
              "unsupported content block in an assistant message: " + block);
    };
  }

  private static Map<String, Object> argumentsOf(ToolCall call) {
    JsonNode arguments = call.arguments();
    return MAPPER.convertValue(arguments, new ArgumentsMapType());
  }

  /** Named so {@code convertValue}'s target type is self-documenting at the call site. */
  private static final class ArgumentsMapType extends TypeReference<Map<String, Object>> {}
}
