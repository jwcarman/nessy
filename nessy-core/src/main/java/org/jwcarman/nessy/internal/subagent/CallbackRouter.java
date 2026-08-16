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
package org.jwcarman.nessy.internal.subagent;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.ToolResolution;

/**
 * A name-keyed registry of {@link Agent}s, so a subagent callback can find the parent agent to
 * resume the child's answer through. Keyed by {@link Agent#name()} — the same durable wire contract
 * a park's own stamp carries (see {@link org.jwcarman.nessy.api.WrongAgentException}): a callback
 * names the agent it is meant for, and this router is where that name resolves back to a live
 * instance.
 *
 * <p>The only door callers need is {@link #resume}: it looks the agent up by name and delegates
 * straight to its own {@link Agent#resume(ParkToken, ToolResolution)}. There is deliberately no
 * public door that hands back the {@code Agent<?>} itself — a wildcard-typed return offers nothing
 * a caller could safely do with it beyond resuming, so that's the only thing this router lets you
 * do (java:S1452).
 */
public final class CallbackRouter {

  private final Map<String, Agent<?>> agents = new ConcurrentHashMap<>();

  /**
   * Registers {@code agent} under {@link Agent#name()}.
   *
   * @throws IllegalArgumentException if an agent with that name is already registered
   */
  public void register(Agent<?> agent) {
    Objects.requireNonNull(agent, "agent must not be null");
    Agent<?> existing = agents.putIfAbsent(agent.name(), agent);
    if (existing != null) {
      throw new IllegalArgumentException(
          "an agent named '" + agent.name() + "' is already registered");
    }
  }

  /**
   * Removes whatever is registered under {@code name}, if anything — idempotent, silent on a name
   * that was never registered (or already removed). Exists for exactly one caller: {@code
   * AgentBuilder}'s own rollback on a failed build (final review SF-5) — a subagent tree built
   * left-to-right registers each child as its own {@code build()} completes, so a later sibling's
   * failure (most concretely, a duplicate name colliding with an earlier one) must not leave the
   * earlier, successfully-built siblings sitting in this registry forever; a corrected rebuild
   * would then collide on THEM instead of the name that actually needs fixing.
   */
  public void unregister(String name) {
    Objects.requireNonNull(name, "name must not be null");
    agents.remove(name);
  }

  /**
   * Looks up the agent registered under {@code agentName} — the same name a park's own stamp
   * carries — and resumes its park with {@code resolution}.
   *
   * @throws IllegalArgumentException if no agent with that name is registered
   * @throws org.jwcarman.nessy.api.WrongAgentException if {@code token} was not minted by that
   *     agent (surfaces uncaught from {@link Agent#resume(ParkToken, ToolResolution)})
   */
  public RunOutcome resume(String agentName, ParkToken token, ToolResolution resolution) {
    Objects.requireNonNull(agentName, "agentName must not be null");
    Agent<?> agent = agents.get(agentName);
    if (agent == null) {
      throw new IllegalArgumentException(
          "no agent named '"
              + agentName
              + "' is registered — an agent's name is a durable wire contract; register it before"
              + " routing callbacks to it");
    }
    return agent.resume(token, resolution);
  }
}
