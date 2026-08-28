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
import java.util.function.Supplier;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * A {@link Substrate} whose {@link #write} throws a caller-supplied {@link RuntimeException} for
 * every document under {@code kind}, delegating everything else — stands in for a genuinely broken
 * store beneath a caller that retries {@link org.jwcarman.nessy.spi.substrate.ConflictException}
 * but has no answer for anything else.
 */
public final class ThrowingOnWriteSubstrate implements Substrate {

  private final Substrate delegate;
  private final String kind;
  private final Supplier<? extends RuntimeException> failure;

  public ThrowingOnWriteSubstrate(
      Substrate delegate, String kind, Supplier<? extends RuntimeException> failure) {
    this.delegate = Objects.requireNonNull(delegate);
    this.kind = Objects.requireNonNull(kind);
    this.failure = Objects.requireNonNull(failure);
  }

  @Override
  public Optional<Document> read(String kind, String key) {
    return delegate.read(kind, key);
  }

  @Override
  public void write(String kind, String key, byte[] payload, long expectedVersion) {
    if (this.kind.equals(kind)) {
      throw failure.get();
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
