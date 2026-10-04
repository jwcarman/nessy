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
package org.jwcarman.nessy.backend.event;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Seq;

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
 * <p>Lives in {@code nessy-backend-spi}, beside {@link org.jwcarman.nessy.backend.payload.Payloads}
 * and {@link org.jwcarman.nessy.backend.lock.Locks}: it is typed on {@link AgentEvent}, and a
 * backend cannot implement this without seeing the grammar it is asked to store.
 */
public interface AgentEvents {

  /**
   * Appends, if nothing else has.
   *
   * <p><b>An agent is a type and an id together, which is why every method here takes both.</b> An
   * id alone was enough while every id was minted fresh, but a caller names its own -- {@code
   * ask(agentId, ...)} and {@code tell(agentId, ...)} both take one -- so an application keying
   * agents off a business identifier can run two agent types over one id. Keyed by id alone, those
   * two share a single story while {@link org.jwcarman.nessy.backend.agent.Agents} keeps them
   * apart, which is one agent's history containing another's turns.
   *
   * @param expectedLast the seq the caller believes is last. An append that finds otherwise must
   *     fail rather than write: the caller decided against a state that no longer holds, and its
   *     recourse is to reconstitute and decide again. Vacuous for a direct harness, load-bearing
   *     for a queued one, and the same seam serves both.
   * @param at the instant every event of this batch is written at, by the caller's clock. A store
   *     records it as given rather than reading a clock of its own, so a story event told as it
   *     commits and the same event read back later carry the same time.
   * @throws AgentEventConflict if {@code expectedLast} is not the agent's last seq
   */
  void append(AgentType type, AgentId agent, List<AgentEvent> events, Seq expectedLast, Instant at);

  /**
   * Everything after {@code after}, in order, without holding it all at once.
   *
   * <p><b>The primitive, and the one a backend implements.</b> {@link #readFrom} is this collected,
   * so a store says how to produce events once and both shapes follow.
   *
   * <p><b>Close it.</b> The returned stream holds whatever the store needed to produce it -- a
   * result set, a cursor, a connection -- until it is closed, exactly as {@code Files.lines} does.
   * Use it in a try-with-resources; a caller that does not will leak whatever is behind it.
   *
   * <p><b>Do not do slow work per element.</b> A durable store may be holding a transaction open
   * for as long as this stream is open, and a network call inside a {@code map} would hold it
   * across the call -- which pins a pooled connection and, on PostgreSQL, holds back vacuum. Read
   * what is wanted, close the stream, then go slow.
   */
  Stream<AgentEvent> streamFrom(AgentType type, AgentId agent, Seq after);

  /**
   * Up to {@code limit} events strictly after {@code after}, oldest first, each with the instant
   * its batch was written, so a reader gets an event and its time in one read.
   *
   * <p><b>The limit is applied by the store, in its query,</b> never by the caller after the fact:
   * a driver may materialise a whole result before returning the first row, so a limit applied
   * later would still read the whole story.
   *
   * @throws IllegalArgumentException if {@code limit} is not positive
   */
  List<Written> readWrittenFrom(AgentType type, AgentId agent, Seq after, int limit);

  /**
   * An event with the {@code at} its batch was appended with.
   *
   * @param event what was stored
   * @param at when its batch was written, as {@link #writtenAt} would answer
   */
  record Written(AgentEvent event, Instant at) {}

  /** The whole story, streamed. Close it; see {@link #streamFrom}. */
  default Stream<AgentEvent> streamAll(AgentType type, AgentId agent) {
    return streamFrom(type, agent, Seq.NONE);
  }

  /**
   * Everything after {@code after}, in order, as a list.
   *
   * <p>{@link #streamFrom} collected and closed. Convenient, and the right choice whenever the
   * answer is small -- one turn, one lookup. For a whole story of unknown length, prefer the stream
   * and stop when the answer is found.
   */
  default List<AgentEvent> readFrom(AgentType type, AgentId agent, Seq after) {
    try (Stream<AgentEvent> events = streamFrom(type, agent, after)) {
      return events.toList();
    }
  }

  /** The whole story, as a list. See {@link #readFrom} for when that is the wrong shape. */
  default List<AgentEvent> readAll(AgentType type, AgentId agent) {
    return readFrom(type, agent, Seq.NONE);
  }

  /**
   * The last turn that started, and everything after it.
   *
   * <p>What a harness replays onto its state's idle fold to find out where an agent is, and the
   * answer is whatever state comes back: a turn that ended leaves it idle, one that did not leaves
   * it where it stopped, and an agent that was ended comes back terminated. Nothing here has to
   * know which of those happened -- that is the fold's job, and asking it is the whole of this
   * method's purpose.
   *
   * <p>Empty for an agent nothing has happened to.
   */
  List<AgentEvent> sinceLastTurnStarted(AgentType type, AgentId agent);

  /**
   * When the event at {@code seq} was written -- the {@code at} its batch was appended with, not
   * the event's.
   *
   * <p>This is the one thing an {@link AgentEvent} does not carry: putting a timestamp on the
   * record itself would make replay depend on wall-clock time and would change the stored payload,
   * so the fold never sees a clock. The row that filed the event knows when that happened; the
   * event is only the fact.
   *
   * <p>What this is for: lazy recovery measures a deadline from when a phase actually started, not
   * from now, and the seq it started at is already in hand -- {@code Inferring.seq()}, or an {@code
   * OutstandingAction.since()} -- so this is the lookup that turns that seq into a clock start.
   *
   * <p>The caller only ever asks about a seq that came from an event it already replayed, so a
   * missing row is a programming error, not a condition to signal through the return type -- hence
   * a thrown exception rather than an {@code Optional}.
   *
   * @throws IllegalArgumentException if this agent has no event at {@code seq}
   */
  Instant writtenAt(AgentType type, AgentId agent, Seq seq);
}
