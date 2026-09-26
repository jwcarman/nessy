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

import java.time.Instant;
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
 * <p><b>TODO -- this belongs in {@code nessy-spi}</b>, beside {@code Payloads}. It cannot go there
 * yet: it is typed on {@link AgentEvent}, which carries a {@code Failure}, which lives in the SPI
 * -- so the move waits on {@code Failure} being lifted to {@code nessy-api}, which the design
 * record already has planned for other reasons.
 */
public interface AgentEvents {

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

  /**
   * When the event at {@code seq} was written -- the database's clock, not the event's.
   *
   * <p>This is the one thing an {@link AgentEvent} does not carry: putting a timestamp on the
   * record itself would make replay depend on wall-clock time and would change the stored payload,
   * so the fold never sees a clock. The row that filed the event knows when that happened; the
   * event is only the fact.
   *
   * <p>What this is for: lazy recovery measures a deadline from when a phase actually started, not
   * from now, and the seq it started at is already in hand -- {@code Inferring.seq()}, or an {@code
   * Outstanding.since()} -- so this is the lookup that turns that seq into a clock start.
   *
   * <p>The caller only ever asks about a seq that came from an event it already replayed, so a
   * missing row is a programming error, not a condition to signal through the return type -- hence
   * a thrown exception rather than an {@code Optional}.
   *
   * @throws IllegalArgumentException if {@code agent} has no event at {@code seq}
   */
  Instant writtenAt(AgentId agent, Seq seq);

  /** Raised when {@code expectedLast} did not hold. */
  final class Conflict extends RuntimeException {
    public Conflict(String message) {
      super(message);
    }
  }
}
