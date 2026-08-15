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
package org.jwcarman.nessy.store.cassandra;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.awaitility.Awaitility;

/**
 * Fires {@code taskCount} virtual-thread tasks at the same instant, for the concurrency and
 * no-stutter-under-race tests {@link CassandraTranscript}'s LWT loop needs to prove.
 *
 * <p>Every task announces itself ready on a shared {@link CountDownLatch}, then parks on a second
 * one; the main thread polls the readiness latch with Awaitility (never {@code Thread.sleep} —
 * S2925) until every task has armed, then releases them all at once, maximizing the contention
 * window instead of hoping the JVM schedules a race.
 */
final class RacingAppenders<T> {

  private final int taskCount;
  private final IntTask<T> task;

  RacingAppenders(int taskCount, IntTask<T> task) {
    this.taskCount = taskCount;
    this.task = task;
  }

  /** Runs every task, releasing them together, and returns each result in task-index order. */
  List<T> runToCompletion() {
    CountDownLatch ready = new CountDownLatch(taskCount);
    CountDownLatch go = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<T>> futures =
          IntStream.range(0, taskCount)
              .mapToObj(index -> toCallable(index, ready, go))
              .map(executor::submit)
              .toList();
      Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> ready.getCount() == 0);
      go.countDown();
      return futures.stream().map(RacingAppenders::join).toList();
    }
  }

  private Callable<T> toCallable(int index, CountDownLatch ready, CountDownLatch go) {
    return () -> {
      ready.countDown();
      go.await();
      return task.run(index);
    };
  }

  private static <T> T join(Future<T> future) {
    try {
      return future.get();
    } catch (ExecutionException e) {
      throw new IllegalStateException("a racing appender failed", e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while awaiting a racing appender", e);
    }
  }

  @FunctionalInterface
  interface IntTask<T> {
    T run(int index) throws Exception;
  }
}
