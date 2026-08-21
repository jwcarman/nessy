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
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.spi.Memory;

/**
 * The scope strapped in (§10.11): thin handles, stamped fresh per delivery by {@link
 * Harness#bind(AgentId)}. Cheap to build, cheap to discard — every field is a view over a shared
 * substrate (Task 2), never a copy, so binding twice for the same id sees the same world.
 */
public record Binding<O>(AgentId id, Memory memory, AgentStateStore store, Backlog<O> backlog) {

  public Binding {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(memory, "memory must not be null");
    Objects.requireNonNull(store, "store must not be null");
    Objects.requireNonNull(backlog, "backlog must not be null");
  }
}
