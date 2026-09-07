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
package org.jwcarman.nessy.model.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gemini's registration as a Boot citizen: what {@code GEMINI_API_KEY} in the environment — read
 * here as the property Boot's relaxed binding turns it into — contributes, and what it does not.
 */
@DisplayName("Gemini's auto-configuration")
class GeminiAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(GeminiAutoConfiguration.class));

  @Test
  @DisplayName("with an API key present, it contributes a ModelProvider")
  void with_an_api_key_present_it_contributes_a_model_provider() {
    runner
        .withPropertyValues("gemini.api-key=gm-test")
        .run(context -> assertThat(context).hasSingleBean(ModelProvider.class));
  }

  @Test
  @DisplayName("with no API key, it contributes nothing")
  void with_no_api_key_it_contributes_nothing() {
    runner.run(context -> assertThat(context).doesNotHaveBean(ModelProvider.class));
  }

  @Nested
  @DisplayName("when the application already supplies its own ModelProvider")
  class WhenTheApplicationSuppliesItsOwn {

    @Test
    @DisplayName("it backs off entirely, the application's bean unmoved")
    void it_backs_off_entirely() {
      runner
          .withPropertyValues("gemini.api-key=gm-test")
          .withUserConfiguration(AModelProvider.class)
          .run(
              context -> {
                assertThat(context).hasSingleBean(ModelProvider.class);
                assertThat(context.getBean(ModelProvider.class)).isSameAs(AModelProvider.INSTANCE);
              });
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AModelProvider {

    static final ModelProvider INSTANCE = id -> null;

    @Bean
    ModelProvider models() {
      return INSTANCE;
    }
  }
}
