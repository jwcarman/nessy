package org.jwcarman.nessy.inference.openai;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonString;
import com.openai.core.http.StreamResponse;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.RateLimitException;
import com.openai.helpers.ChatCompletionAccumulator;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.spi.inference.Failure;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.narration.AgentNarrator;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI, through the vendor's own SDK.
 *
 * <p>Owns the {@link OpenAIClient} and is the only class here that touches the network; the
 * projection onto the wire lives in {@link OpenAiRequests} and can be tested without a key.
 *
 * <p><b>Holds no model name.</b> Which model to call travels in {@link InferenceOptions}, so one
 * client serves several agent types asking for different models rather than needing an instance per
 * model. That is why the old {@code model(ModelId)} handle is gone: there is nothing left for it to
 * pin.
 *
 * <p><b>Streams, and narrates as it goes.</b> Every call is made with {@code stream: true}; each
 * chunk's text is narrated as a {@link AgentEvent.ContentDelta} the moment it arrives, and a chunk
 * carrying {@code reasoning_content} -- what OpenAI-compatible servers such as LM Studio send for a
 * thinking model -- as a {@link AgentEvent.ThinkingDelta}. The chunks are folded back into one
 * completion by the SDK's own accumulator, and the answer is read from that exactly as a
 * non-streaming reply would be. The narrator is best-effort and the engine reads only the result,
 * so nothing above this class can tell it streams; the person watching can.
 *
 * <p><b>Images are not sent.</b> The block grammar has no image yet, so there is nothing to
 * project; when it grows one, {@code OpenAiRequests} is where it lands.
 */
public final class OpenAiInferenceProvider implements InferenceProvider, AutoCloseable {

  /**
   * The OpenTelemetry GenAI semantic conventions' default value for this vendor (agentic-o11y spec
   * §1.1).
   *
   * <p>Unlike the other vendor gateways, this one is SHARED: an xAI deployment builds this very
   * class against {@code https://api.x.ai/v1}, and semconv has a separate {@code x_ai} value for
   * that. So the provider name is a field given at construction rather than a constant -- an xAI
   * turn must not be reported as an OpenAI one. Any other OpenAI-compatible endpoint reached
   * through {@link OpenAiProviderConfig#baseUrl(String)} still answers {@code openai}, which is the
   * honest default: nothing else is known about it.
   */
  static final String PROVIDER_NAME = "openai";

  private static final String NAME = "OpenAI";

  private final OpenAIClient client;
  private final String provider;

  /**
   * Reads a tool's schema, which reaches an adapter as JSON text. Supplied rather than made here: a
   * mapper an application cannot configure is a mapper it cannot fix.
   */
  private final JsonMapper mapper;

  /**
   * Whether {@link #close()} may close {@link #client} -- false for a client handed in through
   * {@link OpenAiProviderConfig#client(OpenAIClient)}, which the application still owns.
   */
  private final boolean ownsClient;

  OpenAiInferenceProvider(
      OpenAIClient client, String provider, boolean ownsClient, JsonMapper mapper) {
    this.client = client;
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.ownsClient = ownsClient;
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  /**
   * The blessed one-call shape: equivalent to {@code create(OpenAiProviderConfig::fromEnv)}.
   * Delegates credential and configuration resolution to the SDK's own environment table.
   */
  public static OpenAiInferenceProvider fromEnv() {
    return create(OpenAiProviderConfig::fromEnv);
  }

  /**
   * Builds a provider from a live {@link OpenAiProviderConfig}: {@code customizer} fills it in,
   * then this factory validates its required field and constructs the finished provider. No public
   * {@code build()} survives here; the factory is the only place a config ever turns into a
   * provider.
   */
  public static OpenAiInferenceProvider create(OpenAiProviderCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    OpenAiProviderConfig config = new OpenAiProviderConfig();
    customizer.customize(config);
    return config.build();
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
  public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
    Objects.requireNonNull(narrator, "narrator must not be null");
    try (StreamResponse<ChatCompletionChunk> stream =
        client.chat().completions().createStreaming(OpenAiRequests.toParams(request, mapper))) {
      ChatCompletionAccumulator accumulator = ChatCompletionAccumulator.create();
      boolean[] any = {false};
      stream.stream()
          .forEach(
              chunk -> {
                any[0] = true;
                accumulator.accumulate(chunk);
                narrate(chunk, narrator);
              });
      if (!any[0]) {
        // A stream that ended before it began: nothing to fold. Asking again returns the same.
        return new InferenceResult.Fault(new Failure.Permanent("model returned no choices"));
      }
      return read(accumulator);
    } catch (OpenAIException e) {
      return new InferenceResult.Fault(classify(e));
    }
  }

  /**
   * What a person watching is told as the chunk lands: the text, and the thinking when the server
   * sends any. Only the first choice, which is the only one read. Tool-call fragments are not
   * narrated: half a JSON argument is not something anybody can watch.
   */
  private static void narrate(ChatCompletionChunk chunk, AgentNarrator narrator) {
    for (ChatCompletionChunk.Choice choice : chunk.choices()) {
      if (choice.index() != 0) {
        continue;
      }
      ChatCompletionChunk.Choice.Delta delta = choice.delta();
      delta
          .content()
          .filter(text -> !text.isEmpty())
          .ifPresent(text -> narrator.narrate(new AgentEvent.ContentDelta(text)));
      for (String field : REASONING_FIELDS) {
        if (delta._additionalProperties().get(field) instanceof JsonString reasoning
            && !reasoning.value().isEmpty()) {
          narrator.narrate(new AgentEvent.ThinkingDelta(reasoning.value()));
        }
      }
    }
  }

  /**
   * Where OpenAI-compatible servers put a thinking model's reasoning in a chunk. Not in the SDK's
   * grammar, because OpenAI's own API does not send it; LM Studio, Ollama, vLLM and DeepSeek do,
   * under one of these two names.
   */
  private static final List<String> REASONING_FIELDS = List.of("reasoning_content", "reasoning");

  /**
   * The folded completion, read -- or the fault a stream that closed before any choice reached its
   * finish reason is: the SDK will not fold half an answer, and neither should this adapter.
   */
  private static InferenceResult read(ChatCompletionAccumulator accumulator) {
    ChatCompletion completion;
    try {
      completion = accumulator.chatCompletion();
    } catch (IllegalStateException incomplete) {
      return new InferenceResult.Fault(
          new Failure.Permanent(
              "the stream ended before the answer was complete: " + incomplete.getMessage()));
    }
    if (completion.choices().isEmpty()) {
      // A 200 that carries no answer. Asking again returns the same nothing.
      return new InferenceResult.Fault(new Failure.Permanent("model returned no choices"));
    }
    return read(completion.choices().getFirst());
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
                    .map(OpenAiInferenceProvider::toBlock))
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
   * Decides what a failed call means, which is the one thing this class knows and nothing above it
   * does.
   *
   * <p>Grounded in the SDK's own retry classification: {@code
   * com.openai.core.http.RetryingHttpClient} retries a raw {@link java.io.IOException} or {@link
   * OpenAIRetryableException} unconditionally, and otherwise by status code (408, 409, 429, or any
   * 5xx) <em>before</em> the response is ever translated into a typed exception -- so by the time
   * one of those surfaces here, the SDK's own budget ({@code maxRetries}, default 2) is already
   * spent. What is still worth another attempt from further out is {@link RateLimitException},
   * {@link InternalServerException}, {@link OpenAIIoException} and {@link
   * OpenAIRetryableException}.
   *
   * <p>Everything else is {@link Failure.Permanent}: a 400, 401, 403, 404 or 422 means the request
   * itself is wrong, and repeating it unchanged only repeats the failure.
   *
   * <p><b>Nothing is classified {@link Failure.Rejected} here.</b> That is the one classification
   * that authorises throwing away something a person said, and it should rest on a measured marker
   * in a particular server's response rather than on a guess about what a 400 meant. None has been
   * measured on this wire, so none is claimed.
   */
  private static Failure classify(OpenAIException e) {
    if (e instanceof RateLimitException
        || e instanceof InternalServerException
        || e instanceof OpenAIRetryableException) {
      return new Failure.Transient("model call failed: " + e.getMessage());
    }
    // Transport-level: the request may or may not have been processed before the connection
    // went. Unknown rather than transient, because repeating it is safe exactly when repeating
    // the work is safe, and that is not this class's call to make.
    if (e instanceof OpenAIIoException) {
      return new Failure.Unknown("no answer from the model: " + e.getMessage());
    }
    return new Failure.Permanent("model call failed: " + e.getMessage());
  }

  /**
   * Closes the {@link OpenAIClient} this provider BUILT -- its OkHttp connection pool and
   * dispatcher threads. A client handed in through {@link
   * OpenAiProviderConfig#client(OpenAIClient)} is never closed here: it was never opened here.
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
  public String provider() {
    return provider;
  }
}
