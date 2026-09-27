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
package org.jwcarman.nessy.backend.inmemory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.backend.lease.Attempt;
import org.jwcarman.nessy.backend.lease.LeaseKind;
import org.jwcarman.nessy.backend.lease.Leases;

/**
 * Leases held in this process and nowhere else, believed held for a time and taken over once it
 * passes.
 *
 * <p>For a CLI, a test, or anything else with one JVM and no database. A row per {@code (kind,
 * type, agent)} in a map plays the part {@code nessy_lease} plays for {@code JdbcLeases}: who holds
 * it, and until when. {@link ConcurrentHashMap#compute} is what makes taking one atomic -- reading
 * whether the current holder has expired and writing the new holder happen inside one call, so two
 * callers racing for the same key can never both conclude it is free.
 *
 * <p><b>Only the holder that took it may release it.</b> A holder whose lease was taken over from
 * it, because it was believed dead or merely slow, must not release the new holder's -- exactly the
 * hazard {@code JdbcLeases}'s {@code WHERE holder = ?} guards against, matched here by comparing
 * the release against the holder recorded in the entry at the moment of release rather than the one
 * this caller took.
 */
public final class InMemoryLeases implements Leases {

  private record Key(LeaseKind kind, AgentType type, AgentId agent) {}

  private record Holding(UUID holder, Instant expiresAt) {
    boolean expired(Instant now) {
      return now.isAfter(expiresAt);
    }
  }

  private final ConcurrentHashMap<Key, Holding> held = new ConcurrentHashMap<>();

  @Override
  public <T> Attempt<T> tryWithLease(
      LeaseKind kind, AgentType type, AgentId agent, Duration ttl, Supplier<T> work) {
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    Objects.requireNonNull(ttl, "ttl must not be null");
    if (ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("ttl must be positive, got " + ttl);
    }
    Objects.requireNonNull(work, "work must not be null");
    Key key = new Key(kind, type, agent);
    UUID holder = UUID.randomUUID();
    Instant now = Instant.now();
    Holding taken =
        held.compute(
            key,
            (_, existing) ->
                existing == null || existing.expired(now)
                    ? new Holding(holder, now.plus(ttl))
                    : existing);
    if (!holder.equals(taken.holder())) {
      return new Attempt.Ignored<>();
    }
    try {
      return new Attempt.Ran<>(work.get());
    } finally {
      // Only if this holder is still the one on record: a taken-over entry belongs to whoever
      // took it over now, and this release must not touch it.
      held.computeIfPresent(key, (_, current) -> holder.equals(current.holder()) ? null : current);
    }
  }
}
