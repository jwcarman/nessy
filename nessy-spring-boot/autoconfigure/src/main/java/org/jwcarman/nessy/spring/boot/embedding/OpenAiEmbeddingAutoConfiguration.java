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
import org.jwcarman.nessy.api.Embedder;
import org.jwcarman.nessy.embedding.ObservedEmbedder;
import org.jwcarman.nessy.embedding.openai.OpenAiEmbedder;
import org.jwcarman.nessy.embedding.openai.OpenAiEmbedderConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnClass(OpenAiEmbedder.class)
@ConditionalOnProperty(name = "openai.api-key")
public class OpenAiEmbeddingAutoConfiguration {

  @Bean
  @Conditional(OnNoOpenAiBaseUrl.class)
  @ConditionalOnMissingBean(Embedder.class)
  public Embedder openAiEmbedder(
      @Value("${openai.api-key}") String apiKey,
      @Value("${nessy.embedding.openai.model:" + OpenAiEmbedderConfig.DEFAULT_MODEL + "}")
          String model,
      ObservationRegistry observations) {
    return observed(OpenAiEmbedder.create(c -> c.apiKey(apiKey).model(model)), observations);
  }

  /** An OpenAI-compatible endpoint serves the models it serves, so this one names its own. */
  @Bean
  @ConditionalOnProperty(name = "nessy.embedding.openai.model")
  @ConditionalOnMissingBean(Embedder.class)
  public Embedder openAiCompatibleEmbedder(
      @Value("${openai.api-key}") String apiKey,
      @Value("${openai.base-url:#{null}}") String baseUrl,
      @Value("${nessy.embedding.openai.model}") String model,
      ObservationRegistry observations) {
    return observed(
        OpenAiEmbedder.create(
            c -> {
              c.apiKey(apiKey).model(model);
              if (baseUrl != null) {
                c.baseUrl(baseUrl);
              }
            }),
        observations);
  }

  private static Embedder observed(Embedder embedder, ObservationRegistry observations) {
    return ObservedEmbedder.wrap(embedder, observations);
  }
}
