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
import java.util.OptionalInt;
import org.jwcarman.nessy.api.Embedder;
import org.jwcarman.nessy.api.Embedding;

/**
 * Gemini's embedding models, through the vendor's own java-genai SDK.
 *
 * <p>One embedder is one model at one dimension, decided where it is built: a store keyed on this
 * embedder's vectors is keyed on that model, and a second model is a second embedder.
 */
public final class GeminiEmbedder implements Embedder, AutoCloseable {

  private final GeminiEmbeddingClient client;
  private final String model;
  private final OptionalInt requestedDimension;
  private final String taskType;
  private volatile int dimension;

  GeminiEmbedder(
      GeminiEmbeddingClient client, String model, OptionalInt requestedDimension, String taskType) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.model = Objects.requireNonNull(model, "model must not be null");
    this.requestedDimension = Objects.requireNonNull(requestedDimension, "dimension");
    this.taskType = taskType;
    this.dimension = requestedDimension.orElse(0);
  }

  public static GeminiEmbedder create(GeminiEmbedderCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    GeminiEmbedderConfig config = new GeminiEmbedderConfig();
    customizer.customize(config);
    return config.build();
  }

  /** {@code GEMINI_API_KEY} or {@code GOOGLE_API_KEY}, and the default model. */
  public static GeminiEmbedder fromEnv() {
    return create(GeminiEmbedderConfig::fromEnv);
  }

  @Override
  public String providerName() {
    return "gcp.gemini";
  }

  @Override
  public String model() {
    return model;
  }

  /**
   * The dimension asked for, or once the first vector has come back, the dimension the model
   * produces. Zero before either.
   */
  @Override
  public int dimension() {
    return dimension;
  }

  /** One request for the whole batch; the vendor returns them in the order given. */
  @Override
  public List<Embedding> embed(List<String> texts) {
    Objects.requireNonNull(texts, "texts must not be null");
    if (texts.isEmpty()) {
      return List.of();
    }
    EmbedContentConfig.Builder config = EmbedContentConfig.builder();
    requestedDimension.ifPresent(config::outputDimensionality);
    if (taskType != null) {
      config.taskType(taskType);
    }
    EmbedContentResponse response = client.embed(model, texts, config.build());
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
      embeddings.add(new Embedding(model, vector));
    }
    if (dimension == 0) {
      dimension = embeddings.getFirst().dimension();
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
