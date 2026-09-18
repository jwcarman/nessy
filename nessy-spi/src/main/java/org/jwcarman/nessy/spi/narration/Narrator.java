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
package org.jwcarman.nessy.spi.narration;

import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * Where an agent says what it is doing.
 *
 * <p>One method and a sink behind it: a console printing a CLI session, a journal an operator
 * queries, a stream pushed to a browser, or nothing at all. The engine promises delivery to this
 * and nothing beyond it.
 *
 * <p><b>Never durable and never transactional with the fold.</b> Events are announced after the
 * transaction that made them true, so a crash between the two loses the announcement and keeps the
 * fact. That is the right way round: losing a narration costs a watcher a line, while storing one
 * costs a row per token for content the entry following it makes redundant. A sink that wants
 * durability -- a journal -- provides it itself, which is the whole point of this being a seam.
 *
 * <p><b>Best-effort, so a broken watcher cannot break an agent.</b> Anything thrown here is caught
 * and logged by the engine. Narration must never be able to fail a turn, and a sink author should
 * not have to be careful to make that true.
 *
 * <p><b>At-least-once, and not in any global order.</b> A retried inference narrates its deltas
 * twice; two agents narrate concurrently on different threads. Events for one agent are in order
 * relative to each other, which is the only ordering a watcher can use.
 *
 * <p>One per factory rather than one per agent type, because identity travels beside the event: a
 * sink that wants to treat agent types differently switches on {@code agentType}, which is
 * something a sink can do and a capability could not.
 */
@FunctionalInterface
public interface Narrator {

  /**
   * @param agentType what kind of agent is speaking -- a shared console shows several side by side,
   *     and an id alone does not say which is which
   * @param agentId which agent
   * @param event what happened
   */
  void narrate(AgentType agentType, AgentId agentId, AgentEvent event);

  /** Nobody is listening, and nothing is lost by saying so. The default. */
  static Narrator silent() {
    return (_, _, _) -> {};
  }

  /**
   * This narrator, bound to one agent.
   *
   * <p>What anything that must not learn which agent it is serving is given -- a provider, most of
   * all, since {@code InferenceRequest} deliberately carries no identity and handing one back
   * through the narrator would undo that. The bound form can say what happened and cannot say who
   * it happened to.
   */
  default AgentNarrator forAgent(AgentType agentType, AgentId agentId) {
    return event -> narrate(agentType, agentId, event);
  }
}
