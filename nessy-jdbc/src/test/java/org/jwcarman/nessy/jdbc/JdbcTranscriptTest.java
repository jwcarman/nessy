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

import static org.assertj.core.api.Assertions.assertThatCode;

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.memory.Transcript;
import org.jwcarman.nessy.tck.TranscriptContract;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The TCK run against a real Postgres, plus a JDBC-specific pin the in-memory transcript has no
 * opinion on: bootstrap idempotency. Requires Docker; tagged {@code container} so the offline
 * default build never needs it.
 */
@Testcontainers
@Tag("container")
class JdbcTranscriptTest extends TranscriptContract {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  private static DataSource dataSource;

  private Transcript transcript;

  @BeforeAll
  static void nessy_jdbc_test_points_a_data_source_at_the_container() {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @BeforeEach
  void a_fresh_transcript_over_an_empty_table() {
    transcript = JdbcTranscript.create(dataSource, new ObjectMapper());
    truncateTranscriptTable();
  }

  @Override
  protected Transcript transcript() {
    return transcript;
  }

  private void truncateTranscriptTable() {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE nessy_transcript");
    } catch (SQLException e) {
      throw new IllegalStateException("failed to truncate nessy_transcript between tests", e);
    }
  }

  @Test
  void the_schema_bootstrap_is_idempotent() {
    assertThatCode(
            () -> {
              JdbcTranscript.create(dataSource, new ObjectMapper());
              JdbcTranscript.create(dataSource, new ObjectMapper());
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
      throw new UnsupportedOperationException("not used by JdbcTranscriptTest");
    }

    @Override
    public void setLogWriter(PrintWriter out) {
      throw new UnsupportedOperationException("not used by JdbcTranscriptTest");
    }

    @Override
    public void setLoginTimeout(int seconds) {
      throw new UnsupportedOperationException("not used by JdbcTranscriptTest");
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
      throw new UnsupportedOperationException("not used by JdbcTranscriptTest");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
