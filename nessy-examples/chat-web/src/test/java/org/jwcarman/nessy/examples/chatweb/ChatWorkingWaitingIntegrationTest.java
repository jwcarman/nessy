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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "nessy.provider=scriptedModels")
@Import(PostgresBacked.class)
class ChatWorkingWaitingIntegrationTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class ScriptedModelConfiguration {
    @Bean
    InferenceProvider scriptedModels() {
      return ScriptedProvider.callingOnce(
          "call-1",
          "send_email",
          "{\"to\":\"jim@example.com\",\"subject\":\"Dinner\",\"body\":\"Free Thursday?\"}",
          "Sent.");
    }
  }

  @LocalServerPort private int port;

  @Test
  void a_turn_parked_on_a_card_is_working_until_the_card_is_answered() {
    ChatClient chat = new ChatClient(port);
    String agentId = UUID.randomUUID().toString();
    assertThat(chat.say(agentId, "Email Jim about dinner")).isEqualTo(202);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(chat.state(agentId).approvals()).isNotEmpty());

    ChatClient.PageState waiting = chat.state(agentId);

    assertThat(waiting.approvals()).isNotEmpty();
    assertThat(waiting.working()).isTrue();

    assertThat(chat.decide(agentId, waiting.approvals().getFirst().id(), "approve", ""))
        .isEqualTo(202);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(chat.state(agentId).texts()).contains("Sent.");
              assertThat(chat.state(agentId).working()).isFalse();
            });
  }
}
