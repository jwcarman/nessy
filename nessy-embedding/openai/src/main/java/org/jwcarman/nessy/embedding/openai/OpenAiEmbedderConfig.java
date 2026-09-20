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
import com.openai.client.okhttp.OpenAIOkHttpClient;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * What {@link OpenAiEmbeddingProvider#create(OpenAiEmbedderCustomizer)} hands a customizer: a
 * CONFIG, not a builder -- fluent setters, no public {@code build()}.
 */
public final class OpenAiEmbedderConfig {

  /** OpenAI's current small model: 1536 dimensions unless asked for fewer. */
  public static final String DEFAULT_MODEL = "text-embedding-3-small";

  private static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";

  private String apiKey;
  private String baseUrl;
  private String organization;
  private String model = DEFAULT_MODEL;
  private OptionalInt dimension = OptionalInt.empty();
  private OpenAIClient client;
  private boolean useEnv;

  OpenAiEmbedderConfig() {}

  public OpenAiEmbedderConfig apiKey(String apiKey) {
    this.apiKey = apiKey;
    return this;
  }

  /**
   * The SDK's own reading of the environment: {@value #API_KEY_ENV_VAR}, {@code OPENAI_BASE_URL},
   * {@code OPENAI_ORG_ID}. Anything set explicitly on this config wins over it.
   */
  public OpenAiEmbedderConfig fromEnv() {
    this.useEnv = true;
    return this;
  }

  /** Any endpoint that speaks OpenAI's wire; keep the {@code /v1} suffix. */
  public OpenAiEmbedderConfig baseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
    return this;
  }

  public OpenAiEmbedderConfig organization(String organization) {
    this.organization = organization;
    return this;
  }

  /** The embedding model; {@value #DEFAULT_MODEL} unless said otherwise. */
  public OpenAiEmbedderConfig model(String model) {
    Objects.requireNonNull(model, "model must not be null");
    if (model.isBlank()) {
      throw new IllegalArgumentException("model must not be blank");
    }
    this.model = model;
    return this;
  }

  /**
   * Ask the model for this many coordinates rather than its full width. The {@code
   * text-embedding-3} models honour it; older ones and most local runtimes ignore or refuse it. A
   * store's index is sized by this, so decide it once, when the store is created.
   */
  public OpenAiEmbedderConfig dimension(int dimension) {
    if (dimension <= 0) {
      throw new IllegalArgumentException("dimension must be positive: " + dimension);
    }
    this.dimension = OptionalInt.of(dimension);
    return this;
  }

  /**
   * Escape hatch: a fully preconfigured SDK client instead of {@code apiKey}/{@code baseUrl}.
   *
   * <p><b>Ownership stays with the caller.</b> The embedder closes only a client it built itself.
   */
  public OpenAiEmbedderConfig client(OpenAIClient client) {
    this.client = client;
    return this;
  }

  /** The model a factory over this connection hands out when an embedder names none. */
  String model() {
    return model;
  }

  OpenAiEmbeddingProvider build() {
    if (client != null) {
      return new OpenAiEmbeddingProvider(client, false);
    }
    if (useEnv) {
      return new OpenAiEmbeddingProvider(buildFromEnv(), true);
    }
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "an API key is required: call apiKey(...) or fromEnv(), or provide a preconfigured"
              + " client via client(...)");
    }
    OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder().apiKey(apiKey);
    if (baseUrl != null) {
      builder.baseUrl(baseUrl);
    }
    if (organization != null) {
      builder.organization(organization);
    }
    return new OpenAiEmbeddingProvider(builder.build(), true);
  }

  private OpenAIClient buildFromEnv() {
    if (apiKey == null && System.getenv(API_KEY_ENV_VAR) == null) {
      throw new IllegalStateException(
          API_KEY_ENV_VAR
              + " environment variable is not set; call apiKey(...) or client(...) instead");
    }
    try {
      OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder().fromEnv();
      if (apiKey != null) {
        builder.apiKey(apiKey);
      }
      if (baseUrl != null) {
        builder.baseUrl(baseUrl);
      }
      if (organization != null) {
        builder.organization(organization);
      }
      return builder.build();
    } catch (RuntimeException e) {
      throw new IllegalStateException("could not resolve credentials from the environment", e);
    }
  }
}
