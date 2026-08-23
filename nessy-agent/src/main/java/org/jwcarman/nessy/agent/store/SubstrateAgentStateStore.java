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
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.State;
import org.jwcarman.nessy.agent.codec.StateCodec;
import org.jwcarman.nessy.spi.substrate.Codec;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.DocumentStore;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The {@code state} recipe (substrate spec §6.1): one document per scope, keyed by {@code agentId}.
 * The document version IS the scope version — no separate version field rides in the payload — so
 * {@link #save(State)} is a direct CAS write and a lost race surfaces as {@link
 * Substrate.Document}'s version disagreeing with what the caller believed it held.
 *
 * <p>The stored shape is a {@link Codec}{@code <}{@link Phase}{@code >} (spec §3, §7): the {@link
 * #SubstrateAgentStateStore(Substrate, String, Clock, ObjectMapper)} constructor defaults it to the
 * {@link StateCodec} binding; {@link #SubstrateAgentStateStore(Substrate, String, Clock, Codec)}
 * accepts a caller-supplied codec directly — a transform chained on with {@link Codec#then(Codec)}
 * (encryption, compression) or a test probe.
 */
public final class SubstrateAgentStateStore implements AgentStateStore {

  private static final String KIND = "state";

  private final Substrate store;
  private final String agentId;
  private final Instant birth;
  private final DocumentStore<Phase> documents;

  /** Defaults the stored shape to the {@link StateCodec} binding over {@code mapper}. */
  public SubstrateAgentStateStore(
      Substrate store, String agentId, Clock clock, ObjectMapper mapper) {
    this(
        store,
        agentId,
        clock,
        new StateCodecAdapter(
            new StateCodec(Objects.requireNonNull(mapper, "mapper must not be null"))));
  }

  public SubstrateAgentStateStore(
      Substrate store, String agentId, Clock clock, Codec<Phase> codec) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
    this.birth = Objects.requireNonNull(clock, "clock must not be null").instant();
    this.documents = store.document(KIND, Objects.requireNonNull(codec, "codec must not be null"));
  }

  @Override
  public State load() {
    return documents
        .read(agentId)
        .map(versioned -> new State(versioned.value(), versioned.version()))
        .orElseGet(State::initial);
  }

  @Override
  public void save(State state) {
    Objects.requireNonNull(state, "state must not be null");
    try {
      documents.write(agentId, state.phase(), state.version());
    } catch (ConflictException _) {
      // Non-decoding version() read (typed-stores fix round 1, Q6): the conflict is already known
      // — this is only naming the actual version for StaleStateException's message — so a foreign-
      // shaped winner (a different Phase-incompatible payload at this key) must surface as the
      // conflict it is, not as an unrelated decode failure masking it.
      long actual = documents.version(agentId).orElse(0L);
      throw new StaleStateException(state.version(), actual);
    }
  }

  @Override
  public Instant lastSaved() {
    return store.read(KIND, agentId).map(Substrate.Document::updatedAt).orElse(birth);
  }

  /**
   * Adapts {@link StateCodec}'s String-JSON binding to the byte-oriented {@link Codec} seam —
   * internal, not a new public type (spec §3, §7).
   */
  private static final class StateCodecAdapter implements Codec<Phase> {

    private final StateCodec codec;

    private StateCodecAdapter(StateCodec codec) {
      this.codec = codec;
    }

    @Override
    public byte[] encode(Phase phase) {
      return codec.toJson(phase).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Phase decode(byte[] bytes) {
      return codec.phase(new String(bytes, StandardCharsets.UTF_8));
    }
  }
}
