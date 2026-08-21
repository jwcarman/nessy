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

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.nessy.agent.State;

/**
 * The shared, thread-safe underlay behind many scopes' state (spec §10.11): one {@link
 * ConcurrentHashMap} keyed by scope id, one clock for every scope's {@code lastSaved} stamp. {@link
 * #forScope(String)} returns a thin view — a reference to this map plus an id, never a copy of the
 * data. Losing a view loses nothing; two views of the same id observe each other's writes.
 */
public final class InMemoryStateSubstrate {

  private record Slot(State state, Instant savedAt) {}

  private final Clock clock;
  private final ConcurrentHashMap<String, AtomicReference<Slot>> slots = new ConcurrentHashMap<>();

  public InMemoryStateSubstrate() {
    this(Clock.systemUTC());
  }

  public InMemoryStateSubstrate(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * A thin view over one scope's slot in the shared map — CAS semantics identical to {@link
   * InMemoryAgentStateStore}.
   */
  public AgentStateStore forScope(String id) {
    Objects.requireNonNull(id, "id must not be null");
    return new View(id);
  }

  private AtomicReference<Slot> slotFor(String id) {
    return slots.computeIfAbsent(
        id, key -> new AtomicReference<>(new Slot(State.initial(), clock.instant())));
  }

  private final class View implements AgentStateStore {

    private final String id;

    private View(String id) {
      this.id = id;
    }

    @Override
    public State load() {
      return slotFor(id).get().state();
    }

    @Override
    public Instant lastSaved() {
      return slotFor(id).get().savedAt();
    }

    @Override
    public void save(State state) {
      Objects.requireNonNull(state, "state must not be null");
      State next = new State(state.phase(), state.version() + 1);
      AtomicReference<Slot> slot = slotFor(id);
      while (true) {
        Slot stored = slot.get();
        if (stored.state().version() != state.version()) {
          throw new StaleStateException(state.version(), stored.state().version());
        }
        if (slot.compareAndSet(stored, new Slot(next, clock.instant()))) {
          return;
        }
      }
    }
  }
}
