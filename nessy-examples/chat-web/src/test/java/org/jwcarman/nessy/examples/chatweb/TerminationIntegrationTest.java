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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("Terminating a conversation")
class TerminationIntegrationTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class ScriptedModelConfiguration {
    @Bean
    InferenceProvider scriptedModels() {
      return ScriptedProvider.alwaysSaying("Noted.");
    }
  }

  @LocalServerPort private int port;

  private ChatClient chat() {
    return new ChatClient(port);
  }

  @Test
  void ending_a_conversation_keeps_its_story_and_refuses_another_message() {
    ChatClient chat = chat();
    String agentId = UUID.randomUUID().toString();
    assertThat(chat.say(agentId, "remember this")).isEqualTo(202);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(chat.state(agentId).texts()).contains("Noted."));
    List<String> before = chat.state(agentId).texts();

    assertThat(chat.terminate(agentId)).isEqualTo(202);

    // The termination lands once the agent is idle; a message is refused from then on.
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(chat.say(agentId, "and this")).isEqualTo(409));
    // The story is not erased, and the refused word is not in it.
    assertThat(chat.state(agentId).texts()).isEqualTo(before);
  }

  @Test
  @DisplayName("terminating one conversation leaves another alone")
  void ending_is_scoped_to_one_agent() {
    ChatClient chat = chat();
    String kept = UUID.randomUUID().toString();
    String terminated = UUID.randomUUID().toString();
    assertThat(chat.say(kept, "something")).isEqualTo(202);
    assertThat(chat.say(terminated, "something")).isEqualTo(202);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(chat.state(kept).transcript()).hasSize(2);
              assertThat(chat.state(terminated).transcript()).hasSize(2);
            });

    assertThat(chat.terminate(terminated)).isEqualTo(202);

    assertThat(chat.say(kept, "something else")).isEqualTo(202);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(chat.state(kept).transcript()).hasSize(4));
  }

  @Test
  @DisplayName("terminating a conversation nobody ever had is accepted, not an error")
  void ending_a_stranger_is_silent() {
    assertThat(chat().terminate(UUID.randomUUID().toString())).isEqualTo(202);
  }

  @Test
  @DisplayName("an id that is not one is the caller's mistake, not a 500")
  void a_malformed_id_is_a_bad_request() {
    assertThat(chat().get("/api/agents/not-a-uuid")).isEqualTo(400);
  }

  @Test
  void no_approvals_stream_endpoint_remains() {
    // The path still reads as an approval key for a POST, so a GET is a 405 rather than a 404;
    // what matters is that nothing answers it with a stream.
    assertThat(chat().get("/api/agents/" + UUID.randomUUID() + "/approvals/events")).isEqualTo(405);
  }
}
