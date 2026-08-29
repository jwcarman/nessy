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
import java.util.function.Function;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.jwcarman.nessy.api.agent.AgentType;

/**
 * The façade over one {@link HarnessActor}.
 *
 * <p>It holds an {@link ActorRef} and nothing else — no registry, no cache, no map. Every operation
 * is an agent id and a message, so there is no state here to go stale.
 *
 * <p><b>Every observation routes through here</b>, and there is no other door: nothing outside the
 * engine can obtain an agent. That is what lets the routing underneath change — local children
 * today, cluster sharding later — without a single caller noticing.
 *
 * <p>Its predecessor asked the tree for a registry on first use and cached it, which made the first
 * {@code observe()} on every harness block on an ask. The harness actor owns routing now, so the
 * hot path is a tell.
 */
public final class PekkoHarness implements Harness<String> {

  private final AgentType type;
  private final ActorRef<HarnessActor.Command> harness;
  private final ActorSystem<?> system;
  private final Traces traces;

  PekkoHarness(
      AgentType type,
      ActorRef<HarnessActor.Command> harness,
      ActorSystem<?> system,
      Traces traces) {
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.harness = Objects.requireNonNull(harness, "harness must not be null");
    this.system = Objects.requireNonNull(system, "system must not be null");
    this.traces = Objects.requireNonNull(traces, "traces must not be null");
  }

  @Override
  public AgentType type() {
    return type;
  }

  @Override
  public void observe(String agentId, String observation) {
    Objects.requireNonNull(observation, "observation must not be null");
    tell(agentId, new AgentActor.Observe(observation, traces.capture()));
  }

  /**
   * Stops this harness and every agent beneath it, and only those.
   *
   * <p>The caller's {@code ActorSystem} keeps running — it was borrowed. A harness that terminated
   * it would take down whatever else the host is doing on the same system, which inside a Boot app
   * is everything.
   */
  @Override
  public void shutdown() {
    harness.tell(new HarnessActor.Stop());
    if (!RoutingStrategy.isClustered(system)) {
      // Local routing claimed this type; releasing lets it be built again after a restart.
      LocalAgentTypes.of(system).release(type);
    }
  }

  /** Sends a message to one agent — the only way anything reaches an agent. */
  public void tell(String agentId, AgentActor.NessyMessage message) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(message, "message must not be null");
    harness.tell(new HarnessActor.Envelope(agentId, message));
  }

  /**
   * Sends a message to one agent and waits for its answer.
   *
   * <p>The reply-to ref belongs to the ASKER, never to the agent, so this does not reintroduce a
   * handle: the agent is still reached only by name.
   */
  public <R> CompletionStage<R> ask(
      String agentId, Function<ActorRef<R>, AgentActor.NessyMessage> message, Duration patience) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    return AskPattern.<HarnessActor.Command, R>ask(
        harness,
        replyTo -> new HarnessActor.Envelope(agentId, message.apply(replyTo)),
        patience,
        system.scheduler());
  }
}
