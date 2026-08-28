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

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.SupervisorStrategy;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Routers;

/**
 * THE SHAPE. Every actor in the watchman, and who owns whom, in one method.
 *
 * <pre>
 *   guardian                                    (this file)
 *     |
 *     +-- model-desk              1  owns the work-pulling producer; agents just tell it
 *     |     \-- producer          1  (WorkPullingProducerController, spawned by the desk)
 *     |
 *     +-- model-worker-0..3       4  work-pulling CONSUMERS; capacity IS this number
 *     |     \-- consumer          1  each (ConsumerController, registers with the Receptionist)
 *     |
 *     +-- tool-pool               1  pool router over N stateless tool workers
 *     |
 *     \-- registry                1  one child per agent id, spawned on first message
 *           \-- agent-watchman    1  DURABLE. The round lives here. One per box.
 *                 \-- call-&lt;id&gt;   *  EPHEMERAL, one per tool call, dies when it settles
 *                       \-- approval  1  EPHEMERAL, only for a call that needs a human
 * </pre>
 *
 * <p><b>What is durable and what is not.</b> Exactly one actor in that tree persists anything: the
 * agent. Everything below it is ephemeral and rebuildable — a tool call's actor is spawned from the
 * agent's record of "this call has no outcome yet", and an approval's actor recomputes its own
 * deadline from the persisted ask time. Everything above it is process-scoped machinery that a
 * restart is welcome to lose.
 *
 * <p><b>Why the two worker tiers differ.</b> Tools go behind a pool router because they are cheap
 * and local, and a router is one line. Model calls go through a work-pulling desk because a router
 * would bound concurrent message PROCESSING and not concurrent in-flight WORK — and for calls that
 * cost money or hit a rate limit, in-flight is the number that matters. See {@link ModelDesk}.
 *
 * <p><b>What this port needed that the spike did not.</b> Three things, and only three: {@link
 * Clock} (the approvals page shows dwell time, and a deadline needs a now), the {@link Traces}
 * carrier (a trace tree cannot survive a mailbox by itself), and a persisted decision on each tool
 * call (a human's answer must be durable before it is acknowledged). The hierarchy itself is the
 * spike's, unchanged.
 */
public final class WatchmanGuardian {

  /** The one agent. One box, one watchman — the same id the sibling application uses. */
  public static final String WATCHMAN = "watchman";

  public sealed interface Command {}

  /** Hands out the registry once the tree is up. */
  public record GetRegistry(ActorRef<ActorRef<AgentRegistry.Command>> replyTo) implements Command {}

  private WatchmanGuardian() {}

  public static Behavior<Command> create(
      WatchmanModel model,
      CommandRunner runner,
      Transcript transcript,
      Traces traces,
      Clock clock,
      Executor blocking,
      int modelWorkers,
      int toolWorkers,
      Duration approvalTerm) {

    return Behaviors.setup(
        context -> {
          ActorRef<ModelDesk.Command> desk = context.spawn(ModelDesk.create(), "model-desk");

          for (int i = 0; i < modelWorkers; i++) {
            context.spawn(
                Behaviors.supervise(ModelWorker.create(model, transcript, blocking, traces))
                    .onFailure(
                        SupervisorStrategy.restartWithBackoff(
                                Duration.ofMillis(200), Duration.ofSeconds(5), 0.2)
                            .withMaxRestarts(3)),
                "model-worker-" + i);
          }

          ActorRef<ToolWorker.RunTool> tools =
              context.spawn(
                  Routers.pool(
                      toolWorkers, ToolWorker.create(runner, transcript, blocking, traces)),
                  "tool-pool");

          ActorRef<AgentRegistry.Command> registry =
              context.spawn(
                  AgentRegistry.create(
                      new AgentActor.Dependencies(
                          desk, tools, transcript, blocking, traces, clock, approvalTerm)),
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
