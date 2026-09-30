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
package org.jwcarman.nessy.engine.embedding;

import io.micrometer.observation.ObservationRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.embedding.Dimension;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.EmbedderConfig;
import org.jwcarman.nessy.api.embedding.EmbedderFactory;
import org.jwcarman.nessy.embedding.EmbeddingOptions;
import org.jwcarman.nessy.embedding.EmbeddingProvider;
import org.jwcarman.nessy.engine.observability.ObservedEmbedder;

/**
 * Embedders over the embedding providers an application registered by name.
 *
 * <p>The only implementation there needs to be, because nothing here is a vendor's business: the
 * connections are the providers' and the model is the store's. A store's provider is resolved
 * exactly once, when its embedder is made; nothing downstream of that sees an id. Every embedder is
 * handed out observed, so an application that gives the factory a registry gets spans without
 * knowing the wrapper's name.
 */
public final class DefaultEmbedderFactory implements EmbedderFactory {

  private final Map<ProviderId, EmbeddingProvider> providers;
  private final @Nullable ProviderId defaultProvider;
  private final @Nullable EmbeddingOptions defaultOptions;
  private final ObservationRegistry observations;

  private DefaultEmbedderFactory(EmbedderFactoryConfig config) {
    this.providers = config.providers();
    this.defaultProvider = config.defaultProvider();
    this.defaultOptions = config.defaultOptions();
    this.observations = config.observations();
  }

  /**
   * One factory, from every customizer that has something to say about it, in order, each adding to
   * the same config before anything is built from it.
   */
  public static DefaultEmbedderFactory of(List<Customizer<EmbedderFactoryConfig>> customizers) {
    Objects.requireNonNull(customizers, "customizers must not be null");
    EmbedderFactoryConfig config = new EmbedderFactoryConfig();
    customizers.forEach(customizer -> customizer.customize(config));
    return new DefaultEmbedderFactory(config);
  }

  /** One customizer, for a caller that is not a container. */
  public static DefaultEmbedderFactory of(Customizer<EmbedderFactoryConfig> customizer) {
    return of(List.of(Objects.requireNonNull(customizer, "customizer must not be null")));
  }

  /**
   * Resolves the provider, then the model, asks the provider whether it can honour the terms, and
   * mints an observed embedder. Every failure happens here, where a store is built, rather than at
   * its first write.
   */
  @Override
  public Embedder create(Customizer<EmbedderConfig> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    Settings settings = new Settings();
    customizer.customize(settings);
    EmbeddingProvider provider = resolve(settings.provider);
    EmbeddingOptions options = settings.options();
    provider.validate(options);
    return ObservedEmbedder.wrap(new DefaultEmbedder(provider, options), observations);
  }

  private EmbeddingProvider resolve(@Nullable ProviderId named) {
    if (named == null) {
      throw new IllegalStateException(
          "an embedder names no provider and the factory has no default; registered: "
              + registered());
    }
    EmbeddingProvider provider = providers.get(named);
    if (provider == null) {
      throw new IllegalStateException(
          "an embedder names provider '"
              + named.value()
              + "', which is not registered; registered: "
              + registered());
    }
    return provider;
  }

  private String registered() {
    return providers.keySet().stream()
        .map(ProviderId::value)
        .collect(Collectors.joining(", ", "[", "]"));
  }

  /** One store's say, seeded field by field from the factory's defaults. */
  private final class Settings implements EmbedderConfig {

    private @Nullable ProviderId provider = defaultProvider;
    private @Nullable String model = defaultOptions == null ? null : defaultOptions.modelName();
    private Optional<Dimension> dimension =
        defaultOptions == null ? Optional.empty() : defaultOptions.dimension();
    private final Map<String, String> properties =
        new LinkedHashMap<>(defaultOptions == null ? Map.of() : defaultOptions.properties());

    @Override
    public EmbedderConfig provider(ProviderId id) {
      this.provider = Objects.requireNonNull(id, "id must not be null");
      return this;
    }

    @Override
    public EmbedderConfig model(String model) {
      this.model = Objects.requireNonNull(model, "model must not be null");
      return this;
    }

    @Override
    public EmbedderConfig dimension(Dimension dimension) {
      this.dimension = Optional.of(Objects.requireNonNull(dimension, "dimension must not be null"));
      return this;
    }

    @Override
    public EmbedderConfig property(String name, String value) {
      Objects.requireNonNull(name, "name must not be null");
      Objects.requireNonNull(value, "value must not be null");
      if (name.isBlank()) {
        throw new IllegalArgumentException("name must not be blank");
      }
      if (value.isBlank()) {
        throw new IllegalArgumentException("value must not be blank");
      }
      properties.put(name, value);
      return this;
    }

    private EmbeddingOptions options() {
      if (model == null) {
        throw new IllegalStateException(
            "an embedder needs a model: model(...), or a factory default");
      }
      return new EmbeddingOptions(model, dimension, properties);
    }
  }
}
