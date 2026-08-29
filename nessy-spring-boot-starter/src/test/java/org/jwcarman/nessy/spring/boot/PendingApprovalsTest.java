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
package org.jwcarman.nessy.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentPhase;
import org.jwcarman.nessy.agent.AgentTransition;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.codec.Codecs;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The projection against a real PostgreSQL, statement for statement — because every interesting
 * thing about it is SQL: an upsert that must not clobber, a partial index, a {@code jsonb} column,
 * and two writes that may arrive in either order.
 *
 * <p>No harness here on purpose. This feeds {@link PendingApprovals} the facts a fold would have
 * published, which is the only contract it has, and lets {@code StarterOnPostgresTest} prove the
 * end-to-end path separately.
 */
@Tag("container")
@Testcontainers
class PendingApprovalsTest {

  /** Any deadline: this projection records when it SAW the fact, never the fact's own time. */
  private static final Instant DEADLINE = Instant.parse("2030-01-01T00:00:00Z");

  // glibc image, never -alpine: see JdbcSubstrateContractTest for why. Same image and tag as
  // DurableResumeTest, deliberately — one Postgres to pull for the whole reactor.
  @Container
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

  private static final String SCOPE = "prod-eu";
  private static final String TYPE = "ops";
  private static final ComputationId PARKED = ComputationId.of("computation-1");
  private static final AgentTransition NOWHERE = AgentTransition.to(new AgentPhase.Idle());

  private final ObjectMapper pinned = Codecs.copyAndPin(new ObjectMapper());
  private JdbcTemplate jdbc;
  private PendingApprovals projection;
  private PendingApprovalsRepository repository;

  record Restart(String target) {}

  @BeforeEach
  void freshTable() {
    jdbc = new JdbcTemplate(dataSource());
    jdbc.execute(shippedDdl());
    jdbc.execute("TRUNCATE TABLE nessy_pending_approvals");
    projection = new PendingApprovals(jdbc, pinned);
    repository = new PendingApprovalsRepository(jdbc);
  }

  @Nested
  class WhenAnApprovalParks {

    @Test
    void the_row_carries_the_coordinates_the_desk_answers_by() {
      projection.applied(AgentId.of(SCOPE), deferred(), NOWHERE);

      List<PendingApproval> pending = repository.pending();

      assertThat(pending).isNotEmpty();
      assertThat(pending)
          .singleElement()
          .satisfies(
              row -> {
                assertThat(row.computationId()).isEqualTo(PARKED.value());
                assertThat(row.agentType()).contains(TYPE);
                assertThat(row.agentId()).contains(SCOPE);
                assertThat(row.toolCallId()).contains("c1");
                assertThat(row.action()).contains("restart prod-eu");
                assertThat(row.parkedAt()).isPresent();
                assertThat(row.answer()).isEmpty();
                assertThat(row.isPending()).isTrue();
              });
    }

    @Test
    void the_frozen_request_is_stored_whole_as_the_evidence_it_is() throws IOException {
      projection.applied(AgentId.of(SCOPE), deferred(), NOWHERE);

      Optional<String> stored = repository.pending().getFirst().requestJson();

      assertThat(stored).isPresent();
      assertThat(pinned.readTree(stored.orElseThrow()))
          .isEqualTo(pinned.readTree(pinned.writeValueAsString(request())));
    }

    @Test
    void the_same_park_delivered_twice_is_still_one_row() {
      projection.applied(AgentId.of(SCOPE), deferred(), NOWHERE);
      projection.applied(AgentId.of(SCOPE), deferred(), NOWHERE);

      assertThat(repository.pending()).hasSize(1);
    }
  }

  @Nested
  class WhenTheDeskAnswers {

    @Test
    void an_approval_leaves_the_pending_list_and_joins_the_recent_one() {
      projection.applied(AgentId.of(SCOPE), deferred(), NOWHERE);

      projection.applied(
          AgentId.of(SCOPE), answered(new Approval.Approved(Optional.of("ticket-9"))), NOWHERE);

      assertThat(repository.pending()).isEmpty();
      List<PendingApproval> recent = repository.recent(50);
      assertThat(recent).isNotEmpty();
      assertThat(recent)
          .singleElement()
          .satisfies(
              row -> {
                assertThat(row.answer()).contains("approved");
                assertThat(row.reference()).contains("ticket-9");
                assertThat(row.note()).isEmpty();
                assertThat(row.answeredAt()).isPresent();
                assertThat(row.requestJson()).isPresent();
                assertThat(row.isPending()).isFalse();
              });
    }

    @Test
    void a_denial_keeps_its_reason_as_the_rows_note() {
      projection.applied(AgentId.of(SCOPE), deferred(), NOWHERE);

      projection.applied(
          AgentId.of(SCOPE), answered(Approval.denied("not during freeze")), NOWHERE);

      List<PendingApproval> recent = repository.recent(50);
      assertThat(recent).isNotEmpty();
      assertThat(recent)
          .singleElement()
          .satisfies(
              row -> {
                assertThat(row.answer()).contains("denied");
                assertThat(row.note()).contains("not during freeze");
              });
    }

    @Test
    void a_second_delivery_of_the_answer_never_rewrites_the_first() {
      projection.applied(AgentId.of(SCOPE), deferred(), NOWHERE);
      projection.applied(
          AgentId.of(SCOPE), answered(new Approval.Approved(Optional.of("first"))), NOWHERE);

      projection.applied(
          AgentId.of(SCOPE), answered(new Approval.Approved(Optional.of("second"))), NOWHERE);

      assertThat(repository.recent(50))
          .singleElement()
          .satisfies(row -> assertThat(row.reference()).contains("first"));
    }

    /**
     * An in-process approval carries no computation id at all — nothing parked, so there is nothing
     * to project. The table must stay empty rather than growing a row keyed on nothing.
     */
    @Test
    void an_answer_with_no_computation_id_projects_nothing() {
      projection.applied(
          AgentId.of(SCOPE),
          new AgentEvent.ApprovalAnswered(call(), Optional.empty(), Approval.approved()),
          NOWHERE);

      assertThat(repository.pending()).isEmpty();
      assertThat(repository.recent(50)).isEmpty();
    }
  }

  /**
   * {@code HarnessObserver}'s contract is explicit that publishes for one scope are NOT guaranteed
   * to arrive in commit order, so the answer can beat the park it answers. Both orders must end at
   * the same row.
   */
  @Nested
  class WhenTheFactsArriveOutOfOrder {

    @Test
    void an_answer_that_beats_its_park_waits_for_it_rather_than_being_lost() {
      projection.applied(
          AgentId.of(SCOPE), answered(new Approval.Approved(Optional.of("ticket-9"))), NOWHERE);

      assertThat(repository.pending()).isEmpty();
      List<PendingApproval> early = repository.recent(50);
      assertThat(early).isNotEmpty();
      assertThat(early)
          .singleElement()
          .satisfies(
              row -> {
                assertThat(row.answer()).contains("approved");
                assertThat(row.requestJson()).isEmpty();
              });

      projection.applied(AgentId.of(SCOPE), deferred(), NOWHERE);

      assertThat(repository.pending()).isEmpty();
      List<PendingApproval> settled = repository.recent(50);
      assertThat(settled).isNotEmpty();
      assertThat(settled)
          .singleElement()
          .satisfies(
              row -> {
                assertThat(row.answer()).contains("approved");
                assertThat(row.reference()).contains("ticket-9");
                assertThat(row.action()).contains("restart prod-eu");
                assertThat(row.requestJson()).isPresent();
              });
    }

    @Test
    void a_park_arriving_late_never_wipes_the_answer_that_beat_it() {
      projection.applied(AgentId.of(SCOPE), answered(Approval.denied("no")), NOWHERE);

      projection.applied(AgentId.of(SCOPE), deferred(), NOWHERE);

      assertThat(repository.pending()).isEmpty();
      assertThat(repository.recent(50))
          .singleElement()
          .satisfies(row -> assertThat(row.answer()).contains("denied"));
    }
  }

  @Nested
  class TheReadDoor {

    @Test
    void recent_refuses_a_meaningless_limit() {
      PendingApprovalsRepository reader = repository;

      assertThat(reader).isNotNull();
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> reader.recent(0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("limit");
    }
  }

  private static AgentEvent deferred() {
    return new AgentEvent.ApprovalDeferred(call(), PARKED, request(), DEADLINE);
  }

  private static AgentEvent answered(Approval answer) {
    return new AgentEvent.ApprovalAnswered(call(), Optional.of(PARKED), answer);
  }

  private static ToolCall call() {
    return new ToolCall(
        "c1", "restart_prod", JsonNodeFactory.instance.objectNode().put("target", SCOPE));
  }

  private static ApprovalRequest request() {
    return ApprovalRequest.draft(
            TYPE, SCOPE, call(), new Restart(SCOPE), Codecs.copyAndPin(new ObjectMapper()))
        .action("restart " + SCOPE)
        .freeze();
  }

  private static DataSource dataSource() {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    return dataSource;
  }

  /** The shipped DDL, verbatim — the file an application copies into its own migrations. */
  static String shippedDdl() {
    try (InputStream in =
        PendingApprovals.class.getResourceAsStream("pending-approvals-postgresql.sql")) {
      if (in == null) {
        throw new IllegalStateException(
            "pending-approvals-postgresql.sql not found beside PendingApprovals");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
