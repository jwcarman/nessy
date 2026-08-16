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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.conversation.SubjectId;
import org.jwcarman.nessy.spi.notebook.Notebook;

/**
 * The durable {@link Notebook} over any of the five databases {@link JdbcDialect} knows (design
 * §2): one row per {@code (subject_id, name)} in {@code nessy_notebook}.
 *
 * <p>Unlike {@link JdbcPlanStore}, this store cannot assume a single writer per key: design §3's
 * concurrency note is explicit that conversations sharing a subject can {@link #save} concurrently,
 * so {@link #save} follows {@link JdbcSummaryStore}'s race-recovery upsert rather than {@link
 * JdbcPlanStore}'s delete-then-insert — an {@code UPDATE} first, falling back to an {@code INSERT}
 * via {@link WriteOnceInsert} only when that {@code UPDATE} touches no row, with a swallowed
 * duplicate-key insert (a concurrent saver's first write for the same key landing first) retrying
 * the same {@code UPDATE} rather than dropping this save's write. See {@link JdbcSummaryStore}'s
 * class javadoc for the full account of why that shape, not a vendor-specific {@code UPSERT}, is
 * this module's portable answer across Postgres/MySQL/MariaDB/SQL Server/Oracle.
 *
 * <p>{@code nessy_notebook} carries no {@code jsonb} (or equivalent) column, so — like {@link
 * JdbcSummaryStore} and {@link JdbcPlanStore} — every statement here is one dialect-independent
 * literal; a {@link JdbcDialect} matters only to {@link #create(DataSource, JdbcDialect)}, which
 * needs it to pick the right {@code notebook-schema.sql} resource to bootstrap, and to {@link
 * WriteOnceInsert#attempt}, which needs it to recognize each dialect's own duplicate-key signal.
 *
 * <p>The constructor alone does not create {@code nessy_notebook} — a caller pointing at a database
 * another process already bootstrapped should not pay a DDL round trip on every startup. Use {@link
 * #create(DataSource)} to bootstrap and construct in one call; its per-dialect schema resource's
 * guarded-create statement is safe to run more than once. As with {@link JdbcSummaryStore}, the
 * dialect is resolved once — at bootstrap for {@code create}, lazily and cached thereafter for the
 * plain constructor — and every {@code create}/constructor pair has an explicit-dialect overload
 * that skips resolution entirely.
 */
public final class JdbcNotebook implements Notebook {

  private static final String SUBJECT_NOT_NULL = "subject must not be null";

  private static final String HEADINGS_SQL =
      "SELECT name, hook, source FROM nessy_notebook WHERE subject_id = ? ORDER BY name";

  private static final String FIND_SQL =
      "SELECT hook, body, source FROM nessy_notebook WHERE subject_id = ? AND name = ?";

  private static final String UPDATE_SQL =
      "UPDATE nessy_notebook SET hook = ?, body = ?, source = ? WHERE subject_id = ? AND name = ?";

  private static final String INSERT_SQL =
      "INSERT INTO nessy_notebook (subject_id, name, hook, body, source) VALUES (?, ?, ?, ?, ?)";

  private static final String DELETE_SQL =
      "DELETE FROM nessy_notebook WHERE subject_id = ? AND name = ?";

  private final DataSource dataSource;

  /** See {@link JdbcConversationStore#dialect} for the resolve-once-then-cache discipline. */
  private volatile JdbcDialect dialect;

  public JdbcNotebook(DataSource dataSource) {
    this(dataSource, null);
  }

  /**
   * {@code null} means resolve lazily on first use, same as the two-arg constructor — a non-null
   * value bypasses resolution entirely. See the class javadoc.
   */
  public JdbcNotebook(DataSource dataSource, JdbcDialect dialect) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.dialect = dialect;
  }

  /**
   * Bootstraps {@code notebook-schema.sql} against {@code dataSource}, then returns a working
   * notebook.
   */
  public static JdbcNotebook create(DataSource dataSource) {
    return create(dataSource, null);
  }

  /** Bootstraps against an explicitly known {@code dialect} — see the class javadoc. */
  public static JdbcNotebook create(DataSource dataSource, JdbcDialect dialect) {
    JdbcDialect resolved =
        JdbcSchemaBootstrap.bootstrap(
            dataSource, JdbcNotebook.class, "notebook-schema.sql", dialect, "notebook");
    return new JdbcNotebook(dataSource, resolved);
  }

  @Override
  public List<Heading> headings(SubjectId subject) {
    Objects.requireNonNull(subject, SUBJECT_NOT_NULL);
    return withConnection(
        connection -> {
          List<Heading> headings = new ArrayList<>();
          try (PreparedStatement ps = connection.prepareStatement(HEADINGS_SQL)) {
            ps.setString(1, subject.value());
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                headings.add(
                    new Heading(
                        rs.getString("name"), rs.getString("hook"), rs.getString("source")));
              }
            }
          }
          return List.copyOf(headings);
        });
  }

  @Override
  public Optional<Entry> find(SubjectId subject, String name) {
    Objects.requireNonNull(subject, SUBJECT_NOT_NULL);
    Objects.requireNonNull(name, "name must not be null");
    return withConnection(
        connection -> {
          try (PreparedStatement ps = connection.prepareStatement(FIND_SQL)) {
            ps.setString(1, subject.value());
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                return Optional.empty();
              }
              return Optional.of(
                  new Entry(
                      name, rs.getString("hook"), rs.getString("body"), rs.getString("source")));
            }
          }
        });
  }

  /**
   * Upserts {@code entry} by {@code (subject, entry.name())}: an {@code UPDATE} first, falling back
   * to an {@code INSERT} only when that {@code UPDATE} touches no row — see the class javadoc for
   * why this, not {@link JdbcPlanStore}'s delete-then-insert, is the shape a facility with real
   * concurrent writers needs.
   */
  @Override
  public void save(SubjectId subject, Entry entry) {
    Objects.requireNonNull(subject, SUBJECT_NOT_NULL);
    Objects.requireNonNull(entry, "entry must not be null");
    withConnection(
        connection -> {
          JdbcDialect resolved = dialectFor(connection);
          SqlConsumer<PreparedStatement> updateBinder =
              ps -> {
                ps.setString(1, entry.hook());
                ps.setString(2, entry.body());
                ps.setString(3, entry.source());
                ps.setString(4, subject.value());
                ps.setString(5, entry.name());
              };
          int updated = update(connection, UPDATE_SQL, updateBinder);
          if (updated == 0) {
            boolean inserted =
                WriteOnceInsert.attempt(
                    connection,
                    resolved,
                    INSERT_SQL,
                    ps -> {
                      ps.setString(1, subject.value());
                      ps.setString(2, entry.name());
                      ps.setString(3, entry.hook());
                      ps.setString(4, entry.body());
                      ps.setString(5, entry.source());
                    });
            if (!inserted) {
              // Lost the insert race to a concurrent first-save of the same (subject, name): that
              // row exists now, so the update this save started with — which found nothing a
              // moment ago — applies cleanly the second time. Skipping this would silently drop
              // this save's write (see the class javadoc).
              update(connection, UPDATE_SQL, updateBinder);
            }
          }
          return null;
        });
  }

  @Override
  public void forget(SubjectId subject, String name) {
    Objects.requireNonNull(subject, SUBJECT_NOT_NULL);
    Objects.requireNonNull(name, "name must not be null");
    withConnection(
        connection -> {
          try (PreparedStatement ps = connection.prepareStatement(DELETE_SQL)) {
            ps.setString(1, subject.value());
            ps.setString(2, name);
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

  /** See {@link JdbcConversationStore#statementsFor(Connection)}. */
  private JdbcDialect dialectFor(Connection connection) throws SQLException {
    JdbcDialect resolved = dialect;
    if (resolved == null) {
      resolved = JdbcDialect.resolve(connection.getMetaData());
      dialect = resolved;
    }
    return resolved;
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
      throw new IllegalStateException("jdbc notebook operation failed", e);
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
