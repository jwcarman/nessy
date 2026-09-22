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
import org.jwcarman.nessy.api.embedding.EmbedderFactory;
import org.jwcarman.nessy.embedding.openai.OpenAiEmbedderConfig;
import org.jwcarman.nessy.embedding.openai.OpenAiEmbeddingProvider;
import org.jwcarman.nessy.engine.embedding.DefaultEmbedderFactory;
import org.jwcarman.nessy.engine.observability.ObservedEmbedder;
import org.jwcarman.nessy.spi.embedding.EmbeddingProvider;
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
    OpenAiEmbeddingProvider provider = OpenAiEmbeddingProvider.create(c -> c.apiKey(apiKey));
    return factory(
        provider,
        EmbeddingModels.modelOr(model, OpenAiEmbedderConfig.DEFAULT_MODEL),
        EmbeddingModels.dimensionOr(dimension, provider.defaultDimension()),
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
        OpenAiEmbeddingProvider.create(
            c -> {
              c.apiKey(apiKey);
              if (baseUrl != null) {
                c.baseUrl(baseUrl);
              }
            });
    return factory(
        provider,
        model,
        EmbeddingModels.dimensionOr(dimension, provider.defaultDimension()),
        observations);
  }

  /**
   * Embedders over one connection, each one watched.
   *
   * <p>Wrapped as they are minted rather than the provider being wrapped once, because what a
   * report wants to say is which model was asked and how wide its vectors are -- facts of the
   * embedder rather than of the connection behind it.
   */
  private static EmbedderFactory factory(
      EmbeddingProvider provider,
      String model,
      OptionalInt dimension,
      ObservationRegistry observations) {
    EmbedderFactory embedders = new DefaultEmbedderFactory(provider, model, dimension);
    return customizer -> ObservedEmbedder.wrap(embedders.create(customizer), observations);
  }
}
