package org.jwcarman.nessy.examples.chatweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatApprovalIntegrationTest extends PostgresBacked {

  @TestConfiguration(proxyBeanMethods = false)
  static class ScriptedModelConfiguration {
    // Declared, so the adapter's auto-configuration (conditional on there being no provider)
    // never builds one: this test talks to no model whatever the machine has running.
    @Bean
    InferenceProvider scriptedModels() {
      return ScriptedProvider.callingOnce(
          "call-1",
          "send_email",
          "{\"to\":\"jim@example.com\",\"subject\":\"Dinner\",\"body\":\"Are you free Thursday?\"}",
          "Sent.");
    }
  }

  @LocalServerPort private int port;
  @Autowired private SendEmailTool email;

  @Test
  void aGatedToolWaitsForAPersonAndThenRuns() {
    RestClient http = RestClient.create("http://localhost:" + port);
    String agentId = UUID.randomUUID().toString();

    http.post()
        .uri("/api/agents/{id}/messages", agentId)
        .body(new ChatController.MessageRequest("Email Jim about dinner"))
        .retrieve()
        .toBodilessEntity();

    // The question reaches the page...
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(approvals(http, agentId)).isNotEmpty());
    // ...and nothing has been sent while it waits, which is the point.
    assertThat(email.sent()).isEmpty();
    Map<String, String> card = approvals(http, agentId).getFirst();
    assertThat(card.get("what")).contains("jim@example.com").contains("Are you free Thursday?");

    ResponseEntity<Void> answered =
        http.post()
            .uri("/api/agents/{id}/approvals/{call}", agentId, card.get("id"))
            .body(new ChatController.Decision("approve", ""))
            .retrieve()
            .toBodilessEntity();
    assertThat(answered.getStatusCode().is2xxSuccessful()).isTrue();

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(email.sent()).isNotEmpty();
              assertThat(email.sent().getFirst().to()).isEqualTo("jim@example.com");
            });
    // The desk hands out each question once: answering it takes it off the page.
    assertThat(approvals(http, agentId)).isEmpty();
    // And the story shows the whole of it, for a page that loads later.
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(transcript(http, agentId))
                    .extracting(line -> line.get("text"))
                    .contains("Email Jim about dinner", "🔧 send_email", "Sent."));
  }

  record PageState(List<Map<String, String>> transcript, List<Map<String, String>> approvals) {}

  private static PageState state(RestClient http, String agentId) {
    return http.get().uri("/api/agents/{id}", agentId).retrieve().body(PageState.class);
  }

  private static List<Map<String, String>> approvals(RestClient http, String agentId) {
    return state(http, agentId).approvals();
  }

  private static List<Map<String, String>> transcript(RestClient http, String agentId) {
    return state(http, agentId).transcript();
  }
}
