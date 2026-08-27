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
package org.jwcarman.nessy.spike.pekko.cluster;

import com.typesafe.config.Config;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.MemberStatus;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.apache.pekko.cluster.typed.Cluster;
import org.apache.pekko.cluster.typed.Join;
import org.jwcarman.nessy.spike.pekko.AgentActor;
import org.jwcarman.nessy.spike.pekko.SpikeAgents;
import org.jwcarman.nessy.spike.pekko.SpikeBlockingWork;
import org.jwcarman.nessy.spike.pekko.SpikeModel;
import org.jwcarman.nessy.spike.pekko.SpikeRuntime;
import org.jwcarman.nessy.spike.pekko.SpikeSweep;
import org.jwcarman.nessy.spike.pekko.SpikeToolbox;
import org.jwcarman.nessy.spike.pekko.SpikeWorkers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * THROWAWAY SPIKE, TIER 2 — the upgrade path, proved available rather than adopted.
 *
 * <p><b>Read this class beside {@code LocalSpikeRuntime} and the round-3 thesis is visible in the
 * diff.</b> The behaviour is imported, not redefined: {@link AgentActor#create} is called with
 * exactly the arguments the single-node registry passes it, and the ONLY differences are
 *
 * <ul>
 *   <li>the node must form a cluster before it can do anything (the join-yourself dance below),
 *   <li>the lookup is {@code sharding.entityRefFor(...)} instead of a registry envelope, and
 *   <li>the stop request is {@code ClusterSharding.Passivate} instead of a registry retire.
 * </ul>
 *
 * <p>The agent itself never learns which of those it is living under. That is what makes the
 * cluster an upgrade path rather than an architecture: nothing has to be rewritten to take it, and
 * nothing has to be carried while we do not.
 */
public final class ShardedSpikeRuntime implements SpikeRuntime {

  private static final Logger LOG = LoggerFactory.getLogger(ShardedSpikeRuntime.class);

  public static final EntityTypeKey<AgentActor.Command> TYPE_KEY =
      EntityTypeKey.create(AgentActor.Command.class, "SpikeAgent");

  private final ActorSystem<Guardian.Command> system;
  private final SpikeBlockingWork blocking = new SpikeBlockingWork();
  private final SpikeModel model;
  private final SpikeAgents agents;
  private final Duration startup;

  public ShardedSpikeRuntime(Config config, SpikeModel model, SpikeSweep sweep) {
    long began = System.nanoTime();
    this.model = model;
    this.system =
        ActorSystem.create(
            Guardian.create(model, new SpikeToolbox(), blocking.executor()), "spike", config);

    // A single node still has to form a cluster: it joins itself. With a random remoting port
    // there is no seed-node address to write into config, so this is the only way to say it.
    Cluster cluster = Cluster.get(system);
    cluster.manager().tell(Join.create(cluster.selfMember().address()));
    awaitUp(cluster);

    ClusterSharding sharding = ClusterSharding.get(system);
    SpikeWorkers.Workers workers = askForWorkers();
    sharding.init(
        Entity.of(
                TYPE_KEY,
                entityContext ->
                    AgentActor.create(
                        entityContext.getEntityId(),
                        new SpikeToolbox(),
                        workers.modelDesk(),
                        workers.tools(),
                        Duration.ofMinutes(10),
                        // Passivation, sharding-flavoured. The registry's version is one tell.
                        (agentId, self) ->
                            entityContext.getShard().tell(new ClusterSharding.Passivate<>(self))))
            .withStopMessage(AgentActor.STOP));

    this.agents = (agentId, command) -> sharding.entityRefFor(TYPE_KEY, agentId).tell(command);
    this.startup = Duration.ofNanos(System.nanoTime() - began);
    LOG.info("[spike] sharded runtime ready in {} ms", startup.toMillis());

    // Tier 2 gets recreation from rememberEntities, so the sweep is usually a no-op here; it is
    // still honoured so the same contract can drive both tiers.
    sweep.unfinishedAgents().forEach(agentId -> agents.tell(agentId, new AgentActor.Wake()));
  }

  private SpikeWorkers.Workers askForWorkers() {
    try {
      return AskPattern.<Guardian.Command, SpikeWorkers.Workers>ask(
              system, Guardian.GetWorkers::new, Duration.ofSeconds(20), system.scheduler())
          .toCompletableFuture()
          .get(20, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted starting the runtime", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("the guardian never handed out its workers", e);
    }
  }

  private static void awaitUp(Cluster cluster) {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (cluster.selfMember().status() != MemberStatus.up()) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("the single-node cluster never came up");
      }
      Thread.onSpinWait();
    }
  }

  @Override
  public SpikeAgents agents() {
    return agents;
  }

  @Override
  public ActorSystem<?> system() {
    return system;
  }

  @Override
  public Duration startupTime() {
    return startup;
  }

  @Override
  public void close() {
    system.terminate();
    try {
      system.getWhenTerminated().toCompletableFuture().get(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted awaiting termination", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("the actor system did not terminate", e);
    } finally {
      blocking.close();
      model.close();
    }
  }

  /** Spawns the SAME worker tier tier 1 uses, then hands it out on request. */
  private static final class Guardian {

    sealed interface Command {}

    record GetWorkers(ActorRef<SpikeWorkers.Workers> replyTo) implements Command {}

    private Guardian() {}

    static Behavior<Command> create(SpikeModel model, SpikeToolbox toolbox, Executor blocking) {
      return Behaviors.setup(
          context -> {
            SpikeWorkers.Workers workers =
                SpikeWorkers.spawn(context, model, toolbox, blocking, 4, 8);
            return Behaviors.receive(Command.class)
                .onMessage(
                    GetWorkers.class,
                    get -> {
                      get.replyTo().tell(workers);
                      return Behaviors.same();
                    })
                .build();
          });
    }
  }
}
