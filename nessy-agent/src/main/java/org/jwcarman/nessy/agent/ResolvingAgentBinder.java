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
package org.jwcarman.nessy.agent;

import java.util.Objects;

/**
 * The ordinary {@link AgentBinder} built from an {@link AgentResolver}: resolve the scope's
 * instance, then hand it the event through its package-private door — the same point-to-point sink
 * handoff as dispatch, performed at bind time (spec §4.3).
 */
public final class ResolvingAgentBinder implements AgentBinder {

  private final AgentResolver resolver;

  public ResolvingAgentBinder(AgentResolver resolver) {
    this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
  }

  @Override
  public void deliver(AgentType type, AgentId id, AgentEvent event) {
    Agent<?> agent = resolver.resolve(type, id);
    if (!(agent instanceof DefaultAgent<?> defaultAgent)) {
      // fix round 1 M6: a null resolve must land in this same message, not NPE out of
      // Class#getName() while building it.
      String resolved = agent == null ? "null" : agent.getClass().getName();
      throw new IllegalStateException("resolved agent is not a DefaultAgent: " + resolved);
    }
    defaultAgent.deliver(event);
  }
}
