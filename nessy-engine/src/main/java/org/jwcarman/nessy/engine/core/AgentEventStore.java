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
package org.jwcarman.nessy.engine.core;

import java.util.List;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.inference.Seq;

/**
 * Where an agent's facts live, in order, append-only.
 *
 * <p>The one seam both harnesses need. A direct harness uses nothing else; a queued harness adds a
 * backlog and an outbox of its own and writes all three in one unit of work.
 *
 * <p><b>The watermark is where replay starts</b>, not where the stream does. It moves to a turn
 * boundary as turns close, so reconstitution costs one turn's events rather than a history --
 * bounded by the turn policy rather than by how long the agent has lived.
 *
 * <p><b>TODO -- this belongs in {@code nessy-spi}</b>, beside {@code PayloadStore}. It cannot go
 * there yet: it is typed on {@link AgentEvent}, which carries a {@code Failure}, which lives in the
 * SPI -- so the move waits on {@code Failure} being lifted to {@code nessy-api}, which the design
 * record already has planned for other reasons.
 */
public interface AgentEventStore {

  /**
   * Appends, if nothing else has.
   *
   * @param expectedLast the seq the caller believes is last. An append that finds otherwise must
   *     fail rather than write: the caller decided against a state that no longer holds, and its
   *     recourse is to reconstitute and decide again. Vacuous for a direct harness, load-bearing
   *     for a queued one, and the same seam serves both.
   */
  void append(AgentId agent, List<AgentEvent> events, Seq expectedLast);

  /** Everything after the watermark, in order. */
  List<AgentEvent> readFrom(AgentId agent, Seq watermark);

  /** Where replay starts for this agent. {@link Seq#NONE} for a agent with no history. */
  Seq watermark(AgentId agent);

  /** Moves the watermark, which a harness does when a turn closes. */
  void watermark(AgentId agent, Seq at);

  /** Raised when {@code expectedLast} did not hold. */
  final class Conflict extends RuntimeException {
    public Conflict(String message) {
      super(message);
    }
  }
}
