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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.testing.TestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Which call a row is about.
 *
 * <p>A model's call id is unique within ONE response and no further, so two agents can each be
 * waiting on a "call_1". Keying a row on the call id alone makes one of them overwrite the other.
 */
@DisplayName("Telling two agents' approvals apart")
class PendingApprovalsIdentityTest {

  private static final Instant ASKED = Instant.parse("2026-09-02T12:00:00Z");

  private PendingApprovalsRepository repository;

  @BeforeEach
  void freshDatabase() {
    repository = new PendingApprovalsRepository(new JdbcTemplate(TestDatabase.fresh()));
  }

  private static PendingApproval waitingOn(String agentType, String agentId, String callId) {
    return new PendingApproval(
        callId,
        agentType,
        agentId,
        "prune_images",
        "docker image prune -af on " + agentId,
        ASKED,
        ASKED.plusSeconds(3600),
        "token-for-" + agentId,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  @Test
  @DisplayName("two agents waiting on the same call id are two questions, not one")
  void a_call_id_is_only_unique_within_one_response() {
    repository.asked(waitingOn("watchman", "house-12", "call_1"));
    repository.asked(waitingOn("watchman", "house-99", "call_1"));

    assertThat(repository.pending())
        .as("one row would mean one house's question silently replaced the other's")
        .hasSize(2);
  }

  @Test
  @DisplayName("answering one leaves the other waiting")
  void deciding_one_does_not_decide_the_other() {
    repository.asked(waitingOn("watchman", "house-12", "call_1"));
    repository.asked(waitingOn("watchman", "house-99", "call_1"));

    repository.answered("watchman", "house-12", "call_1", "denied", "not tonight", ASKED);

    assertThat(repository.pending()).hasSize(1);
    assertThat(repository.pending().getFirst().agentId()).isEqualTo("house-99");
  }

  @Test
  @DisplayName("two agent TYPES sharing an id are also two questions")
  void an_agent_id_is_scoped_by_its_type() {
    repository.asked(waitingOn("watchman", "shared", "call_1"));
    repository.asked(waitingOn("chat", "shared", "call_1"));

    assertThat(repository.pending()).hasSize(2);
  }
}
