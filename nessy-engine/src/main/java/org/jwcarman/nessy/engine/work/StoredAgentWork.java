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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStatus;
import org.jwcarman.nessy.api.AgentStatus.Activity;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.LiveEffect;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.core.AgentState;
import org.jwcarman.nessy.engine.history.RequestedCalls;
import org.jwcarman.nessy.engine.tool.ToolCalls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

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

  /** The most waiting approvals one read returns, and the size of the pages it reads them in. */
  public static final int MAXIMUM_WAITING = 500;

  private static final Logger log = LoggerFactory.getLogger(StoredAgentWork.class);

  /** What is read besides the events: the one thing the two doors do not share. */
  private interface Rows {

    int queued(AgentType type, AgentId id);

    List<LiveEffect> live(AgentType type, AgentId id);

    List<LiveEffect> parkedNow(
        Optional<AgentType> type, Instant now, Optional<LiveEffect> after, int limit);

    Payloads payloads(AgentId id);
  }

  private final AgentEvents events;
  private final Rows rows;
  private final Clock clock;
  private final int maximumWaiting;

  private StoredAgentWork(AgentEvents events, Rows rows, Clock clock, int maximumWaiting) {
    this.events = Objects.requireNonNull(events, "events must not be null");
    this.rows = rows;
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.maximumWaiting = maximumWaiting;
  }

  /** The queued door: events, effect rows and a queue to read. */
  public static StoredAgentWork queued(QueuedBackend backend, Clock clock) {
    return queued(backend, clock, MAXIMUM_WAITING);
  }

  /** The queued door with a smaller maximum, so a test can reach the cap with few approvals. */
  static StoredAgentWork queued(QueuedBackend backend, Clock clock, int maximumWaiting) {
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

          @Override
          public List<LiveEffect> parkedNow(
              Optional<AgentType> type, Instant now, Optional<LiveEffect> after, int limit) {
            return backend.effects().parkedNow(type, now, after, limit);
          }

          @Override
          public Payloads payloads(AgentId id) {
            return backend.payloads().forAgent(id);
          }
        },
        clock,
        maximumWaiting);
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

          @Override
          public List<LiveEffect> parkedNow(
              Optional<AgentType> type, Instant now, Optional<LiveEffect> after, int limit) {
            return List.of();
          }

          @Override
          public Payloads payloads(AgentId id) {
            throw new IllegalStateException("the direct door has no waiting approvals to read");
          }
        },
        clock,
        MAXIMUM_WAITING);
  }

  @Override
  public AgentStatus status(AgentType type, AgentId id) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(id, "id must not be null");
    Stories stories = new Stories(type, id, events.sinceLastTurnStarted(type, id));
    AgentState state = reconstitute(stories.current(type, id));
    List<LiveEffect> live = rows.live(type, id);
    int queued = rows.queued(type, id);
    Instant now = clock.instant();
    if (state instanceof AgentState.AwaitingActions
        && live.stream()
            .filter(row -> row.parkedNow(now))
            .map(LiveEffect::effect)
            .filter(AgentEffect.Approve.class::isInstance)
            .map(AgentEffect.Approve.class::cast)
            .anyMatch(approve -> !holds(stories.current(type, id), approve))) {
      // A parked row is newer than the story: the story was read just before the step that
      // recorded it. The state is worked out again from the fresh story, so that the status is
      // built from one story and not from two.
      state = reconstitute(stories.readAgain(type, id).orElseGet(() -> stories.current(type, id)));
    }
    return switch (state) {
      case AgentState.Terminal _ ->
          status(Activity.TERMINATED, queued, Optional.empty(), List.of(), 0);
      case AgentState.Idle _ ->
          status(
              queued == 0 ? Activity.IDLE : Activity.WORKING,
              queued,
              Optional.empty(),
              List.of(),
              0);
      case AgentState.Inferring inferring ->
          status(Activity.WORKING, queued, Optional.of(inferring.turn()), List.of(), 0);
      case AgentState.AwaitingActions awaiting ->
          status(
              waiting(live, now) ? Activity.WAITING : Activity.WORKING,
              queued,
              Optional.of(awaiting.turn()),
              rebuilt(live.stream().filter(row -> row.parkedNow(now)).toList(), stories),
              waitingToolCalls(live, now));
    };
  }

  @Override
  public List<ApprovalRequest> waitingApprovals() {
    return waiting(Optional.empty());
  }

  @Override
  public List<ApprovalRequest> waitingApprovals(AgentType type) {
    Objects.requireNonNull(type, "type must not be null");
    return waiting(Optional.of(type));
  }

  private List<ApprovalRequest> waiting(Optional<AgentType> type) {
    Instant now = clock.instant();
    Stories stories = new Stories();
    List<ApprovalRequest> requests = new ArrayList<>();
    Optional<LiveEffect> after = Optional.empty();
    while (requests.size() < maximumWaiting) {
      List<LiveEffect> page = rows.parkedNow(type, now, after, maximumWaiting);
      for (LiveEffect row : page) {
        if (requests.size() < maximumWaiting) {
          rebuiltOne(row, stories).ifPresent(requests::add);
        }
      }
      if (page.size() < maximumWaiting) {
        break;
      }
      after = Optional.of(page.getLast());
    }
    return List.copyOf(requests);
  }

  /**
   * The request the approver was shown for each row, rebuilt from stored values. A row whose
   * request cannot be rebuilt is logged and left out, and never fails the read.
   */
  private List<ApprovalRequest> rebuilt(List<LiveEffect> parked, Stories stories) {
    return parked.stream().map(row -> rebuiltOne(row, stories)).flatMap(Optional::stream).toList();
  }

  /**
   * One row's request. A row parked now was parked in the step that wrote its request and its
   * deferral, so a story that lacks either was read before that step: the story is read once more
   * for that agent and the row rebuilt from the fresh read. A row the fresh read does not hold
   * either is skipped, and one whose deferral it still lacks has empty facts.
   */
  private Optional<ApprovalRequest> rebuiltOne(LiveEffect row, Stories stories) {
    if (!(row.effect() instanceof AgentEffect.Approve approve)) {
      return Optional.empty();
    }
    try {
      if (!holds(stories.of(row), approve)) {
        stories.readAgain(row.agentType(), row.agentId());
      }
      Optional<ApprovalRequest> request = rebuild(row, approve, stories.of(row));
      if (request.isEmpty()) {
        skipped(row, approve);
      }
      return request;
    } catch (RuntimeException e) {
      log.warn(
          "[{}] agent {}: waiting approval {} skipped",
          row.agentType().value(),
          row.agentId().value(),
          approve.idempotencyKey(),
          e);
      return Optional.empty();
    }
  }

  /**
   * The stories read in one call, one per agent, and at most one more read per agent when a row
   * turns out to be newer than the story.
   */
  private final class Stories {

    private final Map<String, List<AgentEvent>> read = new HashMap<>();
    private final Set<String> readAgain = new HashSet<>();

    private Stories() {}

    /** An agent whose story is already in hand, as the status read has it. */
    private Stories(AgentType type, AgentId id, List<AgentEvent> story) {
      read.put(keyOf(type, id), story);
    }

    private static String keyOf(AgentType type, AgentId id) {
      return type.value() + '\u0000' + id.value();
    }

    List<AgentEvent> of(LiveEffect row) {
      return current(row.agentType(), row.agentId());
    }

    /** The story as this call holds it: read now if this call has not read it yet. */
    List<AgentEvent> current(AgentType type, AgentId id) {
      return read.computeIfAbsent(keyOf(type, id), _ -> events.sinceLastTurnStarted(type, id));
    }

    /** The story read again, the first time that is asked for this agent in this call. */
    Optional<List<AgentEvent>> readAgain(AgentType type, AgentId id) {
      String key = keyOf(type, id);
      if (!readAgain.add(key)) {
        return Optional.empty();
      }
      List<AgentEvent> fresh = events.sinceLastTurnStarted(type, id);
      read.put(key, fresh);
      return Optional.of(fresh);
    }
  }

  /** Whether a story holds both the request an approval row is for and the row's deferral. */
  private static boolean holds(List<AgentEvent> story, AgentEffect.Approve approve) {
    return story.stream()
            .anyMatch(
                event ->
                    event instanceof AgentEvent.ActionsRequested asked
                        && asked.seq().equals(approve.requestSeq()))
        && story.stream()
            .anyMatch(
                event ->
                    event instanceof AgentEvent.ApprovalDeferred deferred
                        && deferred.idempotencyKey().equals(approve.idempotencyKey()));
  }

  private Optional<ApprovalRequest> rebuild(
      LiveEffect row, AgentEffect.Approve approve, List<AgentEvent> story) {
    IdempotencyKey key = approve.idempotencyKey();
    Optional<ToolCalls.ResolvedCall> call =
        story.stream()
            .filter(AgentEvent.ActionsRequested.class::isInstance)
            .map(AgentEvent.ActionsRequested.class::cast)
            .filter(asked -> asked.seq().equals(approve.requestSeq()))
            .findFirst()
            .flatMap(
                asked ->
                    RequestedCalls.resolve(
                        rows.payloads(row.agentId()),
                        asked,
                        entry -> entry.idempotencyKey().equals(key)));
    if (call.isEmpty()) {
      return Optional.empty();
    }
    ObjectNode facts =
        story.stream()
            .filter(AgentEvent.ApprovalDeferred.class::isInstance)
            .map(AgentEvent.ApprovalDeferred.class::cast)
            .filter(deferred -> deferred.idempotencyKey().equals(key))
            .reduce((_, later) -> later)
            .map(deferred -> deferred.facts().deepCopy())
            .orElseGet(JsonNodeFactory.instance::objectNode);
    return Optional.of(
        new ApprovalRequest(
            row.agentType(),
            row.agentId(),
            approve.turn(),
            approve.callId(),
            key,
            approve.toolName(),
            call.get().call().arguments(),
            call.get().action(),
            row.parkedAt().orElseThrow(),
            row.deadline(),
            facts));
  }

  private static void skipped(LiveEffect row, AgentEffect.Approve approve) {
    log.warn(
        "[{}] agent {}: waiting approval {} skipped: the story does not hold the request",
        row.agentType().value(),
        row.agentId().value(),
        approve.idempotencyKey());
  }

  private static AgentStatus status(
      Activity activity,
      int queued,
      Optional<TurnId> turn,
      List<ApprovalRequest> approvals,
      int waitingToolCalls) {
    return new AgentStatus(activity, queued, turn, approvals, waitingToolCalls);
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
  private static AgentState reconstitute(List<AgentEvent> lastTurn) {
    Seq from =
        lastTurn.isEmpty() ? Seq.NONE : new Seq(Math.max(0, lastTurn.getFirst().seq().value() - 1));
    return AgentState.idle(from).applyAll(lastTurn);
  }
}
