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
package org.jwcarman.nessy.embedding.gemini;

import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.embedding.Embedding;
import org.jwcarman.nessy.spi.embedding.EmbeddingOptions;
import org.jwcarman.nessy.spi.embedding.EmbeddingProvider;

/**
 * Gemini's embedding models, through the vendor's own java-genai SDK.
 *
 * <p>One embedder is one model at one dimension, decided where it is built: a store keyed on this
 * embedder's vectors is keyed on that model, and a second model is a second embedder.
 */
public final class GeminiEmbeddingProvider implements EmbeddingProvider, AutoCloseable {

  private final GeminiEmbeddingClient client;
  private final String taskType;

  GeminiEmbeddingProvider(GeminiEmbeddingClient client, String taskType) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.taskType = taskType;
  }

  public static GeminiEmbeddingProvider create(GeminiEmbedderCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    GeminiEmbedderConfig config = new GeminiEmbedderConfig();
    customizer.customize(config);
    return config.build();
  }

  /** {@code GEMINI_API_KEY} or {@code GOOGLE_API_KEY}, and the default model. */
  public static GeminiEmbeddingProvider fromEnv() {
    return create(GeminiEmbedderConfig::fromEnv);
  }

  @Override
  public String providerName() {
    return "gcp.gemini";
  }

  /** One request for the whole batch; the vendor returns them in the order given. */
  @Override
  public List<Embedding> embedDocuments(List<String> texts, EmbeddingOptions options) {
    return embed(texts, roleOr("RETRIEVAL_DOCUMENT"), options);
  }

  /**
   * A question, told to Gemini as one.
   *
   * <p>Retrieval is asymmetric: a query and the document that answers it are placed differently on
   * purpose, and saying so is the difference between finding it and nearly finding it.
   */
  @Override
  public Embedding embedQuery(String query, EmbeddingOptions options) {
    Objects.requireNonNull(query, "query must not be null");
    return embed(List.of(query), roleOr("RETRIEVAL_QUERY"), options).getFirst();
  }

  /**
   * What a caller configured, when they configured one.
   *
   * <p>An explicit task type is an override: somebody who named one meant it, and roles are the
   * default rather than the rule.
   */
  private String roleOr(String role) {
    return taskType == null ? role : taskType;
  }

  private List<Embedding> embed(List<String> texts, String role, EmbeddingOptions options) {
    Objects.requireNonNull(texts, "texts must not be null");
    if (texts.isEmpty()) {
      return List.of();
    }
    EmbedContentConfig.Builder config = EmbedContentConfig.builder();
    options.dimension().ifPresent(config::outputDimensionality);
    config.taskType(role);
    EmbedContentResponse response = client.embed(options.modelName(), texts, config.build());
    List<ContentEmbedding> returned = response.embeddings().orElse(List.of());
    if (returned.size() != texts.size()) {
      throw new IllegalStateException(
          "the vendor returned %d embeddings for %d texts"
              .formatted(returned.size(), texts.size()));
    }
    List<Embedding> embeddings = new ArrayList<>(returned.size());
    for (ContentEmbedding item : returned) {
      List<Float> values = item.values().orElse(List.of());
      float[] vector = new float[values.size()];
      for (int i = 0; i < vector.length; i++) {
        vector[i] = values.get(i);
      }
      embeddings.add(new Embedding(options.modelName(), vector));
    }
    return List.copyOf(embeddings);
  }

  /**
   * Closes the client this embedder BUILT; one handed in through the config is never closed here.
   */
  @Override
  public void close() {
    client.close();
  }
}
