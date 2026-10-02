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

import com.google.genai.errors.ApiException;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.errors.ServerException;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponsePromptFeedback;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.VendorProperties;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Google Gemini, through the vendor's own java-genai SDK, against the Gemini Developer API.
 *
 * <p>Speaks the {@code gemini} wire.
 *
 * <p>Owns the client and is the only class here that touches the network; the projection onto the
 * wire lives in {@link GeminiRequests} and can be tested without a key.
 *
 * <p><b>Holds no model name.</b> Which model to call travels in {@link InferenceOptions}, so one
 * client serves several agent types asking for different models.
 *
 * <p><b>Streams, and narrates as it goes.</b> Every call is made through {@code
 * generateContentStream}; each partial response's text parts are narrated as they arrive -- a
 * thought summary as thinking and prose as text -- and the parts are folded into one response here,
 * because this SDK has no accumulator of its own: consecutive text parts of one kind are joined,
 * function calls arrive whole and are kept whole, and the last partial's finish reason is the
 * reply's. The reply is then read exactly as a non-streaming one would be; the engine receives one
 * result.
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
  static final String VENDOR = "gcp.gemini";

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

  /** The finish reason for a reply cut off at the output-token limit. */
  private static final String MAX_TOKENS = "MAX_TOKENS";

  private static final int TOO_MANY_REQUESTS = 429;
  private static final String CALL_FAILED = "model call failed: ";

  private final GeminiClient client;
  private final JsonMapper mapper;

  /**
   * The provider's own {@code gemini.} properties, checked at build; the agent type's overlay them.
   */
  private final Map<String, String> properties;

  GeminiInferenceProvider(GeminiClient client, JsonMapper mapper) {
    this(client, mapper, Map.of());
  }

  GeminiInferenceProvider(GeminiClient client, JsonMapper mapper, Map<String, String> properties) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
  }

  /** Reads the merged properties exactly as a request would, so a mistake fails the build (§7c). */
  @Override
  public void validate(InferenceOptions options) {
    Map<String, String> merged = VendorProperties.merge(properties, options.properties());
    GeminiPropertyReader.read(merged);
    GeminiPropertyReader.warnUnsupported(options.properties());
    GeminiPropertyReader.logIgnored(merged);
  }

  public static GeminiInferenceProvider fromEnv() {
    return of(GeminiProviderConfig::fromEnv);
  }

  public static GeminiInferenceProvider of(List<Customizer<GeminiProviderConfig>> customizers) {
    Objects.requireNonNull(customizers, "customizers must not be null");
    GeminiProviderConfig config = new GeminiProviderConfig();
    customizers.forEach(customizer -> customizer.customize(config));
    return config.build();
  }

  /** One customizer, for a caller that is not a container. */
  public static GeminiInferenceProvider of(Customizer<GeminiProviderConfig> customizer) {
    return of(List.of(Objects.requireNonNull(customizer, "customizer must not be null")));
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
  public String vendor() {
    return VENDOR;
  }

  @Override
  public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
    Objects.requireNonNull(narrator, "narrator must not be null");
    try (Stream<GenerateContentResponse> stream =
        client.generateContentStream(
            request.options().modelName(),
            GeminiRequests.toContents(request, mapper),
            GeminiRequests.toConfig(request, properties, mapper))) {
      Folded folded = new Folded();
      stream.forEach(partial -> folded.take(partial, narrator));
      if (!folded.any) {
        return noReply();
      }
      GenerateContentResponse response = folded.response();
      return read(response).withUsage(usageOf(response, request.options().modelName()));
    } catch (ApiException | GenAiIOException e) {
      return new InferenceResult.Fault(classify(e));
    }
  }

  /**
   * What the call cost, in the shape {@link Usage} defines. Prompt in; candidates and thoughts out,
   * since thinking is billed as output everywhere.
   *
   * <p><b>Nothing is summed into the input, because {@code promptTokenCount} already includes what
   * was served from cache.</b> What IS summed is the output: Gemini counts what it said and what it
   * thought separately, and thinking is billed at the output rate, so the two are added and the
   * thinking part is also kept on its own.
   *
   * <p><b>Cache write is unreported rather than zero, and always will be.</b> Gemini does not bill
   * a write on the call that uses the cache -- it is charged on the separate {@code CachedContent}
   * request that created it -- so there is no number here to report, and claiming zero would say
   * this call wrote nothing when this call is not where writing is counted.
   *
   * <p>Every count Gemini reports is optional, including the input one, which is why none of them
   * is defaulted to zero: a count it did not give is one nobody made.
   */
  private static Usage usageOf(GenerateContentResponse response, String requested) {
    String model = response.modelVersion().filter(name -> !name.isBlank()).orElse(requested);
    return response
        .usageMetadata()
        .<Usage>map(
            counted ->
                new Usage(
                    model,
                    counted.promptTokenCount().orElse(null),
                    outputOf(counted),
                    counted.cachedContentTokenCount().orElse(null),
                    null,
                    counted.thoughtsTokenCount().orElse(null)))
        .orElseGet(() -> Usage.unreported(model));
  }

  /** What the model produced, thinking included, or null if it counted neither part. */
  private static Integer outputOf(GenerateContentResponseUsageMetadata counted) {
    if (counted.candidatesTokenCount().isEmpty() && counted.thoughtsTokenCount().isEmpty()) {
      return null;
    }
    return counted.candidatesTokenCount().orElse(0) + counted.thoughtsTokenCount().orElse(0);
  }

  private static InferenceResult noReply() {
    return new InferenceResult.Fault(new Failure.Permanent("model returned no candidates"));
  }

  /**
   * The partial responses folded into one, and narrated on the way.
   *
   * <p>Only the first candidate is kept, which is the only one read. Text parts are joined with
   * their neighbours of the same kind (thought or prose) unless one carries a thought signature,
   * which must stay on the part it came with; every other part is kept as it arrived. The finish
   * reason and prompt feedback are whichever partial last said them.
   */
  private static final class Folded {
    private final List<Part> parts = new ArrayList<>();
    private Optional<FinishReason> finish = Optional.empty();
    private Optional<GenerateContentResponsePromptFeedback> feedback = Optional.empty();
    private Optional<GenerateContentResponseUsageMetadata> usage = Optional.empty();
    private boolean any;

    void take(GenerateContentResponse partial, InferenceNarrator narrator) {
      any = true;
      if (partial.promptFeedback().isPresent()) {
        feedback = partial.promptFeedback();
      }
      if (partial.usageMetadata().isPresent()) {
        // Sent on every partial and cumulative, so the last one is the whole call's.
        usage = partial.usageMetadata();
      }
      List<Candidate> candidates = partial.candidates().orElse(List.of());
      if (candidates.isEmpty()) {
        return;
      }
      Candidate candidate = candidates.getFirst();
      if (candidate.finishReason().isPresent()) {
        finish = candidate.finishReason();
      }
      for (Part part : candidate.content().flatMap(Content::parts).orElse(List.of())) {
        narrate(part, narrator);
        fold(part);
      }
    }

    private static void narrate(Part part, InferenceNarrator narrator) {
      part.text()
          .filter(text -> !text.isEmpty())
          .ifPresent(
              text -> {
                if (part.thought().orElse(false)) {
                  narrator.thinking(text);
                } else {
                  narrator.text(text);
                }
              });
    }

    private void fold(Part part) {
      if (part.text().isEmpty() || part.thoughtSignature().isPresent() || parts.isEmpty()) {
        parts.add(part);
        return;
      }
      Part last = parts.getLast();
      boolean lastIsThought = last.thought().orElse(false);
      boolean thisIsThought = part.thought().orElse(false);
      boolean joinable =
          last.text().isPresent()
              && last.thoughtSignature().isEmpty()
              && lastIsThought == thisIsThought;
      if (!joinable) {
        parts.add(part);
        return;
      }
      Part.Builder joined = Part.builder().text(last.text().get() + part.text().get());
      if (part.thought().orElse(false)) {
        joined.thought(true);
      }
      parts.set(parts.size() - 1, joined.build());
    }

    GenerateContentResponse response() {
      GenerateContentResponse.Builder response = GenerateContentResponse.builder();
      feedback.ifPresent(response::promptFeedback);
      usage.ifPresent(response::usageMetadata);
      if (!parts.isEmpty() || finish.isPresent()) {
        Candidate.Builder candidate =
            Candidate.builder().content(Content.builder().role("model").parts(parts).build());
        finish.ifPresent(candidate::finishReason);
        response.candidates(List.of(candidate.build()));
      }
      return response.build();
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
   *
   * <p>A reply whose finish reason is {@code MAX_TOKENS} was cut off at the output limit. With
   * prose in it that is a {@link InferenceResult.Truncated}; with a function call in it, a fault,
   * because the call's arguments are incomplete; with neither, the empty-answer fault.
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

    boolean cutOff = MAX_TOKENS.equals(finish);
    if (asking && cutOff) {
      // The arguments of a call cut off mid-way can still parse, as an empty object, and run.
      return new InferenceResult.Fault(
          new Failure.Permanent(
              "the reply was cut off at the output limit inside a tool call (finish_reason="
                  + finish
                  + ")"));
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
      List<Block.AnswerContent> content =
          blocks.stream().map(Block.AnswerContent.class::cast).toList();
      // Not asking, so every block is text (see add): a non-empty reply here holds text.
      if (cutOff) {
        return new InferenceResult.Truncated(content);
      }
      return new InferenceResult.Answer(content);
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
        VENDOR,
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
