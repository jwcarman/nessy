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
package org.jwcarman.nessy.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.engine.harness.direct.DefaultDirectHarnessFactory;
import org.jwcarman.nessy.engine.harness.queued.DefaultQueuedHarnessFactory;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** A {@code Customizer<HarnessConfig<?>>} bean equips the harnesses of both doors. */
@DisplayName("Harness features from beans")
class HarnessFeaturesWiringTest {

  static class AnAnsweringModel implements InferenceProvider {
    @Override
    public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
      return new InferenceResult.Answer(List.of(new Block.Text("done")));
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AModel {

    @Bean
    AnAnsweringModel model() {
      return new AnAnsweringModel();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AFeature {

    static final List<AgentType> equipped = new CopyOnWriteArrayList<>();

    @Bean
    Customizer<HarnessConfig<?>> recordingFeature() {
      return config -> equipped.add(config.agentType());
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  JacksonAutoConfiguration.class,
                  ObservationAutoConfiguration.class,
                  NessyAutoConfiguration.class,
                  JdbcBackendAutoConfiguration.class,
                  InMemoryBackendAutoConfiguration.class,
                  QueuedHarnessAutoConfiguration.class,
                  DirectHarnessAutoConfiguration.class))
          .withUserConfiguration(AModel.class)
          .withPropertyValues(
              "nessy.model=a-test-model",
              "nessy.provider=model",
              "nessy.system-prompt=you are a test assistant");

  @Test
  void a_feature_bean_equips_harnesses_on_both_doors() {
    AFeature.equipped.clear();
    runner
        .withUserConfiguration(AFeature.class)
        .run(
            context -> {
              context
                  .getBean(DefaultDirectHarnessFactory.class)
                  .<String>create(
                      new AgentType("direct-one"),
                      config -> config.inputRenderer(text -> List.of(new Block.Text(text))));
              context
                  .getBean(DefaultQueuedHarnessFactory.class)
                  .create(
                      new AgentType("queued-one"),
                      String.class,
                      config -> config.systemPrompt("You are a test assistant."));
              assertThat(AFeature.equipped)
                  .containsExactlyInAnyOrder(
                      new AgentType("direct-one"), new AgentType("queued-one"));
            });
  }
}
