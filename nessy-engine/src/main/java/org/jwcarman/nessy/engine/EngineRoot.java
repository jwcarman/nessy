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

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.SupervisorStrategy;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Routers;
import org.jwcarman.nessy.api.agent.AgentType;
import org.jwcarman.nessy.api.agent.Coalescer;
import org.jwcarman.nessy.api.agent.ObservationRenderer;

/**
 * The top of the engine's own subtree: the model desk and its workers, the tool pool, and the
 * registry that owns agents.
 *
 * <p><b>Not a Pekko guardian.</b> A guardian is the behavior an {@code ActorSystem} is CREATED
 * with, and a harness handed an existing system can never be one (engine-extraction spec §3.1) — so
 * this is spawned beneath whatever guardian the caller already has. It exists so the engine has a
 * single parent: stop this one actor and everything the harness started stops with it, without the
 * harness tracking children by hand.
 *
 * <p><b>Why the two worker tiers differ.</b> Tools go behind a pool router because they are cheap
 * and local, and a router is one line. Model calls go through a work-pulling desk because a router
 * bounds concurrent message PROCESSING rather than concurrent in-flight WORK — and for calls that
 * cost money or hit a rate limit, in-flight is the number that matters. See {@link ModelDesk}.
 */
public final class EngineRoot {

  /** What this actor accepts. */
  public sealed interface Command {}

  /** Hands out the registry once the tree is up. */
  public record GetRegistry(ActorRef<ActorRef<AgentRegistry.Command>> replyTo) implements Command {}

  /**
   * Stops this subtree and everything beneath it.
   *
   * <p>A command rather than an outside {@code stop} call, because a top-level actor is stopped by
   * its own decision — and because this is the single point that takes the engine down, which is
   * what having one parent bought us.
   */
  public record Stop() implements Command {}

  private EngineRoot() {}

  /** Everything the engine needs that a host supplies. */
  public record Wiring(
      AgentModel model,
      AgentTools tools,
      Memories memories,
      Coalescer<String> coalescer,
      ObservationRenderer<String> renderer,
      Traces traces,
      Clock clock,
      Executor blocking,
      int modelWorkers,
      int toolWorkers,
      Duration approvalTerm,
      Claims claims,
      AgentType agentType) {}

  /** The engine's subtree, ready to be spawned under a caller's system. */
  public static Behavior<Command> create(Wiring wiring) {
    return Behaviors.setup(
        context -> {
          ActorRef<ModelDesk.Command> desk = context.spawn(ModelDesk.create(), "model-desk");

          for (int i = 0; i < wiring.modelWorkers(); i++) {
            context.spawn(
                Behaviors.supervise(
                        ModelWorker.create(
                            wiring.model(), wiring.memories(), wiring.blocking(), wiring.traces()))
                    .onFailure(
                        SupervisorStrategy.restartWithBackoff(
                                Duration.ofMillis(200), Duration.ofSeconds(5), 0.2)
                            .withMaxRestarts(3)),
                "model-worker-" + i);
          }

          ActorRef<ToolWorker.RunTool> tools =
              context.spawn(
                  Routers.pool(
                      wiring.toolWorkers(),
                      ToolWorker.create(
                          wiring.tools(),
                          wiring.memories(),
                          wiring.blocking(),
                          wiring.traces(),
                          wiring.claims())),
                  "tool-pool");

          ActorRef<AgentRegistry.Command> registry =
              context.spawn(
                  AgentRegistry.create(
                      new AgentActor.Dependencies(
                          desk,
                          tools,
                          wiring.memories(),
                          wiring.coalescer(),
                          wiring.renderer(),
                          wiring.blocking(),
                          wiring.traces(),
                          wiring.clock(),
                          wiring.approvalTerm(),
                          wiring.claims(),
                          wiring.tools(),
                          wiring.agentType())),
                  "registry");

          return Behaviors.receive(Command.class)
              .onMessage(
                  GetRegistry.class,
                  get -> {
                    get.replyTo().tell(registry);
                    return Behaviors.same();
                  })
              .onMessage(Stop.class, stop -> Behaviors.stopped())
              .build();
        });
  }
}
