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

package org.jwcarman.nessy.engine.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.lock.Locks;
import org.jwcarman.nessy.spi.lock.Locks.Attempt;

@DisplayName("Locks held in this process")
class InMemoryLocksTest {

  private final Locks locks = new InMemoryLocks();
  private final ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();

  private static boolean ran(Attempt<?> attempt) {
    return attempt instanceof Attempt.Ran<?>;
  }

  @AfterEach
  void stop() {
    callers.shutdownNow();
  }

  @Test
  @DisplayName("run the work and hand back what it produced")
  void the_first_caller_runs() {
    AtomicInteger counted = new AtomicInteger();

    assertThat(locks.tryWithLock("a", counted::incrementAndGet)).isEqualTo(new Attempt.Ran<>(1));
    assertThat(counted).hasValue(1);
  }

  /** The reason this is not an Optional: work that produces nothing still ran. */
  @Test
  @DisplayName("say a null result ran, rather than saying nothing ran")
  void nothing_produced_is_not_nothing_run() {
    AtomicInteger counted = new AtomicInteger();

    // A block lambda that returns nothing is a Runnable and only a Runnable, which is how the
    // overload is picked: a method reference that happens to return a value picks the other one.
    Attempt<Void> attempt =
        locks.tryWithLock(
            "a",
            () -> {
              counted.incrementAndGet();
            });

    assertThat(attempt).isEqualTo(new Attempt.Ran<Void>(null));
    assertThat(counted).hasValue(1);
  }

  @Test
  @DisplayName("refuse anyone else while held, at once and without waiting")
  void a_held_lock_is_refused() throws Exception {
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<Attempt<Void>> holder =
        callers.submit(
            () ->
                locks.tryWithLock(
                    "a",
                    () -> {
                      holding.countDown();
                      await().atMost(Duration.ofSeconds(10)).until(() -> release.getCount() == 0);
                    }));
    holding.await();
    AtomicInteger counted = new AtomicInteger();

    Attempt<Integer> refused = locks.tryWithLock("a", counted::incrementAndGet);

    assertThat(refused).isEqualTo(new Attempt.Ignored<Integer>());
    assertThat(counted).hasValue(0);
    release.countDown();
    assertThat(ran(holder.get())).isTrue();
  }

  @Test
  @DisplayName("are free again once the work returns, however it returns")
  void released_after_the_work() {
    AtomicInteger counted = new AtomicInteger();

    assertThatThrownBy(
            () ->
                locks.tryWithLock(
                    "a",
                    () -> {
                      throw new IllegalStateException("the work failed");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(ran(locks.tryWithLock("a", counted::incrementAndGet))).isTrue();
    assertThat(counted).hasValue(1);
  }

  @Test
  @DisplayName("of many asking at once, exactly one runs")
  void a_race_has_one_winner() throws Exception {
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger counted = new AtomicInteger();
    List<Future<Attempt<Void>>> outcomes =
        IntStream.range(0, 16)
            .mapToObj(
                _ ->
                    callers.submit(
                        () -> {
                          go.await();
                          return locks.tryWithLock(
                              "a",
                              () -> {
                                counted.incrementAndGet();
                                await().pollDelay(Duration.ofMillis(300)).until(() -> true);
                              });
                        }))
            .toList();

    go.countDown();

    long winners = 0;
    for (Future<Attempt<Void>> outcome : outcomes) {
      if (ran(outcome.get())) {
        winners++;
      }
    }
    assertThat(winners).isEqualTo(1);
    assertThat(counted).hasValue(1);
  }

  /**
   * Distinct keys do not block each other -- which striping makes a statement about likelihood
   * rather than certainty, so this asks for one held key not to stop a sweep of many others.
   */
  @Test
  @DisplayName("hold different keys independently")
  void different_keys_do_not_block_each_other() throws Exception {
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    callers.submit(
        () ->
            locks.tryWithLock(
                "held",
                () -> {
                  holding.countDown();
                  await().atMost(Duration.ofSeconds(10)).until(() -> release.getCount() == 0);
                }));
    holding.await();

    long ran =
        IntStream.range(0, 200).filter(i -> ran(locks.tryWithLock("other-" + i, () -> i))).count();

    assertThat(ran).as("at most one stripe is taken").isGreaterThanOrEqualTo(190L);
    release.countDown();
  }

  @Test
  @DisplayName("refuse a size with no locks in it, and work with only one")
  void the_stripe_count_is_checked_where_it_is_given() {
    assertThatThrownBy(() -> new InMemoryLocks(0)).isInstanceOf(IllegalArgumentException.class);
    assertThat(ran(new InMemoryLocks(1).tryWithLock("a", () -> "ran"))).isTrue();
  }
}
