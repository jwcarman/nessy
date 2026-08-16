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
package org.jwcarman.nessy.spi.execute;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.internal.LoopObservations;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.model.ContextOverflowException;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default {@code CallModel} performance: recall from Memory, stream from the provider, merge
 * deltas into settled blocks (a hundred chunks become one block), narrate texture as it arrives,
 * and yield the one settled fact. Message construction lives here and nowhere else on the model
 * side: facts are what happened; this is where what happened is assembled.
 *
 * <p>Stream consumption runs inside one {@code nessy.model.call} observation: opened before the
 * provider is asked to stream, marked {@link Observation#error(Throwable)} on an unexpected {@link
 * RuntimeException}, stopped in a {@code finally} regardless of outcome, and tagged with the
 * settled usage the instant {@link ModelEvent.TurnEnded} arrives. Any {@code RuntimeException} the
 * provider call or stream consumption raises — a context overflow, an HTTP error, a socket reset, a
 * broken stream protocol, whatever a provider SDK decides to throw — yields a {@code
 * ModelCallFailed} fact instead of propagating: there is no allowlist and no marker interface, so
 * no provider failure can leave a conversation stuck at {@code AWAITING_MODEL}.
 */
public final class ProviderModelCallExecutor implements ModelCallExecutor {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProviderModelCallExecutor.class);

  private final ModelProvider provider;
  private final ModelSettings config;
  private final ToolRegistry tools;
  private final Memory memory;
  private final ObservationRegistry observations;

  public ProviderModelCallExecutor(
      ModelProvider provider,
      ModelSettings config,
      ToolRegistry tools,
      Memory memory,
      ObservationRegistry observations) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.tools = Objects.requireNonNull(tools, "tools must not be null");
    this.memory = Objects.requireNonNull(memory, "memory must not be null");
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
  }

  @Override
  public Awaited<ConversationEvent> execute(ConversationState state, TurnObserver observer) {
    ModelRequest request;
    try {
      request =
          new ModelRequest(
              memory.recall(state.id()),
              config.systemPrompt(),
              config.model(),
              config.maxTokens(),
              tools.specs(),
              config.capabilities(),
              null);
    } catch (ContextOverflowException e) {
      return Awaited.ready(new ConversationEvent.ModelCallFailed(state.id(), e.getMessage()));
    }
    return stream(state, request, observer);
  }

  /**
   * Consumes one {@link ModelStream} inside the {@code nessy.model.call} observation — extracted so
   * {@link #execute}'s own {@code try} is never nested (S1141).
   */
  private Awaited<ConversationEvent> stream(
      ConversationState state, ModelRequest request, TurnObserver observer) {
    Observation modelCall = LoopObservations.modelCall(observations, config.model());
    List<ContentBlock> blocks = new ArrayList<>();
    try (var _ = modelCall.openScope();
        ModelStream stream = provider.stream(request)) {
      for (ModelEvent event : stream) {
        switch (event) {
          case ModelEvent.TextChunk(String text) -> {
            observer.on(new TurnEvent.TextDelta(text));
            mergeText(blocks, text);
          }
          case ModelEvent.ThinkingChunk(String text) -> {
            observer.on(new TurnEvent.ThinkingDelta(text));
            mergeThinking(blocks, text);
          }
          case ModelEvent.ThinkingSigned(String signature) -> sign(blocks, signature);
          case ModelEvent.RedactedThinkingEmitted(String data) -> {
            observer.on(new TurnEvent.RedactedThinking(data));
            blocks.add(new RedactedThinkingBlock(data));
          }
          case ModelEvent.ToolUseEmitted(var call, var signature) -> {
            observer.on(new TurnEvent.ToolCallRequested(call));
            blocks.add(new ToolUseBlock(call, signature));
          }
          case ModelEvent.TurnEnded(var reason, var usage) -> {
            LoopObservations.recordUsage(modelCall, usage);
            return Awaited.ready(
                new ConversationEvent.ModelResponded(
                    state.id(), Message.assistant(List.copyOf(blocks)), reason, usage));
          }
        }
      }
      throw new IllegalStateException("model stream ended without a TurnEnded event");
    } catch (ContextOverflowException e) {
      return Awaited.ready(new ConversationEvent.ModelCallFailed(state.id(), e.getMessage()));
    } catch (RuntimeException e) {
      // Everything else the provider call or stream consumption can throw — a 403, a socket reset,
      // whatever a provider SDK decides to raise — folds to ModelCallFailed instead of leaking out
      // of execute() and leaving the conversation stuck at AWAITING_MODEL.
      modelCall.error(e);
      LOGGER.error("model call failed", e);
      String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
      return Awaited.ready(new ConversationEvent.ModelCallFailed(state.id(), reason));
    } finally {
      modelCall.stop();
    }
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
   * signature covers its exact text, so a later delta starts a fresh block.
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
