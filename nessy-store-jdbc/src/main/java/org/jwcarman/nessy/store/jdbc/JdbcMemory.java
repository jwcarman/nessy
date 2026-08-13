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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.spi.memory.Memory;

/**
 * The durable floor: verbatim retention in Postgres, the {@code ListMemory} contract with a
 * lifespan.
 *
 * <p>Every telling lands in {@code nessy_memory}, one row per message, ordered by an append-only
 * {@code seq} column. {@link #remember} holds the consecutive-duplicate rule that makes
 * at-least-once tellings idempotent — see {@code ListMemory}'s javadoc — the same way, but enforced
 * under a row lock instead of an in-process map: {@code SELECT ... FOR UPDATE} on the
 * conversation's last row serializes concurrent {@code remember} calls for that conversation
 * against each other, so two racing tellings of the same message never both insert.
 *
 * <p>The constructor alone does not create {@code nessy_memory} — a caller pointing at a database
 * another process already bootstrapped should not pay a DDL round trip on every startup. Use {@link
 * #create(DataSource, ObjectMapper)} to bootstrap and construct in one call; its {@code CREATE
 * TABLE IF NOT EXISTS} is safe to run more than once.
 *
 * <p>{@link #recall} trims a trailing unanswered tool-use message — the loop's own park-in-progress
 * bookkeeping, remembered before the loop knows whether the call will park — before constructing
 * its {@link Context}, so a parked conversation's recall stays legal. {@code ListMemory} mirrors
 * the same trim; see {@link #withoutOpenTail}.
 */
public final class JdbcMemory implements Memory {

  private final DataSource dataSource;
  private final StateCodec codec;

  public JdbcMemory(DataSource dataSource, ObjectMapper mapper) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.codec = new StateCodec(Objects.requireNonNull(mapper, "mapper must not be null"));
  }

  /**
   * Bootstraps {@code memory-schema.sql} against {@code dataSource}, then returns a working memory.
   */
  public static JdbcMemory create(DataSource dataSource, ObjectMapper mapper) {
    JdbcMemory memory = new JdbcMemory(dataSource, mapper);
    memory.bootstrap();
    return memory;
  }

  private void bootstrap() {
    String schema = readSchemaResource();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql : schema.split(";")) {
        String trimmed = sql.strip();
        if (!trimmed.isEmpty()) {
          statement.execute(trimmed);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("failed to bootstrap the nessy-store-jdbc memory schema", e);
    }
  }

  private static String readSchemaResource() {
    try (InputStream in = JdbcMemory.class.getResourceAsStream("memory-schema.sql")) {
      if (in == null) {
        throw new IllegalStateException(
            "memory-schema.sql not found on the classpath next to JdbcMemory");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read memory-schema.sql", e);
    }
  }

  @Override
  public void remember(ConversationId id, Message message) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(message, "message must not be null");
    inTransaction(
        connection -> {
          Optional<LastRow> last = readLastForUpdate(connection, id);
          if (last.isPresent() && last.get().message().equals(message)) {
            return null;
          }
          long nextSeq = last.map(row -> row.seq() + 1).orElse(0L);
          insert(connection, id, nextSeq, message);
          return null;
        });
  }

  @Override
  public Context recall(ConversationId id) {
    Objects.requireNonNull(id, "id must not be null");
    return withConnection(
        connection -> {
          try (PreparedStatement ps =
              connection.prepareStatement(
                  "SELECT message FROM nessy_memory WHERE conversation_id = ? ORDER BY seq")) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
              List<Message> messages = new ArrayList<>();
              while (rs.next()) {
                messages.add(codec.readMessage(rs.getString("message")));
              }
              return Context.of(withoutOpenTail(messages));
            }
          }
        });
  }

  /**
   * {@code ConversationLoop} (nessy-core) remembers the model's tool-use message the moment its
   * fold settles, before the loop learns whether the call will park — so a parked conversation's
   * raw telling legitimately ends in an unanswered assistant tool-use message, an illegal trailing
   * shape for {@link Context}'s wire-safe invariant. {@link Memory#recall} is nonetheless
   * contracted to "return a legal {@code Context}" (see {@code Memory}'s javadoc); dropping that
   * one open tail — the loop's own park-in-progress bookkeeping, not settled dialogue yet — is what
   * keeps this implementation honest to that contract without touching the fold/remember timing
   * itself.
   */
  private static List<Message> withoutOpenTail(List<Message> messages) {
    if (messages.isEmpty()) {
      return messages;
    }
    Message last = messages.getLast();
    boolean openTail =
        last.role() == Role.ASSISTANT
            && last.content().stream().anyMatch(ToolUseBlock.class::isInstance);
    return openTail ? messages.subList(0, messages.size() - 1) : messages;
  }

  private Optional<LastRow> readLastForUpdate(Connection connection, ConversationId id)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "SELECT seq, message FROM nessy_memory WHERE conversation_id = ?"
                + " ORDER BY seq DESC LIMIT 1 FOR UPDATE")) {
      ps.setString(1, id.value());
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        long seq = rs.getLong("seq");
        Message message = codec.readMessage(rs.getString("message"));
        return Optional.of(new LastRow(seq, message));
      }
    }
  }

  private void insert(Connection connection, ConversationId id, long seq, Message message)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO nessy_memory (conversation_id, seq, message) VALUES (?, ?, ?::jsonb)")) {
      ps.setString(1, id.value());
      ps.setLong(2, seq);
      ps.setString(3, codec.writeMessage(message));
      ps.executeUpdate();
    }
  }

  /**
   * Runs {@code body} on a connection borrowed fresh from the pool; no transaction of its own, one
   * statement, autocommit exactly as the pool hands it back — the same discipline {@code
   * JdbcConversationStore}'s own {@code withConnection} follows, and for the same reason: a pool
   * that does not reset a connection between borrowers must never be handed back one still in a
   * prior caller's transaction state.
   */
  private <T> T withConnection(SqlFunction<Connection, T> body) {
    try (Connection connection = dataSource.getConnection()) {
      return body.apply(connection);
    } catch (SQLException e) {
      throw new IllegalStateException("jdbc memory operation failed", e);
    }
  }

  /**
   * Runs {@code body} inside one explicit transaction, committing on a normal return and rolling
   * back on any exception. Restores the borrowed connection's autocommit to what it had on loan
   * before returning it to the pool, for the same non-resetting-pool reason {@link #withConnection}
   * restores it too.
   */
  private <T> T inTransaction(SqlFunction<Connection, T> body) {
    try (Connection connection = dataSource.getConnection()) {
      return runInTransaction(connection, body);
    } catch (SQLException e) {
      throw new IllegalStateException("jdbc memory operation failed", e);
    }
  }

  /** The transaction body of {@link #inTransaction}, extracted so it is not a nested try block. */
  private static <T> T runInTransaction(Connection connection, SqlFunction<Connection, T> body) {
    try {
      connection.setAutoCommit(false);
      T result = body.apply(connection);
      connection.commit();
      return result;
    } catch (SQLException e) {
      rollbackQuietly(connection, e);
      throw new IllegalStateException("jdbc memory operation failed", e);
    } catch (RuntimeException e) {
      rollbackQuietly(connection, e);
      throw e;
    } finally {
      restoreConnection(connection);
    }
  }

  /** Rolls back, folding a rollback failure into {@code cause} rather than losing either one. */
  private static void rollbackQuietly(Connection connection, Exception cause) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      cause.addSuppressed(rollbackFailure);
    }
  }

  /**
   * Best-effort: puts the connection's autocommit back the way {@link #inTransaction} found it,
   * before it returns to the pool. A failure here is swallowed rather than thrown — throwing from
   * this {@code finally} would replace whatever exception is already propagating from the
   * transaction body above, and a connection too broken to restore is the pool's own problem to
   * evict on next borrow, not this method's to escalate.
   */
  private static void restoreConnection(Connection connection) {
    try {
      connection.setAutoCommit(true);
    } catch (SQLException _) {
      // best-effort restore; see method javadoc
    }
  }

  private record LastRow(long seq, Message message) {}

  @FunctionalInterface
  private interface SqlFunction<A, T> {
    T apply(A input) throws SQLException;
  }
}
