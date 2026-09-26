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

package org.jwcarman.nessy.backend.inmemory;

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

@DisplayName("Locks held in this process")
class InMemoryLocksTest {

  private static final LockKind KIND = new LockKind("turn");
  private static final AgentType TYPE = new AgentType("chat");

  private final Locks locks = new InMemoryLocks();
  private final ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();

  private static AgentId agent() {
    return AgentId.random();
  }

  /**
   * Starts a caller that holds {@code (KIND, TYPE, agent)} under {@code withLock} until released.
   */
  private Future<Void> holderOf(AgentId agent, CountDownLatch holding, CountDownLatch release) {
    return callers.submit(
        () ->
            locks.withLock(
                KIND,
                TYPE,
                agent,
                () -> {
                  holding.countDown();
                  await().atMost(Duration.ofSeconds(10)).until(() -> release.getCount() == 0);
                  return null;
                }));
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

    Integer result = locks.withLock(KIND, TYPE, agent, counted::incrementAndGet);

    assertThat(result).isEqualTo(1);
    assertThat(counted).hasValue(1);
  }

  @Test
  @DisplayName("wait for a held lock rather than being refused, and run once it is released")
  void a_held_lock_is_waited_for_rather_than_refused() throws Exception {
    AgentId agent = agent();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    holderOf(agent, holding, release);
    holding.await();
    AtomicInteger counted = new AtomicInteger();

    Future<Integer> waiter =
        callers.submit(() -> locks.withLock(KIND, TYPE, agent, counted::incrementAndGet));
    // Long enough that a caller refused once, rather than waiting, would already have returned.
    await().pollDelay(Duration.ofMillis(100)).until(() -> true);
    assertThat(waiter.isDone()).as("still waiting, not refused").isFalse();
    assertThat(counted).hasValue(0);

    release.countDown();

    assertThat(waiter.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    assertThat(counted).hasValue(1);
  }

  @Test
  @DisplayName("are free again once the work returns, however it returns")
  void released_after_the_work() {
    AgentId agent = agent();
    AtomicInteger counted = new AtomicInteger();

    assertThatThrownBy(
            () ->
                locks.withLock(
                    KIND,
                    TYPE,
                    agent,
                    () -> {
                      throw new IllegalStateException("the work failed");
                    }))
        .isInstanceOf(IllegalStateException.class);

    Integer result = locks.withLock(KIND, TYPE, agent, counted::incrementAndGet);

    assertThat(result).isEqualTo(1);
    assertThat(counted).hasValue(1);
  }

  /**
   * There is no refusal left to prove: {@link Locks#withLock} always runs the work eventually. What
   * still matters is that "one at a time" is real -- nobody sees another caller's work in-flight --
   * so this counts the callers running concurrently instead of counting winners.
   */
  @Test
  @DisplayName("of many asking at once, never more than one runs at a time")
  void contenders_run_one_at_a_time() throws Exception {
    AgentId agent = agent();
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger concurrent = new AtomicInteger();
    AtomicInteger maxConcurrent = new AtomicInteger();
    AtomicInteger completed = new AtomicInteger();
    List<Future<Void>> outcomes =
        IntStream.range(0, 16)
            .mapToObj(
                _ ->
                    callers.<Void>submit(
                        () -> {
                          go.await();
                          return locks.withLock(
                              KIND,
                              TYPE,
                              agent,
                              () -> {
                                int now = concurrent.incrementAndGet();
                                maxConcurrent.updateAndGet(max -> Math.max(max, now));
                                await().pollDelay(Duration.ofMillis(20)).until(() -> true);
                                concurrent.decrementAndGet();
                                completed.incrementAndGet();
                                return null;
                              });
                        }))
            .toList();

    go.countDown();
    for (Future<Void> outcome : outcomes) {
      outcome.get(10, TimeUnit.SECONDS);
    }

    assertThat(maxConcurrent).as("never more than one holder at once").hasValue(1);
    assertThat(completed).as("every contender eventually ran").hasValue(16);
  }

  /**
   * A lock per {@code (kind, type, agent)}, not a fixed set of stripes shared by every agent: two
   * unrelated agents can no longer collide. Every one of them runs promptly, which would be
   * impossible under the old striping (64 stripes, 200 callers) without a false wait.
   */
  @Test
  @DisplayName("hold different agents independently, never colliding")
  void different_agents_never_collide() throws Exception {
    AgentId held = agent();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    holderOf(held, holding, release);
    holding.await();

    long ran =
        IntStream.range(0, 200)
            .filter(i -> locks.withLock(KIND, TYPE, agent(), () -> i) == i)
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
    holderOf(agent, holding, release);
    holding.await();
    AtomicInteger counted = new AtomicInteger();

    Integer result = locks.withLock(other, TYPE, agent, counted::incrementAndGet);

    assertThat(result).isEqualTo(1);
    assertThat(counted).hasValue(1);
    release.countDown();
  }
}
