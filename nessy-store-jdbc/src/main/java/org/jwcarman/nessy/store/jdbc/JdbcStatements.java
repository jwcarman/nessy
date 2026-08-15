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

/**
 * The complete per-dialect SQL text {@link JdbcDialect} variance touches (design §4). Earlier this
 * class assembled statements by splicing small fragments — a JSON parameter placeholder, a
 * reserved-word column quoting wrinkle — into shared templates at prepare time. Sonar's data-flow
 * analysis cannot prove those fragments constant once spliced, and (worse) miscounts the resulting
 * statement's placeholders, so every fragment-assembled statement using two variable pieces read as
 * a dynamically-formatted SQL query (java:S2077) and a suspiciously-mismatched parameter count
 * (java:S2695) — both false positives, but Sonar has no way to know that from the spliced form.
 * Every statement below is instead a complete literal per dialect, chosen by a {@code switch} over
 * {@link JdbcDialect} with no {@code default} arm — the compiler, not a runtime fallback, is what
 * catches a sixth dialect arriving without its own literal here.
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
   * {@code nessy_conversation}'s fenced {@code UPDATE} (design §4): identical everywhere but the
   * {@code state} column's parameter placeholder, which Postgres alone must cast explicitly to
   * {@code jsonb} (its driver otherwise binds the parameter as {@code unknown} or {@code text} and
   * the server rejects the assignment); every other dialect accepts the bare {@code ?} the driver
   * already sends as a string.
   */
  String conversationUpdateSql() {
    return switch (dialect) {
      case POSTGRES ->
          "UPDATE nessy_conversation SET version = ?, state = ?::jsonb WHERE id = ? AND version = ?";
      case MYSQL, MARIADB, SQLSERVER, ORACLE ->
          "UPDATE nessy_conversation SET version = ?, state = ? WHERE id = ? AND version = ?";
    };
  }

  /**
   * {@code nessy_conversation}'s version-0 insert (design §4) — see {@link
   * #conversationUpdateSql()} for why Postgres alone needs the {@code ?::jsonb} cast.
   */
  String conversationInsertSql() {
    return switch (dialect) {
      case POSTGRES ->
          "INSERT INTO nessy_conversation (id, version, state) VALUES (?, ?, ?::jsonb)";
      case MYSQL, MARIADB, SQLSERVER, ORACLE ->
          "INSERT INTO nessy_conversation (id, version, state) VALUES (?, ?, ?)";
    };
  }

  /**
   * {@code nessy_inbox}'s append insert (design §4) — see {@link #conversationUpdateSql()} for why
   * Postgres alone needs the {@code ?::jsonb} cast.
   */
  String inboxInsertSql() {
    return switch (dialect) {
      case POSTGRES ->
          "INSERT INTO nessy_inbox (entry_id, conversation_id, kind, payload) VALUES (?, ?, ?, ?::jsonb)";
      case MYSQL, MARIADB, SQLSERVER, ORACLE ->
          "INSERT INTO nessy_inbox (entry_id, conversation_id, kind, payload) VALUES (?, ?, ?, ?)";
    };
  }

  /**
   * {@code nessy_transcript}'s append insert (design §4) — see {@link #conversationUpdateSql()} for
   * why Postgres alone needs the {@code ?::jsonb} cast.
   */
  String transcriptInsertSql() {
    return switch (dialect) {
      case POSTGRES ->
          "INSERT INTO nessy_transcript (conversation_id, version, message) VALUES (?, ?, ?::jsonb)";
      case MYSQL, MARIADB, SQLSERVER, ORACLE ->
          "INSERT INTO nessy_transcript (conversation_id, version, message) VALUES (?, ?, ?)";
    };
  }

  /**
   * {@code nessy_parks}'s insert (design §4): both the Postgres-only {@code ?::jsonb} cast (see
   * {@link #conversationUpdateSql()}) and the {@code call} column's reserved-word quoting wrinkle
   * apply here at once. {@code call} is bare on Postgres, SQL Server, and Oracle (all three
   * confirmed live to accept it as an ordinary column name) but backtick-quoted on MySQL/MariaDB,
   * where {@code CALL} is a reserved word their grammar will not accept bare in this position — a
   * MySQL-family wrinkle the design's audit did not anticipate, found running the actual schema
   * against a live container rather than by inspection.
   */
  String parksInsertSql() {
    return switch (dialect) {
      case POSTGRES ->
          "INSERT INTO nessy_parks (token, conversation_id, call, agent_name) VALUES (?, ?, ?::jsonb, ?)";
      case MYSQL, MARIADB ->
          "INSERT INTO nessy_parks (token, conversation_id, `call`, agent_name) VALUES (?, ?, ?, ?)";
      case SQLSERVER, ORACLE ->
          "INSERT INTO nessy_parks (token, conversation_id, call, agent_name) VALUES (?, ?, ?, ?)";
    };
  }

  /** {@code nessy_parks}'s by-token read (design §4) — see {@link #parksInsertSql()}. */
  String parksFindSql() {
    return switch (dialect) {
      case MYSQL, MARIADB ->
          "SELECT conversation_id, `call`, agent_name FROM nessy_parks WHERE token = ?";
      case POSTGRES, SQLSERVER, ORACLE ->
          "SELECT conversation_id, call, agent_name FROM nessy_parks WHERE token = ?";
    };
  }

  /** {@code nessy_parks}'s by-conversation read (design §4) — see {@link #parksInsertSql()}. */
  String parksForConversationSql() {
    return switch (dialect) {
      case MYSQL, MARIADB ->
          "SELECT token, `call`, agent_name FROM nessy_parks WHERE conversation_id = ?";
      case POSTGRES, SQLSERVER, ORACLE ->
          "SELECT token, call, agent_name FROM nessy_parks WHERE conversation_id = ?";
    };
  }

  /**
   * {@code nessy_transcript}'s last-row read-and-lock (design §4): the row {@link
   * JdbcTranscript#append} must serialize concurrent appends against. Same table, same columns,
   * same {@code WHERE conversation_id = ?} — only the limit-one-row-locked idiom itself differs:
   * Postgres/MySQL/MariaDB share {@code ORDER BY ... LIMIT 1 FOR UPDATE}; SQL Server has no {@code
   * FOR UPDATE} and locks via {@code WITH (UPDLOCK, ROWLOCK)} on a {@code TOP 1} instead; Oracle
   * cannot combine {@code FOR UPDATE} with its own row-limiting clause at all — confirmed only by
   * running the syntax against a real Oracle container, not by inspection: {@code ... FETCH FIRST 1
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
   * {@code nessy_transcript}'s page read (design §4 — an un-dialected {@code LIMIT} clause here is
   * a syntax error on SQL Server and Oracle, so this needs the same per-dialect treatment as the
   * row-lock above): the newest {@code limit} rows below {@code beforeVersion}, fetched
   * newest-first so the limiting clause keeps the right window (the caller reverses back to
   * ascending order). Postgres/MySQL/MariaDB share {@code ORDER BY version DESC LIMIT ?}; SQL
   * Server has no {@code LIMIT} and expresses it as {@code SELECT TOP (?) ...} — notably {@code
   * TOP}'s parameter binds <b>first</b>, before the {@code WHERE} clause's own two parameters,
   * which is exactly what {@link #transcriptPageLimitBindsFirst()} exists to tell the caller;
   * Oracle has no {@code LIMIT} or {@code TOP} and expresses it as trailing {@code OFFSET 0 ROWS
   * FETCH NEXT ? ROWS ONLY}, with the limit parameter binding last, in the same position
   * Postgres/MySQL/MariaDB already use.
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
   * {@code nessy_inbox}'s drain delete (design §4): one row at a time, by primary key — {@link
   * JdbcConversationStore#drainInbox} batches this statement with JDBC's own {@code addBatch()} /
   * {@code executeBatch()} rather than growing a dynamically-sized {@code IN (?, …, ?)} per call,
   * so the statement text itself is a constant, identical across every dialect and every drain
   * size.
   */
  static final String INBOX_DRAIN_DELETE_SQL =
      "DELETE FROM nessy_inbox WHERE conversation_id = ? AND entry_id = ?";
}
