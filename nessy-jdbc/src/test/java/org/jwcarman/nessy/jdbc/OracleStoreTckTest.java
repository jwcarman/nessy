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
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.Parks;
import org.jwcarman.nessy.spi.memory.SummaryStore;
import org.jwcarman.nessy.spi.transcript.Transcript;
import org.jwcarman.nessy.tck.ConversationStoreContract;
import org.jwcarman.nessy.tck.ParksContract;
import org.jwcarman.nessy.tck.SummaryStoreContract;
import org.jwcarman.nessy.tck.TranscriptContract;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

/**
 * The full TCK, all four contracts, run against a real Oracle — plus the dialect-resolution pin
 * (design §6). One container for the whole class (nested contracts share it, each truncating its
 * own table between tests) rather than four, the same efficiency trade the vendor matrix needs five
 * times over — most valuable here, where Oracle is the matrix's heavyweight: its image pull and
 * container start dwarf the other four vendors combined, so paying that cost once rather than four
 * times over is not an optimization, it's the difference between a runnable local sweep and one
 * nobody bothers to run. Requires Docker; tagged {@code container} so the offline default build
 * never needs it.
 *
 * <p>Image pinned to {@code gvenzl/oracle-free:23-slim-faststart} — the exact tag Task 2's live
 * schema/SQLState verification ran against (see that task's report, which also notes the tag is a
 * moving convenience target gvenzl continues fast-forwarding — reported version at that
 * verification was "Oracle AI Database 26ai Free Release 23.26.2.0.0"). This matrix reuses those
 * findings rather than re-discovering them against a different point release; re-verify the
 * schema/SQLState notes if this pin is ever bumped to a materially newer database version.
 */
@Testcontainers
@Tag("container")
@Tag("vendor")
class OracleStoreTckTest {

  @Container
  static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart");

  private static DataSource dataSource;

  @BeforeAll
  static void nessy_jdbc_test_points_a_data_source_at_the_container() {
    dataSource =
        new DriverManagerDataSource(
            ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword());
  }

  @Test
  void the_resolver_picks_oracle_against_the_real_container() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      assertThat(JdbcDialect.resolve(connection.getMetaData())).isEqualTo(JdbcDialect.ORACLE);
    }
  }

  private static void truncate(String table) {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE TABLE " + table);
    } catch (SQLException e) {
      throw new IllegalStateException("failed to truncate " + table + " between tests", e);
    }
  }

  @Nested
  class Conversation_store_contract extends ConversationStoreContract {

    @Override
    protected ConversationStore newStore() {
      JdbcConversationStore store = JdbcConversationStore.create(dataSource, new ObjectMapper());
      truncate("nessy_inbox");
      truncate("nessy_conversation");
      return store;
    }
  }

  @Nested
  class Parks_contract extends ParksContract {

    private Parks parks;

    @BeforeEach
    void a_fresh_registry_over_an_empty_table() {
      parks = JdbcParks.create(dataSource, new ObjectMapper());
      truncate("nessy_parks");
    }

    @Override
    protected Parks parks() {
      return parks;
    }
  }

  @Nested
  class Transcript_contract extends TranscriptContract {

    private Transcript transcript;

    @BeforeEach
    void a_fresh_transcript_over_an_empty_table() {
      transcript = JdbcTranscript.create(dataSource, new ObjectMapper());
      truncate("nessy_transcript");
    }

    @Override
    protected Transcript transcript() {
      return transcript;
    }
  }

  @Nested
  class Summary_store_contract extends SummaryStoreContract {

    private SummaryStore summaries;

    @BeforeEach
    void a_fresh_store_over_an_empty_table() {
      summaries = JdbcSummaryStore.create(dataSource);
      truncate("nessy_summary");
    }

    @Override
    protected SummaryStore summaries() {
      return summaries;
    }
  }

  /**
   * The thinnest possible {@link DataSource}: a fresh {@link DriverManager} connection per call, no
   * pooling. Sufficient for a test that wants one connection per JDBC operation and nothing
   * fancier; a real deployment supplies its own pooled {@code DataSource} instead.
   *
   * <p>Oracle-specific addition the other four vendors' matching class does not need: {@link
   * #getConnection()}/{@link #getConnection(String, String)} retry on any {@link SQLException}, up
   * to {@link #CONNECT_RETRY_BUDGET}. Confirmed live in this task's own verification runs: the
   * gvenzl/oracle-free listener intermittently answers a brand-new physical connection with {@code
   * ORA-12516} ("no protocol handler ... registered for service") under this suite's rapid,
   * unpooled connection churn — dozens of short-lived connections opened and closed within a
   * fraction of a second, well after the container itself already reported ready and had already
   * served earlier connections successfully, so this is not the usual Testcontainers not-ready-yet
   * race. A short bounded retry absorbs it without weakening any test's own assertions; a real
   * deployment would use a pooled {@code DataSource} and never hit this at all.
   */
  private static final class DriverManagerDataSource implements DataSource {

    private static final Duration CONNECT_RETRY_BUDGET = Duration.ofSeconds(30);
    private static final Duration CONNECT_RETRY_INTERVAL = Duration.ofMillis(250);

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
      return connectWithRetry(() -> DriverManager.getConnection(url, user, password));
    }

    @Override
    public Connection getConnection(String username, String pass) throws SQLException {
      return connectWithRetry(() -> DriverManager.getConnection(url, username, pass));
    }

    private static Connection connectWithRetry(Callable<Connection> attempt) throws SQLException {
      try {
        return await()
            .ignoreExceptions()
            .atMost(CONNECT_RETRY_BUDGET)
            .pollInterval(CONNECT_RETRY_INTERVAL)
            .until(attempt, Objects::nonNull);
      } catch (ConditionTimeoutException e) {
        throw new SQLException(
            "Oracle refused every connection attempt for " + CONNECT_RETRY_BUDGET, e);
      }
    }

    @Override
    public PrintWriter getLogWriter() {
      throw new UnsupportedOperationException("not used by OracleStoreTckTest");
    }

    @Override
    public void setLogWriter(PrintWriter out) {
      throw new UnsupportedOperationException("not used by OracleStoreTckTest");
    }

    @Override
    public void setLoginTimeout(int seconds) {
      throw new UnsupportedOperationException("not used by OracleStoreTckTest");
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
      throw new UnsupportedOperationException("not used by OracleStoreTckTest");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
