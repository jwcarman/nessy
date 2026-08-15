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

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The small set of SQL fragments that actually vary by {@link JdbcDialect} (design §4): a JSON
 * parameter placeholder, the transcript's last-row read-and-lock, the transcript's page read, and
 * the inbox drain's dynamically-sized delete — plus one fragment the design's audit did not
 * anticipate, {@link #parkedCallColumn()}, a reserved-word quoting wrinkle live verification
 * against MySQL turned up. Everything else — the fenced CAS {@code UPDATE}, every other plain read,
 * and the write-once inserts (unified onto one SQLState-and-vendor-code-driven code path in {@link
 * WriteOnceInsert} rather than varied per dialect) — is ANSI SQL already and needs no variant here.
 */
final class JdbcStatements {

  private final JdbcDialect dialect;

  private JdbcStatements(JdbcDialect dialect) {
    this.dialect = dialect;
  }

  static JdbcStatements forDialect(JdbcDialect dialect) {
    return new JdbcStatements(Objects.requireNonNull(dialect, "dialect must not be null"));
  }

  /** The dialect this instance was built for — {@link WriteOnceInsert} needs it too. */
  JdbcDialect dialect() {
    return dialect;
  }

  /**
   * The parameter placeholder a {@code jsonb}/{@code json}/{@code clob} column's bound value needs:
   * Postgres alone requires the explicit {@code ?::jsonb} cast (its driver otherwise binds the
   * parameter as {@code unknown} or {@code text} and the server rejects the assignment); every
   * other dialect accepts the bare {@code ?} the driver already sends as a string.
   */
  String jsonPlaceholder() {
    return dialect == JdbcDialect.POSTGRES ? "?::jsonb" : "?";
  }

  /**
   * The {@code nessy_parks.call} column's reference in SQL text: bare {@code call} on Postgres, SQL
   * Server, and Oracle (all three confirmed live to accept it as an ordinary column name), but
   * backtick-quoted on MySQL/MariaDB, where {@code CALL} is a reserved word their grammar will not
   * accept bare in this position — a MySQL-family wrinkle the design's audit did not anticipate,
   * found running the actual schema against a live container rather than by inspection. Not part of
   * design §4's enumerated variance list, but the same shape of variance: one identifier, one
   * per-dialect spelling.
   */
  String parkedCallColumn() {
    return switch (dialect) {
      case MYSQL, MARIADB -> "`call`";
      case POSTGRES, SQLSERVER, ORACLE -> "call";
    };
  }

  /**
   * {@code nessy_transcript}'s last-row read-and-lock (design §4): the row {@link
   * JdbcTranscript#append} must serialize concurrent appends against. Same table, same columns,
   * same {@code WHERE conversation_id = ?} — only the limit-one-row-locked idiom itself differs:
   * Postgres/MySQL/MariaDB share {@code ORDER BY ... LIMIT 1 FOR UPDATE}; SQL Server has no {@code
   * FOR UPDATE} and locks via {@code WITH (UPDLOCK, ROWLOCK)} on a {@code TOP 1} instead; Oracle
   * cannot combine {@code FOR UPDATE} with its own row-limiting clause at all — confirmed live
   * against a real Oracle container in Task 3's matrix (not caught by Task 2's fix round, which
   * pinned the syntax offline but never ran it against a real database): {@code ... FETCH FIRST 1
   * ROWS ONLY FOR UPDATE} raises {@code ORA-02014 ("cannot select FOR UPDATE from view with
   * DISTINCT, GROUP BY, etc.")}, because Oracle implements the row-limiting clause as an implicit
   * inline view internally, and {@code FOR UPDATE} refuses to lock through one. Oracle's fragment
   * instead finds the target row's {@code rowid} in an unlocked, row-limited inner query, then
   * locks by {@code rowid} equality in a plain outer query that carries no row-limiting clause of
   * its own for {@code FOR UPDATE} to object to.
   */
  String transcriptLastRowForUpdateSql() {
    return switch (dialect) {
      case POSTGRES, MYSQL, MARIADB ->
          "SELECT version, message FROM nessy_transcript WHERE conversation_id = ?"
              + " ORDER BY version DESC LIMIT 1 FOR UPDATE";
      case SQLSERVER ->
          "SELECT TOP 1 version, message FROM nessy_transcript WITH (UPDLOCK, ROWLOCK)"
              + " WHERE conversation_id = ? ORDER BY version DESC";
      case ORACLE ->
          "SELECT version, message FROM nessy_transcript WHERE rowid = ("
              + "SELECT rowid FROM nessy_transcript WHERE conversation_id = ?"
              + " ORDER BY version DESC FETCH FIRST 1 ROWS ONLY) FOR UPDATE";
    };
  }

  /**
   * {@code nessy_transcript}'s page read (design §4, added in Task 2's fix round — the original
   * commit left this one raw and it is a syntax error on SQL Server and Oracle): the newest {@code
   * limit} rows below {@code beforeVersion}, fetched newest-first so the limiting clause keeps the
   * right window (the caller reverses back to ascending order). Postgres/MySQL/MariaDB share {@code
   * ORDER BY version DESC LIMIT ?}; SQL Server has no {@code LIMIT} and expresses it as {@code
   * SELECT TOP (?) ...} — notably {@code TOP}'s parameter binds <b>first</b>, before the {@code
   * WHERE} clause's own two parameters, which is exactly what {@link
   * #transcriptPageLimitBindsFirst()} exists to tell the caller; Oracle has no {@code LIMIT} or
   * {@code TOP} and expresses it as trailing {@code OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY}, with the
   * limit parameter binding last, in the same position Postgres/MySQL/MariaDB already use.
   */
  String transcriptPageSql() {
    return switch (dialect) {
      case POSTGRES, MYSQL, MARIADB ->
          "SELECT version, message FROM nessy_transcript WHERE conversation_id = ?"
              + " AND version < ? ORDER BY version DESC LIMIT ?";
      case SQLSERVER ->
          "SELECT TOP (?) version, message FROM nessy_transcript WHERE conversation_id = ?"
              + " AND version < ? ORDER BY version DESC";
      case ORACLE ->
          "SELECT version, message FROM nessy_transcript WHERE conversation_id = ?"
              + " AND version < ? ORDER BY version DESC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY";
    };
  }

  /**
   * Whether {@link #transcriptPageSql()}'s limit parameter is the <b>first</b> {@code ?} to bind
   * (true only for SQL Server's {@code TOP (?)}, which sits before the {@code WHERE} clause in the
   * statement text) rather than the last (every other dialect, {@code LIMIT ?} / {@code FETCH NEXT
   * ? ROWS ONLY} trailing after the two {@code WHERE} parameters).
   */
  boolean transcriptPageLimitBindsFirst() {
    return dialect == JdbcDialect.SQLSERVER;
  }

  /**
   * {@code nessy_inbox}'s drain delete for exactly {@code idCount} drained entry ids (design §4):
   * Postgres's {@code entry_id = ANY(?)} array binding has no portable equivalent, so every dialect
   * — Postgres included — now shares one dynamically-sized {@code IN (?, …, ?)} instead; inbox
   * drains are small (one save's worth of entries), so the per-call statement text this builds
   * stays short. {@code idCount} must be at least 1 — callers with nothing to drain skip the delete
   * entirely rather than ask for an empty, invalid {@code IN ()}.
   */
  String inboxDrainDeleteSql(int idCount) {
    if (idCount < 1) {
      throw new IllegalArgumentException("idCount must be at least 1, was " + idCount);
    }
    String placeholders =
        Stream.generate(() -> "?").limit(idCount).collect(Collectors.joining(","));
    return "DELETE FROM nessy_inbox WHERE entry_id IN ("
        + placeholders
        + ") AND conversation_id = ?";
  }
}
