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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code Memory} has no TCK — these mirror {@code ListMemoryTest}'s scenarios against a real
 * Postgres, plus the two JDBC-specific pins the in-memory implementation has no opinion on:
 * bootstrap idempotency and surviving a fresh instance over the same database. Requires Docker;
 * tagged {@code container} so the offline default build never needs it.
 */
@Testcontainers
@Tag("container")
class JdbcMemoryTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static DataSource dataSource;

  private JdbcMemory memory;

  @BeforeAll
  static void nessy_store_jdbc_test_points_a_data_source_at_the_container() {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @BeforeEach
  void a_fresh_memory_over_an_empty_table() {
    memory = JdbcMemory.create(dataSource, new ObjectMapper());
    truncateMemoryTable();
  }

  private void truncateMemoryTable() {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE nessy_memory");
    } catch (SQLException e) {
      throw new IllegalStateException("failed to truncate nessy_memory between tests", e);
    }
  }

  @Test
  void recalls_exactly_what_it_was_told_in_order() {
    ConversationId id = ConversationId.generate();
    Message first = Message.user("hello");
    Message second = Message.assistant(List.of(new TextBlock("hi there")));
    memory.remember(id, first);
    memory.remember(id, second);

    Context recalled = memory.recall(id);

    assertThat(recalled.messages()).containsExactly(first, second);
  }

  @Test
  void recalls_nothing_for_a_conversation_never_told_anything() {
    Context recalled = memory.recall(ConversationId.generate());
    assertThat(recalled.messages()).isEmpty();
  }

  @Test
  void keeps_conversations_apart() {
    ConversationId one = ConversationId.generate();
    ConversationId other = ConversationId.generate();
    memory.remember(one, Message.user("for one"));
    memory.remember(other, Message.user("for the other"));

    assertThat(memory.recall(one).messages()).containsExactly(Message.user("for one"));
    assertThat(memory.recall(other).messages()).containsExactly(Message.user("for the other"));
  }

  @Test
  void tolerates_the_same_message_told_twice_in_a_row() {
    // At-least-once tellings (design 2026-08-11, ruling 6): a crash between telling
    // Memory and persisting state re-tells the same message. remember is idempotent.
    ConversationId id = ConversationId.generate();
    Message toldFirst = Message.user("once only, please");
    Message toldAgain = Message.user("once only, please");
    memory.remember(id, toldFirst);
    memory.remember(id, toldAgain);

    assertThat(memory.recall(id).messages()).containsExactly(toldFirst);
  }

  @Test
  void recall_drops_a_trailing_unanswered_tool_use_so_the_context_stays_legal() {
    // The loop remembers the assistant's tool-use message the moment its fold settles, before it
    // learns whether the call will park — so a parked conversation's raw telling can legitimately
    // end in an unanswered tool-use message. Memory#recall is contracted to always return a legal
    // Context, so that open tail must not surface here.
    ConversationId id = ConversationId.generate();
    Message userTurn = Message.user("issue a coupon, please");
    Message openToolUse =
        Message.assistant(
            List.of(
                new ToolUseBlock(
                    new ToolCall("c1", "issue_coupon", JsonNodeFactory.instance.objectNode()))));
    memory.remember(id, userTurn);
    memory.remember(id, openToolUse);

    Context recalled = memory.recall(id);

    assertThat(recalled.messages()).containsExactly(userTurn);
  }

  @Test
  void the_schema_bootstrap_is_idempotent() {
    assertThatCode(
            () -> {
              JdbcMemory.create(dataSource, new ObjectMapper());
              JdbcMemory.create(dataSource, new ObjectMapper());
            })
        .doesNotThrowAnyException();
  }

  @Test
  void survives_a_new_instance_over_the_same_database() {
    ConversationId id = ConversationId.generate();
    Message told = Message.user("remember me after restart");
    memory.remember(id, told);

    JdbcMemory freshInstance = JdbcMemory.create(dataSource, new ObjectMapper());

    assertThat(freshInstance.recall(id).messages()).containsExactly(told);
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
      throw new UnsupportedOperationException("not used by JdbcMemoryTest");
    }

    @Override
    public void setLogWriter(PrintWriter out) {
      throw new UnsupportedOperationException("not used by JdbcMemoryTest");
    }

    @Override
    public void setLoginTimeout(int seconds) {
      throw new UnsupportedOperationException("not used by JdbcMemoryTest");
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
      throw new UnsupportedOperationException("not used by JdbcMemoryTest");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
