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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.embedding.EmbeddingOptions;
import org.jwcarman.nessy.embedding.EmbeddingProvider;

/**
 * Everything an embedder factory is built from, in one place a customizer can reach: the embedding
 * providers by name, the defaults a store that says nothing gets, and where every embedder reports
 * its work.
 *
 * <p>The counterpart of {@code DirectHarnessFactoryConfig}'s {@code provider} and {@code inference}
 * for embeddings: the id and the terms given together, so a default model cannot be set without
 * saying whose it is.
 */
public final class EmbedderFactoryConfig {

  private final Map<ProviderId, EmbeddingProvider> providers = new LinkedHashMap<>();
  private @Nullable ProviderId defaultProvider;
  private @Nullable EmbeddingOptions defaultOptions;
  private ObservationRegistry observations = ObservationRegistry.NOOP;

  EmbedderFactoryConfig() {}

  /**
   * One of the providers this factory's embedders may be minted over, under the name a store will
   * ask for it by. Repeatable; the same id twice fails at once.
   */
  public EmbedderFactoryConfig provider(ProviderId id, EmbeddingProvider provider) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(provider, "provider must not be null");
    if (providers.putIfAbsent(id, provider) != null) {
      throw new IllegalArgumentException(
          "embedding provider '" + id.value() + "' is already registered");
    }
    return this;
  }

  /**
   * What an embedder gets when it says nothing: which provider, which model, how wide, which vendor
   * properties. Optional; without it every store names a provider and a model itself.
   */
  public EmbedderFactoryConfig embedding(ProviderId provider, EmbeddingOptions options) {
    this.defaultProvider = Objects.requireNonNull(provider, "provider must not be null");
    this.defaultOptions = Objects.requireNonNull(options, "options must not be null");
    return this;
  }

  /**
   * Where every embedder this factory mints reports its calls. Defaults to {@link
   * ObservationRegistry#NOOP}: every embedder is wrapped either way, and a no-op registry costs a
   * check per call.
   */
  public EmbedderFactoryConfig observations(ObservationRegistry observations) {
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
    return this;
  }

  // ---- what the factory reads ------------------------------------------------------------

  Map<ProviderId, EmbeddingProvider> providers() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(providers));
  }

  @Nullable ProviderId defaultProvider() {
    return defaultProvider;
  }

  @Nullable EmbeddingOptions defaultOptions() {
    return defaultOptions;
  }

  ObservationRegistry observations() {
    return observations;
  }
}
