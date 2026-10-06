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
package org.jwcarman.nessy.spring.boot.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AskOutcome;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.DirectHarnessFactory;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.jdbc.JdbcDirectBackend;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.spring.boot.DirectHarnessAutoConfiguration;
import org.jwcarman.nessy.spring.boot.InMemoryBackendAutoConfiguration;
import org.jwcarman.nessy.spring.boot.JdbcBackendAutoConfiguration;
import org.jwcarman.nessy.spring.boot.NessyAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * An application with a DataSource of its own and no nessy-backend-jdbc: the in-memory backend
 * keeps the conversation, and nothing creates the nessy_plan_task table. Its agents must still
 * answer.
 */
@DisplayName("The plan store beside the in-memory backend")
class PlanWithoutTheJdbcBackendTest {

  @Configuration(proxyBeanMethods = false)
  static class TheApplication {

    @Bean
    DataSource dataSource() {
      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();
    }

    @Bean
    InferenceProvider model() {
      return (InferenceRequest request, InferenceNarrator narrator) ->
          new InferenceResult.Answer(List.of(new Block.Text("done")));
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withClassLoader(new FilteredClassLoader(JdbcDirectBackend.class))
          .withConfiguration(
              AutoConfigurations.of(
                  JacksonAutoConfiguration.class,
                  ObservationAutoConfiguration.class,
                  NessyAutoConfiguration.class,
                  JdbcBackendAutoConfiguration.class,
                  InMemoryBackendAutoConfiguration.class,
                  DirectHarnessAutoConfiguration.class,
                  PlanAutoConfiguration.class))
          .withUserConfiguration(TheApplication.class)
          .withPropertyValues("nessy.model=a-test-model", "nessy.provider=model");

  @Test
  void an_agent_on_the_in_memory_backend_still_answers() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean("nessyPlanFeature");
          DirectHarness<String, String> harness =
              context
                  .getBean(DirectHarnessFactory.class)
                  .<String>create(
                      new AgentType("chat"),
                      config ->
                          config
                              .systemPrompt("Be brief.")
                              .inputRenderer(said -> List.of(new Block.Text(said))));

          AskOutcome<String> outcome = harness.ask(AgentId.random(), "hello");

          assertThat(outcome).isInstanceOf(AskOutcome.Answered.class);
        });
  }
}
