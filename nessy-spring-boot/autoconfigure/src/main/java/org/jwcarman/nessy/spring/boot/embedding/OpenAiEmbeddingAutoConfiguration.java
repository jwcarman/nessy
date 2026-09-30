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
import org.jwcarman.nessy.embedding.openai.OpenAiEmbedderConfig;
import org.jwcarman.nessy.embedding.openai.OpenAiEmbeddingProvider;
import org.jwcarman.nessy.engine.embedding.DefaultEmbedderFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * OpenAI's embeddings, from the same key and base URL its chat models use.
 *
 * <p><b>Two beans, because a base URL changes what can be assumed.</b> At OpenAI itself the default
 * model is a fact, so a key alone is enough. At an OpenAI-compatible endpoint -- LM Studio, Ollama,
 * vLLM -- it is a guess, and a wrong one fails at the first call with a model nobody named. So a
 * base URL means the model has to be said.
 */
@AutoConfiguration(after = VoyageEmbeddingAutoConfiguration.class)
@ConditionalOnClass(OpenAiEmbeddingProvider.class)
@ConditionalOnConfiguredProperty("openai.api-key")
public class OpenAiEmbeddingAutoConfiguration {

  @Bean
  @Conditional(OnNoOpenAiBaseUrl.class)
  @ConditionalOnMissingBean(EmbedderFactory.class)
  public EmbedderFactory openAiEmbedders(
      @Value("${openai.api-key}") String apiKey,
      @Value("${nessy.embedding.openai.model:}") String model,
      @Value("${nessy.embedding.openai.dimension:}") String dimension,
      ObservationRegistry observations) {
    OpenAiEmbeddingProvider provider = OpenAiEmbeddingProvider.of(c -> c.apiKey(apiKey));
    return factory(
        "openai",
        provider,
        EmbeddingModels.modelOr(model, OpenAiEmbedderConfig.DEFAULT_MODEL),
        EmbeddingModels.dimensionOr(dimension, OptionalInt.empty()),
        observations);
  }

  /** An OpenAI-compatible endpoint serves the models it serves, so this one names its own. */
  @Bean
  @ConditionalOnConfiguredProperty("nessy.embedding.openai.model")
  @ConditionalOnMissingBean(EmbedderFactory.class)
  public EmbedderFactory openAiCompatibleEmbedders(
      @Value("${openai.api-key}") String apiKey,
      @Value("${openai.base-url:#{null}}") String baseUrl,
      @Value("${nessy.embedding.openai.model}") String model,
      @Value("${nessy.embedding.openai.dimension:}") String dimension,
      ObservationRegistry observations) {
    OpenAiEmbeddingProvider provider =
        OpenAiEmbeddingProvider.of(
            c -> {
              c.apiKey(apiKey);
              if (baseUrl != null) {
                c.baseUrl(baseUrl);
              }
            });
    return factory(
        "openai",
        provider,
        model,
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
