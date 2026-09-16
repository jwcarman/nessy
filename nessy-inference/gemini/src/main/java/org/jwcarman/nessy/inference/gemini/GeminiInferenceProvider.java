package org.jwcarman.nessy.inference.gemini;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.errors.ServerException;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.spi.inference.Failure;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.narration.AgentNarrator;
import tools.jackson.databind.json.JsonMapper;

/**
 * Google Gemini, through the vendor's own java-genai SDK, against the Gemini Developer API.
 *
 * <p>Owns the client and is the only class here that touches the network; the projection onto the
 * wire lives in {@link GeminiRequests} and can be tested without a key.
 *
 * <p><b>Holds no model name.</b> Which model to call travels in {@link InferenceOptions}, so one
 * client serves several agent types asking for different models.
 *
 * <p><b>Does not stream, so it narrates nothing and answers all at once.</b> A caller cannot tell
 * it apart from one that does.
 *
 * <p><b>Thought signatures round-trip.</b> Gemini ties an opaque signature to each function call it
 * makes and wants it back with the call on the next turn. It comes back as a {@link Block.Provider}
 * block carrying this vendor's tag and the call it vouches for, and {@link GeminiRequests} replays
 * it onto the rebuilt call. A call with no signature is replayed with Google's documented
 * skip-validation sentinel rather than refused.
 */
public final class GeminiInferenceProvider implements InferenceProvider, AutoCloseable {

  /**
   * The OpenTelemetry GenAI semantic conventions' value for this vendor, and the tag on every
   * {@link Block.Provider} block this adapter issues.
   */
  static final String PROVIDER_NAME = "gcp.gemini";

  private static final String NAME = "Gemini";

  /**
   * The finish reasons that mean the provider STOPPED the turn rather than the model finishing one.
   * Each becomes a {@link InferenceResult.Refusal} carrying the vendor's own value as its category,
   * so a caller can tell a safety block from a recitation block without this adapter inventing a
   * taxonomy.
   */
  private static final Set<String> REFUSALS =
      Set.of(
          "SAFETY",
          "RECITATION",
          "LANGUAGE",
          "BLOCKLIST",
          "PROHIBITED_CONTENT",
          "SPII",
          "IMAGE_SAFETY",
          "IMAGE_PROHIBITED_CONTENT",
          "IMAGE_RECITATION",
          "IMAGE_OTHER");

  private static final int TOO_MANY_REQUESTS = 429;
  private static final String CALL_FAILED = "model call failed: ";

  private final GeminiClient client;
  private final JsonMapper mapper;

  GeminiInferenceProvider(GeminiClient client, JsonMapper mapper) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  public static GeminiInferenceProvider fromEnv() {
    return create(GeminiProviderConfig::fromEnv);
  }

  public static GeminiInferenceProvider create(GeminiProviderCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    GeminiProviderConfig config = new GeminiProviderConfig();
    customizer.customize(config);
    return config.build();
  }

  /**
   * Total for anything the provider can do to us. A classified failure comes back as {@link
   * InferenceResult.Fault} rather than thrown, because the agent that asked for this is mid-turn
   * and only something reaching the fold ends that turn.
   *
   * <p>{@link ApiException} and {@link GenAiIOException} are the two public roots of everything
   * this SDK throws; catching both is what makes the handling total. A bug in this adapter still
   * escapes rather than being recorded as the model's fault.
   */
  @Override
  public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
    try {
      GenerateContentResponse response =
          client.generateContent(
              request.options().modelName(),
              GeminiRequests.toContents(request, mapper),
              GeminiRequests.toConfig(request, mapper));
      return read(response);
    } catch (ApiException | GenAiIOException e) {
      return new InferenceResult.Fault(classify(e));
    }
  }

  /**
   * Which of the three shapes a reply is.
   *
   * <p>A prompt the provider would not even send to the model comes back with no candidates and a
   * {@code promptFeedback.blockReason}; a reply the provider cut off comes back with one of the
   * {@link #REFUSALS} as its finish reason. Both are refusals, reported where they are offered.
   *
   * <p>Gemini has no finish reason for "the model called a tool" -- a turn that calls a function
   * still finishes with {@code STOP} -- so the choice between an answer and a request for actions
   * is made on the presence of a function-call part.
   */
  private InferenceResult read(GenerateContentResponse response) {
    List<Candidate> candidates = response.candidates().orElse(List.of());
    if (candidates.isEmpty()) {
      return response
          .promptFeedback()
          .flatMap(feedback -> feedback.blockReason().map(Object::toString))
          .<InferenceResult>map(InferenceResult.Refusal::new)
          .orElseGet(
              () ->
                  new InferenceResult.Fault(new Failure.Permanent("model returned no candidates")));
    }
    Candidate candidate = candidates.getFirst();
    List<Part> parts = candidate.content().flatMap(Content::parts).orElse(List.of());
    boolean asking = parts.stream().anyMatch(part -> part.functionCall().isPresent());
    String finish = candidate.finishReason().map(Object::toString).orElse("none");

    if (!asking && REFUSALS.contains(finish)) {
      return new InferenceResult.Refusal(finish);
    }

    List<Block> blocks = new ArrayList<>();
    for (Part part : parts) {
      add(part, asking, blocks);
    }

    if (!asking) {
      if (blocks.isEmpty()) {
        // A reply with nothing in it: a model that spent its budget thinking, say. Said as a
        // fault rather than an answer of no blocks, which the story could not hold.
        return new InferenceResult.Fault(
            new Failure.Permanent("model returned an empty answer (finish_reason=" + finish + ")"));
      }
      return new InferenceResult.Answer(
          blocks.stream().map(Block.AnswerContent.class::cast).toList());
    }
    return new InferenceResult.Actions(
        blocks.stream().map(Block.ActionRequestContent.class::cast).toList());
  }

  /**
   * One part's blocks. A thought summary is prose about the reasoning, not state the vendor wants
   * back -- the continuity token is the signature on the call -- so it contributes nothing.
   */
  private void add(Part part, boolean asking, List<Block> blocks) {
    if (part.thought().orElse(false)) {
      return;
    }
    Optional<String> text = part.text().filter(value -> !value.isBlank());
    if (text.isPresent()) {
      blocks.add(asking ? new Block.Commentary(text.get()) : new Block.Text(text.get()));
    }
    if (part.functionCall().isPresent()) {
      FunctionCall call = part.functionCall().get();
      String id = call.id().orElseGet(() -> "gemini-call-" + blocks.size());
      blocks.add(new Block.ToolCall(id, call.name().orElseThrow(), arguments(call)));
      part.thoughtSignature().ifPresent(signature -> blocks.add(signature(id, signature)));
    }
  }

  private String arguments(FunctionCall call) {
    return mapper.writeValueAsString(call.args().orElse(Map.of()));
  }

  /** Gemini's own continuity token, kept whole: this adapter is the only thing that reads it. */
  private Block.Provider signature(String callId, byte[] signature) {
    return new Block.Provider(
        PROVIDER_NAME,
        mapper.writeValueAsString(
            Map.of(
                "type",
                "thought-signature",
                "callId",
                callId,
                "signature",
                Base64.getEncoder().encodeToString(signature))));
  }

  /**
   * Decides what a failed call means, which is the one thing this class knows and nothing above it
   * does.
   *
   * <p>A 5xx and a 429 are the provider's own admission that trying again may work. Any other
   * client error is a request the provider will keep refusing. A transport failure is unknown: the
   * request may or may not have been processed before the connection went, so repeating it is safe
   * exactly when repeating the work is safe, and that is not this class's call.
   *
   * <p><b>Nothing is classified {@link Failure.Rejected}.</b> That is the one classification that
   * authorises throwing away something a person said.
   */
  private static Failure classify(RuntimeException e) {
    if (e instanceof ServerException) {
      return new Failure.Transient(CALL_FAILED + e.getMessage());
    }
    if (e instanceof ClientException client && client.code() == TOO_MANY_REQUESTS) {
      return new Failure.Transient(CALL_FAILED + e.getMessage());
    }
    if (e instanceof GenAiIOException) {
      return new Failure.Unknown("no answer from the model: " + e.getMessage());
    }
    return new Failure.Permanent(CALL_FAILED + e.getMessage());
  }

  /**
   * Closes the client this provider BUILT; one handed in through the config is never closed here.
   */
  @Override
  public void close() {
    client.close();
  }

  /** This vendor, by name -- not an SPI method, kept because callers and logs want it. */
  public String name() {
    return NAME;
  }
}
