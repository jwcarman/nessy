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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.embedding.Dimension;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.Embedding;
import org.jwcarman.nessy.embedding.EmbeddingOptions;
import org.jwcarman.nessy.embedding.EmbeddingProvider;

/**
 * One model over one connection.
 *
 * <p>A facade and nothing more: it holds which model to ask for and passes the two flavours
 * through. The provider does the work and knows what a document and a query mean to its vendor.
 *
 * <p>Owns nothing. The connection belongs to the provider, so an embedder going out of scope closes
 * no sockets and takes no sibling down with it.
 */
final class DefaultEmbedder implements Embedder {

  private final EmbeddingProvider provider;
  private final EmbeddingOptions options;

  /**
   * What this model's vectors are wide, once anything is known.
   *
   * <p>Requested, or learned from the first vector that comes back: some vendors only say by
   * answering. Kept here rather than in every adapter, which is where it used to be four times
   * over.
   */
  private volatile Optional<Dimension> dimension;

  DefaultEmbedder(EmbeddingProvider provider, EmbeddingOptions options) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.options = Objects.requireNonNull(options, "options must not be null");
    this.dimension = options.dimension();
  }

  @Override
  public String vendor() {
    return provider.vendor();
  }

  @Override
  public String model() {
    return options.modelName();
  }

  @Override
  public Optional<Dimension> dimension() {
    return dimension;
  }

  @Override
  public List<Embedding> embedDocuments(List<String> texts) {
    Objects.requireNonNull(texts, "texts must not be null");
    if (texts.isEmpty()) {
      return List.of();
    }
    return learn(provider.embedDocuments(texts, options));
  }

  @Override
  public Embedding embedQuery(String query) {
    Objects.requireNonNull(query, "query must not be null");
    Embedding embedding = provider.embedQuery(query, options);
    learn(List.of(embedding));
    return embedding;
  }

  /**
   * Checks every vector against the width asked for, and learns the width when none was.
   *
   * <p>A server that ignores the width it is asked for answers at its model's own, and a store that
   * sized an index from {@link #dimension()} would fill it with vectors of the wrong shape. So a
   * difference fails here, on every reply rather than the first: the check is one comparison per
   * vector, and what it prevents is an index nothing can rank.
   */
  private List<Embedding> learn(List<Embedding> embeddings) {
    Optional<Dimension> asked = options.dimension();
    if (asked.isPresent()) {
      int width = asked.get().value();
      for (Embedding embedding : embeddings) {
        if (embedding.dimension() != width) {
          throw new IllegalStateException(
              "asked for " + width + " coordinates, the model returned " + embedding.dimension());
        }
      }
    }
    if (dimension.isEmpty() && !embeddings.isEmpty()) {
      dimension = Optional.of(new Dimension(embeddings.getFirst().dimension()));
    }
    return embeddings;
  }
}
