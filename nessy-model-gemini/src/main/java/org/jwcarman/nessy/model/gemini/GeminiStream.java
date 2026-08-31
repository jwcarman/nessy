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
package org.jwcarman.nessy.model.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * Lazily translates the java-genai SDK's raw {@code GenerateContentResponse} stream into {@link
 * ModelEvent}s.
 *
 * <p>Constructed from a plain {@link Iterable} of response chunks and a close callback rather than
 * the SDK's own {@code ResponseStream} directly: {@code ResponseStream} implements both {@code
 * Iterable<GenerateContentResponse>} and {@code AutoCloseable} already, so production code ({@link
 * GeminiClient}'s real implementation) simply passes {@code responseStream} and {@code
 * responseStream::close}; a test fixture can pass a plain {@link List} of SDK-builder-constructed
 * {@code GenerateContentResponse} objects and a no-op — see {@link GeminiClient}'s class javadoc
 * for why {@code ResponseStream} itself resists offline construction.
 *
 * <p>Unlike Chat Completions' {@code index}-keyed {@code tool_calls} fragments, the Gemini
 * Developer API (as opposed to Vertex AI's opt-in {@code streamFunctionCallArguments}, which this
 * module never requests) never streams a function call's arguments incrementally: each {@code
 * functionCall} {@link Part} that arrives is already complete, so it translates straight to a
 * {@link ModelEvent.ToolCallEmitted} the moment it is seen — no accumulation state needed.
 *
 * <p>Gemini's {@code finishReason} has no dedicated "the model called a tool" value — a turn that
 * calls a function still reports {@code STOP}, the same reason as an ordinary text turn. This class
 * therefore tracks whether any function call was seen anywhere in the turn and reports {@link
 * StopReason#TOOL_USE} instead of the mapped {@code finishReason} whenever one was.
 *
 * <p>{@code usageMetadata} arrives on every chunk carrying the running cumulative totals for the
 * turn so far (confirmed against the SDK's own streaming examples), not a per-chunk delta — this
 * class simply keeps the latest one, so by the time the stream ends the figure folded into {@link
 * ModelEvent.Stopped} is already the turn's final total, honestly zeroed if no chunk ever carried
 * one.
 *
 * <p>{@code thought}-flagged {@link Part}s (Gemini's thinking/thought-summary output) are silently
 * skipped: {@link GeminiModelProvider} does not advertise {@link
 * org.jwcarman.nessy.spi.model.Capability#THINKING} in v1 and this class never requests it, so none
 * should arrive in practice, but a part this harness doesn't model yet is content it drops, not an
 * error.
 */
final class GeminiStream implements ModelStream {

  /**
   * The finish reasons that mean the provider STOPPED the turn rather than the model finishing one.
   * Each becomes a {@link ModelEvent.Refused} carrying this very value as its category, so a caller
   * can tell a safety block from a recitation block without this adapter inventing a taxonomy of
   * its own.
   */
  private static final java.util.Set<String> REFUSALS =
      java.util.Set.of(
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

  private final Iterable<GenerateContentResponse> chunks;
  private final Runnable onClose;

  GeminiStream(Iterable<GenerateContentResponse> chunks, Runnable onClose) {
    this.chunks = Objects.requireNonNull(chunks, "chunks must not be null");
    this.onClose = Objects.requireNonNull(onClose, "onClose must not be null");
  }

  @Override
  public Iterator<ModelEvent> iterator() {
    return new TranslatingIterator(chunks.iterator());
  }

  @Override
  public void close() {
    onClose.run();
  }

  /**
   * Pulls SDK response chunks one at a time and emits translated {@link ModelEvent}s from a small
   * queue, so a turn is never buffered in full.
   */
  private static final class TranslatingIterator implements Iterator<ModelEvent> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Iterator<GenerateContentResponse> raw;
    private final Deque<ModelEvent> pending = new ArrayDeque<>();
    private Usage usage = new Usage(0, 0);
    private StopReason stopReason;

    /** The vendor's own finish reason when it refused, or null when it did not. */
    private String refusedAs;

    private boolean finishSeen;
    private boolean turnEndedEmitted;
    private boolean sawToolCall;
    private long nextMintedCallIndex;

    private TranslatingIterator(Iterator<GenerateContentResponse> raw) {
      this.raw = raw;
    }

    @Override
    public boolean hasNext() {
      fill();
      return !pending.isEmpty();
    }

    @Override
    public ModelEvent next() {
      fill();
      var next = pending.poll();
      if (next == null) {
        throw new NoSuchElementException();
      }
      return next;
    }

    /**
     * Pulls SDK chunks until there's something to hand back, or the SDK stream is exhausted.
     *
     * <p>A real stream always carries a {@code finishReason} on its final candidate. If the
     * underlying chunks run out without one ever having been seen — a blocked prompt with no
     * candidates at all, for instance — silently returning "no more events" here would let the
     * harness treat that as a normal, successful end of turn, so it fails loudly instead.
     */
    private void fill() {
      while (pending.isEmpty() && raw.hasNext()) {
        translate(raw.next());
      }
      if (pending.isEmpty() && !raw.hasNext()) {
        if (!finishSeen) {
          throw new IllegalStateException("stream ended without a finishReason");
        }
        if (!turnEndedEmitted) {
          // A refused turn is its own event, not a stop reason: StopReason names only the three
          // ways a turn that HAPPENED can end. Gemini has a whole family of refusal reasons, so
          // the vendor's own value is carried through as the category rather than flattened.
          pending.add(
              refusedAs != null
                  ? new ModelEvent.Refused(
                      refusedAs, "the provider stopped this response: " + refusedAs, usage)
                  : new ModelEvent.Stopped(stopReason, usage));
          turnEndedEmitted = true;
        }
      }
    }

    private void translate(GenerateContentResponse response) {
      response.usageMetadata().ifPresent(this::translateUsage);
      List<Candidate> candidates = response.candidates().orElse(List.of());
      if (!candidates.isEmpty()) {
        translateCandidate(candidates.get(0));
      }
    }

    /**
     * Only the first candidate is translated; {@code GeminiRequests} never asks for more than one.
     */
    private void translateCandidate(Candidate candidate) {
      List<Part> parts = candidate.content().flatMap(Content::parts).orElse(List.of());
      parts.forEach(this::translatePart);
      candidate.finishReason().ifPresent(this::translateFinish);
    }

    private void translatePart(Part part) {
      if (part.thought().orElse(false)) {
        return;
      }
      part.text()
          .filter(text -> !text.isEmpty())
          .ifPresent(text -> pending.add(new ModelEvent.TextChunk(text)));
      part.functionCall().ifPresent(call -> translateFunctionCall(call, part));
    }

    /**
     * The {@code thoughtSignature} — Gemini's opaque continuity token for a function call — lives
     * on the enclosing {@link Part}, not on {@link FunctionCall} itself, so the part is threaded
     * through alongside the call it wraps.
     */
    private void translateFunctionCall(FunctionCall call, Part part) {
      sawToolCall = true;
      String id = call.id().orElseGet(this::mintCallId);
      String name =
          call.name()
              .orElseThrow(() -> new IllegalStateException("function call arrived with no name"));
      Map<String, Object> args = call.args().orElse(Map.of());
      JsonNode arguments = MAPPER.valueToTree(args);
      ToolCall toolCall = new ToolCall(id, name, arguments);
      Optional<String> signature =
          part.thoughtSignature().map(bytes -> Base64.getEncoder().encodeToString(bytes));
      pending.add(
          signature
              .map(sig -> new ModelEvent.ToolCallEmitted(toolCall, sig))
              .orElseGet(() -> new ModelEvent.ToolCallEmitted(toolCall)));
    }

    /**
     * Deterministic per-stream minted id, used only when the SDK omits {@code FunctionCall.id()}.
     */
    private String mintCallId() {
      return "gemini-call-" + nextMintedCallIndex++;
    }

    private void translateFinish(FinishReason reason) {
      if (!sawToolCall && REFUSALS.contains(reason.toString())) {
        // A refusal still FINISHES the stream — it just finishes it a different way, so
        // finishSeen must be set here too or the fold reports a stream that never ended.
        refusedAs = reason.toString();
      } else {
        stopReason = sawToolCall ? StopReason.TOOL_USE : mapFinishReason(reason);
      }
      finishSeen = true;
    }

    /**
     * No cache arithmetic here, unlike {@code AnthropicStream} and {@code BedrockStream}: Gemini's
     * {@code promptTokenCount} is ALREADY the whole prompt. The API reference says so in as many
     * words — "When {@code cachedContent} is set, this is still the total effective prompt size
     * meaning this includes the number of tokens in the cached content" — which makes {@code
     * cachedContentTokenCount} a subset, exactly the shape the OpenTelemetry GenAI conventions ask
     * for. Summing here would double-count (2026-08-26 per-vendor token-semantics audit).
     */
    private void translateUsage(GenerateContentResponseUsageMetadata metadata) {
      // Usage carries two numbers now. Gemini's promptTokenCount already INCLUDES the cached
      // content it read back, which is what semconv asks gen_ai.usage.input_tokens to mean, so
      // this stays a pass-through and never sums.
      usage =
          new Usage(
              metadata.promptTokenCount().orElse(0), metadata.candidatesTokenCount().orElse(0));
    }

    /**
     * Matches on the wire string ({@link FinishReason#toString()}) rather than the SDK's own {@code
     * Known} enum, the same reasoning {@code OpenAiStream.mapFinishReason} documents: a genuinely
     * novel value fails with our {@link IllegalStateException} instead of silently degrading to
     * {@code FINISH_REASON_UNSPECIFIED} the way {@link FinishReason}'s own lenient constructor
     * would.
     */
    private static StopReason mapFinishReason(FinishReason reason) {
      return switch (reason.toString()) {
        case "STOP" -> StopReason.END_TURN;
        case "MAX_TOKENS" -> StopReason.MAX_TOKENS;
        default -> throw new IllegalStateException("Unrecognized Gemini finishReason: " + reason);
      };
    }
  }
}
