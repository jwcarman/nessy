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
package org.jwcarman.nessy.inference.gemini;

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
import java.util.stream.Stream;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.ToolOffer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turn-speak into the Gemini Developer API's {@code generateContent} shape: a list of {@link
 * Content} and a {@link GenerateContentConfig}.
 *
 * <p>Pure translation, and the only place in this module that knows what a role is. Nothing here
 * touches the network, which is what makes the whole projection testable without a key.
 *
 * <p>Two roles, {@code user} and {@code model}, and a system instruction beside them. A tool's
 * result is a {@code functionResponse} part on a user turn, addressed by the function's
 * <em>name</em> rather than the call's id, which is why an exchange is translated as a unit: the
 * names are on the calls it carries.
 */
public final class GeminiRequests {

  private static final String USER = "user";
  private static final String MODEL = "model";

  /**
   * Google's documented sentinel that tells the API to skip thought-signature validation for one
   * function-call part, rather than reject a replayed history that carries no signature: a call
   * that predates capture, or one another vendor made. Validation is skipped for that call only.
   */
  private static final byte[] SKIP_THOUGHT_SIGNATURE_VALIDATOR =
      "skip_thought_signature_validator".getBytes(StandardCharsets.UTF_8);

  private GeminiRequests() {}

  /** The conversation: every summary, then every turn, as the wire wants them. */
  public static List<Content> toContents(InferenceRequest request, JsonMapper mapper) {
    return Stream.concat(
            request.context().summaries().stream().map(GeminiRequests::summary),
            request.context().turns().stream().flatMap(turn -> turn(turn, mapper)))
        .toList();
  }

  /**
   * The standing instruction, the background, the token cap and the tools on offer.
   *
   * <p>Ambient content goes into the system instruction as its own labelled part rather than into
   * the conversation, because it is true now and was not said by anyone.
   */
  public static GenerateContentConfig toConfig(InferenceRequest request, JsonMapper mapper) {
    GenerateContentConfig.Builder builder = GenerateContentConfig.builder();
    if (request.options().hasMaxTokens()) {
      builder.maxOutputTokens(request.options().maxTokens());
    }
    List<Part> instruction = new ArrayList<>();
    instruction.add(Part.fromText(request.systemPrompt().value()));
    for (Ambient ambient : request.context().ambient()) {
      String text = text(ambient.content());
      if (!text.isBlank()) {
        instruction.add(
            Part.fromText(
                "<%s>\n%s\n</%s>".formatted(ambient.kind(), text.strip(), ambient.kind())));
      }
    }
    builder.systemInstruction(Content.builder().parts(instruction).build());
    if (request.hasTools()) {
      builder.tools(
          List.of(
              Tool.builder()
                  .functionDeclarations(
                      request.tools().stream().map(offer -> declaration(offer, mapper)).toList())
                  .build()));
    }
    return builder.build();
  }

  /**
   * The schema goes onto {@code parametersJsonSchema} whole, as plain maps and lists: this SDK
   * accepts a raw JSON Schema object there and serialises it with its own Jackson, which is not
   * this project's Jackson, so nothing typed crosses the boundary.
   */
  private static FunctionDeclaration declaration(ToolOffer offer, JsonMapper mapper) {
    Map<String, Object> schema = mapper.readValue(offer.schema().json(), new TypeReference<>() {});
    return FunctionDeclaration.builder()
        .name(offer.name().value())
        .description(offer.description())
        .parametersJsonSchema(schema)
        .build();
  }

  // ---- the conversation ----------------------------------------------------------------

  /**
   * A summary, standing where the turns it replaces once stood: user role and bracketed, because
   * {@code user} is the role that reads as something the model is being shown, and the tag and the
   * range tell it from a question.
   */
  private static Content summary(Summary summary) {
    return Content.builder()
        .role(USER)
        .parts(
            List.of(
                Part.fromText(
                    "<summary from=\"%d\" through=\"%d\">\n%s\n</summary>"
                        .formatted(
                            summary.from().value(),
                            summary.through().value(),
                            text(summary.content())))))
        .build();
  }

  /**
   * One turn, as this provider wants to be asked.
   *
   * <p>A turn that <b>failed</b> gets a model message saying so, so its question and the next
   * turn's question are not two user messages running together. A turn that was <b>refused</b> is
   * omitted whole, question included, because the question is what caused the refusal, and
   * re-sending it keeps the conversation refused for as long as it is in the request.
   */
  private static Stream<Content> turn(Turn turn, JsonMapper mapper) {
    if (turn.result() instanceof TurnResult.Refused) {
      return Stream.of();
    }
    Stream<Content> opening = content(USER, turn.observation().blocks(), mapper).stream();
    Stream<Content> middle =
        turn.exchanges().stream().flatMap(exchange -> exchange(exchange, mapper));
    Stream<Content> ending =
        switch (turn.result()) {
          case null -> Stream.of();
          case TurnResult.Answered(var blocks) -> content(MODEL, blocks, mapper).stream();
          case TurnResult.Failed _ ->
              Stream.of(
                  Content.builder()
                      .role(MODEL)
                      .parts(
                          List.of(
                              Part.fromText("(The previous attempt to answer did not complete.)")))
                      .build());
          case TurnResult.Refused _ -> Stream.of();
        };
    return Stream.concat(opening, Stream.concat(middle, ending));
  }

  /**
   * One round: the model asking, then the results coming back on a user turn, one {@code
   * functionResponse} part per result, each addressed by the name of the function it answers.
   */
  private static Stream<Content> exchange(Exchange exchange, JsonMapper mapper) {
    Optional<Content> asking = content(MODEL, exchange.request(), mapper);
    Map<CallId, ToolName> names = new HashMap<>();
    for (Block.ToolCall call : exchange.calls()) {
      names.put(call.id(), call.name());
    }
    List<Part> results =
        exchange.outcomes().stream().map(outcome -> answering(outcome, names)).toList();
    if (results.isEmpty()) {
      return asking.stream();
    }
    Content answering = Content.builder().role(USER).parts(results).build();
    return Stream.concat(asking.stream(), Stream.of(answering));
  }

  /**
   * One result, addressed to the function it answers.
   *
   * <p>The response map is what the model reads. {@code output} carries a tool's answer and {@code
   * error} carries a failure or a denial, so the model is told when the content is not the tool's
   * answer; what the two are is still distinguished in the words.
   */
  private static Part answering(ToolOutcome outcome, Map<CallId, ToolName> names) {
    ToolName name = names.get(outcome.callId());
    if (name == null) {
      throw new IllegalArgumentException(
          "a tool result answers a call this exchange did not make: " + outcome.callId());
    }
    Map<String, Object> response =
        switch (outcome) {
          case ToolOutcome.Succeeded(var _, var blocks) -> Map.of("output", text(blocks));
          case ToolOutcome.Failed(var _, String message) -> Map.of("error", message);
          case ToolOutcome.Denied(var _, String reason) ->
              Map.of("error", "This call was not run because it was not permitted: " + reason);
        };
    return Part.fromFunctionResponse(name.value(), response);
  }

  /**
   * A run of blocks as one content, or nothing if none of them travels on this wire. Gemini ties a
   * thought signature to the call it belongs to, so this adapter's provider blocks are indexed by
   * call before the parts are built and replayed onto the calls they vouch for.
   */
  private static Optional<Content> content(
      String role, List<? extends Block> blocks, JsonMapper mapper) {
    Map<String, byte[]> signatures = signaturesByCall(blocks, mapper);
    List<Part> parts = new ArrayList<>();
    for (Block block : blocks) {
      switch (block) {
        case Block.Text(String text) -> {
          if (!text.isEmpty()) {
            parts.add(Part.fromText(text));
          }
        }
        case Block.Commentary(String text) -> {
          if (!text.isEmpty()) {
            parts.add(Part.fromText(text));
          }
        }
        case Block.ToolCall(CallId id, ToolName name, String arguments) ->
            parts.add(call(id, name, arguments, signatures.get(id.value()), mapper));
        case Block.Provider _ -> {
          // Carried on the call it vouches for, or another vendor's and not ours to send.
        }
      }
    }
    return parts.isEmpty()
        ? Optional.empty()
        : Optional.of(Content.builder().role(role).parts(parts).build());
  }

  private static Map<String, byte[]> signaturesByCall(
      List<? extends Block> blocks, JsonMapper mapper) {
    Map<String, byte[]> signatures = new HashMap<>();
    for (Block block : blocks) {
      if (block instanceof Block.Provider(String vendor, String payload)
          && GeminiInferenceProvider.PROVIDER_NAME.equals(vendor)) {
        Map<String, Object> data = mapper.readValue(payload, new TypeReference<>() {});
        if ("thought-signature".equals(data.get("type"))) {
          try {
            signatures.put(
                String.valueOf(data.get("callId")),
                Base64.getDecoder().decode(String.valueOf(data.get("signature"))));
          } catch (IllegalArgumentException _) {
            // Not base64: replayed unsigned below rather than failing the whole request and
            // making this history permanently unreplayable through Gemini.
          }
        }
      }
    }
    return signatures;
  }

  /**
   * A call as the model made it, with its signature back on it. Absent means the call predates
   * capture or another vendor made it, not "no continuity wanted", so the sentinel goes on instead
   * of nothing, which the API would refuse.
   */
  private static Part call(
      CallId id, ToolName name, String arguments, byte[] signature, JsonMapper mapper) {
    Map<String, Object> args = mapper.readValue(arguments, new TypeReference<>() {});
    return Part.builder()
        .functionCall(FunctionCall.builder().id(id.value()).name(name.value()).args(args).build())
        .thoughtSignature(signature != null ? signature : SKIP_THOUGHT_SIGNATURE_VALIDATOR.clone())
        .build();
  }

  /** The text of a run of blocks, and only the text. */
  static String text(List<? extends Block> blocks) {
    StringBuilder text = new StringBuilder();
    for (Block block : blocks) {
      switch (block) {
        case Block.Text(String value) -> text.append(value);
        case Block.Commentary(String value) -> text.append(value);
        case Block.Provider _, Block.ToolCall _ -> {
          // Not something a person reads.
        }
      }
    }
    return text.toString();
  }
}
