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
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.CallId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * A whole round, end to end: the scripted watchman checks the disks and proposes a prune, the prune
 * is waiting on the page, a person approves it, it runs, and the round's notes are written.
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

  @Autowired private AgentWork work;
  @Autowired private org.jwcarman.nessy.engine.store.TurnHistories histories;
  @org.springframework.boot.test.web.server.LocalServerPort private int port;

  /**
   * Sixty seconds rather than twenty, because of what is being waited for.
   *
   * <p>A round begins on ApplicationReadyEvent and has to get a proposal through a model stand-in,
   * an approval gate and a wait before it shows on the page -- in a module that starts two Spring
   * contexts against one Postgres container, on a shared runner. Twenty seconds was enough on a
   * laptop and lost on CI. Raising the ceiling of a wait does not weaken what it asserts: the
   * condition is the same, and a passing run still stops the moment it is met.
   */
  @Test
  void a_proposed_prune_waits_for_a_person_until_a_person_approves_it() {
    // The first round starts on ApplicationReadyEvent; the prune is waiting...
    await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(ours()).hasSize(1));
    ApprovalRequest waiting = ours().getFirst();
    assertThat(waiting.action()).isEqualTo("docker image prune -af");
    assertThat(waiting.agentId()).isEqualTo(Watchman.AGENT);
    assertThat(waiting.callId()).isEqualTo(new CallId("round-1-prune"));

    // ...a person approves it from the page...
    // (the form posts the three values that address the call: agent type, agent id and key)
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("agentType", waiting.agentType().value());
    form.add("agentId", waiting.agentId().value().toString());
    RestClient.create("http://localhost:" + port)
        .post()
        .uri("/approve/{key}", waiting.idempotencyKey().toString())
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(form)
        .exchange((request, response) -> response.getStatusCode());

    // ...which clears the wait and lets the round finish with its notes.
    await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(ours()).isEmpty());
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
  }

  /**
   * What Nessy says the watchman is waiting on, filtered to the agent this test owns: this module
   * runs two Spring contexts against one Postgres container, so another context's agents are
   * visible here.
   */
  private List<ApprovalRequest> ours() {
    return work.waitingApprovals(Watchman.TYPE).stream()
        .filter(pending -> pending.agentId().equals(Watchman.AGENT))
        .toList();
  }
}
