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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.Parks;
import org.jwcarman.nessy.spi.memory.SummaryStore;
import org.jwcarman.nessy.spi.memory.Transcript;
import org.jwcarman.nessy.tck.ConversationStoreContract;
import org.jwcarman.nessy.tck.ParksContract;
import org.jwcarman.nessy.tck.SummaryStoreContract;
import org.jwcarman.nessy.tck.TranscriptContract;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The full TCK, all four contracts, run against a real MariaDB — plus the dialect-resolution pin
 * (design §6). One container for the whole class (nested contracts share it, each truncating its
 * own table between tests) rather than four, the same efficiency trade the vendor matrix needs five
 * times over. Requires Docker; tagged {@code container} so the offline default build never needs
 * it.
 *
 * <p>Image pinned to {@code mariadb:11.4} — the exact tag Task 2's live schema verification ran
 * against (see that task's report); this matrix reuses those findings rather than re-discovering
 * them against a different point release.
 */
@Testcontainers
@Tag("container")
@Tag("vendor")
class MariaDbStoreTckTest {

  @Container static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.4");

  private static DataSource dataSource;

  @BeforeAll
  static void nessy_store_jdbc_test_points_a_data_source_at_the_container() {
    dataSource =
        new DriverManagerDataSource(
            MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
  }

  /**
   * The sniff's tripwire half (design §2, {@link JdbcDialect}'s own javadoc): the MariaDB
   * Connector/J driver reports {@code "MySQL"} as its JDBC product name, for compatibility, and
   * stamps {@code "MariaDB"} into the product *version* string instead — the resolver has to read
   * past the lying product name into the version string to land on {@link JdbcDialect#MARIADB}
   * rather than the wrong {@link JdbcDialect#MYSQL}. A hand-rolled {@link
   * java.sql.DatabaseMetaData} double could assert whatever this test wants regardless of whether
   * the sniff logic is right; only a real MariaDB server behind a real driver can actually exercise
   * it, which is the whole reason this test exists against a container rather than as a unit test.
   */
  @Test
  void the_resolver_reads_past_the_mysql_product_name_to_pick_mariadb_against_the_real_container()
      throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      assertThat(JdbcDialect.resolve(connection.getMetaData())).isEqualTo(JdbcDialect.MARIADB);
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
      throw new UnsupportedOperationException("not used by MariaDbStoreTckTest");
    }

    @Override
    public void setLogWriter(PrintWriter out) {
      throw new UnsupportedOperationException("not used by MariaDbStoreTckTest");
    }

    @Override
    public void setLoginTimeout(int seconds) {
      throw new UnsupportedOperationException("not used by MariaDbStoreTckTest");
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
      throw new UnsupportedOperationException("not used by MariaDbStoreTckTest");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
