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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.backend.lease.Attempt;
import org.jwcarman.nessy.backend.lease.LeaseKind;
import org.jwcarman.nessy.backend.lease.Leases;

@DisplayName("A lease held in this process")
class InMemoryLeasesTest {

  private static final LeaseKind SUMMARY = new LeaseKind("summary");
  private static final LeaseKind ENRICHMENT = new LeaseKind("enrichment");
  private static final AgentType TYPE = new AgentType("chat");
  private static final Duration TTL = Duration.ofSeconds(30);

  private final Leases leases = new InMemoryLeases();
  private final ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();

  private static AgentId agent() {
    return AgentId.random();
  }

  /** Whether the work ran, which is the whole of what a caller needs from an attempt. */
  private static boolean ran(Attempt<?> attempt) {
    return attempt instanceof Attempt.Ran<?>;
  }

  private Future<Attempt<Void>> held(
      AgentId agent, Duration ttl, CountDownLatch holding, CountDownLatch release) {
    return callers.submit(
        () ->
            leases.tryWithLease(
                SUMMARY,
                TYPE,
                agent,
                ttl,
                () -> {
                  holding.countDown();
                  await().atMost(Duration.ofSeconds(10)).until(() -> release.getCount() == 0);
                }));
  }

  @AfterEach
  void stop() {
    callers.shutdownNow();
  }

  @Test
  @DisplayName("is taken by the first to ask, who is told so, and does the work")
  void the_first_caller_runs() {
    AgentId agent = agent();
    AtomicInteger counted = new AtomicInteger();

    Attempt<Integer> attempt =
        leases.tryWithLease(SUMMARY, TYPE, agent, TTL, counted::incrementAndGet);

    assertThat(attempt).isEqualTo(new Attempt.Ran<>(1));
    assertThat(counted).hasValue(1);
  }

  @Test
  @DisplayName("hands back what the work produced, null included")
  void the_work_answers_through_the_attempt() {
    AgentId agent = agent();

    assertThat(leases.tryWithLease(SUMMARY, TYPE, agent, TTL, () -> "summarised"))
        .isEqualTo(new Attempt.Ran<>("summarised"));
    // The reason this is not an Optional: work that produces nothing still ran.
    assertThat(leases.tryWithLease(SUMMARY, TYPE, agent, TTL, () -> null))
        .isEqualTo(new Attempt.Ran<>(null));
  }

  @Test
  @DisplayName("is refused to anyone else while it is held, who does nothing and does not wait")
  void a_held_lease_is_refused() throws Exception {
    AgentId agent = agent();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<Attempt<Void>> holder = held(agent, TTL, holding, release);
    holding.await();
    AtomicInteger counted = new AtomicInteger();

    Attempt<Integer> refused =
        leases.tryWithLease(SUMMARY, TYPE, agent, TTL, counted::incrementAndGet);

    assertThat(refused).as("refused, at once").isEqualTo(new Attempt.Ignored<Integer>());
    assertThat(counted).hasValue(0);
    release.countDown();
    assertThat(ran(holder.get())).isTrue();
  }

  @Test
  @DisplayName("is free again once the holder's work returns, however it returns")
  void released_after_the_work() {
    AgentId agent = agent();
    AtomicInteger counted = new AtomicInteger();

    assertThatThrownBy(
            () ->
                leases.tryWithLease(
                    SUMMARY,
                    TYPE,
                    agent,
                    TTL,
                    () -> {
                      throw new IllegalStateException("the work failed");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(ran(leases.tryWithLease(SUMMARY, TYPE, agent, TTL, counted::incrementAndGet)))
        .isTrue();
    assertThat(counted).hasValue(1);
  }

  @Test
  @DisplayName("is taken over once a holder that never released it has expired")
  void an_expired_lease_is_taken_over() throws Exception {
    // A holder that is still running when its lease runs out: a slow one, or a dead one.
    AgentId agent = agent();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    held(agent, Duration.ofMillis(200), holding, release);
    holding.await();
    AtomicInteger counted = new AtomicInteger();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(
                        ran(
                            leases.tryWithLease(
                                SUMMARY, TYPE, agent, TTL, counted::incrementAndGet)))
                    .isTrue());

    assertThat(counted).hasValue(1);
    release.countDown();
  }

  @Test
  @DisplayName(
      "a release once the lease has been taken over changes nothing: the new holder keeps it")
  void a_release_after_a_takeover_changes_nothing() throws Exception {
    AgentId agent = agent();
    CountDownLatch originalHolding = new CountDownLatch(1);
    CountDownLatch letOriginalFinish = new CountDownLatch(1);
    Future<Attempt<Void>> original =
        held(agent, Duration.ofMillis(200), originalHolding, letOriginalFinish);
    originalHolding.await();
    // Past the original holder's ttl, so the entry below is eligible to be taken over.
    await().pollDelay(Duration.ofMillis(300)).until(() -> true);

    // The new holder keeps holding it, so the original's later release has something live to
    // threaten.
    CountDownLatch newHolding = new CountDownLatch(1);
    CountDownLatch letNewFinish = new CountDownLatch(1);
    Future<Attempt<Void>> takeover = held(agent, TTL, newHolding, letNewFinish);
    newHolding.await();

    // The original holder now finishes and tries to release a lease it no longer holds.
    letOriginalFinish.countDown();
    Attempt<Void> outcome = original.get();

    assertThat(ran(outcome)).as("the original holder's own work still ran and returned").isTrue();
    // The new holder is still holding: the lost holder's release did not touch its entry.
    assertThat(ran(leases.tryWithLease(SUMMARY, TYPE, agent, TTL, () -> "refused, still held")))
        .as("the current holder's lease survived the other holder's release")
        .isFalse();

    letNewFinish.countDown();
    assertThat(ran(takeover.get())).isTrue();
  }

  @Test
  @DisplayName("keys are scoped by kind: the same agent under another kind is another lease")
  void kinds_do_not_collide() {
    AgentId agent = agent();
    AtomicInteger counted = new AtomicInteger();

    Attempt<Boolean> outer =
        leases.tryWithLease(
            SUMMARY,
            TYPE,
            agent,
            TTL,
            () -> ran(leases.tryWithLease(ENRICHMENT, TYPE, agent, TTL, counted::incrementAndGet)));

    assertThat(outer).isEqualTo(new Attempt.Ran<>(true));
    assertThat(counted).hasValue(1);
  }

  @Test
  @DisplayName("of many asking at once, exactly one runs")
  void a_race_has_one_winner() throws Exception {
    AgentId agent = agent();
    int callerCount = 16;
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger counted = new AtomicInteger();
    List<Future<Attempt<Void>>> outcomes =
        IntStream.range(0, callerCount)
            .mapToObj(
                _ ->
                    callers.submit(
                        () -> {
                          go.await();
                          return leases.tryWithLease(
                              SUMMARY,
                              TYPE,
                              agent,
                              TTL,
                              () -> {
                                counted.incrementAndGet();
                                // Hold it long enough for the others to be refused.
                                await().pollDelay(Duration.ofMillis(300)).until(() -> true);
                              });
                        }))
            .toList();

    go.countDown();

    int winners = 0;
    for (Future<Attempt<Void>> outcome : outcomes) {
      if (ran(outcome.get())) {
        winners++;
      }
    }
    assertThat(winners).isEqualTo(1);
    assertThat(counted).hasValue(1);
  }

  @Test
  @DisplayName("a ttl with no life in it is refused at the call")
  void a_ttl_that_is_not_positive_is_refused() {
    AgentId agent = agent();

    assertThatThrownBy(
            () -> leases.tryWithLease(SUMMARY, TYPE, agent, Duration.ZERO, () -> "never runs"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
