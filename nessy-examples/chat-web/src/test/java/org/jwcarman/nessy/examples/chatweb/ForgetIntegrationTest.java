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
package org.jwcarman.nessy.examples.chatweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Ending a conversation, over real HTTP.
 *
 * <p>The leak this closes was in shipped code: the browser keeps its agent id in {@code
 * localStorage}, so every visitor became a permanent agent — a state row and a transcript nothing
 * ever removed — and "New chat" made it worse by minting another and walking away from the old one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Ending a conversation")
class ForgetIntegrationTest {

  @LocalServerPort private int port;

  private RestClient http() {
    return RestClient.create("http://localhost:" + port);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, String>> transcript(RestClient http, String agentId) {
    Map<String, Object> state =
        http.get().uri("/api/agents/{id}", agentId).retrieve().body(Map.class);
    return (List<Map<String, String>>) state.get("transcript");
  }

  @Test
  @DisplayName("what was said is gone afterwards, and the id starts clean")
  void forgetting_an_agent_empties_its_transcript() {
    RestClient http = http();
    String agentId = UUID.randomUUID().toString();

    http.post()
        .uri("/api/agents/{id}/messages", agentId)
        .body(new ChatController.MessageRequest("remember this"))
        .retrieve()
        .toBodilessEntity();
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(transcript(http, agentId)).isNotEmpty());

    ResponseEntity<Void> forgotten =
        http.delete().uri("/api/agents/{id}", agentId).retrieve().toBodilessEntity();

    assertThat(forgotten.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    // Accepted is not gone: the agent is TOLD here, and acts when it is next idle.
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(transcript(http, agentId)).isEmpty());
  }

  @Test
  @DisplayName("forgetting one conversation leaves another alone")
  void forgetting_is_scoped_to_one_agent() {
    RestClient http = http();
    String kept = UUID.randomUUID().toString();
    String ended = UUID.randomUUID().toString();
    for (String id : List.of(kept, ended)) {
      http.post()
          .uri("/api/agents/{id}/messages", id)
          .body(new ChatController.MessageRequest("something"))
          .retrieve()
          .toBodilessEntity();
    }
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(transcript(http, kept)).isNotEmpty();
              assertThat(transcript(http, ended)).isNotEmpty();
            });

    http.delete().uri("/api/agents/{id}", ended).retrieve().toBodilessEntity();

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(transcript(http, ended)).isEmpty());
    assertThat(transcript(http, kept)).as("somebody else's conversation").isNotEmpty();
  }

  @Test
  @DisplayName("forgetting a conversation nobody ever had is accepted, not an error")
  void forgetting_a_stranger_is_silent() {
    ResponseEntity<Void> forgotten =
        http()
            .delete()
            .uri("/api/agents/{id}", UUID.randomUUID().toString())
            .retrieve()
            .toBodilessEntity();

    assertThat(forgotten.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
  }
}
