package org.jwcarman.nessy.inference.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ToolUseBlock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * Anthropic, through the vendor's own SDK.
 *
 * <p>Owns the {@link AnthropicClient} and is the only class here that touches the network; the
 * projection onto the wire lives in {@link AnthropicRequests} and can be tested without a key.
 *
 * <p><b>Holds no model name.</b> Which model to call travels in {@link InferenceOptions}, so one
 * client serves several agent types asking for different models.
 *
 * <p><b>Streams, and narrates as it goes.</b> Every call is made through the streaming endpoint;
 * each text delta is narrated as a {@link AgentEvent.ContentDelta} and each thinking delta as a
 * {@link AgentEvent.ThinkingDelta} the moment it arrives. The events are folded back into one
 * message by the SDK's own accumulator, and the reply is read from that exactly as a non-streaming
 * one would be: the engine receives one result, and only the person watching can tell.
 *
 * <p><b>Extended thinking round-trips.</b> What the model reasoned comes back as a {@link
 * Block.Provider} block carrying this vendor's own payload, signature included, and goes out again
 * untouched on the next turn. That is what the provider block is for: state that means nothing to
 * this engine and everything to the vendor that issued it.
 */
public final class AnthropicInferenceProvider implements InferenceProvider, AutoCloseable {

  /**
   * The OpenTelemetry GenAI semantic conventions' value for this vendor (agentic-o11y spec §1.1),
   * and also the tag on every {@link Block.Provider} block this adapter issues -- which is what
   * stops another vendor's reasoning state being sent here, and ours being sent there.
   */
  static final String PROVIDER_NAME = "anthropic";

  private static final String NAME = "Anthropic";

  private final AnthropicClient client;
  private final AnthropicRequests.Features features;
  private final boolean ownsClient;

  /**
   * Reads and writes the JSON text that tool schemas, call arguments and provider payloads travel
   * as. Supplied rather than made here: a mapper an application cannot configure is one it cannot
   * fix.
   */
  private final JsonMapper mapper;

  AnthropicInferenceProvider(
      AnthropicClient client,
      AnthropicRequests.Features features,
      boolean ownsClient,
      JsonMapper mapper) {
    this.client = client;
    this.features = Objects.requireNonNull(features, "features must not be null");
    this.ownsClient = ownsClient;
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  public static AnthropicInferenceProvider fromEnv() {
    return create(AnthropicProviderConfig::fromEnv);
  }

  public static AnthropicInferenceProvider create(AnthropicProviderCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    AnthropicProviderConfig config = new AnthropicProviderConfig();
    customizer.customize(config);
    return config.build();
  }

  /**
   * Total for anything the provider can do to us. A classified failure comes back as {@link
   * InferenceResult.Fault} rather than thrown, because the agent that asked for this is mid-turn
   * and only something reaching the fold ends that turn.
   *
   * <p>{@link AnthropicException} is the root of everything this SDK throws, and catching it is
   * what makes the handling total -- a list of subtypes would let the next one the SDK adds escape
   * silently. It is still narrow: a bug in this adapter escapes rather than being retried three
   * times and then recorded as the model's fault.
   */
  @Override
  public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
    Objects.requireNonNull(narrator, "narrator must not be null");
    try (StreamResponse<RawMessageStreamEvent> stream =
        client.messages().createStreaming(AnthropicRequests.toParams(request, features, mapper))) {
      MessageAccumulator accumulator = MessageAccumulator.create();
      boolean[] any = {false};
      stream.stream()
          .forEach(
              event -> {
                any[0] = true;
                accumulator.accumulate(event);
                narrate(event, narrator);
              });
      if (!any[0]) {
        return new InferenceResult.Fault(new Failure.Permanent("model returned no message"));
      }
      Message message;
      try {
        message = accumulator.message();
      } catch (IllegalStateException incomplete) {
        // The stream closed before message_stop: the SDK will not fold half a message, and
        // neither should this adapter.
        return new InferenceResult.Fault(
            new Failure.Permanent(
                "the stream ended before the answer was complete: " + incomplete.getMessage()));
      }
      return read(message);
    } catch (AnthropicException e) {
      return new InferenceResult.Fault(classify(e));
    }
  }

  /**
   * What a person watching is told as each delta lands: text, and thinking. Tool-input fragments
   * are not narrated -- half a JSON argument is not something anybody can watch -- and signatures
   * are the vendor's business.
   */
  private static void narrate(RawMessageStreamEvent event, AgentNarrator narrator) {
    if (!event.isContentBlockDelta()) {
      return;
    }
    RawContentBlockDelta delta = event.asContentBlockDelta().delta();
    if (delta.isText() && !delta.asText().text().isEmpty()) {
      narrator.narrate(new AgentEvent.ContentDelta(delta.asText().text()));
    } else if (delta.isThinking() && !delta.asThinking().thinking().isEmpty()) {
      narrator.narrate(new AgentEvent.ThinkingDelta(delta.asThinking().thinking()));
    }
  }

  /**
   * Which of the three shapes a reply is.
   *
   * <p>A refusal is reported in {@code stop_reason} rather than in the content, so it is taken
   * where it is offered -- an adapter for a wire that cannot say it has to guess, and this one does
   * not have to.
   *
   * <p>Otherwise the choice is made on the presence of a tool-use block. Reasoning and prose are
   * carried in either case: prose beside calls is the model talking while it works, which is
   * commentary rather than an answer, and the grammar is what says so.
   */
  private InferenceResult read(Message message) {
    if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
      return new InferenceResult.Refusal(
          message.stopDetails().map(Object::toString).orElse("the model declined to answer"));
    }

    boolean asking = message.content().stream().anyMatch(ContentBlock::isToolUse);
    List<Block> blocks =
        message.content().stream().flatMap(block -> toBlock(block, asking).stream()).toList();

    if (!asking) {
      if (blocks.isEmpty()) {
        // A reply with nothing in it: a model that spent its budget thinking, say. Said as a
        // fault rather than an answer of no blocks, which the story could not hold.
        return new InferenceResult.Fault(
            new Failure.Permanent(
                "model returned an empty answer (stop_reason="
                    + message.stopReason().map(Object::toString).orElse("none")
                    + ")"));
      }
      return new InferenceResult.Answer(
          blocks.stream().map(Block.AnswerContent.class::cast).toList());
    }
    return new InferenceResult.Actions(
        blocks.stream().map(Block.ActionRequestContent.class::cast).toList());
  }

  /**
   * @param asking whether this reply is a request for actions, which decides what its prose is:
   *     text arriving beside calls is commentary, and text arriving alone is an answer. Both are
   *     the same field on the wire, so only the shape of the reply can tell them apart.
   */
  private Optional<Block> toBlock(ContentBlock block, boolean asking) {
    if (block.isText()) {
      String text = block.text().orElseThrow().text();
      // Whitespace is not content in either position: an answer of it is an empty answer.
      // Formatting rather than the model talking: a block of whitespace is a dim empty line
      // in every console and a wasted block in every later request.
      if (text.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(asking ? new Block.Commentary(text) : new Block.Text(text));
    }
    if (block.isThinking()) {
      var thinking = block.thinking().orElseThrow();
      // Signature included, because this only comes back to the vendor if it can be shown not to
      // have been altered.
      return Optional.of(
          provider(
              Map.of(
                  "type",
                  "thinking",
                  "thinking",
                  thinking.thinking(),
                  "signature",
                  thinking.signature())));
    }
    if (block.isRedactedThinking()) {
      return Optional.of(
          provider(
              Map.of(
                  "type",
                  "redacted_thinking",
                  "data",
                  block.redactedThinking().orElseThrow().data())));
    }
    if (block.isToolUse()) {
      ToolUseBlock use = block.toolUse().orElseThrow();
      return Optional.of(new Block.ToolCall(use.id(), use.name(), arguments(use)));
    }
    // Server-side tools, container uploads, web-search results: nothing here asked for any of
    // them, so nothing here knows what to do with one. Dropped rather than guessed at.
    return Optional.empty();
  }

  /**
   * The call's arguments, as the JSON text a {@link Block.ToolCall} carries.
   *
   * <p>Converted to plain objects and written back out rather than taken as a node, because the two
   * sides are on different Jackson majors -- the SDK's is 2, this project's is 3. Plain maps and
   * lists are what neither has to know about the other.
   */
  private String arguments(ToolUseBlock use) {
    return mapper.writeValueAsString(use._input().convert(Object.class));
  }

  private Block.Provider provider(Map<String, Object> payload) {
    return new Block.Provider(PROVIDER_NAME, mapper.writeValueAsString(payload));
  }

  /**
   * Decides what a failed call means, which is the one thing this class knows and nothing above it
   * does.
   *
   * <p>Grounded in the SDK's own retry classification: it retries a raw {@code IOException} or
   * {@link AnthropicRetryableException} unconditionally, and otherwise by status code, <em>before
   * </em> a typed exception is ever constructed -- so by the time one surfaces here its own budget
   * is already spent, and only a further caller-driven retry with backoff is left.
   *
   * <p><b>Nothing is classified {@link Failure.Rejected}.</b> That is the one classification that
   * authorises throwing away something a person said, and it should rest on a measured marker in a
   * response rather than on a guess about what a 400 meant.
   */
  private static Failure classify(AnthropicException e) {
    if (e instanceof RateLimitException
        || e instanceof InternalServerException
        || e instanceof AnthropicRetryableException) {
      return new Failure.Transient("model call failed: " + e.getMessage());
    }
    // Transport-level: the request may or may not have been processed before the connection went.
    // Unknown rather than transient, because repeating it is safe exactly when repeating the work
    // is safe, and that is not this class's call to make.
    if (e instanceof AnthropicIoException) {
      return new Failure.Unknown("no answer from the model: " + e.getMessage());
    }
    return new Failure.Permanent("model call failed: " + e.getMessage());
  }

  /**
   * Closes the {@link AnthropicClient} this provider BUILT. A client handed in through {@link
   * AnthropicProviderConfig#client(AnthropicClient)} is never closed here: it was never opened
   * here.
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
}
