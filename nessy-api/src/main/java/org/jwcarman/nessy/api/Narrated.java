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
package org.jwcarman.nessy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What a listener is handed: whose event it is, the event, and for a story event where it sits in
 * the agent's story.
 *
 * <p><b>The position is the event's place, and only a story event has one.</b> A {@link
 * Narration.Story} event was stored, so it has a {@link Seq} and a time written, and a listener
 * that reads the story can match what it hears to what it reads. A {@link Narration.Live} signal
 * was never stored and has no place to be given. The same story event carries the same position
 * whether it is heard live or read back later.
 *
 * @param agentType what kind of agent -- a shared console shows several side by side, and an id
 *     alone does not say which is which
 * @param agentId which agent
 * @param event what happened
 * @param position where a story event sits in the story; empty for a live signal
 */
public record Narrated(
    AgentType agentType, AgentId agentId, Narration event, Optional<Position> position) {

  /**
   * Where a story event sits.
   *
   * @param seq the event's place in the agent's story
   * @param at when the event was written, by the engine's clock
   */
  public record Position(Seq seq, Instant at) {

    public Position {
      Objects.requireNonNull(seq, "seq must not be null");
      Objects.requireNonNull(at, "at must not be null");
    }
  }

  public Narrated {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(event, "event must not be null");
    Objects.requireNonNull(position, "position must not be null");
    if (event instanceof Narration.Story != position.isPresent()) {
      throw new IllegalArgumentException("a story event has a position, and only a story event");
    }
  }

  /** A live signal, which has no position. */
  public static Narrated live(AgentType agentType, AgentId agentId, Narration.Live event) {
    return new Narrated(agentType, agentId, event, Optional.empty());
  }

  /** A story event, at its place in the story. */
  public static Narrated story(
      AgentType agentType, AgentId agentId, Narration.Story event, Seq seq, Instant at) {
    return new Narrated(agentType, agentId, event, Optional.of(new Position(seq, at)));
  }
}
