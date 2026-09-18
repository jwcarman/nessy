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
package org.jwcarman.nessy.lease;

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
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("A lease")
class JdbcLeasesTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static DataSource database() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    return database;
  }

  private final Leases leases = new JdbcLeases(database());
  private final ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();

  /** A key nobody else in the shared database is using. */
  private final String key = UUID.randomUUID().toString();

  @AfterEach
  void stop() {
    callers.shutdownNow();
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
    Duration ttl = Duration.ofSeconds(30);
    Runnable failing =
        () -> {
          throw new IllegalStateException("the work failed");
        };
    assertThatThrownBy(() -> leases.tryRun("summary", key, ttl, failing))
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
