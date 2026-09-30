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
import com.openai.core.JsonString;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIException;
import com.openai.helpers.ChatCompletionAccumulator;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.completions.CompletionUsage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
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
 * OpenAI, through the vendor's own SDK.
 *
 * <p>Speaks Chat Completions -- the {@code openai-chat} wire under Boot -- which xAI and any
 * OpenAI-compatible endpoint (such as LM Studio) also answer to.
 *
 * <p>Owns the {@link OpenAIClient} and is the only class here that touches the network; the
 * projection onto the wire lives in {@link OpenAiChatRequests} and can be tested without a key.
 *
 * <p><b>Holds no model name.</b> Which model to call travels in {@link InferenceOptions}, so one
 * client serves several agent types asking for different models rather than needing an instance per
 * model. That is why the old {@code model(ModelId)} handle is gone: there is nothing left for it to
 * pin.
 *
 * <p><b>Streams, and narrates as it goes.</b> Every call is made with {@code stream: true}; each
 * chunk's text is narrated as text the moment it arrives, and a chunk carrying {@code
 * reasoning_content} -- what OpenAI-compatible servers such as LM Studio send for a thinking model
 * -- as thinking. The chunks are folded back into one completion by the SDK's own accumulator, and
 * the answer is read from that exactly as a non-streaming reply would be. The narrator is
 * best-effort and the engine reads only the result, so nothing above this class can tell it
 * streams; the person watching can.
 *
 * <p><b>Images are not sent.</b> The block grammar has no image yet, so there is nothing to
 * project; when it grows one, {@code OpenAiChatRequests} is where it lands.
 */
public final class OpenAiChatInferenceProvider implements InferenceProvider, AutoCloseable {

  /**
   * The OpenTelemetry GenAI semantic conventions' default value for this vendor (agentic-o11y spec
   * §1.1).
   *
   * <p>Unlike the other vendor providers, this one is SHARED: an xAI deployment builds this very
   * class against {@code https://api.x.ai/v1}, and semconv has a separate {@code x_ai} value for
   * that. So the provider name is a field given at construction rather than a constant -- an xAI
   * turn must not be reported as an OpenAI one. Any other OpenAI-compatible endpoint reached
   * through {@link OpenAiChatProviderConfig#baseUrl(String)} still answers {@code openai}, which is
   * the honest default: nothing else is known about it.
   */
  static final String VENDOR = "openai";

  static final String NAME = "OpenAI";

  private final OpenAIClient client;
  private final String vendor;

  /**
   * Reads a tool's schema, which reaches an adapter as JSON text. Supplied rather than made here: a
   * mapper an application cannot configure is a mapper it cannot fix.
   */
  private final JsonMapper mapper;

  /**
   * Whether {@link #close()} may close {@link #client} -- false for a client handed in through
   * {@link OpenAiChatProviderConfig#client(OpenAIClient)}, which the application still owns.
   */
  private final boolean ownsClient;

  /**
   * The provider's own {@code openai.} properties, checked at build; the agent type's overlay them.
   */
  private final Map<String, String> properties;

  OpenAiChatInferenceProvider(
      OpenAIClient client, String vendor, boolean ownsClient, JsonMapper mapper) {
    this(client, vendor, ownsClient, mapper, Map.of());
  }

  OpenAiChatInferenceProvider(
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

  /**
   * The blessed one-call shape: equivalent to {@code of(OpenAiChatProviderConfig::fromEnv)}.
   * Delegates credential and configuration resolution to the SDK's own environment table.
   */
  public static OpenAiChatInferenceProvider fromEnv() {
    return of(OpenAiChatProviderConfig::fromEnv);
  }

  /**
   * Builds a provider from a live {@link OpenAiChatProviderConfig}: {@code customizer} fills it in,
   * then this factory validates its required field and constructs the finished provider. No public
   * {@code build()} survives here; the factory is the only place a config ever turns into a
   * provider.
   */
  public static OpenAiChatInferenceProvider of(
      List<Customizer<OpenAiChatProviderConfig>> customizers) {
    Objects.requireNonNull(customizers, "customizers must not be null");
    OpenAiChatProviderConfig config = new OpenAiChatProviderConfig();
    customizers.forEach(customizer -> customizer.customize(config));
    return config.build();
  }

  /** One customizer, for a caller that is not a container. */
  public static OpenAiChatInferenceProvider of(Customizer<OpenAiChatProviderConfig> customizer) {
    return of(List.of(Objects.requireNonNull(customizer, "customizer must not be null")));
  }

  /**
   * Reads the merged properties exactly as a request would, so a clash, a bad value or a name this
   * wire cannot carry fails the harness build rather than its first turn (spec §7c).
   */
  @Override
  public void validate(InferenceOptions options) {
    Map<String, String> merged = VendorProperties.merge(properties, options.properties());
    OpenAiProperties.chat(merged, mapper);
    OpenAiProperties.logIgnored(merged);
  }

  /**
   * Total for anything the provider can do to us. A classified failure comes back as {@link
   * InferenceResult.Fault} rather than thrown, because the agent that asked for this is mid-turn
   * and only something reaching the fold ends that turn.
   *
   * <p>{@link OpenAIException} is the root of everything this SDK throws, and catching it is what
   * makes the handling total -- catching a list of subtypes instead would let the next one the SDK
   * adds escape silently. It is still narrow: anything outside it -- a null dereference here, a
   * translation that blows up -- is a bug in this adapter, and a bug dressed as {@code
   * Failure.Unknown} would be retried three times and then recorded as the model's fault.
   */
  @Override
  public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
    Objects.requireNonNull(narrator, "narrator must not be null");
    try (StreamResponse<ChatCompletionChunk> stream =
        client
            .chat()
            .completions()
            .createStreaming(OpenAiChatRequests.toParams(request, properties, mapper))) {
      ChatCompletionAccumulator accumulator = ChatCompletionAccumulator.create();
      boolean[] any = {false};
      CompletionUsage[] earlyUsage = {null};
      stream.stream()
          .forEach(
              chunk -> {
                any[0] = true;
                accumulator.accumulate(strippedOfEarlyUsage(chunk, earlyUsage));
                narrate(chunk, narrator);
              });
      if (!any[0]) {
        // A stream that ended before it began: nothing to fold. Asking again returns the same.
        return new InferenceResult.Fault(new Failure.Permanent("model returned no choices"));
      }
      return read(accumulator, earlyUsage[0]);
    } catch (OpenAIException e) {
      return new InferenceResult.Fault(OpenAiFailures.classify(e));
    }
  }

  /**
   * OpenAI's own contract puts {@code usage} only on a final, choices-less chunk. Groq and Mistral
   * instead send it on the {@code finish_reason} chunk that still carries choices (Groq then
   * repeats it on a final choices-less chunk of its own). The SDK's {@link
   * ChatCompletionAccumulator} treats ANY chunk carrying {@code usage} as that final chunk: it
   * folds and returns immediately, before the still-unread choices on that same chunk are ever
   * accumulated, which makes {@code build()} throw for want of a {@code choices} field.
   *
   * <p>So a chunk that carries both is fed to the accumulator with its usage stripped -- the
   * accumulator then processes its choices normally -- and the usage is remembered in {@code
   * earlyUsage} so {@link #read(ChatCompletionAccumulator, CompletionUsage)} can attach it if the
   * folded completion never got one of its own (Mistral's shape, which sends no later chunk at
   * all). {@code chunk.usage()} treats an explicit JSON {@code null} the same as an absent field,
   * so this never fires on the ordinary chunks vendors pad with {@code "usage": null}.
   */
  private static ChatCompletionChunk strippedOfEarlyUsage(
      ChatCompletionChunk chunk, CompletionUsage[] earlyUsage) {
    if (chunk.choices().isEmpty() || chunk.usage().isEmpty()) {
      return chunk;
    }
    earlyUsage[0] = chunk.usage().get();
    return chunk.toBuilder().usage(Optional.<CompletionUsage>empty()).build();
  }

  /**
   * What a person watching is told as the chunk lands: the text, and the thinking when the server
   * sends any. Only the first choice, which is the only one read. Tool-call fragments are not
   * narrated: half a JSON argument is not something anybody can watch.
   */
  private static void narrate(ChatCompletionChunk chunk, InferenceNarrator narrator) {
    for (ChatCompletionChunk.Choice choice : chunk.choices()) {
      if (choice.index() != 0) {
        continue;
      }
      ChatCompletionChunk.Choice.Delta delta = choice.delta();
      delta.content().filter(text -> !text.isEmpty()).ifPresent(narrator::text);
      for (String field : REASONING_FIELDS) {
        if (delta._additionalProperties().get(field) instanceof JsonString reasoning
            && !reasoning.value().isEmpty()) {
          narrator.thinking(reasoning.value());
        }
      }
    }
  }

  /**
   * Where OpenAI-compatible servers put a thinking model's reasoning in a chunk. Not in the SDK's
   * grammar, because OpenAI's own API does not send it; LM Studio, Ollama, vLLM and Groq do, under
   * one of these two names.
   */
  private static final List<String> REASONING_FIELDS = List.of("reasoning_content", "reasoning");

  /**
   * The folded completion, read -- or the fault a stream that closed before any choice reached its
   * finish reason is: the SDK will not fold half an answer, and neither should this adapter.
   */
  private static InferenceResult read(
      ChatCompletionAccumulator accumulator, CompletionUsage earlyUsage) {
    ChatCompletion completion;
    try {
      completion = accumulator.chatCompletion();
    } catch (IllegalStateException incomplete) {
      return new InferenceResult.Fault(
          new Failure.Permanent(
              "the stream ended before the answer was complete: " + incomplete.getMessage()));
    }
    if (completion.usage().isEmpty() && earlyUsage != null) {
      // Mistral's shape: usage arrived early (see strippedOfEarlyUsage) and no later chunk ever
      // repeated it, so the fold never picked one up on its own.
      completion = completion.toBuilder().usage(earlyUsage).build();
    }
    Usage usage = usageOf(completion);
    if (completion.choices().isEmpty()) {
      // A 200 that carries no answer. Asking again returns the same nothing.
      return new InferenceResult.Fault(new Failure.Permanent("model returned no choices"))
          .withUsage(usage);
    }
    return read(completion.choices().getFirst()).withUsage(usage);
  }

  /**
   * What the call cost, in the shape {@link Usage} defines.
   *
   * <p><b>Nothing is summed here, unlike the Anthropic adapter.</b> OpenAI's {@code prompt_tokens}
   * already includes whatever was served from cache, so it IS all input processed and the cache
   * counts are a breakdown of it.
   *
   * <p><b>An absent cache count is null rather than zero, and that is the opposite of the Anthropic
   * reading.</b> This adapter reaches every OpenAI-compatible endpoint, and many of them -- LM
   * Studio among them -- omit {@code prompt_tokens_details} entirely rather than reporting zeroes.
   * Calling that zero would claim a server had told us nothing was cached when it had told us
   * nothing at all.
   */
  private static Usage usageOf(ChatCompletion completion) {
    return completion
        .usage()
        .<Usage>map(
            counted ->
                new Usage(
                    completion.model(),
                    (int) counted.promptTokens(),
                    (int) counted.completionTokens(),
                    counted
                        .promptTokensDetails()
                        .flatMap(d -> d.cachedTokens())
                        .map(Long::intValue)
                        .orElse(null),
                    counted
                        .promptTokensDetails()
                        .flatMap(d -> d.cacheWriteTokens())
                        .map(Long::intValue)
                        .orElse(null),
                    counted
                        .completionTokensDetails()
                        .flatMap(d -> d.reasoningTokens())
                        .map(Long::intValue)
                        .orElse(null)))
        .orElseGet(() -> Usage.unreported(completion.model()));
  }

  /**
   * Which of the three shapes an assistant message is.
   *
   * <p>A refusal is checked first because it is the one the SDK reports in a field of its own --
   * most wires make it indistinguishable from an ordinary answer, and this one does not, so the
   * distinction is taken where it is offered.
   *
   * <p>Otherwise the choice is made on the presence of {@code tool_calls} rather than on {@code
   * finish_reason}: the content is the thing that has to be answered, and several OpenAI-compatible
   * servers report the reason inconsistently while all of them put the calls in the same place.
   */
  private static InferenceResult read(ChatCompletion.Choice choice) {
    ChatCompletionMessage message = choice.message();
    if (message.refusal().isPresent()) {
      return new InferenceResult.Refusal(message.refusal().get());
    }
    String said = message.content().orElse("");
    List<ChatCompletionMessageToolCall> calls = message.toolCalls().orElseGet(List::of);
    if (calls.isEmpty()) {
      if (said.isBlank()) {
        // A 200 with nothing in it. A reasoning model that spent its whole token budget thinking
        // looks exactly like this, with finish_reason=length -- said here, because it is the one
        // clue to what happened and nothing else will carry it.
        return new InferenceResult.Fault(
            new Failure.Permanent(
                "model returned an empty answer (finish_reason=" + choice.finishReason() + ")"));
      }
      return new InferenceResult.Answer(List.of(new Block.Text(said)));
    }
    // Commentary, not text, and the grammar is what says so: a turn that is still asking has
    // not answered, so prose arriving beside calls is the model talking while it works. This is
    // the one place that classification happens -- the adapter, where the shape of the response
    // says whether the model was working or answering.
    return new InferenceResult.Actions(
        Stream.concat(
                said.isBlank()
                    ? Stream.<Block.ActionRequestContent>of()
                    : Stream.<Block.ActionRequestContent>of(new Block.Commentary(said)),
                calls.stream()
                    .filter(ChatCompletionMessageToolCall::isFunction)
                    .map(OpenAiChatInferenceProvider::toBlock))
            .toList());
  }

  /**
   * Custom tool calls are dropped rather than translated. Nothing in this engine can offer one --
   * every tool goes out as a function -- so a custom call coming back would be a call to something
   * that was never offered, and there is no {@code CallId} it could be answered under.
   */
  private static Block.ToolCall toBlock(ChatCompletionMessageToolCall call) {
    var function = call.asFunction();
    return new Block.ToolCall(
        function.id(), function.function().name(), function.function().arguments());
  }

  /**
   * Closes the {@link OpenAIClient} this provider BUILT -- its OkHttp connection pool and
   * dispatcher threads. A client handed in through {@link
   * OpenAiChatProviderConfig#client(OpenAIClient)} is never closed here: it was never opened here.
   * Idempotent, as the SDK's own {@code close()} is.
   */
  @Override
  public void close() {
    if (ownsClient) {
      client.close();
    }
  }

  /** This vendor, by name -- not an SPI method, kept because callers and logs want it. */
  public String name() {
    return NAME;
  }

  /** Which vendor an observability layer should report this call under. */
  @Override
  public String vendor() {
    return vendor;
  }
}
