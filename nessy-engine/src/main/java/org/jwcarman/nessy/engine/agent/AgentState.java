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
package org.jwcarman.nessy.engine.agent;

import java.util.Objects;

/**
 * Everything an agent persists, and no more.
 *
 * <p><b>No backlog and no observation content.</b> The backlog is a table of its own, and the
 * observation a turn is working is a claim id. So this document is a turn id, a phase and two short
 * strings no matter how much work the agent has done or how large its observations are.
 *
 * <p><b>Why {@code observation} is not cleared when a turn ends.</b> The finished claim id is
 * exactly what the next take must name so the store can sweep the right row. One field serves the
 * working turn and the sweep, and an agent that is idle holding an id has simply finished that one.
 *
 * @param turnId the backlog row this turn came from — one observation is one turn
 * @param phase what is being waited on
 * @param observation the claim id holding the rendered observation
 */
public record AgentState(String turnId, Phase phase, String observation) {

  public AgentState {
    Objects.requireNonNull(phase, "phase must not be null");
  }

  public static AgentState idle() {
    return new AgentState(null, new Phase.Idle(), null);
  }

  /** Whether a turn is running. */
  public boolean busy() {
    return !(phase instanceof Phase.Idle);
  }

  /** The tool calls in flight. Asking an agent that is not working tools is a bug, not a query. */
  public Phase.WorkingTools working() {
    if (phase instanceof Phase.WorkingTools tools) {
      return tools;
    }
    throw new IllegalStateException("not working tools: " + phase);
  }

  /** The same turn, at a new phase. */
  public AgentState at(Phase next) {
    return new AgentState(turnId, next, observation);
  }

  /** A turn begins: the backlog row's id IS the turn id, and its claim holds the input. */
  public AgentState taking(String newTurnId, String observationClaim) {
    return new AgentState(newTurnId, new Phase.CallingModel(), observationClaim);
  }

  /** The turn is over. The claim id stays, because the next take has to name it. */
  public AgentState finished() {
    return new AgentState(null, new Phase.Idle(), observation);
  }
}
