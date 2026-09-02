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
package org.jwcarman.nessy.store.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.memory.notebook.JdbcNotebook;
import org.jwcarman.nessy.memory.notebook.Notebook;
import org.jwcarman.nessy.memory.plan.JdbcPlanStore;
import org.jwcarman.nessy.memory.plan.Plan;
import org.jwcarman.nessy.memory.plan.PlanStore;
import org.jwcarman.nessy.spi.memory.TranscriptMemory;
import org.jwcarman.nessy.spi.store.Schemas;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Everything Nessy stores, against the database people actually deploy.
 *
 * <p>Each store is tested in its own module against H2, which is fast and runs in the default
 * build. H2 is not PostgreSQL, though, and the differences are the silent kind: it accepts {@code
 * BYTEA} but rejects {@code TIMESTAMPTZ}, and it reserves {@code key} where PostgreSQL does not. So
 * the parts that could differ by dialect — the shipped DDL, and each store's SQL — are re-run here
 * against the real thing.
 *
 * <p><b>The DDL is the shipped file, never a pasted copy.</b> {@link Schemas} finds every module's
 * {@code nessy-schema.sql} exactly as an application would, so what is proven here is the file a
 * user's migration tooling would apply.
 *
 * <p>Tagged {@code container} and therefore skipped by default: {@code clean verify} must pass with
 * no Docker. Run it with {@code ./mvnw test -Dnessy.excludedGroups=}.
 */
@Tag("container")
@Testcontainers
@DisplayName("Every store, on PostgreSQL")
class PostgresStoreCertificationTest {

  /**
   * Deliberately not the {@code -alpine} image.
   *
   * <p>musl's {@code strcoll} degenerates to a byte-order {@code strcmp}, so alpine hides
   * collation-dependent ordering bugs that a glibc PostgreSQL — Debian, RDS, essentially every
   * production deployment — would expose. Certifying against the image whose libc masks a class of
   * defect would be worse than not certifying at all.
   */
  @Container
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

  private static final AgentType TYPE = AgentType.of("chat");
  private static final AgentId AGENT = AgentId.of("agent-one");

  private DataSource database;

  @BeforeEach
  void fresh() {
    database = dataSource();
    Schemas.initialize(database);
    truncate();
  }

  @Nested
  @DisplayName("the shipped DDL")
  class TheSchema {

    @Test
    @DisplayName("every module's file applies to PostgreSQL")
    void the_tables_exist() {
      assertThat(tables())
          .contains(
              "nessy_claim",
              "nessy_reminder",
              "nessy_note",
              "nessy_plan_task",
              "nessy_transcript",
              "nessy_intent",
              "nessy_backlog",
              "nessy_pending_approvals");
    }

    /** Every statement is IF NOT EXISTS, which is what makes it safe to run on every start. */
    @Test
    void applying_it_twice_is_a_no_op() {
      Schemas.initialize(database);

      assertThat(tables()).contains("nessy_note");
    }
  }

  @Nested
  @DisplayName("the approvals projection")
  class TheApprovals {

    /**
     * The statement, not just the table.
     *
     * <p>It used to be {@code ON CONFLICT ... DO UPDATE ... WHERE}, which PostgreSQL accepts and H2
     * rejects — so the projection worked here and could not park a single approval on the database
     * the starter builds by default. Certifying the write on both is what makes that a test failure
     * rather than a support ticket.
     */
    @Test
    void asking_twice_refreshes_the_address_without_making_a_second_row() {
      var repository =
          new org.jwcarman.nessy.spring.boot.PendingApprovalsRepository(
              new org.springframework.jdbc.core.JdbcTemplate(database));
      var asked = java.time.Instant.parse("2026-09-01T12:00:00Z");
      var question =
          new org.jwcarman.nessy.spring.boot.PendingApproval(
              "c1",
              "watchman",
              "house-12",
              "prune_images",
              "docker image prune -af",
              asked,
              asked.plusSeconds(3600),
              "token-1",
              java.util.Optional.empty(),
              java.util.Optional.empty(),
              java.util.Optional.empty());

      repository.asked(question);
      repository.asked(
          new org.jwcarman.nessy.spring.boot.PendingApproval(
              "c1",
              "watchman",
              "house-12",
              "prune_images",
              "docker image prune -af",
              asked,
              asked.plusSeconds(7200),
              "token-2",
              java.util.Optional.empty(),
              java.util.Optional.empty(),
              java.util.Optional.empty()));

      assertThat(repository.pending()).hasSize(1);
      assertThat(repository.byCallId("watchman", "house-12", "c1").orElseThrow().replyToken())
          .isEqualTo("token-2");
    }

    @Test
    void an_answer_stops_it_waiting() {
      var repository =
          new org.jwcarman.nessy.spring.boot.PendingApprovalsRepository(
              new org.springframework.jdbc.core.JdbcTemplate(database));
      var asked = java.time.Instant.parse("2026-09-01T12:00:00Z");
      repository.asked(
          new org.jwcarman.nessy.spring.boot.PendingApproval(
              "c2",
              "watchman",
              "house-12",
              "restart",
              "restart prod-1",
              asked,
              asked.plusSeconds(3600),
              "token-3",
              java.util.Optional.empty(),
              java.util.Optional.empty(),
              java.util.Optional.empty()));

      repository.answered(
          "watchman", "house-12", "c2", "denied", "not tonight", asked.plusSeconds(60));

      assertThat(repository.pending()).isEmpty();
      assertThat(repository.byCallId("watchman", "house-12", "c2").orElseThrow().answer())
          .contains("denied");
    }
  }

  @Nested
  @DisplayName("the notebook")
  class TheNotebook {

    @Test
    void a_note_round_trips_and_the_index_carries_no_bodies() {
      Notebook notebook = new JdbcNotebook(database, TYPE);

      Notebook.Entry written = notebook.write(AGENT, "Prefers terse answers", "THE BODY");

      assertThat(notebook.find(AGENT, written.id()).orElseThrow().body()).isEqualTo("THE BODY");
      assertThat(notebook.headings(AGENT))
          .containsExactly(new Notebook.Heading(written.id(), "Prefers terse answers"));
    }

    @Test
    @DisplayName("insertion order survives, which the index depends on")
    void headings_come_back_in_the_order_written() {
      Notebook notebook = new JdbcNotebook(database, TYPE);
      notebook.write(AGENT, "first", "a");
      notebook.write(AGENT, "second", "b");
      notebook.write(AGENT, "third", "c");

      assertThat(notebook.headings(AGENT))
          .extracting(Notebook.Heading::hook)
          .containsExactly("first", "second", "third");
    }
  }

  @Nested
  @DisplayName("the plan")
  class ThePlan {

    @Test
    void saving_replaces_the_whole_list_in_order() {
      PlanStore plans = new JdbcPlanStore(database, TYPE);
      plans.save(
          AGENT,
          new Plan(
              List.of(
                  new Plan.Task("read", Plan.Status.DONE),
                  new Plan.Task("write", Plan.Status.IN_PROGRESS))));

      plans.save(AGENT, new Plan(List.of(new Plan.Task("ship", Plan.Status.PENDING))));

      assertThat(plans.find(AGENT).orElseThrow().tasks())
          .containsExactly(new Plan.Task("ship", Plan.Status.PENDING));
    }

    @Test
    void an_emptied_plan_reads_as_no_plan() {
      PlanStore plans = new JdbcPlanStore(database, TYPE);
      plans.save(AGENT, new Plan(List.of(new Plan.Task("read", Plan.Status.PENDING))));

      plans.save(AGENT, Plan.empty());

      assertThat(plans.find(AGENT)).isEmpty();
    }
  }

  @Nested
  @DisplayName("the transcript")
  class TheTranscript {

    @Test
    void everything_remembered_comes_back_in_order() {
      TranscriptMemory memory = TranscriptMemory.eternal(database, TYPE);
      memory.remember(AGENT, UserMessage.of("hello"));
      memory.remember(AGENT, new AnswerMessage(List.of(new TextBlock("hi"))));

      assertThat(memory.recall(AGENT).messages()).hasSize(2);
      assertThat(memory.recall(AGENT).messages().getFirst()).isEqualTo(UserMessage.of("hello"));
    }

    /**
     * The budget walk reads newest-first and stops. On PostgreSQL that is a DESC index scan, which
     * is exactly the plan H2 would not have exercised.
     */
    @Test
    @DisplayName("a bounded recall keeps the newest and forgets the rest, oldest first")
    void the_character_budget_is_honoured() {
      TranscriptMemory memory = TranscriptMemory.recent(database, TYPE, 12);
      memory.remember(AGENT, UserMessage.of("aaaaaaaaaa"));
      memory.remember(AGENT, UserMessage.of("bbbbbbbbbb"));

      assertThat(memory.recall(AGENT).messages()).containsExactly(UserMessage.of("bbbbbbbbbb"));
    }
  }

  private DataSource dataSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }

  /** One container serves every test, so each starts from an empty set of rows. */
  private void truncate() {
    JdbcClient jdbc = JdbcClient.create(database);
    for (String table : tables()) {
      jdbc.sql("TRUNCATE TABLE " + table).update();
    }
  }

  private List<String> tables() {
    return JdbcClient.create(database)
        .sql(
            "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name LIKE 'nessy_%'")
        .query(String.class)
        .list();
  }
}
