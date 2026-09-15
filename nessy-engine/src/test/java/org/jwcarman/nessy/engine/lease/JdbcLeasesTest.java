package org.jwcarman.nessy.engine.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Leases;
import org.jwcarman.nessy.engine.EngineUnderTest;
import org.jwcarman.nessy.spi.inference.InferenceResult;

@DisplayName("A lease")
class JdbcLeasesTest {

  private final EngineUnderTest engine =
      new EngineUnderTest((request, narrator) -> new InferenceResult.Refusal("unused"));
  private final Leases leases = engine.harnesses().leases();
  private final ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();

  /** A key nobody else in the shared database is using. */
  private final String key = UUID.randomUUID().toString();

  @AfterEach
  void stop() {
    callers.shutdownNow();
    engine.close();
  }

  @Test
  @DisplayName("is taken by the first to ask, who is told so, and does the work")
  void the_first_caller_runs() {
    AtomicInteger ran = new AtomicInteger();
    assertThat(leases.tryRun("summary", key, Duration.ofSeconds(30), ran::incrementAndGet))
        .isTrue();
    assertThat(ran).hasValue(1);
  }

  @Test
  @DisplayName("is refused to anyone else while it is held, who does nothing and does not wait")
  void a_held_lease_is_refused() throws Exception {
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<Boolean> holder =
        callers.submit(
            () ->
                leases.tryRun(
                    "summary",
                    key,
                    Duration.ofSeconds(30),
                    () -> {
                      holding.countDown();
                      await().atMost(Duration.ofSeconds(10)).until(() -> release.getCount() == 0);
                    }));
    holding.await();

    AtomicInteger ran = new AtomicInteger();
    assertThat(leases.tryRun("summary", key, Duration.ofSeconds(30), ran::incrementAndGet))
        .as("refused, at once")
        .isFalse();
    assertThat(ran).hasValue(0);

    release.countDown();
    assertThat(holder.get()).isTrue();
  }

  @Test
  @DisplayName("is free again once the holder's work returns, however it returns")
  void released_after_the_work() {
    assertThatThrownBy(
            () ->
                leases.tryRun(
                    "summary",
                    key,
                    Duration.ofSeconds(30),
                    () -> {
                      throw new IllegalStateException("the work failed");
                    }))
        .isInstanceOf(IllegalStateException.class);

    AtomicInteger ran = new AtomicInteger();
    assertThat(leases.tryRun("summary", key, Duration.ofSeconds(30), ran::incrementAndGet))
        .isTrue();
    assertThat(ran).hasValue(1);
  }

  @Test
  @DisplayName("is taken over once a holder that never released it has expired")
  void an_expired_lease_is_taken_over() throws Exception {
    // A holder that is still running when its lease runs out: a slow one, or a dead one.
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    callers.submit(
        () ->
            leases.tryRun(
                "summary",
                key,
                Duration.ofMillis(500),
                () -> {
                  holding.countDown();
                  await().atMost(Duration.ofSeconds(10)).until(() -> release.getCount() == 0);
                }));
    holding.await();

    AtomicInteger ran = new AtomicInteger();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(
                        leases.tryRun("summary", key, Duration.ofSeconds(30), ran::incrementAndGet))
                    .isTrue());
    assertThat(ran).hasValue(1);
    release.countDown();
  }

  @Test
  @DisplayName("keys are scoped by kind: the same key under another kind is another lease")
  void kinds_do_not_collide() {
    CountDownLatch inside = new CountDownLatch(1);
    AtomicInteger ran = new AtomicInteger();
    leases.tryRun(
        "summary",
        key,
        Duration.ofSeconds(30),
        () -> {
          assertThat(leases.tryRun("enrichment", key, Duration.ofSeconds(30), ran::incrementAndGet))
              .isTrue();
          inside.countDown();
        });
    assertThat(inside.getCount()).isZero();
    assertThat(ran).hasValue(1);
  }

  @Test
  @DisplayName("of many asking at once, exactly one runs")
  void a_race_has_one_winner() throws Exception {
    int callerCount = 16;
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger ran = new AtomicInteger();
    List<Future<Boolean>> outcomes =
        IntStream.range(0, callerCount)
            .mapToObj(
                i ->
                    callers.submit(
                        () -> {
                          go.await();
                          return leases.tryRun(
                              "summary",
                              key,
                              Duration.ofSeconds(30),
                              () -> {
                                ran.incrementAndGet();
                                // Hold it long enough for the others to be refused.
                                await().pollDelay(Duration.ofMillis(300)).until(() -> true);
                              });
                        }))
            .toList();
    go.countDown();
    int winners = 0;
    for (Future<Boolean> outcome : outcomes) {
      if (outcome.get()) {
        winners++;
      }
    }
    assertThat(winners).isEqualTo(1);
    assertThat(ran).hasValue(1);
  }

  @Test
  void a_ttl_that_is_not_positive_is_refused() {
    assertThatThrownBy(() -> leases.tryRun("summary", key, Duration.ZERO, () -> {}))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
