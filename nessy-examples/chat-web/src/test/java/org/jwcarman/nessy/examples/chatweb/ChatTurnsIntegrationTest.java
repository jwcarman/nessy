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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "nessy.provider=scriptedModels")
@Import(PostgresBacked.class)
class ChatTurnsIntegrationTest {

  /** Opens while a test lets a held turn go. */
  static final CountDownLatch RELEASE = new CountDownLatch(1);

  @TestConfiguration(proxyBeanMethods = false)
  static class ScriptedModelConfiguration {
    @Bean
    InferenceProvider scriptedModels() {
      return ScriptedProvider.holdingOn("hold", RELEASE, "Noted.");
    }
  }

  @LocalServerPort private int port;
  @Autowired private AgentWork work;

  private ChatClient chat() {
    return new ChatClient(port);
  }

  private static long userLines(ChatClient.PageState state) {
    return state.transcript().stream().filter(line -> "user".equals(line.get("role"))).count();
  }

  @Test
  void a_message_is_accepted_at_once_and_answered_on_the_stream_and_in_the_transcript()
      throws Exception {
    ChatClient chat = chat();
    String agentId = UUID.randomUUID().toString();
    List<String> seen = new CopyOnWriteArrayList<>();
    HttpResponse<InputStream> stream =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://localhost:" + port + "/api/agents/" + agentId + "/events"))
                    .header("Accept", "text/event-stream")
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream());
    Thread reader =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (BufferedReader lines =
                      new BufferedReader(
                          new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                      if (line.startsWith("event:")) {
                        seen.add(line.substring("event:".length()).trim());
                      }
                    }
                  } catch (IOException _) {
                    // The test is over and the stream was closed under the reader.
                  }
                });

    assertThat(chat.say(agentId, "hello")).isEqualTo(202);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(seen).contains("answered"));
    assertThat(seen).contains("turn-started");
    assertThat(chat.state(agentId).texts()).containsExactly("hello", "Noted.");
    stream.body().close();
    reader.join(Duration.ofSeconds(5));
  }

  @Test
  void messages_sent_during_a_turn_are_batched_into_one_turn_in_the_order_they_arrived() {
    ChatClient chat = chat();
    String agentId = UUID.randomUUID().toString();
    assertThat(chat.say(agentId, "hold this")).isEqualTo(202);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(work.status(ChatConfiguration.TYPE, agentIdOf(agentId)).turn())
                    .isPresent());

    assertThat(chat.say(agentId, "first extra")).isEqualTo(202);
    assertThat(chat.say(agentId, "second extra")).isEqualTo(202);
    RELEASE.countDown();

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(chat.state(agentId).texts())
                    .containsExactly(
                        "hold this", "Noted.", "first extra\n\nsecond extra", "Noted."));
    assertThat(userLines(chat.state(agentId))).isEqualTo(2);
    assertThat(chat.state(agentId).transcript().stream().map(line -> line.get("turn")).distinct())
        .hasSize(2);
  }

  @Test
  void a_message_sent_to_an_idle_agent_starts_its_own_turn() {
    ChatClient chat = chat();
    String agentId = UUID.randomUUID().toString();
    assertThat(chat.say(agentId, "one")).isEqualTo(202);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(chat.state(agentId).texts()).contains("Noted."));

    assertThat(chat.say(agentId, "two")).isEqualTo(202);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(chat.state(agentId).texts())
                    .containsExactly("one", "Noted.", "two", "Noted."));
    assertThat(userLines(chat.state(agentId))).isEqualTo(2);
  }

  private static AgentId agentIdOf(String id) {
    return new AgentId(UUID.fromString(id));
  }

  @Test
  void a_message_with_no_text_is_a_400() {
    ChatClient chat = chat();

    int status = chat.say(UUID.randomUUID().toString(), null);

    assertThat(status).isEqualTo(400);
  }

  @Test
  void a_message_of_only_blanks_is_a_400() {
    ChatClient chat = chat();

    int status = chat.say(UUID.randomUUID().toString(), "   ");

    assertThat(status).isEqualTo(400);
  }
}
