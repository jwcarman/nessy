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
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * THROWAWAY SPIKE, TIER 1 — the PRIMARY configuration.
 *
 * <p>Everything a process must grow to host durable agents on one node, in full:
 *
 * <pre>
 *   an ActorSystem, with provider = local
 * </pre>
 *
 * <p>That is the whole list. No cluster to form, no node to join itself to, no remoting port, no
 * bound hostname, no split-brain-resolver policy, no seed nodes, no {@code pekko-management} for
 * Kubernetes discovery, and — because {@link SpikeStateSerializer} replaced Pekko's Jackson — no
 * serialization module to reconcile against the reactor's Jackson pin. Compare §7 of the round-1
 * report, which is the same list for a sharded deployment and is seven items long.
 *
 * <p>Recreating stalled turns is {@link SpikeSweep}'s job rather than {@code rememberEntities}'.
 */
public final class LocalSpikeRuntime implements SpikeRuntime {

  private static final Logger LOG = LoggerFactory.getLogger(LocalSpikeRuntime.class);

  private final ActorSystem<Guardian.Command> system;
  private final SpikeBlockingWork blocking = new SpikeBlockingWork();
  private final SpikeModel model;
  private final SpikeAgents agents;
  private final Duration startup;

  public LocalSpikeRuntime(Config config, SpikeModel model, SpikeSweep sweep) {
    long began = System.nanoTime();
    this.model = model;
    this.system =
        ActorSystem.create(
            Guardian.create(model, new SpikeToolbox(), blocking.executor()), "spike", config);

    ActorRef<SpikeRegistry.Command> registry = askForRegistry();
    this.agents = (agentId, command) -> registry.tell(new SpikeRegistry.Envelope(agentId, command));
    this.startup = Duration.ofNanos(System.nanoTime() - began);
    LOG.info("[spike] local runtime ready in {} ms", startup.toMillis());

    // The driver obligation, owned by us rather than by rememberEntities.
    sweep.unfinishedAgents().forEach(agentId -> agents.tell(agentId, new AgentActor.Wake()));
  }

  private ActorRef<SpikeRegistry.Command> askForRegistry() {
    try {
      return AskPattern.<Guardian.Command, ActorRef<SpikeRegistry.Command>>ask(
              system, Guardian.GetRegistry::new, Duration.ofSeconds(20), system.scheduler())
          .toCompletableFuture()
          .get(20, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted starting the runtime", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("the guardian never handed out a registry", e);
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

  /** Spawns the worker tier and the registry, then hands the registry out on request. */
  private static final class Guardian {

    sealed interface Command {}

    record GetRegistry(ActorRef<ActorRef<SpikeRegistry.Command>> replyTo) implements Command {}

    private Guardian() {}

    static Behavior<Command> create(
        SpikeModel model, SpikeToolbox toolbox, java.util.concurrent.Executor blocking) {
      return Behaviors.setup(
          context -> {
            SpikeWorkers.Workers workers =
                SpikeWorkers.spawn(context, model, toolbox, blocking, 4, 8);
            ActorRef<SpikeRegistry.Command> registry =
                context.spawn(
                    SpikeRegistry.create(
                        toolbox, workers.modelDesk(), workers.tools(), Duration.ofMinutes(10)),
                    "registry");
            return Behaviors.receive(Command.class)
                .onMessage(
                    GetRegistry.class,
                    get -> {
                      get.replyTo().tell(registry);
                      return Behaviors.same();
                    })
                .build();
          });
    }
  }
}
