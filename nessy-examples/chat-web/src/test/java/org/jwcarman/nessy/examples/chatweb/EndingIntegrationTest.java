package org.jwcarman.nessy.examples.chatweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Ending a conversation")
class EndingIntegrationTest extends PostgresBacked {

  @TestConfiguration(proxyBeanMethods = false)
  static class ScriptedModelConfiguration {
    @Bean
    InferenceProvider scriptedModels() {
      return ScriptedProvider.alwaysSaying("Noted.");
    }
  }

  @LocalServerPort private int port;

  private RestClient http() {
    return RestClient.create("http://localhost:" + port);
  }

  record PageState(List<Map<String, String>> transcript, List<Map<String, String>> approvals) {}

  private static List<Map<String, String>> transcript(RestClient http, String agentId) {
    return http.get()
        .uri("/api/agents/{id}", agentId)
        .retrieve()
        .body(PageState.class)
        .transcript();
  }

  private static void say(RestClient http, String agentId, String text) {
    http.post()
        .uri("/api/agents/{id}/messages", agentId)
        .body(new ChatController.MessageRequest(text))
        .retrieve()
        .toBodilessEntity();
  }

  @Test
  @DisplayName("what was said is kept, and nothing said afterwards is taken")
  void an_ended_agent_keeps_its_story_and_takes_no_more() {
    RestClient http = http();
    String agentId = UUID.randomUUID().toString();

    say(http, agentId, "remember this");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(transcript(http, agentId))
                    .extracting(line -> line.get("text"))
                    .contains("Noted."));
    List<Map<String, String>> before = transcript(http, agentId);

    ResponseEntity<Void> ended =
        http.delete().uri("/api/agents/{id}", agentId).retrieve().toBodilessEntity();
    assertThat(ended.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    // Accepted, and the story is not erased: an ended conversation is one that took its last
    // word, not one that never happened.
    say(http, agentId, "and this");
    // Ignored rather than refused, so there is nothing to wait for but the absence of a change.
    // A word the agent took would become a line within the same patience the first one did.
    await()
        .pollDelay(Duration.ofSeconds(3))
        .atMost(Duration.ofSeconds(4))
        .untilAsserted(() -> assertThat(transcript(http, agentId)).isEqualTo(before));
  }

  @Test
  @DisplayName("ending one conversation leaves another alone")
  void ending_is_scoped_to_one_agent() {
    RestClient http = http();
    String kept = UUID.randomUUID().toString();
    String ended = UUID.randomUUID().toString();
    say(http, kept, "something");
    say(http, ended, "something");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(transcript(http, kept)).hasSize(2);
              assertThat(transcript(http, ended)).hasSize(2);
            });

    http.delete().uri("/api/agents/{id}", ended).retrieve().toBodilessEntity();

    say(http, kept, "something else");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(transcript(http, kept)).hasSize(4));
  }

  @Test
  @DisplayName("ending a conversation nobody ever had is accepted, not an error")
  void ending_a_stranger_is_silent() {
    ResponseEntity<Void> ended =
        http()
            .delete()
            .uri("/api/agents/{id}", UUID.randomUUID().toString())
            .retrieve()
            .toBodilessEntity();
    assertThat(ended.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
  }

  @Test
  @DisplayName("an id that is not one is the caller's mistake, not a 500")
  void a_malformed_id_is_a_bad_request() {
    ResponseEntity<String> refused =
        http()
            .get()
            .uri("/api/agents/{id}", "not-a-uuid")
            .exchange(
                (request, response) -> ResponseEntity.status(response.getStatusCode()).build());
    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }
}
