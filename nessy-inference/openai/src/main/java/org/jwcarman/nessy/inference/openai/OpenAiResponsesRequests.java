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
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolChoiceFunction;
import com.openai.models.responses.ToolChoiceOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.ToolChoice;
import org.jwcarman.nessy.inference.ToolOffer;
import org.jwcarman.nessy.vendor.VendorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turn-speak into Responses shape.
 *
 * <p>Pure translation; nothing here touches the network. <b>Stateless by construction</b>: every
 * request carries the whole context and {@code store: false}, and nothing here can name a previous
 * response or a conversation -- Nessy's event log is the only conversation there is.
 */
final class OpenAiResponsesRequests {

  private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesRequests.class);

  private OpenAiResponsesRequests() {}

  /** No provider-level properties: the agent type's alone. */
  static ResponseCreateParams toParams(InferenceRequest request, String vendor, JsonMapper mapper) {
    return toParams(request, vendor, Map.of(), mapper);
  }

  /**
   * The Responses request for one inference call: stateless, the whole context projected into input
   * items, the tools offered as functions.
   *
   * @param request what to ask, with its context, tools and options
   * @param vendor the provider's own vendor tag; only {@code Block.Provider} blocks carrying it are
   *     replayed
   * @param providerProperties the provider's own {@code openai.} map, overlaid here by the agent
   *     type's (spec §7a)
   * @param mapper reads a tool's schema and a stored reasoning item; supplied, never made here
   */
  static ResponseCreateParams toParams(
      InferenceRequest request,
      String vendor,
      Map<String, String> providerProperties,
      JsonMapper mapper) {
    InferenceOptions options = request.options();
    OpenAiProperties.Read read =
        OpenAiProperties.responses(
            VendorProperties.merge(providerProperties, options.properties()));
    List<ResponseInputItem> input = new ArrayList<>();
    request
        .context()
        .summaries()
        .forEach(
            summary ->
                input.add(message(EasyInputMessage.Role.USER, OpenAiRendering.summary(summary))));
    List<Turn> turns = request.context().turns();
    for (int i = 0; i < turns.size(); i++) {
      Turn turn = turns.get(i);
      boolean inFlight = i == turns.size() - 1 && turn.result() == null;
      input.addAll(items(turn, inFlight, vendor, mapper));
    }

    // store is sent explicitly: the API's default is true, and a default is a thing that changes.
    ResponseCreateParams.Builder builder =
        ResponseCreateParams.builder()
            .model(options.modelName())
            .instructions(OpenAiRendering.system(request))
            .inputOfResponse(input)
            .store(false)
            .addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT);
    if (options.hasMaxTokens()) {
      builder.maxOutputTokens(options.maxTokens());
    }
    request.toolset().offers().forEach(offer -> builder.addTool(toFunctionTool(offer, mapper)));
    chooseTool(builder, request.toolset().offers(), request.toolset().choice());
    request.outputSchema().ifPresent(schema -> constrainAnswer(builder, schema, mapper));
    // Built only when asked for: a reasoning object is a 400 on a model that does not reason,
    // and the adapter never guesses which kind it holds (Responses record §5g).
    if (read.effort().isPresent() || read.summary().isPresent()) {
      Reasoning.Builder reasoning = Reasoning.builder();
      read.effort().ifPresent(effort -> reasoning.effort(ReasoningEffort.of(effort)));
      read.summary().ifPresent(summary -> reasoning.summary(Reasoning.Summary.of(summary)));
      builder.reasoning(reasoning.build());
    }
    read.serviceTier()
        .ifPresent(tier -> builder.serviceTier(ResponseCreateParams.ServiceTier.of(tier)));
    return builder.build();
  }

  /**
   * One turn as input items. An exchange is one item per stored block, in stored order -- a
   * reasoning item must precede the call it led to -- followed by one output per call.
   */
  private static List<ResponseInputItem> items(
      Turn turn, boolean inFlight, String vendor, JsonMapper mapper) {
    List<ResponseInputItem> items = new ArrayList<>();
    if (!(turn.result() instanceof TurnResult.Refused)) {
      items.add(message(EasyInputMessage.Role.USER, OpenAiRendering.text(turn.input().blocks())));
    }
    List<Exchange> exchanges = turn.exchanges();
    for (int i = 0; i < exchanges.size(); i++) {
      Exchange exchange = exchanges.get(i);
      boolean replaying = replays(inFlight, i == exchanges.size() - 1);
      for (Block.ActionRequestContent block : exchange.request()) {
        switch (block) {
          case Block.Commentary(String said) ->
              items.add(message(EasyInputMessage.Role.ASSISTANT, said));
          case Block.ToolCall call -> items.add(functionCall(call));
          case Block.Provider provider -> {
            if (replaying) {
              ours(provider, vendor, mapper).ifPresent(items::add);
            }
          }
        }
      }
      exchange
          .outcomes()
          .forEach(
              outcome ->
                  items.add(
                      ResponseInputItem.ofFunctionCallOutput(
                          ResponseInputItem.FunctionCallOutput.builder()
                              .callId(outcome.callId().value())
                              .output(OpenAiRendering.outcome(outcome))
                              .build())));
    }
    switch (turn.result()) {
      case null -> {
        // In flight: nothing after the question yet.
      }
      case TurnResult.Answered(var blocks) ->
          items.add(message(EasyInputMessage.Role.ASSISTANT, OpenAiRendering.text(blocks)));
      case TurnResult.Failed _ ->
          items.add(message(EasyInputMessage.Role.SYSTEM, OpenAiRendering.FAILED_TURN));
      case TurnResult.Refused _ ->
          items.add(message(EasyInputMessage.Role.SYSTEM, OpenAiRendering.REFUSED_TURN));
    }
    return items;
  }

  /**
   * The replay rule (§5j), in one place: a reasoning item travels with the tool results it led to
   * -- the last exchange of the turn in flight -- and never across turns. Widening it to every
   * exchange of the turn in flight is this method answering {@code inFlight} alone.
   */
  private static boolean replays(boolean inFlight, boolean lastExchange) {
    return inFlight && lastExchange;
  }

  /**
   * This provider's own reasoning item, built back from what the adapter stored; anything else --
   * another vendor's state, or an item with nothing encrypted to hand back -- is not sent.
   */
  private static Optional<ResponseInputItem> ours(
      Block.Provider block, String vendor, JsonMapper mapper) {
    if (!vendor.equals(block.vendor())) {
      return Optional.empty();
    }
    Map<String, Object> data = mapper.readValue(block.payload(), new TypeReference<>() {});
    if (!(data.get("id") instanceof String id)
        || !(data.get("encrypted_content") instanceof String encrypted)) {
      return Optional.empty();
    }
    List<ResponseReasoningItem.Summary> summary = new ArrayList<>();
    if (data.get("summary") instanceof List<?> parts) {
      for (Object part : parts) {
        if (part instanceof Map<?, ?> fields && fields.get("text") instanceof String text) {
          summary.add(ResponseReasoningItem.Summary.builder().text(text).build());
        }
      }
    }
    return Optional.of(
        ResponseInputItem.ofReasoning(
            ResponseReasoningItem.builder()
                .id(id)
                .encryptedContent(encrypted)
                .summary(summary)
                .build()));
  }

  /**
   * The {@code call_id} is the one a function_call_output quotes; the {@code fc_} item id is not
   * kept.
   */
  private static ResponseInputItem functionCall(Block.ToolCall call) {
    return ResponseInputItem.ofFunctionCall(
        ResponseFunctionToolCall.builder()
            .callId(call.id().value())
            .name(call.name().value())
            .arguments(call.arguments())
            .build());
  }

  private static ResponseInputItem message(EasyInputMessage.Role role, String text) {
    return ResponseInputItem.ofEasyInputMessage(
        EasyInputMessage.builder().role(role).content(text).build());
  }

  /**
   * One function tool, strict when the rewrite could express its schema (§5d). A schema it could
   * not is sent as generated with {@code strict: false}, and says so, per tool.
   */
  private static Tool toFunctionTool(ToolOffer offer, JsonMapper mapper) {
    OpenAiResponsesSchemas.Projected projected =
        OpenAiResponsesSchemas.project(offer.schema().json(), mapper);
    projected
        .refusedKeyword()
        .ifPresent(
            keyword ->
                log.warn(
                    "Tool {} is offered without strict mode: its schema uses {}, which strict mode"
                        + " cannot express",
                    offer.name().value(),
                    keyword));
    FunctionTool.Parameters.Builder parameters = FunctionTool.Parameters.builder();
    projected
        .schema()
        .forEach((name, value) -> parameters.putAdditionalProperty(name, JsonValue.from(value)));
    return Tool.ofFunction(
        FunctionTool.builder()
            .name(offer.name().value())
            .description(offer.description())
            .parameters(parameters.build())
            .strict(projected.strict())
            .build());
  }

  /**
   * The answer's shape as {@code text.format}, rewritten as a tool's schema is: strict mode needs
   * the same shape here. The name is a label the wire requires and nothing reads.
   */
  private static void constrainAnswer(
      ResponseCreateParams.Builder builder, JsonSchema schema, JsonMapper mapper) {
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
    ResponseFormatTextJsonSchemaConfig.Schema.Builder shape =
        ResponseFormatTextJsonSchemaConfig.Schema.builder();
    projected
        .schema()
        .forEach((name, value) -> shape.putAdditionalProperty(name, JsonValue.from(value)));
    builder.text(
        ResponseTextConfig.builder()
            .format(
                ResponseFormatTextJsonSchemaConfig.builder()
                    .name("answer")
                    .schema(shape.build())
                    .strict(projected.strict())
                    .build())
            .build());
  }

  /**
   * The chat adapter's mapping on this wire's vocabulary. {@code Answer} is emulated as {@code
   * none}, the offers left in place so a cached prefix is not disturbed; if a model ever answers
   * empty under it, the fallback is to send no tools at all, at the cost of the cache.
   */
  private static void chooseTool(
      ResponseCreateParams.Builder builder, List<ToolOffer> tools, ToolChoice choice) {
    if (tools.isEmpty()) {
      return;
    }
    switch (choice) {
      case ToolChoice.Auto _ -> {
        // What the absent field already means.
      }
      case ToolChoice.None _, ToolChoice.Answer _ -> builder.toolChoice(ToolChoiceOptions.NONE);
      case ToolChoice.Any _ -> builder.toolChoice(ToolChoiceOptions.REQUIRED);
      case ToolChoice.Named(ToolName name) ->
          builder.toolChoice(ToolChoiceFunction.builder().name(name.value()).build());
    }
  }
}
