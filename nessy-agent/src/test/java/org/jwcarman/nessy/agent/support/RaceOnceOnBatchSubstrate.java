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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * Simulates one lost race between a caller's read and its own {@link #batch}: the first call to
 * {@code batch} is preceded by a competitor's direct {@link #write} to {@code (kind, key)} at
 * whatever version is currently stored (supplied by the test as the competitor's payload), so any
 * op in the caller's batch that targets that same document under CAS genuinely conflicts and the
 * whole batch is rejected atomically — nothing in it applies, including any {@code AppendEntry} ops
 * riding alongside. Every later {@code batch} call goes straight through. Mirrors the {@code
 * RaceOnceOnWriteSubstrate} convention.
 */
public final class RaceOnceOnBatchSubstrate implements Substrate {

  private final Substrate delegate;
  private final String kind;
  private final String key;
  private final byte[] competitorPayload;
  private boolean raced;

  public RaceOnceOnBatchSubstrate(
      Substrate delegate, String kind, String key, byte[] competitorPayload) {
    this.delegate = Objects.requireNonNull(delegate);
    this.kind = Objects.requireNonNull(kind);
    this.key = Objects.requireNonNull(key);
    this.competitorPayload = Objects.requireNonNull(competitorPayload);
  }

  @Override
  public Optional<Document> read(String kind, String key) {
    return delegate.read(kind, key);
  }

  @Override
  public void write(String kind, String key, byte[] payload, long expectedVersion) {
    delegate.write(kind, key, payload, expectedVersion);
  }

  @Override
  public void delete(String kind, String key, long expectedVersion) {
    delegate.delete(kind, key, expectedVersion);
  }

  @Override
  public List<String> keys(String kind, int limit) {
    return delegate.keys(kind, limit);
  }

  @Override
  public void append(String kind, String key, long expectedSeq, byte[] payload) {
    delegate.append(kind, key, expectedSeq, payload);
  }

  @Override
  public List<Entry> entries(String kind, String key, long fromSeq) {
    return delegate.entries(kind, key, fromSeq);
  }

  @Override
  public void batch(List<Op> ops) {
    if (!raced) {
      raced = true;
      long currentVersion = delegate.read(kind, key).map(Document::version).orElse(0L);
      delegate.write(kind, key, competitorPayload, currentVersion); // someone else won first
    }
    delegate.batch(ops);
  }

  @Override
  public CodecFactory codecs() {
    return delegate.codecs();
  }
}
