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
package org.jwcarman.nessy.examples.chatweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.backend.jdbc.Schemas;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Every table Nessy creates, as this application sees them: the backend's, the notebook's and the
 * plan's.
 *
 * <p>The guard is an allowlist of the columns that may be text, rather than a list of the columns
 * that must be bytes. A new content column added as {@code TEXT} is not on the list, so the test
 * fails and the person adding it has to decide whether it is content (make it {@code BYTEA} and
 * encode it) or an identifier, a status or plumbing (name it here, with the reason). A list of
 * bytes columns alone would let the same mistake through silently.
 */
@DisplayName("The columns Nessy creates")
class ContentColumnsTest {

  /** Columns that are not content: each finds, orders or fences a row, or is plumbing. */
  private static final Set<String> NOT_CONTENT =
      Set.of(
          // The agent's type and id name whose row it is, and the keys every query goes by.
          "nessy_agent.agent_type",
          "nessy_agent_event.agent_type",
          "nessy_agent_backlog.agent_type",
          "nessy_agent_effect.agent_type",
          "nessy_agent_turn.agent_type",
          "nessy_chapter.agent_type",
          "nessy_lease.agent_type",
          "nessy_note.agent_type",
          "nessy_note.agent_id",
          "nessy_plan_task.agent_type",
          "nessy_plan_task.agent_id",
          // A note's minted id, which is how the model names it back.
          "nessy_note.note_id",
          // A fixed word, not a sentence; the plan's view and the queries read it.
          "nessy_plan_task.status",
          "nessy_agent_effect.status",
          // What kind of lease it is, a short name from the code.
          "nessy_lease.kind",
          // A turn's outcome is one fixed word, and its trajectory hash is hex a query matches on.
          "nessy_agent_turn.outcome",
          "nessy_agent_turn.trajectory_hash",
          // Tool names and outcome words: the trajectory's structure, not content.
          "nessy_agent_turn.trajectory",
          // What a payload is, BLOCKS or DOCUMENT; a fixed word from the code.
          "nessy_payload.kind",
          // Observability plumbing: a W3C trace parent, never what anybody said.
          "nessy_agent_effect.trace_context");

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  private static JdbcClient jdbc;

  @BeforeAll
  static void startDatabase() {
    POSTGRES.start();
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    jdbc = JdbcClient.create(database);
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  private static Set<String> columnsOfType(String condition) {
    return new TreeSet<>(
        jdbc.sql(
                "SELECT table_name || '.' || column_name FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND table_name LIKE 'nessy\\_%' AND "
                    + condition)
            .query(String.class)
            .list());
  }

  @Test
  void any_text_column_is_one_that_is_known_not_to_hold_content() {
    Set<String> textColumns =
        columnsOfType(
            "data_type IN ('text', 'character varying', 'character', 'json', 'jsonb', 'xml', 'ARRAY')");

    assertThat(textColumns).isNotEmpty().isSubsetOf(NOT_CONTENT);
  }

  @Test
  void the_columns_that_hold_content_are_bytes() {
    Set<String> byteColumns = columnsOfType("data_type = 'bytea'");

    assertThat(byteColumns)
        .contains(
            "nessy_chapter.summary",
            "nessy_note.hook",
            "nessy_note.body",
            "nessy_plan_task.title",
            "nessy_payload.content",
            "nessy_agent_event.payload",
            "nessy_agent_effect.payload",
            "nessy_agent_backlog.payload");
  }
}
