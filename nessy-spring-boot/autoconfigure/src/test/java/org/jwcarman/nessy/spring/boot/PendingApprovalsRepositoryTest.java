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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.testing.TestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The table behind the approvals page.
 *
 * <p>This had no tests at all, and shipped a real failure: the starter wrote to {@code
 * nessy_pending_approvals} and shipped no DDL for it, so every parked approval died with "relation
 * does not exist" and took the narration actor down with it. Running against a database with the
 * schema applied is what proves the two agree.
 */
@DisplayName("What is waiting on a person")
class PendingApprovalsRepositoryTest {

  private static final Instant ASKED = Instant.parse("2026-09-01T12:00:00Z");
  private static final Instant EXPIRES = ASKED.plus(3, ChronoUnit.DAYS);

  private PendingApprovalsRepository repository;

  @BeforeEach
  void freshDatabase() {
    DataSource dataSource = TestDatabase.fresh();
    repository = new PendingApprovalsRepository(new JdbcTemplate(dataSource));
  }

  private static PendingApproval question(String callId, String token) {
    return new PendingApproval(
        CallId.of(callId),
        AgentType.of("watchman"),
        AgentId.of("house-12"),
        "prune_images",
        "docker image prune -af",
        ASKED,
        EXPIRES,
        token,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  @Nested
  class Asking {

    @Test
    void nothing_is_waiting_to_begin_with() {
      assertThat(repository.pending()).isEmpty();
    }

    @Test
    void a_question_comes_back_whole() {
      repository.asked(question("c1", "token-1"));

      PendingApproval row =
          repository
              .byCallId(AgentType.of("watchman"), AgentId.of("house-12"), CallId.of("c1"))
              .orElseThrow();

      assertThat(row.tool()).isEqualTo("prune_images");
      assertThat(row.action()).isEqualTo("docker image prune -af");
      assertThat(row.askedAt()).isEqualTo(ASKED);
      assertThat(row.expiresAt()).isEqualTo(EXPIRES);
      assertThat(row.replyToken()).isEqualTo("token-1");
      assertThat(row.answer()).isEmpty();
    }

    @Test
    void a_call_nobody_asked_about_is_absent() {
      assertThat(
              repository.byCallId(
                  AgentType.of("watchman"), AgentId.of("house-12"), CallId.of("never")))
          .isEmpty();
    }

    @Test
    @DisplayName("asking twice is one row, because a recovered turn re-asks")
    void the_same_call_asked_again_does_not_become_a_second_row() {
      repository.asked(question("c1", "token-1"));
      repository.asked(question("c1", "token-2"));

      assertThat(repository.pending()).hasSize(1);
    }

    @Test
    @DisplayName("but it refreshes the address, or the button on the page cannot work")
    void re_asking_replaces_the_reply_token() {
      repository.asked(question("c1", "token-1"));
      repository.asked(question("c1", "token-2"));

      assertThat(
              repository
                  .byCallId(AgentType.of("watchman"), AgentId.of("house-12"), CallId.of("c1"))
                  .orElseThrow()
                  .replyToken())
          .isEqualTo("token-2");
    }

    @Test
    void what_is_waiting_comes_back_oldest_first() {
      repository.asked(question("newer", "t2"));
      PendingApproval older =
          new PendingApproval(
              CallId.of("older"),
              AgentType.of("watchman"),
              AgentId.of("house-12"),
              "restart",
              "restart prod-1",
              ASKED.minus(1, ChronoUnit.HOURS),
              EXPIRES,
              "t1",
              Optional.empty(),
              Optional.empty(),
              Optional.empty());
      repository.asked(older);

      assertThat(repository.pending())
          .extracting(PendingApproval::callId)
          .containsExactly(CallId.of("older"), CallId.of("newer"));
    }
  }

  @Nested
  class Answering {

    @Test
    void an_answered_call_stops_waiting() {
      repository.asked(question("c1", "token-1"));

      repository.answered(
          AgentType.of("watchman"),
          AgentId.of("house-12"),
          CallId.of("c1"),
          "approved",
          "looks fine",
          ASKED.plusSeconds(60));

      assertThat(repository.pending()).isEmpty();
      PendingApproval row =
          repository
              .byCallId(AgentType.of("watchman"), AgentId.of("house-12"), CallId.of("c1"))
              .orElseThrow();
      assertThat(row.waiting()).isFalse();
      assertThat(row.answer()).contains("approved");
      assertThat(row.note()).contains("looks fine");
      assertThat(row.answeredAt()).contains(ASKED.plusSeconds(60));
    }

    @Test
    @DisplayName("waiting() is exactly the absence of an answer, in either direction")
    void a_row_with_no_answer_is_still_waiting() {
      repository.asked(question("c1", "token-1"));

      PendingApproval row =
          repository
              .byCallId(AgentType.of("watchman"), AgentId.of("house-12"), CallId.of("c1"))
              .orElseThrow();

      assertThat(row.waiting()).isTrue();
    }

    @Test
    @DisplayName("a late click on a stale page does not overwrite what was decided")
    void a_second_answer_changes_nothing() {
      repository.asked(question("c1", "token-1"));
      repository.answered(
          AgentType.of("watchman"),
          AgentId.of("house-12"),
          CallId.of("c1"),
          "denied",
          "no",
          ASKED.plusSeconds(60));

      repository.answered(
          AgentType.of("watchman"),
          AgentId.of("house-12"),
          CallId.of("c1"),
          "approved",
          "changed my mind",
          ASKED.plusSeconds(120));

      assertThat(
              repository
                  .byCallId(AgentType.of("watchman"), AgentId.of("house-12"), CallId.of("c1"))
                  .orElseThrow()
                  .answer())
          .contains("denied");
    }

    @Test
    @DisplayName("a late re-ask does not reopen a decision")
    void asking_again_after_an_answer_leaves_it_answered() {
      repository.asked(question("c1", "token-1"));
      repository.answered(
          AgentType.of("watchman"),
          AgentId.of("house-12"),
          CallId.of("c1"),
          "denied",
          "no",
          ASKED.plusSeconds(60));

      repository.asked(question("c1", "token-2"));

      assertThat(repository.pending()).isEmpty();
      assertThat(
              repository
                  .byCallId(AgentType.of("watchman"), AgentId.of("house-12"), CallId.of("c1"))
                  .orElseThrow()
                  .replyToken())
          .isEqualTo("token-1");
    }
  }
}
