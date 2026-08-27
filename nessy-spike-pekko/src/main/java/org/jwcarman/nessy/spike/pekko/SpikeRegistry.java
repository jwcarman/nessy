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
package org.jwcarman.nessy.spike.pekko;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * THROWAWAY SPIKE. Cluster Sharding, minus the cluster. About forty lines.
 *
 * <p>Sharding solves two problems: routing an id to an actor somewhere in a cluster, and
 * guaranteeing that at most ONE actor exists for that id cluster-wide. On a single node the first
 * problem does not exist and the second is free — so this is what is left.
 *
 * <p><b>Single-writer, and why it is genuinely safe here.</b> The map below is touched only by this
 * actor, and an actor processes one message at a time, so "check whether the child exists, and
 * spawn it if not" is atomic without a lock. The child name is derived from the agent id and Pekko
 * refuses duplicate child names, so a second instance is impossible even if this code were wrong.
 * That is the same guarantee sharding gives, obtained from the actor model itself.
 *
 * <p>Takes {@code (agentId, command)} envelopes deliberately — the same calling convention as
 * {@code ShardingEnvelope}, so {@link SpikeAgents} can sit over either without adaptation.
 *
 * <p>{@code watch} plus {@code Terminated} is what makes the registry self-healing: when a child
 * stops — passivated by {@link AgentActor.Rest}, or dead from a failure — its entry is dropped and
 * the next message for that id spawns a fresh one that recovers its state from the store.
 */
public final class SpikeRegistry {

  public sealed interface Command {}

  /** The envelope: an agent id and something to tell it. */
  public record Envelope(String agentId, AgentActor.Command command) implements Command {}

  /** Ask a live agent to stop; a no-op if it is not in memory. */
  public record Retire(String agentId) implements Command {}

  /** A child stopped. Private to the registry. */
  private record ChildStopped(String agentId) implements Command {}

  private SpikeRegistry() {}

  public static Behavior<Command> create(
      SpikeToolbox toolbox,
      ActorRef<SpikeModelDesk.Command> modelDesk,
      ActorRef<SpikeToolWorker.RunTool> tools,
      Duration approvalTerm) {
    return Behaviors.setup(
        context -> {
          Map<String, ActorRef<AgentActor.Command>> agents = new HashMap<>();
          return Behaviors.receive(Command.class)
              .onMessage(
                  Envelope.class,
                  envelope -> {
                    agentFor(
                            context,
                            agents,
                            envelope.agentId(),
                            toolbox,
                            modelDesk,
                            tools,
                            approvalTerm)
                        .tell(envelope.command());
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
                  ChildStopped.class,
                  stopped -> {
                    agents.remove(stopped.agentId());
                    return Behaviors.same();
                  })
              .build();
        });
  }

  private static ActorRef<AgentActor.Command> agentFor(
      ActorContext<Command> context,
      Map<String, ActorRef<AgentActor.Command>> agents,
      String agentId,
      SpikeToolbox toolbox,
      ActorRef<SpikeModelDesk.Command> modelDesk,
      ActorRef<SpikeToolWorker.RunTool> tools,
      Duration approvalTerm) {
    return agents.computeIfAbsent(
        agentId,
        id -> {
          // Passivation, registry-flavoured: the agent asks to be retired, we stop it, Terminated
          // cleans the map. Exactly the shape sharding's Passivate/stopMessage handshake has.
          AgentActor.StopRequest stopRequest =
              (stoppingId, self) -> context.getSelf().tell(new Retire(stoppingId));
          ActorRef<AgentActor.Command> agent =
              context.spawn(
                  AgentActor.create(id, toolbox, modelDesk, tools, approvalTerm, stopRequest),
                  nameFor(id));
          context.watchWith(agent, new ChildStopped(id));
          return agent;
        });
  }

  private static String nameFor(String agentId) {
    return "agent-" + agentId.replaceAll("[^A-Za-z0-9-]", "_");
  }
}
