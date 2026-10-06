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
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * A turn that ends without an answer ends in a system line in the state, and that line is how the
 * page tells such a turn from one still going when it reads the state after a reconnect.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "nessy.provider=endingModels")
@Import(PostgresBacked.class)
class ChatTurnEndingsIntegrationTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class EndingModelConfiguration {
    @Bean
    InferenceProvider endingModels() {
      return (request, narrator) -> {
        String said =
            request.context().turns().stream()
                .filter(turn -> !turn.complete())
                .flatMap(turn -> turn.input().blocks().stream())
                .map(block -> block instanceof Block.Text(String text) ? text : "")
                .reduce("", String::concat);
        if (said.contains("refuse")) {
          return new InferenceResult.Refusal("policy");
        }
        if (said.contains("fail")) {
          return new InferenceResult.Fault(new Failure.Permanent("the model is down"));
        }
        return new InferenceResult.Answer(List.of(new Block.Text("Noted.")));
      };
    }
  }

  @LocalServerPort private int port;

  private static Map<String, Object> lastLine(ChatClient.PageState state) {
    return state.transcript().getLast();
  }

  @Test
  void a_refused_turn_ends_in_a_system_line_and_no_answer() {
    ChatClient chat = new ChatClient(port);
    String agentId = UUID.randomUUID().toString();

    assertThat(chat.say(agentId, "please refuse")).isEqualTo(202);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(chat.state(agentId).working()).isFalse());
    ChatClient.PageState state = chat.state(agentId);
    assertThat(state.texts()).containsExactly("please refuse", "the agent declined to answer");
    assertThat(lastLine(state)).containsEntry("role", "system");
  }

  @Test
  void a_failed_turn_ends_in_a_system_line_and_no_answer() {
    ChatClient chat = new ChatClient(port);
    String agentId = UUID.randomUUID().toString();

    assertThat(chat.say(agentId, "please fail")).isEqualTo(202);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(chat.state(agentId).working()).isFalse());
    ChatClient.PageState state = chat.state(agentId);
    assertThat(state.texts()).containsExactly("please fail", "the agent could not answer");
    assertThat(lastLine(state)).containsEntry("role", "system");
  }
}
