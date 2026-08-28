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

import java.util.HashMap;
import java.util.Map;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * Cluster Sharding, minus the cluster. One child per agent id, spawned on first message.
 *
 * <p>Single-writer is free rather than engineered: the map is touched only by this actor, an actor
 * handles one message at a time, so check-then-spawn is atomic without a lock — and Pekko refuses
 * duplicate child names, so a second instance for one id is impossible even if this code were
 * wrong.
 *
 * <p>{@code watchWith} plus {@code AgentStopped} is what makes it self-healing: when a child stops,
 * its entry is dropped and the next message for that id spawns a fresh one that recovers from the
 * store.
 */
public final class AgentRegistry {

  public sealed interface Command {}

  /** The envelope, deliberately the same shape as sharding's. */
  public record Envelope(String agentId, AgentActor.NessyMessage message) implements Command {}

  /** Ask a live agent to stop; a no-op if it is not in memory. */
  public record Retire(String agentId) implements Command {}

  private record AgentStopped(String agentId) implements Command {}

  private AgentRegistry() {}

  public static Behavior<Command> create(AgentActor.Dependencies deps) {
    return Behaviors.setup(
        context -> {
          Map<String, ActorRef<AgentActor.NessyMessage>> agents = new HashMap<>();
          return Behaviors.receive(Command.class)
              .onMessage(
                  Envelope.class,
                  envelope -> {
                    agentFor(context, agents, envelope.agentId(), deps).tell(envelope.message());
                    return Behaviors.same();
                  })
              .onMessage(
                  Retire.class,
                  retire -> {
                    var agent = agents.get(retire.agentId());
                    if (agent != null) {
                      agent.tell(AgentActor.STOP);
                    }
                    return Behaviors.same();
                  })
              .onMessage(
                  AgentStopped.class,
                  stopped -> {
                    agents.remove(stopped.agentId());
                    return Behaviors.same();
                  })
              .build();
        });
  }

  private static ActorRef<AgentActor.NessyMessage> agentFor(
      ActorContext<Command> context,
      Map<String, ActorRef<AgentActor.NessyMessage>> agents,
      String agentId,
      AgentActor.Dependencies deps) {
    return agents.computeIfAbsent(
        agentId,
        id -> {
          AgentActor.StopRequest stopRequest =
              (stoppingId, self) -> context.getSelf().tell(new Retire(stoppingId));
          ActorRef<AgentActor.NessyMessage> agent =
              context.spawn(AgentActor.create(id, deps, stopRequest), "agent-" + safe(id));
          context.watchWith(agent, new AgentStopped(id));
          return agent;
        });
  }

  private static String safe(String agentId) {
    return agentId.replaceAll("[^A-Za-z0-9-]", "_");
  }
}
