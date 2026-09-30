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
import java.util.OptionalInt;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.embedding.EmbedderFactory;
import org.jwcarman.nessy.embedding.EmbeddingOptions;
import org.jwcarman.nessy.embedding.EmbeddingProvider;
import org.jwcarman.nessy.embedding.gemini.GeminiEmbedderConfig;
import org.jwcarman.nessy.embedding.gemini.GeminiEmbeddingProvider;
import org.jwcarman.nessy.engine.embedding.DefaultEmbedderFactory;
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
      @Value("${nessy.embedding.gemini.dimension:}") String dimension,
      ObservationRegistry observations) {
    return observed(apiKey, model, dimension, observations);
  }

  @Bean
  @ConditionalOnConfiguredProperty("google.api-key")
  @ConditionalOnMissingBean(EmbedderFactory.class)
  public EmbedderFactory googleEmbedders(
      @Value("${google.api-key}") String apiKey,
      @Value("${nessy.embedding.gemini.model:}") String model,
      @Value("${nessy.embedding.gemini.dimension:}") String dimension,
      ObservationRegistry observations) {
    return observed(apiKey, model, dimension, observations);
  }

  private static EmbedderFactory observed(
      String apiKey, String model, String dimension, ObservationRegistry observations) {
    GeminiEmbeddingProvider provider = GeminiEmbeddingProvider.of(c -> c.apiKey(apiKey));
    return factory(
        "gemini",
        provider,
        EmbeddingModels.modelOr(model, GeminiEmbedderConfig.DEFAULT_MODEL),
        EmbeddingModels.dimensionOr(dimension, OptionalInt.empty()),
        observations);
  }

  /**
   * Embedders over one connection, registered under the vendor's id with the configured model as
   * the default; the factory wraps every embedder it mints.
   */
  private static EmbedderFactory factory(
      String id,
      EmbeddingProvider provider,
      String model,
      OptionalInt dimension,
      ObservationRegistry observations) {
    ProviderId providerId = ProviderId.of(id);
    return DefaultEmbedderFactory.of(
        f ->
            f.provider(providerId, provider)
                .embedding(providerId, new EmbeddingOptions(model, dimension))
                .observations(observations));
  }
}
