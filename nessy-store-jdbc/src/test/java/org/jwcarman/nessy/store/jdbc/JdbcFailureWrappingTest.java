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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.memory.SummaryStore.Summary;

/**
 * The doors' failure contract, pinned without a database: a {@link SQLException} anywhere inside a
 * door surfaces as an {@link IllegalStateException} naming the door, with the original as its cause
 * — never a checked leak, never a swallow. And the transactional door rolls back before it reports.
 * No Docker, no container tag: the failure happens before any real database could matter.
 *
 * <p>The doubles here are hand-rolled (a tiny {@link DataSource} and a dynamic-proxy {@link
 * Connection}) — the house bans mocking libraries, not test doubles.
 */
class JdbcFailureWrappingTest {

  private static final SQLException REFUSED = new SQLException("connection refused (test)");

  /** A datasource whose every borrow fails — the simplest broken database there is. */
  private static DataSource refusing() {
    return new RefusingDataSource();
  }

  @Test
  void the_transcript_wraps_a_failed_read_naming_itself() {
    JdbcTranscript transcript = new JdbcTranscript(refusing(), new ObjectMapper());
    ConversationId id = ConversationId.generate();

    assertThatThrownBy(() -> transcript.all(id))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("transcript")
        .cause()
        .isSameAs(REFUSED);
  }

  @Test
  void the_transcript_wraps_a_failed_append_naming_itself() {
    JdbcTranscript transcript = new JdbcTranscript(refusing(), new ObjectMapper());
    ConversationId id = ConversationId.generate();
    Message hello = Message.user("hello");

    assertThatThrownBy(() -> transcript.append(id, hello))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("transcript")
        .cause()
        .isSameAs(REFUSED);
  }

  @Test
  void the_parks_registry_wraps_a_failed_find_naming_itself() {
    JdbcParks parks = new JdbcParks(refusing(), new ObjectMapper());
    ParkToken token = ParkToken.generate();

    assertThatThrownBy(() -> parks.find(token))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("parks")
        .cause()
        .isSameAs(REFUSED);
  }

  @Test
  void the_summary_store_wraps_a_failed_save_naming_itself() {
    JdbcSummaryStore summaries = new JdbcSummaryStore(refusing());
    ConversationId id = ConversationId.generate();
    Summary summary = new Summary(3L, "so far");

    assertThatThrownBy(() -> summaries.save(id, summary))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("summary")
        .cause()
        .isSameAs(REFUSED);
  }

  @Test
  void the_conversation_store_wraps_a_failed_load_naming_itself() {
    JdbcConversationStore store = new JdbcConversationStore(refusing(), new ObjectMapper());
    ConversationId id = ConversationId.generate();

    assertThatThrownBy(() -> store.load(id))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conversation store")
        .cause()
        .isSameAs(REFUSED);
  }

  @Test
  void a_failure_inside_the_transcripts_transaction_rolls_back_before_reporting() {
    AtomicBoolean rolledBack = new AtomicBoolean();
    AtomicBoolean autoCommitRestored = new AtomicBoolean();
    Connection failingInside =
        (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "prepareStatement" -> throw REFUSED;
                      case "rollback" -> {
                        rolledBack.set(true);
                        yield null;
                      }
                      case "setAutoCommit" -> {
                        if (Boolean.TRUE.equals(args[0])) {
                          autoCommitRestored.set(true);
                        }
                        yield null;
                      }
                      case "close" -> null;
                      case "isClosed" -> false;
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    JdbcTranscript transcript =
        new JdbcTranscript(new OneConnectionDataSource(failingInside), new ObjectMapper());
    ConversationId id = ConversationId.generate();
    Message hello = Message.user("hello");

    assertThatThrownBy(() -> transcript.append(id, hello))
        .isInstanceOf(IllegalStateException.class)
        .cause()
        .isSameAs(REFUSED);
    assertThat(rolledBack).isTrue();
    assertThat(autoCommitRestored).isTrue();
  }

  /** Hand-rolled: every {@code getConnection} refuses with {@link #REFUSED}. */
  private static final class RefusingDataSource implements DataSource {

    @Override
    public Connection getConnection() throws SQLException {
      throw REFUSED;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      throw REFUSED;
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
