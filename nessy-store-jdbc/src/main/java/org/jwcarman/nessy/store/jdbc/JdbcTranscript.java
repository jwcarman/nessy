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
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.memory.Transcript;

/**
 * The durable transcript: an append-only, versioned, per-conversation message log in Postgres — the
 * storage primitive {@code TranscriptMemory} and audit reads are both built on.
 *
 * <p>Every telling lands in {@code nessy_transcript}, one row per message, ordered by an
 * append-only {@code version} column. {@link #append} holds the no-stutter rule — appending unless
 * {@code message} equals the current last entry — under a row lock instead of an in-process map:
 * {@code SELECT ... FOR UPDATE} on the conversation's last row serializes concurrent {@code append}
 * calls for that conversation against each other, so two racing tellings of the same message never
 * both insert. The same locking discipline this module's retired durable-memory implementation used
 * to hold, lifted here (design §2).
 *
 * <p>The constructor alone does not create {@code nessy_transcript} — a caller pointing at a
 * database another process already bootstrapped should not pay a DDL round trip on every startup.
 * Use {@link #create(DataSource, ObjectMapper)} to bootstrap and construct in one call; its {@code
 * CREATE TABLE IF NOT EXISTS} is safe to run more than once.
 */
public final class JdbcTranscript implements Transcript {

  private static final String ID_MUST_NOT_BE_NULL = "id must not be null";

  private final DataSource dataSource;
  private final StateCodec codec;

  public JdbcTranscript(DataSource dataSource, ObjectMapper mapper) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.codec = new StateCodec(Objects.requireNonNull(mapper, "mapper must not be null"));
  }

  /**
   * Bootstraps {@code transcript-schema.sql} against {@code dataSource}, then returns a working
   * transcript.
   */
  public static JdbcTranscript create(DataSource dataSource, ObjectMapper mapper) {
    JdbcTranscript transcript = new JdbcTranscript(dataSource, mapper);
    transcript.bootstrap();
    return transcript;
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
      throw new IllegalStateException(
          "failed to bootstrap the nessy-store-jdbc transcript schema", e);
    }
  }

  private static String readSchemaResource() {
    try (InputStream in = JdbcTranscript.class.getResourceAsStream("transcript-schema.sql")) {
      if (in == null) {
        throw new IllegalStateException(
            "transcript-schema.sql not found on the classpath next to JdbcTranscript");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read transcript-schema.sql", e);
    }
  }

  @Override
  public Entry append(ConversationId id, Message message) {
    Objects.requireNonNull(id, ID_MUST_NOT_BE_NULL);
    Objects.requireNonNull(message, "message must not be null");
    return inTransaction(
        connection -> {
          Optional<Entry> last = readLastForUpdate(connection, id);
          if (last.isPresent() && last.get().message().equals(message)) {
            return last.get();
          }
          long nextVersion = last.map(entry -> entry.version() + 1).orElse(0L);
          insert(connection, id, nextVersion, message);
          return new Entry(nextVersion, message);
        });
  }

  @Override
  public List<Entry> all(ConversationId id) {
    Objects.requireNonNull(id, ID_MUST_NOT_BE_NULL);
    return withConnection(
        connection ->
            queryEntries(
                connection,
                "SELECT version, message FROM nessy_transcript"
                    + " WHERE conversation_id = ? ORDER BY version",
                ps -> ps.setString(1, id.value())));
  }

  @Override
  public List<Entry> tail(ConversationId id, long afterVersion) {
    Objects.requireNonNull(id, ID_MUST_NOT_BE_NULL);
    return withConnection(
        connection ->
            queryEntries(
                connection,
                "SELECT version, message FROM nessy_transcript"
                    + " WHERE conversation_id = ? AND version > ? ORDER BY version",
                ps -> {
                  ps.setString(1, id.value());
                  ps.setLong(2, afterVersion);
                }));
  }

  @Override
  public List<Entry> page(ConversationId id, long beforeVersion, int limit) {
    Objects.requireNonNull(id, ID_MUST_NOT_BE_NULL);
    return withConnection(
        connection -> {
          // The newest `limit` rows below the bound, fetched newest-first so LIMIT keeps the
          // right window, then reversed back into the ascending order the contract promises.
          List<Entry> newestFirst =
              queryEntries(
                  connection,
                  "SELECT version, message FROM nessy_transcript"
                      + " WHERE conversation_id = ? AND version < ? ORDER BY version DESC LIMIT ?",
                  ps -> {
                    ps.setString(1, id.value());
                    ps.setLong(2, beforeVersion);
                    ps.setInt(3, limit);
                  });
          return List.copyOf(newestFirst.reversed());
        });
  }

  private List<Entry> queryEntries(
      Connection connection, String sql, SqlConsumer<PreparedStatement> binder)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      binder.accept(ps);
      try (ResultSet rs = ps.executeQuery()) {
        List<Entry> entries = new ArrayList<>();
        while (rs.next()) {
          entries.add(new Entry(rs.getLong("version"), codec.readMessage(rs.getString("message"))));
        }
        return List.copyOf(entries);
      }
    }
  }

  private Optional<Entry> readLastForUpdate(Connection connection, ConversationId id)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "SELECT version, message FROM nessy_transcript WHERE conversation_id = ?"
                + " ORDER BY version DESC LIMIT 1 FOR UPDATE")) {
      ps.setString(1, id.value());
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        long version = rs.getLong("version");
        Message message = codec.readMessage(rs.getString("message"));
        return Optional.of(new Entry(version, message));
      }
    }
  }

  private void insert(Connection connection, ConversationId id, long version, Message message)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO nessy_transcript (conversation_id, version, message) VALUES (?, ?, ?::jsonb)")) {
      ps.setString(1, id.value());
      ps.setLong(2, version);
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
      throw new IllegalStateException("jdbc transcript operation failed", e);
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
      throw new IllegalStateException("jdbc transcript operation failed", e);
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
      throw new IllegalStateException("jdbc transcript operation failed", e);
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

  @FunctionalInterface
  private interface SqlFunction<A, T> {
    T apply(A input) throws SQLException;
  }

  @FunctionalInterface
  private interface SqlConsumer<A> {
    void accept(A input) throws SQLException;
  }
}
