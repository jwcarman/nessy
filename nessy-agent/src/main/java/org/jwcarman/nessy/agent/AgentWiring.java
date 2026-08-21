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

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.ObservationRenderer;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.spi.Memory;

/**
 * Everything a shell needs, pre-scoped (§3.5), plus the two host knobs: who executes the drain
 * (§3.1) and when a quiet phase counts as dead (§6.1). Plan 3's builders produce these.
 */
public record AgentWiring<O>(
    Memory memory,
    AgentStateStore store,
    Backlog<O> backlog,
    ObservationRenderer<O> renderer,
    ModelCallExecutor model,
    ToolCallExecutor tools,
    AgentObserver observer,
    boolean drainOnIdle,
    Duration staleThreshold,
    Clock clock) {

  public AgentWiring {
    Objects.requireNonNull(memory, "memory must not be null");
    Objects.requireNonNull(store, "store must not be null");
    Objects.requireNonNull(backlog, "backlog must not be null");
    Objects.requireNonNull(renderer, "renderer must not be null");
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(tools, "tools must not be null");
    Objects.requireNonNull(observer, "observer must not be null");
    Objects.requireNonNull(staleThreshold, "staleThreshold must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
  }
}
