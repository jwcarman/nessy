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
package org.jwcarman.nessy.api;

import java.util.Objects;

/**
 * What KIND of agent this is — a watchman, a support agent, a planner.
 *
 * <p>The type is code; the id is data. One {@link Harness} serves exactly one type, and an {@link
 * AgentId} is only meaningful within it: two kinds of agent may both have an instance called {@code
 * "house-12"} without colliding, because the type is the namespace.
 *
 * <p>It reaches further than configuration. It qualifies persistence keys, labels telemetry, and
 * tells a shared approvals page whether the call it is showing came from a watchman or a support
 * agent — so keep the name boring and stable. Renaming a type in a running system orphans whatever
 * was stored under the old one.
 */
public record AgentType(String name) {

  public AgentType {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("agent type name must not be blank");
    }
  }

  public static AgentType of(String name) {
    return new AgentType(name);
  }
}
