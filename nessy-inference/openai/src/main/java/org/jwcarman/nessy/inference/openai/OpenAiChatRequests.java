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
import com.openai.models.ReasoningEffort;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionNamedToolChoice;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.VendorProperties;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.ToolChoice;
import org.jwcarman.nessy.inference.ToolOffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turn-speak into Chat Completions shape.
 *
 * <p>Pure translation, and the only place in this module that knows what a "role" is. Everything
 * above it speaks in turns, exchanges and blocks; everything below it speaks OpenAI's wire. Nothing
 * here touches the network, which is what makes the whole projection testable without a key.
 *
 * <p>The system message holds the system prompt and nothing else. The strata of the context are
 * placed as the cache wants them: the summaries and the finished turns first, unchanged from call
 * to call; then the active turn's question, led by the memory and state that are fixed for it; then
 * the rounds of calls made so far; and last, the ambient background, which is asked afresh on every
 * call.
 */
final class OpenAiChatRequests {

  private static final Logger log = LoggerFactory.getLogger(OpenAiChatRequests.class);

  private OpenAiChatRequests() {}

  /** No provider-level properties: the agent type's alone. */
  static ChatCompletionCreateParams toParams(InferenceRequest request, JsonMapper mapper) {
    return toParams(request, Map.of(), mapper);
  }

  /**
   * @param providerProperties the provider's own {@code openai.} map, overlaid here by the agent
   *     type's (spec §7a)
   * @param mapper reads a tool's schema back into a document. {@link
   *     org.jwcarman.nessy.api.JsonSchema} carries JSON text on purpose -- a tree would have to be
   *     some library's tree, and this wire's is not the one this project speaks -- so the parse is
   *     the bridge between the two, not a validation step. Supplied rather than made here, because
   *     a mapper an application cannot configure is a mapper it cannot fix.
   */
  static ChatCompletionCreateParams toParams(
      InferenceRequest request, Map<String, String> providerProperties, JsonMapper mapper) {
    InferenceOptions options = request.options();
    OpenAiPropertyReader.Read read =
        OpenAiPropertyReader.chat(VendorProperties.merge(providerProperties, options.properties()));

    List<ChatCompletionMessageParam> messages =
        Stream.concat(
                Stream.of(system(request.systemPrompt().value())),
                Stream.of(
                        request.context().summaries().stream().map(OpenAiChatRequests::summary),
                        request.context().tail().stream().flatMap(turn -> toMessages(turn, "", "")),
                        activeTurn(request.context()))
                    .flatMap(rendered -> rendered))
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
    request
        .toolset()
        .offers()
        .forEach(offer -> builder.addTool(toFunctionTool(offer, read.strict(), mapper)));
    chooseTool(builder, request.toolset().offers(), request.toolset().choice());
    request.outputSchema().ifPresent(schema -> constrainAnswer(builder, schema, mapper));
    read.effort()
        .ifPresent(
            effort ->
                builder.reasoningEffort(
                    ReasoningEffort.of(effort.name().toLowerCase(Locale.ROOT))));
    read.serviceTier()
        .ifPresent(
            tier ->
                builder.serviceTier(
                    ChatCompletionCreateParams.ServiceTier.of(
                        tier.name().toLowerCase(Locale.ROOT))));
    return builder.build();
  }

  /**
   * Asks for the answer in a shape, natively.
   *
   * <p>OpenAI constrains decoding to the schema in strict mode, so the answer cannot come back
   * malformed -- which is stronger than offering a recording tool and hoping it gets called. It
   * composes with tools: the constraint applies to a message when the model answers, and says
   * nothing about the calls it makes on the way there.
   *
   * <p>The schema's name is a label this wire requires and nothing reads, so it is a constant here
   * rather than something {@link JsonSchema} has to carry.
   */
  private static void constrainAnswer(
      ChatCompletionCreateParams.Builder builder, JsonSchema schema, JsonMapper mapper) {
    OpenAiResponsesSchemas.Projected projected =
        OpenAiResponsesSchemas.project(schema.json(), mapper);
    projected
        .refusedKeyword()
        .ifPresent(
            keyword ->
                log.warn(
                    "The answer's shape is asked for without strict mode: its schema uses {},"
                        + " which strict mode cannot express",
                    keyword));
    ResponseFormatJsonSchema.JsonSchema.Schema.Builder shape =
        ResponseFormatJsonSchema.JsonSchema.Schema.builder();
    projected
        .schema()
        .forEach((name, value) -> shape.putAdditionalProperty(name, JsonValue.from(value)));

    builder.responseFormat(
        ResponseFormatJsonSchema.builder()
            .jsonSchema(
                ResponseFormatJsonSchema.JsonSchema.builder()
                    .name("answer")
                    .schema(shape.build())
                    .strict(projected.strict())
                    .build())
            .build());
  }

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
    return user(OpenAiRendering.summary(summary));
  }

  /**
   * The turn being answered, with the strata that surround it.
   *
   * <p>Memory and state lead its question, in the same user message: they are fixed for the turn,
   * so everything before them and them themselves are the same on every call it makes, and a cached
   * prefix reaches through them. Ambient ends the request, because it is asked afresh on every call
   * and anything after it would be re-read every time. Before the first exchange the request ends
   * on the question, and the ambient is appended to that message rather than made a second user
   * message after it; after it the ambient is a user message of its own.
   */
  private static Stream<ChatCompletionMessageParam> activeTurn(InferenceContext context) {
    Turn turn = context.activeTurn();
    String leading = OpenAiRendering.leading(context);
    String trailing = OpenAiRendering.trailing(context);
    boolean endsOnQuestion = turn.exchanges().isEmpty() && turn.result() == null;
    if (endsOnQuestion || trailing.isEmpty()) {
      return toMessages(turn, leading, trailing);
    }
    return Stream.concat(toMessages(turn, leading, ""), Stream.of(user(trailing)));
  }

  /**
   * One turn, as this provider wants to be asked.
   *
   * <p>Every decision here is this adapter's, because only it knows what its wire permits. A turn
   * that produced nothing has to be explained somehow or two user turns end up adjacent with
   * nothing between them, and on this wire a mid-conversation {@code system} line is the natural
   * way to do that.
   *
   * <p>A refused input is dropped rather than re-sent: it is what caused the refusal, and
   * re-sending it keeps the conversation refused for as long as it is still in the request.
   */
  private static Stream<ChatCompletionMessageParam> toMessages(
      Turn turn, String leading, String trailing) {
    Stream<ChatCompletionMessageParam> opening;
    if (turn.result() instanceof TurnResult.Refused) {
      // Nothing of the withdrawn question is sent, but what was recalled for it still has
      // to be somewhere.
      opening = leading.isEmpty() ? Stream.empty() : Stream.of(user(leading));
    } else {
      opening =
          Stream.of(
              user(
                  OpenAiRendering.opening(
                      leading, OpenAiRendering.text(turn.input().blocks()), trailing)));
    }

    // Every round, in order, between the question and whatever the model finally said. A call
    // and its result have to stay adjacent and in sequence: this wire rejects an assistant
    // message carrying calls that is not immediately followed by a result for each of them.
    Stream<ChatCompletionMessageParam> middle =
        turn.exchanges().stream()
            .flatMap(
                exchange ->
                    Stream.concat(
                        Stream.of(asking(exchange)),
                        exchange.outcomes().stream().map(OpenAiChatRequests::answering)));

    Stream<ChatCompletionMessageParam> ending =
        switch (turn.result()) {
          case null -> Stream.of();
          case TurnResult.Answered(var blocks) ->
              Stream.of(assistant(OpenAiRendering.text(blocks)));
          // "Did not complete" rather than "returned an error", because the call may never
          // have been made at all.
          case TurnResult.Failed _ -> Stream.of(system(OpenAiRendering.FAILED_TURN));
          // Stands where the withdrawn question stood. Saying nothing would leave two user
          // turns adjacent with no explanation; saying what it was would put back the very
          // content this exists to remove.
          case TurnResult.Refused _ -> Stream.of(system(OpenAiRendering.REFUSED_TURN));
        };

    return Stream.concat(opening, Stream.concat(middle, ending));
  }

  /** The assistant asking for work: the calls, and whatever it said alongside them. */
  private static ChatCompletionMessageParam asking(Exchange exchange) {
    ChatCompletionAssistantMessageParam.Builder builder =
        ChatCompletionAssistantMessageParam.builder();
    String said = OpenAiRendering.text(exchange.request());
    if (!said.isEmpty()) {
      builder.content(said);
    }
    exchange.calls().stream().map(OpenAiChatRequests::toToolCall).forEach(builder::addToolCall);
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
    String content = OpenAiRendering.outcome(outcome);
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
   * How the model is told whether it may reach for what it was offered.
   *
   * <p>Said only when it is not the default and there is something to choose from: an explicit
   * "auto" is what the wire already means when the field is absent, and several OpenAI-compatible
   * servers are happier without it.
   */
  private static void chooseTool(
      ChatCompletionCreateParams.Builder builder, List<ToolOffer> tools, ToolChoice choice) {
    if (tools.isEmpty()) {
      return;
    }
    switch (choice) {
      case ToolChoice.Auto _ -> {
        // What the absent field already means.
      }
      case ToolChoice.None _ -> builder.toolChoice(ChatCompletionToolChoiceOption.Auto.NONE);
      // The same wire value, for a different intent that happens to coincide here: OpenAI
      // documents "none" as not calling a tool and generating a message instead, which is what
      // answering now means. The offers stay in the request, so a cached prefix is not disturbed.
      //
      // Documented rather than measured. If a model here ever returns empty content under this,
      // the fallback is to send no tools at all -- at the cost of the cache.
      case ToolChoice.Answer _ -> builder.toolChoice(ChatCompletionToolChoiceOption.Auto.NONE);
      case ToolChoice.Any _ -> builder.toolChoice(ChatCompletionToolChoiceOption.Auto.REQUIRED);
      case ToolChoice.Named(ToolName name) ->
          builder.toolChoice(
              ChatCompletionNamedToolChoice.builder()
                  .function(
                      ChatCompletionNamedToolChoice.Function.builder().name(name.value()).build())
                  .build());
    }
  }

  /**
   * One tool, as this wire describes one. {@code function} is the only kind this API has ever had
   * for a described tool, and it is still required on every entry. Under {@code
   * openai.tools.strict=true} the schema goes out rewritten for strict mode (spec §10); a schema
   * strict mode cannot express goes as generated with {@code strict: false}, and says so.
   */
  private static ChatCompletionTool toFunctionTool(
      ToolOffer offer, boolean strict, JsonMapper mapper) {
    FunctionDefinition.Builder function =
        FunctionDefinition.builder().name(offer.name().value()).description(offer.description());
    if (strict) {
      OpenAiResponsesSchemas.Projected projected =
          OpenAiResponsesSchemas.project(offer.schema().json(), mapper);
      projected
          .refusedKeyword()
          .ifPresent(
              keyword ->
                  log.warn(
                      "Tool {} is offered without strict mode: its schema uses {}, which strict"
                          + " mode cannot express",
                      offer.name().value(),
                      keyword));
      FunctionParameters.Builder parameters = FunctionParameters.builder();
      projected
          .schema()
          .forEach((name, value) -> parameters.putAdditionalProperty(name, JsonValue.from(value)));
      function.parameters(parameters.build()).strict(projected.strict());
    } else {
      function.parameters(toFunctionParameters(offer.schema().json(), mapper));
    }
    return ChatCompletionTool.ofFunction(
        ChatCompletionFunctionTool.builder().function(function.build()).build());
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
}
