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

/**
 * Every rule about what an agent does next, and no way to do any of it.
 *
 * <p>Pure by construction: no clock, no store, no actor, no Pekko import. That is what lets a
 * three-day parked approval and a crash mid-model-call be ordinary unit tests rather than a
 * cluster, a race and a fifteen-second timeout.
 */
public final class AgentLogic {

  private AgentLogic() {}

  public static Decision decide(AgentState state, Input input) {
    return switch (input) {
      case Input.BacklogUpdated ignored -> onBacklogUpdated(state);
      case Input.WorkTaken taken -> onWorkTaken(state, taken);
      case Input.NoWork ignored -> Decision.of(state, new Instruction.Sleep());
      default -> Decision.nothing(state);
    };
  }

  /**
   * A busy agent drops this on the floor, and that is the point: going idle always ends with a
   * take, so missing the signal costs nothing when a signal-free path reaches the same place.
   */
  private static Decision onBacklogUpdated(AgentState state) {
    return state.busy() ? Decision.nothing(state) : Decision.of(state, new Instruction.TakeWork());
  }

  private static Decision onWorkTaken(AgentState state, Input.WorkTaken taken) {
    return Decision.of(
        state.taking(taken.turnId(), taken.observationClaim()),
        new Instruction.Narrate.TurnStarted(taken.turnId()),
        new Instruction.CallModel());
  }
}
