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
import org.apache.pekko.actor.typed.javadsl.ActorContext;

/**
 * How a message reaches the agent named by an id.
 *
 * <p>One method, because nothing above it ever holds an agent — every operation is {@code (agent
 * id, message)}. That is exactly what makes local and clustered interchangeable: a handle would
 * claim you are holding the agent when all you have is a name you can send to, and under sharding
 * the entity may move between two calls. An address is not an identity (composition spec §8).
 *
 * <p>Neither implementation keeps a map. Locally the parent asks Pekko for its own child by name;
 * clustered, sharding already knows where every entity lives.
 */
@FunctionalInterface
interface Agents {

  /** Delivers {@code message} to {@code agentId}, starting that agent if it is not running. */
  void tell(String agentId, AgentActor.NessyMessage message);

  /**
   * Agents as children of the harness actor, found by name.
   *
   * <p><b>No map.</b> {@code getChild} IS Pekko's registry of children; keeping a second one beside
   * it was only ever necessary because that call returns an untyped ref, and {@code unsafeUpcast}
   * answers it. The upcast is safe by construction — this actor spawned the child itself, with a
   * behavior of exactly this type. Dropping the map also drops the death-watch that existed solely
   * to remove entries from it.
   */
  static Agents local(ActorContext<HarnessActor.Command> context, AgentActor.Dependencies deps) {
    return (agentId, message) -> {
      String name = "agent-" + safe(agentId);
      ActorRef<AgentActor.NessyMessage> agent =
          context
              .getChild(name)
              .map(ActorRef::<AgentActor.NessyMessage>unsafeUpcast)
              .orElseGet(
                  () ->
                      context.spawn(
                          AgentActor.create(
                              agentId, deps, (stoppingId, self) -> self.tell(AgentActor.STOP)),
                          name));
      agent.tell(message);
    };
  }

  /** An actor name Pekko will accept, from an agent id a caller chose. */
  private static String safe(String agentId) {
    return agentId.replaceAll("[^A-Za-z0-9-]", "_");
  }
}
