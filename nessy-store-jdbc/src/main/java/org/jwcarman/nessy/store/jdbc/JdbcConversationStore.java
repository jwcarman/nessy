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
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.LaneEntry;
import org.jwcarman.nessy.api.conversation.ParkedCall;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.StaleStateException;

/**
 * The reference durable {@link ConversationStore}: plain JDBC against Postgres, no Spring, no JPA —
 * the house stance. See {@code schema.sql} on the classpath next to this class for the four tables
 * it reads and writes: {@code nessy_conversation} (the fenced control block), {@code nessy_lane}
 * (the append-only debt lane), {@code nessy_park} (the token index), and {@code nessy_token}
 * (single-use resume tokens).
 *
 * <p>The constructor alone does not create those tables — a caller pointing at a database another
 * process already bootstrapped should not pay a DDL round trip on every startup. Use {@link
 * #create(DataSource, ObjectMapper)} to bootstrap and construct in one call; its {@code CREATE
 * TABLE IF NOT EXISTS} / {@code CREATE INDEX IF NOT EXISTS} statements are safe to run more than
 * once.
 *
 * <p>{@link #save} is Postgres's own fence: an {@code UPDATE ... WHERE id = ? AND version = ?} that
 * either updates exactly the row the caller read, or updates nothing because someone else's save
 * already moved it — the database's row lock, not an in-process monitor, is what makes two
 * concurrent savers on the same conversation see exactly one winner. A version-0 save additionally
 * tries {@code INSERT ... ON CONFLICT DO NOTHING} for the case there is no row yet to match
 * against. Either way, a losing save re-reads the column and fails loudly with {@link
 * StaleStateException} rather than silently doing nothing.
 *
 * <p>The park index is synced by delta, not by clearing and rebuilding: a token this save's {@code
 * parkedCalls()} still names is never deleted and never re-inserted ({@code ON CONFLICT (token) DO
 * NOTHING} — a park's call is immutable for the life of its token, so there is nothing to update
 * even if it were re-inserted), so a save that leaves a park untouched never disturbs its row.
 */
public final class JdbcConversationStore implements ConversationStore {

  private final DataSource dataSource;
  private final StateCodec codec;

  public JdbcConversationStore(DataSource dataSource, ObjectMapper mapper) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.codec = new StateCodec(Objects.requireNonNull(mapper, "mapper must not be null"));
  }

  /** Bootstraps {@code schema.sql} against {@code dataSource}, then returns a working store. */
  public static JdbcConversationStore create(DataSource dataSource, ObjectMapper mapper) {
    JdbcConversationStore store = new JdbcConversationStore(dataSource, mapper);
    store.bootstrap();
    return store;
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
      throw new IllegalStateException("failed to bootstrap the nessy-store-jdbc schema", e);
    }
  }

  private static String readSchemaResource() {
    try (InputStream in = JdbcConversationStore.class.getResourceAsStream("schema.sql")) {
      if (in == null) {
        throw new IllegalStateException(
            "schema.sql not found on the classpath next to JdbcConversationStore");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read schema.sql", e);
    }
  }

  @Override
  public Optional<Loaded> load(ConversationId id) {
    Objects.requireNonNull(id, "id must not be null");
    return inTransaction(
        Connection.TRANSACTION_REPEATABLE_READ,
        connection -> {
          Optional<ConversationState> row = readState(connection, id);
          List<LaneEntry> lane = readLane(connection, id);
          if (row.isEmpty() && lane.isEmpty()) {
            return Optional.empty();
          }
          ConversationState state = row.orElseGet(() -> ConversationState.newConversation(id));
          return Optional.of(new Loaded(state, lane));
        });
  }

  private Optional<ConversationState> readState(Connection connection, ConversationId id)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement("SELECT version, state FROM nessy_conversation WHERE id = ?")) {
      ps.setString(1, id.value());
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        // The column is the fence; the jsonb blob's own embedded version is a value copied at
        // write time, not the authority. Trusting anything else would let a save that updates
        // the column but (somehow) writes a stale blob resurrect an old version number.
        long version = rs.getLong("version");
        ConversationState decoded = codec.readState(rs.getString("state"));
        return Optional.of(decoded.withVersion(version));
      }
    }
  }

  private List<LaneEntry> readLane(Connection connection, ConversationId id) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "SELECT payload FROM nessy_lane WHERE conversation_id = ? ORDER BY entry_id")) {
      ps.setString(1, id.value());
      try (ResultSet rs = ps.executeQuery()) {
        List<LaneEntry> entries = new ArrayList<>();
        while (rs.next()) {
          entries.add(codec.readLaneEntry(rs.getString("payload")));
        }
        return List.copyOf(entries);
      }
    }
  }

  @Override
  public ConversationState save(ConversationState state, Collection<String> drainedLaneIds) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(drainedLaneIds, "drainedLaneIds must not be null");
    ConversationId id = state.id();
    long expected = state.version();
    ConversationState bumped = state.withVersion(expected + 1);
    String json = codec.writeState(bumped);

    return inTransaction(
        Connection.TRANSACTION_READ_COMMITTED,
        connection -> {
          int updated =
              update(
                  connection,
                  "UPDATE nessy_conversation SET version = ?, state = ?::jsonb"
                      + " WHERE id = ? AND version = ?",
                  ps -> {
                    ps.setLong(1, bumped.version());
                    ps.setString(2, json);
                    ps.setString(3, id.value());
                    ps.setLong(4, expected);
                  });

          if (updated == 0) {
            boolean inserted = expected == 0 && insertNewConversation(connection, id, bumped, json);
            if (!inserted) {
              throw new StaleStateException(id, expected, currentVersion(connection, id));
            }
          }

          drainLane(connection, drainedLaneIds);
          syncParks(connection, id, bumped.parkedCalls());
          return bumped;
        });
  }

  private boolean insertNewConversation(
      Connection connection, ConversationId id, ConversationState bumped, String json)
      throws SQLException {
    int inserted =
        update(
            connection,
            "INSERT INTO nessy_conversation (id, version, state) VALUES (?, ?, ?::jsonb)"
                + " ON CONFLICT (id) DO NOTHING",
            ps -> {
              ps.setString(1, id.value());
              ps.setLong(2, bumped.version());
              ps.setString(3, json);
            });
    return inserted == 1;
  }

  private long currentVersion(Connection connection, ConversationId id) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement("SELECT version FROM nessy_conversation WHERE id = ?")) {
      ps.setString(1, id.value());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong("version") : 0L;
      }
    }
  }

  private void drainLane(Connection connection, Collection<String> drainedLaneIds)
      throws SQLException {
    Array ids = connection.createArrayOf("text", drainedLaneIds.toArray(new String[0]));
    try (PreparedStatement ps =
        connection.prepareStatement("DELETE FROM nessy_lane WHERE entry_id = ANY(?)")) {
      ps.setArray(1, ids);
      ps.executeUpdate();
    }
  }

  /**
   * Moves {@code nessy_park} to exactly {@code parkedCalls} by delta. The delete's {@code token <>
   * ALL(?)} against the array of currently-parked tokens removes every row this conversation owns
   * that {@code parkedCalls} no longer names — including every row, if {@code parkedCalls} is
   * empty, since {@code <> ALL} over an empty array is vacuously true for every row. The insert's
   * {@code ON CONFLICT (token) DO NOTHING} then leaves every still-parked token's row exactly as it
   * was: a call's payload never changes for the life of its single-use token, so there is nothing
   * an UPDATE would ever need to change.
   */
  private void syncParks(Connection connection, ConversationId id, List<ParkedCall> parkedCalls)
      throws SQLException {
    String[] tokens =
        parkedCalls.stream().map(parked -> parked.token().value()).toArray(String[]::new);
    Array tokenArray = connection.createArrayOf("text", tokens);
    try (PreparedStatement ps =
        connection.prepareStatement(
            "DELETE FROM nessy_park WHERE conversation_id = ? AND token <> ALL(?)")) {
      ps.setString(1, id.value());
      ps.setArray(2, tokenArray);
      ps.executeUpdate();
    }
    if (parkedCalls.isEmpty()) {
      return;
    }
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO nessy_park (token, conversation_id, call) VALUES (?, ?, ?::jsonb)"
                + " ON CONFLICT (token) DO NOTHING")) {
      for (ParkedCall parked : parkedCalls) {
        ps.setString(1, parked.token().value());
        ps.setString(2, id.value());
        ps.setString(3, codec.writeToolCall(parked.call()));
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  @Override
  public void appendLane(ConversationId id, LaneEntry entry) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(entry, "entry must not be null");
    String kind = entry instanceof LaneEntry.Told ? "told" : "resolved";
    withConnection(
        connection -> {
          try (PreparedStatement ps =
              connection.prepareStatement(
                  "INSERT INTO nessy_lane (entry_id, conversation_id, kind, payload)"
                      + " VALUES (?, ?, ?, ?::jsonb)")) {
            ps.setString(1, entry.id());
            ps.setString(2, id.value());
            ps.setString(3, kind);
            ps.setString(4, codec.writeLaneEntry(entry));
            ps.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public Optional<ParkedCall> findPark(ParkToken token) {
    Objects.requireNonNull(token, "token must not be null");
    return withConnection(
        connection -> {
          try (PreparedStatement ps =
              connection.prepareStatement("SELECT call FROM nessy_park WHERE token = ?")) {
            ps.setString(1, token.value());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                return Optional.empty();
              }
              ToolCall call = codec.readToolCall(rs.getString("call"));
              return Optional.of(new ParkedCall(token, call));
            }
          }
        });
  }

  @Override
  public Optional<ConversationId> findParkConversation(ParkToken token) {
    Objects.requireNonNull(token, "token must not be null");
    return withConnection(
        connection -> {
          try (PreparedStatement ps =
              connection.prepareStatement(
                  "SELECT conversation_id FROM nessy_park WHERE token = ?")) {
            ps.setString(1, token.value());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                return Optional.empty();
              }
              return Optional.of(new ConversationId(rs.getString("conversation_id")));
            }
          }
        });
  }

  @Override
  public boolean consumeToken(ParkToken token) {
    Objects.requireNonNull(token, "token must not be null");
    return withConnection(
        connection -> {
          try (PreparedStatement ps =
              connection.prepareStatement(
                  "INSERT INTO nessy_token (token) VALUES (?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, token.value());
            return ps.executeUpdate() == 1;
          }
        });
  }

  private int update(Connection connection, String sql, SqlConsumer<PreparedStatement> binder)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      binder.accept(ps);
      return ps.executeUpdate();
    }
  }

  /** Runs {@code body} on its own auto-commit connection; no transaction, one statement. */
  private <T> T withConnection(SqlFunction<Connection, T> body) {
    try (Connection connection = dataSource.getConnection()) {
      return body.apply(connection);
    } catch (SQLException e) {
      throw new IllegalStateException("jdbc conversation store operation failed", e);
    }
  }

  /**
   * Runs {@code body} inside one explicit transaction at {@code isolationLevel}, committing on a
   * normal return and rolling back on any exception — a {@link StaleStateException} included, so
   * the caller's failed save leaves no partial effect behind either.
   */
  private <T> T inTransaction(int isolationLevel, SqlFunction<Connection, T> body) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(isolationLevel);
      try {
        T result = body.apply(connection);
        connection.commit();
        return result;
      } catch (SQLException e) {
        connection.rollback();
        throw new IllegalStateException("jdbc conversation store operation failed", e);
      } catch (RuntimeException e) {
        connection.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("jdbc conversation store operation failed", e);
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
