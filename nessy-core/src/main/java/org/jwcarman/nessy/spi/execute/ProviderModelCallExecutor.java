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
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.model.ContextOverflowException;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * The default {@code CallModel} performance: recall from Memory, stream from the provider, merge
 * deltas into settled blocks (a hundred chunks become one block), narrate texture as it arrives,
 * and yield the one settled fact. Message construction lives here and nowhere else on the model
 * side: facts are what happened; this is where what happened is assembled.
 */
public final class ProviderModelCallExecutor implements ModelCallExecutor {

  private final ModelProvider provider;
  private final ModelSettings config;
  private final ToolRegistry tools;
  private final Memory memory;

  public ProviderModelCallExecutor(
      ModelProvider provider, ModelSettings config, ToolRegistry tools, Memory memory) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.tools = Objects.requireNonNull(tools, "tools must not be null");
    this.memory = Objects.requireNonNull(memory, "memory must not be null");
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
    List<ContentBlock> blocks = new ArrayList<>();
    try (ModelStream stream = provider.stream(request)) {
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
          case ModelEvent.ToolUseEmitted(var call) -> {
            observer.on(new TurnEvent.ToolCallRequested(call));
            blocks.add(new ToolUseBlock(call));
          }
          case ModelEvent.TurnEnded(var reason, var usage) -> {
            return Awaited.ready(
                new ConversationEvent.ModelResponded(
                    state.id(), Message.assistant(List.copyOf(blocks)), reason, usage));
          }
        }
      }
    } catch (ContextOverflowException e) {
      return Awaited.ready(new ConversationEvent.ModelCallFailed(state.id(), e.getMessage()));
    }
    throw new IllegalStateException("model stream ended without a TurnEnded event");
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
    if (!blocks.isEmpty() && blocks.getLast() instanceof ThinkingBlock(String text, String _)) {
      blocks.set(blocks.size() - 1, new ThinkingBlock(text, signature));
    }
  }
}
