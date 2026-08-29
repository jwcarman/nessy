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
 * One harness, as an actor: the parent of every agent of ONE type, plus the machinery they share.
 *
 * <p><b>Its whole protocol is {@link Envelope}</b> — an agent id and a message. Nothing above it
 * ever receives an agent reference, which is what lets the routing underneath swap between local
 * children and cluster sharding without a single caller noticing. {@link Harness} is the façade
 * over this; it only ever tells.
 *
 * <p><b>Not a Pekko guardian.</b> A guardian is the behavior an {@code ActorSystem} is CREATED
 * with, and a harness handed an existing system can never be one (engine-extraction spec §3.1).
 * This is spawned beneath whatever guardian the caller already has, and stopping it stops
 * everything the harness started.
 *
 * <p><b>One harness is one agent type</b>, which is why the sharding entity key needs no separate
 * concept: it is the harness's own {@link AgentType}.
 *
 * <p><b>Why the two worker tiers differ.</b> Tools go behind a pool router because they are cheap
 * and local, and a router is one line. Model calls go through a work-pulling desk because a router
 * bounds concurrent message PROCESSING rather than concurrent in-flight WORK — and for calls that
 * cost money or hit a rate limit, in-flight is the number that matters. See {@link ModelDesk}.
 */
public final class HarnessActor {

  /** What this actor accepts. */
  public sealed interface Command {}

  /**
   * A message for one agent. Deliberately the same shape as cluster sharding's, so the clustered
   * routing is a substitution rather than a redesign.
   */
  public record Envelope(String agentId, AgentActor.NessyMessage message) implements Command {}

  /** Stops this harness and every agent beneath it. */
  public record Stop() implements Command {}

  private HarnessActor() {}

  /** Everything a harness needs that a host supplies. */
  public record Wiring(
      AgentType agentType,
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
      Claims claims) {}

  /** The harness's subtree, ready to be spawned under a caller's system. */
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

          AgentActor.Dependencies deps =
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
                  wiring.agentType());

          Agents agents = Agents.local(context, deps);

          return Behaviors.receive(Command.class)
              .onMessage(
                  Envelope.class,
                  envelope -> {
                    agents.tell(envelope.agentId(), envelope.message());
                    return Behaviors.same();
                  })
              .onMessage(Stop.class, stop -> Behaviors.stopped())
              .build();
        });
  }
}
