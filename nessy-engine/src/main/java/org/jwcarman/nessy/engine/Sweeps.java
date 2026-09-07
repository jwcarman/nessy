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
package org.jwcarman.nessy.engine;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A periodic job on a virtual thread, where an actor scheduler used to be.
 *
 * <p><b>A sweep that throws must not kill the loop.</b> A scheduled task that dies takes every
 * future run with it, and the symptom -- deadlines that quietly stop firing hours later -- looks
 * like anything except a swallowed exception. So every run is guarded and the loop continues.
 *
 * <p><b>The work decides when it comes back.</b> A fixed cadence cannot express what {@link
 * EffectPoller} needs -- {@link PollSchedule#next(int)} returns the floor the moment a pass finds
 * work and an exponentially backed-off, jittered delay the moment one does not -- so {@code work}
 * itself returns the wait before its next run rather than this class imposing one. A fixed sweep is
 * still one line: {@code new Sweeps(() -> { work(); return INTERVAL; })}.
 *
 * <p>No leader election and no singleton: claiming is {@code FOR UPDATE SKIP LOCKED}, so every node
 * may run one of these at once.
 */
final class Sweeps implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(Sweeps.class);

  /**
   * What a throwing {@code work} waits before trying again. {@code work} normally says how long to
   * wait; a sweep that threw before returning one gets this instead, so a permanently broken sweep
   * backs off rather than spinning a core at 100%.
   */
  private static final Duration FAILURE_BACKOFF = Duration.ofMillis(100);

  private final Supplier<Duration> work;
  private final AtomicBoolean running = new AtomicBoolean();
  private final ExecutorService threads =
      Executors.newSingleThreadExecutor(Thread.ofVirtual().name("nessy-sweep").factory());

  Sweeps(Supplier<Duration> work) {
    this.work = Objects.requireNonNull(work, "work must not be null");
  }

  void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    threads.execute(this::loop);
  }

  private void loop() {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      Duration next;
      try {
        next = work.get();
      } catch (RuntimeException failure) {
        LOG.error("a sweep failed; the loop continues", failure);
        next = FAILURE_BACKOFF;
      }
      try {
        TimeUnit.NANOSECONDS.sleep(next.toNanos());
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public void close() {
    running.set(false);
    threads.shutdownNow();
  }
}
