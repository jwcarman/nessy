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
import java.util.concurrent.Executor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.SupervisorStrategy;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Routers;

/**
 * THROWAWAY SPIKE. The worker tier, spawned identically by both runtimes.
 *
 * <p>Two deliberately different strategies, because the two kinds of work want different things.
 *
 * <p><b>Model calls: work pulling, Receptionist-discovered.</b> {@link SpikeModelWorker}s register
 * themselves under a {@link SpikeModelDesk#MODEL_WORKERS} service key and confirm each job only
 * after the model answers, so the number of workers IS the number of model calls that can be in
 * flight. Adding capacity is spawning another worker; no configuration is involved.
 *
 * <p><b>Tools: a plain pool router.</b> Cheaper, one line, and honest about being a weaker
 * guarantee — see {@link SpikeToolWorker} for why a pool router does not bound in-flight
 * asynchronous work.
 *
 * <p><b>Retry as supervision.</b> {@code restartWithBackoff} IS an attempt counter plus exponential
 * backoff plus jitter, already written and tested by somebody else, so the spike writes none of it.
 * Where it does NOT fit is stated in the report: supervision restarts an actor that threw, but it
 * cannot know whether the tool had already done its work before dying, so it is a retry policy for
 * FAILING work and never a substitute for knowing whether an effect happened.
 */
public final class SpikeWorkers {

  /** What every runtime needs handed to its agents. */
  public record Workers(
      ActorRef<SpikeModelDesk.Command> modelDesk, ActorRef<SpikeToolWorker.RunTool> tools) {}

  private SpikeWorkers() {}

  public static <T> Workers spawn(
      ActorContext<T> context,
      SpikeModel model,
      SpikeToolbox toolbox,
      Executor blocking,
      int modelWorkers,
      int toolWorkers) {

    ActorRef<SpikeModelDesk.Command> desk = context.spawn(SpikeModelDesk.create(), "model-desk");

    for (int i = 0; i < modelWorkers; i++) {
      context.spawn(
          Behaviors.supervise(SpikeModelWorker.create(model, blocking))
              .onFailure(
                  SupervisorStrategy.restartWithBackoff(
                          Duration.ofMillis(200), Duration.ofSeconds(5), 0.2)
                      .withMaxRestarts(3)),
          "model-worker-" + i);
    }

    ActorRef<SpikeToolWorker.RunTool> tools =
        context.spawn(
            Routers.pool(toolWorkers, SpikeToolWorker.create(toolbox, blocking)), "tool-pool");

    return new Workers(desk, tools);
  }
}
