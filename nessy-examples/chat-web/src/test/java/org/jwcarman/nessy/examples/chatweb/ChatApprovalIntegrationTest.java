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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.testing.ScriptedModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * The whole human-in-the-loop, through HTTP, with no model and no network.
 *
 * <p>It asserts the property the example exists to demonstrate and the one that is easiest to break
 * without noticing: a gated tool does NOT run when the model asks for it. It runs when a person
 * says so, through the page, and not one moment earlier.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // A dead port, so that if the scripted provider ever stopped overriding the real one this
    // test fails loudly instead of quietly passing through whatever model the developer happens
    // to be running on this machine.
    properties = "chat.model-url=http://localhost:1/v1")
class ChatApprovalIntegrationTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class ScriptedModelConfiguration {

    /**
     * Two turns: ask to send the mail, then report what came back. The second turn is only ever
     * reached if the first tool call actually settles, so a script that runs to completion is
     * itself evidence the approval was answered.
     */
    @Bean
    @Primary
    ModelProvider scriptedModels() {
      ObjectNode arguments = JsonNodeFactory.instance.objectNode();
      arguments.put("to", "jim@example.com");
      arguments.put("subject", "Dinner");
      arguments.put("body", "Are you free Thursday?");
      ScriptedModel model =
          ScriptedModel.script(
              script ->
                  script
                      .text("I will send that.")
                      .toolCall("call-1", "send_email", arguments)
                      .endWithToolCalls()
                      .text("Sent.")
                      .endTurn());
      return id -> model;
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

    String callId = approvals(http, agentId).getFirst().get("id");
    ResponseEntity<Void> answered =
        http.post()
            .uri("/api/agents/{id}/approvals/{call}", agentId, callId)
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
  }

  /**
   * The page's own view of an agent, bound to a record rather than read out of a {@code Map}: the
   * cast a map would need is exactly the kind this project does not suppress.
   */
  record PageState(List<Map<String, String>> transcript, List<Map<String, String>> approvals) {}

  private List<Map<String, String>> approvals(RestClient http, String agentId) {
    PageState state = http.get().uri("/api/agents/{id}", agentId).retrieve().body(PageState.class);
    return state.approvals();
  }
}
