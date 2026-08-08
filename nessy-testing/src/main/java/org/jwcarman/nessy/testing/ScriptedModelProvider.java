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
package org.jwcarman.nessy.testing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.jwcarman.nessy.core.StopReason;
import org.jwcarman.nessy.core.ToolCall;
import org.jwcarman.nessy.model.Capability;
import org.jwcarman.nessy.model.ModelEvent;
import org.jwcarman.nessy.model.ModelProvider;
import org.jwcarman.nessy.model.ModelRequest;
import org.jwcarman.nessy.model.ModelStream;

/**
 * A model that says exactly what you told it to.
 *
 * <p>This is how the whole loop gets tested without a key, a network, or a nondeterministic remote
 * service that charges per call. It also records every request it received, so tests can assert on
 * what the harness <em>sent</em>, which is usually the more interesting half.
 */
public final class ScriptedModelProvider implements ModelProvider {

  private final List<List<ModelEvent>> turns;
  private final List<ModelRequest> requests = new ArrayList<>();
  private int nextTurn;

  private ScriptedModelProvider(List<List<ModelEvent>> turns) {
    this.turns = List.copyOf(turns);
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public ModelStream stream(ModelRequest request) {
    if (nextTurn >= turns.size()) {
      throw new IllegalStateException(
          "script exhausted: the harness asked for turn " + (nextTurn + 1) + " of " + turns.size());
    }
    requests.add(request);
    Iterator<ModelEvent> events = turns.get(nextTurn++).iterator();
    return new ModelStream() {

      private boolean iterated;

      @Override
      public Iterator<ModelEvent> iterator() {
        // A second pass over an already-advanced iterator would silently look
        // like an empty turn. This module exists to fail loudly instead.
        if (iterated) {
          throw new IllegalStateException(
              "this ModelStream has already been iterated; a stream replays one turn exactly once");
        }
        iterated = true;
        return events;
      }

      @Override
      public void close() {
        // Nothing to release: the script is already in memory.
      }
    };
  }

  @Override
  public Set<Capability> capabilities() {
    return Set.of();
  }

  /** A snapshot of every request this provider was handed, oldest first. */
  public List<ModelRequest> requests() {
    return List.copyOf(requests);
  }

  public static final class Builder {

    private final List<List<ModelEvent>> turns = new ArrayList<>();
    private List<ModelEvent> current = new ArrayList<>();

    public Builder text(String text) {
      current.add(new ModelEvent.TextChunk(text));
      return this;
    }

    public Builder toolUse(String id, String name, ObjectNode arguments) {
      current.add(new ModelEvent.ToolUseEmitted(new ToolCall(id, name, arguments)));
      return this;
    }

    public Builder endTurn() {
      return end(StopReason.END_TURN);
    }

    public Builder endWithToolUse() {
      return end(StopReason.TOOL_USE);
    }

    private Builder end(StopReason reason) {
      current.add(new ModelEvent.TurnEnded(reason));
      turns.add(List.copyOf(current));
      current = new ArrayList<>();
      return this;
    }

    public ScriptedModelProvider build() {
      if (!current.isEmpty()) {
        throw new IllegalStateException(
            "last turn was never ended: call endTurn() or endWithToolUse()");
      }
      return new ScriptedModelProvider(turns);
    }
  }
}
