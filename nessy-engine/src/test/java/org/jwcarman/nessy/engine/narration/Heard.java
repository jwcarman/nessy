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
package org.jwcarman.nessy.engine.narration;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.NarrationListener;

/** Everything a listener was told, in the order it was told, with who it was about. */
public final class Heard implements NarrationListener {

  /** One thing heard: the envelope's agent and event, and the position if the event had one. */
  public record Line(AgentId agent, Narration event, Optional<Narrated.Position> position) {

    /** The narration's own name, which is what a test reads the story by. */
    public String kind() {
      return event.getClass().getSimpleName();
    }
  }

  private final List<Line> lines = new CopyOnWriteArrayList<>();

  @Override
  public void on(Narrated narrated) {
    lines.add(new Line(narrated.agentId(), narrated.event(), narrated.position()));
  }

  /** What was heard about one agent, by name, oldest first. */
  public List<String> kindsFor(AgentId agent) {
    return forAgent(agent).map(Line::kind).toList();
  }

  public Stream<Line> forAgent(AgentId agent) {
    return lines.stream().filter(line -> line.agent().equals(agent));
  }
}
