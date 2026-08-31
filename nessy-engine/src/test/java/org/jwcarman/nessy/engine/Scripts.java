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
package org.jwcarman.nessy.engine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.jwcarman.nessy.api.block.AnswerContentBlock;
import org.jwcarman.nessy.api.block.CommentaryBlock;
import org.jwcarman.nessy.api.block.ExchangeContentBlock;
import org.jwcarman.nessy.api.block.ProviderBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * Scripted models, said out loud.
 *
 * <p>A test wants to say "the model replies with this"; a provider streams. This turns the former
 * into the latter, so tests describe the answer and the engine still exercises the streaming path
 * it will use in production — rather than a shortcut that skips the fold entirely.
 */
final class Scripts {

  private Scripts() {}

  /** The events a provider would have emitted to produce {@code result}. */
  static ModelStream saying(ModelResult result) {
    List<ModelEvent> events = new ArrayList<>();
    switch (result) {
      case ModelResult.Answered answered -> {
        for (AnswerContentBlock block : answered.message().content()) {
          switch (block) {
            case TextBlock text -> events.add(new ModelEvent.TextChunk(text.text()));
            case ProviderBlock state ->
                events.add(new ModelEvent.ProviderStateEmitted(state.provider(), state.data()));
          }
        }
        events.add(new ModelEvent.Stopped(answered.stopReason(), answered.usage()));
      }
      case ModelResult.Asked asked -> {
        for (ExchangeContentBlock block : asked.content()) {
          switch (block) {
            case CommentaryBlock commentary ->
                events.add(new ModelEvent.TextChunk(commentary.text()));
            case ProviderBlock state ->
                events.add(new ModelEvent.ProviderStateEmitted(state.provider(), state.data()));
            case ToolCallBlock call -> events.add(new ModelEvent.ToolCallEmitted(call.call()));
          }
        }
        events.add(new ModelEvent.Stopped(StopReason.TOOL_USE, asked.usage()));
      }
      case ModelResult.Refused refused ->
          events.add(
              new ModelEvent.Refused(refused.category(), refused.explanation(), refused.usage()));
    }
    return new ModelStream() {
      @Override
      public Iterator<ModelEvent> iterator() {
        return events.iterator();
      }

      @Override
      public void close() {
        // Nothing to release.
      }
    };
  }
}
