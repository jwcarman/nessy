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
package org.jwcarman.nessy.spring.boot.inference;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("The Gemini auto-configuration")
class GeminiAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ObservationAutoConfiguration.class, GeminiAutoConfiguration.class));

  @Test
  void gemini_api_key_contributes_a_provider() {
    runner
        .withPropertyValues("gemini.api-key=test")
        .run(context -> assertThat(context).hasSingleBean(InferenceProvider.class));
  }

  @Test
  void googles_other_name_for_the_key_does_too() {
    runner
        .withPropertyValues("google.api-key=test")
        .run(context -> assertThat(context).hasSingleBean(InferenceProvider.class));
  }

  @Test
  void with_neither_key_there_is_no_bean() {
    runner.run(context -> assertThat(context).doesNotHaveBean(InferenceProvider.class));
  }

  @Test
  void an_applications_own_provider_wins() {
    runner
        .withPropertyValues("gemini.api-key=test")
        .withBean(InferenceProvider.class, () -> (request, narrator) -> null)
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(InferenceProvider.class)
                    .doesNotHaveBean("geminiInferenceProvider"));
  }
}
