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
package org.jwcarman.nessy.model.anthropic;

import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawContentBlockStopEvent;
import com.anthropic.models.messages.RawMessageDeltaEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.session.Usage;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * Lazily translates the anthropic-java SDK's raw stream events into {@link ModelEvent}s.
 *
 * <p>Each call to {@link Iterator#next()} pulls only as many SDK events as needed to produce the
 * next {@link ModelEvent}; the turn is never buffered in full. {@code tool_use} content blocks are
 * the one case that spans several SDK events ({@code content_block_start} through {@code
 * content_block_stop}): their id, name, and streamed JSON fragments are accumulated internally and
 * surfaced as a single {@link ModelEvent.ToolUseEmitted} once the block closes.
 *
 * <p>{@link #iterator()} is one-shot: each call wraps a fresh {@link Iterator} over the SDK's
 * {@code Stream}, but that {@code Stream} itself is a standard single-use {@link
 * java.util.stream.Stream} under the hood — traversing it (fully or partially) and then calling
 * {@link #iterator()} again throws from the SDK side, not this class.
 */
public final class AnthropicStream implements ModelStream {

  private final StreamResponse<RawMessageStreamEvent> stream;

  public AnthropicStream(StreamResponse<RawMessageStreamEvent> stream) {
    this.stream = Objects.requireNonNull(stream, "stream must not be null");
  }

  @Override
  public Iterator<ModelEvent> iterator() {
    return new TranslatingIterator(stream.stream().iterator());
  }

  @Override
  public void close() {
    stream.close();
  }

  // The SDK's stop-reason type shares its simple name with org.jwcarman.nessy.api.StopReason
  // (imported above), so the parameter type here is left fully qualified rather than sprinkling
  // FQNs through the rest of the file. Matching on the wire string itself (rather than the SDK's
  // own Known/Value enums) means a genuinely novel value fails with our IllegalStateException
  // instead of the SDK's own exception type, which is the contract this method exists to keep.
  //
  // "model_context_window_exceeded" maps to StopReason.MAX_TOKENS: semantically it is also "ran
  // out of room" (context, not output budget), and the reducer already halts cleanly on
  // MAX_TOKENS, so no new StopReason variant is needed to handle it correctly.
  //
  // "pause_turn" is a KNOWN SDK value that this method still deliberately throws on: it is
  // emitted only for server-side tools (e.g. the built-in web search tool), which this harness
  // never requests via AnthropicRequests, so it is unreachable through our params today. It will
  // gain a real mapping when server-tool support ships. Every other value not named above is
  // genuinely novel to the SDK and also throws.
  private static StopReason mapStopReason(com.anthropic.models.messages.StopReason reason) {
    return switch (reason.asString()) {
      case "end_turn", "stop_sequence" -> StopReason.END_TURN;
      case "tool_use" -> StopReason.TOOL_USE;
      case "max_tokens", "model_context_window_exceeded" -> StopReason.MAX_TOKENS;
      case "refusal" -> StopReason.REFUSAL;
      default ->
          throw new IllegalStateException(
              "Unrecognized Anthropic stop_reason: " + reason.asString());
    };
  }

  /**
   * Pulls SDK events one at a time and emits translated {@link ModelEvent}s from a small queue, so
   * a single SDK event that maps to zero, one, or (across a tool-use block) eventually one
   * accumulated event never forces the whole turn into memory.
   */
  private static final class TranslatingIterator implements Iterator<ModelEvent> {

    private final Iterator<RawMessageStreamEvent> events;
    private final Deque<ModelEvent> pending = new ArrayDeque<>();
    private final Map<Long, PendingToolUse> toolUsesByIndex = new HashMap<>();
    private long inputTokens;
    private long cachedInputTokens;
    private boolean turnEnded;

    private TranslatingIterator(Iterator<RawMessageStreamEvent> events) {
      this.events = events;
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
     * <p>A real stream always closes with {@code message_delta} (which is where {@link
     * ModelEvent.TurnEnded} comes from) before {@code message_stop}. If the underlying events run
     * out without one ever having been translated, the turn ended without a stop reason — silently
     * returning "no more events" here would let the harness treat that as a normal, successful end
     * of turn, so it fails loudly instead.
     *
     * <p>Mirrors {@code OpenAiStream}'s exhaustion guard: {@code translateContentBlockStop} always
     * removes an index from {@link #toolUsesByIndex} when its {@code content_block_stop} arrives,
     * so a non-empty map here can only mean the stream ended with {@code content_block_start}
     * events that never got a matching {@code content_block_stop} — orphaned {@code tool_use}
     * accumulation with no closing event left to flush it. Silently dropping it would lose part of
     * a tool call the caller never finds out about.
     */
    private void fill() {
      while (pending.isEmpty() && events.hasNext()) {
        translate(events.next());
      }
      if (pending.isEmpty() && !events.hasNext()) {
        if (!turnEnded) {
          throw new IllegalStateException("stream ended without a message_delta (no stop_reason)");
        }
        if (!toolUsesByIndex.isEmpty()) {
          throw new IllegalStateException(
              "stream ended with orphaned tool-use fragments at index(es): "
                  + toolUsesByIndex.keySet());
        }
      }
    }

    private void translate(RawMessageStreamEvent event) {
      if (event.isMessageStart()) {
        var usage = event.asMessageStart().message().usage();
        inputTokens = usage.inputTokens();
        cachedInputTokens = usage.cacheReadInputTokens().orElse(0L);
      } else if (event.isContentBlockStart()) {
        translateContentBlockStart(event.asContentBlockStart());
      } else if (event.isContentBlockDelta()) {
        translateContentBlockDelta(event.asContentBlockDelta());
      } else if (event.isContentBlockStop()) {
        translateContentBlockStop(event.asContentBlockStop());
      } else if (event.isMessageDelta()) {
        translateMessageDelta(event.asMessageDelta());
      }
      // message_stop carries nothing the translation table maps; TurnEnded already went out
      // when message_delta arrived.
    }

    /**
     * Translates the two {@code content_block_start} variants that need one: {@code tool_use}
     * (which opens accumulation, keyed by block index) and {@code redacted_thinking} (which is
     * complete in one shot, so it's emitted immediately). {@code text} and {@code thinking} blocks
     * carry nothing to translate here — their content arrives via {@code content_block_delta}.
     *
     * <p>Deliberately unmapped: {@code server_tool_use} and the various {@code *_tool_result} block
     * variants (server-side tools this harness never declares, so they never appear when this
     * stream is driven by {@code AnthropicRequests}-built params), plus any future block variant
     * the SDK adds. All fall through as a silent no-op rather than a crash — content this harness
     * doesn't model is content it drops, not an error — and are not logged, since this is a
     * per-token hot path.
     */
    private void translateContentBlockStart(RawContentBlockStartEvent start) {
      var block = start.contentBlock();
      if (block.isToolUse()) {
        var toolUse = block.asToolUse();
        toolUsesByIndex.put(start.index(), new PendingToolUse(toolUse.id(), toolUse.name()));
      } else if (block.isRedactedThinking()) {
        pending.add(new ModelEvent.RedactedThinkingEmitted(block.asRedactedThinking().data()));
      }
    }

    /**
     * Translates the four {@code content_block_delta} variants the mapping table covers: {@code
     * text_delta}, {@code thinking_delta}, {@code signature_delta}, and {@code input_json_delta}
     * (which appends to whichever tool-use block is accumulating at that index, if any).
     *
     * <p>Deliberately unmapped: {@code citations_delta} — there's no {@link ModelEvent} variant for
     * citation metadata, so it falls through as a silent no-op rather than a crash, same as the
     * unmapped content-block-start variants above and for the same reason (dropped, not an error;
     * not logged, since this is a per-token hot path).
     */
    private void translateContentBlockDelta(RawContentBlockDeltaEvent event) {
      var delta = event.delta();
      if (delta.isText()) {
        pending.add(new ModelEvent.TextChunk(delta.asText().text()));
      } else if (delta.isThinking()) {
        pending.add(new ModelEvent.ThinkingChunk(delta.asThinking().thinking()));
      } else if (delta.isSignature()) {
        pending.add(new ModelEvent.ThinkingSigned(delta.asSignature().signature()));
      } else if (delta.isInputJson()) {
        var toolUse = toolUsesByIndex.get(event.index());
        if (toolUse != null) {
          toolUse.appendPartialJson(delta.asInputJson().partialJson());
        }
      }
    }

    private void translateContentBlockStop(RawContentBlockStopEvent event) {
      var toolUse = toolUsesByIndex.remove(event.index());
      if (toolUse != null) {
        pending.add(new ModelEvent.ToolUseEmitted(toolUse.toToolCall()));
      }
    }

    private void translateMessageDelta(RawMessageDeltaEvent event) {
      var delta = event.delta();
      var stopReason =
          delta
              .stopReason()
              .orElseThrow(
                  () -> new IllegalStateException("message_delta event is missing stop_reason"));
      var usage = new Usage(inputTokens, event.usage().outputTokens(), cachedInputTokens);
      pending.add(new ModelEvent.TurnEnded(mapStopReason(stopReason), usage));
      turnEnded = true;
    }
  }

  /** Accumulates one {@code tool_use} content block's streamed {@code input_json_delta} parts. */
  private static final class PendingToolUse {

    private final String id;
    private final String name;
    private final StringBuilder partialJson = new StringBuilder();

    private PendingToolUse(String id, String name) {
      this.id = id;
      this.name = name;
    }

    void appendPartialJson(String fragment) {
      partialJson.append(fragment);
    }

    /**
     * Parses the accumulated fragments into this tool call's arguments.
     *
     * <p>Fails fast rather than truncating or otherwise guessing at a partial call: a stream that
     * stops mid-{@code tool_use} (for example, a {@code max_tokens} cutoff) leaves genuinely
     * unusable arguments, and the harness's finally-save preserves session state up to this point,
     * so the failure is recoverable by inspection rather than silently corrupting the tool call.
     */
    ToolCall toToolCall() {
      var json = partialJson.isEmpty() ? "{}" : partialJson.toString();
      JsonNode arguments;
      try {
        arguments = ObjectMappers.jsonMapper().readTree(json);
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
