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
package org.jwcarman.nessy.engine.work;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStatus;
import org.jwcarman.nessy.api.AgentStatus.Activity;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.LiveEffect;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.engine.core.AgentState;

/**
 * What an agent is doing, read from what is stored about it.
 *
 * <p>Every call reads again: the agent's events from its last turn's start, folded to an {@link
 * AgentState} the way a harness folds them, and then, on the queued door, its live effect rows and
 * its queue. Nothing is kept between calls, and nothing is locked or begun, so a status may be a
 * step behind an agent that is moving. The events are read first and the rows after, so a row that
 * finishes between the two reads can only make an agent look busier than it is for that one call.
 *
 * <p><b>Waiting is a fact about rows, not about events.</b> An agent awaiting actions is {@link
 * Activity#WAITING} only when it has at least one live row and every one of them is parked now. A
 * row re-claimed at its deadline still carries its parked mark until it is deleted, and {@link
 * LiveEffect#parkedNow} says that row is not waiting; an agent with such a row is working.
 */
public final class StoredAgentWork implements AgentWork {

  /** What is read besides the events: the one thing the two doors do not share. */
  private interface Rows {

    int queued(AgentType type, AgentId id);

    List<LiveEffect> live(AgentType type, AgentId id);
  }

  private final AgentEvents events;
  private final Rows rows;
  private final Clock clock;

  private StoredAgentWork(AgentEvents events, Rows rows, Clock clock) {
    this.events = Objects.requireNonNull(events, "events must not be null");
    this.rows = rows;
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /** The queued door: events, effect rows and a queue to read. */
  public static StoredAgentWork queued(QueuedBackend backend, Clock clock) {
    Objects.requireNonNull(backend, "backend must not be null");
    return new StoredAgentWork(
        backend.events(),
        new Rows() {
          @Override
          public int queued(AgentType type, AgentId id) {
            return backend.queued(type, id);
          }

          @Override
          public List<LiveEffect> live(AgentType type, AgentId id) {
            return backend.effects().liveFor(type, id);
          }
        },
        clock);
  }

  /** The direct door: events only. It has no queue and no effect rows, so it reports none. */
  public static StoredAgentWork direct(AgentEvents events, Clock clock) {
    return new StoredAgentWork(
        events,
        new Rows() {
          @Override
          public int queued(AgentType type, AgentId id) {
            return 0;
          }

          @Override
          public List<LiveEffect> live(AgentType type, AgentId id) {
            return List.of();
          }
        },
        clock);
  }

  @Override
  public AgentStatus status(AgentType type, AgentId id) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(id, "id must not be null");
    AgentState state = reconstitute(type, id);
    List<LiveEffect> live = rows.live(type, id);
    int queued = rows.queued(type, id);
    Instant now = clock.instant();
    return switch (state) {
      case AgentState.Terminal _ -> status(Activity.ENDED, queued, Optional.empty(), 0);
      case AgentState.Idle _ ->
          status(queued == 0 ? Activity.IDLE : Activity.WORKING, queued, Optional.empty(), 0);
      case AgentState.Inferring inferring ->
          status(Activity.WORKING, queued, Optional.of(inferring.turn()), 0);
      case AgentState.AwaitingActions awaiting ->
          status(
              waiting(live, now) ? Activity.WAITING : Activity.WORKING,
              queued,
              Optional.of(awaiting.turn()),
              waitingToolCalls(live, now));
    };
  }

  // Task 6 fills these two in from the parked approval rows; until then nothing is reported.
  @Override
  public List<ApprovalRequest> waitingApprovals() {
    return List.of();
  }

  // Task 6 fills this in with the one above.
  @Override
  public List<ApprovalRequest> waitingApprovals(AgentType type) {
    Objects.requireNonNull(type, "type must not be null");
    return List.of();
  }

  private static AgentStatus status(
      Activity activity, int queued, Optional<TurnId> turn, int waitingToolCalls) {
    // Task 6 passes the agent's waiting approval requests here instead of an empty list.
    return new AgentStatus(activity, queued, turn, List.of(), waitingToolCalls);
  }

  private static boolean waiting(List<LiveEffect> live, Instant now) {
    return !live.isEmpty() && live.stream().allMatch(row -> row.parkedNow(now));
  }

  private static int waitingToolCalls(List<LiveEffect> live, Instant now) {
    return (int)
        live.stream()
            .filter(row -> row.parkedNow(now))
            .filter(row -> row.effect() instanceof AgentEffect.CallTool)
            .count();
  }

  /** The agent as it stands: the last turn that started, replayed onto idle. */
  private AgentState reconstitute(AgentType type, AgentId id) {
    List<AgentEvent> lastTurn = events.sinceLastTurnStarted(type, id);
    Seq from =
        lastTurn.isEmpty() ? Seq.NONE : new Seq(Math.max(0, lastTurn.getFirst().seq().value() - 1));
    return AgentState.idle(from).applyAll(lastTurn);
  }
}
