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

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.jwcarman.nessy.api.agent.AgentType;

/**
 * A {@link Harness} over one {@link EngineRoot} subtree.
 *
 * <p>Holds the root rather than the registry, and asks the root for the registry once, lazily. The
 * root is spawned synchronously but its children are created inside {@code Behaviors.setup}, so the
 * registry does not exist the instant {@code spawn} returns — asking for it is how a caller waits
 * for the tree without the factory blocking on construction.
 */
final class PekkoHarness implements Harness<String> {

  private static final Duration WIRING_PATIENCE = Duration.ofSeconds(10);

  private final AgentType type;
  private final ActorRef<EngineRoot.Command> root;
  private final ActorSystem<?> system;
  private final Traces traces;

  private volatile ActorRef<AgentRegistry.Command> registry;

  PekkoHarness(
      AgentType type, ActorRef<EngineRoot.Command> root, ActorSystem<?> system, Traces traces) {
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.root = Objects.requireNonNull(root, "root must not be null");
    this.system = Objects.requireNonNull(system, "system must not be null");
    this.traces = Objects.requireNonNull(traces, "traces must not be null");
  }

  @Override
  public AgentType type() {
    return type;
  }

  @Override
  public void observe(String agentId, String observation) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(observation, "observation must not be null");
    registry()
        .tell(
            new AgentRegistry.Envelope(
                agentId, new AgentActor.Observe(observation, traces.capture())));
  }

  /**
   * Stops the subtree this harness spawned, and only that.
   *
   * <p>The caller's {@code ActorSystem} keeps running — it was borrowed. A harness that terminated
   * it would take down whatever else the host is doing on the same system, which inside a Boot app
   * is everything.
   */
  @Override
  public void shutdown() {
    root.tell(new EngineRoot.Stop());
  }

  private ActorRef<AgentRegistry.Command> registry() {
    ActorRef<AgentRegistry.Command> known = registry;
    if (known != null) {
      return known;
    }
    CompletionStage<ActorRef<AgentRegistry.Command>> asked =
        AskPattern.ask(root, EngineRoot.GetRegistry::new, WIRING_PATIENCE, system.scheduler());
    ActorRef<AgentRegistry.Command> resolved = asked.toCompletableFuture().join();
    registry = resolved;
    return resolved;
  }
}
