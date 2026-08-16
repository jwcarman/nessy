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
package org.jwcarman.nessy.spi.subagent;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.Agent;

/**
 * A name-keyed registry of {@link Agent}s, so a subagent callback can find the parent agent to
 * route the child's answer through. Keyed by {@link Agent#name()} — the same durable wire contract
 * a park's own stamp carries (see {@link org.jwcarman.nessy.api.WrongAgentException}): a callback
 * names the agent it is meant for, and this router is where that name resolves back to a live
 * instance.
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
   * Looks up the agent registered under {@code agentName} — the same name a park's own stamp
   * carries.
   *
   * @throws IllegalArgumentException if no agent with that name is registered
   */
  public Agent<?> route(String agentName) {
    Objects.requireNonNull(agentName, "agentName must not be null");
    Agent<?> agent = agents.get(agentName);
    if (agent == null) {
      throw new IllegalArgumentException(
          "no agent named '"
              + agentName
              + "' is registered — an agent's name is a durable wire contract; register it before"
              + " routing callbacks to it");
    }
    return agent;
  }
}
