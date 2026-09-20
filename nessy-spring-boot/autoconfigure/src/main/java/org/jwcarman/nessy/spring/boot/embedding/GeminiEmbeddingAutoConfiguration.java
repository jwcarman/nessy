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
import org.jwcarman.nessy.embedding.gemini.GeminiEmbedder;
import org.jwcarman.nessy.embedding.gemini.GeminiEmbedderConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Gemini's embeddings, from the same key its chat models use -- either spelling of it, as the
 * inference side accepts both.
 */
@AutoConfiguration(after = OpenAiEmbeddingAutoConfiguration.class)
@ConditionalOnClass(GeminiEmbedder.class)
public class GeminiEmbeddingAutoConfiguration {

  @Bean
  @ConditionalOnProperty(name = "gemini.api-key")
  @ConditionalOnMissingBean(Embedder.class)
  public Embedder geminiEmbedder(
      @Value("${gemini.api-key}") String apiKey,
      @Value("${nessy.embedding.gemini.model:" + GeminiEmbedderConfig.DEFAULT_MODEL + "}")
          String model,
      ObservationRegistry observations) {
    return observed(apiKey, model, observations);
  }

  @Bean
  @ConditionalOnProperty(name = "google.api-key")
  @ConditionalOnMissingBean(Embedder.class)
  public Embedder googleEmbedder(
      @Value("${google.api-key}") String apiKey,
      @Value("${nessy.embedding.gemini.model:" + GeminiEmbedderConfig.DEFAULT_MODEL + "}")
          String model,
      ObservationRegistry observations) {
    return observed(apiKey, model, observations);
  }

  private static Embedder observed(String apiKey, String model, ObservationRegistry observations) {
    Embedder embedder = GeminiEmbedder.create(c -> c.apiKey(apiKey).model(model));
    return ObservedEmbedder.wrap(embedder, observations);
  }
}
