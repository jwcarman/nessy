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

/** The whole of a scope's control state: what it is doing, and the optimistic-lock version. */
public record State(Phase phase, long version) {

  public State {
    Objects.requireNonNull(phase, "phase must not be null");
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
  }

  public static State initial() {
    return new State(new Phase.Idle(), 0L);
  }
}
