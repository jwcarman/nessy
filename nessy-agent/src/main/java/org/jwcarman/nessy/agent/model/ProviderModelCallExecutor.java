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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.ModelOutcome;
import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.Sink;
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

  public ProviderModelCallExecutor(
      Model model,
      String systemPrompt,
      ModelSettings settings,
      ToolRegistry tools,
      Memory memory,
      TurnObserver turn,
      Executor executor) {
    this.model = Objects.requireNonNull(model, "model must not be null");
    this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    this.settings = Objects.requireNonNull(settings, "settings must not be null");
    this.tools = Objects.requireNonNull(tools, "tools must not be null");
    this.memory = Objects.requireNonNull(memory, "memory must not be null");
    this.turn = Objects.requireNonNull(turn, "turn must not be null");
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
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
          case ModelEvent.TurnEnded _ -> {
            // usage metrics ride the observability design, not this plan
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
