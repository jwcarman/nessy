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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.tool.SealedInputs;
import org.jwcarman.nessy.spi.store.ConflictException;
import org.jwcarman.nessy.spi.store.ScopedStore;

/**
 * The {@code intent} recipe (scoped-store spec §6.3): one document per scope, keyed by {@code
 * agentId}, holding the latest declaration — last write wins via a read-then-CAS retry loop, the
 * same read-decide-CAS shape the kernel's other recipes ride.
 *
 * <p>The stored payload rides the same discriminator convention {@link IntentTool} binds sealed
 * vocabularies through ({@link SealedInputs}): a sealed vocabulary's declaration is rendered with a
 * {@code "type"} discriminator naming the declared record, and read back through the vocabulary
 * class token via {@link SealedInputs#bind}. The freeform {@link Intent} tier — and any other plain
 * record vocabulary — round-trips as an ordinary JSON object, no discriminator involved.
 *
 * @param <T> the declared-intent vocabulary this store holds
 */
public final class StoredIntentStore<T> implements IntentStore<T> {

  private static final String KIND = "intent";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ScopedStore store;
  private final String agentId;
  private final Class<T> vocabulary;

  public StoredIntentStore(ScopedStore store, String agentId, Class<T> vocabulary) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
    this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary must not be null");
  }

  @Override
  public void declare(T declaration) {
    Objects.requireNonNull(declaration, "declaration must not be null");
    String payload = toJson(declaration);
    while (true) {
      Optional<ScopedStore.Document> doc = store.read(KIND, agentId);
      long expectedVersion = doc.map(ScopedStore.Document::version).orElse(0L);
      try {
        store.write(KIND, agentId, payload, expectedVersion);
        return;
      } catch (ConflictException e) {
        // another writer declared between our read and our write; retry
      }
    }
  }

  @Override
  public Optional<T> latest() {
    return store.read(KIND, agentId).map(doc -> fromJson(doc.payload()));
  }

  private String toJson(T declaration) {
    ObjectNode node = (ObjectNode) MAPPER.valueToTree(declaration);
    if (SealedInputs.isSealedInput(vocabulary)) {
      node.put("type", declaration.getClass().getSimpleName());
    }
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("unwritable intent payload", e);
    }
  }

  private T fromJson(String payload) {
    JsonNode node;
    try {
      node = MAPPER.readTree(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("malformed intent payload", e);
    }
    if (SealedInputs.isSealedInput(vocabulary)) {
      return SealedInputs.bind(vocabulary, node, MAPPER);
    }
    return MAPPER.convertValue(node, vocabulary);
  }
}
