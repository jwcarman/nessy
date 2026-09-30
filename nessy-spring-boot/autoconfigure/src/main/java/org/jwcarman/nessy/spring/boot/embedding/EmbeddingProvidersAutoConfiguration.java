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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.embedding.Dimension;
import org.jwcarman.nessy.api.embedding.EmbedderFactory;
import org.jwcarman.nessy.embedding.EmbeddingOptions;
import org.jwcarman.nessy.embedding.EmbeddingProvider;
import org.jwcarman.nessy.engine.embedding.DefaultEmbedderFactory;
import org.jwcarman.nessy.spring.boot.NessyProperties;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers every {@code nessy.embedders.<id>} preset (lit by a vendor key, when that vendor's
 * embedding adapter is on the classpath) and every custom embedder as an {@code EmbeddingProvider}
 * bean named {@code <id>Embeddings}, and contributes the one {@link EmbedderFactory} over them.
 *
 * <p>Every application-declared {@code EmbeddingProvider} bean joins the same registry beside the
 * presets, under its bean name. The factory's default is {@code nessy.embedder} and {@code
 * nessy.embedding-model}, set together or not at all, with {@code nessy.embedding-dimension}
 * optional beside them.
 */
@AutoConfiguration
@EnableConfigurationProperties(NessyProperties.class)
public class EmbeddingProvidersAutoConfiguration {

  /** The bean name of the starter's own factory. */
  static final String FACTORY_BEAN = "nessyEmbedderFactory";

  @Bean
  static EmbedderRegistrar nessyEmbedderRegistrar() {
    return new EmbedderRegistrar();
  }

  @Bean
  // Against the INTERFACE, for the reason DirectHarnessAutoConfiguration gives: an application
  // declaring its own factory declares it as EmbedderFactory. Present whether or not anything is
  // registered -- @ConditionalOnBean cannot see definitions the registrar added -- and a factory
  // with nothing registered says so when a store asks it for an embedder.
  @ConditionalOnMissingBean(EmbedderFactory.class)
  public DefaultEmbedderFactory nessyEmbedderFactory(
      ObjectProvider<ResolvedEmbedders> resolvedEmbedders,
      ListableBeanFactory beans,
      NessyProperties properties,
      ObjectProvider<ObservationRegistry> observations) {
    requireDefaults(properties);
    Map<String, EmbeddingProvider> providers = beans.getBeansOfType(EmbeddingProvider.class);
    List<ResolvedEmbedder> resolved =
        resolvedEmbedders.getIfAvailable(() -> new ResolvedEmbedders(List.of())).embedders();
    Set<String> resolvedBeans =
        resolved.stream().map(ResolvedEmbedder::beanName).collect(Collectors.toSet());
    DefaultEmbedderFactory factory =
        DefaultEmbedderFactory.of(
            config -> {
              // Presets and custom embedders under their ids; everything else under its bean name.
              resolved.forEach(
                  embedder ->
                      config.provider(
                          ProviderId.of(embedder.id()), providers.get(embedder.beanName())));
              providers.forEach(
                  (name, provider) -> {
                    if (!resolvedBeans.contains(name)) {
                      config.provider(providerId(name), provider);
                    }
                  });
              config.observations(observations.getIfAvailable(() -> ObservationRegistry.NOOP));
              if (properties.embedder() != null) {
                Optional<Dimension> width =
                    Optional.ofNullable(properties.embeddingDimension()).map(Dimension::new);
                config.embedding(
                    ProviderId.of(properties.embedder()),
                    new EmbeddingOptions(properties.embeddingModel(), width));
              }
            });
    if (properties.embedder() != null) {
      // Once, here: a mistyped default fails at startup listing what is registered, rather than at
      // the first store that asks. The embedder is thrown away; nothing is called.
      factory.create(config -> {});
    }
    return factory;
  }

  /** Says what will make the vectors, before a single store asks. */
  @Bean
  @ConditionalOnMissingBean
  public EmbeddingReport nessyEmbeddingReport(
      ObjectProvider<ResolvedEmbedders> resolvedEmbedders,
      ListableBeanFactory beans,
      NessyProperties properties) {
    return new EmbeddingReport(resolvedEmbedders, beans, properties);
  }

  /**
   * A bean's Spring name, as a {@link ProviderId} -- so a bean whose name breaks the id rule fails
   * naming the bean, not with {@link ProviderId}'s own message alone.
   */
  private static ProviderId providerId(String beanName) {
    try {
      return ProviderId.of(beanName);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "the EmbeddingProvider bean '"
              + beanName
              + "' cannot be a provider id: "
              + e.getMessage(),
          e);
    }
  }

  /**
   * {@code nessy.embedder} and {@code nessy.embedding-model} are a pair: set both, or neither. The
   * width needs the pair. Blank is unset ({@link NessyProperties} has already said so).
   */
  private static void requireDefaults(NessyProperties properties) {
    String embedder = properties.embedder();
    String model = properties.embeddingModel();
    Integer dimension = properties.embeddingDimension();
    if ((embedder == null) != (model == null)) {
      throw new IllegalStateException(
          "nessy.embedder and nessy.embedding-model are a pair: set both, or neither and name them"
              + " on each store (nessy.embedder="
              + embedder
              + ", nessy.embedding-model="
              + model
              + ")");
    }
    if (dimension != null && embedder == null) {
      throw new IllegalStateException(
          "nessy.embedding-dimension is the default embedder's width: set it with nessy.embedder"
              + " and nessy.embedding-model");
    }
    if (dimension != null) {
      try {
        Dimension.of(dimension);
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException("nessy.embedding-dimension: " + e.getMessage(), e);
      }
    }
  }
}
