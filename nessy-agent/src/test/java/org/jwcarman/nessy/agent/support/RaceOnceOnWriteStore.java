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
import org.jwcarman.nessy.spi.store.ScopedStore;

/**
 * Simulates one lost race on {@link #write}: the first call to write is preceded by a competitor's
 * write (supplied by the test) landing first at the very {@code expectedVersion} the caller is
 * targeting, so the delegate throws a genuine {@code ConflictException}; every later write goes
 * straight through. Mirrors the {@code RaceOnceOnAppendStore} convention.
 */
public final class RaceOnceOnWriteStore implements ScopedStore {

  private final ScopedStore delegate;
  private final String competitorPayload;
  private boolean raced;

  public RaceOnceOnWriteStore(ScopedStore delegate, String competitorPayload) {
    this.delegate = Objects.requireNonNull(delegate);
    this.competitorPayload = Objects.requireNonNull(competitorPayload);
  }

  @Override
  public Optional<Document> read(String kind, String key) {
    return delegate.read(kind, key);
  }

  @Override
  public void write(String kind, String key, String payload, long expectedVersion) {
    if (!raced) {
      raced = true;
      delegate.write(kind, key, competitorPayload, expectedVersion); // someone else won first
    }
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
  public void append(String kind, String key, long expectedSeq, String payload) {
    delegate.append(kind, key, expectedSeq, payload);
  }

  @Override
  public List<Entry> entries(String kind, String key, long fromSeq) {
    return delegate.entries(kind, key, fromSeq);
  }

  @Override
  public void batch(List<Op> ops) {
    delegate.batch(ops);
  }
}
