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
 * A plain pass-through {@link Substrate} over some delegate — never an {@code InMemorySubstrate}
 * itself, regardless of what it wraps. Stands in for "some durable third-party substrate" in tests
 * that only care about the {@code instanceof InMemorySubstrate} distinction {@link
 * org.jwcarman.nessy.agent.host.HarnessConfig#finish()}'s durability-mismatch guard draws — it
 * still delegates every real operation to an in-memory store underneath, so a harness built over it
 * works exactly as it would over a bare {@code InMemorySubstrate}.
 */
public final class DelegatingSubstrate implements Substrate {

  private final Substrate delegate;

  public DelegatingSubstrate(Substrate delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
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
  public long head(String kind, String key) {
    return delegate.head(kind, key);
  }

  @Override
  public void batch(List<Op> ops) {
    delegate.batch(ops);
  }

  @Override
  public CodecFactory codecs() {
    return delegate.codecs();
  }
}
