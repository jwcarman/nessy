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
package org.jwcarman.nessy.inference.bedrock;

import java.util.ArrayList;
import java.util.Base64;
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
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningTextBlock;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultStatus;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turn-speak into Bedrock's Converse shape.
 *
 * <p>Pure translation, and the only place in this module that knows what a role is. Nothing here
 * touches the network, which is what makes the whole projection testable without a credential.
 *
 * <p><b>This wire insists that roles alternate.</b> Converse rejects two consecutive messages with
 * the same role, and a summary followed by an observation is exactly that, so the drafted messages
 * are coalesced before they are sent: neighbours with one role become one message with both
 * contents. The same rule shapes the two awkward turns: a failed turn is answered for so that two
 * questions do not run together, and a refused turn is left out whole.
 */
public final class BedrockRequests {

  private BedrockRequests() {}

  public static ConverseStreamRequest toRequest(InferenceRequest request, JsonMapper mapper) {
    ConverseStreamRequest.Builder builder =
        ConverseStreamRequest.builder().modelId(request.options().modelName());
    if (request.options().hasMaxTokens()) {
      builder.inferenceConfig(config -> config.maxTokens(request.options().maxTokens()));
    }

    List<SystemContentBlock> system = new ArrayList<>();
    system.add(SystemContentBlock.fromText(request.systemPrompt().value()));
    for (Ambient ambient : request.context().ambient()) {
      String text = text(ambient.content());
      if (!text.isBlank()) {
        system.add(
            SystemContentBlock.fromText(
                "<%s>\n%s\n</%s>".formatted(ambient.kind(), text.strip(), ambient.kind())));
      }
    }
    builder.system(system);

    List<Message> drafted =
        Stream.concat(
                request.context().summaries().stream().map(BedrockRequests::summary),
                request.context().turns().stream().flatMap(turn -> turn(turn, mapper)))
            .toList();
    builder.messages(alternating(drafted));

    if (request.hasTools()) {
      builder.toolConfig(
          ToolConfiguration.builder()
              .tools(request.tools().stream().map(offer -> tool(offer, mapper)).toList())
              .build());
    }
    return builder.build();
  }

  /** Neighbours with one role become one message, so the wire's alternation holds. */
  private static List<Message> alternating(List<Message> drafted) {
    List<Message> merged = new ArrayList<>();
    for (Message message : drafted) {
      Message last = merged.isEmpty() ? null : merged.getLast();
      if (last != null && last.role() == message.role()) {
        List<ContentBlock> content = new ArrayList<>(last.content());
        content.addAll(message.content());
        merged.set(merged.size() - 1, last.toBuilder().content(content).build());
      } else {
        merged.add(message);
      }
    }
    return merged;
  }

  // ---- the conversation ----------------------------------------------------------------

  private static Message summary(Summary summary) {
    return Message.builder()
        .role(ConversationRole.USER)
        .content(
            ContentBlock.fromText(
                "<summary from=\"%d\" through=\"%d\">\n%s\n</summary>"
                    .formatted(
                        summary.from().value(),
                        summary.through().value(),
                        text(summary.content()))))
        .build();
  }

  private static Stream<Message> turn(Turn turn, JsonMapper mapper) {
    if (turn.result() instanceof TurnResult.Refused) {
      return Stream.of();
    }
    Stream<Message> opening =
        message(ConversationRole.USER, turn.observation().blocks(), mapper).stream();
    Stream<Message> middle =
        turn.exchanges().stream().flatMap(exchange -> exchange(exchange, mapper));
    Stream<Message> ending =
        switch (turn.result()) {
          case null -> Stream.of();
          case TurnResult.Answered(var blocks) ->
              message(ConversationRole.ASSISTANT, blocks, mapper).stream();
          case TurnResult.Failed _ ->
              Stream.of(
                  Message.builder()
                      .role(ConversationRole.ASSISTANT)
                      .content(
                          ContentBlock.fromText(
                              "(The previous attempt to answer did not complete.)"))
                      .build());
          case TurnResult.Refused _ -> Stream.of();
        };
    return Stream.concat(opening, Stream.concat(middle, ending));
  }

  /** One round: the assistant asking, then the results coming back as user content. */
  private static Stream<Message> exchange(Exchange exchange, JsonMapper mapper) {
    Optional<Message> asking = message(ConversationRole.ASSISTANT, exchange.request(), mapper);
    List<ContentBlock> results =
        exchange.outcomes().stream().map(BedrockRequests::answering).toList();
    if (results.isEmpty()) {
      return asking.stream();
    }
    Message answering = Message.builder().role(ConversationRole.USER).content(results).build();
    return Stream.concat(asking.stream(), Stream.of(answering));
  }

  /**
   * One result, quoting the call it answers. This wire can say a call went wrong: {@code ERROR}
   * carries both a failure and a denial, because in each case the model is being told the content
   * is not the tool's answer; what the two are is still distinguished in the words.
   */
  private static ContentBlock answering(ToolOutcome outcome) {
    return switch (outcome) {
      case ToolOutcome.Succeeded(CallId id, var blocks) ->
          result(id, text(blocks), ToolResultStatus.SUCCESS);
      case ToolOutcome.Failed(CallId id, String message) ->
          result(id, message, ToolResultStatus.ERROR);
      case ToolOutcome.Denied(CallId id, String reason) ->
          result(
              id,
              "This call was not run because it was not permitted: " + reason,
              ToolResultStatus.ERROR);
    };
  }

  private static ContentBlock result(CallId id, String content, ToolResultStatus status) {
    return ContentBlock.fromToolResult(
        ToolResultBlock.builder()
            .toolUseId(id.value())
            .content(ToolResultContentBlock.fromText(content))
            .status(status)
            .build());
  }

  private static Optional<Message> message(
      ConversationRole role, List<? extends Block> blocks, JsonMapper mapper) {
    List<ContentBlock> content = new ArrayList<>();
    for (Block block : blocks) {
      toContent(block, mapper).ifPresent(content::add);
    }
    return content.isEmpty()
        ? Optional.empty()
        : Optional.of(Message.builder().role(role).content(content).build());
  }

  private static Optional<ContentBlock> toContent(Block block, JsonMapper mapper) {
    return switch (block) {
      case Block.Text(String text) ->
          text.isEmpty() ? Optional.empty() : Optional.of(ContentBlock.fromText(text));
      case Block.Commentary(String text) ->
          text.isEmpty() ? Optional.empty() : Optional.of(ContentBlock.fromText(text));
      case Block.ToolCall(CallId id, ToolName name, String arguments) ->
          Optional.of(
              ContentBlock.fromToolUse(
                  ToolUseBlock.builder()
                      .toolUseId(id.value())
                      .name(name.value())
                      .input(document(mapper.readValue(arguments, Object.class)))
                      .build()));
      case Block.Provider(String vendor, String payload) -> ours(vendor, payload, mapper);
    };
  }

  /**
   * Another vendor's opaque state is not ours to send, and our own must go back exactly as it came.
   * Reasoning is only accepted back with the signature it was issued with, so an unsigned one is
   * dropped rather than sent and rejected.
   */
  private static Optional<ContentBlock> ours(String vendor, String payload, JsonMapper mapper) {
    if (!BedrockInferenceProvider.PROVIDER_NAME.equals(vendor)) {
      return Optional.empty();
    }
    Map<String, Object> data = mapper.readValue(payload, new TypeReference<>() {});
    return switch (String.valueOf(data.get("type"))) {
      case "reasoning" -> {
        String signature = String.valueOf(data.getOrDefault("signature", ""));
        yield signature.isEmpty()
            ? Optional.empty()
            : Optional.of(
                ContentBlock.fromReasoningContent(
                    ReasoningContentBlock.fromReasoningText(
                        ReasoningTextBlock.builder()
                            .text(String.valueOf(data.getOrDefault("text", "")))
                            .signature(signature)
                            .build())));
      }
      case "redacted" ->
          Optional.of(
              ContentBlock.fromReasoningContent(
                  ReasoningContentBlock.fromRedactedContent(
                      SdkBytes.fromByteArray(
                          Base64.getDecoder()
                              .decode(String.valueOf(data.getOrDefault("data", "")))))));
      default -> Optional.empty();
    };
  }

  // ---- tools ---------------------------------------------------------------------------

  private static Tool tool(ToolOffer offer, JsonMapper mapper) {
    return Tool.fromToolSpec(
        ToolSpecification.builder()
            .name(offer.name().value())
            .description(offer.description())
            .inputSchema(
                ToolInputSchema.fromJson(
                    document(mapper.readValue(offer.schema().json(), Object.class))))
            .build());
  }

  /**
   * Plain JSON values into the SDK's own {@link Document} tree. The SDK has no Jackson bridge, and
   * the two sides are on different Jackson majors anyway, so the bridge is maps and lists.
   */
  static Document document(Object value) {
    return switch (value) {
      case null -> Document.fromNull();
      case Map<?, ?> map -> {
        Document.MapBuilder builder = Document.mapBuilder();
        map.forEach((key, entry) -> builder.putDocument(String.valueOf(key), document(entry)));
        yield builder.build();
      }
      case List<?> list -> {
        Document.ListBuilder builder = Document.listBuilder();
        list.forEach(entry -> builder.addDocument(document(entry)));
        yield builder.build();
      }
      case Boolean flag -> Document.fromBoolean(flag);
      case Number number -> Document.fromNumber(number.toString());
      default -> Document.fromString(String.valueOf(value));
    };
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
