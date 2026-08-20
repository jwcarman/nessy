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
package org.jwcarman.nessy.agent.store;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.nessy.agent.State;

/**
 * One scope, one atomic reference. Versioning is enforced exactly as a JDBC store would: the CAS is
 * the concurrency model, and a store that skips it removes the lock (spec §3.2).
 */
public final class InMemoryAgentStateStore implements AgentStateStore {

  private final AtomicReference<State> current = new AtomicReference<>(State.initial());

  @Override
  public State load() {
    return current.get();
  }

  @Override
  public void save(State state) {
    Objects.requireNonNull(state, "state must not be null");
    State next = new State(state.phase(), state.version() + 1);
    while (true) {
      State stored = current.get();
      if (stored.version() != state.version()) {
        throw new StaleStateException(state.version(), stored.version());
      }
      if (current.compareAndSet(stored, next)) {
        return;
      }
    }
  }
}
