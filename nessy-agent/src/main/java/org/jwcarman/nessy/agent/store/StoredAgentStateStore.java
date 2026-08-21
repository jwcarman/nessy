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
import org.jwcarman.nessy.agent.State;
import org.jwcarman.nessy.agent.codec.StateCodec;
import org.jwcarman.nessy.spi.store.ConflictException;
import org.jwcarman.nessy.spi.store.ScopedStore;

/**
 * The {@code state} recipe (spec §6.1): one document per scope, keyed by {@code agentId}. The
 * document version IS the scope version — no separate version field rides in the payload — so
 * {@link #save(State)} is a direct CAS write and a lost race surfaces as {@link
 * ScopedStore.Document}'s version disagreeing with what the caller believed it held.
 */
public final class StoredAgentStateStore implements AgentStateStore {

  private static final String KIND = "state";

  private final ScopedStore store;
  private final String agentId;
  private final Instant birth;

  public StoredAgentStateStore(ScopedStore store, String agentId, Clock clock) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
    this.birth = Objects.requireNonNull(clock, "clock must not be null").instant();
  }

  @Override
  public State load() {
    return store
        .read(KIND, agentId)
        .map(doc -> new State(StateCodec.phase(doc.payload()), doc.version()))
        .orElseGet(State::initial);
  }

  @Override
  public void save(State state) {
    Objects.requireNonNull(state, "state must not be null");
    String payload = StateCodec.toJson(state.phase());
    try {
      store.write(KIND, agentId, payload, state.version());
    } catch (ConflictException e) {
      long actual = store.read(KIND, agentId).map(ScopedStore.Document::version).orElse(0L);
      throw new StaleStateException(state.version(), actual);
    }
  }

  @Override
  public Instant lastSaved() {
    return store.read(KIND, agentId).map(ScopedStore.Document::updatedAt).orElse(birth);
  }
}
