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

import com.openai.client.OpenAIClient;
import com.openai.core.JsonField;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIException;
import com.openai.helpers.ResponseAccumulator;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseErrorEvent;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputRefusal;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseStreamEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.vendor.VendorProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI's Responses API, through the vendor's own SDK -- the {@code openai-responses} wire under
 * Boot.
 *
 * <p><b>Stateless.</b> Every call sends the whole context with {@code store: false}; nothing here
 * can name a previous response or a conversation, so Nessy's event log stays the only story there
 * is (the projection is {@link OpenAiResponsesRequests}).
 *
 * <p><b>Reasoning items are kept whole.</b> Every encrypted reasoning item that comes back --
 * beside calls or beside the answer -- is stored as a {@link Block.Provider} tagged with {@link
 * #vendor()}, in its arrival position. Which ones travel back is the projection's rule, not this
 * class's.
 *
 * <p><b>The SDK's accumulator folds nothing.</b> It keeps the whole {@code Response} the terminal
 * event carries ({@code completed}, {@code failed} or {@code incomplete}) and ignores everything
 * else, trailing events and unknown types included; the answer is read from that {@code Response}
 * in {@code read}, and the deltas exist for narration.
 *
 * <p>Holds no model name: the model travels in {@link InferenceOptions}.
 */
public final class OpenAiResponsesInferenceProvider implements InferenceProvider, AutoCloseable {

  private final OpenAIClient client;
  private final String vendor;
  private final JsonMapper mapper;
  private final boolean ownsClient;

  /**
   * The provider's own {@code openai.} properties, checked at build; the agent type's overlay them.
   */
  private final Map<String, String> properties;

  OpenAiResponsesInferenceProvider(
      OpenAIClient client, String vendor, boolean ownsClient, JsonMapper mapper) {
    this(client, vendor, ownsClient, mapper, Map.of());
  }

  OpenAiResponsesInferenceProvider(
      OpenAIClient client,
      String vendor,
      boolean ownsClient,
      JsonMapper mapper,
      Map<String, String> properties) {
    this.client = client;
    this.vendor = Objects.requireNonNull(vendor, "vendor must not be null");
    this.ownsClient = ownsClient;
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
  }

  /** Equivalent to {@code of(OpenAiResponsesProviderConfig::fromEnv)}. */
  public static OpenAiResponsesInferenceProvider fromEnv() {
    return of(OpenAiResponsesProviderConfig::fromEnv);
  }

  /**
   * Builds a provider from a live {@link OpenAiResponsesProviderConfig}: each customizer fills it
   * in, then this factory validates its required field and constructs the finished provider. No
   * public {@code build()} survives here; the factory is the only place a config ever turns into a
   * provider.
   */
  public static OpenAiResponsesInferenceProvider of(
      List<Customizer<OpenAiResponsesProviderConfig>> customizers) {
    Objects.requireNonNull(customizers, "customizers must not be null");
    OpenAiResponsesProviderConfig config = new OpenAiResponsesProviderConfig();
    customizers.forEach(customizer -> customizer.customize(config));
    return config.build();
  }

  /** One customizer, for a caller that is not a container. */
  public static OpenAiResponsesInferenceProvider of(
      Customizer<OpenAiResponsesProviderConfig> customizer) {
    return of(List.of(Objects.requireNonNull(customizer, "customizer must not be null")));
  }

  /**
   * Reads the merged properties exactly as a request would, so a clash, a bad value or {@code
   * openai.tools.strict=false} fails the harness build rather than its first turn (spec §7c).
   */
  @Override
  public void validate(InferenceOptions options) {
    Map<String, String> merged = VendorProperties.merge(properties, options.properties());
    OpenAiProperties.responses(merged, mapper);
    OpenAiProperties.logIgnored(merged);
  }

  /**
   * Total for anything the provider can do to us, and narrow for everything else: {@link
   * OpenAIException} is the root of what the SDK throws; anything outside it is a bug here and
   * escapes rather than being recorded as the model's fault.
   */
  @Override
  public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
    Objects.requireNonNull(narrator, "narrator must not be null");
    try (StreamResponse<ResponseStreamEvent> stream =
        client
            .responses()
            .createStreaming(
                OpenAiResponsesRequests.toParams(request, vendor, properties, mapper))) {
      ResponseAccumulator accumulator = ResponseAccumulator.create();
      boolean[] ended = {false};
      ResponseErrorEvent[] error = {null};
      stream.stream()
          .forEach(
              event -> {
                accumulator.accumulate(event);
                if (!ended[0]) {
                  narrate(event, narrator);
                }
                event.error().ifPresent(e -> error[0] = e);
                ended[0] |= event.isCompleted() || event.isFailed() || event.isIncomplete();
              });
      return read(accumulator, error[0], request.options().modelName());
    } catch (OpenAIException e) {
      return new InferenceResult.Fault(OpenAiFailures.classify(e));
    }
  }

  /**
   * The terminal event's response, read -- or the fault a stream that closed without one is. A
   * mid-stream {@code error} event, which the accumulator ignores, is remembered so the fault can
   * say what the server said rather than only that the stream ended.
   */
  private InferenceResult read(
      ResponseAccumulator accumulator, ResponseErrorEvent error, String asked) {
    Response response;
    try {
      response = accumulator.response();
    } catch (IllegalStateException incomplete) {
      String ended = "the stream ended before the answer was complete: ";
      if (error != null) {
        return new InferenceResult.Fault(
            OpenAiFailures.classify(error.code().orElse("unknown"), ended + error.message()));
      }
      return new InferenceResult.Fault(new Failure.Permanent(ended + incomplete.getMessage()));
    }
    Usage usage = usageOf(response, modelOf(response, asked));
    if (response.error().isPresent()) {
      return new InferenceResult.Fault(OpenAiFailures.classify(response.error().get()))
          .withUsage(usage);
    }
    return read(response).withUsage(usage);
  }

  /**
   * What a person watching is told as the stream lands: the answer's text, and thinking -- a
   * reasoning summary, or raw reasoning text a compatible server streams unasked. Function-call
   * argument fragments and refusal deltas are not narrated; empty deltas are skipped.
   */
  private static void narrate(ResponseStreamEvent event, InferenceNarrator narrator) {
    event
        .outputTextDelta()
        .map(delta -> delta.delta())
        .filter(text -> !text.isEmpty())
        .ifPresent(narrator::text);
    event
        .reasoningSummaryTextDelta()
        .map(delta -> delta.delta())
        .filter(text -> !text.isEmpty())
        .ifPresent(narrator::thinking);
    event
        .reasoningTextDelta()
        .map(delta -> delta.delta())
        .filter(text -> !text.isEmpty())
        .ifPresent(narrator::thinking);
  }

  /**
   * The output items, walked in order. A refusal is checked first, because this wire reports it in
   * a part of its own. Each message's text is commentary beside calls or the answer without them;
   * each function call is a call; each encrypted reasoning item is kept where it arrived; every
   * other kind -- web search, file search, code interpreter, MCP and the rest -- is dropped,
   * because nothing here offered it.
   */
  private InferenceResult read(Response response) {
    Optional<String> refusal =
        response.output().stream()
            .filter(ResponseOutputItem::isMessage)
            .flatMap(item -> item.asMessage().content().stream())
            .flatMap(content -> content.refusal().stream())
            .map(ResponseOutputRefusal::refusal)
            .findFirst();
    if (refusal.isPresent()) {
      return new InferenceResult.Refusal(refusal.get());
    }
    List<Block.ActionRequestContent> inOrder = new ArrayList<>();
    List<Block.AnswerContent> reasoning = new ArrayList<>();
    StringBuilder said = new StringBuilder();
    boolean called = false;
    for (ResponseOutputItem item : response.output()) {
      if (item.isMessage()) {
        String text = textOf(item.asMessage());
        said.append(text);
        if (!text.isBlank()) {
          inOrder.add(new Block.Commentary(text));
        }
      } else if (item.isFunctionCall()) {
        ResponseFunctionToolCall call = item.asFunctionCall();
        inOrder.add(new Block.ToolCall(call.callId(), call.name(), call.arguments()));
        called = true;
      } else if (item.isReasoning()) {
        Optional<Block.Provider> kept = kept(item.asReasoning());
        kept.ifPresent(inOrder::add);
        kept.ifPresent(reasoning::add);
      }
    }
    if (called) {
      return new InferenceResult.Actions(inOrder);
    }
    if (said.toString().isBlank()) {
      return new InferenceResult.Fault(
          new Failure.Permanent(
              "model returned an empty answer (status="
                  + response.status().map(ResponseStatus::asString).orElse("unknown")
                  + ", reason="
                  + response
                      .incompleteDetails()
                      .flatMap(Response.IncompleteDetails::reason)
                      .map(Response.IncompleteDetails.Reason::asString)
                      .orElse("none")
                  + ")"));
    }
    List<Block.AnswerContent> answer = new ArrayList<>(reasoning);
    answer.add(new Block.Text(said.toString()));
    return new InferenceResult.Answer(answer);
  }

  private static String textOf(ResponseOutputMessage message) {
    return message.content().stream()
        .flatMap(content -> content.outputText().stream())
        .map(ResponseOutputText::text)
        .collect(Collectors.joining());
  }

  /**
   * An encrypted reasoning item as a {@code Provider} block: its own fields as one JSON object,
   * written by the configured mapper. Safe to re-serialise because the signed bytes are the {@code
   * encrypted_content} string itself. An item with nothing encrypted has nothing to hand back.
   */
  private Optional<Block.Provider> kept(ResponseReasoningItem item) {
    return item.encryptedContent()
        .map(
            encrypted -> {
              Map<String, Object> payload = new LinkedHashMap<>();
              payload.put("id", item.id());
              payload.put("encrypted_content", encrypted);
              payload.put(
                  "summary",
                  item.summary().stream()
                      .map(
                          part -> {
                            Map<String, Object> summary = new LinkedHashMap<>();
                            summary.put("type", "summary_text");
                            summary.put("text", part.text());
                            return summary;
                          })
                      .toList());
              return new Block.Provider(vendor, mapper.writeValueAsString(payload));
            });
  }

  /**
   * The model that answered, across the SDK's three model arms (a name the SDK knows deserialises
   * into the chat arm, not the string one), or the one asked for when the server said none.
   */
  private static String modelOf(Response response, String asked) {
    return response
        ._model()
        .asKnown()
        .map(OpenAiResponsesInferenceProvider::nameOf)
        .filter(name -> !name.isBlank())
        .orElse(asked);
  }

  private static String nameOf(ResponsesModel model) {
    if (model.isString()) {
      return model.asString();
    }
    if (model.isChat()) {
      return model.asChat().asString();
    }
    return model.asOnly().asString();
  }

  /**
   * What the call cost. {@code input_tokens} already includes cached input, so nothing is summed.
   * Every count is read through {@code asKnown()}: the SDK marks the detail objects required and
   * throws on a server that omits them, and a server that says nothing about caching has not said
   * zero.
   */
  private static Usage usageOf(Response response, String model) {
    return response
        .usage()
        .map(
            counted ->
                new Usage(
                    model,
                    count(counted._inputTokens()),
                    count(counted._outputTokens()),
                    counted
                        ._inputTokensDetails()
                        .asKnown()
                        .map(details -> count(details._cachedTokens()))
                        .orElse(null),
                    counted
                        ._inputTokensDetails()
                        .asKnown()
                        .map(details -> count(details._cacheWriteTokens()))
                        .orElse(null),
                    counted
                        ._outputTokensDetails()
                        .asKnown()
                        .map(details -> count(details._reasoningTokens()))
                        .orElse(null)))
        .orElseGet(() -> Usage.unreported(model));
  }

  private static Integer count(JsonField<Long> field) {
    return field.asKnown().map(Long::intValue).orElse(null);
  }

  /** Closes the client this provider built; a supplied one stays the caller's. Idempotent. */
  @Override
  public void close() {
    if (ownsClient) {
      client.close();
    }
  }

  /** This vendor, by name -- not an SPI method, kept because callers and logs want it. */
  public String name() {
    return OpenAiChatInferenceProvider.NAME;
  }

  @Override
  public String vendor() {
    return vendor;
  }
}
