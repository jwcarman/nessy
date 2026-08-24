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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.DocumentStore;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.jwcarman.nessy.spi.substrate.Versioned;

/**
 * Dispatch memory: which computation a call is currently in flight under. This is what the deleted
 * id derivation used to provide for free — the gate absorbs a staleness redrive by reading this
 * rather than by recomputing an id Continuum no longer lets it choose.
 */
public final class DispatchIndex {

  private final DocumentStore<DispatchEntry> entries;

  /**
   * @param store the substrate this index's entries live in
   * @param mapper the pinned mapper the entry codec encodes and decodes through
   * @param kind this index's own kind — {@code dispatch/<agentType>} (see {@link
   *     Kinds#dispatchIndex(AgentType)})
   */
  public DispatchIndex(Substrate store, ObjectMapper mapper, String kind) {
    Objects.requireNonNull(store, "store must not be null");
    Objects.requireNonNull(mapper, "mapper must not be null");
    Objects.requireNonNull(kind, "kind must not be null");
    this.entries = store.document(kind, codec(mapper));
  }

  /**
   * The entry {@code address} is currently recorded under, if any.
   *
   * @param address the call's coordinates
   * @return the recorded entry, or empty if this call has never been dispatched or its entry has
   *     since been deleted
   */
  public Optional<DispatchEntry> find(CallAddress address) {
    Objects.requireNonNull(address, "address must not be null");
    return entries.read(address.indexKey()).map(Versioned::value);
  }

  /**
   * Records the computation this call is now in flight under, replacing any earlier entry — a
   * granted approval whose tool then defers writes a second computation under the same key.
   *
   * @param address the call's coordinates
   * @param entry the computation this call is now in flight under
   */
  public void record(CallAddress address, DispatchEntry entry) {
    Objects.requireNonNull(address, "address must not be null");
    Objects.requireNonNull(entry, "entry must not be null");
    String key = address.indexKey();
    while (true) {
      long expected = entries.version(key).orElse(0L);
      try {
        entries.write(key, entry, expected);
        return;
      } catch (ConflictException _) {
        // another writer moved this call along; re-read and re-apply
      }
    }
  }

  /**
   * An op deleting this call's entry, for the fold batch; empty when there is nothing to delete —
   * the fold contributes a delete for every call, including calls that never went durable and have
   * no entry, so an absent key must not fail the batch.
   *
   * @param address the call's coordinates
   * @return the delete op, or empty if {@code address} has no recorded entry
   */
  public Optional<Substrate.Op> deleteOp(CallAddress address) {
    Objects.requireNonNull(address, "address must not be null");
    OptionalLong version = entries.version(address.indexKey());
    return version.isPresent()
        ? Optional.of(entries.deleteOp(address.indexKey(), version.getAsLong()))
        : Optional.empty();
  }

  private static Codec<DispatchEntry> codec(ObjectMapper mapper) {
    return new Codec<>() {
      @Override
      public byte[] encode(DispatchEntry value) {
        try {
          return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("undecodable dispatch entry", e);
        }
      }

      @Override
      public DispatchEntry decode(byte[] bytes) {
        try {
          return mapper.readValue(bytes, DispatchEntry.class);
        } catch (IOException e) {
          throw new IllegalArgumentException("undecodable dispatch entry", e);
        }
      }
    };
  }
}
