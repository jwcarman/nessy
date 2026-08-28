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
package org.jwcarman.nessy.examples.watchman.pekko;

import java.util.Objects;

/**
 * Everything an agent persists: identifiers, status, human decisions, and the observations waiting
 * for a turn. NEVER content — tool results live in Memory and tool arguments live in Claims. With
 * the transcript out, this document measured 16 bytes idle and stayed flat across 100+ revisions;
 * that property is the reason this record holds what it holds.
 *
 * @param turnId names the turn in flight, and owns that turn's claims. Null when idle.
 */
public record AgentState(String turnId, Phase phase) {

  public AgentState {
    Objects.requireNonNull(phase, "phase must not be null");
  }

  public static AgentState idle() {
    return new AgentState(null, new Phase.Idle());
  }

  public AgentState withPhase(Phase next) {
    return new AgentState(turnId, next);
  }

  /** Names the turn about to run. Its claims are deleted under this id when the turn ends. */
  public AgentState startingTurn(String newTurnId) {
    return new AgentState(newTurnId, phase);
  }

  /** Back to rest: no turn, no claims owed. */
  public AgentState finishedTurn() {
    return new AgentState(null, new Phase.Idle());
  }
}
