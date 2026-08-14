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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.ConversationStoreContract;
import org.jwcarman.nessy.spi.conversation.StaleStateException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The TCK run against a real Postgres, plus two JDBC-specific pins the in-memory store has no
 * opinion on: a real cross-connection CAS race, and bootstrap idempotency. Requires Docker; tagged
 * {@code container} so the offline default build never needs it.
 */
@Testcontainers
@Tag("container")
class JdbcConversationStoreTest extends ConversationStoreContract {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  private static DataSource dataSource;

  @BeforeAll
  static void nessy_store_jdbc_test_points_a_data_source_at_the_container() {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @Override
  protected ConversationStore newStore() {
    JdbcConversationStore store = JdbcConversationStore.create(dataSource, new ObjectMapper());
    truncateEveryTable();
    return store;
  }

  private void truncateEveryTable() {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE nessy_conversation, nessy_inbox, nessy_park, nessy_token");
    } catch (SQLException e) {
      throw new IllegalStateException("failed to truncate tables between tests", e);
    }
  }

  @Test
  void two_connections_racing_a_save_see_exactly_one_winner() throws InterruptedException {
    ConversationId id = ConversationId.generate();
    ConversationState base = store().save(ConversationState.newConversation(id), List.of());

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<ConversationState>> racers = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      racers.add(
          executor.submit(
              () -> {
                ready.countDown();
                go.await();
                return store().save(base, List.of());
              }));
    }
    ready.await();
    go.countDown();

    int winners = 0;
    int losers = 0;
    for (Future<ConversationState> racer : racers) {
      try {
        racer.get();
        winners++;
      } catch (ExecutionException e) {
        assertThat(e.getCause()).isInstanceOf(StaleStateException.class);
        losers++;
      }
    }
    executor.shutdown();

    assertThat(winners).isEqualTo(1);
    assertThat(losers).isEqualTo(1);
  }

  @Test
  void the_schema_bootstrap_is_idempotent() {
    assertThatCode(
            () -> {
              JdbcConversationStore.create(dataSource, new ObjectMapper());
              JdbcConversationStore.create(dataSource, new ObjectMapper());
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
      throw new UnsupportedOperationException("not used by JdbcConversationStoreTest");
    }

    @Override
    public void setLogWriter(PrintWriter out) {
      throw new UnsupportedOperationException("not used by JdbcConversationStoreTest");
    }

    @Override
    public void setLoginTimeout(int seconds) {
      throw new UnsupportedOperationException("not used by JdbcConversationStoreTest");
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
      throw new UnsupportedOperationException("not used by JdbcConversationStoreTest");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
