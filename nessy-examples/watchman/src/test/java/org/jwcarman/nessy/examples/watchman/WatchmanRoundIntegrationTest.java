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
package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.CallId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * A whole round, end to end: the scripted watchman checks the disks and proposes a prune, the prune
 * lands on the board, a person approves it, it runs, and the round's notes are written.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"watchman.round-interval=PT1H", "nessy.provider=scripted"})
@Import(PostgresBacked.Connection.class)
@DisplayName("A round of the scripted watchman")
class WatchmanRoundIntegrationTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class CannedCommands {
    // Canned rather than run: this test proves the wiring, not that the machine has Docker.
    @Bean
    @Primary
    CommandRunner fakeRunner() {
      return new FakeRunner();
    }
  }

  @Autowired private PendingApprovalsRepository approvals;
  @Autowired private org.jwcarman.nessy.engine.store.TurnHistories histories;
  @org.springframework.boot.test.web.server.LocalServerPort private int port;

  /**
   * Sixty seconds rather than twenty, because of what is being waited for.
   *
   * <p>A round begins on ApplicationReadyEvent and has to get a proposal through a model stand-in,
   * an approval gate and a row before it lands on the board -- in a module that starts two Spring
   * contexts against one Postgres container, on a shared runner. Twenty seconds was enough on a
   * laptop and lost on CI. Raising the ceiling of a wait does not weaken what it asserts: the
   * condition is the same, and a passing run still stops the moment it is met.
   */
  @Test
  void a_proposed_prune_waits_on_the_board_until_a_person_approves_it() {
    // The first round starts on ApplicationReadyEvent; the prune reaches the board...
    await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(ours()).hasSize(1));
    PendingApproval waiting = ours().getFirst();
    assertThat(waiting.action()).isEqualTo("docker image prune -af");
    assertThat(waiting.agentId()).isEqualTo(Watchman.AGENT);
    assertThat(waiting.callId()).isEqualTo(new CallId("round-1-prune"));

    // ...a person approves it from the page...
    RestClient.create("http://localhost:" + port)
        .post()
        .uri("/approve/{key}", waiting.idempotencyKey().toString())
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .exchange((request, response) -> response.getStatusCode());

    // ...which takes it off the board and lets the round finish with its notes.
    assertThat(ours()).isEmpty();
    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () -> {
              List<ApprovalsController.Note> notes =
                  ApprovalsController.notes(
                      histories.forAgent(Watchman.TYPE, Watchman.AGENT).turnsFrom(0));
              assertThat(notes)
                  .extracting(ApprovalsController.Note::text)
                  .anySatisfy(text -> assertThat(text).contains("Total reclaimed space: 4.2GB"))
                  .contains("Rounds complete. Nothing needs your attention.");
            });
    assertThat(approvals.byIdempotencyKey(waiting.idempotencyKey()).orElseThrow().answer())
        .contains("approved");
  }

  /**
   * The board, filtered to the agent this test owns.
   *
   * <p>{@code approvals.pending()} is every row in the table, and this module runs two Spring
   * contexts against one Postgres container -- so a sibling test's fixtures are visible here. A
   * size assertion over all of them passes or fails on test ORDER rather than on what this test
   * did, which is how it came to report "expected 1 but was 7" with its own row sitting correctly
   * among six belonging to somebody else.
   */
  private List<PendingApproval> ours() {
    return approvals.pending().stream()
        .filter(pending -> pending.agentId().equals(Watchman.AGENT))
        .toList();
  }
}
