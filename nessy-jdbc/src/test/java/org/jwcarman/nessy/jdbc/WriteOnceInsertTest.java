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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link WriteOnceInsert#isDuplicateKey} and {@link WriteOnceInsert#attempt}, pinned without a
 * database: the duplicate-key test is narrower than "any {@code 23xxx} SQLState" — it requires the
 * SQLState family <b>and</b> the exact vendor error code each dialect's genuine duplicate-key path
 * is verified to raise. Oracle's empty-string-is-{@code NULL} trap (ORA-01400, also SQLState {@code
 * 23000}) is the concrete failure the wider check would have silently swallowed as a duplicate.
 * Every case here is a hand-rolled {@link SQLException} built with the exact (SQLState, vendor
 * code) pair a real driver reports — no mocking library, the same house stance the rest of this
 * module's tests take.
 */
class WriteOnceInsertTest {

  @Nested
  class Genuine_duplicates_swallow {

    @Test
    void postgres_23505_is_a_duplicate() {
      assertThat(WriteOnceInsert.isDuplicateKey(JdbcDialect.POSTGRES, duplicate("23505", 0)))
          .isTrue();
    }

    @Test
    void my_sql_23000_vendor_1062_is_a_duplicate() {
      assertThat(WriteOnceInsert.isDuplicateKey(JdbcDialect.MYSQL, duplicate("23000", 1062)))
          .isTrue();
    }

    @Test
    void maria_db_23000_vendor_1062_is_a_duplicate() {
      assertThat(WriteOnceInsert.isDuplicateKey(JdbcDialect.MARIADB, duplicate("23000", 1062)))
          .isTrue();
    }

    @Test
    void sql_server_23000_vendor_2627_is_a_duplicate() {
      assertThat(WriteOnceInsert.isDuplicateKey(JdbcDialect.SQLSERVER, duplicate("23000", 2627)))
          .isTrue();
    }

    @Test
    void sql_server_23000_vendor_2601_is_also_a_duplicate() {
      assertThat(WriteOnceInsert.isDuplicateKey(JdbcDialect.SQLSERVER, duplicate("23000", 2601)))
          .isTrue();
    }

    @Test
    void oracle_23000_vendor_1_is_a_duplicate() {
      assertThat(WriteOnceInsert.isDuplicateKey(JdbcDialect.ORACLE, duplicate("23000", 1)))
          .isTrue();
    }
  }

  @Nested
  class A_same_class_non_duplicate_never_swallows {

    /**
     * Oracle's own trap: binding {@code ""} to a {@code NOT NULL} column raises ORA-01400 —
     * SQLState {@code 23000}, the very class a duplicate key also raises there — but it is a lost
     * write waiting to happen if treated as a no-op, not a race any caller intended to tolerate.
     */
    @Test
    void oracle_23000_vendor_1400_not_null_is_not_a_duplicate() {
      assertThat(WriteOnceInsert.isDuplicateKey(JdbcDialect.ORACLE, duplicate("23000", 1400)))
          .isFalse();
    }

    @Test
    void my_sql_23000_with_a_foreign_key_vendor_code_is_not_a_duplicate() {
      // 1452: ER_NO_REFERENCED_ROW_2 — a foreign-key violation, same 23xxx class, different cause.
      assertThat(WriteOnceInsert.isDuplicateKey(JdbcDialect.MYSQL, duplicate("23000", 1452)))
          .isFalse();
    }

    @Test
    void postgres_23503_foreign_key_violation_is_not_a_duplicate() {
      assertThat(WriteOnceInsert.isDuplicateKey(JdbcDialect.POSTGRES, duplicate("23503", 0)))
          .isFalse();
    }

    @Test
    void sql_server_23000_with_an_unrelated_vendor_code_is_not_a_duplicate() {
      assertThat(WriteOnceInsert.isDuplicateKey(JdbcDialect.SQLSERVER, duplicate("23000", 515)))
          .isFalse();
    }

    @Test
    void a_null_sql_state_is_never_a_duplicate() {
      SQLException noState = new SQLException("driver gave up", null, 1062);
      assertThat(WriteOnceInsert.isDuplicateKey(JdbcDialect.MYSQL, noState)).isFalse();
    }
  }

  @Nested
  class Attempt_end_to_end_under_autocommit {

    @Test
    void a_genuine_duplicate_swallows_and_returns_false() throws SQLException {
      Connection autocommitConnection = connectionThatFailsPrepareWith(duplicate("23505", 0));

      boolean inserted =
          WriteOnceInsert.attempt(
              autocommitConnection, JdbcDialect.POSTGRES, "INSERT INTO t VALUES (?)", ps -> {});

      assertThat(inserted).isFalse();
    }

    @Test
    void oracle_s_not_null_shape_propagates_instead_of_swallowing() {
      SQLException notNull = duplicate("23000", 1400);
      Connection autocommitConnection = connectionThatFailsPrepareWith(notNull);

      assertThatThrownBy(
              () ->
                  WriteOnceInsert.attempt(
                      autocommitConnection,
                      JdbcDialect.ORACLE,
                      "INSERT INTO t VALUES (?)",
                      ps -> {}))
          .isSameAs(notNull);
    }

    /**
     * Hand-rolled: autocommit {@code true} (so {@link WriteOnceInsert#attempt} never asks for a
     * savepoint), {@code prepareStatement} handing back a {@link PreparedStatement} double whose
     * {@code executeUpdate} throws {@code failure}.
     */
    private Connection connectionThatFailsPrepareWith(SQLException failure) {
      PreparedStatement failingStatement =
          (PreparedStatement)
              Proxy.newProxyInstance(
                  PreparedStatement.class.getClassLoader(),
                  new Class<?>[] {PreparedStatement.class},
                  (proxy, method, args) ->
                      switch (method.getName()) {
                        case "executeUpdate" -> throw failure;
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                      });
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "getAutoCommit" -> true;
                    case "prepareStatement" -> failingStatement;
                    default -> throw new UnsupportedOperationException(method.getName());
                  });
    }
  }

  @Nested
  class Attempt_under_an_explicit_transaction {

    /**
     * The savepoint branch is {@link WriteOnceInsert}'s highest-risk mechanism (it exists at all
     * because a caught duplicate-key error poisons the rest of a Postgres transaction otherwise —
     * see the class javadoc) and deserves a test that names it directly: a genuine duplicate, hit
     * while {@code getAutoCommit()} is {@code false}, must take a savepoint before attempting the
     * insert and roll back to that <em>same</em> savepoint — not just "a" savepoint, the one it
     * itself took — on the duplicate-key branch.
     */
    @Test
    void a_genuine_duplicate_takes_a_savepoint_and_rolls_back_to_it() throws SQLException {
      AtomicBoolean savepointTaken = new AtomicBoolean();
      AtomicReference<Savepoint> rolledBackTo = new AtomicReference<>();
      Savepoint theSavepoint = savepointDouble();
      Connection connection =
          explicitTransactionConnection(
              savepointTaken, rolledBackTo, theSavepoint, duplicate("23505", 0));

      boolean inserted =
          WriteOnceInsert.attempt(
              connection, JdbcDialect.POSTGRES, "INSERT INTO t VALUES (?)", ps -> {});

      assertThat(inserted).isFalse();
      assertThat(savepointTaken).isTrue();
      assertThat(rolledBackTo.get()).isSameAs(theSavepoint);
    }

    /**
     * "Rolled back to the savepoint" and "the transaction is usable after" are two different claims
     * — a savepoint rollback that somehow left the connection unusable would still pass the test
     * above. This one keeps going: a second {@code attempt} on the very same connection, right
     * after the first one swallowed its duplicate, must succeed rather than inherit whatever broke.
     */
    @Test
    void the_connection_stays_usable_for_a_second_attempt_right_after_the_first_swallows()
        throws SQLException {
      AtomicBoolean savepointTaken = new AtomicBoolean();
      AtomicReference<Savepoint> rolledBackTo = new AtomicReference<>();
      Savepoint theSavepoint = savepointDouble();
      Connection connection =
          explicitTransactionConnection(
              savepointTaken, rolledBackTo, theSavepoint, duplicate("23505", 0));

      boolean firstAttempt =
          WriteOnceInsert.attempt(
              connection, JdbcDialect.POSTGRES, "INSERT INTO t VALUES (?)", ps -> {});
      boolean secondAttempt =
          WriteOnceInsert.attempt(
              connection, JdbcDialect.POSTGRES, "INSERT INTO t VALUES (?)", ps -> {});

      assertThat(firstAttempt).isFalse();
      assertThat(secondAttempt).isFalse();
    }

    private Savepoint savepointDouble() {
      return (Savepoint)
          Proxy.newProxyInstance(
              Savepoint.class.getClassLoader(),
              new Class<?>[] {Savepoint.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "getSavepointId" -> 1;
                    case "getSavepointName" -> "write-once-insert";
                    default -> throw new UnsupportedOperationException(method.getName());
                  });
    }

    /**
     * Hand-rolled: autocommit {@code false} (so {@link WriteOnceInsert#attempt} must take a
     * savepoint), {@code setSavepoint()} always hands back the same {@code theSavepoint} instance
     * (so identity comparison is meaningful), every {@code prepareStatement} call returns a fresh
     * {@link PreparedStatement} double whose {@code executeUpdate} throws {@code failure} — every
     * attempt on this connection loses its race the same way, on purpose, so a test can call {@code
     * attempt} more than once against it.
     */
    private Connection explicitTransactionConnection(
        AtomicBoolean savepointTaken,
        AtomicReference<Savepoint> rolledBackTo,
        Savepoint theSavepoint,
        SQLException failure) {
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "getAutoCommit" -> false;
                    case "setSavepoint" -> {
                      savepointTaken.set(true);
                      yield theSavepoint;
                    }
                    case "rollback" -> {
                      rolledBackTo.set((Savepoint) args[0]);
                      yield null;
                    }
                    case "prepareStatement" -> failingPreparedStatement(failure);
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                  });
    }

    private PreparedStatement failingPreparedStatement(SQLException failure) {
      return (PreparedStatement)
          Proxy.newProxyInstance(
              PreparedStatement.class.getClassLoader(),
              new Class<?>[] {PreparedStatement.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "executeUpdate" -> throw failure;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                  });
    }
  }

  private static SQLException duplicate(String sqlState, int vendorCode) {
    return new SQLException("simulated (test)", sqlState, vendorCode);
  }
}
