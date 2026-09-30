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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.VendorProperties;
import org.jwcarman.nessy.api.VendorProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What {@link OpenAiEmbeddingProvider#of(Customizer)} hands a customizer: a CONFIG, not a builder
 * -- fluent setters, no public {@code build()}.
 */
public final class OpenAiEmbedderConfig {

  /** OpenAI's current small model: 1536 dimensions unless asked for fewer. */
  public static final String DEFAULT_MODEL = "text-embedding-3-small";

  private static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";

  /** The prefix this embedder's properties are named under. */
  private static final String PROPERTY_PREFIX = "openai.";

  private static final Logger log = LoggerFactory.getLogger(OpenAiEmbedderConfig.class);

  private String apiKey;
  private String baseUrl;
  private String organization;
  private String model = DEFAULT_MODEL;
  private OptionalInt dimension = OptionalInt.empty();
  private OpenAIClient client;
  private boolean useEnv;

  private final Map<String, String> properties = new LinkedHashMap<>();

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

  /**
   * A vendor property for this embedder's requests, named under its prefix. No embedder property is
   * supported yet: one under this adapter's prefix is ignored, with a warning naming it, when the
   * provider is built. Repeatable; the last value given for a name wins. A name under another
   * prefix fails at build.
   */
  public OpenAiEmbedderConfig property(String name, String value) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    properties.put(name, VendorProperties.requireString(name, value));
    return this;
  }

  /**
   * {@link #property(String, String)}, with the value typed: it is stored as the text {@code
   * property} writes it as.
   */
  public <T> OpenAiEmbedderConfig property(VendorProperty<T> property, T value) {
    return property(property.name(), property.format(value));
  }

  /** {@link #property(String, String)} for each entry. */
  public OpenAiEmbedderConfig properties(Map<String, String> properties) {
    Objects.requireNonNull(properties, "properties must not be null");
    properties.forEach(this::property);
    return this;
  }

  /**
   * Says, once per name, that a property under this adapter's prefix is ignored: no embedder
   * property is supported yet. Called when a property set is first checked (a provider's build, an
   * embedder's build), never per request.
   */
  static void warnUnsupported(Map<String, String> properties) {
    for (String name : VendorProperties.under(properties, PROPERTY_PREFIX).keySet()) {
      log.warn(
          "NESSY EMBEDDING: property '{}{}' is not supported by openai and is ignored; supported: []",
          PROPERTY_PREFIX,
          name);
    }
    if (log.isDebugEnabled()) {
      List<String> others =
          properties.keySet().stream().filter(name -> !name.startsWith(PROPERTY_PREFIX)).toList();
      if (!others.isEmpty()) {
        log.debug("NESSY EMBEDDING: properties for other adapters, ignored here: {}", others);
      }
    }
  }

  /** An embedder is one adapter: a property under another prefix is a mistake. */
  private void requireOwnProperties() {
    for (String name : properties.keySet()) {
      if (!name.startsWith(PROPERTY_PREFIX)) {
        throw new IllegalArgumentException(
            "property '"
                + name
                + "' is not under '"
                + PROPERTY_PREFIX
                + "'; an embedder reads only its own prefix");
      }
    }
  }

  /** The model a factory over this connection hands out when an embedder names none. */
  String model() {
    return model;
  }

  OpenAiEmbeddingProvider build() {
    requireOwnProperties();
    warnUnsupported(properties);
    if (client != null) {
      return new OpenAiEmbeddingProvider(client, false, model, dimension);
    }
    if (useEnv) {
      return new OpenAiEmbeddingProvider(buildFromEnv(), true, model, dimension);
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
    return new OpenAiEmbeddingProvider(builder.build(), true, model, dimension);
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
