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
 * <p>A duplicate key is recognized by SQLState <b>and</b> vendor error code together, not by
 * SQLState alone: the {@code 23xxx} ANSI integrity-constraint-violation class is not exclusively a
 * duplicate-key signal. Oracle in particular treats an empty string {@code ''} as {@code NULL}, so
 * a {@code NOT NULL} column bound to {@code ""} (an empty {@code agent_name}, an empty summary)
 * raises {@code ORA-01400} — SQLState {@code 23000}, the very same class a duplicate key raises
 * there. Swallowing every {@code 23xxx} as "duplicate, no-op" would have silently dropped that
 * write instead of surfacing the real NOT-NULL violation — a lost park is a conversation nothing
 * can ever resume, never an acceptable no-op. So {@link #isDuplicateKey} requires the SQLState
 * family <b>and</b> the exact vendor error code each dialect's genuine duplicate-key path is
 * verified live to raise:
 *
 * <ul>
 *   <li>Postgres: SQLState {@code 23505} exactly ({@code unique_violation} — Postgres's own
 *       SQLState already disambiguates duplicate keys from every other {@code 23xxx} cause, so no
 *       vendor code check is needed there).
 *   <li>MySQL/MariaDB: vendor error {@code 1062} ({@code ER_DUP_ENTRY}).
 *   <li>SQL Server: vendor error {@code 2601} or {@code 2627} (unique index / unique constraint).
 *   <li>Oracle: vendor error {@code 1} (ORA-00001) — distinct from the {@code 1400} (ORA-01400, NOT
 *       NULL) an empty-string bind raises, both under SQLState {@code 23000}.
 * </ul>
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
   * {@code true} if the row landed, {@code false} if {@code dialect}'s own genuine duplicate-key
   * signal rejected it as the documented no-op. Any other {@link SQLException} — including a {@code
   * 23xxx} SQLState that is not actually a duplicate key, e.g. Oracle's NOT-NULL-via-empty- string
   * {@code ORA-01400} — propagates unchanged.
   */
  static boolean attempt(
      Connection connection, JdbcDialect dialect, String sql, SqlConsumer<PreparedStatement> binder)
      throws SQLException {
    boolean inExplicitTransaction = !connection.getAutoCommit();
    Savepoint savepoint = inExplicitTransaction ? connection.setSavepoint() : null;
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      binder.accept(ps);
      ps.executeUpdate();
      return true;
    } catch (SQLException e) {
      if (isDuplicateKey(dialect, e)) {
        if (savepoint != null) {
          connection.rollback(savepoint);
        }
        return false;
      }
      throw e;
    }
  }

  /**
   * The narrow, per-dialect duplicate-key test: the {@code 23xxx} SQLState family is the outer
   * gate, but never the whole test by itself — see the class javadoc for why (Oracle's
   * empty-string-is-NULL trap is the concrete case that forced this).
   */
  static boolean isDuplicateKey(JdbcDialect dialect, SQLException e) {
    String sqlState = e.getSQLState();
    if (sqlState == null || !sqlState.startsWith("23")) {
      return false;
    }
    int vendorCode = e.getErrorCode();
    return switch (dialect) {
      case POSTGRES -> "23505".equals(sqlState);
      case MYSQL, MARIADB -> vendorCode == 1062;
      case SQLSERVER -> vendorCode == 2601 || vendorCode == 2627;
      case ORACLE -> vendorCode == 1;
    };
  }

  @FunctionalInterface
  interface SqlConsumer<A> {
    void accept(A input) throws SQLException;
  }
}
