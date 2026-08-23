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
package org.jwcarman.nessy.spi.reflection;

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
 * A minimal scripted {@link Model} standing in for the critic's side model, local to this test
 * package for the same reason {@code RecordingTextModel} is local to {@code spi.memory}'s tests:
 * {@code nessy-core} cannot depend on {@code nessy-testing}'s richer {@code ScriptedModel} (the
 * dependency runs the other way). Scripts one reply per call — a text response, or a {@link
 * RuntimeException} thrown instead of replying at all, for the never-throw test.
 */
final class ScriptedCriticModel implements Model {

  private final Deque<Object> script = new ArrayDeque<>();
  private final List<ModelRequest> requests = new ArrayList<>();

  ScriptedCriticModel reply(String text) {
    script.add(text);
    return this;
  }

  ScriptedCriticModel throwing(RuntimeException failure) {
    script.add(failure);
    return this;
  }

  @Override
  public ModelStream stream(ModelRequest request) {
    requests.add(request);
    Object next = script.isEmpty() ? "" : script.poll();
    if (next instanceof RuntimeException failure) {
      throw failure;
    }
    List<ModelEvent> events =
        List.of(
            new ModelEvent.TextChunk((String) next),
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
    return "scripted-critic";
  }

  int callCount() {
    return requests.size();
  }

  List<ModelRequest> requests() {
    return List.copyOf(requests);
  }
}
