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
package org.jwcarman.nessy.model.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.openai.core.ObjectMappers;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.completions.CompletionUsage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.TreeMap;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * Lazily translates the openai-java SDK's raw {@code ChatCompletionChunk} stream into {@link
 * ModelEvent}s.
 *
 * <p>Each call to {@link Iterator#next()} pulls only as many SDK chunks as needed to produce the
 * next {@link ModelEvent}; the turn is never buffered in full. Tool calls are the one case that
 * span several chunks: unlike Anthropic's block-indexed {@code tool_use} events, Chat Completions
 * streams {@code delta.tool_calls} fragments keyed by an integer {@code index} — the fragment that
 * opens an index carries the call's {@code id} and function {@code name}; every fragment after that
 * (for the same index) appends to {@code function.arguments}. Fragments for different indexes can
 * interleave chunk-to-chunk, so accumulation is keyed by index and flushed, in index order, only
 * once the choice's {@code finish_reason} arrives.
 *
 * <p>Usage arrives separately from the finish reason: OpenAI (when {@code
 * stream_options.include_usage} is set, which {@code OpenAiRequests} always sets) sends a final
 * chunk with an empty {@code choices} list carrying the completed token counts, after the chunk
 * that carried {@code finish_reason} — though some OpenAI-compatible servers (Azure, vLLM) instead
 * put usage directly on the same chunk that carries {@code finish_reason}; both shapes fold
 * correctly since usage is read independently of the choices loop. This class waits for the SDK
 * stream to actually end before emitting {@link ModelEvent.Stopped}, folding in whatever usage
 * arrived by then. Some OpenAI-compatible servers never send usage at all; that is tolerated and
 * yields {@link Usage#zero()} rather than a failure.
 *
 * <p>Only choice index {@code 0} is translated. {@code OpenAiRequests} never sets {@code n}, so a
 * well-behaved server only ever populates index {@code 0}; a second choice (from a caller-set
 * {@code n > 1}) is a distinct, independent completion with its own text and tool-call streams, and
 * folding it into the same accumulation state as index 0 would silently interleave two unrelated
 * turns into one. Any choice at a non-zero index is therefore ignored outright rather than
 * translated.
 *
 * <p>{@link #iterator()} is one-shot: each call wraps a fresh {@link Iterator} over the SDK's
 * {@code Stream}, but that {@code Stream} itself is a standard single-use {@link
 * java.util.stream.Stream} under the hood — traversing it (fully or partially) and then calling
 * {@link #iterator()} again throws from the SDK side, not this class.
 */
public final class OpenAiStream implements ModelStream {

  private final StreamResponse<ChatCompletionChunk> stream;

  public OpenAiStream(StreamResponse<ChatCompletionChunk> stream) {
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

  /**
   * Pulls SDK chunks one at a time and emits translated {@link ModelEvent}s from a small queue, so
   * a single chunk that maps to zero, one, or (across an accumulating tool call) eventually one
   * event never forces the whole turn into memory.
   */
  private static final class TranslatingIterator implements Iterator<ModelEvent> {

    private final Iterator<ChatCompletionChunk> chunks;
    private final Deque<ModelEvent> pending = new ArrayDeque<>();
    private final TreeMap<Long, PendingToolCall> toolCallsByIndex = new TreeMap<>();
    // Nothing reported YET, which is not the same as a call that cost nothing: a stream
    // whose usage event never arrives must not close claiming it was free.
    private Usage usage = Usage.unreported();
    private StopReason stopReason;

    /** Set instead of {@link #stopReason} when the vendor filtered the turn away. */
    private boolean refused;

    private boolean finishSeen;
    private boolean turnEndedEmitted;

    private TranslatingIterator(Iterator<ChatCompletionChunk> chunks) {
      this.chunks = chunks;
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
     * <p>A real stream always carries a {@code finish_reason} on some choice before it ends. If the
     * underlying chunks run out without one ever having been seen, the turn ended without a stop
     * reason — silently returning "no more events" here would let the harness treat that as a
     * normal, successful end of turn, so it fails loudly instead. Once a stream ends having seen a
     * {@code finish_reason}, {@link ModelEvent.Stopped} is queued exactly once, folding in whatever
     * usage arrived (possibly none, per the class javadoc).
     */
    private void fill() {
      while (pending.isEmpty() && chunks.hasNext()) {
        translate(chunks.next());
      }
      if (pending.isEmpty() && !chunks.hasNext()) {
        if (!finishSeen) {
          throw new IllegalStateException("stream ended without a finish_reason");
        }
        if (!toolCallsByIndex.isEmpty()) {
          // finish_reason always flushes and clears toolCallsByIndex (see translateFinish), so a
          // non-empty map here can only mean fragments arrived for these indexes *after* the
          // finish chunk — orphaned input with no finish event left to flush them. Silently
          // dropping them would lose part of a tool call the caller never finds out about.
          throw new IllegalStateException(
              "stream ended with orphaned tool-call fragments at index(es): "
                  + toolCallsByIndex.keySet());
        }
        if (!turnEndedEmitted) {
          // A filtered turn is its own event, not a stop reason: StopReason names only the three
          // ways a turn that HAPPENED can end, and a filtered turn did not happen.
          pending.add(
              refused
                  ? new ModelEvent.Refused(
                      "content_filter", "the provider filtered this response", usage)
                  : new ModelEvent.Stopped(stopReason, usage));
          turnEndedEmitted = true;
        }
      }
    }

    /**
     * Translates one chunk's choices (index {@code 0} only — see the class javadoc) and, if
     * present, its usage.
     */
    private void translate(ChatCompletionChunk chunk) {
      for (ChatCompletionChunk.Choice choice : chunk.choices()) {
        if (choice.index() == 0) {
          translateChoice(choice);
        }
      }
      chunk.usage().ifPresent(this::translateUsage);
    }

    /**
     * Translates one choice's delta and, if present, its finish reason.
     *
     * <p>An empty-string {@code content} (as opposed to absent) is suppressed rather than emitted
     * as an empty {@link ModelEvent.TextChunk}: the role-announcing first delta of a turn (({@code
     * role: "assistant", content: ""}) carries no actual text, and a downstream consumer gains
     * nothing from a zero-length chunk.
     *
     * <p>Deliberately unmapped: {@code delta.role()} (redundant — every assistant chunk implies the
     * same role), {@code delta.refusal()} (a distinct field from the {@code content_filter} finish
     * reason, carrying refusal message text there is no {@link ModelEvent} variant for), the
     * deprecated {@code delta.functionCall()} (superseded by {@code delta.toolCalls()}, which this
     * class does translate), and {@code choice.logprobs()}. All fall through as a silent no-op
     * rather than a crash — content this harness doesn't model is content it drops, not an error —
     * and are not logged, since this is a per-token hot path.
     */
    private void translateChoice(ChatCompletionChunk.Choice choice) {
      var delta = choice.delta();
      delta
          .content()
          .filter(text -> !text.isEmpty())
          .ifPresent(text -> pending.add(new ModelEvent.TextChunk(text)));
      delta.toolCalls().ifPresent(toolCalls -> toolCalls.forEach(this::accumulateToolCallDelta));
      choice.finishReason().ifPresent(this::translateFinish);
    }

    // ChatCompletionChunk.Choice.Delta.ToolCall shares its simple name with
    // org.jwcarman.nessy.api.tool.ToolCall (the type PendingToolCall assembles into, used
    // throughout
    // this file), so this one accessor is left fully qualified rather than sprinkling FQNs through
    // the rest of the class.
    private void accumulateToolCallDelta(
        com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta.ToolCall delta) {
      var pendingCall = toolCallsByIndex.computeIfAbsent(delta.index(), PendingToolCall::new);
      delta.id().ifPresent(pendingCall::setId);
      delta
          .function()
          .ifPresent(
              function -> {
                function.name().ifPresent(pendingCall::setName);
                function.arguments().ifPresent(pendingCall::appendArguments);
              });
    }

    /**
     * Flushes every accumulating tool call, in index order, then records the turn's stop reason.
     */
    private void translateFinish(ChatCompletionChunk.Choice.FinishReason finishReason) {
      toolCallsByIndex
          .values()
          .forEach(call -> pending.add(new ModelEvent.ToolCallEmitted(call.toToolCall())));
      toolCallsByIndex.clear();
      // A second finish_reason (e.g. a malformed or replayed stream) simply overwrites stopReason
      // rather than erroring — last-wins is deliberate, not an oversight; only a genuinely novel
      // finish_reason value is worth failing loudly over, and mapFinishReason already does that.
      refused = "content_filter".equals(finishReason.asString());
      stopReason = refused ? null : mapFinishReason(finishReason);
      finishSeen = true;
    }

    /**
     * No cache arithmetic here, unlike {@code AnthropicStream} and {@code BedrockStream}: OpenAI's
     * {@code prompt_tokens} is ALREADY the whole prompt, and {@code
     * prompt_tokens_details.cached_tokens} is a subset of it, not a sibling — OpenAI's own
     * cost-worked example derives the uncached remainder as {@code input_tokens - cached_tokens -
     * cache_write_tokens}, which only holds if the parts live inside the total. That is exactly the
     * shape the OpenTelemetry GenAI conventions ask for ("This value SHOULD include all types of
     * input tokens, including cached tokens"), so passing it through is the correct mapping and
     * summing here would double-count (2026-08-26 per-vendor token-semantics audit).
     */
    private void translateUsage(CompletionUsage completionUsage) {
      // A pass-through, never a sum: the vendor's promptTokens ALREADY includes cached tokens,
      // which is the shape semconv asks for. Summing here would double-count them.
      //
      // Absent details mean absent, not zero. LM Studio speaks this wire and sends no
      // prompt_tokens_details at all, and writing zero there would put a provider that keeps no
      // cache books on the same graph line as one whose cache never hits.
      // toIntExact rather than a cast: a count that genuinely did not fit should fail loudly
      // instead of wrapping into a negative token total that no graph would ever explain.
      Integer cacheRead =
          completionUsage
              .promptTokensDetails()
              .flatMap(CompletionUsage.PromptTokensDetails::cachedTokens)
              .map(Math::toIntExact)
              .orElse(null);
      // No cache-WRITE count exists on this wire: OpenAI caches prompts automatically and bills
      // no premium for the write, so there is nothing to report and null says exactly that.
      usage =
          new Usage(
              Math.toIntExact(completionUsage.promptTokens()),
              Math.toIntExact(completionUsage.completionTokens()),
              cacheRead,
              null);
    }

    // The SDK's finish-reason type (ChatCompletionChunk.Choice.FinishReason) shares its role with
    // org.jwcarman.nessy.api.StopReason (imported above) but not its simple name, so no collision
    // forces an FQN here — unlike the tool-call delta type below. Matching on the wire string
    // itself (rather than the SDK's own Known/Value enums) means a genuinely novel value fails
    // with our IllegalStateException instead of the SDK's own exception type, which is the
    // contract this method exists to keep.
    // "content_filter" is handled before this method is reached: it becomes a ModelEvent.Refused
    // rather than a stop reason, so it is deliberately absent from the switch below.
    private static StopReason mapFinishReason(ChatCompletionChunk.Choice.FinishReason reason) {
      return switch (reason.asString()) {
        case "stop" -> StopReason.END_TURN;
        case "length" -> StopReason.MAX_TOKENS;
        // "function_call" is the deprecated, pre-tool_calls legacy finish reason; mapping it to
        // TOOL_USE (rather than leaving it unmapped) keeps this harness working against servers
        // that still emit it.
        case "tool_calls", "function_call" -> StopReason.TOOL_USE;
        default ->
            throw new IllegalStateException(
                "Unrecognized OpenAI finish_reason: " + reason.asString());
      };
    }
  }

  /** Accumulates one tool call's streamed {@code function.arguments} fragments, keyed by index. */
  private static final class PendingToolCall {

    private final long index;
    private String id;
    private String name;
    private final StringBuilder arguments = new StringBuilder();

    PendingToolCall(long index) {
      this.index = index;
    }

    void setId(String id) {
      this.id = id;
    }

    void setName(String name) {
      this.name = name;
    }

    void appendArguments(String fragment) {
      arguments.append(fragment);
    }

    /**
     * Parses the accumulated fragments into this tool call's arguments.
     *
     * <p>Fails fast rather than truncating or otherwise guessing at a partial call: a stream that
     * stops mid-call (for example, a {@code length} cutoff) leaves genuinely unusable arguments,
     * and the harness's finally-save preserves session state up to this point, so the failure is
     * recoverable by inspection rather than silently corrupting the tool call.
     *
     * <p>{@code id}/{@code name} are guarded explicitly before ever reaching {@link ToolCall}'s
     * constructor: that constructor rejects a blank id or name too, but with a message that names
     * neither the stream index nor the fragments that got this far — undiagnosable for a call that
     * never received its opening fragment (the one that carries {@code id} and {@code
     * function.name}).
     */
    ToolCall toToolCall() {
      var json = arguments.isEmpty() ? "{}" : arguments.toString();
      if (id == null || name == null) {
        throw new IllegalStateException(
            "tool-call fragment(s) at index "
                + index
                + " never carried an id/function name; accumulated arguments: "
                + truncate(json));
      }
      JsonNode parsed;
      try {
        parsed = ObjectMappers.jsonMapper().readTree(json);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException(describeFailure("did not parse as JSON", json), e);
      }
      if (!parsed.isObject()) {
        throw new IllegalStateException(describeFailure("did not parse to a JSON object", json));
      }
      return new ToolCall(id, name, parsed);
    }

    private String describeFailure(String problem, String json) {
      return "tool '"
          + name
          + "' (id="
          + id
          + ") streamed arguments "
          + problem
          + ": "
          + truncate(json);
    }

    private static String truncate(String json) {
      return json.length() <= 200 ? json : json.substring(0, 200);
    }
  }
}
