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

import java.time.Instant;
import java.util.Objects;
import org.jwcarman.nessy.agent.AgentPhase;
import org.jwcarman.nessy.agent.store.AgentPhaseStore;
import org.jwcarman.nessy.spi.substrate.Versioned;

/**
 * Simulates one lost race: the first save is preceded by a competitor's save (supplied by the
 * test), so the delegate throws a genuine StaleStateException; every later save goes straight
 * through. The competitor's state is computed by the test with the pure phase machine.
 */
public final class RaceOnceStore implements AgentPhaseStore {

  private final AgentPhaseStore delegate;
  private final Versioned<AgentPhase> competitor;
  private boolean raced;

  public RaceOnceStore(AgentPhaseStore delegate, Versioned<AgentPhase> competitor) {
    this.delegate = Objects.requireNonNull(delegate);
    this.competitor = Objects.requireNonNull(competitor);
  }

  @Override
  public Versioned<AgentPhase> load() {
    return delegate.load();
  }

  @Override
  public Instant lastSaved() {
    return delegate.lastSaved();
  }

  @Override
  public void save(Versioned<AgentPhase> state) {
    if (!raced) {
      raced = true;
      delegate.save(competitor); // someone else won first
    }
    delegate.save(state);
  }
}
