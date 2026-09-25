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
 * <p><b>Reading an agent back costs its last turn, not its history.</b> Replay begins at the last
 * turn that started, so reconstitution is bounded by the length of a turn rather than by how long
 * the agent has lived -- a conversation of a thousand turns comes back as fast as its first.
 *
 * <p>Nothing records where that is. The boundary is a fact the events already carry, and a stored
 * watermark would be a second copy of it that can disagree: written after the append, missed on a
 * crash, and wrong in a way nothing detects. Finding it costs reading backwards to the nearest
 * {@link AgentEvent.TurnStarted}, which is one turn's worth of rows.
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

  /** Everything after {@code after}, in order. {@link Seq#NONE} reads the whole story. */
  List<AgentEvent> readFrom(AgentId agent, Seq after);

  /**
   * The last turn that started, and everything after it.
   *
   * <p>What a harness replays onto {@link AgentState#idle} to find out where an agent is, and the
   * answer is whatever state comes back: a turn that ended leaves it idle, one that did not leaves
   * it where it stopped, and an agent that was ended comes back terminated. Nothing here has to
   * know which of those happened -- that is the fold's job, and asking it is the whole of this
   * method's purpose.
   *
   * <p>Empty for an agent nothing has happened to.
   */
  List<AgentEvent> sinceLastTurnStarted(AgentId agent);

  /** Raised when {@code expectedLast} did not hold. */
  final class Conflict extends RuntimeException {
    public Conflict(String message) {
      super(message);
    }
  }
}
