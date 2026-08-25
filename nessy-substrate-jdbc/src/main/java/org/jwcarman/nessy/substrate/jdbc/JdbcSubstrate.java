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
package org.jwcarman.nessy.substrate.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.jwcarman.nessy.spi.substrate.SubstrateSupport;

/**
 * A PostgreSQL-backed {@link Substrate} over a plain {@link DataSource}: a connection is acquired
 * per unit of work and closed, exactly as {@code JdbcContinuumRepository} does — there is no
 * ambient-transaction participation. Schema is application-owned; run the classpath resource {@code
 * org/jwcarman/nessy/substrate/jdbc/nessy-postgresql.sql} before first use.
 *
 * <p>Document and journal timestamps ({@code updated_at}, {@code appended_at}) come from an
 * injected {@link Clock}, never SQL {@code now()} — {@code InMemorySubstrate} stamps in the JVM,
 * and the two implementations must agree for the shared contract battery to hold both to the same
 * standard. {@code TIMESTAMPTZ} is microsecond-resolution, so a nanosecond-precision {@link
 * Instant} passed to a write is rounded to the nearest microsecond on round-trip through {@link
 * #read} or {@link #entries}; this is a documented, deliberate loss of precision, not a silent one
 * — {@code updatedAt} and {@code appendedAt} are informational, since {@code version} is the CAS
 * token and {@code seq} is the journal's own order.
 *
 * <p>The shipped DDL pins {@code key} to the {@code "C"} collation on both tables, so {@code
 * #keys(String, int)}'s ascending order is byte order — matching {@code InMemorySubstrate}'s {@code
 * String.compareTo} and this interface's documented "ascending lexicographic order" — regardless of
 * the database's own default collation.
 *
 * <p>{@link #batch(java.util.List)} applies every op in one transaction, in list order, by calling
 * the same private per-shape methods that back {@link #write(String, String, byte[], long)}, {@link
 * #delete(String, String, long)}, and {@link #append(String, String, long, byte[])} — never a
 * second copy of their SQL. Any CAS or sequence miss throws {@link ConflictException}, which the
 * {@code inTransaction} helper's rollback then makes atomic across every op the batch touched.
 */
public final class JdbcSubstrate extends SubstrateSupport implements Substrate {

  private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";
  private static final String KIND_NULL_MESSAGE = "kind must not be null";
  private static final String KEY_NULL_MESSAGE = "key must not be null";
  private static final String PAYLOAD_NULL_MESSAGE = "payload must not be null";

  private static final String SELECT_DOCUMENT_SQL =
      "SELECT payload, version, updated_at FROM nessy_document WHERE kind = ? AND key = ?";
  private static final String INSERT_DOCUMENT_SQL =
      "INSERT INTO nessy_document (kind, key, payload, version, updated_at) "
          + "VALUES (?, ?, ?, 1, ?)";
  private static final String UPDATE_DOCUMENT_SQL =
      "UPDATE nessy_document SET payload = ?, version = version + 1, updated_at = ? "
          + "WHERE kind = ? AND key = ? AND version = ?";
  private static final String DELETE_DOCUMENT_SQL =
      "DELETE FROM nessy_document WHERE kind = ? AND key = ? AND version = ?";
  private static final String SELECT_KEYS_SQL =
      "SELECT key FROM nessy_document WHERE kind = ? ORDER BY key LIMIT ?";
  private static final String INSERT_JOURNAL_SQL =
      "INSERT INTO nessy_journal (kind, key, seq, payload, appended_at) VALUES (?, ?, ?, ?, ?)";
  private static final String SELECT_JOURNAL_SQL =
      "SELECT seq, payload, appended_at FROM nessy_journal "
          + "WHERE kind = ? AND key = ? AND seq >= ? ORDER BY seq";

  private final DataSource dataSource;
  private final Clock clock;

  /**
   * Binds this substrate to a data source, stamping every write with {@link Clock#systemUTC()}. No
   * schema is created or validated here — run {@code nessy-postgresql.sql} before first use.
   *
   * @param dataSource the PostgreSQL data source; the application owns pooling and schema
   */
  public JdbcSubstrate(DataSource dataSource) {
    this(dataSource, Clock.systemUTC());
  }

  /**
   * Binds this substrate to a data source and an explicit clock — the seam a test uses to control
   * {@code updatedAt}/{@code appendedAt} timestamps the way it controls {@code
   * InMemorySubstrate}'s.
   *
   * @param dataSource the PostgreSQL data source; the application owns pooling and schema
   * @param clock the clock every document's {@code updatedAt} and journal entry's {@code
   *     appendedAt} is stamped from
   */
  public JdbcSubstrate(DataSource dataSource, Clock clock) {
    super();
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T perform(Connection connection) throws SQLException;
  }

  private <T> T inTransaction(SqlWork<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        T result = work.perform(connection);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        rollbackPreserving(connection, e);
        throw e;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("substrate operation failed", e);
    }
  }

  /**
   * Rolls back {@code connection}, attaching a rollback failure to {@code original} as a suppressed
   * exception rather than letting it replace {@code original} — a caller must still see the {@link
   * ConflictException} (or whatever else failed the unit of work), not an unrelated {@link
   * SQLException} from the rollback itself.
   */
  private void rollbackPreserving(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      original.addSuppressed(rollbackFailure);
    }
  }

  @Override
  public Optional<Document> read(String kind, String key) {
    Objects.requireNonNull(kind, KIND_NULL_MESSAGE);
    Objects.requireNonNull(key, KEY_NULL_MESSAGE);
    return inTransaction(connection -> readDocument(connection, kind, key));
  }

  private Optional<Document> readDocument(Connection connection, String kind, String key)
      throws SQLException {
    try (PreparedStatement select = connection.prepareStatement(SELECT_DOCUMENT_SQL)) {
      select.setString(1, kind);
      select.setString(2, key);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new Document(
                row.getBytes("payload"),
                row.getLong("version"),
                row.getTimestamp("updated_at").toInstant()));
      }
    }
  }

  @Override
  public void write(String kind, String key, byte[] payload, long expectedVersion) {
    Objects.requireNonNull(kind, KIND_NULL_MESSAGE);
    Objects.requireNonNull(key, KEY_NULL_MESSAGE);
    Objects.requireNonNull(payload, PAYLOAD_NULL_MESSAGE);
    inTransaction(
        connection -> {
          writeDocument(connection, kind, key, payload, expectedVersion, clock.instant());
          return null;
        });
  }

  /**
   * Dispatches to {@link #insertDocument} or {@link #updateDocument} by {@code expectedVersion},
   * exactly as {@link #write(String, String, byte[], long)} does — the single code path both the
   * public method and {@link #batch(List)} call, so the two can never drift apart.
   */
  private void writeDocument(
      Connection connection,
      String kind,
      String key,
      byte[] payload,
      long expectedVersion,
      Instant now)
      throws SQLException {
    if (expectedVersion == 0) {
      insertDocument(connection, kind, key, payload, now);
    } else {
      updateDocument(connection, kind, key, payload, expectedVersion, now);
    }
  }

  private void insertDocument(
      Connection connection, String kind, String key, byte[] payload, Instant now)
      throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(INSERT_DOCUMENT_SQL)) {
      insert.setString(1, kind);
      insert.setString(2, key);
      insert.setBytes(3, payload);
      insert.setTimestamp(4, Timestamp.from(now));
      insert.executeUpdate();
    } catch (SQLException e) {
      // Detect by SQLSTATE, never by message text — matching on getMessage() breaks across
      // driver versions and locales.
      if (UNIQUE_VIOLATION_SQLSTATE.equals(e.getSQLState())) {
        throw new ConflictException(
            "stale write at kind="
                + kind
                + " key="
                + key
                + ": expected version 0 but a document already exists");
      }
      throw e;
    }
  }

  private void updateDocument(
      Connection connection,
      String kind,
      String key,
      byte[] payload,
      long expectedVersion,
      Instant now)
      throws SQLException {
    try (PreparedStatement update = connection.prepareStatement(UPDATE_DOCUMENT_SQL)) {
      update.setBytes(1, payload);
      update.setTimestamp(2, Timestamp.from(now));
      update.setString(3, kind);
      update.setString(4, key);
      update.setLong(5, expectedVersion);
      int affected = update.executeUpdate();
      if (affected == 0) {
        throw new ConflictException(
            "stale write at kind="
                + kind
                + " key="
                + key
                + ": expected version "
                + expectedVersion
                + " but found a different version");
      }
    }
  }

  @Override
  public void delete(String kind, String key, long expectedVersion) {
    Objects.requireNonNull(kind, KIND_NULL_MESSAGE);
    Objects.requireNonNull(key, KEY_NULL_MESSAGE);
    inTransaction(
        connection -> {
          deleteDocument(connection, kind, key, expectedVersion);
          return null;
        });
  }

  private void deleteDocument(Connection connection, String kind, String key, long expectedVersion)
      throws SQLException {
    int affected;
    try (PreparedStatement delete = connection.prepareStatement(DELETE_DOCUMENT_SQL)) {
      delete.setString(1, kind);
      delete.setString(2, key);
      delete.setLong(3, expectedVersion);
      affected = delete.executeUpdate();
    }
    if (affected > 0) {
      return;
    }
    // Zero affected rows is not automatically the conflict: Substrate#delete's own contract makes
    // deleting a genuinely absent document at expectedVersion == 0 an idempotent no-op success. A
    // literal DELETE ... WHERE version = ? affects zero rows for that exact case too, so existence
    // must be checked, within this same transaction, to tell it apart from a real conflict — a row
    // present at a different version, or absence at any other expectedVersion.
    Optional<Document> current = readDocument(connection, kind, key);
    if (current.isEmpty() && expectedVersion == 0) {
      return;
    }
    throw new ConflictException(
        "stale delete at kind="
            + kind
            + " key="
            + key
            + ": expected version "
            + expectedVersion
            + " but found a different version");
  }

  @Override
  public List<String> keys(String kind, int limit) {
    Objects.requireNonNull(kind, KIND_NULL_MESSAGE);
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be at least 1, was " + limit);
    }
    return inTransaction(
        connection -> {
          List<String> keys = new ArrayList<>();
          try (PreparedStatement select = connection.prepareStatement(SELECT_KEYS_SQL)) {
            select.setString(1, kind);
            select.setInt(2, limit);
            try (ResultSet row = select.executeQuery()) {
              while (row.next()) {
                keys.add(row.getString("key"));
              }
            }
          }
          return keys;
        });
  }

  @Override
  public void append(String kind, String key, long expectedSeq, byte[] payload) {
    Objects.requireNonNull(kind, KIND_NULL_MESSAGE);
    Objects.requireNonNull(key, KEY_NULL_MESSAGE);
    Objects.requireNonNull(payload, PAYLOAD_NULL_MESSAGE);
    inTransaction(
        connection -> {
          appendEntry(connection, kind, key, expectedSeq, payload, clock.instant());
          return null;
        });
  }

  /**
   * Inserts a journal entry, create-only: a primary-key violation on {@code (kind, key, seq)} is
   * the conflict. The single code path both {@link #append(String, String, long, byte[])} and
   * {@link #batch(List)} call.
   */
  private void appendEntry(
      Connection connection, String kind, String key, long seq, byte[] payload, Instant now)
      throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(INSERT_JOURNAL_SQL)) {
      insert.setString(1, kind);
      insert.setString(2, key);
      insert.setLong(3, seq);
      insert.setBytes(4, payload);
      insert.setTimestamp(5, Timestamp.from(now));
      insert.executeUpdate();
    } catch (SQLException e) {
      // Detect by SQLSTATE, never by message text — matching the document insert's own rule.
      if (UNIQUE_VIOLATION_SQLSTATE.equals(e.getSQLState())) {
        throw new ConflictException(
            "stale append at kind="
                + kind
                + " key="
                + key
                + ": an entry already exists at seq "
                + seq);
      }
      throw e;
    }
  }

  @Override
  public List<Entry> entries(String kind, String key, long fromSeq) {
    Objects.requireNonNull(kind, KIND_NULL_MESSAGE);
    Objects.requireNonNull(key, KEY_NULL_MESSAGE);
    return inTransaction(
        connection -> {
          List<Entry> entries = new ArrayList<>();
          try (PreparedStatement select = connection.prepareStatement(SELECT_JOURNAL_SQL)) {
            select.setString(1, kind);
            select.setString(2, key);
            select.setLong(3, fromSeq);
            try (ResultSet row = select.executeQuery()) {
              while (row.next()) {
                entries.add(
                    new Entry(
                        row.getLong("seq"),
                        row.getBytes("payload"),
                        row.getTimestamp("appended_at").toInstant()));
              }
            }
          }
          return entries;
        });
  }

  @Override
  public void batch(List<Op> ops) {
    Objects.requireNonNull(ops, "ops must not be null");
    List<Op> snapshot = List.copyOf(ops);
    inTransaction(
        connection -> {
          Instant now = clock.instant();
          for (Op op : snapshot) {
            applyOp(connection, op, now);
          }
          return null;
        });
  }

  /**
   * Applies one batch op by calling the exact same private method the corresponding single-op
   * public method calls, so a batched write/delete/append can never diverge from its standalone
   * counterpart's SQL.
   */
  private void applyOp(Connection connection, Op op, Instant now) throws SQLException {
    switch (op) {
      case Op.WriteDocument(String wKind, String wKey, byte[] wPayload, long wExpectedVersion) ->
          writeDocument(connection, wKind, wKey, wPayload, wExpectedVersion, now);
      case Op.DeleteDocument(String dKind, String dKey, long dExpectedVersion) ->
          deleteDocument(connection, dKind, dKey, dExpectedVersion);
      case Op.AppendEntry(String aKind, String aKey, long aSeq, byte[] aPayload) ->
          appendEntry(connection, aKind, aKey, aSeq, aPayload, now);
    }
  }
}
