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

import io.micrometer.observation.ObservationRegistry;
import org.jwcarman.nessy.engine.observability.ObservedInferenceProvider;
import org.jwcarman.nessy.inference.gemini.GeminiInferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Gemini provider once its key is in the environment: {@code GEMINI_API_KEY} ({@code
 * gemini.api-key} under Boot's relaxed binding), or Google's other documented name for it, {@code
 * GOOGLE_API_KEY}. Either bean backs off to an application's own {@link InferenceProvider}.
 *
 * <p>Bedrock deliberately has no counterpart here. AWS credentials are ambient on most machines,
 * and a mechanism that let their presence choose a provider would route an application with a stray
 * profile to Bedrock; an application that wants Bedrock says so in code.
 */
@AutoConfiguration
@ConditionalOnClass(GeminiInferenceProvider.class)
public class GeminiAutoConfiguration {

  @Bean
  @ConditionalOnProperty(name = "gemini.api-key")
  @ConditionalOnMissingBean(InferenceProvider.class)
  public InferenceProvider geminiInferenceProvider(
      @Value("${gemini.api-key}") String apiKey,
      ObjectProvider<JsonMapper> mappers,
      ObservationRegistry observations) {
    return observed(apiKey, mappers, observations);
  }

  @Bean
  @ConditionalOnProperty(name = "google.api-key")
  @ConditionalOnMissingBean(InferenceProvider.class)
  public InferenceProvider googleInferenceProvider(
      @Value("${google.api-key}") String apiKey,
      ObjectProvider<JsonMapper> mappers,
      ObservationRegistry observations) {
    return observed(apiKey, mappers, observations);
  }

  /** Both keys name the same vendor, so both beans are the same provider. */
  private static InferenceProvider observed(
      String apiKey, ObjectProvider<JsonMapper> mappers, ObservationRegistry observations) {
    InferenceProvider provider =
        GeminiInferenceProvider.create(
            c -> {
              c.apiKey(apiKey);
              mappers.ifAvailable(c::mapper);
            });
    return ObservedInferenceProvider.wrap(provider, observations);
  }
}
