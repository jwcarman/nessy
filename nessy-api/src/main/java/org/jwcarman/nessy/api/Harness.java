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
package org.jwcarman.nessy.api;

/**
 * One agent type's door.
 *
 * <p>An interface with one method, and that is the whole design. Everything an agent type needs --
 * its codec, its renderer, its transactions, the callback its effects report through -- is behind
 * an implementation a caller cannot reach.
 *
 * <p>In particular there is no way to deliver an outcome from out here. That capability lives on
 * the engine's own callback, which the same object implements and this type does not mention: were
 * it here, anyone could fabricate an answer the model never gave and have it folded into an agent's
 * story as though it had.
 *
 * @param <O> the observation type
 */
public interface Harness<O> {

  /**
   * Tells an agent something happened.
   *
   * <p>An agent that has never been heard of comes into being here rather than through a separate
   * call: there is nothing to say about an agent before its first observation, and a create step
   * would only be a way to get that wrong.
   */
  void observe(AgentId agentId, O observation);

  /**
   * Ends an agent.
   *
   * <p>Takes effect at once if the agent is idle. One mid-turn stops accepting immediately and ends
   * when the turn it already owes an outcome for is finished -- there is no cancelling an effect
   * that has already been written down, and abandoning it would leave a row nobody will ever
   * discharge.
   *
   * <p>Nothing is written to the story. What ended is the agent, not its conversation, and the
   * model has no use for the fact.
   *
   * <p>Idempotent, and irreversible: an observation arriving afterwards is refused, whenever it
   * arrives.
   */
  void terminate(AgentId agentId);
}
