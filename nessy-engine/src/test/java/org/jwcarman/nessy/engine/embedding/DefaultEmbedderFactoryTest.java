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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.embedding.Embedding;
import org.jwcarman.nessy.spi.embedding.EmbeddingOptions;
import org.jwcarman.nessy.spi.embedding.EmbeddingProvider;

/**
 * What a connection decides once, and what an embedder decides for itself.
 *
 * <p>A store's index is sized by a width, so every embedder writing into it has to agree on one.
 * Saying it per embedder means every caller remembering; saying it per connection means saying it
 * where the connection is built. The connection-level width existed as a setter that was never read
 * -- a caller set it and got silence -- which is why these assert on what reaches the provider
 * rather than on what the config holds.
 */
@DisplayName("Embedders over one connection")
class DefaultEmbedderFactoryTest {

  /** Remembers the options it was asked with, which is the whole of what is under test. */
  private static final class Asked implements EmbeddingProvider {
    final AtomicReference<EmbeddingOptions> options = new AtomicReference<>();

    @Override
    public List<Embedding> embedDocuments(List<String> texts, EmbeddingOptions options) {
      this.options.set(options);
      return texts.stream().map(text -> new Embedding("m", new float[] {1f})).toList();
    }

    @Override
    public Embedding embedQuery(String query, EmbeddingOptions options) {
      this.options.set(options);
      return new Embedding("m", new float[] {1f});
    }

    @Override
    public String providerName() {
      return "asked";
    }
  }

  @Test
  @DisplayName("inherit the connection's width when they ask for none")
  void inherit_the_connections_width() {
    Asked provider = new Asked();
    new DefaultEmbedderFactory(provider, "a-model", OptionalInt.of(1024))
        .create(c -> {})
        .embedDocument("anything");

    assertThat(provider.options.get().dimension()).hasValue(1024);
    assertThat(provider.options.get().modelName()).isEqualTo("a-model");
  }

  @Test
  @DisplayName("keep their own width when they ask for one")
  void keep_their_own_width() {
    Asked provider = new Asked();
    new DefaultEmbedderFactory(provider, "a-model", OptionalInt.of(1024))
        .create(c -> c.dimension(256))
        .embedDocument("anything");

    assertThat(provider.options.get().dimension()).hasValue(256);
  }

  /** A connection with no opinion leaves the width to the model, which is most of them. */
  @Test
  @DisplayName("ask for no width when neither the connection nor the embedder named one")
  void ask_for_no_width_when_nobody_named_one() {
    Asked provider = new Asked();
    new DefaultEmbedderFactory(provider, "a-model").create(c -> {}).embedDocument("anything");

    assertThat(provider.options.get().dimension()).isEmpty();
  }
}
