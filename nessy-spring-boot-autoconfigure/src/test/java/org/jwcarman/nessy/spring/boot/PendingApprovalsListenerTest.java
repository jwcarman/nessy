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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.testing.TestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * How narration and an approver's address become one row a page can render.
 *
 * <p>The two halves arrive separately and on purpose: narration says a question was asked, and it
 * does not say where to answer, because a reply address is authority rather than description and
 * must not travel in an event every subscriber sees.
 */
@DisplayName("Turning a parked call into something a person can answer")
class PendingApprovalsListenerTest {

  private static final AgentType WATCHMAN = AgentType.of("watchman");
  private static final AgentId HOUSE = AgentId.of("house-12");
  private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
  private static final Instant EXPIRES = NOW.plusSeconds(3600);

  private PendingApprovalsRepository repository;
  private PendingApprovalsListener listener;

  @BeforeEach
  void freshDatabase() {
    DataSource dataSource = TestDatabase.fresh();
    repository = new PendingApprovalsRepository(new JdbcTemplate(dataSource));
    listener =
        new PendingApprovalsListener(repository, WATCHMAN, HOUSE, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static AgentEvent.ApprovalRequested asked(String callId) {
    return new AgentEvent.ApprovalRequested(
        "e1", callId, "prune_images", "docker image prune -af", EXPIRES);
  }

  @Nested
  class BothHalvesArrive {

    @Test
    void a_question_with_an_address_becomes_a_row() {
      listener.expecting("c1", ReplyToken.of("token-1"));

      listener.on(asked("c1"));

      PendingApproval row = repository.byCallId("c1").orElseThrow();
      assertThat(row.tool()).isEqualTo("prune_images");
      assertThat(row.action()).isEqualTo("docker image prune -af");
      assertThat(row.agentType()).isEqualTo("watchman");
      assertThat(row.agentId()).isEqualTo("house-12");
      assertThat(row.replyToken()).isEqualTo("token-1");
      assertThat(row.expiresAt()).isEqualTo(EXPIRES);
    }

    @Test
    @DisplayName("a question with no address is not written, because the button could not work")
    void narration_alone_writes_nothing() {
      listener.on(asked("c1"));

      assertThat(repository.pending()).isEmpty();
    }

    @Test
    @DisplayName("an address alone is not a question")
    void an_address_alone_writes_nothing() {
      listener.expecting("c1", ReplyToken.of("token-1"));

      assertThat(repository.pending()).isEmpty();
    }
  }

  @Nested
  class Deciding {

    @Test
    void an_approval_stops_it_waiting() {
      listener.expecting("c1", ReplyToken.of("token-1"));
      listener.on(asked("c1"));

      listener.on(
          new AgentEvent.ApprovalDecided("e2", "c1", "prune_images", ApprovalResult.approved()));

      assertThat(repository.pending()).isEmpty();
      assertThat(repository.byCallId("c1").orElseThrow().answer()).contains("approved");
    }

    @Test
    void a_denial_records_the_reason_somebody_gave() {
      listener.expecting("c1", ReplyToken.of("token-1"));
      listener.on(asked("c1"));

      listener.on(
          new AgentEvent.ApprovalDecided(
              "e2", "c1", "prune_images", ApprovalResult.denied("not tonight")));

      PendingApproval row = repository.byCallId("c1").orElseThrow();
      assertThat(row.answer()).contains("denied");
      assertThat(row.note()).contains("not tonight");
    }

    @Test
    @DisplayName("the address is forgotten once it is spent")
    void a_decided_call_asked_again_is_not_rewritten() {
      listener.expecting("c1", ReplyToken.of("token-1"));
      listener.on(asked("c1"));
      listener.on(
          new AgentEvent.ApprovalDecided("e2", "c1", "prune_images", ApprovalResult.approved()));

      listener.on(asked("c1"));

      assertThat(repository.pending()).isEmpty();
    }
  }

  @Nested
  class SurvivingARestart {

    @Test
    @DisplayName("a recovered turn re-asks, and the row is refreshed rather than duplicated")
    void the_same_call_asked_again_keeps_one_row_with_the_new_address() {
      listener.expecting("c1", ReplyToken.of("token-1"));
      listener.on(asked("c1"));

      listener.expecting("c1", ReplyToken.of("token-2"));
      listener.on(asked("c1"));

      assertThat(repository.pending()).hasSize(1);
      assertThat(repository.byCallId("c1").orElseThrow().replyToken())
          .as("a row keeping the old token would show a button the engine rejects")
          .isEqualTo("token-2");
    }
  }

  @Nested
  class MindingItsOwnBusiness {

    @Test
    void other_narration_is_ignored() {
      listener.expecting("c1", ReplyToken.of("token-1"));

      listener.on(new AgentEvent.TurnStarted("e0"));
      listener.on(new AgentEvent.TextDelta("e1", "thinking"));

      assertThat(repository.pending()).isEmpty();
    }
  }
}
