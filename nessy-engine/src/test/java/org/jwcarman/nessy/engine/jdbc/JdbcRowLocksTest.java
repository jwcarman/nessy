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
package org.jwcarman.nessy.engine.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.backend.lock.LockKind;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Exclusion that is exact: a caller waits for it, and never guesses whether it was granted. */
@Tag("container")
@DisplayName("A row lock in a database")
class JdbcRowLocksTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static final AgentType TYPE = new AgentType("chat");
  private static final LockKind KIND = new LockKind("turn");

  private static DataSource database() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    return database;
  }

  private final DataSource dataSource = database();
  private final Locks locks = new JdbcRowLocks(dataSource);

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting for a latch", e);
    }
  }

  /** Starts a thread that holds (kind, type, agent) under {@code withLock} until told to let go. */
  private Thread holderOf(
      LockKind kind, AgentId agent, CountDownLatch holding, CountDownLatch release) {
    Thread holder =
        new Thread(
            () ->
                locks.withLock(
                    kind,
                    TYPE,
                    agent,
                    () -> {
                      holding.countDown();
                      await(release);
                      return null;
                    }));
    holder.start();
    return holder;
  }

  @Test
  @DisplayName("a lock nobody holds can be taken at once")
  void an_unheld_lock_can_be_taken() {
    String result = locks.withLock(KIND, TYPE, AgentId.random(), () -> "ran");

    assertThat(result).isEqualTo("ran");
  }

  @Test
  @DisplayName("a different kind on the same agent is not excluded")
  void a_different_kind_on_the_same_agent_is_not_excluded() throws InterruptedException {
    AgentId agent = AgentId.random();
    LockKind otherKind = new LockKind("summary");
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder = holderOf(KIND, agent, holding, release);
    holding.await();

    String result = locks.withLock(otherKind, TYPE, agent, () -> "ran");

    release.countDown();
    holder.join();

    assertThat(result).isEqualTo("ran");
  }

  @Test
  @DisplayName("the same kind on a different agent is not excluded")
  void the_same_kind_on_a_different_agent_is_not_excluded() throws InterruptedException {
    AgentId held = AgentId.random();
    AgentId other = AgentId.random();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder = holderOf(KIND, held, holding, release);
    holding.await();

    String result = locks.withLock(KIND, TYPE, other, () -> "ran");

    release.countDown();
    holder.join();

    assertThat(result).isEqualTo("ran");
  }

  @Test
  @DisplayName("withLock waits for the holder rather than refusing, and runs once it commits")
  void with_lock_waits_for_the_holder() throws Exception {
    AgentId agent = AgentId.random();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch holderFinished = new CountDownLatch(1);
    Thread holder =
        new Thread(
            () -> {
              locks.withLock(
                  KIND,
                  TYPE,
                  agent,
                  () -> {
                    holding.countDown();
                    await(release);
                    // Inside the work, so it is set BEFORE the commit that releases the row. The
                    // waiter wakes on that commit, so a signal raised after withLock returned
                    // would race it -- the waiter can be running before the holder's next line.
                    holderFinished.countDown();
                    return null;
                  });
            });
    holder.start();
    holding.await();

    CompletableFuture<Boolean> waiter =
        CompletableFuture.supplyAsync(
            () -> locks.withLock(KIND, TYPE, agent, () -> holderFinished.getCount() == 0L));

    assertThat(waiter.isDone()).as("nothing to run until the holder lets go").isFalse();
    Thread.sleep(200);
    assertThat(waiter.isDone()).as("still waiting behind the holder").isFalse();

    release.countDown();
    holder.join();

    assertThat(waiter.get(5, TimeUnit.SECONDS))
        .as("only ran once the holder's transaction had already committed")
        .isTrue();
  }

  @Test
  @DisplayName("work that throws rolls back its writes and does not leave the lock held")
  void work_that_throws_rolls_back_and_releases() {
    AgentId agent = AgentId.random();
    JdbcClient jdbc = JdbcClient.create(dataSource);
    RuntimeException boom = new RuntimeException("boom");

    assertThatThrownBy(
            () ->
                locks.withLock(
                    KIND,
                    TYPE,
                    agent,
                    () -> {
                      jdbc.sql("INSERT INTO nessy_agent (agent_type, agent_id) VALUES (?, ?)")
                          .params(TYPE.value(), agent.value())
                          .update();
                      throw boom;
                    }))
        .isSameAs(boom);

    Long rows =
        jdbc.sql("SELECT count(*) FROM nessy_agent WHERE agent_type = ? AND agent_id = ?")
            .params(TYPE.value(), agent.value())
            .query(Long.class)
            .single();
    assertThat(rows).as("the insert rolled back with the transaction that opened it").isZero();

    String afterThrow = locks.withLock(KIND, TYPE, agent, () -> "ran");
    assertThat(afterThrow).as("the exception did not leave the lock held").isEqualTo("ran");
  }

  @Nested
  @DisplayName("the two-argument constructor")
  class Given_an_explicit_transaction_manager {

    @Test
    @DisplayName("takes the lock the same way as the one built from a bare data source")
    void takes_the_lock_the_same_way() {
      Locks explicit = new JdbcRowLocks(dataSource, new JdbcTransactionManager(dataSource));

      String result = explicit.withLock(KIND, TYPE, AgentId.random(), () -> "ran");

      assertThat(result).isEqualTo("ran");
    }
  }
}
