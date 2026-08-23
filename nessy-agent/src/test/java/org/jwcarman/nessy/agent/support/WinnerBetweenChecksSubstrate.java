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
import org.jwcarman.nessy.spi.substrate.CodecFactory;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * Pins the interleaving behind the 2026-08-23 CI flake: the first read of the watched key under the
 * watched kind computes its result from the delegate FIRST (so the caller sees the pre-transfer
 * world), then fires the winner's transfer before returning — the caller's very next observation
 * sees the post-transfer world. A losing completer whose winner lands between its two presence
 * checks must converge to {@code ALREADY_DONE}, never mint a second {@code TRANSFERRED}.
 */
public final class WinnerBetweenChecksSubstrate implements Substrate {

  private final Substrate delegate;
  private final String watchedKind;
  private final String watchedKey;
  private final Runnable winner;
  private boolean fired;

  public WinnerBetweenChecksSubstrate(
      Substrate delegate, String watchedKind, String watchedKey, Runnable winner) {
    this.delegate = Objects.requireNonNull(delegate);
    this.watchedKind = Objects.requireNonNull(watchedKind);
    this.watchedKey = Objects.requireNonNull(watchedKey);
    this.winner = Objects.requireNonNull(winner);
  }

  @Override
  public Optional<Document> read(String kind, String key) {
    Optional<Document> staleView = delegate.read(kind, key);
    if (!fired && watchedKind.equals(kind) && watchedKey.equals(key)) {
      fired = true;
      winner.run();
    }
    return staleView;
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
    delegate.batch(ops);
  }

  @Override
  public CodecFactory codecs() {
    return delegate.codecs();
  }
}
