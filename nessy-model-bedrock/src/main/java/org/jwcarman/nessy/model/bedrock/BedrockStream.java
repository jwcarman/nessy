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
package org.jwcarman.nessy.model.bedrock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelStream;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDeltaEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStartEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStopEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamMetadataEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.MessageStartEvent;
import software.amazon.awssdk.services.bedrockruntime.model.MessageStopEvent;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;

/**
 * Lazily translates the AWS SDK's raw {@code ConverseStreamOutput} events into {@link ModelEvent}s.
 *
 * <p>Constructed from a plain {@link Iterable} of SDK output events and a close callback, exactly
 * the shape {@code GeminiStream} takes — see {@link BedrockClient}'s class javadoc for why the real
 * {@code converseStream} call needs a bridge to produce one. A test fixture passes a plain {@link
 * java.util.List} of SDK-builder-constructed {@code ConverseStreamOutput} events (each of {@code
 * MessageStartEvent}, {@code ContentBlockStartEvent}, {@code ContentBlockDeltaEvent}, {@code
 * ContentBlockStopEvent}, {@code MessageStopEvent}, {@code ConverseStreamMetadataEvent} is a plain,
 * offline-constructible builder POJO) and a no-op close callback.
 *
 * <p>{@code toolUse} content blocks span three events ({@code content_block_start} through {@code
 * content_block_stop}), the same shape {@code AnthropicStream} handles: the block's id and name
 * arrive on {@code start}, its JSON input streams as string fragments on successive {@code delta}
 * events (accumulated per {@code contentBlockIndex}, the OpenAI/Gemini/Anthropic precedent for
 * fragmentary tool arguments), and the accumulated JSON is parsed once the block closes. Bedrock
 * issues no per-call continuity token, so every {@link ModelEvent.ToolCallEmitted} uses the
 * no-signature convenience constructor.
 *
 * <p>{@code image}, {@code reasoningContent}, and {@code citation} content-block variants are this
 * harness's affair to model later, not now: {@link BedrockModelProvider} does not advertise {@link
 * org.jwcarman.nessy.spi.model.Capability#THINKING} or {@link
 * org.jwcarman.nessy.spi.model.Capability#IMAGE_INPUT} in v1, so this class never requests them and
 * silently drops any that arrive anyway — content this harness doesn't model is content it drops,
 * not an error, the same convention {@code AnthropicStream} documents for its own
 * deliberately-unmapped block variants.
 *
 * <p>{@code metadata} (carrying {@code usage}) arrives once, as the stream's last event, after
 * {@code messageStop} — this class simply keeps whatever usage it last saw, honestly zeroed via
 * {@link Usage#zero()} if the stream never carried one at all (mirroring {@code GeminiStream}'s
 * tolerance for a usage-less stream).
 */
final class BedrockStream implements ModelStream {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Iterable<ConverseStreamOutput> chunks;
  private final Runnable onClose;

  BedrockStream(Iterable<ConverseStreamOutput> chunks, Runnable onClose) {
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
   * Pulls SDK events one at a time and emits translated {@link ModelEvent}s from a small queue, so
   * a turn is never buffered in full.
   *
   * <p>Implements {@link ConverseStreamResponseHandler.Visitor} directly rather than switching on
   * an {@code instanceof} chain: each {@code ConverseStreamOutput}'s own {@code accept} method
   * dispatches to the matching {@code visit*} override here, which is the double-dispatch shape the
   * SDK's visitor type is built for.
   */
  private static final class TranslatingIterator
      implements Iterator<ModelEvent>, ConverseStreamResponseHandler.Visitor {

    private final Iterator<ConverseStreamOutput> raw;
    private final Deque<ModelEvent> pending = new ArrayDeque<>();
    private final Map<Integer, PendingToolUse> toolUsesByIndex = new HashMap<>();
    // Nothing reported YET, which is not the same as a call that cost nothing: a stream whose
    // metadata event never arrives must not close claiming it was free.
    private Usage usage = Usage.unreported();
    private StopReason stopReason;

    /** The vendor's own stop reason when it refused, or null when it did not. */
    private String refusedAs;

    private boolean finishSeen;
    private boolean turnEndedEmitted;

    private TranslatingIterator(Iterator<ConverseStreamOutput> raw) {
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
     * Pulls SDK events until there's something to hand back, or the SDK stream is exhausted.
     *
     * <p>A real stream always carries a {@code messageStop} event before it ends. If the underlying
     * events run out without one ever having been seen, silently returning "no more events" here
     * would let the harness treat that as a normal, successful end of turn, so it fails loudly
     * instead — the same guard {@code GeminiStream} and {@code AnthropicStream} apply for their own
     * stream-integrity invariant.
     */
    private void fill() {
      while (pending.isEmpty() && raw.hasNext()) {
        raw.next().accept(this);
      }
      if (pending.isEmpty() && !raw.hasNext()) {
        if (!finishSeen) {
          throw new IllegalStateException("stream ended without a messageStop event");
        }
        if (!toolUsesByIndex.isEmpty()) {
          throw new IllegalStateException(
              "stream ended with orphaned tool-use fragments at index(es): "
                  + toolUsesByIndex.keySet());
        }
        if (!turnEndedEmitted) {
          // A refused turn is its own event, not a stop reason: StopReason names only the three
          // ways a turn that HAPPENED can end. Bedrock distinguishes a guardrail from a content
          // filter, so the vendor's own value is carried through as the category.
          pending.add(
              refusedAs != null
                  ? new ModelEvent.Refused(
                      refusedAs, "the provider stopped this response: " + refusedAs, usage)
                  : new ModelEvent.Stopped(stopReason, usage));
          turnEndedEmitted = true;
        }
      }
    }

    @Override
    public void visitMessageStart(MessageStartEvent event) {
      // Nothing to translate: the role it carries is always "assistant" for a Converse response,
      // and no usage rides this event — usage arrives once, on the closing metadata event.
    }

    @Override
    public void visitContentBlockStart(ContentBlockStartEvent event) {
      var toolUse = event.start() == null ? null : event.start().toolUse();
      if (toolUse != null) {
        toolUsesByIndex.put(
            event.contentBlockIndex(), new PendingToolUse(toolUse.toolUseId(), toolUse.name()));
      }
    }

    @Override
    public void visitContentBlockDelta(ContentBlockDeltaEvent event) {
      var delta = event.delta();
      if (delta == null) {
        return;
      }
      if (delta.text() != null && !delta.text().isEmpty()) {
        pending.add(new ModelEvent.TextChunk(delta.text()));
      }
      if (delta.toolUse() != null) {
        var pendingToolUse = toolUsesByIndex.get(event.contentBlockIndex());
        if (pendingToolUse != null) {
          pendingToolUse.appendPartialJson(delta.toolUse().input());
        }
      }
    }

    @Override
    public void visitContentBlockStop(ContentBlockStopEvent event) {
      var toolUse = toolUsesByIndex.remove(event.contentBlockIndex());
      if (toolUse != null) {
        pending.add(new ModelEvent.ToolCallEmitted(toolUse.toToolCall()));
      }
    }

    @Override
    public void visitMessageStop(MessageStopEvent event) {
      var reason = event.stopReason();
      if (reason
              == software.amazon.awssdk.services.bedrockruntime.model.StopReason
                  .GUARDRAIL_INTERVENED
          || reason
              == software.amazon.awssdk.services.bedrockruntime.model.StopReason.CONTENT_FILTERED) {
        refusedAs = reason.toString();
      } else {
        stopReason = mapStopReason(reason);
      }
      finishSeen = true;
    }

    /**
     * Folds the Converse API's three-way split of the prompt into the ONE number the OpenTelemetry
     * GenAI conventions ask for on {@code gen_ai.usage.input_tokens}, with the two cache counts as
     * subsets of it.
     *
     * <p>Bedrock's {@code inputTokens} is not the size of the prompt whenever caching is on. The
     * Bedrock prompt-caching guide: "When prompt caching is enabled, the {@code inputTokens} field
     * represents only the non-cached input tokens (tokens that were not read from or written to the
     * cache)", with {@code total input tokens = inputTokens + cacheReadInputTokens +
     * cacheWriteInputTokens}. Semconv wants the total — "This value SHOULD include all types of
     * input tokens, including cached tokens" — so the adapter adds them up, the same correction
     * {@code AnthropicStream.readUsage} carries and for the same reason.
     */
    @Override
    public void visitMetadata(ConverseStreamMetadataEvent event) {
      TokenUsage tokenUsage = event.usage();
      if (tokenUsage != null) {
        int cacheRead = orZero(tokenUsage.cacheReadInputTokens());
        int cacheWrite = orZero(tokenUsage.cacheWriteInputTokens());
        // Bedrock's own inputTokens EXCLUDES both cache counts, so the whole is the sum — which
        // is what semconv asks gen_ai.usage.input_tokens to mean.
        //
        // Absent cache counts stay ZERO here rather than becoming null, unlike the OpenAI wire:
        // Bedrock supports prompt caching and reports these fields, so their absence on a
        // response is the vendor saying nothing was cached — and a sum needs a number anyway.
        usage =
            new Usage(
                orZero(tokenUsage.inputTokens()) + cacheRead + cacheWrite,
                orZero(tokenUsage.outputTokens()),
                cacheRead,
                cacheWrite);
      }
    }

    private static int orZero(Integer value) {
      return value == null ? 0 : value;
    }

    // The SDK's StopReason (fully qualified below since its simple name collides with
    // org.jwcarman.nessy.api.StopReason, imported above) is a genuine Java enum with a
    // forward-compatible UNKNOWN_TO_SDK_VERSION sentinel for wire values this SDK build
    // predates, so a plain exhaustive switch with a throwing default handles both that sentinel
    // and any StopReason this mapping does not (yet) cover.
    //
    // "model_context_window_exceeded" maps to StopReason.MAX_TOKENS for the same reason
    // AnthropicStream's mapping does: semantically it is also "ran out of room" (context, not
    // output budget), and the fold already halts cleanly on MAX_TOKENS.
    //
    // "guardrail_intervened" and "content_filtered" both map to StopReason.REFUSAL — Bedrock's
    // two flavors of "the model was stopped by a safety mechanism", mirroring how
    // AnthropicStream maps "refusal" and GeminiStream maps SAFETY/RECITATION/PROHIBITED_CONTENT.
    //
    // GUARDRAIL_INTERVENED and CONTENT_FILTERED are handled before this method is reached: they
    // become a ModelEvent.Refused rather than a stop reason, so they are deliberately absent below.
    //
    // "malformed_model_output" and "malformed_tool_use" are genuinely novel to this mapping —
    // the model produced output Bedrock itself could not parse — and fall through to the
    // throwing default rather than being silently coerced into an existing StopReason.
    private static StopReason mapStopReason(
        software.amazon.awssdk.services.bedrockruntime.model.StopReason reason) {
      return switch (reason) {
        case END_TURN, STOP_SEQUENCE -> StopReason.END_TURN;
        case TOOL_USE -> StopReason.TOOL_USE;
        case MAX_TOKENS, MODEL_CONTEXT_WINDOW_EXCEEDED -> StopReason.MAX_TOKENS;
        default -> throw new IllegalStateException("Unrecognized Bedrock stopReason: " + reason);
      };
    }
  }

  /** Accumulates one {@code toolUse} content block's streamed input-JSON string fragments. */
  private static final class PendingToolUse {

    private final String id;
    private final String name;
    private final StringBuilder partialJson = new StringBuilder();

    private PendingToolUse(String id, String name) {
      this.id = id;
      this.name = name;
    }

    void appendPartialJson(String fragment) {
      if (fragment != null) {
        partialJson.append(fragment);
      }
    }

    /**
     * Parses the accumulated fragments into this tool call's arguments.
     *
     * <p>Fails fast rather than truncating or otherwise guessing at a partial call: a stream that
     * stops mid-{@code toolUse} leaves genuinely unusable arguments, so the failure is recoverable
     * by inspection rather than silently corrupting the tool call — the same reasoning {@code
     * AnthropicStream.PendingToolUse.toToolCall} documents.
     */
    ToolCall toToolCall() {
      var json = partialJson.isEmpty() ? "{}" : partialJson.toString();
      JsonNode arguments;
      try {
        arguments = MAPPER.readTree(json);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException(describeFailure("did not parse as JSON", json), e);
      }
      if (!arguments.isObject()) {
        throw new IllegalStateException(describeFailure("did not parse to a JSON object", json));
      }
      return new ToolCall(id, name, arguments);
    }

    private String describeFailure(String problem, String json) {
      var truncated = json.length() <= 200 ? json : json.substring(0, 200);
      return "tool '" + name + "' (id=" + id + ") streamed arguments " + problem + ": " + truncated;
    }
  }
}
