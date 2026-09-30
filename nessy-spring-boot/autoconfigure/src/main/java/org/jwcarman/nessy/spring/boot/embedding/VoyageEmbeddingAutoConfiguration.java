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
import org.jwcarman.nessy.embedding.voyage.VoyageEmbedderConfig;
import org.jwcarman.nessy.embedding.voyage.VoyageEmbeddingProvider;
import org.jwcarman.nessy.engine.embedding.DefaultEmbedderFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
@ConditionalOnConfiguredProperty("nessy.embedding.voyage.api-key")
public class VoyageEmbeddingAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(EmbedderFactory.class)
  public EmbedderFactory voyageEmbedders(
      @Value("${nessy.embedding.voyage.api-key}") String apiKey,
      @Value("${nessy.embedding.voyage.model:}") String model,
      @Value("${nessy.embedding.voyage.dimension:}") String dimension,
      ObservationRegistry observations) {
    VoyageEmbeddingProvider provider = VoyageEmbeddingProvider.of(c -> c.apiKey(apiKey));
    return factory(
        "voyage",
        provider,
        EmbeddingModels.modelOr(model, VoyageEmbedderConfig.DEFAULT_MODEL),
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
