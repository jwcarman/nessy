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
   * The semconv names this executor's own span carries (agentic-o11y spec §1.1). {@code chat} is
   * both the span name and the observation's Micrometer name: a meter requires one stable
   * low-cardinality key set per name, so the three GenAI operations cannot share the semconv metric
   * name {@code gen_ai.client.operation.duration} — see {@code Observations}' javadoc for the full
   * reasoning and the §1.2 amendment it records.
   */
  private static final String CHAT = "chat";

  private static final String GEN_AI_OPERATION_NAME = "gen_ai.operation.name";
  private static final String GEN_AI_PROVIDER_NAME = "gen_ai.provider.name";
  private static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";
  private static final String GEN_AI_RESPONSE_FINISH_REASONS = "gen_ai.response.finish_reasons";
  private static final String GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
  private static final String GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
  private static final String NESSY_USAGE_CACHED_INPUT_TOKENS = "nessy.usage.cached_input_tokens";
  private static final String ERROR_TYPE = "error.type";

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
    try {
      return streamInto(request, chat);
    } catch (RuntimeException e) {
      // The span records the failure and is stopped below; the exception itself keeps going, to be
      // folded into a ModelOutcome.Failed by call() exactly as it always was.
      chat.lowCardinalityKeyValue(ERROR_TYPE, e.getClass().getSimpleName());
      chat.error(e);
      throw e;
    } finally {
      chat.stop();
    }
  }

  /**
   * The {@code chat} span (agentic-o11y spec §1.1). The two outcome-bearing low-cardinality keys
   * are declared here, as placeholders, and overwritten when the outcome is known: every
   * observation of one name must carry the same low-cardinality KEYS or the meter behind them has
   * unstable tags. Parented to the scope's open segment; parentless when the scope has none.
   */
  private Observation startChat() {
    Observation parent = parentSegment.get();
    Observation chat =
        Observation.createNotStarted(CHAT, observations)
            .contextualName(CHAT + " " + model.id())
            .lowCardinalityKeyValue(GEN_AI_OPERATION_NAME, CHAT)
            .lowCardinalityKeyValue(GEN_AI_PROVIDER_NAME, model.provider())
            .lowCardinalityKeyValue(GEN_AI_REQUEST_MODEL, model.id())
            .lowCardinalityKeyValue(GEN_AI_RESPONSE_FINISH_REASONS, KeyValue.NONE_VALUE)
            .lowCardinalityKeyValue(ERROR_TYPE, KeyValue.NONE_VALUE);
    if (parent != null) {
      chat.parentObservation(parent);
    }
    return chat.start();
  }

  private ModelOutcome streamInto(ModelRequest request, Observation chat) {
    List<ContentBlock> blocks = new ArrayList<>();
    List<ToolCall> calls = new ArrayList<>();
    try (ModelStream stream = model.stream(request)) {
      for (ModelEvent event : stream) {
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
                NESSY_USAGE_CACHED_INPUT_TOKENS, Long.toString(usage.cachedInputTokens()));
          }
        }
      }
    }
    return new ModelOutcome.Responded(blocks, calls, ModelResponseId.generate());
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
