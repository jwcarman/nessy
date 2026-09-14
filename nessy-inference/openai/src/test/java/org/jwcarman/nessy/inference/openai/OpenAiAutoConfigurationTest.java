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
package org.jwcarman.nessy.inference.openai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAI's (and xAI's) registration as Boot citizens: what {@code OPENAI_API_KEY} / {@code
 * XAI_API_KEY} in the environment — read here as the properties Boot's relaxed binding turns them
 * into — contribute, and what they do not.
 */
@DisplayName("OpenAI's auto-configuration")
class OpenAiAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(OpenAiAutoConfiguration.class));

  @Test
  @DisplayName("with an OpenAI key present, it contributes an InferenceProvider")
  void with_an_openai_key_present_it_contributes_an_inference_provider() {
    runner
        .withPropertyValues("openai.api-key=sk-test")
        .run(context -> assertThat(context).hasSingleBean(InferenceProvider.class));
  }

  @Test
  @DisplayName("with an xAI key present, it contributes an InferenceProvider")
  void with_an_xai_key_present_it_contributes_an_inference_provider() {
    runner
        .withPropertyValues("xai.api-key=xai-test")
        .run(context -> assertThat(context).hasSingleBean(InferenceProvider.class));
  }

  @Test
  @DisplayName("with no key at all, it contributes nothing")
  void with_no_key_at_all_it_contributes_nothing() {
    runner.run(context -> assertThat(context).doesNotHaveBean(InferenceProvider.class));
  }

  @Nested
  @DisplayName("when the application already supplies its own InferenceProvider")
  class WhenTheApplicationSuppliesItsOwn {

    @Test
    @DisplayName("it backs off entirely, the application's bean unmoved")
    void it_backs_off_entirely() {
      runner
          .withPropertyValues("openai.api-key=sk-test", "xai.api-key=xai-test")
          .withUserConfiguration(AnInferenceProvider.class)
          .run(
              context -> {
                assertThat(context).hasSingleBean(InferenceProvider.class);
                assertThat(context.getBean(InferenceProvider.class))
                    .isSameAs(AnInferenceProvider.INSTANCE);
              });
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AnInferenceProvider {

    static final InferenceProvider INSTANCE =
        (request, narrator) -> new InferenceResult.Refusal("this provider answers nothing");

    @Bean
    InferenceProvider inference() {
      return INSTANCE;
    }
  }
}
