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
    properties = {"watchman.scripted=true", "watchman.round-interval=PT1H"})
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

  @Test
  void a_proposed_prune_waits_on_the_board_until_a_person_approves_it() {
    // The first round starts on ApplicationReadyEvent; the prune reaches the board...
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(approvals.pending()).hasSize(1));
    PendingApproval waiting = approvals.pending().getFirst();
    assertThat(waiting.action()).isEqualTo("docker image prune -af");
    assertThat(waiting.agentId()).isEqualTo(Watchman.AGENT);
    assertThat(waiting.callId()).isEqualTo(new CallId("round-1-prune"));

    // ...a person approves it from the page...
    RestClient.create("http://localhost:" + port)
        .post()
        .uri(
            "/approve/{t}/{a}/{c}",
            Watchman.TYPE.value(),
            Watchman.AGENT.value().toString(),
            waiting.callId().value())
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .exchange((request, response) -> response.getStatusCode());

    // ...which takes it off the board and lets the round finish with its notes.
    assertThat(approvals.pending()).isEmpty();
    await()
        .atMost(Duration.ofSeconds(20))
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
    assertThat(
            approvals
                .byCallId(Watchman.TYPE, Watchman.AGENT, waiting.callId())
                .orElseThrow()
                .answer())
        .contains("approved");
  }
}
