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

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * What {@link GeminiEmbedder#create(GeminiEmbedderCustomizer)} hands a customizer: a CONFIG, not a
 * builder -- fluent setters, no public {@code build()}.
 */
public final class GeminiEmbedderConfig {

  /** Google's current embedding model: 3072 dimensions unless asked for fewer. */
  public static final String DEFAULT_MODEL = "gemini-embedding-001";

  private static final String GEMINI_API_KEY_ENV_VAR = "GEMINI_API_KEY";
  private static final String GOOGLE_API_KEY_ENV_VAR = "GOOGLE_API_KEY";

  private String apiKey;
  private String baseUrl;
  private String model = DEFAULT_MODEL;
  private OptionalInt dimension = OptionalInt.empty();
  private String taskType;
  private Client client;
  private boolean useEnv;

  GeminiEmbedderConfig() {}

  public GeminiEmbedderConfig apiKey(String apiKey) {
    this.apiKey = apiKey;
    return this;
  }

  /**
   * {@value #GEMINI_API_KEY_ENV_VAR} then {@value #GOOGLE_API_KEY_ENV_VAR}; an explicit key wins.
   */
  public GeminiEmbedderConfig fromEnv() {
    this.useEnv = true;
    return this;
  }

  public GeminiEmbedderConfig baseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
    return this;
  }

  /** The embedding model; {@value #DEFAULT_MODEL} unless said otherwise. */
  public GeminiEmbedderConfig model(String model) {
    Objects.requireNonNull(model, "model must not be null");
    if (model.isBlank()) {
      throw new IllegalArgumentException("model must not be blank");
    }
    this.model = model;
    return this;
  }

  /** Ask for this many coordinates rather than the model's full width; decide it once per store. */
  public GeminiEmbedderConfig dimension(int dimension) {
    if (dimension <= 0) {
      throw new IllegalArgumentException("dimension must be positive: " + dimension);
    }
    this.dimension = OptionalInt.of(dimension);
    return this;
  }

  /**
   * Gemini's hint about what the vectors are for: {@code RETRIEVAL_DOCUMENT}, {@code
   * RETRIEVAL_QUERY}, {@code SEMANTIC_SIMILARITY} and the rest of Google's list. None by default.
   */
  public GeminiEmbedderConfig taskType(String taskType) {
    this.taskType = taskType;
    return this;
  }

  /** Escape hatch: a fully preconfigured SDK client. <b>Ownership stays with the caller.</b> */
  public GeminiEmbedderConfig client(Client client) {
    this.client = client;
    return this;
  }

  GeminiEmbedder build() {
    return new GeminiEmbedder(resolveClient(), model, dimension, taskType);
  }

  private GeminiEmbeddingClient resolveClient() {
    if (client != null) {
      return GeminiEmbeddingClient.over(client, false);
    }
    String key = apiKey;
    if (useEnv && key == null) {
      key = System.getenv(GEMINI_API_KEY_ENV_VAR);
      if (key == null) {
        key = System.getenv(GOOGLE_API_KEY_ENV_VAR);
      }
      if (key == null) {
        throw new IllegalStateException(
            GEMINI_API_KEY_ENV_VAR
                + " (or "
                + GOOGLE_API_KEY_ENV_VAR
                + ") environment variable is not set; call apiKey(...) or client(...) instead");
      }
    }
    if (key == null || key.isBlank()) {
      throw new IllegalStateException(
          "an API key is required: call apiKey(...) or fromEnv(), or provide a preconfigured"
              + " client via client(...)");
    }
    Client.Builder builder = Client.builder().apiKey(key);
    if (baseUrl != null) {
      builder.httpOptions(HttpOptions.builder().baseUrl(baseUrl).build());
    }
    return GeminiEmbeddingClient.over(builder.build(), true);
  }
}
