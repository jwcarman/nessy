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
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "nessy.provider=scriptedModels")
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
  @Autowired private PostgreSQLContainer postgres;

  private ChatClient chat() {
    return new ChatClient(port);
  }

  /** Asks for the email and waits until its approval request is on the page. */
  private ChatClient.Card asked(ChatClient chat, String agentId) {
    assertThat(chat.say(agentId, "Email Jim about dinner")).isEqualTo(202);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(chat.state(agentId).approvals()).isNotEmpty());
    return chat.state(agentId).approvals().getFirst();
  }

  @Test
  void an_email_waits_for_a_person_and_the_state_lists_it_as_a_card() {
    ChatClient chat = chat();
    String agentId = UUID.randomUUID().toString();
    int sentBefore = email.sent().size();

    ChatClient.Card card = asked(chat, agentId);

    // The id is the call's idempotency key, which UUID.fromString accepts only if it is one.
    assertThat(UUID.fromString(card.id())).isNotNull();
    assertThat(card.tool()).isEqualTo("send_email");
    // The turn the call was asked in: the one whose request line is the last in the transcript.
    Object asking = chat.state(agentId).transcript().getLast().get("turn");
    assertThat(card.turn()).isEqualTo(((Number) asking).longValue());
    assertThat(card.what())
        .isEqualTo("Send an email to jim@example.com, subject \"Dinner\": Are you free Thursday?");
    assertThat(card.args()).contains("jim@example.com").contains("\n");
    Instant askedAt = Instant.parse(card.askedAt());
    Instant deadline = Instant.parse(card.deadline());
    // The term is the default five minutes, less what the clock rounds off between the two.
    assertThat(Duration.between(askedAt, deadline))
        .isBetween(Duration.ofMinutes(5).minusSeconds(5), Duration.ofMinutes(5));
    assertThat(email.sent()).hasSize(sentBefore);
  }

  @Test
  void a_waiting_card_is_still_there_for_a_second_application_context_on_the_same_database() {
    ChatClient chat = chat();
    String agentId = UUID.randomUUID().toString();
    ChatClient.Card card = asked(chat, agentId);

    try (ConfigurableApplicationContext second =
        new SpringApplicationBuilder(ChatWebApplication.class, ScriptedModelConfiguration.class)
            // Command-line arguments, which outrank the application.yml defaults for the database.
            .run(
                "--spring.main.web-application-type=none",
                "--nessy.provider=scriptedModels",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword())) {
      ChatController controller = second.getBean(ChatController.class);

      var state = controller.state(agentId);

      assertThat(state.get("approvals").toString()).contains(card.id());
    }
  }

  @Test
  void approving_a_card_sends_the_email_and_the_turn_answers() {
    ChatClient chat = chat();
    String agentId = UUID.randomUUID().toString();
    int sentBefore = email.sent().size();
    ChatClient.Card card = asked(chat, agentId);
    assertThat(email.sent()).hasSize(sentBefore);

    assertThat(chat.decide(agentId, card.id(), "approve", "")).isEqualTo(202);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(email.sent()).hasSize(sentBefore + 1);
              assertThat(chat.state(agentId).texts()).contains("🔧 send_email", "Sent.");
            });
    assertThat(chat.state(agentId).approvals()).isEmpty();
  }

  @Test
  void denying_a_card_tells_the_model_and_nothing_is_sent() {
    ChatClient chat = chat();
    String agentId = UUID.randomUUID().toString();
    int sentBefore = email.sent().size();
    ChatClient.Card card = asked(chat, agentId);

    assertThat(chat.decide(agentId, card.id(), "deny", "not to Jim")).isEqualTo(202);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> assertThat(chat.state(agentId).texts()).contains("denied: not to Jim", "Sent."));
    assertThat(email.sent()).hasSize(sentBefore);
    assertThat(chat.state(agentId).approvals()).isEmpty();
  }

  @Test
  void a_second_answer_to_one_card_is_a_409_and_changes_nothing() {
    ChatClient chat = chat();
    String agentId = UUID.randomUUID().toString();
    int sentBefore = email.sent().size();
    ChatClient.Card card = asked(chat, agentId);
    assertThat(chat.decide(agentId, card.id(), "approve", "")).isEqualTo(202);

    assertThat(chat.decide(agentId, card.id(), "deny", "too late")).isEqualTo(409);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(chat.state(agentId).texts()).contains("Sent."));
    assertThat(email.sent()).hasSize(sentBefore + 1);
    assertThat(chat.state(agentId).texts()).noneMatch(text -> text.contains("too late"));
  }

  @Test
  void a_malformed_key_is_a_400() {
    ChatClient chat = chat();

    assertThat(chat.decide(UUID.randomUUID().toString(), "not-a-key", "approve", ""))
        .isEqualTo(400);
  }

  @Test
  void an_unknown_key_is_a_409() {
    ChatClient chat = chat();

    assertThat(
            chat.decide(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "approve", ""))
        .isEqualTo(409);
  }
}
