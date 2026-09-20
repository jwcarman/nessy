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
package org.jwcarman.nessy.spring.boot.embedding;

import io.micrometer.observation.ObservationRegistry;
import org.jwcarman.nessy.api.embedding.EmbedderFactory;
import org.jwcarman.nessy.embedding.gemini.GeminiEmbedderConfig;
import org.jwcarman.nessy.embedding.gemini.GeminiEmbeddingProvider;
import org.jwcarman.nessy.engine.embedding.DefaultEmbedderFactory;
import org.jwcarman.nessy.engine.observability.ObservedEmbedder;
import org.jwcarman.nessy.spi.embedding.EmbeddingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Gemini's embeddings, from the same key its chat models use -- either spelling of it, as the
 * inference side accepts both.
 */
@AutoConfiguration(after = OpenAiEmbeddingAutoConfiguration.class)
@ConditionalOnClass(GeminiEmbeddingProvider.class)
public class GeminiEmbeddingAutoConfiguration {

  @Bean
  @ConditionalOnConfiguredProperty("gemini.api-key")
  @ConditionalOnMissingBean(EmbedderFactory.class)
  public EmbedderFactory geminiEmbedders(
      @Value("${gemini.api-key}") String apiKey,
      @Value("${nessy.embedding.gemini.model:}") String model,
      ObservationRegistry observations) {
    return observed(apiKey, model, observations);
  }

  @Bean
  @ConditionalOnConfiguredProperty("google.api-key")
  @ConditionalOnMissingBean(EmbedderFactory.class)
  public EmbedderFactory googleEmbedders(
      @Value("${google.api-key}") String apiKey,
      @Value("${nessy.embedding.gemini.model:}") String model,
      ObservationRegistry observations) {
    return observed(apiKey, model, observations);
  }

  private static EmbedderFactory observed(
      String apiKey, String model, ObservationRegistry observations) {
    EmbeddingProvider provider = GeminiEmbeddingProvider.create(c -> c.apiKey(apiKey));
    return factory(
        provider, EmbeddingModels.modelOr(model, GeminiEmbedderConfig.DEFAULT_MODEL), observations);
  }

  /**
   * Embedders over one connection, each one watched.
   *
   * <p>Wrapped as they are minted rather than the provider being wrapped once, because what a
   * report wants to say is which model was asked and how wide its vectors are -- facts of the
   * embedder rather than of the connection behind it.
   */
  private static EmbedderFactory factory(
      EmbeddingProvider provider, String model, ObservationRegistry observations) {
    EmbedderFactory embedders = new DefaultEmbedderFactory(provider, model);
    return customizer -> ObservedEmbedder.wrap(embedders.create(customizer), observations);
  }
}
