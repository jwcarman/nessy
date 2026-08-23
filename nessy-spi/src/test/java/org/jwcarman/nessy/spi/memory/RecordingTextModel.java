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
package org.jwcarman.nessy.spi.memory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * A minimal scripted {@link Model} for {@code nessy-core}'s own tests: replies with one text chunk
 * per call, drawn from a fixed queue (the last reply repeats once the queue runs dry, so a test
 * exercising more calls than it bothered to script still gets a deterministic answer), and counts
 * how many calls it received — the counting is the point for a below-threshold-makes-no-call
 * assertion.
 *
 * <p>{@code nessy-testing}'s {@code ScriptedModel} is the richer, multi-event-per-turn cousin of
 * this one; {@code nessy-core} cannot depend on {@code nessy-testing} (the dependency runs the
 * other way), so this is the small, single-purpose double this module's own tests need.
 */
final class RecordingTextModel implements Model {

  private final Deque<String> replies;
  private final List<ModelRequest> requests = new ArrayList<>();
  private String lastReply = "";

  RecordingTextModel(String... replies) {
    this.replies = new ArrayDeque<>(List.of(replies));
  }

  @Override
  public ModelStream stream(ModelRequest request) {
    requests.add(request);
    if (!replies.isEmpty()) {
      lastReply = replies.poll();
    }
    List<ModelEvent> events =
        List.of(
            new ModelEvent.TextChunk(lastReply),
            new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
    Iterator<ModelEvent> scriptedEvents = events.iterator();
    return new ModelStream() {

      @Override
      public Iterator<ModelEvent> iterator() {
        return scriptedEvents;
      }

      @Override
      public void close() {
        // nothing to release
      }
    };
  }

  @Override
  public Set<Capability> capabilities() {
    return Set.of();
  }

  @Override
  public String id() {
    return "recording-text";
  }

  int callCount() {
    return requests.size();
  }

  List<ModelRequest> requests() {
    return List.copyOf(requests);
  }
}
