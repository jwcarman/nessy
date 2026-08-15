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
package org.jwcarman.nessy.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.spi.memory.SummaryStore;
import org.jwcarman.nessy.spi.memory.SummaryStore.Summary;
import org.jwcarman.nessy.store.tck.SummaryStoreContract;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The TCK run against a real Postgres, plus a JDBC-specific pin the in-memory store has no opinion
 * on: bootstrap idempotency and the upsert overwrite path. Requires Docker; tagged {@code
 * container} so the offline default build never needs it.
 */
@Testcontainers
@Tag("container")
class JdbcSummaryStoreTest extends SummaryStoreContract {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  private static DataSource dataSource;

  private SummaryStore summaries;

  @BeforeAll
  static void nessy_store_jdbc_test_points_a_data_source_at_the_container() {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @BeforeEach
  void a_fresh_store_over_an_empty_table() {
    summaries = JdbcSummaryStore.create(dataSource);
    truncateSummaryTable();
  }

  @Override
  protected SummaryStore summaries() {
    return summaries;
  }

  private void truncateSummaryTable() {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE nessy_summary");
    } catch (SQLException e) {
      throw new IllegalStateException("failed to truncate nessy_summary between tests", e);
    }
  }

  /**
   * The I-3 fix (Task 2 fix round): two connections racing the very first {@code save} of a
   * brand-new conversation both hit zero rows on their {@code UPDATE} and race an {@code INSERT}
   * for the same key. The loser used to discard {@link WriteOnceInsert#attempt}'s {@code false} and
   * drop its own write silently; it now re-runs its {@code UPDATE}, which finds the winner's row
   * and applies. Neither racer may throw (there is no fencing here, design §10), and the row that
   * lands must be one of the two writes — never neither, which is what the discarded-boolean bug
   * produced whenever the loser's save happened to be the one carrying the value a caller cared
   * about.
   */
  @Test
  void two_connections_racing_the_first_save_of_a_conversation_both_land()
      throws InterruptedException {
    ConversationId id = ConversationId.generate();
    Summary first = new Summary(1L, "from the first racer");
    Summary second = new Summary(2L, "from the second racer");

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Void>> racers =
        List.of(
            executor.submit(() -> raceSave(ready, go, id, first)),
            executor.submit(() -> raceSave(ready, go, id, second)));
    ready.await();
    go.countDown();
    for (Future<Void> racer : racers) {
      assertThatCode(racer::get).doesNotThrowAnyException();
    }
    executor.shutdown();

    assertThat(summaries().find(id)).isPresent().get().isIn(first, second);
  }

  private Void raceSave(CountDownLatch ready, CountDownLatch go, ConversationId id, Summary value)
      throws InterruptedException {
    ready.countDown();
    go.await();
    summaries().save(id, value);
    return null;
  }

  @Test
  void the_schema_bootstrap_is_idempotent() {
    assertThatCode(
            () -> {
              JdbcSummaryStore.create(dataSource);
              JdbcSummaryStore.create(dataSource);
            })
        .doesNotThrowAnyException();
  }

  /**
   * The thinnest possible {@link DataSource}: a fresh {@link DriverManager} connection per call, no
   * pooling. Sufficient for a test that wants one connection per JDBC operation and nothing
   * fancier; a real deployment supplies its own pooled {@code DataSource} instead.
   */
  private static final class DriverManagerDataSource implements DataSource {

    private final String url;
    private final String user;
    private final String password;

    private DriverManagerDataSource(String url, String user, String password) {
      this.url = url;
      this.user = user;
      this.password = password;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return DriverManager.getConnection(url, user, password);
    }

    @Override
    public Connection getConnection(String username, String pass) throws SQLException {
      return DriverManager.getConnection(url, username, pass);
    }

    @Override
    public PrintWriter getLogWriter() {
      throw new UnsupportedOperationException("not used by JdbcSummaryStoreTest");
    }

    @Override
    public void setLogWriter(PrintWriter out) {
      throw new UnsupportedOperationException("not used by JdbcSummaryStoreTest");
    }

    @Override
    public void setLoginTimeout(int seconds) {
      throw new UnsupportedOperationException("not used by JdbcSummaryStoreTest");
    }

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException("no java.util.logging parent logger");
    }

    @Override
    public <T> T unwrap(Class<T> iface) {
      throw new UnsupportedOperationException("not used by JdbcSummaryStoreTest");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
