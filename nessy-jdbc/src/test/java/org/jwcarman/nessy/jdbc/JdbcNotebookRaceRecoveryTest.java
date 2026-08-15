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
package org.jwcarman.nessy.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.SubjectId;
import org.jwcarman.nessy.spi.notebook.Notebook.Entry;

/**
 * {@link JdbcNotebook#save}'s race-recovery upsert, proven deterministically rather than only by a
 * racy container test: a forced-duplicate seam — a hand-rolled {@link Connection} whose first
 * {@code UPDATE} always reports zero rows and whose {@code INSERT} always fails with a genuine
 * duplicate key — must make {@link JdbcNotebook#save} run the {@code UPDATE} a second time rather
 * than silently accept the swallowed insert as "done." No database, no race timing to get lucky or
 * unlucky on. Mirrors {@link JdbcSummaryStoreRaceRecoveryTest}, the template this store's upsert
 * shape (design §5) is deliberately identical to.
 */
class JdbcNotebookRaceRecoveryTest {

  @Test
  void a_swallowed_duplicate_insert_makes_save_retry_the_update() {
    AtomicInteger updateAttempts = new AtomicInteger();
    SubjectId subject = new SubjectId("user-42");
    Entry entry =
        new Entry("user-taste", "recovered hook", "recovered after losing the insert race");
    DataSource dataSource = new OneConnectionDataSource(raceRecoveryConnection(updateAttempts));
    JdbcNotebook notebook = new JdbcNotebook(dataSource, JdbcDialect.POSTGRES);

    assertThatCode(() -> notebook.save(subject, entry)).doesNotThrowAnyException();

    // Once for the UPDATE that (simulated) finds nothing, once more after the swallowed
    // duplicate-key INSERT — the retry the race-recovery upsert requires. A regression back to
    // discarding WriteOnceInsert#attempt's boolean stops at one: the second UPDATE never runs.
    assertThat(updateAttempts.get()).isEqualTo(2);
  }

  /**
   * {@code UPDATE nessy_notebook ...} always reports zero rows updated; {@code INSERT INTO
   * nessy_notebook ...} always fails with a genuine Postgres duplicate-key {@link SQLException}
   * ({@code 23505}) — simulating a concurrent saver's insert landing first. Autocommit {@code true}
   * throughout, so {@link WriteOnceInsert#attempt} never needs a savepoint.
   */
  private static Connection raceRecoveryConnection(AtomicInteger updateAttempts) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "getAutoCommit" -> true;
                  case "prepareStatement" -> preparedStatementFor((String) args[0], updateAttempts);
                  case "close" -> null;
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static PreparedStatement preparedStatementFor(String sql, AtomicInteger updateAttempts) {
    boolean isUpdate = sql.startsWith("UPDATE");
    return (PreparedStatement)
        Proxy.newProxyInstance(
            PreparedStatement.class.getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "setString" -> null;
                  case "executeUpdate" ->
                      isUpdate ? zeroThenOneRow(updateAttempts) : throwDuplicate();
                  case "close" -> null;
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static int zeroThenOneRow(AtomicInteger updateAttempts) {
    return updateAttempts.incrementAndGet() == 1 ? 0 : 1;
  }

  private static int throwDuplicate() throws SQLException {
    throw new SQLException("simulated concurrent duplicate (test)", "23505", 0);
  }

  /** Hand-rolled: lends exactly the one connection it was given. */
  private static final class OneConnectionDataSource implements DataSource {

    private final Connection connection;

    private OneConnectionDataSource(Connection connection) {
      this.connection = connection;
    }

    @Override
    public Connection getConnection() {
      return connection;
    }

    @Override
    public Connection getConnection(String username, String password) {
      return connection;
    }

    @Override
    public PrintWriter getLogWriter() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setLogWriter(PrintWriter out) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setLoginTimeout(int seconds) {
      throw new UnsupportedOperationException();
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
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
