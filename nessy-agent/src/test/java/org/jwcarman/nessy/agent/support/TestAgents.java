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
package org.jwcarman.nessy.agent.support;

import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.DefaultAgent;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.StalenessPolicy;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.ObservationRenderer;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.spi.Memory;

/**
 * A one-scope {@link Harness} over fixed collaborators, for tests that want to wire a {@link
 * DefaultAgent} directly against test doubles — the id-free factories all ignore the raw id and
 * hand back the same fixed instance every time, matching the old {@code AgentWiring}'s one-scope
 * shape.
 */
public final class TestAgents {

  private TestAgents() {}

  public static <O> DefaultAgent<O> wired(
      Memory memory,
      AgentStateStore store,
      Backlog<O> backlog,
      ObservationRenderer<O> renderer,
      ModelCallExecutor model,
      ToolCallExecutor tools,
      AgentObserver observer,
      boolean drainOnIdle,
      StalenessPolicy stalenessPolicy) {
    Harness<O> harness =
        Harness.of(
            AgentType.of("test"),
            renderer,
            observer,
            drainOnIdle,
            stalenessPolicy,
            rawId -> memory,
            rawId -> store,
            rawId -> backlog,
            binding -> model,
            binding -> tools);
    return new DefaultAgent<>(harness, harness.bind(AgentId.of("test-scope")));
  }
}
