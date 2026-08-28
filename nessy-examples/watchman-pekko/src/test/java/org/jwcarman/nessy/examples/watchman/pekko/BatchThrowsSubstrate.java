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

import java.util.List;
import java.util.Optional;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * A {@link Substrate} whose {@code batch} always fails -- exactly what {@code Memory#remember}
 * calls underneath. Shared by {@link ToolWorkerTest} and {@link ToolCallActorTest}: both drive the
 * same "a throwing remember must never settle the call" shape, on the worker's run path and on
 * {@code ToolCallActor}'s denial path respectively.
 */
record BatchThrowsSubstrate(Substrate delegate) implements Substrate {

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
    throw new IllegalStateException("substrate unreachable");
  }

  @Override
  public CodecFactory codecs() {
    return delegate.codecs();
  }
}
