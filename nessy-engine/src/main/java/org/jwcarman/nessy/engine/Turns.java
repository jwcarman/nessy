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

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.message.UserMessage;

/**
 * How an agent asks for a turn.
 *
 * <p>A factory rather than a bag of dependencies: an agent should know how to REQUEST a turn, not
 * what one needs. Whatever builds this closes over the model, the tools, memory, and narration — so
 * adding a dependency to a turn never changes an agent's signature.
 *
 * <p><b>No type parameter.</b> The observation boundary dies at the agent: it renders to a {@link
 * UserMessage} before asking for a turn, so nothing from here down is generic.
 */
@FunctionalInterface
public interface Turns {

  /**
   * @param carried the trace context of the message that asked for this turn, captured on the
   *     agent's own thread while its receive span was still open. A turn spawns from a {@code
   *     thenRun} — after persistence commits, after that scope has closed — so capturing at the
   *     spawn would come back empty and orphan everything the turn goes on to do.
   */
  Behavior<TurnActor.Command> turn(
      AgentId agentId,
      String turnId,
      UserMessage input,
      ActorRef<NessyMessage> agent,
      java.util.Map<String, String> carried);
}
