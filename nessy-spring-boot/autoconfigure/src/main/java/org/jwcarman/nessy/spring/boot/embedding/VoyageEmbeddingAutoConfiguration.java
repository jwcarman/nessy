package org.jwcarman.nessy.spring.boot.embedding;

import io.micrometer.observation.ObservationRegistry;
import org.jwcarman.nessy.embedding.Embedder;
import org.jwcarman.nessy.embedding.ObservedEmbedder;
import org.jwcarman.nessy.embedding.voyage.VoyageEmbedder;
import org.jwcarman.nessy.embedding.voyage.VoyageEmbedderConfig;
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
@ConditionalOnClass(VoyageEmbedder.class)
@ConditionalOnProperty(name = "nessy.embedding.voyage.api-key")
public class VoyageEmbeddingAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(Embedder.class)
  public Embedder voyageEmbedder(
      @Value("${nessy.embedding.voyage.api-key}") String apiKey,
      @Value("${nessy.embedding.voyage.model:" + VoyageEmbedderConfig.DEFAULT_MODEL + "}")
          String model,
      ObservationRegistry observations) {
    Embedder embedder = VoyageEmbedder.create(c -> c.apiKey(apiKey).model(model));
    return ObservedEmbedder.wrap(embedder, observations);
  }
}
