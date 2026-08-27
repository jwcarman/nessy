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

import com.typesafe.config.Config;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.pekko.actor.BootstrapSetup;
import org.apache.pekko.actor.setup.ActorSystemSetup;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.MemberStatus;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.apache.pekko.cluster.typed.Cluster;
import org.apache.pekko.cluster.typed.Join;
import org.apache.pekko.serialization.jackson3.JacksonObjectMapperProviderSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * THROWAWAY SPIKE. Everything a process has to grow in order to host one agent.
 *
 * <p>This class IS the ops footprint answer: an ActorSystem, a one-node cluster that joins itself,
 * and a sharding registration. Timed on startup, because whether a CLI can afford this is a real
 * question.
 */
public final class SpikeCluster implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(SpikeCluster.class);

  private final ActorSystem<Void> system;
  private final ClusterSharding sharding;
  private final SpikeModel model;
  private final Duration startup;

  /** The scripted-model constructor the automated tests use. */
  public SpikeCluster(Config config, Duration modelLatency) {
    this(config, new ScriptedSpikeModel(modelLatency));
  }

  public SpikeCluster(Config config, SpikeModel model) {
    long began = System.nanoTime();
    this.model = model;

    // The ObjectMapper seam is programmatic, not config: there is no
    // pekko.serialization.jackson.*.object-mapper-factory setting. This is where a real
    // integration would hand Pekko Nessy's own pinned mapper. Identical in both phases.
    ActorSystemSetup setup =
        ActorSystemSetup.create(BootstrapSetup.create(config))
            .withSetup(JacksonObjectMapperProviderSetup.create(new SpikeObjectMapperFactory()));
    this.system = ActorSystem.create(Behaviors.empty(), "spike", setup);

    // A single node still has to form a cluster: it joins itself. With a random remoting port
    // there is no seed-node address to write into config, so this is the only way to say it.
    Cluster cluster = Cluster.get(system);
    cluster.manager().tell(Join.create(cluster.selfMember().address()));
    awaitUp(cluster);

    this.sharding = ClusterSharding.get(system);
    SpikeToolbox toolbox = new SpikeToolbox();
    sharding.init(
        Entity.of(
                SpikeTurnEntity.TYPE_KEY,
                entityContext -> SpikeTurnEntity.create(entityContext, model, toolbox))
            // Required for a deliberate passivation: the shard sends this back when the entity
            // asks to be let go, and only this message actually stops the actor.
            .withStopMessage(SpikeTurnEntity.STOP));

    this.startup = Duration.ofNanos(System.nanoTime() - began);
    LOG.info(
        "[spike] ActorSystem + single-node cluster + sharding ready in {} ms", startup.toMillis());
  }

  /** How long the whole thing took to become usable. */
  public Duration startupTime() {
    return startup;
  }

  public ActorSystem<Void> system() {
    return system;
  }

  /** The door to one agent. The id is the whole address — no lookup, no routing table. */
  public EntityRef<SpikeTurnEntity.Command> agent(String agentId) {
    return sharding.entityRefFor(SpikeTurnEntity.TYPE_KEY, agentId);
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

  /** A real, full termination: the system is gone before this returns. */
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
      model.close();
    }
  }
}
