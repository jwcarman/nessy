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
import org.jwcarman.nessy.api.block.AnswerContentBlock;
import org.jwcarman.nessy.api.block.CommentaryBlock;
import org.jwcarman.nessy.api.block.ExchangeContentBlock;
import org.jwcarman.nessy.api.block.ProviderBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.message.AnswerMessage;
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
   *
   * <p>Folds a stream into one result.
   *
   * <p><b>Where text becomes commentary.</b> A chunk of prose cannot be classified as it arrives —
   * whether the model was talking on its way to a tool call or answering the question is only
   * settled when it stops. So prose is accumulated, and the stop reason decides: {@code TOOL_USE}
   * means everything it said was commentary, anything else means it was an answer. One place, no
   * vendor involved.
   *
   * <p>Reasoning text is watched and dropped: it reaches whoever is listening as it streams and is
   * never stored. What IS stored is {@code ProviderStateEmitted} — the opaque thing a provider
   * wants back — which is a different fact that happens to arrive nearby.
   */
  public static ModelResult drain(ModelStream stream, Consumer<ModelEvent> watcher) {
    List<ExchangeContentBlock> asked = new ArrayList<>();
    List<ProviderBlock> state = new ArrayList<>();
    StringBuilder prose = new StringBuilder();
    ModelResult result = null;
    try (ModelStream events = stream) {
      for (ModelEvent event : events) {
        watcher.accept(event);
        switch (event) {
          case ModelEvent.TextChunk(var text) -> prose.append(text);
          case ModelEvent.ReasoningChunk _ -> {
            // Narration only: watched above, never kept.
          }
          case ModelEvent.ProviderStateEmitted(var provider, var data) ->
              state.add(new ProviderBlock(provider, data));
          case ModelEvent.ToolCallEmitted(var call) -> {
            flushCommentary(asked, prose);
            asked.addAll(state);
            state.clear();
            asked.add(new ToolCallBlock(call));
          }
          case ModelEvent.Stopped(var reason, var usage) ->
              result = settle(asked, state, prose, reason, usage);
          case ModelEvent.Refused(var category, var explanation, var usage) ->
              result = new ModelResult.Refused(category, explanation, usage);
        }
      }
    }
    if (result == null) {
      // A stream that ended without saying why. Treated as a finished answer rather than an
      // exception: whatever arrived is real, and losing it because the closing event went missing
      // would be worse than reporting it as complete.
      result = settle(asked, state, prose, StopReason.END_TURN, Usage.unreported());
    }
    return result;
  }

  /** The one place the stop reason decides what the model was doing. */
  private static ModelResult settle(
      List<ExchangeContentBlock> asked,
      List<ProviderBlock> state,
      StringBuilder prose,
      StopReason reason,
      Usage usage) {
    if (reason == StopReason.TOOL_USE) {
      flushCommentary(asked, prose);
      asked.addAll(state);
      return new ModelResult.Asked(List.copyOf(asked), usage);
    }
    List<AnswerContentBlock> answer = new ArrayList<>(state);
    if (!prose.isEmpty()) {
      answer.add(new TextBlock(prose.toString()));
    }
    return new ModelResult.Answered(new AnswerMessage(answer), reason, usage);
  }

  /** Prose said on the way to a call is commentary; empty prose adds nothing. */
  private static void flushCommentary(List<ExchangeContentBlock> blocks, StringBuilder prose) {
    if (!prose.isEmpty()) {
      blocks.add(new CommentaryBlock(prose.toString()));
      prose.setLength(0);
    }
  }
}
