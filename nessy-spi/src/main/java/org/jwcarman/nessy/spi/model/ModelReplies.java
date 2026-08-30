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
package org.jwcarman.nessy.spi.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.block.AssistantContentBlock;
import org.jwcarman.nessy.api.block.RedactedThinkingBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ThinkingBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.message.AssistantMessage;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;

/**
 * Turns a stream of a model talking into the message it said.
 *
 * <p>Lives here, once, so the blocking door and the streaming one cannot disagree about what a
 * sequence of chunks means. {@code Model.call} is this with nobody watching; an engine that paints
 * words as they arrive is this with a listener.
 *
 * <p><b>Prose and reasoning are accumulated, not emitted per chunk.</b> A provider sends text in
 * whatever pieces its transport happened to produce; a transcript holding forty one-word blocks
 * would be an artefact of the network rather than anything the model said. Thinking is accumulated
 * for a second reason too: its signature arrives after its text, and a block cannot be built before
 * the thing that makes it trustworthy on replay.
 */
public final class ModelReplies {

  private ModelReplies() {}

  /**
   * Drains {@code stream} to the end, showing every event to {@code watcher} on the way past.
   *
   * <p>Closes the stream. A provider needs to know when nobody is listening.
   */
  public static ModelResult drain(ModelStream stream, Consumer<ModelEvent> watcher) {
    List<AssistantContentBlock> blocks = new ArrayList<>();
    StringBuilder prose = new StringBuilder();
    StringBuilder reasoning = new StringBuilder();
    ModelResult result = null;
    try (ModelStream events = stream) {
      for (ModelEvent event : events) {
        watcher.accept(event);
        switch (event) {
          case ModelEvent.TextChunk chunk -> prose.append(chunk.text());
          case ModelEvent.ThinkingChunk chunk -> reasoning.append(chunk.text());
          case ModelEvent.ThinkingSigned signed -> {
            blocks.add(new ThinkingBlock(reasoning.toString(), signed.signature()));
            reasoning.setLength(0);
          }
          case ModelEvent.RedactedThinkingEmitted redacted ->
              blocks.add(new RedactedThinkingBlock(redacted.data()));
          case ModelEvent.ToolCallEmitted emitted -> {
            flush(blocks, prose);
            blocks.add(new ToolCallBlock(emitted.call(), emitted.signature()));
          }
          case ModelEvent.Stopped stopped -> {
            flush(blocks, prose);
            result = replied(blocks, stopped.reason(), stopped.usage());
          }
          case ModelEvent.Refused refused ->
              result =
                  new ModelResult.Refused(
                      refused.category(), refused.explanation(), refused.usage());
        }
      }
    }
    if (result == null) {
      // A stream that ended without saying why. Treated as a finished answer rather than an
      // exception: whatever arrived is real, and losing it because the closing event went missing
      // would be worse than reporting it as complete.
      flush(blocks, prose);
      result = replied(blocks, StopReason.END_TURN, new Usage(0, 0));
    }
    return result;
  }

  private static ModelResult replied(
      List<AssistantContentBlock> blocks, StopReason reason, Usage usage) {
    return new ModelResult.Replied(new AssistantMessage(List.copyOf(blocks)), reason, usage);
  }

  /** Reasoning left unsigned is dropped: a thinking block without its signature fails on replay. */
  private static void flush(List<AssistantContentBlock> blocks, StringBuilder prose) {
    if (!prose.isEmpty()) {
      blocks.add(new TextBlock(prose.toString()));
      prose.setLength(0);
    }
  }
}
