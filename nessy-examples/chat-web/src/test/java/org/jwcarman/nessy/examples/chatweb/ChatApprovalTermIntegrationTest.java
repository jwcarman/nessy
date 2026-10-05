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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"nessy.provider=scriptedModels", "chat.approval-term=PT3S"})
@Import(PostgresBacked.class)
class ChatApprovalTermIntegrationTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class ScriptedModelConfiguration {
    @Bean
    InferenceProvider scriptedModels() {
      return ScriptedProvider.callingOnce(
          "call-1",
          "send_email",
          "{\"to\":\"jim@example.com\",\"subject\":\"Dinner\",\"body\":\"Are you free Thursday?\"}",
          "Could not send.");
    }
  }

  @LocalServerPort private int port;
  @Autowired private SendEmailTool email;

  @Test
  void an_answer_after_the_term_is_a_409_and_the_call_is_recorded_as_failed() {
    ChatClient chat = new ChatClient(port);
    String agentId = UUID.randomUUID().toString();
    assertThat(chat.say(agentId, "Email Jim about dinner")).isEqualTo(202);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(chat.state(agentId).approvals()).isNotEmpty());
    ChatClient.Card card = chat.state(agentId).approvals().getFirst();

    // Nobody answers within the term: the card goes and the call is recorded as failed.
    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () -> {
              assertThat(chat.state(agentId).approvals()).isEmpty();
              assertThat(chat.state(agentId).texts())
                  .anyMatch(text -> text.startsWith("failed"))
                  .contains("Could not send.");
            });

    assertThat(chat.decide(agentId, card.id(), "approve", "")).isEqualTo(409);
    assertThat(email.sent()).isEmpty();
  }
}
