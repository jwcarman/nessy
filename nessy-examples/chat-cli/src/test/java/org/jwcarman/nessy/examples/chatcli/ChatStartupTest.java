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

package org.jwcarman.nessy.examples.chatcli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.DirectHarnessFactory;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The whole application, started: the starter's factory, the database the notebook and the plan
 * live in, and a model that is a lambda. No network, no key, and no console -- {@code
 * chat.terminal=false} leaves the terminal runner out.
 */
@Tag("container")
@SpringBootTest(
    properties = {
      "chat.terminal=false",
      "nessy.provider=scriptedModel",
      "nessy.model=scripted",
      "spring.docker.compose.enabled=false"
    })
@Import(ChatStartupTest.Infrastructure.class)
class ChatStartupTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class Infrastructure {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
      return new PostgreSQLContainer("postgres:18-alpine");
    }

    // Declared, so no preset is lit and nothing here talks to a model.
    @Bean
    InferenceProvider scriptedModel() {
      return (InferenceRequest request, InferenceNarrator narrator) ->
          new InferenceResult.Answer(List.of(new Block.Text("hello from the script")));
    }
  }

  @Autowired private DirectHarnessFactory harnesses;

  @Nested
  class When_the_application_starts {

    @Test
    void the_starters_direct_harness_factory_is_the_one_in_the_context() {
      assertThat(harnesses).isNotNull();
    }

    @Test
    void a_turn_is_answered_by_the_provider_the_properties_name() {
      DirectHarness<String, String> harness =
          harnesses.<String>create(
              new AgentType("chat"),
              h ->
                  h.systemPrompt("Be brief.").inputRenderer(said -> List.of(new Block.Text(said))));

      Outcome<String> outcome = harness.ask(AgentId.random(), "hi");

      assertThat(outcome).isInstanceOf(Outcome.Answered.class);
      assertThat(((Outcome.Answered<String>) outcome).value()).isEqualTo("hello from the script");
    }
  }
}
