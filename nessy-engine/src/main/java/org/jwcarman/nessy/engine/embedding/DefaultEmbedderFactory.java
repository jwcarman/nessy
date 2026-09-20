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

import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.EmbedderConfig;
import org.jwcarman.nessy.api.embedding.EmbedderFactory;
import org.jwcarman.nessy.spi.embedding.EmbeddingOptions;
import org.jwcarman.nessy.spi.embedding.EmbeddingProvider;

/**
 * Embedders over one connection, one per model.
 *
 * <p>The only implementation there needs to be, because nothing here is a vendor's business: the
 * connection is the provider's and the model is the caller's. A harness factory is this same shape
 * over an inference provider, for the same reason.
 */
public final class DefaultEmbedderFactory implements EmbedderFactory {

  private final EmbeddingProvider provider;
  private final String defaultModel;

  /**
   * @param defaultModel what an embedder gets when it names none; null to require one, which is the
   *     honest setting when no model is obviously right
   */
  public DefaultEmbedderFactory(EmbeddingProvider provider, String defaultModel) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.defaultModel = defaultModel;
  }

  /** Every embedder names its own model. */
  public DefaultEmbedderFactory(EmbeddingProvider provider) {
    this(provider, null);
  }

  @Override
  public Embedder create(Consumer<EmbedderConfig> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    Settings settings = new Settings();
    customizer.accept(settings);
    return new DefaultEmbedder(provider, settings.options());
  }

  private final class Settings implements EmbedderConfig {

    private String model = defaultModel;
    private OptionalInt dimension = OptionalInt.empty();

    @Override
    public EmbedderConfig model(String model) {
      this.model = Objects.requireNonNull(model, "model must not be null");
      return this;
    }

    @Override
    public EmbedderConfig dimension(int dimension) {
      this.dimension = OptionalInt.of(dimension);
      return this;
    }

    private EmbeddingOptions options() {
      return new EmbeddingOptions(
          Objects.requireNonNull(
              model, "an embedder needs a model: model(...), or a factory default"),
          dimension);
    }
  }
}
