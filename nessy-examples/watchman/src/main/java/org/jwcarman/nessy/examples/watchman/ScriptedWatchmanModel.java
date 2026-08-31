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
package org.jwcarman.nessy.examples.watchman;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.jwcarman.nessy.api.message.ContextMessage;
import org.jwcarman.nessy.api.message.ExchangeMessage;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * A watchman round with no tokens and no network. Deterministic on the context it is given.
 *
 * <p>Streams, because that is the only thing a {@link Model} does now — so the application
 * exercises the same fold in a scripted run as it does against a real provider, rather than a
 * shortcut that skips it.
 */
public final class ScriptedWatchmanModel implements Model {

  private static final ModelId ID = ModelId.of("scripted-watchman");

  // Plausible, non-zero on purpose: a script that always reported zero would let token accounting
  // go missing without any test noticing.
  private static final Usage USAGE = new Usage(606, 142);

  private final Duration latency;

  public ScriptedWatchmanModel(Duration latency) {
    this.latency = latency;
  }

  @Override
  public ModelId id() {
    return ID;
  }

  @Override
  public ModelStream stream(ModelRequest request) {
    sleep(latency);
    List<ModelEvent> events = new ArrayList<>();
    if (answeredAlready(request)) {
      events.add(new ModelEvent.TextChunk("Rounds complete. Nothing needs your attention."));
      events.add(new ModelEvent.Stopped(StopReason.END_TURN, USAGE));
    } else {
      events.add(new ModelEvent.TextChunk("Checking the disks."));
      events.add(
          new ModelEvent.ToolCallEmitted(
              new ToolCall("call-1", "disk_usage", JsonNodeFactory.instance.objectNode())));
      events.add(new ModelEvent.Stopped(StopReason.TOOL_USE, USAGE));
    }
    return new ModelStream() {
      @Override
      public Iterator<ModelEvent> iterator() {
        return events.iterator();
      }

      @Override
      public void close() {
        // Nothing to release: the script is already in memory.
      }
    };
  }

  /**
   * Whether this round has already had a tool answered — the cue to wrap up rather than call again.
   */
  private static boolean answeredAlready(ModelRequest request) {
    List<ContextMessage> messages = request.context().messages();
    return messages.stream().anyMatch(ExchangeMessage.class::isInstance);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
