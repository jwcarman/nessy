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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;

/**
 * {@link JdbcConversationStore#save}'s inbox drain, pinned without a database against the one gap a
 * thrown {@link SQLException} does not cover: {@code executeBatch()} is not obligated to throw on a
 * failed element. The JDBC spec also permits a driver to keep going past one and report {@link
 * Statement#EXECUTE_FAILED} for that element in the array it returns, with the batch call itself
 * returning normally — a save that didn't check the array would commit with that entry left
 * undrained forever. The doubles here are hand-rolled (a {@link DataSource} lending one {@link
 * Connection}, both dynamic-proxy) — the same house idiom {@code WriteOnceInsertTest} and {@code
 * JdbcFailureWrappingTest} already use, no mocking library, no container.
 */
class JdbcConversationStoreDrainBatchTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void a_batch_reporting_execute_failed_throws_and_rolls_back() {
    ConversationId id = ConversationId.generate();
    AtomicBoolean rolledBack = new AtomicBoolean();
    AtomicBoolean autoCommitRestored = new AtomicBoolean();
    Connection connection =
        drainingConnection(
            new int[] {1, Statement.EXECUTE_FAILED, 1}, rolledBack, autoCommitRestored);
    JdbcConversationStore store =
        new JdbcConversationStore(
            new OneConnectionDataSource(connection), MAPPER, JdbcDialect.POSTGRES);
    ConversationState state = ConversationState.newConversation(id).withVersion(1);
    List<String> drained = List.of("e1", "e2", "e3");

    assertThatThrownBy(() -> store.save(state, drained))
        .isInstanceOf(IllegalStateException.class)
        .cause()
        .isInstanceOf(SQLException.class)
        .hasMessageContaining(id.value())
        .hasMessageContaining("1");
    assertThat(rolledBack).isTrue();
    assertThat(autoCommitRestored).isTrue();
  }

  @Test
  void a_batch_reporting_success_no_info_is_accepted() {
    ConversationId id = ConversationId.generate();
    AtomicBoolean rolledBack = new AtomicBoolean();
    AtomicBoolean autoCommitRestored = new AtomicBoolean();
    Connection connection =
        drainingConnection(
            new int[] {1, Statement.SUCCESS_NO_INFO, 1}, rolledBack, autoCommitRestored);
    JdbcConversationStore store =
        new JdbcConversationStore(
            new OneConnectionDataSource(connection), MAPPER, JdbcDialect.POSTGRES);
    ConversationState state = ConversationState.newConversation(id).withVersion(1);

    ConversationState saved = store.save(state, List.of("e1", "e2", "e3"));

    assertThat(saved.version()).isEqualTo(2L);
    assertThat(rolledBack).isFalse();
    assertThat(autoCommitRestored).isTrue();
  }

  /**
   * Hand-rolled: an UPDATE {@link PreparedStatement} that always reports one row updated (so {@code
   * save} never falls into the version-0 insert branch), and a DELETE {@link PreparedStatement}
   * whose {@code executeBatch()} returns {@code batchResult} verbatim — the exact array shape a
   * real driver could hand back under the "continue past a failure" permission the JDBC spec
   * allows.
   */
  private static Connection drainingConnection(
      int[] batchResult, AtomicBoolean rolledBack, AtomicBoolean autoCommitRestored) {
    PreparedStatement updateStatement = updateStatementStub();
    PreparedStatement deleteStatement = batchStatementStub(batchResult);
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                  case "setAutoCommit" -> {
                    if (Boolean.TRUE.equals(args[0])) {
                      autoCommitRestored.set(true);
                    }
                    yield null;
                  }
                  case "setTransactionIsolation" -> null;
                  case "prepareStatement" ->
                      ((String) args[0]).startsWith("UPDATE") ? updateStatement : deleteStatement;
                  case "commit" -> null;
                  case "rollback" -> {
                    rolledBack.set(true);
                    yield null;
                  }
                  case "close" -> null;
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  /**
   * A {@link PreparedStatement} double whose {@code executeUpdate()} always reports one row hit.
   */
  private static PreparedStatement updateStatementStub() {
    return (PreparedStatement)
        Proxy.newProxyInstance(
            PreparedStatement.class.getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            (proxy, m, args) ->
                switch (m.getName()) {
                  case "setLong", "setString" -> null;
                  case "executeUpdate" -> 1;
                  case "close" -> null;
                  default -> throw new UnsupportedOperationException(m.getName());
                });
  }

  /**
   * A {@link PreparedStatement} double whose {@code executeBatch()} returns {@code batchResult}.
   */
  private static PreparedStatement batchStatementStub(int[] batchResult) {
    return (PreparedStatement)
        Proxy.newProxyInstance(
            PreparedStatement.class.getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            (proxy, m, args) ->
                switch (m.getName()) {
                  case "setString" -> null;
                  case "addBatch" -> null;
                  case "executeBatch" -> batchResult;
                  case "close" -> null;
                  default -> throw new UnsupportedOperationException(m.getName());
                });
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
    public Logger getParentLogger() {
      throw new UnsupportedOperationException();
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
