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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStatus.Activity;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
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
    properties = "nessy.provider=edgeModels")
@Import(PostgresBacked.class)
class ChatApprovalEdgesIntegrationTest {

  /** Asks for the email in a turn whose input says "Email"; answers everything else "Noted.". */
  static final class EmailOnRequest implements InferenceProvider {
    @Override
    public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
      boolean wantsEmail =
          request.context().turns().stream()
              .filter(turn -> !turn.complete())
              .anyMatch(
                  turn ->
                      turn.input().blocks().stream()
                          .anyMatch(b -> b instanceof Block.Text(String t) && t.contains("Email")));
      boolean called =
          request.context().turns().stream()
              .anyMatch(turn -> !turn.complete() && !turn.exchanges().isEmpty());
      if (wantsEmail && !called) {
        return new InferenceResult.Actions(
            List.of(
                new Block.Commentary("I will send that."),
                new Block.ToolCall(
                    "call-1",
                    "send_email",
                    "{\"to\":\"jim@example.com\",\"subject\":\"Dinner\",\"body\":\"Free?\"}")));
      }
      return new InferenceResult.Answer(List.of(new Block.Text(wantsEmail ? "Sent." : "Noted.")));
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class EdgeConfiguration {
    @Bean
    InferenceProvider edgeModels() {
      return new EmailOnRequest();
    }
  }

  @LocalServerPort private int port;
  @Autowired private SendEmailTool email;
  @Autowired private QueuedHarness<String> harness;
  @Autowired private AgentWork work;
  @Autowired private PostgreSQLContainer postgres;

  private ChatClient.Card asked(ChatClient chat, String agentId) {
    assertThat(chat.say(agentId, "Email Jim about dinner")).isEqualTo(202);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(chat.state(agentId).approvals()).isNotEmpty());
    return chat.state(agentId).approvals().getFirst();
  }

  @Test
  void three_messages_sent_while_a_turn_waits_on_a_card_run_as_one_turn_after_the_answer() {
    ChatClient chat = new ChatClient(port);
    String agentId = UUID.randomUUID().toString();
    ChatClient.Card card = asked(chat, agentId);

    assertThat(chat.say(agentId, "alpha")).isEqualTo(202);
    assertThat(chat.say(agentId, "beta")).isEqualTo(202);
    assertThat(chat.say(agentId, "gamma")).isEqualTo(202);
    assertThat(chat.state(agentId).texts()).doesNotContain("alpha");
    assertThat(chat.decide(agentId, card.id(), "approve", "")).isEqualTo(202);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(chat.state(agentId).texts())
                    .containsSubsequence(
                        "Email Jim about dinner", "Sent.", "alpha\n\nbeta\n\ngamma", "Noted."));
    long userLines =
        chat.state(agentId).transcript().stream()
            .filter(line -> "user".equals(line.get("role")))
            .count();
    assertThat(userLines).isEqualTo(2);
    assertThat(chat.state(agentId).approvals()).isEmpty();
  }

  @Test
  void a_card_can_be_answered_from_a_second_application_context() {
    ChatClient chat = new ChatClient(port);
    String agentId = UUID.randomUUID().toString();
    ChatClient.Card card = asked(chat, agentId);

    try (ConfigurableApplicationContext second =
        new SpringApplicationBuilder(ChatWebApplication.class, EdgeConfiguration.class)
            .run(
                "--spring.main.web-application-type=none",
                "--nessy.provider=edgeModels",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword())) {
      Replies replies = second.getBean(Replies.class);

      ReplyOutcome outcome =
          replies.approve(
              ChatConfiguration.TYPE,
              new AgentId(UUID.fromString(agentId)),
              IdempotencyKey.of(UUID.fromString(card.id())),
              ApprovalResult.approved());

      assertThat(outcome).isInstanceOf(ReplyOutcome.Applied.class);
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(() -> assertThat(chat.state(agentId).texts()).contains("Sent."));
    }
  }

  @Test
  void two_answers_at_the_same_moment_to_one_card_are_one_202_and_one_409() throws Exception {
    ChatClient chat = new ChatClient(port);
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      for (int round = 0; round < 5; round++) {
        String agentId = UUID.randomUUID().toString();
        ChatClient.Card card = asked(chat, agentId);
        CyclicBarrier together = new CyclicBarrier(2);
        List<Future<Integer>> answers = new ArrayList<>();
        for (String decision : List.of("approve", "deny")) {
          answers.add(
              pool.submit(
                  () -> {
                    together.await();
                    return chat.decide(agentId, card.id(), decision, "no");
                  }));
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> answer : answers) {
          statuses.add(answer.get());
        }
        Collections.sort(statuses);
        assertThat(statuses).containsExactly(202, 409);
        await()
            .atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> assertThat(chat.state(agentId).approvals()).isEmpty());
      }
    }
  }

  @Test
  void
      a_message_sent_after_the_end_while_the_last_turn_is_in_progress_is_a_409_at_once_and_nothing_is_queued() {
    ChatClient chat = new ChatClient(port);
    String agentId = UUID.randomUUID().toString();
    AgentId agent = new AgentId(UUID.fromString(agentId));
    asked(chat, agentId);
    assertThat(chat.end(agentId)).isEqualTo(202);

    int status = chat.say(agentId, "are you still there?");

    // The agent is terminated from the moment of the end, and tell says so at once, though its
    // status does not read as terminated until the parked turn ends.
    assertThat(status).isEqualTo(409);
    assertThat(work.status(ChatConfiguration.TYPE, agent).queued()).isZero();
    assertThat(chat.state(agentId).texts()).doesNotContain("are you still there?");
  }

  @Test
  void a_tell_after_the_end_leaves_nothing_queued() {
    ChatClient chat = new ChatClient(port);
    String agentId = UUID.randomUUID().toString();
    AgentId agent = new AgentId(UUID.fromString(agentId));
    assertThat(chat.say(agentId, "hello")).isEqualTo(202);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(chat.state(agentId).texts()).contains("Noted."));
    harness.terminate(agent);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(work.status(ChatConfiguration.TYPE, agent).activity())
                    .isEqualTo(Activity.TERMINATED));

    harness.tell(agent, "too late");

    // tell returns after its locked step, so an input it kept would be counted here already.
    assertThat(work.status(ChatConfiguration.TYPE, agent).queued()).isZero();
  }
}
