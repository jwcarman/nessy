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
package org.jwcarman.nessy.engine;

import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.engine.agent.Input;

/**
 * Where an effect's outcome goes.
 *
 * <p>This was {@code tell(agentId, NessyMessage)} into a sharded entity -- an address, a
 * serializer, and a cluster to route it. It is now one method taking the thing the agent actually
 * reasons about, which is why {@link EffectWorker} no longer imports Pekko and why a test can watch
 * an effect's outcome with a lambda.
 */
@FunctionalInterface
public interface Dispatcher {

  /**
   * Feeds an agent something that happened. May be called from any thread.
   *
   * @param completing the obligation this outcome discharges, retired in the same transaction that
   *     records the outcome; null when the input came from outside and no obligation produced it
   */
  void dispatch(AgentId agentId, Input input, EffectId completing, String observability);

  /**
   * An input from the outside world: an observation, a person's answer, a reaper's nudge.
   *
   * <p>No obligation to discharge and no carrier to restore -- an observation starts its own trace.
   */
  default void dispatch(AgentId agentId, Input input) {
    dispatch(agentId, input, null, null);
  }
}
