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
import java.sql.SQLException;
import java.sql.Savepoint;

/**
 * The write-once unification design §4 calls for: every "insert unless a row with this key already
 * exists" write in this module — {@code nessy_conversation}'s version-0 insert ({@link
 * JdbcConversationStore}), {@code nessy_parks}'s idempotent {@code park} ({@link JdbcParks}) — used
 * to lean on Postgres's {@code ON CONFLICT DO NOTHING}. That syntax has no portable equivalent
 * across MySQL/MariaDB/SQL Server/Oracle, so all five dialects — Postgres included — now share this
 * one mechanism instead: attempt the plain {@code INSERT}, and treat a duplicate-key failure as the
 * documented no-op rather than an error. One code path, five databases, and the write-once
 * semantics live here in Java, next to the javadoc that already explains them, instead of five
 * different pieces of vendor SQL.
 *
 * <p>A duplicate key is recognized by SQLState alone, not by vendor error code: every driver this
 * module targets maps its native duplicate/unique-constraint error into the ANSI {@code 23xxx}
 * integrity-constraint-violation class — Postgres's {@code 23505} ({@code unique_violation}),
 * MySQL/MariaDB's {@code 23000} (native error 1062, {@code ER_DUP_ENTRY}), Oracle's {@code 23000}
 * (ORA-00001), and SQL Server's {@code 23000} (native errors 2601/2627, both unique-constraint-
 * or-index violations — the mssql-jdbc driver folds both into the same ANSI class rather than
 * exposing a vendor-specific SQLState). See the Task 2 report for the source of each of those
 * mappings; nothing here special-cases a vendor, which is the point.
 *
 * <p>Found live rather than anticipated: on Postgres, a statement that raises a real SQL error —
 * which a duplicate-key INSERT now does, unlike the retired {@code ON CONFLICT DO NOTHING}, which
 * never errored at all — poisons the rest of an explicit multi-statement transaction ("current
 * transaction is aborted, commands ignored until end of transaction block") until an explicit
 * rollback happens. {@link JdbcConversationStore#save} needs to keep working in the very same
 * transaction after a lost insert race (it still has to read the current version and drain the
 * inbox), so this method wraps the attempt in a savepoint when the connection is not in autocommit,
 * and rolls back to it — restoring the transaction to a usable state — on exactly the duplicate-key
 * branch. Under autocommit (every write-once insert this module runs outside an explicit
 * transaction, e.g. {@link JdbcParks#park}), a savepoint would itself be illegal to request (the
 * JDBC contract forbids {@link Connection#setSavepoint()} in autocommit mode) and is unnecessary
 * anyway — autocommit already isolates the failed statement to itself.
 */
final class WriteOnceInsert {

  private WriteOnceInsert() {}

  /**
   * Executes {@code sql} (bound by {@code binder}) as an insert allowed to lose a race: returns
   * {@code true} if the row landed, {@code false} if a duplicate key rejected it as the documented
   * no-op. Any other {@link SQLException} propagates unchanged — only a {@code 23xxx} SQLState is
   * ever swallowed.
   */
  static boolean attempt(Connection connection, String sql, SqlConsumer<PreparedStatement> binder)
      throws SQLException {
    boolean inExplicitTransaction = !connection.getAutoCommit();
    Savepoint savepoint = inExplicitTransaction ? connection.setSavepoint() : null;
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      binder.accept(ps);
      ps.executeUpdate();
      return true;
    } catch (SQLException e) {
      if (isDuplicateKey(e)) {
        if (savepoint != null) {
          connection.rollback(savepoint);
        }
        return false;
      }
      throw e;
    }
  }

  private static boolean isDuplicateKey(SQLException e) {
    String sqlState = e.getSQLState();
    return sqlState != null && sqlState.startsWith("23");
  }

  @FunctionalInterface
  interface SqlConsumer<A> {
    void accept(A input) throws SQLException;
  }
}
