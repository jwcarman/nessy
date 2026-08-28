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
package org.jwcarman.nessy.examples.watchman.pekko;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.jwcarman.nessy.api.Identifiers;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Backlogs} over {@link Substrate}'s document door, kept under the {@code "backlog"} kind
 * and keyed by agent id — the same shape {@link StateSerializer} uses for {@link AgentState}, one
 * document per agent, rewritten under CAS.
 *
 * <p><b>Idempotence beats atomicity.</b> {@link #ingest} and {@link #taken} both
 * read-transform-write under the document's CAS version and retry on a {@link ConflictException}:
 * real contention on one agent's backlog is rare (a single {@code AgentActor} is this key's only
 * regular writer), but the retry loop is what makes correctness independent of that. Both methods
 * also skip the write entirely when the transform is a no-op — a re-{@link #taken} after a crash
 * sees the same content it would have written and returns for free.
 */
public final class SubstrateBacklogs<O> implements Backlogs<O> {

  private static final Logger LOG = LoggerFactory.getLogger(SubstrateBacklogs.class);
  private static final String KIND = "backlog";

  private final Substrate substrate;
  private final Coalescer<O> coalescer;
  private final ObjectMapper mapper;
  private final JavaType backlogType;

  public SubstrateBacklogs(Substrate substrate, Coalescer<O> coalescer, Class<O> observationType) {
    this.substrate = Objects.requireNonNull(substrate, "substrate must not be null");
    this.coalescer = Objects.requireNonNull(coalescer, "coalescer must not be null");
    Objects.requireNonNull(observationType, "observationType must not be null");
    this.mapper = StateSerializer.MAPPER;
    this.backlogType =
        mapper.getTypeFactory().constructParametricType(Backlog.class, observationType);
  }

  @Override
  public void ingest(String agentId, O observation, Instant receivedAt) {
    Backlog.Entry<O> arrival = new Backlog.Entry<>(Identifiers.next(), observation, receivedAt);
    update(agentId, current -> coalescer.ingest(current, arrival));
  }

  @Override
  public Optional<Taken<O>> next(String agentId) {
    return read(agentId).entries().stream()
        .findFirst()
        .map(entry -> new Taken<>(entry.id(), entry.observation()));
  }

  @Override
  public void taken(String agentId, String entryId) {
    update(agentId, current -> current.remove(entryId));
  }

  private Backlog<O> read(String agentId) {
    return substrate.read(KIND, agentId).map(this::deserialize).orElseGet(Backlog::empty);
  }

  /** Read, transform, write under CAS; retry on conflict; skip the write when nothing changed. */
  private void update(String agentId, UnaryOperator<Backlog<O>> transform) {
    while (true) {
      Optional<Substrate.Document> document = substrate.read(KIND, agentId);
      Backlog<O> current = document.map(this::deserialize).orElseGet(Backlog::empty);
      Backlog<O> next = transform.apply(current);
      if (next.equals(current)) {
        return;
      }
      long expectedVersion = document.map(Substrate.Document::version).orElse(0L);
      try {
        substrate.write(KIND, agentId, serialize(next), expectedVersion);
        return;
      } catch (ConflictException outdated) {
        // Someone else wrote first; loop around, re-read, and try again with the fresh version.
        LOG.debug("[watchman] backlog write conflict for {}, retrying: {}", agentId, outdated);
      }
    }
  }

  private Backlog<O> deserialize(Substrate.Document document) {
    try {
      return mapper.readValue(document.payload(), backlogType);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read a backlog", e);
    }
  }

  private byte[] serialize(Backlog<O> backlog) {
    try {
      return mapper.writeValueAsBytes(backlog);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write a backlog", e);
    }
  }
}
