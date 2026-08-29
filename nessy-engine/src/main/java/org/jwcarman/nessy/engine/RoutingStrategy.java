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

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.jwcarman.nessy.api.agent.AgentType;

/**
 * How a message reaches the one actor instance for an agent, starting it if it is not running.
 *
 * <p>That is the whole requirement: <b>exactly one instance per (agent type, agent id)</b>, and
 * deterministic routing to it. Everything else follows — nothing above this holds an agent, so a
 * handle can never go stale, and how many harnesses exist stops mattering because they all address
 * the same entity.
 *
 * <p><b>Cluster sharding is what provides it.</b> Not a map, not a claim, not a guard: Pekko
 * already solves this, cluster-wide, and it works on a single node (measured 2026-08-29 — one
 * instance created across five sends). The alternative, agents as children of a harness actor, can
 * only hold the invariant if something also guarantees one harness per type, and nothing local can
 * guarantee that across processes — Pekko will not even fail on a duplicate actor name, it silently
 * renames.
 *
 * <p>The entity key needs no new concept. One harness is one agent type, so it is the harness's own
 * {@link AgentType}.
 */
@FunctionalInterface
interface RoutingStrategy {

  /** Delivers {@code message} to {@code agentId}, starting that agent if it is not running. */
  void tell(String agentId, AgentActor.NessyMessage message);

  /**
   * The strategy this actor system calls for.
   *
   * <p>Reading {@code pekko.actor.provider} rather than probing: {@code ClusterSharding.get} throws
   * on a non-clustered system, and a check should not depend on catching an exception.
   */
  static RoutingStrategy forSystem(
      ActorContext<HarnessActor.Command> context,
      AgentType agentType,
      AgentActor.Dependencies deps) {
    return isClustered(context.getSystem())
        ? sharded(context.getSystem(), agentType, deps)
        : local(context, deps);
  }

  /** Whether this system can host sharded entities. */
  static boolean isClustered(ActorSystem<?> system) {
    return "cluster".equals(system.settings().config().getString("pekko.actor.provider"));
  }

  /**
   * RoutingStrategy as children of the harness actor, found by name.
   *
   * <p><b>No map.</b> {@code getChild} IS Pekko's registry of children; a second one beside it was
   * only ever needed because that call returns an untyped ref, which {@code unsafeUpcast} answers.
   * The upcast is safe by construction — this actor spawned the child itself, with a behavior of
   * exactly this type.
   */
  static RoutingStrategy local(
      ActorContext<HarnessActor.Command> context, AgentActor.Dependencies deps) {
    return (agentId, message) -> {
      String name = "agent-" + agentId.replaceAll("[^A-Za-z0-9-]", "_");
      context
          .getChild(name)
          .map(child -> child.<AgentActor.NessyMessage>unsafeUpcast())
          .orElseGet(
              () ->
                  context.spawn(
                      AgentActor.create(
                          agentId, deps, (stoppingId, self) -> self.tell(AgentActor.STOP)),
                      name))
          .tell(message);
    };
  }

  /** The entity key for one agent type. */
  static EntityTypeKey<AgentActor.NessyMessage> keyFor(AgentType agentType) {
    return EntityTypeKey.create(AgentActor.NessyMessage.class, agentType.name());
  }

  /**
   * Registers this agent type with sharding and returns the door to its entities.
   *
   * <p>{@code init} is what makes this node able to host entities of the type; {@code entityRefFor}
   * then addresses one by id from anywhere in the cluster, whether or not it currently exists here.
   */
  static RoutingStrategy sharded(
      ActorSystem<?> system, AgentType agentType, AgentActor.Dependencies deps) {
    EntityTypeKey<AgentActor.NessyMessage> key = keyFor(agentType);
    ClusterSharding sharding = ClusterSharding.get(system);
    sharding.init(
        Entity.of(
            key,
            entityContext ->
                AgentActor.create(
                    entityContext.getEntityId(),
                    deps,
                    (stoppingId, self) -> self.tell(AgentActor.STOP))));
    return (agentId, message) -> sharding.entityRefFor(key, agentId).tell(message);
  }
}
