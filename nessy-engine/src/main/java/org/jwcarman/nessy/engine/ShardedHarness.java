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

import java.util.Objects;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentSubscriber;
import org.jwcarman.nessy.api.AgentSubscription;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;

/**
 * The application's whole surface onto one kind of agent.
 *
 * <p><b>No actor of its own, and no state.</b> Every operation is an agent id and a message, so
 * there is nothing here to go stale — no registry, no cache, no map from id to address. Sharding
 * owns that, including moving an entity between nodes without anyone left holding a dead reference,
 * which is the whole reason this engine is always clustered.
 *
 * <p>There is no {@code shutdown}: entities belong to the cluster, not to whoever asked for a
 * harness.
 *
 * @param <O> the observation type
 */
final class ShardedHarness<O> implements Harness<O> {

  /** Closes a bridge. Private, so nothing else can pretend to be an event. */
  private record Close() {}

  private final AgentType type;
  private final EntityTypeKey<NessyMessage> agents;
  private final EntityTypeKey<NarrationActor.Command> narration;
  private final BacklogStore<O> backlog;
  private final ClusterSharding sharding;
  private final ActorSystem<?> system;
  private final Traces traces;

  ShardedHarness(
      AgentType type,
      EntityTypeKey<NessyMessage> agents,
      EntityTypeKey<NarrationActor.Command> narration,
      BacklogStore<O> backlog,
      ActorSystem<?> system,
      Traces traces) {
    this.type = type;
    this.agents = agents;
    this.narration = narration;
    this.backlog = backlog;
    this.system = system;
    this.traces = traces;
    this.sharding = ClusterSharding.get(system);
  }

  @Override
  public AgentType type() {
    return type;
  }

  @Override
  public void observe(AgentId agentId, O observation) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(observation, "observation must not be null");
    // COMMIT, then signal. Reversed, the agent could take before the row lands, find nothing, and
    // go back to sleep with work sitting in the table.
    backlog.offer(agentId, observation);
    // The trace starts wherever the caller is — a cron tick, an HTTP request, a queue consumer —
    // and this is where it crosses into the actor system. Capturing here is what makes everything
    // the resulting turn does a child of whatever caused it, instead of a root of its own.
    sharding
        .entityRefFor(agents, agentId.value())
        .tell(
            new NessyMessage.BacklogUpdated(
                traces.capture(type.name(), agentId.value(), "BacklogUpdated")));
  }

  /**
   * Tells the agent it is finished with, and returns.
   *
   * <p>No backlog write and no commit-then-signal dance: unlike an observation there is nothing
   * durable to land first. The agent's own state is where the intent is recorded, by the agent, so
   * that a restart between being told and acting still forgets.
   */
  @Override
  public void forget(AgentId agentId) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    sharding
        .entityRefFor(agents, agentId.value())
        .tell(new NessyMessage.Forget(traces.capture(type.name(), agentId.value(), "Forget")));
  }

  /**
   * Bridges a plain subscriber onto the narration entity.
   *
   * <p>A subscriber is a lambda; narration delivers to ADDRESSES, because the agent may be on
   * another node and because an address is something that can be watched. So one small actor stands
   * between them. Closing the subscription stops it, which is also how narration learns to forget
   * it — it watches everything it delivers to, so a dead bridge unsubscribes itself whether it was
   * closed politely or the process holding it went away.
   */
  @Override
  public AgentSubscription subscribe(AgentId agentId, AgentSubscriber subscriber) {
    return subscribe(agentId, subscriber, null);
  }

  @Override
  public AgentSubscription subscribe(
      AgentId agentId, AgentSubscriber subscriber, String lastEventId) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(subscriber, "subscriber must not be null");
    ActorRef<Object> bridge =
        system.systemActorOf(
            Behaviors.receive(Object.class)
                .onMessage(
                    AgentEvent.class,
                    event -> {
                      subscriber.on(event);
                      return Behaviors.same();
                    })
                .onMessage(Close.class, close -> Behaviors.stopped())
                .build(),
            "sub-" + type.name() + "-" + agentId.value() + "-" + Identifiers.next(),
            Props.empty());
    EntityRef<NarrationActor.Command> narrator = narrationFor(agentId);
    narrator.tell(new NarrationActor.Subscribe(bridge.narrow(), lastEventId));
    return () -> {
      narrator.tell(new NarrationActor.Unsubscribe(bridge.narrow()));
      bridge.tell(new Close());
    };
  }

  private EntityRef<NarrationActor.Command> narrationFor(AgentId agentId) {
    return sharding.entityRefFor(narration, agentId.value());
  }
}
