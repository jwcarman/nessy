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

import java.util.List;

/**
 * What one input does: the state to persist, and what to do once it is durable.
 *
 * <p>Order matters, and it is the reverse of what the engine used to do. Persist, THEN instruct:
 * content is checked in before the agent is told about it, so a state referencing something must
 * find it there. The standing rule for any instruction added later is that a state referencing
 * something missing must be RECOVERABLE, not stuck.
 */
public record Decision(AgentState next, List<Instruction> then) {

  public Decision {
    then = List.copyOf(then);
  }

  /** Nothing to persist and nothing to do — the shape of a message deliberately ignored. */
  public static Decision nothing(AgentState state) {
    return new Decision(state, List.of());
  }

  public static Decision of(AgentState next, Instruction... then) {
    return new Decision(next, List.of(then));
  }
}
