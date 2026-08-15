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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.spi.memory.SummaryStore;

/**
 * The durable {@link SummaryStore} over any of the five databases {@link JdbcDialect} knows (design
 * §2): one row per conversation in {@code nessy_summary}, last write wins.
 *
 * <p>{@link #save} is an upsert, but not a vendor-specific one: it tries an {@code UPDATE} first,
 * and falls back to an {@code INSERT} only if that {@code UPDATE} touched no row — the same
 * duplicate-tolerant insert every write-once table in this module now shares (see {@link
 * WriteOnceInsert}), reused here even though this table is not write-once, because it happens to be
 * exactly the portable, no-vendor-syntax shape an upsert needs too. Postgres's original {@code ON
 * CONFLICT (conversation_id) DO UPDATE} does not survive this rewrite as a per-dialect variant;
 * design §4 never enumerated this upsert (the audit that produced design §1 missed it — {@code
 * nessy_summary} carries no {@code jsonb} column, so it never showed up in the {@code jsonb}/cast
 * inventory), but it is exactly as Postgres-specific as the write-once inserts design §4 does
 * enumerate, and needed the same fix for the same reason: MySQL/MariaDB/SQL Server/Oracle have no
 * portable equivalent to {@code ON CONFLICT ... DO UPDATE}. See the Task 2 report for this
 * deviation, called out there rather than folded in silently. There is no fencing here (design §10)
 * — a save that loses its own race (its fallback {@code INSERT} arriving after a concurrent saver's
 * {@code INSERT} already landed) simply drops that write rather than retry, the same "worst case,
 * one re-summarized tail" posture the class already had.
 *
 * <p>The constructor alone does not create {@code nessy_summary} — a caller pointing at a database
 * another process already bootstrapped should not pay a DDL round trip on every startup. Use {@link
 * #create(DataSource)} to bootstrap and construct in one call; its per-dialect schema resource's
 * guarded-create statement is safe to run more than once. As with {@link JdbcConversationStore},
 * the dialect is resolved once — at bootstrap for {@code create}, lazily and cached thereafter for
 * the plain constructor — and every {@code create}/constructor pair has an explicit-dialect
 * overload that skips resolution entirely.
 */
public final class JdbcSummaryStore implements SummaryStore {

  private final DataSource dataSource;

  /** See {@link JdbcConversationStore#dialect} for the resolve-once-then-cache discipline. */
  private volatile JdbcDialect dialect;

  public JdbcSummaryStore(DataSource dataSource) {
    this(dataSource, null);
  }

  /** Bypasses dialect resolution entirely — see the class javadoc. */
  public JdbcSummaryStore(DataSource dataSource, JdbcDialect dialect) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.dialect = dialect;
  }

  /**
   * Bootstraps {@code summary-schema.sql} against {@code dataSource}, then returns a working store.
   */
  public static JdbcSummaryStore create(DataSource dataSource) {
    return create(dataSource, null);
  }

  /** Bootstraps against an explicitly known {@code dialect} — see the class javadoc. */
  public static JdbcSummaryStore create(DataSource dataSource, JdbcDialect dialect) {
    JdbcDialect resolved =
        JdbcSchemaBootstrap.bootstrap(
            dataSource, JdbcSummaryStore.class, "summary-schema.sql", dialect, "summary");
    return new JdbcSummaryStore(dataSource, resolved);
  }

  @Override
  public Optional<Summary> find(ConversationId id) {
    Objects.requireNonNull(id, "id must not be null");
    return withConnection(
        connection -> {
          try (PreparedStatement ps =
              connection.prepareStatement(
                  "SELECT watermark, summary FROM nessy_summary WHERE conversation_id = ?")) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                return Optional.empty();
              }
              return Optional.of(new Summary(rs.getLong("watermark"), rs.getString("summary")));
            }
          }
        });
  }

  @Override
  public void save(ConversationId id, Summary summary) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(summary, "summary must not be null");
    withConnection(
        connection -> {
          int updated =
              update(
                  connection,
                  "UPDATE nessy_summary SET watermark = ?, summary = ? WHERE conversation_id = ?",
                  ps -> {
                    ps.setLong(1, summary.watermark());
                    ps.setString(2, summary.text());
                    ps.setString(3, id.value());
                  });
          if (updated == 0) {
            WriteOnceInsert.attempt(
                connection,
                "INSERT INTO nessy_summary (conversation_id, watermark, summary) VALUES (?, ?, ?)",
                ps -> {
                  ps.setString(1, id.value());
                  ps.setLong(2, summary.watermark());
                  ps.setString(3, summary.text());
                });
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
   * Runs {@code body} on a connection borrowed fresh from the pool; no transaction of its own, one
   * statement, autocommit exactly as the pool hands it back — the same discipline the other doors'
   * own {@code withConnection} follows, and for the same reason: a pool that does not reset a
   * connection between borrowers must never be handed back one still in a prior caller's
   * transaction state.
   */
  private <T> T withConnection(SqlFunction<Connection, T> body) {
    try (Connection connection = dataSource.getConnection()) {
      return body.apply(connection);
    } catch (SQLException e) {
      throw new IllegalStateException("jdbc summary store operation failed", e);
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
