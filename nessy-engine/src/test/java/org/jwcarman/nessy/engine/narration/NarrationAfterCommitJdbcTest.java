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
package org.jwcarman.nessy.engine.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.jdbc.JdbcAgentEvents;
import org.jwcarman.nessy.backend.jdbc.JdbcDirectBackend;
import org.jwcarman.nessy.backend.jdbc.JdbcQueuedBackend;
import org.jwcarman.nessy.backend.jdbc.Schemas;
import org.jwcarman.nessy.engine.harness.direct.DefaultDirectHarnessFactory;
import org.jwcarman.nessy.engine.harness.queued.DefaultQueuedHarnessFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Against a real database, where {@code withLock} is a transaction: a listener is told about a step
 * only once it has committed, and a step whose commit fails is never told at all.
 */
@Tag("container")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class NarrationAfterCommitJdbcTest {

  private static final Duration PATIENCE = Duration.ofSeconds(30);
  private static final Duration PATIENCE_FOR_THE_LISTENER = Duration.ofMillis(500);

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  /** A transaction manager whose next commit on one thread fails, after which it is real again. */
  private static final class FailingCommits extends JdbcTransactionManager {

    private final ThreadLocal<Boolean> armed = new ThreadLocal<>();

    private volatile CountDownLatch commitsWaitFor;

    FailingCommits(DataSource dataSource) {
      super(dataSource);
    }

    /**
     * Every commit waits, up to {@link #PATIENCE_FOR_THE_LISTENER}, for {@code listenerHasRead}. A
     * listener told before the commit reads the database while the step is still open; one told
     * after it has nothing to wait on and the commit simply times out.
     */
    void commitsWaitFor(CountDownLatch listenerHasRead) {
      commitsWaitFor = listenerHasRead;
    }

    /** The next commit made by the calling thread fails, and the database rolls it back. */
    void failTheNextCommitOfThisThread() {
      armed.set(true);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      CountDownLatch waiting = commitsWaitFor;
      if (waiting != null) {
        try {
          waiting.await(PATIENCE_FOR_THE_LISTENER.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
        }
      }
      if (armed.get() != null) {
        armed.remove();
        // What a database that refuses the commit leaves behind: nothing. Rolled back here, since
        // the manager would otherwise hand the connection back to autocommit, which commits.
        doRollback(status);
        throw new TransactionSystemException("the commit failed, forced by the test");
      }
      super.doCommit(status);
    }
  }

  private final DataSource database = database();
  private final FailingCommits transactions = new FailingCommits(database);
  private final JacksonCodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());
  private final AgentEvents events = new JdbcAgentEvents(JdbcClient.create(database), codecs);
  private final Heard heard = new Heard();

  private static DataSource database() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    return database;
  }

  /**
   * Whether the history already held the turn's answer at the moment its end was heard. The commits
   * are made to wait for the listener's read, so a listener told too early reads while the step is
   * still open.
   */
  private NarrationListener checkingTheHistory(List<Boolean> readableWhenHeard) {
    CountDownLatch read = new CountDownLatch(1);
    transactions.commitsWaitFor(read);
    return (type, agent, event) -> {
      if (event instanceof Narration.TurnEnded ended) {
        readableWhenHeard.add(
            events.readAll(type, agent).stream()
                .anyMatch(
                    stored ->
                        stored instanceof AgentEvent.InferenceAnswered answered
                            && answered.turn().equals(ended.turn())));
        read.countDown();
      }
    };
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class The_direct_door {

    private DefaultDirectHarnessFactory factory(NarrationListener listener) {
      return Doors.directFactory(new JdbcDirectBackend(database, transactions, codecs), listener);
    }

    @Test
    void does_not_tell_a_listener_about_a_step_whose_commit_failed_and_the_database_rolled_back() {
      try (DefaultDirectHarnessFactory factory = factory(heard)) {
        DirectHarness<String, String> harness = Doors.direct(factory);
        AgentId agent = AgentId.random();
        transactions.failTheNextCommitOfThisThread();

        assertThatThrownBy(() -> harness.ask(agent, "hello"))
            .isInstanceOf(TransactionSystemException.class);
        assertThat(events.readAll(Doors.TYPE, agent)).as("rolled back").isEmpty();
        harness.ask(agent, "hello again");

        await().atMost(PATIENCE).until(() -> heard.kindsFor(agent).contains("TurnEnded"));
        assertThat(heard.kindsFor(agent))
            .as("the rolled-back turn was never told, so the story is one turn long")
            .containsExactly("TurnStarted", "Answered", "TurnEnded");
      }
    }

    @Test
    void tells_a_listener_about_a_turns_end_only_once_the_history_can_show_it() {
      List<Boolean> readable = new CopyOnWriteArrayList<>();
      try (DefaultDirectHarnessFactory factory = factory(checkingTheHistory(readable))) {
        DirectHarness<String, String> harness = Doors.direct(factory);

        harness.ask(AgentId.random(), "hello");

        await().atMost(PATIENCE).until(() -> !readable.isEmpty());
        assertThat(readable).containsExactly(true);
      }
    }
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class The_queued_door {

    private DefaultQueuedHarnessFactory factory(NarrationListener listener) {
      return Doors.queuedFactory(new JdbcQueuedBackend(database, transactions, codecs), listener);
    }

    @Test
    void does_not_tell_a_listener_about_a_step_whose_commit_failed_and_the_database_rolled_back() {
      try (DefaultQueuedHarnessFactory factory = factory(heard)) {
        QueuedHarness<String> harness = Doors.queued(factory);
        AgentId agent = AgentId.random();
        transactions.failTheNextCommitOfThisThread();

        assertThatThrownBy(() -> harness.tell(agent, "hello"))
            .isInstanceOf(TransactionSystemException.class);
        assertThat(events.readAll(Doors.TYPE, agent)).as("rolled back").isEmpty();
        harness.tell(agent, "hello again");

        await().atMost(PATIENCE).until(() -> heard.kindsFor(agent).contains("TurnEnded"));
        assertThat(heard.kindsFor(agent))
            .as("the rolled-back turn was never told, so the story is one turn long")
            .containsExactly("TurnStarted", "Thinking", "Answered", "TurnEnded");
      }
    }

    @Test
    void tells_a_listener_about_a_turns_end_only_once_the_history_can_show_it() {
      List<Boolean> readable = new CopyOnWriteArrayList<>();
      try (DefaultQueuedHarnessFactory factory = factory(checkingTheHistory(readable))) {
        QueuedHarness<String> harness = Doors.queued(factory);

        harness.tell(AgentId.random(), "hello");

        await().atMost(PATIENCE).until(() -> !readable.isEmpty());
        assertThat(readable).containsExactly(true);
      }
    }
  }
}
