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
package org.jwcarman.nessy.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.spi.substrate.Codec;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.DocumentStore;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.jwcarman.nessy.spi.substrate.Versioned;

/**
 * The {@code intent} recipe (substrate spec §6.3): one document per scope, keyed by {@code
 * agentId}, holding the latest declaration — last write wins, blindly, via a version-only CAS retry
 * loop over {@link DocumentStore#version(String)} (typed-stores fix round 1, Q3): the documented
 * contract is "last write wins" full stop, not "last write wins unless the incumbent happens to be
 * a foreign shape" — {@link DocumentStore#update} would decode the incumbent before discarding it,
 * narrowing that contract for a caller sharing a key across declaration types.
 *
 * <p>The stored shape is a {@link Codec}{@code <T>} (spec §3, §7): the {@link
 * #SubstrateIntentStore(Substrate, String, Class, ObjectMapper)} constructor defaults it to {@link
 * Codec#json(ObjectMapper, Class)} — a sealed vocabulary's declaration is rendered with a {@code
 * "type"} discriminator naming the declared record, and read back through the vocabulary class
 * token; the freeform {@link Intent} tier — and any other plain record vocabulary — round-trips as
 * an ordinary JSON object, no discriminator involved. {@link #SubstrateIntentStore(Substrate,
 * String, Codec)} accepts a caller-supplied codec directly — a transform chained on with {@link
 * Codec#then(Codec)} (encryption, compression) or a test probe.
 *
 * @param <T> the declared-intent vocabulary this store holds
 */
public final class SubstrateIntentStore<T> implements IntentStore<T> {

  private static final String KIND = "intent";

  private final DocumentStore<T> documents;
  private final String agentId;

  /** Defaults the stored shape to {@link Codec#json(ObjectMapper, Class)} over {@code mapper}. */
  public SubstrateIntentStore(
      Substrate store, String agentId, Class<T> vocabulary, ObjectMapper mapper) {
    this(
        store,
        agentId,
        Codec.json(
            Objects.requireNonNull(mapper, "mapper must not be null"),
            Objects.requireNonNull(vocabulary, "vocabulary must not be null")));
  }

  public SubstrateIntentStore(Substrate store, String agentId, Codec<T> codec) {
    Objects.requireNonNull(store, "store must not be null");
    this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
    this.documents = store.document(KIND, Objects.requireNonNull(codec, "codec must not be null"));
  }

  @Override
  public void declare(T declaration) {
    Objects.requireNonNull(declaration, "declaration must not be null");
    while (true) {
      long expectedVersion = documents.version(agentId).orElse(0L);
      try {
        documents.write(agentId, declaration, expectedVersion);
        return;
      } catch (ConflictException _) {
        // another writer declared between our version read and our write; retry
      }
    }
  }

  @Override
  public Optional<T> latest() {
    return documents.read(agentId).map(Versioned::value);
  }
}
