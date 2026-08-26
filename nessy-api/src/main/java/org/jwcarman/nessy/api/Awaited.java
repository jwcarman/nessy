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
package org.jwcarman.nessy.api;

import java.time.Duration;
import java.util.Objects;
import org.jwcarman.nessy.api.tool.ComputationCallback;

/**
 * The outcome of something that might have to wait.
 *
 * <p>Two arms, no third (durable spec, two-armed ruling): {@link Ready} is the answer in hand;
 * {@link Deferred} says the answer arrives through a durable computation that does not exist yet.
 * Deferring is a pure RETURN now (deferral-by-callback spec §1) — a tool says what to do once the
 * id exists and the plumbing creates it, folds it, commits, and only then runs the callback.
 * Nothing a tool can do makes the id exist early, so "deferred without parking" and "answered after
 * deferring" are no longer writable.
 *
 * @param <T> what the wait produces
 */
public sealed interface Awaited<T> {

  /** The wait finished in-process: {@code value} is the answer, in hand right now. */
  record Ready<T>(T value) implements Awaited<T> {}

  /**
   * The wait outlives this process.
   *
   * @param callback what to run once the computation exists — the only thing that tells the world
   *     where to answer
   * @param term how long the tool wants; REQUIRED, because the deferring party always knows what it
   *     wants and the harness never does (spec §5). The harness clips it to its own ceiling, and
   *     only the clipped {@code deadline} is ever shown to the callback.
   */
  record Deferred<T>(ComputationCallback callback, Duration term) implements Awaited<T> {
    public Deferred {
      Objects.requireNonNull(callback, "callback must not be null");
      Objects.requireNonNull(term, "term must not be null");
    }
  }

  /** {@link Ready#Ready(Object)} wrapping {@code value}. */
  static <T> Awaited<T> ready(T value) {
    return new Ready<>(value);
  }

  /** {@link Deferred}: what to do once the id exists, and for how long it is wanted. */
  static <T> Awaited<T> deferred(ComputationCallback callback, Duration term) {
    return new Deferred<>(callback, term);
  }
}
