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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Trajectory;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.api.TurnStats;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.turn.AgentTurn;
import org.jwcarman.nessy.backend.turn.AgentTurns;
import org.jwcarman.nessy.engine.core.TurnTrajectory.CallOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a turn's row the moment the turn ends, from the state the fold held just before.
 *
 * <p>Every event that ends a turn is accepted by {@link AgentState.Inferring} and leaves {@link
 * AgentState.Idle} behind, which carries nothing. So the summary is taken from the inferring state
 * the ending event is applied to, plus the event itself, and that is the only moment it can be. The
 * harness calls this after the append and inside the same locked transaction, so the row and the
 * ending commit together or not at all.
 *
 * <p><b>The tally is moved on by the ending event here.</b> The fold's own transition to idle does
 * not count the final call (idle has no tally to count it into), and a row that said a turn made
 * one call fewer than it did would be wrong for every turn. The retry count comes from the
 * trajectory state rather than from {@link TurnStats#failedAttempts()}, because that figure also
 * counts the failure that ends a turn, and an ending is not a retry.
 *
 * <p><b>The span tags are best-effort.</b> They are written to whatever observation is current when
 * the ending is folded, before the transaction commits. If the step later rolls back, a span can
 * carry a hash for an ending that did not commit, and the queued door's retry can then tag a second
 * span. The row is the record of truth. When a person's reply settles a turn's last call and the
 * policy stops the turn, the current observation is the replier's own, so the tags land there.
 */
public final class TurnRecorder {

  private static final Logger log = LoggerFactory.getLogger(TurnRecorder.class);

  static final String HASH = "nessy.trajectory.hash";
  static final String VERSION_KEY = "nessy.trajectory.version";
  static final String OUTCOME = "nessy.turn.outcome";
  static final String ROUNDS = "nessy.turn.rounds";
  static final String TOOL_CALLS = "nessy.turn.tool_calls";
  static final String TOOL_FAILURES = "nessy.turn.tool_failures";
  static final String TOOL_DENIALS = "nessy.turn.tool_denials";

  private final AgentType type;
  private final AgentTurns turns;
  private final ObservationRegistry observations;

  public TurnRecorder(AgentType type, AgentTurns turns, ObservationRegistry observations) {
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.turns = Objects.requireNonNull(turns, "turns must not be null");
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
  }

  /**
   * Folds {@code events} onto {@code before} one at a time and, at the one that ends a turn, writes
   * that turn's row and tags whatever observation is in force.
   *
   * @param at when the events were written, which is when the turn ended
   * @return the row, if a turn ended in these events
   */
  public Optional<AgentTurn> recordEnding(
      AgentId agent, AgentState before, List<AgentEvent> events, Instant at) {
    AgentState state = before;
    Optional<AgentTurn> recorded = Optional.empty();
    for (AgentEvent event : events) {
      Optional<TurnOutcome> ending = TurnTrajectory.endingOf(event);
      if (ending.isPresent()) {
        if (state instanceof AgentState.Inferring inferring) {
          AgentTurn row = summarise(inferring, event, ending.get(), at);
          turns.append(type, agent, row);
          tag(row);
          recorded = Optional.of(row);
        } else {
          log.error(
              "No turn row was written for agent {} and turn ending event {}: the state before it was {}, not Inferring",
              agent,
              event.getClass().getSimpleName(),
              state.getClass().getSimpleName());
        }
      }
      state = state.apply(event);
    }
    return recorded;
  }

  static AgentTurn summarise(
      AgentState.Inferring ending, AgentEvent event, TurnOutcome outcome, Instant at) {
    TurnTrajectory.State trajectory = ending.trajectory();
    TurnStats stats = TurnTally.after(ending.stats(), event);
    Trajectory fingerprint = TurnTrajectory.fingerprint(trajectory, outcome);
    return new AgentTurn(
        ending.turn(),
        event.seq(),
        trajectory.arrivedAt(),
        stats.startedAt(),
        at,
        fingerprint,
        TurnTrajectory.json(trajectory, outcome),
        columnSafe(trajectory.label()),
        outcome,
        trajectory.completed().size(),
        trajectory.toolCalls(),
        trajectory.count(CallOutcome.SUCCESS),
        trajectory.count(CallOutcome.FAILED),
        trajectory.count(CallOutcome.DENIED),
        stats.modelCalls(),
        trajectory.retries());
  }

  /**
   * The label as the row's text column can hold it: U+0000 and an unpaired surrogate, which the
   * database refuses, become U+FFFD. Only the row's copy changes; the event keeps what was said.
   */
  private static String columnSafe(String label) {
    StringBuilder safe = new StringBuilder(label.length());
    int i = 0;
    while (i < label.length()) {
      int codePoint = label.codePointAt(i);
      int width = Character.charCount(codePoint);
      boolean lone = Character.isSurrogate(label.charAt(i)) && width == 1;
      if (codePoint == 0 || lone) {
        safe.append('\uFFFD');
      } else {
        safe.appendCodePoint(codePoint);
      }
      i += width;
    }
    return safe.toString();
  }

  /**
   * High-cardinality only: a hash is one value per trajectory, and none of these may become a
   * dimension of the duration timer the direct door's observation also drives.
   */
  private void tag(AgentTurn row) {
    Observation current = observations.getCurrentObservation();
    if (current == null) {
      return;
    }
    current
        .highCardinalityKeyValue(HASH, row.trajectory().hash())
        .highCardinalityKeyValue(VERSION_KEY, Short.toString(row.trajectory().version()))
        .highCardinalityKeyValue(OUTCOME, row.outcome().name())
        .highCardinalityKeyValue(ROUNDS, Integer.toString(row.rounds()))
        .highCardinalityKeyValue(TOOL_CALLS, Integer.toString(row.toolCalls()))
        .highCardinalityKeyValue(TOOL_FAILURES, Integer.toString(row.toolFailures()))
        .highCardinalityKeyValue(TOOL_DENIALS, Integer.toString(row.toolDenials()));
  }
}
