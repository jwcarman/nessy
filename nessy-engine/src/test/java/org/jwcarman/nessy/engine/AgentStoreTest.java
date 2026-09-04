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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.agent.Phase;
import org.jwcarman.nessy.testing.TestDatabase;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The row that replaced an actor.
 *
 * <p>Two properties carry the design and neither is about storage: one agent's transitions
 * serialize, and two agents' do not. The second is the one that would silently regress -- a
 * table-level lock passes every single-agent test and turns the engine into a queue of one.
 */
@DisplayName("An agent's durable row")
class AgentStoreTest {

  private static final AgentType TYPE = AgentType.of("watchman");
  private static final AgentId ONE = AgentId.of("house-1");
  private static final AgentId TWO = AgentId.of("house-2");

  private EmbeddedDatabase database;
  private AgentStore store;
  private TransactionTemplate transactions;

  @BeforeEach
  void fresh() {
    database = TestDatabase.fresh();
    store = new AgentStore(database);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
  }

  @AfterEach
  void close() {
    database.shutdown();
  }

  @Test
  @DisplayName("an agent nobody has spoken to is idle rather than missing")
  void an_unknown_agent_reads_as_idle() {
    AgentState loaded = transactions.execute(status -> store.lockAndLoad(TYPE, ONE));

    assertThat(loaded).isEqualTo(AgentState.idle());
  }

  @Test
  @DisplayName("what was saved is what comes back")
  void a_saved_state_round_trips() {
    AgentState working = AgentState.idle().taking(TurnId.of("turn-1"), "claim-1");
    transactions.executeWithoutResult(status -> store.save(TYPE, ONE, working));

    AgentState loaded = transactions.execute(status -> store.lockAndLoad(TYPE, ONE));

    assertThat(loaded).isEqualTo(working);
    assertThat(loaded.phase()).isInstanceOf(Phase.CallingModel.class);
  }

  @Test
  @DisplayName("peeking a stranger finds nobody, and creates nobody")
  void peeking_an_unknown_agent_is_empty_and_conjures_no_row() {
    assertThat(store.peek(TYPE, ONE)).isEmpty();

    // The property lockAndLoad does NOT have: peek must never bring the row it just failed to
    // find into existence. Read straight, with no transaction of peek's own -- proof it took no
    // lock either, since a second FOR UPDATE from another thread would otherwise have to wait.
    assertThat(store.stalled(TYPE, Instant.now().plus(Duration.ofMinutes(10)), 10)).isEmpty();
    AgentState afterPeek = transactions.execute(status -> store.lockAndLoad(TYPE, ONE));
    assertThat(afterPeek)
        .as("lockAndLoad on a fresh read sees no row peek left behind")
        .isEqualTo(AgentState.idle());
  }

  @Test
  @DisplayName("peeking a known agent reads what is there, taking no lock")
  void peeking_a_known_agent_reads_it_without_locking() throws Exception {
    AgentState working = AgentState.idle().taking(TurnId.of("turn-1"), "claim-1");
    transactions.executeWithoutResult(status -> store.save(TYPE, ONE, working));

    assertThat(store.peek(TYPE, ONE)).contains(working);

    // Proof it is genuinely lock-free: a peek running WHILE another transaction holds this same
    // row's FOR UPDATE lock must still return promptly rather than waiting behind it.
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService threads = Executors.newFixedThreadPool(1);
    try {
      threads.submit(
          () ->
              transactions.executeWithoutResult(
                  status -> {
                    store.lockAndLoad(TYPE, ONE);
                    held.countDown();
                    awaitQuietly(release);
                  }));
      assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

      assertThat(store.peek(TYPE, ONE))
          .as("read while the row is locked elsewhere")
          .contains(working);
    } finally {
      release.countDown();
      threads.shutdownNow();
      assertThat(threads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("two agents do not contend: the lock is row-level, not table-level")
  void different_agents_never_block_each_other() throws Exception {
    // Committed rows, so each thread's FOR UPDATE contends on the SELECT lock itself rather than
    // racing an INSERT of a row that does not exist yet -- see AgentStoreTest's own history.
    transactions.executeWithoutResult(status -> store.save(TYPE, ONE, AgentState.idle()));
    transactions.executeWithoutResult(status -> store.save(TYPE, TWO, AgentState.idle()));

    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService threads = Executors.newFixedThreadPool(2);
    try {
      threads.submit(
          () ->
              transactions.executeWithoutResult(
                  status -> {
                    store.lockAndLoad(TYPE, ONE);
                    held.countDown();
                    awaitQuietly(release);
                  }));
      assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

      Future<AgentState> other =
          threads.submit(() -> transactions.execute(status -> store.lockAndLoad(TYPE, TWO)));

      assertThat(other.get(5, TimeUnit.SECONDS)).isEqualTo(AgentState.idle());
    } finally {
      release.countDown();
      threads.shutdownNow();
      assertThat(threads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("one agent does contend: a second lock waits for the first to commit")
  void the_same_agent_serializes() throws Exception {
    // A committed row, so the first thread's FOR UPDATE contends on the SELECT lock itself rather
    // than racing an INSERT of a row that does not exist yet -- see AgentStoreTest's own history.
    transactions.executeWithoutResult(status -> store.save(TYPE, ONE, AgentState.idle()));

    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Instant> beforeCommit = new AtomicReference<>();
    AtomicReference<Instant> afterSecondLock = new AtomicReference<>();
    ExecutorService threads = Executors.newFixedThreadPool(2);
    try {
      threads.submit(
          () ->
              transactions.executeWithoutResult(
                  status -> {
                    store.lockAndLoad(TYPE, ONE);
                    store.save(TYPE, ONE, AgentState.idle().taking(TurnId.of("t"), "c"));
                    beforeCommit.set(Instant.now());
                    held.countDown();
                    awaitQuietly(release);
                  }));
      assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

      Future<AgentState> second =
          threads.submit(
              () ->
                  transactions.execute(
                      status -> {
                        AgentState loaded = store.lockAndLoad(TYPE, ONE);
                        afterSecondLock.set(Instant.now());
                        return loaded;
                      }));
      release.countDown();

      assertThat(second.get(5, TimeUnit.SECONDS).turnId()).isEqualTo(TurnId.of("t"));
      // Proof that the second lock WAITED rather than merely finishing quickly: it could only
      // have landed after the first thread's pre-commit timestamp, because a lock held across
      // that commit is what forced the ordering.
      assertThat(afterSecondLock.get()).isAfter(beforeCommit.get());
    } finally {
      release.countDown();
      threads.shutdownNow();
      assertThat(threads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("a busy agent that has not moved is stalled; an idle one never is")
  void stalled_finds_only_agents_stuck_mid_turn() {
    transactions.executeWithoutResult(
        status -> store.save(TYPE, ONE, AgentState.idle().taking(TurnId.of("t"), "c")));
    transactions.executeWithoutResult(status -> store.save(TYPE, TWO, AgentState.idle()));

    assertThat(store.stalled(TYPE, Instant.now().plus(Duration.ofMinutes(10)), 10))
        .containsExactly(ONE);
  }

  @Test
  @DisplayName("a forgotten agent leaves no row behind")
  void delete_removes_the_row() {
    transactions.executeWithoutResult(
        status -> store.save(TYPE, ONE, AgentState.idle().taking(TurnId.of("t"), "c")));

    transactions.executeWithoutResult(status -> store.delete(TYPE, ONE));

    assertThat(store.stalled(TYPE, Instant.now().plus(Duration.ofMinutes(10)), 10)).isEmpty();
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
