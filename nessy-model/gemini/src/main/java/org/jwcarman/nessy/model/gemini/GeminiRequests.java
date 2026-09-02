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
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.block.CommentaryBlock;
import org.jwcarman.nessy.api.block.ProviderBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.block.ToolResultBlock;
import org.jwcarman.nessy.api.block.ToolResultContentBlock;
import org.jwcarman.nessy.api.message.AmbientMessage;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.ContextMessage;
import org.jwcarman.nessy.api.message.ExchangeMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
  private static final Logger LOGGER = LoggerFactory.getLogger(GeminiRequests.class);

  /**
   * Google's documented sentinel that tells the Gemini API to skip thought-signature validation for
   * a function-call part, rather than reject a replayed history that carries no signature — one
   * predating this project's signature capture, or one authored by another provider in a mixed
   * setup. See <a href="https://ai.google.dev/gemini-api/docs/thought-signatures">Google's
   * thought-signatures docs</a>. Tradeoff: validation is skipped for that call only, which degrades
   * — but does not break — the model's reasoning continuity for it.
   */
  private static final byte[] SKIP_THOUGHT_SIGNATURE_VALIDATOR =
      "skip_thought_signature_validator".getBytes(StandardCharsets.UTF_8);

  private GeminiRequests() {}

  /**
   * Maps every {@link Message} in the request's {@link org.jwcarman.nessy.api.message.Context} to a
   * {@link Content}, in order.
   *
   * <p>A tool-result answering turn always maps to exactly one {@code Content} (role {@code
   * "user"}) carrying one {@code functionResponse} part per result, mirroring how the model's own
   * (possibly several) function calls arrived together on one {@code "model"}-role {@code Content}.
   * Every {@link ToolResultBlock} needs the name of the tool it answers — a field {@code
   * ToolResultBlock} itself does not carry — so this method first scans every {@link ToolCallBlock}
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
    Map<CallId, String> callNamesById = collectCallNames(request.context().messages());
    List<Content> contents = new ArrayList<>();
    for (ContextMessage message : request.context().messages()) {
      contents.addAll(toContents(message, callNamesById));
    }
    return contents;
  }

  /**
   * Assembles the {@link GenerateContentConfig}: system instruction, max output tokens, and
   * function declarations built from each {@link org.jwcarman.nessy.api.tool.Tool<?>}'s JSON
   * schema.
   *
   * <p>A blank {@code systemPrompt} omits {@code systemInstruction} entirely, the same "absent is
   * the correct encoding of no system prompt" precedent {@code OpenAiRequests} follows.
   */
  public static GenerateContentConfig toConfig(ModelRequest request) {
    var builder = GenerateContentConfig.builder().maxOutputTokens(request.maxTokens());
    // The standing instruction, then whatever background the context carries. Gemini has a
    // systemInstruction, so that is where ambient content belongs rather than in the conversation.
    List<Part> instruction = new ArrayList<>();
    if (!request.systemPrompt().isBlank()) {
      instruction.add(Part.fromText(request.systemPrompt()));
    }
    for (ContextMessage message : request.context().messages()) {
      if (message instanceof AmbientMessage ambient) {
        String text = flattenText(ambient.content());
        if (!text.isBlank()) {
          // Its own Part, headed by its kind: the part is the boundary, so the kind labels it.
          instruction.add(Part.fromText("[%s]%n%s".formatted(ambient.kind(), text.strip())));
        }
      }
    }
    if (!instruction.isEmpty()) {
      builder.systemInstruction(Content.fromParts(instruction.toArray(new Part[0])));
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
  private static FunctionDeclaration toFunctionDeclaration(
      org.jwcarman.nessy.api.tool.Tool<?> spec) {
    return FunctionDeclaration.builder()
        .name(spec.name())
        .description(spec.description())
        .parametersJsonSchema(spec.inputSchema())
        .build();
  }

  private static Map<CallId, String> collectCallNames(List<ContextMessage> messages) {
    Map<CallId, String> names = new HashMap<>();
    for (ContextMessage message : messages) {
      if (!(message instanceof ExchangeMessage exchange)) {
        continue;
      }
      for (Block block : exchange.content()) {
        if (block instanceof ToolCallBlock(ToolCall call)) {
          names.put(call.id(), call.name());
        }
      }
    }
    return names;
  }

  /**
   * One of our messages becomes as many of Gemini's as its wire needs.
   *
   * <p>An exchange becomes two: the model turn carrying the function calls, then a user turn
   * carrying the responses — Gemini's encoding, and not ours to impose anywhere but here.
   *
   * <p>Ambient content produces nothing: it goes to {@code systemInstruction}.
   */
  private static List<Content> toContents(
      ContextMessage message, Map<CallId, String> callNamesById) {
    return switch (message) {
      case UserMessage(var content) -> toUserContent(content, callNamesById).stream().toList();
      case AnswerMessage(var content) -> toModelContent(content).stream().toList();
      case AmbientMessage _ -> List.of();
      case ExchangeMessage(var content, var results) ->
          java.util.stream.Stream.of(toModelContent(content), toUserContent(results, callNamesById))
              .flatMap(Optional::stream)
              .toList();
    };
  }

  /**
   * A {@code USER}-role {@link Message} may legally mix {@link ToolResultBlock}s with other blocks
   * — the reducer's told-notes flush builds exactly that shape. Unlike Chat Completions, Gemini's
   * user {@code Content} natively holds {@code functionResponse} parts and text parts side by side,
   * so every block maps in place into a single {@code Content}'s part list, preserving the blocks'
   * original relative order. See the class javadoc for how tool results are addressed back to the
   * function they answer.
   */
  private static Optional<Content> toUserContent(
      List<? extends Block> content, Map<CallId, String> callNamesById) {
    var parts = content.stream().map(block -> toUserPart(block, callNamesById)).toList();
    return Optional.of(Content.builder().role("user").parts(parts).build());
  }

  private static Part toUserPart(Block block, Map<CallId, String> callNamesById) {
    return switch (block) {
      case TextBlock(String text) -> Part.fromText(text);
      case ToolResultBlock result -> toFunctionResponsePart(result, callNamesById);
      default ->
          throw new IllegalArgumentException(
              "unsupported content block in a user message: " + block);
    };
  }

  /**
   * A tool's answer as the text a function response carries.
   *
   * <p>This is the block that made the widening dangerous rather than merely incomplete: the
   * response map takes {@code Object}, so handing it the block list would have COMPILED and then
   * serialised a list of records onto the wire. Rendering is explicit here for that reason.
   *
   * <p>Text only: ToolResultContentBlock permits nothing else, so there is no longer an image to
   * substitute a placeholder for.
   */
  private static String flatten(ToolResultBlock result) {
    var rendered = new ArrayList<String>(result.content().size());
    for (ToolResultContentBlock block : result.content()) {
      switch (block) {
        case TextBlock(String text) -> rendered.add(text);
      }
    }
    return String.join("\n", rendered);
  }

  private static Part toFunctionResponsePart(
      ToolResultBlock result, Map<CallId, String> callNamesById) {
    String name = callNamesById.get(result.toolUseId());
    if (name == null) {
      throw new IllegalArgumentException(
          "tool result for an unknown call id: " + result.toolUseId());
    }
    String rendered = flatten(result);
    Map<String, Object> response = Map.of(result.isError() ? "error" : "output", rendered);
    return Part.fromFunctionResponse(name, response);
  }

  private static Optional<Content> toModelContent(List<? extends Block> content) {
    // Gemini ties a thought signature to a specific function call, so the state blocks are indexed
    // by the call they belong to before the parts are built.
    Map<CallId, String> signatures = signaturesByCallId(content);
    List<Part> parts = new ArrayList<>();
    for (Block block : content) {
      toModelPart(block, signatures).ifPresent(parts::add);
    }
    if (parts.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(Content.builder().role("model").parts(parts).build());
  }

  /**
   * Every signature this provider issued, by the call it vouches for.
   *
   * <p>A block another provider issued is skipped — a transcript outlives a model choice, and a
   * rival's opaque state means nothing here.
   */
  private static Map<CallId, String> signaturesByCallId(List<? extends Block> content) {
    Map<CallId, String> signatures = new java.util.HashMap<>();
    for (Block block : content) {
      if (block instanceof ProviderBlock(String provider, JsonNode data)
          && GeminiModelProvider.PROVIDER.equals(provider)) {
        signatures.put(
            CallId.of(data.path("callId").asText()), data.path("thoughtSignature").asText());
      }
    }
    return signatures;
  }

  private static Optional<Part> toModelPart(Block block, Map<CallId, String> signatures) {
    return switch (block) {
      case TextBlock(String text) -> Optional.of(Part.fromText(text));
      case CommentaryBlock(String text) -> Optional.of(Part.fromText(text));
      case ToolCallBlock(ToolCall call) ->
          Optional.of(toFunctionCallPart(call, signatures.get(call.id())));
      case ProviderBlock _ -> Optional.empty();
      default ->
          throw new IllegalArgumentException(
              "unsupported content block in an assistant message: " + block);
    };
  }

  /**
   * Rebuilds a {@code functionCall} {@link Part}, replaying the block's stored signature (decoded
   * from base64) onto the part's {@code thoughtSignature} when present. Absent signature means the
   * block predates signature capture or was authored by another provider — not "no continuity token
   * wanted" — so it stamps {@link #SKIP_THOUGHT_SIGNATURE_VALIDATOR} rather than leaving {@code
   * thoughtSignature} unset, which the Gemini API would otherwise reject. A present-but-undecodable
   * signature — e.g. one authored by a provider whose token isn't base64 — is treated the same as
   * absent: logged and replaced with the sentinel, rather than failing the whole request and making
   * that history permanently unreplayable through Gemini.
   */
  private static Part toFunctionCallPart(ToolCall call, String signature) {
    return Part.builder()
        .functionCall(FunctionCall.builder().name(call.name()).args(argumentsOf(call)).build())
        .thoughtSignature(thoughtSignatureFor(call, signature))
        .build();
  }

  private static byte[] thoughtSignatureFor(ToolCall call, String signature) {
    if (signature == null) {
      return SKIP_THOUGHT_SIGNATURE_VALIDATOR.clone();
    }
    try {
      return Base64.getDecoder().decode(signature);
    } catch (IllegalArgumentException e) {
      LOGGER.warn(
          "tool call {} carries a signature that is not valid base64; replaying it unsigned",
          call.id(),
          e);
      return SKIP_THOUGHT_SIGNATURE_VALIDATOR.clone();
    }
  }

  private static Map<String, Object> argumentsOf(ToolCall call) {
    JsonNode arguments = call.arguments();
    return MAPPER.convertValue(arguments, new ArgumentsMapType());
  }

  /** Named so {@code convertValue}'s target type is self-documenting at the call site. */
  private static final class ArgumentsMapType extends TypeReference<Map<String, Object>> {}

  private static String flattenText(List<? extends Block> content) {
    var text = new StringBuilder();
    for (Block block : content) {
      if (block instanceof TextBlock(String value)) {
        text.append(value);
      }
    }
    return text.toString();
  }
}
