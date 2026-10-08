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
package org.jwcarman.nessy.backend.turn;

import java.time.Instant;
import java.util.List;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Trajectory;

/**
 * The completed turns of every agent, one row each, written as each turn ends.
 *
 * <p>A materialised projection of the event stream, not a second source of truth: every row can be
 * rebuilt from the turn's events. It exists so that questions about behaviour across many turns
 * (how many distinct trajectories, which are common, which are new) are a query rather than a fold
 * over every event ever written.
 *
 * <p>{@link #firstSighting} and {@link #append} are both called inside the same unit of work as the
 * append of the turn-ending event, so a committed ending always has its row and a rolled-back one
 * never does.
 */
public interface AgentTurns {

  /**
   * Records that a turn of this agent type, with this label, ended on this trajectory, and says
   * whether that had ever happened before. Called before {@link #append}, in the same unit of work,
   * so the row can carry the answer. Write-once: a repeat changes nothing.
   *
   * @param at when the turn ended; kept as the first time only on a first sighting
   * @return true if this is the first time: the turn is novel
   */
  boolean firstSighting(AgentType type, String label, Trajectory trajectory, Instant at);

  /**
   * Writes the row for a turn that has just ended.
   *
   * @throws IllegalStateException if this agent already has a row for this turn
   */
  void append(AgentType type, AgentId agent, AgentTurn turn);

  /** This agent's completed turns, oldest first. For tests and audit; analytics use SQL. */
  List<AgentTurn> of(AgentType type, AgentId agent);
}
