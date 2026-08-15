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
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.InboxEntry;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.StaleStateException;

/**
 * The reference durable {@link ConversationStore}: plain JDBC, no Spring, no JPA — the house stance
 * — over any of the five databases {@link JdbcDialect} knows (design §2). See the {@code
 * schema.sql} resource next to this class, under each dialect's own subdirectory on the classpath,
 * for the tables it reads and writes: {@code nessy_conversation} (the fenced control block) and
 * {@code nessy_inbox} (the append-only inbox). The park-registry tables this class used to own are
 * retired from its responsibility (design §5: {@link JdbcParks} answers the callback door now, over
 * its own {@code nessy_parks} table).
 *
 * <p>The constructor alone does not create those tables — a caller pointing at a database another
 * process already bootstrapped should not pay a DDL round trip on every startup. Use {@link
 * #create(DataSource, ObjectMapper)} to bootstrap and construct in one call; its {@code CREATE
 * TABLE IF NOT EXISTS} / {@code CREATE INDEX IF NOT EXISTS} statements (or each dialect's own
 * idiomatic guarded-create, where the database has no such syntax — see the per-dialect schema
 * resource) are safe to run more than once.
 *
 * <p>The dialect itself is resolved once, not per statement: {@link #create(DataSource,
 * ObjectMapper)} resolves it at the same connection its bootstrap DDL runs over; the plain
 * constructor defers resolution to the first connection any real operation borrows, then caches it
 * for the life of this instance. Every {@code create}/constructor pair also has an explicit-dialect
 * overload that skips resolution entirely — for a driver whose metadata lies, or a caller that
 * already knows.
 *
 * <p>{@link #save} is the fence: an {@code UPDATE ... WHERE id = ? AND version = ?} that either
 * updates exactly the row the caller read, or updates nothing because someone else's save already
 * moved it — the database's row lock, not an in-process monitor, is what makes two concurrent
 * savers on the same conversation see exactly one winner. A version-0 save additionally tries an
 * insert for the case there is no row yet to match against; that insert is allowed to lose a race
 * to another insert (see {@link WriteOnceInsert}) rather than fail outright. Either way, a losing
 * save re-reads the column and fails loudly with {@link StaleStateException} rather than silently
 * doing nothing.
 */
public final class JdbcConversationStore implements ConversationStore {

  private final DataSource dataSource;
  private final StateCodec codec;

  /**
   * {@code null} until resolved: the plain constructor defers dialect resolution to the first
   * connection a real operation borrows (see {@link #statementsFor(Connection)}), caching the
   * result here afterward. An explicit-dialect construction populates this at construction time
   * instead, and {@link #statementsFor(Connection)} then never touches a connection's metadata at
   * all. A benign race is possible if two threads both find this {@code null} at once — both would
   * resolve independently, redundantly, and agree, since resolution is a pure function of the
   * connection's own metadata; nothing here needs a lock over that.
   */
  private volatile JdbcDialect dialect;

  public JdbcConversationStore(DataSource dataSource, ObjectMapper mapper) {
    this(dataSource, mapper, null);
  }

  /**
   * {@code null} means resolve lazily on first use, same as the two-arg constructor — a non-null
   * value bypasses resolution entirely. See the class javadoc.
   */
  public JdbcConversationStore(DataSource dataSource, ObjectMapper mapper, JdbcDialect dialect) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.codec = new StateCodec(Objects.requireNonNull(mapper, "mapper must not be null"));
    this.dialect = dialect;
  }

  /** Bootstraps {@code schema.sql} against {@code dataSource}, then returns a working store. */
  public static JdbcConversationStore create(DataSource dataSource, ObjectMapper mapper) {
    return create(dataSource, mapper, null);
  }

  /** Bootstraps against an explicitly known {@code dialect} — see the class javadoc. */
  public static JdbcConversationStore create(
      DataSource dataSource, ObjectMapper mapper, JdbcDialect dialect) {
    JdbcDialect resolved =
        JdbcSchemaBootstrap.bootstrap(
            dataSource, JdbcConversationStore.class, "schema.sql", dialect, "conversation store");
    return new JdbcConversationStore(dataSource, mapper, resolved);
  }

  @Override
  public Optional<Loaded> load(ConversationId id) {
    Objects.requireNonNull(id, "id must not be null");
    return inTransaction(
        this::consistentReadIsolationLevel,
        connection -> {
          Optional<ConversationState> row = readState(connection, id);
          List<InboxEntry> inbox = readInbox(connection, id);
          if (row.isEmpty() && inbox.isEmpty()) {
            return Optional.empty();
          }
          ConversationState state = row.orElseGet(() -> ConversationState.newConversation(id));
          return Optional.of(new Loaded(state, inbox));
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

  private List<InboxEntry> readInbox(Connection connection, ConversationId id) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "SELECT payload FROM nessy_inbox WHERE conversation_id = ? ORDER BY entry_id")) {
      ps.setString(1, id.value());
      try (ResultSet rs = ps.executeQuery()) {
        List<InboxEntry> entries = new ArrayList<>();
        while (rs.next()) {
          entries.add(codec.readInboxEntry(rs.getString("payload")));
        }
        return List.copyOf(entries);
      }
    }
  }

  @Override
  public ConversationState save(ConversationState state, Collection<String> drainedInboxIds) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(drainedInboxIds, "drainedInboxIds must not be null");
    ConversationId id = state.id();
    long expected = state.version();
    ConversationState bumped = state.withVersion(expected + 1);
    String json = codec.writeState(bumped);

    return inTransaction(
        connection -> Connection.TRANSACTION_READ_COMMITTED,
        connection -> {
          JdbcStatements statements = statementsFor(connection);
          int updated =
              update(
                  connection,
                  statements.conversationUpdateSql(),
                  ps -> {
                    ps.setLong(1, bumped.version());
                    ps.setString(2, json);
                    ps.setString(3, id.value());
                    ps.setLong(4, expected);
                  });

          if (updated == 0) {
            boolean inserted =
                expected == 0 && insertNewConversation(connection, statements, id, bumped, json);
            if (!inserted) {
              throw new StaleStateException(id, expected, currentVersion(connection, id));
            }
          }

          drainInbox(connection, statements, id, drainedInboxIds);
          return bumped;
        });
  }

  private boolean insertNewConversation(
      Connection connection,
      JdbcStatements statements,
      ConversationId id,
      ConversationState bumped,
      String json)
      throws SQLException {
    return WriteOnceInsert.attempt(
        connection,
        statements.dialect(),
        statements.conversationInsertSql(),
        ps -> {
          ps.setString(1, id.value());
          ps.setLong(2, bumped.version());
          ps.setString(3, json);
        });
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

  /**
   * How many {@code DELETE}s {@link #drainInbox} batches into one {@link
   * PreparedStatement#executeBatch()} round trip before flushing. JDBC batching binds each drained
   * id's {@code DELETE} as its own statement execution rather than growing a single statement's
   * parameter list, so unlike the retired dynamic {@code IN (?, …, ?)} this constant is not a
   * vendor parameter ceiling — no single execution ever carries more than {@link
   * JdbcStatements#INBOX_DRAIN_DELETE_SQL}'s fixed two parameters. It exists purely to cap how much
   * unflushed batch state (and driver-side buffering) one drain accumulates before a round trip,
   * same rationale as any batch-insert loop.
   */
  private static final int DRAIN_BATCH_SIZE = 500;

  /**
   * Deletes exactly the drained entries — a no-op if there is nothing to drain. Each drained id's
   * {@code DELETE FROM nessy_inbox WHERE conversation_id = ? AND entry_id = ?} is added to one
   * {@link PreparedStatement} batch and flushed every {@link #DRAIN_BATCH_SIZE} entries (and once
   * more at the end for the remainder), all on this same {@code connection}, inside the one
   * explicit transaction {@link #save} already opened — so the whole drain stays atomic with the
   * save it accompanies regardless of how many entries it drained or how many batch flushes that
   * took.
   */
  private void drainInbox(
      Connection connection,
      JdbcStatements statements,
      ConversationId id,
      Collection<String> drainedInboxIds)
      throws SQLException {
    if (drainedInboxIds.isEmpty()) {
      return;
    }
    try (PreparedStatement ps =
        connection.prepareStatement(JdbcStatements.INBOX_DRAIN_DELETE_SQL)) {
      int pending = 0;
      for (String entryId : drainedInboxIds) {
        ps.setString(1, id.value());
        ps.setString(2, entryId);
        ps.addBatch();
        pending++;
        if (pending == DRAIN_BATCH_SIZE) {
          checkBatchResults(ps.executeBatch(), id);
          pending = 0;
        }
      }
      if (pending > 0) {
        checkBatchResults(ps.executeBatch(), id);
      }
    }
  }

  /**
   * {@code executeBatch()} is not obligated to throw on a failed element: the JDBC spec also
   * permits a driver to continue past one and report {@link Statement#EXECUTE_FAILED} (a negative
   * count other than {@link Statement#SUCCESS_NO_INFO}) for that element in the array it returns,
   * with the batch call itself returning normally. Left unchecked, that path would commit the
   * surrounding transaction with the failed delete's entry still sitting in {@code nessy_inbox},
   * undrained forever — nothing else ever revisits it. Scanning the counts here turns that silent
   * partial failure into a thrown {@link SQLException}, which rolls back this drain's transaction
   * (the same one {@link #save} opened) and lets the standard stale-retry machinery re-drive the
   * whole save, drain included.
   */
  private static void checkBatchResults(int[] counts, ConversationId id) throws SQLException {
    int failed = 0;
    for (int count : counts) {
      if (count < 0 && count != Statement.SUCCESS_NO_INFO) {
        failed++;
      }
    }
    if (failed > 0) {
      throw new SQLException(
          "inbox drain batch reported "
              + failed
              + " failed delete(s) for conversation "
              + id.value()
              + " without throwing");
    }
  }

  @Override
  public void append(ConversationId id, InboxEntry entry) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(entry, "entry must not be null");
    String kind = entry instanceof InboxEntry.Told ? "told" : "resolved";
    withConnection(
        connection -> {
          JdbcStatements statements = statementsFor(connection);
          try (PreparedStatement ps = connection.prepareStatement(statements.inboxInsertSql())) {
            ps.setString(1, entry.id());
            ps.setString(2, id.value());
            ps.setString(3, kind);
            ps.setString(4, codec.writeInboxEntry(entry));
            ps.executeUpdate();
          }
          return null;
        });
  }

  private int update(Connection connection, String sql, SqlConsumer<PreparedStatement> binder)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      binder.accept(ps);
      return ps.executeUpdate();
    }
  }

  /**
   * The dialect this store speaks, resolved once and cached — see {@link #dialect}'s javadoc for
   * the resolve-once-then-cache discipline this method implements.
   */
  private JdbcStatements statementsFor(Connection connection) throws SQLException {
    JdbcDialect resolved = dialect;
    if (resolved == null) {
      resolved = JdbcDialect.resolve(connection.getMetaData());
      dialect = resolved;
    }
    return JdbcStatements.forDialect(resolved);
  }

  /**
   * Runs {@code body} on a connection borrowed fresh from the pool; no transaction of its own, one
   * statement, autocommit exactly as the pool hands it back. That last clause is an invariant
   * {@link #inTransaction} owes this method, not something {@code withConnection} enforces itself:
   * a pool that does not reset a connection between borrowers would otherwise let a prior {@code
   * inTransaction} call's {@code setAutoCommit(false)} leak into whatever this method's next caller
   * does — an INSERT here would sit uncommitted and vanish, silently, when the connection closes
   * back into the pool.
   */
  private <T> T withConnection(SqlFunction<Connection, T> body) {
    try (Connection connection = dataSource.getConnection()) {
      return body.apply(connection);
    } catch (SQLException e) {
      throw new IllegalStateException("jdbc conversation store operation failed", e);
    }
  }

  /**
   * Runs {@code body} inside one explicit transaction at the isolation level {@code
   * isolationLevelFor} picks, committing on a normal return and rolling back on any exception — a
   * {@link StaleStateException} included, so the caller's failed save leaves no partial effect
   * behind either. Restores the borrowed connection's autocommit and isolation to what it had on
   * loan before returning it to the pool: on a non-resetting pool, handing back a connection still
   * in {@code autoCommit(false)} would silently discard the next borrower's own INSERT at close,
   * with nothing marking the loss — see {@link #withConnection}.
   *
   * <p>{@code isolationLevelFor} runs against the already-open connection, not before it — a fixed
   * level like {@link #save}'s {@code READ_COMMITTED} could be decided earlier, but {@link #load}'s
   * dialect-dependent choice (see {@link #consistentReadIsolationLevel}) needs {@link
   * #statementsFor}, which itself needs a live connection to resolve an as-yet-unresolved dialect
   * against.
   */
  private <T> T inTransaction(
      SqlFunction<Connection, Integer> isolationLevelFor, SqlFunction<Connection, T> body) {
    try (Connection connection = dataSource.getConnection()) {
      return runInTransaction(connection, isolationLevelFor, body);
    } catch (SQLException e) {
      throw new IllegalStateException("jdbc conversation store operation failed", e);
    }
  }

  /** The transaction body of {@link #inTransaction}, extracted so it is not a nested try block. */
  private static <T> T runInTransaction(
      Connection connection,
      SqlFunction<Connection, Integer> isolationLevelFor,
      SqlFunction<Connection, T> body)
      throws SQLException {
    int originalIsolation = connection.getTransactionIsolation();
    try {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(isolationLevelFor.apply(connection));
      T result = body.apply(connection);
      connection.commit();
      return result;
    } catch (SQLException e) {
      rollbackQuietly(connection, e);
      throw new IllegalStateException("jdbc conversation store operation failed", e);
    } catch (RuntimeException e) {
      rollbackQuietly(connection, e);
      throw e;
    } finally {
      restoreConnection(connection, originalIsolation);
    }
  }

  /**
   * {@link #load}'s isolation level: {@link Connection#TRANSACTION_REPEATABLE_READ} everywhere
   * except Oracle, which does not support it at all — confirmed only by running against a real
   * Oracle container, not by inspection: {@code connection.setTransactionIsolation
   * (TRANSACTION_REPEATABLE_READ)} raises {@code ORA-17030} ("READ_COMMITTED and SERIALIZABLE are
   * the only valid transaction levels"), Oracle's JDBC driver rejecting the level outright rather
   * than silently downgrading it. {@link Connection#TRANSACTION_SERIALIZABLE} is the closest level
   * Oracle actually offers — at least as strong as repeatable read, so {@link #load}'s combined
   * state-plus-inbox read still lands as one consistent snapshot there too, just via a stricter
   * isolation level than the other four dialects need for the same guarantee.
   */
  private int consistentReadIsolationLevel(Connection connection) throws SQLException {
    return statementsFor(connection).dialect() == JdbcDialect.ORACLE
        ? Connection.TRANSACTION_SERIALIZABLE
        : Connection.TRANSACTION_REPEATABLE_READ;
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
   * Best-effort: puts the connection's autocommit and isolation back the way {@code inTransaction}
   * found them, before it returns to the pool. A failure here is swallowed rather than thrown —
   * throwing from this {@code finally} would replace whatever exception is already propagating from
   * the transaction body above, and a connection too broken to restore is the pool's own problem to
   * evict on next borrow, not this method's to escalate.
   */
  private static void restoreConnection(Connection connection, int originalIsolation) {
    try {
      connection.setAutoCommit(true);
      connection.setTransactionIsolation(originalIsolation);
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
