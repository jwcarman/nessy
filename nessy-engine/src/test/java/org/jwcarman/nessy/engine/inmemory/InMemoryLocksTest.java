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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.backend.lock.LockKind;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.lock.Locks.Attempt;

@DisplayName("Locks held in this process")
class InMemoryLocksTest {

  private static final LockKind KIND = new LockKind("turn");
  private static final AgentType TYPE = new AgentType("chat");

  private final Locks locks = new InMemoryLocks();
  private final ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();

  private static boolean ran(Attempt<?> attempt) {
    return attempt instanceof Attempt.Ran<?>;
  }

  private static AgentId agent() {
    return AgentId.random();
  }

  @AfterEach
  void stop() {
    callers.shutdownNow();
  }

  @Test
  @DisplayName("run the work and hand back what it produced")
  void the_first_caller_runs() {
    AtomicInteger counted = new AtomicInteger();
    AgentId agent = agent();

    assertThat(locks.tryWithLock(KIND, TYPE, agent, counted::incrementAndGet))
        .isEqualTo(new Attempt.Ran<>(1));
    assertThat(counted).hasValue(1);
  }

  /** The reason this is not an Optional: work that produces nothing still ran. */
  @Test
  @DisplayName("say a null result ran, rather than saying nothing ran")
  void nothing_produced_is_not_nothing_run() {
    AtomicInteger counted = new AtomicInteger();
    AgentId agent = agent();

    // A block lambda that returns nothing is a Runnable and only a Runnable, which is how the
    // overload is picked: a method reference that happens to return a value picks the other one.
    Attempt<Void> attempt =
        locks.tryWithLock(
            KIND,
            TYPE,
            agent,
            () -> {
              counted.incrementAndGet();
            });

    assertThat(attempt).isEqualTo(new Attempt.Ran<Void>(null));
    assertThat(counted).hasValue(1);
  }

  @Test
  @DisplayName("refuse anyone else while held, at once and without waiting")
  void a_held_lock_is_refused() throws Exception {
    AgentId agent = agent();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<Attempt<Void>> holder =
        callers.submit(
            () ->
                locks.tryWithLock(
                    KIND,
                    TYPE,
                    agent,
                    () -> {
                      holding.countDown();
                      await().atMost(Duration.ofSeconds(10)).until(() -> release.getCount() == 0);
                    }));
    holding.await();
    AtomicInteger counted = new AtomicInteger();

    Attempt<Integer> refused = locks.tryWithLock(KIND, TYPE, agent, counted::incrementAndGet);

    assertThat(refused).isEqualTo(new Attempt.Ignored<Integer>());
    assertThat(counted).hasValue(0);
    release.countDown();
    assertThat(ran(holder.get())).isTrue();
  }

  @Test
  @DisplayName("are free again once the work returns, however it returns")
  void released_after_the_work() {
    AgentId agent = agent();
    AtomicInteger counted = new AtomicInteger();

    assertThatThrownBy(
            () ->
                locks.tryWithLock(
                    KIND,
                    TYPE,
                    agent,
                    () -> {
                      throw new IllegalStateException("the work failed");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(ran(locks.tryWithLock(KIND, TYPE, agent, counted::incrementAndGet))).isTrue();
    assertThat(counted).hasValue(1);
  }

  @Test
  @DisplayName("of many asking at once, exactly one runs")
  void a_race_has_one_winner() throws Exception {
    AgentId agent = agent();
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
                              KIND,
                              TYPE,
                              agent,
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
   * A lock per {@code (kind, type, agent)}, not a fixed set of stripes shared by every agent: two
   * unrelated agents can no longer collide and refuse each other. Every one of them runs, which
   * would be impossible under the old striping (64 stripes, 200 callers) without a false refusal.
   */
  @Test
  @DisplayName("hold different agents independently, never colliding")
  void different_agents_never_collide() throws Exception {
    AgentId held = agent();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    callers.submit(
        () ->
            locks.tryWithLock(
                KIND,
                TYPE,
                held,
                () -> {
                  holding.countDown();
                  await().atMost(Duration.ofSeconds(10)).until(() -> release.getCount() == 0);
                }));
    holding.await();

    long ran =
        IntStream.range(0, 200)
            .filter(i -> ran(locks.tryWithLock(KIND, TYPE, agent(), () -> i)))
            .count();

    assertThat(ran).as("every unrelated agent runs").isEqualTo(200L);
    release.countDown();
  }

  /** The same agent under two different kinds is two locks, not one. */
  @Test
  @DisplayName("hold different kinds of the same agent independently")
  void different_kinds_of_the_same_agent_do_not_collide() throws Exception {
    AgentId agent = agent();
    LockKind other = new LockKind("summary");
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    callers.submit(
        () ->
            locks.tryWithLock(
                KIND,
                TYPE,
                agent,
                () -> {
                  holding.countDown();
                  await().atMost(Duration.ofSeconds(10)).until(() -> release.getCount() == 0);
                }));
    holding.await();
    AtomicInteger counted = new AtomicInteger();

    assertThat(ran(locks.tryWithLock(other, TYPE, agent, counted::incrementAndGet))).isTrue();
    assertThat(counted).hasValue(1);
    release.countDown();
  }

  @Test
  @DisplayName("wait for a held lock rather than being refused")
  void with_lock_waits_rather_than_giving_up() throws Exception {
    AgentId agent = agent();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    callers.submit(
        () ->
            locks.tryWithLock(
                KIND,
                TYPE,
                agent,
                () -> {
                  holding.countDown();
                  await().atMost(Duration.ofSeconds(10)).until(() -> release.getCount() == 0);
                }));
    holding.await();

    Future<String> waiter = callers.submit(() -> locks.withLock(KIND, TYPE, agent, () -> "ran"));
    // Long enough that a caller refused once, rather than waiting, would already have returned.
    await().pollDelay(Duration.ofMillis(100)).until(() -> true);
    assertThat(waiter.isDone()).as("still waiting, not refused").isFalse();

    release.countDown();
    assertThat(waiter.get(10, TimeUnit.SECONDS)).isEqualTo("ran");
  }
}
