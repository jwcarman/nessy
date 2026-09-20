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
import org.jwcarman.nessy.embedding.voyage.VoyageEmbedderConfig;
import org.jwcarman.nessy.embedding.voyage.VoyageEmbeddingProvider;
import org.jwcarman.nessy.engine.embedding.DefaultEmbedderFactory;
import org.jwcarman.nessy.engine.observability.ObservedEmbedder;
import org.jwcarman.nessy.spi.embedding.EmbeddingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Voyage's embeddings, when the module is on the classpath and a key is configured.
 *
 * <p>First of the three, because a Voyage key exists for one reason: an application that configured
 * one meant its embeddings to come from Voyage, whatever it talks to for chat. Anthropic has no
 * embedding API of its own, and this is the pairing it recommends.
 *
 * <p>The key lives under {@code nessy.embedding.voyage} rather than beside the chat providers',
 * because there is no Voyage chat model for it to be shared with.
 */
@AutoConfiguration
@ConditionalOnClass(VoyageEmbeddingProvider.class)
@ConditionalOnProperty(name = "nessy.embedding.voyage.api-key")
public class VoyageEmbeddingAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(EmbedderFactory.class)
  public EmbedderFactory voyageEmbedders(
      @Value("${nessy.embedding.voyage.api-key}") String apiKey,
      @Value("${nessy.embedding.voyage.model:" + VoyageEmbedderConfig.DEFAULT_MODEL + "}")
          String model,
      ObservationRegistry observations) {
    EmbeddingProvider provider = VoyageEmbeddingProvider.create(c -> c.apiKey(apiKey));
    return factory(provider, model, observations);
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
