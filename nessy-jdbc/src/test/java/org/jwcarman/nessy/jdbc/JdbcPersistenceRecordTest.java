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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * {@link JdbcPersistence}'s own compact constructor, pinned without a container: every component is
 * validated the same way {@code ConversationSnapshot} and {@code ToolContext} validate theirs in
 * this generation. No branch touches a database — the {@link JdbcConversationStore}, {@link
 * JdbcParks}, {@link JdbcTranscript}, and {@link JdbcSummaryStore} constructors each only store
 * their {@link DataSource} reference, never opening a connection.
 */
class JdbcPersistenceRecordTest {

  @Test
  void a_null_store_is_rejected() {
    assertThatThrownBy(() -> new JdbcPersistence(null, null, null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("store");
  }

  @Test
  void a_null_parks_is_rejected() {
    JdbcConversationStore store =
        new JdbcConversationStore(new UnusedDataSource(), new ObjectMapper());

    assertThatThrownBy(() -> new JdbcPersistence(store, null, null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("parks");
  }

  @Test
  void a_null_transcript_is_rejected() {
    JdbcConversationStore store =
        new JdbcConversationStore(new UnusedDataSource(), new ObjectMapper());
    JdbcParks parks = new JdbcParks(new UnusedDataSource(), new ObjectMapper());

    assertThatThrownBy(() -> new JdbcPersistence(store, parks, null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("transcript");
  }

  @Test
  void a_null_summaries_is_rejected() {
    JdbcConversationStore store =
        new JdbcConversationStore(new UnusedDataSource(), new ObjectMapper());
    JdbcParks parks = new JdbcParks(new UnusedDataSource(), new ObjectMapper());
    JdbcTranscript transcript = new JdbcTranscript(new UnusedDataSource(), new ObjectMapper());

    assertThatThrownBy(() -> new JdbcPersistence(store, parks, transcript, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("summaries");
  }

  /** A {@link DataSource} that is never actually connected to — construction alone must suffice. */
  private static final class UnusedDataSource implements DataSource {

    @Override
    public Connection getConnection() {
      throw new UnsupportedOperationException("not used by JdbcPersistenceRecordTest");
    }

    @Override
    public Connection getConnection(String username, String password) {
      throw new UnsupportedOperationException("not used by JdbcPersistenceRecordTest");
    }

    @Override
    public PrintWriter getLogWriter() {
      throw new UnsupportedOperationException("not used by JdbcPersistenceRecordTest");
    }

    @Override
    public void setLogWriter(PrintWriter out) {
      throw new UnsupportedOperationException("not used by JdbcPersistenceRecordTest");
    }

    @Override
    public void setLoginTimeout(int seconds) {
      throw new UnsupportedOperationException("not used by JdbcPersistenceRecordTest");
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
      throw new UnsupportedOperationException("not used by JdbcPersistenceRecordTest");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
