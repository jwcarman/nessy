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
package org.jwcarman.nessy.agent.model;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.ModelOutcome;
import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bridge from the agent machine to core's {@link Model} SPI: recall from {@link Memory}, stream
 * from the bound model handle, merge deltas into settled blocks (a hundred chunks become one
 * block), narrate texture as it arrives on {@link TurnObserver}, and deliver exactly one {@link
 * AgentEvent.ModelFinished} to the dispatch-time {@link Sink}. Message construction lives here and
 * nowhere else on the model side.
 *
 * <p>This is one of the two places a span is opened from inside an executor rather than derived
 * from the fact stream (agentic-o11y spec §3.1): the {@code chat} observation, because {@link
 * ModelEvent.TurnEnded}'s {@link Usage} and {@link StopReason} arrive here and nowhere else. It is
 * parented to the scope's open {@code invoke_agent} segment explicitly — Micrometer's scope does
 * not follow {@code executor.execute} onto another virtual thread (spec §3.2) — and started/stopped
 * by hand rather than through {@code observe(Runnable)}, because what it times is a stream this
 * class iterates and closes.
 *
 * <p>{@link #callModel(Sink)} is async by {@link ModelCallExecutor}'s contract: the call is
 * submitted to {@code executor} and never runs on the dispatching stack. Any {@code
 * RuntimeException} the model call or stream consumption raises — a context overflow, an HTTP
 * error, a broken stream protocol, whatever a provider SDK decides to throw — folds to a {@link
 * ModelOutcome.Failed} instead of propagating, so a model failure always yields the one required
 * {@code ModelFinished} rather than escaping onto the executor thread.
 */
public final class ProviderModelCallExecutor implements ModelCallExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(ProviderModelCallExecutor.class);

  private final Model model;
  private final String systemPrompt;
  private final ModelSettings settings;
  private final ToolRegistry tools;
  private final Memory memory;
  private final TurnObserver turn;
  private final Executor executor;
  private final ObservationRegistry observations;
  private final Supplier<Observation> parentSegment;

  /**
   * The semconv names this executor's own span carries (agentic-o11y spec §1.1, corrected by the
   * 2026-08-26 semconv audit). The observation's Micrometer NAME is the semconv METER name {@code
   * gen_ai.client.operation.duration} — the histogram semconv defines for a provider-facing client
   * call — and {@code chat {model}} is its semconv SPAN name, carried as the contextual name.
   * Semconv gives {@code invoke_agent} and {@code execute_tool} their own meter names with their
   * own attribute sets, so nothing here is shared with them and every observation under this name
   * carries one stable low-cardinality key set, which is what a meter requires.
   */
  private static final String OPERATION_DURATION = "gen_ai.client.operation.duration";

  private static final String CHAT = "chat";

  private static final String GEN_AI_OPERATION_NAME = "gen_ai.operation.name";
  private static final String GEN_AI_PROVIDER_NAME = "gen_ai.provider.name";
  private static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";
  private static final String GEN_AI_REQUEST_STREAM = "gen_ai.request.stream";
  private static final String GEN_AI_REQUEST_MAX_TOKENS = "gen_ai.request.max_tokens";
  private static final String GEN_AI_RESPONSE_FINISH_REASONS = "gen_ai.response.finish_reasons";
  private static final String GEN_AI_RESPONSE_TIME_TO_FIRST_CHUNK =
      "gen_ai.response.time_to_first_chunk";
  private static final String GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
  private static final String GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
  private static final String GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS =
      "gen_ai.usage.cache_read.input_tokens";
  private static final String GEN_AI_USAGE_CACHE_WRITE_INPUT_TOKENS =
      "gen_ai.usage.cache_write.input_tokens";
  private static final String ERROR_TYPE = "error.type";

  /**
   * {@code gen_ai.request.stream} is Conditionally Required "if and only if the request is
   * streaming". Every model call this harness makes goes through {@link Model#stream}: there is no
   * non-streaming door, so this is a constant true rather than a flag read from somewhere.
   */
  private static final String ALWAYS_STREAMING = "true";

  /**
   * @param observations where the {@code chat} span is recorded — {@code ObservationRegistry.NOOP}
   *     unless the application supplied one, in which case this costs nothing
   * @param parentSegment this scope's open {@code invoke_agent} observation, or null when none is
   *     open — read afresh per call, since a segment ends at every park
   */
  public ProviderModelCallExecutor(
      Model model,
      String systemPrompt,
      ModelSettings settings,
      ToolRegistry tools,
      Memory memory,
      TurnObserver turn,
      Executor executor,
      ObservationRegistry observations,
      Supplier<Observation> parentSegment) {
    this.model = Objects.requireNonNull(model, "model must not be null");
    this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    this.settings = Objects.requireNonNull(settings, "settings must not be null");
    this.tools = Objects.requireNonNull(tools, "tools must not be null");
    this.memory = Objects.requireNonNull(memory, "memory must not be null");
    this.turn = Objects.requireNonNull(turn, "turn must not be null");
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
    this.parentSegment = Objects.requireNonNull(parentSegment, "parentSegment must not be null");
  }

  @Override
  public void callModel(Sink sink) {
    executor.execute(() -> sink.deliver(new AgentEvent.ModelFinished(call())));
  }

  private ModelOutcome call() {
    try {
      ModelRequest request =
          new ModelRequest(
              memory.recall(),
              systemPrompt,
              settings.maxTokens(),
              tools.specs(),
              settings.capabilities(),
              null);
      return stream(request);
    } catch (RuntimeException e) {
      return new ModelOutcome.Failed(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * Mints the {@link ModelResponseId} for this response here, at arrival — never in the reducer
   * (durable-deliveries spec §2 purity law): a CAS-retry re-handling the same {@code ModelFinished}
   * event must fold to identical state, which only holds if the id already rode in on the event.
   */
  private ModelOutcome stream(ModelRequest request) {
    Observation chat = startChat();
    // The scope is what makes this span an ANCESTOR rather than a bystander (in-the-loop amendment
    // §1, §2): a provider SDK's own HTTP instrumentation, or anything else the stream touches on
    // this thread, nests inside chat instead of starting a trace of its own. Closed in the finally,
    // before the stop, and guarded like every other call that can reach an application's handler.
    Observation.Scope scope = opened(chat);
    try {
      return streamInto(request, chat);
    } catch (RuntimeException e) {
      // The span records the failure and is stopped below; the exception itself keeps going, to be
      // folded into a ModelOutcome.Failed by call() exactly as it always was.
      chat.lowCardinalityKeyValue(ERROR_TYPE, e.getClass().getSimpleName());
      quietly(() -> chat.error(e));
      throw e;
    } finally {
      quietly(scope::close);
      quietly(chat::stop);
    }
  }

  /**
   * Opens one observation's scope, containing anything it throws — a {@code ScopeOpened} callback
   * is an application's handler like any other. A failed open yields {@link
   * Observation.Scope#NOOP}, so the {@code close()} in the {@code finally} is a harmless no-op
   * rather than a second failure on the same broken handler.
   */
  private static Observation.Scope opened(Observation observation) {
    try {
      return observation.openScope();
    } catch (RuntimeException e) {
      LOG.warn(
          "an observation handler threw opening chat's scope; the model call is unaffected", e);
      return Observation.Scope.NOOP;
    }
  }

  /**
   * Runs one instrumentation call, containing anything it throws (fix round 1). A turn must never
   * fail because the thing describing it did: an {@code ObservationHandler} lives in the
   * application, is arbitrary code, and reads key-values that a given span may legitimately not
   * carry — an application handler reading {@code gen_ai.usage.input_tokens} off a {@code chat}
   * that failed before the model reported any usage is the case that named this rule. Telemetry is
   * a description of the work, never a participant in it.
   */
  private static void quietly(Runnable instrumentation) {
    try {
      instrumentation.run();
    } catch (RuntimeException e) {
      LOG.warn("an observation handler threw around chat; the model call is unaffected", e);
    }
  }

  /**
   * Starts one observation, containing anything it throws (fix round 1) — see {@link #quietly}. A
   * failed start yields {@link Observation#NOOP}, so the {@code stop()} and the key-value writes
   * that follow are harmless no-ops rather than a second failure on the same broken handler.
   */
  private static Observation started(Supplier<Observation> start) {
    try {
      return start.get();
    } catch (RuntimeException e) {
      LOG.warn("an observation handler threw starting chat; the model call is unaffected", e);
      return Observation.NOOP;
    }
  }

  /**
   * The {@code chat} span (agentic-o11y spec §1.1). The two outcome-bearing low-cardinality keys
   * are declared here, as placeholders, and overwritten when the outcome is known: every
   * observation of one name must carry the same low-cardinality KEYS or the meter behind them has
   * unstable tags. Parented to the scope's open segment; parentless when the scope has none.
   */
  private Observation startChat() {
    return started(this::newChat);
  }

  /**
   * Who this span hangs off. An ENCLOSING observation wins when there is one — the nearest open
   * scope is a truer parent than a hand-looked-up segment (in-the-loop amendment §2). The segment
   * is the fallback for the case Micrometer's own scope cannot reach: this call runs on its own
   * virtual thread, where no scope followed the dispatch (agentic-o11y spec §3.2), which is the
   * usual case here.
   */
  private Observation parentOf() {
    Observation enclosing = observations.getCurrentObservation();
    return enclosing != null ? enclosing : parentSegment.get();
  }

  private Observation newChat() {
    Observation parent = parentOf();
    Observation chat =
        Observation.createNotStarted(OPERATION_DURATION, observations)
            .contextualName(CHAT + " " + model.id())
            .lowCardinalityKeyValue(GEN_AI_OPERATION_NAME, CHAT)
            .lowCardinalityKeyValue(GEN_AI_PROVIDER_NAME, model.provider())
            .lowCardinalityKeyValue(GEN_AI_REQUEST_MODEL, model.id())
            .lowCardinalityKeyValue(GEN_AI_REQUEST_STREAM, ALWAYS_STREAMING)
            .lowCardinalityKeyValue(GEN_AI_RESPONSE_FINISH_REASONS, KeyValue.NONE_VALUE)
            .lowCardinalityKeyValue(ERROR_TYPE, KeyValue.NONE_VALUE)
            // Recommended, and high-cardinality by Micrometer's division of labour: a numeric
            // budget is a span attribute, never a meter tag.
            .highCardinalityKeyValue(
                GEN_AI_REQUEST_MAX_TOKENS, Integer.toString(settings.maxTokens()));
    if (parent != null) {
      chat.parentObservation(parent);
    }
    return chat.start();
  }

  private ModelOutcome streamInto(ModelRequest request, Observation chat) {
    List<ContentBlock> blocks = new ArrayList<>();
    List<ToolCall> calls = new ArrayList<>();
    // gen_ai.response.time_to_first_chunk: "measured from when the client issues the generation
    // request to when the first chunk is received in the response stream", Recommended if the
    // request was streaming. Measured here rather than as the semconv METRIC of the same shape,
    // for the reason the token histogram is not recorded here either (spec §1.2): an
    // ObservationRegistry times observations and cannot record an arbitrary value histogram. It
    // rides the span; an application handler that wants the metric reads it on stop.
    long issuedAt = System.nanoTime();
    boolean firstChunkSeen = false;
    try (ModelStream stream = model.stream(request)) {
      for (ModelEvent event : stream) {
        if (!firstChunkSeen && isChunk(event)) {
          firstChunkSeen = true;
          chat.highCardinalityKeyValue(
              GEN_AI_RESPONSE_TIME_TO_FIRST_CHUNK, seconds(System.nanoTime() - issuedAt));
        }
        switch (event) {
          case ModelEvent.TextChunk(String text) -> {
            turn.on(new TurnEvent.TextDelta(text));
            mergeText(blocks, text);
          }
          case ModelEvent.ThinkingChunk(String text) -> {
            turn.on(new TurnEvent.ThinkingDelta(text));
            mergeThinking(blocks, text);
          }
          case ModelEvent.ThinkingSigned(String signature) -> sign(blocks, signature);
          case ModelEvent.RedactedThinkingEmitted(String data) -> {
            turn.on(new TurnEvent.RedactedThinking(data));
            blocks.add(new RedactedThinkingBlock(data));
          }
          case ModelEvent.ToolUseEmitted(ToolCall call, String signature) -> {
            turn.on(new TurnEvent.ToolCallRequested(call));
            blocks.add(new ToolUseBlock(call, signature));
            calls.add(call);
          }
          case ModelEvent.TurnEnded(StopReason reason, Usage usage) -> {
            // The one place the vendor's own accounting exists (agentic-o11y spec §3.1). The token
            // counts ride the span as key-values: an ObservationRegistry cannot record a value
            // histogram, so the semconv gen_ai.client.token.usage metric is the application's to
            // produce from these, in a handler that reads them on stop (spec §1.2).
            chat.lowCardinalityKeyValue(
                GEN_AI_RESPONSE_FINISH_REASONS, "[" + reason.name().toLowerCase(Locale.ROOT) + "]");
            chat.highCardinalityKeyValue(
                GEN_AI_USAGE_INPUT_TOKENS, Long.toString(usage.inputTokens()));
            chat.highCardinalityKeyValue(
                GEN_AI_USAGE_OUTPUT_TOKENS, Long.toString(usage.outputTokens()));
            chat.highCardinalityKeyValue(
                GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS, Long.toString(usage.cacheReadInputTokens()));
            chat.highCardinalityKeyValue(
                GEN_AI_USAGE_CACHE_WRITE_INPUT_TOKENS,
                Long.toString(usage.cacheWriteInputTokens()));
          }
        }
      }
    }
    return new ModelOutcome.Responded(blocks, calls, ModelResponseId.generate());
  }

  /**
   * Whether this event is a CHUNK of the response — the arrival semconv's time-to-first-chunk is
   * measured to. A signature, a turn-end summary and (deliberately) nothing else are bookkeeping
   * the provider emits around the content, not content arriving.
   */
  private static boolean isChunk(ModelEvent event) {
    return switch (event) {
      case ModelEvent.TextChunk _,
          ModelEvent.ThinkingChunk _,
          ModelEvent.RedactedThinkingEmitted _,
          ModelEvent.ToolUseEmitted _ ->
          true;
      case ModelEvent.ThinkingSigned _, ModelEvent.TurnEnded _ -> false;
    };
  }

  /** Nanoseconds as a seconds string — the unit semconv gives every {@code time_to_*} value. */
  private static String seconds(long nanos) {
    return Double.toString(nanos / 1_000_000_000.0d);
  }

  /** Merges a chunk into the trailing text block: a hundred deltas become one block. */
  private static void mergeText(List<ContentBlock> blocks, String text) {
    if (!blocks.isEmpty() && blocks.getLast() instanceof TextBlock(String existing)) {
      blocks.set(blocks.size() - 1, new TextBlock(existing + text));
    } else {
      blocks.add(new TextBlock(text));
    }
  }

  /**
   * Merges a chunk into the trailing unsigned thinking block. A signed block is closed: its
   * signature covers its exact text, so a later delta starts a fresh block (mirrors the old
   * executor's semantics; the brief's starting sketch omitted the {@code signature.isEmpty()}
   * guard).
   */
  private static void mergeThinking(List<ContentBlock> blocks, String text) {
    if (!blocks.isEmpty()
        && blocks.getLast() instanceof ThinkingBlock(String existing, String signature)
        && signature.isEmpty()) {
      blocks.set(blocks.size() - 1, new ThinkingBlock(existing + text, ""));
    } else {
      blocks.add(new ThinkingBlock(text, ""));
    }
  }

  /** Lands a signature on the trailing thinking block; a no-op when nothing trails to sign. */
  private static void sign(List<ContentBlock> blocks, String signature) {
    if (!blocks.isEmpty() && blocks.getLast() instanceof ThinkingBlock(String text, _)) {
      blocks.set(blocks.size() - 1, new ThinkingBlock(text, signature));
    }
  }
}
