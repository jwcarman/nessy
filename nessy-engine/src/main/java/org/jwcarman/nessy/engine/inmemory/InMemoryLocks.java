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
package org.jwcarman.nessy.engine.inmemory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.jwcarman.nessy.spi.lock.Locks;

/**
 * Locks held in this process and nowhere else.
 *
 * <p>For a CLI, a test, or anything else with one JVM and no database. There is no kind and no
 * time-to-live because neither means anything here: a holder cannot die without releasing, since
 * the process exiting releases everything it held.
 *
 * <p><b>Striped rather than one lock per key.</b> A map keyed by agent id grows for as long as the
 * process runs and cannot be pruned without racing the callers it serves. A fixed array cannot
 * grow, allocates nothing after construction, and costs a hash.
 *
 * <p>What striping usually costs is false contention -- two unrelated keys landing on one lock, so
 * one blocks the other. That cost is not paid here: being refused is already an answer every caller
 * handles, so a collision means one attempt is skipped exactly as if a real holder had it. Nothing
 * waits, so nothing is delayed.
 */
public final class InMemoryLocks implements Locks {

  /** Enough that collisions are rare, small enough to be free. */
  private static final int DEFAULT_STRIPES = 64;

  private final List<Lock> stripes;

  public InMemoryLocks() {
    this(DEFAULT_STRIPES);
  }

  public InMemoryLocks(int stripes) {
    if (stripes <= 0) {
      throw new IllegalArgumentException("stripes must be positive");
    }
    this.stripes = IntStream.range(0, stripes).<Lock>mapToObj(_ -> new ReentrantLock()).toList();
  }

  @Override
  public <T> Attempt<T> tryWithLock(String key, Supplier<T> work) {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(work, "work must not be null");
    Lock stripe = stripes.get(Math.floorMod(key.hashCode(), stripes.size()));
    // tryLock rather than lock: refusing is the contract, and waiting is not.
    if (!stripe.tryLock()) {
      return new Attempt.Ignored<>();
    }
    try {
      return new Attempt.Ran<>(work.get());
    } finally {
      stripe.unlock();
    }
  }
}
