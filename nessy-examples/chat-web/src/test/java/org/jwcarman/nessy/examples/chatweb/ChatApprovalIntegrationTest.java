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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresBacked.class)
class ChatApprovalIntegrationTest {

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

    // Sent without waiting for the reply, which is what a browser does and what this door
    // requires of anything that gates a tool on a person: the request is held open for the whole
    // turn, so the answer to the question it raises has to come in on a different one. A test that
    // blocked here would be waiting for a turn that is waiting for the test.
    CompletableFuture<Void> said =
        CompletableFuture.runAsync(
            () ->
                http.post()
                    .uri("/api/agents/{id}/messages", agentId)
                    .body(new ChatController.MessageRequest("Email Jim about dinner"))
                    .retrieve()
                    .toBodilessEntity());

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
    // And the turn that was held open across all of that can now finish, which is the proof that
    // a person answering hours later would have released it too.
    said.orTimeout(30, TimeUnit.SECONDS).join();

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
