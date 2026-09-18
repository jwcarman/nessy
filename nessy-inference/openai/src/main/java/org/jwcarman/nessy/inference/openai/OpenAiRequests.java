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
package org.jwcarman.nessy.inference.openai;

import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.ToolOffer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turn-speak into chat-completions shape.
 *
 * <p>Pure translation, and the only place in this module that knows what a "role" is. Everything
 * above it speaks in turns, exchanges and blocks; everything below it speaks OpenAI's wire. Nothing
 * here touches the network, which is what makes the whole projection testable without a key.
 */
public final class OpenAiRequests {

  private OpenAiRequests() {}

  /**
   * @param mapper reads a tool's schema back into a document. {@link
   *     org.jwcarman.nessy.api.tool.InputSchema} carries JSON text on purpose -- a tree would have
   *     to be some library's tree, and this wire's is not the one this project speaks -- so the
   *     parse is the bridge between the two, not a validation step. Supplied rather than made here,
   *     because a mapper an application cannot configure is a mapper it cannot fix.
   */
  public static ChatCompletionCreateParams toParams(InferenceRequest request, JsonMapper mapper) {
    InferenceOptions options = request.options();

    List<ChatCompletionMessageParam> messages =
        Stream.concat(
                Stream.of(
                    ChatCompletionMessageParam.ofSystem(
                        ChatCompletionSystemMessageParam.builder()
                            .content(system(request))
                            .build())),
                Stream.concat(
                    request.context().summaries().stream().map(OpenAiRequests::summary),
                    request.context().turns().stream().flatMap(OpenAiRequests::toMessages)))
            .toList();

    ChatCompletionCreateParams.Builder builder =
        ChatCompletionCreateParams.builder().model(options.modelName()).messages(messages);
    // Streamed, usage arrives only when asked for, on a final chunk of its own.
    builder.streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());

    // Omitted rather than sent as zero when no ceiling was asked for: zero is a real value to
    // this API and would ask for an empty answer.
    if (options.hasMaxTokens()) {
      builder.maxCompletionTokens(options.maxTokens());
    }
    request.tools().forEach(offer -> builder.addTool(toFunctionTool(offer, mapper)));
    return builder.build();
  }

  /**
   * The system prompt, and whatever background stands behind the conversation.
   *
   * <p><b>Folded into the system message, and that is this adapter's decision alone.</b> This wire
   * has one place for anything nobody said, so background goes there. Anthropic would put it in the
   * top-level system block and Gemini in a system instruction; {@link Ambient} says what the
   * background is and takes no view on any of that.
   *
   * <p>Labelled with tags so a model reading two unlabelled blobs run together can tell which is
   * the standing instruction and which is today's note. The kind is safe to interpolate without
   * escaping -- {@code Ambient} constrains it to lowercase kebab-case precisely so no adapter has
   * to remember to, and none can forget.
   *
   * <p>Sections are omitted entirely when there are none. A heading with nothing under it tells a
   * model its notebook is empty, which is a claim; saying nothing is not.
   */
  private static String system(InferenceRequest request) {
    if (!request.context().hasAmbient()) {
      return request.systemPrompt().value();
    }
    StringBuilder system = new StringBuilder(request.systemPrompt().value());
    for (Ambient ambient : request.context().ambient()) {
      system
          .append("\n\n<")
          .append(ambient.kind())
          .append(">\n")
          .append(text(ambient.content()))
          .append("\n</")
          .append(ambient.kind())
          .append('>');
    }
    return system.toString();
  }

  /**
   * One turn, as this provider wants to be asked.
   *
   * <p>Every decision here is this adapter's, because only it knows what its wire permits. A turn
   * that produced nothing has to be explained somehow or two user turns end up adjacent with
   * nothing between them, and on this wire a mid-conversation {@code system} line is the natural
   * way to do that.
   *
   * <p>A refused observation is dropped rather than re-sent: it is what caused the refusal, and
   * re-sending it keeps the conversation refused for as long as it is still in the request.
   */
  /**
   * A summary, standing where the turns it replaces once stood.
   *
   * <p><b>User role, and bracketed, and that is this adapter's decision.</b> This wire has no role
   * for "here is what happened earlier": {@code system} is the standing instruction, {@code
   * assistant} would put the recap in the model's own mouth, and {@code user} is the only one that
   * reads as something the model is being shown. The tag is what tells it from something the person
   * just said, and the range is on it because the model is entitled to know that turns are missing
   * and which ones.
   */
  private static ChatCompletionMessageParam summary(Summary summary) {
    return user(
        "<summary from=\"%d\" through=\"%d\">\n%s\n</summary>"
            .formatted(summary.from().value(), summary.through().value(), text(summary.content())));
  }

  private static Stream<ChatCompletionMessageParam> toMessages(Turn turn) {
    Stream<ChatCompletionMessageParam> opening =
        turn.result() instanceof TurnResult.Refused
            ? Stream.empty()
            : Stream.of(user(text(turn.observation().blocks())));

    // Every round, in order, between the question and whatever the model finally said. A call
    // and its result have to stay adjacent and in sequence: this wire rejects an assistant
    // message carrying calls that is not immediately followed by a result for each of them.
    Stream<ChatCompletionMessageParam> middle =
        turn.exchanges().stream()
            .flatMap(
                exchange ->
                    Stream.concat(
                        Stream.of(asking(exchange)),
                        exchange.outcomes().stream().map(OpenAiRequests::answering)));

    Stream<ChatCompletionMessageParam> ending =
        switch (turn.result()) {
          case null -> Stream.of();
          case TurnResult.Answered(var blocks) -> Stream.of(assistant(text(blocks)));
          // "Did not complete" rather than "returned an error", because the call may never
          // have been made at all.
          case TurnResult.Failed _ ->
              Stream.of(system("The previous attempt to answer did not complete."));
          // Stands where the withdrawn question stood. Saying nothing would leave two user
          // turns adjacent with no explanation; saying what it was would put back the very
          // content this exists to remove.
          case TurnResult.Refused _ ->
              Stream.of(
                  system(
                      "A previous message was withdrawn from this conversation and is no longer"
                          + " available."));
        };

    return Stream.concat(opening, Stream.concat(middle, ending));
  }

  /** The assistant asking for work: the calls, and whatever it said alongside them. */
  private static ChatCompletionMessageParam asking(Exchange exchange) {
    ChatCompletionAssistantMessageParam.Builder builder =
        ChatCompletionAssistantMessageParam.builder();
    String said = text(exchange.request());
    if (!said.isEmpty()) {
      builder.content(said);
    }
    exchange.calls().stream().map(OpenAiRequests::toToolCall).forEach(builder::addToolCall);
    return ChatCompletionMessageParam.ofAssistant(builder.build());
  }

  /**
   * One result, quoting the call it answers.
   *
   * <p>All three outcomes flatten to the same shape here, because that is all this wire has --
   * there is no field for "denied" and no error flag on a {@code tool} message. The distinction is
   * not lost, only unsendable: it stays in the story, and an adapter for a wire that can express it
   * is free to.
   */
  private static ChatCompletionMessageParam answering(ToolOutcome outcome) {
    String content =
        switch (outcome) {
          case ToolOutcome.Succeeded(CallId _, var blocks) -> text(blocks);
          case ToolOutcome.Failed(CallId _, String message) -> "Error: " + message;
          case ToolOutcome.Denied(CallId _, String reason) ->
              "This call was not run because it was not permitted: " + reason;
        };
    return ChatCompletionMessageParam.ofTool(
        ChatCompletionToolMessageParam.builder()
            .toolCallId(outcome.callId().value())
            .content(content)
            .build());
  }

  private static ChatCompletionMessageParam user(String content) {
    return ChatCompletionMessageParam.ofUser(
        ChatCompletionUserMessageParam.builder().content(content).build());
  }

  private static ChatCompletionMessageParam assistant(String content) {
    return ChatCompletionMessageParam.ofAssistant(
        ChatCompletionAssistantMessageParam.builder().content(content).build());
  }

  private static ChatCompletionMessageParam system(String content) {
    return ChatCompletionMessageParam.ofSystem(
        ChatCompletionSystemMessageParam.builder().content(content).build());
  }

  private static ChatCompletionMessageFunctionToolCall toToolCall(Block.ToolCall call) {
    return ChatCompletionMessageFunctionToolCall.builder()
        .id(call.id().value())
        .function(
            ChatCompletionMessageFunctionToolCall.Function.builder()
                .name(call.name().value())
                .arguments(call.arguments())
                .build())
        .build();
  }

  /**
   * One tool, as this wire describes one. {@code function} is the only kind this API has ever had
   * for a described tool, and it is still required on every entry.
   */
  private static ChatCompletionTool toFunctionTool(ToolOffer offer, JsonMapper mapper) {
    return ChatCompletionTool.ofFunction(
        ChatCompletionFunctionTool.builder()
            .function(
                FunctionDefinition.builder()
                    .name(offer.name().value())
                    .description(offer.description())
                    .parameters(toFunctionParameters(offer.schema().json(), mapper))
                    .build())
            .build());
  }

  /**
   * Read into plain maps and lists rather than into nodes, because the two sides are on different
   * Jackson majors: this project is on Jackson 3 ({@code tools.jackson}) and the SDK's {@code
   * JsonValue.fromJsonNode} wants a Jackson 2 node. {@code JsonValue.from(Object)} is the bridge
   * that needs neither to know about the other.
   *
   * <p>Not cached. A schema parses in microseconds and the call it is part of takes hundreds of
   * milliseconds over the network, so a cache here would buy nothing and owe a lifetime.
   */
  private static FunctionParameters toFunctionParameters(String schema, JsonMapper mapper) {
    FunctionParameters.Builder builder = FunctionParameters.builder();
    Map<String, Object> properties = mapper.readValue(schema, new TypeReference<>() {});
    properties.forEach((name, value) -> builder.putAdditionalProperty(name, JsonValue.from(value)));
    return builder.build();
  }

  /**
   * The text of a run of blocks, and only the text.
   *
   * <p>Exhaustive, so a new block kind has to say here whether it is something a person reads.
   * Anything that is not text contributes nothing rather than being cast and thrown: a call travels
   * in {@code tool_calls}, and another vendor's reasoning state belongs to whoever attached it --
   * handing those bytes to this endpoint would at best be ignored and at worst rejected.
   *
   * <p>Commentary is re-sent beside the answer because it is part of what the assistant said, and
   * this wire has one content field for both.
   */
  static String text(List<? extends Block> blocks) {
    return blocks.stream()
        .map(OpenAiRequests::readable)
        .flatMap(Optional::stream)
        .collect(Collectors.joining());
  }

  private static Optional<String> readable(Block block) {
    return switch (block) {
      case Block.Text(String value) -> Optional.of(value);
      case Block.Commentary(String value) -> Optional.of(value);
      case Block.Provider _, Block.ToolCall _ -> Optional.empty();
    };
  }
}
