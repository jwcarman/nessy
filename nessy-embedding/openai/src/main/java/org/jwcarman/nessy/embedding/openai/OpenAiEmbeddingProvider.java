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
package org.jwcarman.nessy.embedding.openai;

import com.openai.client.OpenAIClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.embedding.Embedding;
import org.jwcarman.nessy.spi.embedding.EmbeddingOptions;
import org.jwcarman.nessy.spi.embedding.EmbeddingProvider;

/**
 * OpenAI's embeddings endpoint, through the vendor's own SDK, and with a base URL every service
 * that speaks the same wire: a local runtime serving {@code nomic-embed-text} is this class with a
 * different URL.
 *
 * <p>One embedder is one model at one dimension, decided where it is built: a store keyed on this
 * embedder's vectors is keyed on that model, and a second model is a second embedder.
 */
public final class OpenAiEmbeddingProvider implements EmbeddingProvider, AutoCloseable {

  private final OpenAIClient client;
  private final boolean ownsClient;

  OpenAiEmbeddingProvider(OpenAIClient client, boolean ownsClient) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.ownsClient = ownsClient;
  }

  public static OpenAiEmbeddingProvider create(OpenAiEmbedderCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    OpenAiEmbedderConfig config = new OpenAiEmbedderConfig();
    customizer.customize(config);
    return config.build();
  }

  /** {@code OPENAI_API_KEY} and the default model, {@value OpenAiEmbedderConfig#DEFAULT_MODEL}. */
  public static OpenAiEmbeddingProvider fromEnv() {
    return create(OpenAiEmbedderConfig::fromEnv);
  }

  /**
   * OpenAI, and anything that speaks its wire at another base URL: nothing more is known about it.
   */
  @Override
  public String providerName() {
    return "openai";
  }

  /**
   * One request for the whole batch; the vendor returns them indexed, and they are put in order.
   */
  @Override
  public List<Embedding> embedDocuments(List<String> texts, EmbeddingOptions options) {
    String model = options.modelName();
    Objects.requireNonNull(texts, "texts must not be null");
    if (texts.isEmpty()) {
      return List.of();
    }
    EmbeddingCreateParams.Builder params =
        EmbeddingCreateParams.builder().model(model).inputOfArrayOfStrings(texts);
    options.dimension().ifPresent(params::dimensions);
    CreateEmbeddingResponse response = client.embeddings().create(params.build());

    Embedding[] ordered = new Embedding[texts.size()];
    for (com.openai.models.embeddings.Embedding item : response.data()) {
      List<Float> values = item.embedding();
      float[] vector = new float[values.size()];
      for (int i = 0; i < vector.length; i++) {
        vector[i] = values.get(i);
      }
      ordered[(int) item.index()] = new Embedding(model, vector);
    }
    List<Embedding> embeddings = new ArrayList<>(ordered.length);
    for (Embedding embedding : ordered) {
      if (embedding == null) {
        throw new IllegalStateException("the vendor returned fewer embeddings than texts");
      }
      embeddings.add(embedding);
    }
    return List.copyOf(embeddings);
  }

  /**
   * The same call, because OpenAI has nothing to say about what a text is for.
   *
   * <p>Its embedding models were not trained to place a question differently from a statement, so
   * being told which this is would change nothing. Written out rather than left to a default, so
   * that reading this class tells you what the vendor does.
   */
  @Override
  public Embedding embedQuery(String query, EmbeddingOptions options) {
    Objects.requireNonNull(query, "query must not be null");
    return embedDocuments(List.of(query), options).getFirst();
  }

  /**
   * Closes the client this embedder BUILT; one handed in through the config is never closed here.
   */
  @Override
  public void close() {
    if (ownsClient) {
      client.close();
    }
  }
}
