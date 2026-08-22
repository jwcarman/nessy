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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.jwcarman.nessy.agent.State;
import org.jwcarman.nessy.agent.codec.StateCodec;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The {@code state} recipe (substrate spec §6.1): one document per scope, keyed by {@code agentId}.
 * The document version IS the scope version — no separate version field rides in the payload — so
 * {@link #save(State)} is a direct CAS write and a lost race surfaces as {@link
 * Substrate.Document}'s version disagreeing with what the caller believed it held.
 */
public final class StoredAgentStateStore implements AgentStateStore {

  private static final String KIND = "state";

  private final Substrate store;
  private final String agentId;
  private final Instant birth;
  private final StateCodec codec;

  public StoredAgentStateStore(Substrate store, String agentId, Clock clock, ObjectMapper mapper) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
    this.birth = Objects.requireNonNull(clock, "clock must not be null").instant();
    this.codec = new StateCodec(Objects.requireNonNull(mapper, "mapper must not be null"));
  }

  @Override
  public State load() {
    return store
        .read(KIND, agentId)
        .map(
            doc ->
                new State(
                    codec.phase(new String(doc.payload(), StandardCharsets.UTF_8)), doc.version()))
        .orElseGet(State::initial);
  }

  @Override
  public void save(State state) {
    Objects.requireNonNull(state, "state must not be null");
    byte[] payload = codec.toJson(state.phase()).getBytes(StandardCharsets.UTF_8);
    try {
      store.write(KIND, agentId, payload, state.version());
    } catch (ConflictException e) {
      long actual = store.read(KIND, agentId).map(Substrate.Document::version).orElse(0L);
      throw new StaleStateException(state.version(), actual);
    }
  }

  @Override
  public Instant lastSaved() {
    return store.read(KIND, agentId).map(Substrate.Document::updatedAt).orElse(birth);
  }
}
