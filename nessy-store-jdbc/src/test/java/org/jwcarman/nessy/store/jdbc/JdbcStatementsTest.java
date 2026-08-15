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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Every per-dialect SQL fragment {@link JdbcStatements} carries, pinned offline as plain string
 * assertions — cheap, and catches a typo or a swapped dialect branch before a container ever has to
 * run the statement. {@link JdbcTranscript#page} shipped with a raw, un-dialected {@code LIMIT}
 * clause in the original Task 2 commit (a syntax error on SQL Server and Oracle, C-1 in the fix
 * round); this class exists so the next such gap fails here first.
 */
class JdbcStatementsTest {

  @Nested
  class Conversation_update {

    @Test
    void postgres_casts_the_state_column_to_jsonb() {
      assertThat(JdbcStatements.forDialect(JdbcDialect.POSTGRES).conversationUpdateSql())
          .isEqualTo(
              "UPDATE nessy_conversation SET version = ?, state = ?::jsonb"
                  + " WHERE id = ? AND version = ?");
    }

    @Test
    void every_other_dialect_uses_a_bare_placeholder() {
      String expected =
          "UPDATE nessy_conversation SET version = ?, state = ? WHERE id = ? AND version = ?";
      for (JdbcDialect dialect :
          new JdbcDialect[] {
            JdbcDialect.MYSQL, JdbcDialect.MARIADB, JdbcDialect.SQLSERVER, JdbcDialect.ORACLE
          }) {
        assertThat(JdbcStatements.forDialect(dialect).conversationUpdateSql()).isEqualTo(expected);
      }
    }
  }

  @Nested
  class Conversation_insert {

    @Test
    void postgres_casts_the_state_column_to_jsonb() {
      assertThat(JdbcStatements.forDialect(JdbcDialect.POSTGRES).conversationInsertSql())
          .isEqualTo("INSERT INTO nessy_conversation (id, version, state) VALUES (?, ?, ?::jsonb)");
    }

    @Test
    void every_other_dialect_uses_a_bare_placeholder() {
      String expected = "INSERT INTO nessy_conversation (id, version, state) VALUES (?, ?, ?)";
      for (JdbcDialect dialect :
          new JdbcDialect[] {
            JdbcDialect.MYSQL, JdbcDialect.MARIADB, JdbcDialect.SQLSERVER, JdbcDialect.ORACLE
          }) {
        assertThat(JdbcStatements.forDialect(dialect).conversationInsertSql()).isEqualTo(expected);
      }
    }
  }

  @Nested
  class Inbox_insert {

    @Test
    void postgres_casts_the_payload_column_to_jsonb() {
      assertThat(JdbcStatements.forDialect(JdbcDialect.POSTGRES).inboxInsertSql())
          .isEqualTo(
              "INSERT INTO nessy_inbox (entry_id, conversation_id, kind, payload)"
                  + " VALUES (?, ?, ?, ?::jsonb)");
    }

    @Test
    void every_other_dialect_uses_a_bare_placeholder() {
      String expected =
          "INSERT INTO nessy_inbox (entry_id, conversation_id, kind, payload) VALUES (?, ?, ?, ?)";
      for (JdbcDialect dialect :
          new JdbcDialect[] {
            JdbcDialect.MYSQL, JdbcDialect.MARIADB, JdbcDialect.SQLSERVER, JdbcDialect.ORACLE
          }) {
        assertThat(JdbcStatements.forDialect(dialect).inboxInsertSql()).isEqualTo(expected);
      }
    }
  }

  @Nested
  class Transcript_insert {

    @Test
    void postgres_casts_the_message_column_to_jsonb() {
      assertThat(JdbcStatements.forDialect(JdbcDialect.POSTGRES).transcriptInsertSql())
          .isEqualTo(
              "INSERT INTO nessy_transcript (conversation_id, version, message)"
                  + " VALUES (?, ?, ?::jsonb)");
    }

    @Test
    void every_other_dialect_uses_a_bare_placeholder() {
      String expected =
          "INSERT INTO nessy_transcript (conversation_id, version, message) VALUES (?, ?, ?)";
      for (JdbcDialect dialect :
          new JdbcDialect[] {
            JdbcDialect.MYSQL, JdbcDialect.MARIADB, JdbcDialect.SQLSERVER, JdbcDialect.ORACLE
          }) {
        assertThat(JdbcStatements.forDialect(dialect).transcriptInsertSql()).isEqualTo(expected);
      }
    }
  }

  @Nested
  class Parks_insert {

    @Test
    void postgres_casts_the_call_column_to_jsonb_and_leaves_the_column_name_bare() {
      assertThat(JdbcStatements.forDialect(JdbcDialect.POSTGRES).parksInsertSql())
          .isEqualTo(
              "INSERT INTO nessy_parks (token, conversation_id, call, agent_name)"
                  + " VALUES (?, ?, ?::jsonb, ?)");
    }

    @Test
    void my_sql_and_maria_db_backtick_quote_the_column_name() {
      String expected =
          "INSERT INTO nessy_parks (token, conversation_id, `call`, agent_name)"
              + " VALUES (?, ?, ?, ?)";
      assertThat(JdbcStatements.forDialect(JdbcDialect.MYSQL).parksInsertSql()).isEqualTo(expected);
      assertThat(JdbcStatements.forDialect(JdbcDialect.MARIADB).parksInsertSql())
          .isEqualTo(expected);
    }

    @Test
    void sql_server_and_oracle_leave_the_column_name_bare_with_a_plain_placeholder() {
      String expected =
          "INSERT INTO nessy_parks (token, conversation_id, call, agent_name) VALUES (?, ?, ?, ?)";
      assertThat(JdbcStatements.forDialect(JdbcDialect.SQLSERVER).parksInsertSql())
          .isEqualTo(expected);
      assertThat(JdbcStatements.forDialect(JdbcDialect.ORACLE).parksInsertSql())
          .isEqualTo(expected);
    }
  }

  @Nested
  class Parks_find {

    @Test
    void my_sql_and_maria_db_backtick_quote_the_call_column() {
      String expected =
          "SELECT conversation_id, `call`, agent_name FROM nessy_parks WHERE token = ?";
      assertThat(JdbcStatements.forDialect(JdbcDialect.MYSQL).parksFindSql()).isEqualTo(expected);
      assertThat(JdbcStatements.forDialect(JdbcDialect.MARIADB).parksFindSql()).isEqualTo(expected);
    }

    @Test
    void postgres_sql_server_and_oracle_leave_the_call_column_bare() {
      String expected = "SELECT conversation_id, call, agent_name FROM nessy_parks WHERE token = ?";
      assertThat(JdbcStatements.forDialect(JdbcDialect.POSTGRES).parksFindSql())
          .isEqualTo(expected);
      assertThat(JdbcStatements.forDialect(JdbcDialect.SQLSERVER).parksFindSql())
          .isEqualTo(expected);
      assertThat(JdbcStatements.forDialect(JdbcDialect.ORACLE).parksFindSql()).isEqualTo(expected);
    }
  }

  @Nested
  class Parks_for_conversation {

    @Test
    void my_sql_and_maria_db_backtick_quote_the_call_column() {
      String expected =
          "SELECT token, `call`, agent_name FROM nessy_parks WHERE conversation_id = ?";
      assertThat(JdbcStatements.forDialect(JdbcDialect.MYSQL).parksForConversationSql())
          .isEqualTo(expected);
      assertThat(JdbcStatements.forDialect(JdbcDialect.MARIADB).parksForConversationSql())
          .isEqualTo(expected);
    }

    @Test
    void postgres_sql_server_and_oracle_leave_the_call_column_bare() {
      String expected = "SELECT token, call, agent_name FROM nessy_parks WHERE conversation_id = ?";
      assertThat(JdbcStatements.forDialect(JdbcDialect.POSTGRES).parksForConversationSql())
          .isEqualTo(expected);
      assertThat(JdbcStatements.forDialect(JdbcDialect.SQLSERVER).parksForConversationSql())
          .isEqualTo(expected);
      assertThat(JdbcStatements.forDialect(JdbcDialect.ORACLE).parksForConversationSql())
          .isEqualTo(expected);
    }
  }

  @Nested
  class Transcript_last_row_for_update {

    @Test
    void postgres_my_sql_and_maria_db_share_limit_one_for_update() {
      String expected =
          "SELECT version, message FROM nessy_transcript WHERE conversation_id = ?"
              + " ORDER BY version DESC LIMIT 1 FOR UPDATE";
      assertThat(JdbcStatements.forDialect(JdbcDialect.POSTGRES).transcriptLastRowForUpdateSql())
          .isEqualTo(expected);
      assertThat(JdbcStatements.forDialect(JdbcDialect.MYSQL).transcriptLastRowForUpdateSql())
          .isEqualTo(expected);
      assertThat(JdbcStatements.forDialect(JdbcDialect.MARIADB).transcriptLastRowForUpdateSql())
          .isEqualTo(expected);
    }

    @Test
    void sql_server_locks_via_updlock_rowlock_on_top_one() {
      assertThat(JdbcStatements.forDialect(JdbcDialect.SQLSERVER).transcriptLastRowForUpdateSql())
          .isEqualTo(
              "SELECT TOP 1 version, message FROM nessy_transcript WITH (UPDLOCK, ROWLOCK)"
                  + " WHERE conversation_id = ? ORDER BY version DESC");
    }

    @Test
    void oracle_locks_by_rowid_found_via_an_unlocked_fetch_first_inner_query() {
      assertThat(JdbcStatements.forDialect(JdbcDialect.ORACLE).transcriptLastRowForUpdateSql())
          .isEqualTo(
              "SELECT version, message FROM nessy_transcript WHERE rowid = ("
                  + "SELECT rowid FROM nessy_transcript WHERE conversation_id = ?"
                  + " ORDER BY version DESC FETCH FIRST 1 ROWS ONLY) FOR UPDATE");
    }
  }

  @Nested
  class Transcript_page {

    @Test
    void postgres_my_sql_and_maria_db_share_limit() {
      String expected =
          "SELECT version, message FROM nessy_transcript WHERE conversation_id = ?"
              + " AND version < ? ORDER BY version DESC LIMIT ?";
      assertThat(JdbcStatements.forDialect(JdbcDialect.POSTGRES).transcriptPageSql())
          .isEqualTo(expected);
      assertThat(JdbcStatements.forDialect(JdbcDialect.MYSQL).transcriptPageSql())
          .isEqualTo(expected);
      assertThat(JdbcStatements.forDialect(JdbcDialect.MARIADB).transcriptPageSql())
          .isEqualTo(expected);
      assertThat(JdbcStatements.forDialect(JdbcDialect.POSTGRES).transcriptPageLimitBindsFirst())
          .isFalse();
    }

    @Test
    void sql_server_uses_top_with_the_limit_parameter_binding_first() {
      assertThat(JdbcStatements.forDialect(JdbcDialect.SQLSERVER).transcriptPageSql())
          .isEqualTo(
              "SELECT TOP (?) version, message FROM nessy_transcript WHERE conversation_id = ?"
                  + " AND version < ? ORDER BY version DESC");
      assertThat(JdbcStatements.forDialect(JdbcDialect.SQLSERVER).transcriptPageLimitBindsFirst())
          .isTrue();
    }

    @Test
    void oracle_uses_offset_fetch_next_with_the_limit_parameter_binding_last() {
      assertThat(JdbcStatements.forDialect(JdbcDialect.ORACLE).transcriptPageSql())
          .isEqualTo(
              "SELECT version, message FROM nessy_transcript WHERE conversation_id = ?"
                  + " AND version < ? ORDER BY version DESC OFFSET 0 ROWS FETCH NEXT ? ROWS"
                  + " ONLY");
      assertThat(JdbcStatements.forDialect(JdbcDialect.ORACLE).transcriptPageLimitBindsFirst())
          .isFalse();
    }
  }

  @Nested
  class Inbox_drain_delete {

    @Test
    void is_one_constant_statement_shared_by_every_dialect() {
      assertThat(JdbcStatements.INBOX_DRAIN_DELETE_SQL)
          .isEqualTo("DELETE FROM nessy_inbox WHERE conversation_id = ? AND entry_id = ?");
    }
  }

  @Test
  void for_dialect_rejects_a_null_dialect() {
    assertThatThrownBy(() -> JdbcStatements.forDialect(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("dialect");
  }
}
