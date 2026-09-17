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
import org.jwcarman.nessy.inference.openai.OpenAiInferenceProvider;
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
 * OpenAI (and xAI, which rides this same wire) as Boot citizens.
 *
 * <p>{@code OPENAI_API_KEY} present (Boot's relaxed binding reads it as {@code openai.api-key})
 * contributes an {@link OpenAiInferenceProvider} bean, with {@code OPENAI_BASE_URL} ({@code
 * openai.base-url}) layered on when it is also present — local runtimes (LM Studio, Ollama) and
 * gateways (OpenRouter) stay zero-code env citizens. {@code XAI_API_KEY} ({@code xai.api-key})
 * contributes a second, separate bean at xAI's fixed base URL: Grok as a first-class citizen with
 * zero new provider code, OpenAI's wire protocol at xAI's URL.
 *
 * <p>An application's own {@code JsonMapper} bean is handed to the provider when there is one, so
 * the adapter reads tool schemas with the mapper the application configured rather than one it made
 * behind the application's back.
 *
 * <p>{@code @ConditionalOnMissingBean} rather than a hard requirement: an application that declares
 * its own {@link InferenceProvider} bean is choosing one explicitly, and this backs off entirely —
 * the same convention {@code NessyAutoConfiguration} follows. Two keys present at once still
 * resolves to exactly one bean, in declaration order below, rather than an ambiguous context.
 */
@AutoConfiguration
@ConditionalOnClass(OpenAiInferenceProvider.class)
public class OpenAiAutoConfiguration {

  /** xAI has no module of its own and never will; this wire, that vendor's URL. */
  static final String XAI_BASE_URL = "https://api.x.ai/v1";

  /**
   * The OpenTelemetry GenAI semantic conventions' pinned value for xAI. The gateway class is shared
   * with OpenAI, so the vendor identity has to be stamped here, where the key that named the vendor
   * was read (agentic-o11y spec §1.1).
   */
  static final String XAI_PROVIDER_NAME = "x_ai";

  @Bean
  @ConditionalOnProperty(name = "openai.api-key")
  @ConditionalOnMissingBean(InferenceProvider.class)
  public InferenceProvider openAiInferenceProvider(
      @Value("${openai.api-key}") String apiKey,
      @Value("${openai.base-url:#{null}}") String baseUrl,
      ObjectProvider<JsonMapper> mappers,
      ObservationRegistry observations) {
    InferenceProvider provider =
        OpenAiInferenceProvider.create(
            c -> {
              c.apiKey(apiKey);
              if (baseUrl != null) {
                c.baseUrl(baseUrl);
              }
              mappers.ifAvailable(c::mapper);
            });
    return ObservedInferenceProvider.wrap(provider, observations);
  }

  @Bean
  @ConditionalOnProperty(name = "xai.api-key")
  @ConditionalOnMissingBean(InferenceProvider.class)
  public InferenceProvider xaiInferenceProvider(
      @Value("${xai.api-key}") String apiKey,
      ObjectProvider<JsonMapper> mappers,
      ObservationRegistry observations) {
    InferenceProvider provider =
        OpenAiInferenceProvider.create(
            c -> {
              c.apiKey(apiKey).baseUrl(XAI_BASE_URL).provider(XAI_PROVIDER_NAME);
              mappers.ifAvailable(c::mapper);
            });
    return ObservedInferenceProvider.wrap(provider, observations);
  }
}
