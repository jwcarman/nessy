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
  class Json_placeholder {

    @Test
    void postgres_casts_to_jsonb() {
      assertThat(JdbcStatements.forDialect(JdbcDialect.POSTGRES).jsonPlaceholder())
          .isEqualTo("?::jsonb");
    }

    @Test
    void every_other_dialect_is_a_bare_placeholder() {
      for (JdbcDialect dialect :
          new JdbcDialect[] {
            JdbcDialect.MYSQL, JdbcDialect.MARIADB, JdbcDialect.SQLSERVER, JdbcDialect.ORACLE
          }) {
        assertThat(JdbcStatements.forDialect(dialect).jsonPlaceholder()).isEqualTo("?");
      }
    }
  }

  @Nested
  class Parked_call_column {

    @Test
    void my_sql_and_maria_db_backtick_quote_it() {
      assertThat(JdbcStatements.forDialect(JdbcDialect.MYSQL).parkedCallColumn())
          .isEqualTo("`call`");
      assertThat(JdbcStatements.forDialect(JdbcDialect.MARIADB).parkedCallColumn())
          .isEqualTo("`call`");
    }

    @Test
    void postgres_sql_server_and_oracle_leave_it_bare() {
      assertThat(JdbcStatements.forDialect(JdbcDialect.POSTGRES).parkedCallColumn())
          .isEqualTo("call");
      assertThat(JdbcStatements.forDialect(JdbcDialect.SQLSERVER).parkedCallColumn())
          .isEqualTo("call");
      assertThat(JdbcStatements.forDialect(JdbcDialect.ORACLE).parkedCallColumn())
          .isEqualTo("call");
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
    void builds_exactly_id_count_placeholders() {
      assertThat(JdbcStatements.forDialect(JdbcDialect.POSTGRES).inboxDrainDeleteSql(1))
          .isEqualTo("DELETE FROM nessy_inbox WHERE entry_id IN (?) AND conversation_id = ?");
      assertThat(JdbcStatements.forDialect(JdbcDialect.POSTGRES).inboxDrainDeleteSql(3))
          .isEqualTo("DELETE FROM nessy_inbox WHERE entry_id IN (?,?,?) AND conversation_id = ?");
    }

    @Test
    void is_the_same_shape_for_every_dialect() {
      for (JdbcDialect dialect : JdbcDialect.values()) {
        assertThat(JdbcStatements.forDialect(dialect).inboxDrainDeleteSql(2))
            .isEqualTo("DELETE FROM nessy_inbox WHERE entry_id IN (?,?) AND conversation_id = ?");
      }
    }

    @Test
    void zero_ids_is_rejected_rather_than_building_an_invalid_in_clause() {
      JdbcStatements statements = JdbcStatements.forDialect(JdbcDialect.POSTGRES);

      assertThatThrownBy(() -> statements.inboxDrainDeleteSql(0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("0");
    }

    @Test
    void a_negative_id_count_is_rejected_too() {
      JdbcStatements statements = JdbcStatements.forDialect(JdbcDialect.POSTGRES);

      assertThatThrownBy(() -> statements.inboxDrainDeleteSql(-1))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void for_dialect_rejects_a_null_dialect() {
    assertThatThrownBy(() -> JdbcStatements.forDialect(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("dialect");
  }
}
