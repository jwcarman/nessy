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
 * What {@link GeminiEmbeddingProvider#of(Customizer)} hands a customizer: a CONFIG, not a builder
 * -- fluent setters, no public {@code build()}.
 */
public final class GeminiEmbedderConfig {

  /** Google's current embedding model: 3072 dimensions unless asked for fewer. */
  public static final String DEFAULT_MODEL = "gemini-embedding-001";

  private static final String GEMINI_API_KEY_ENV_VAR = "GEMINI_API_KEY";
  private static final String GOOGLE_API_KEY_ENV_VAR = "GOOGLE_API_KEY";

  /** The prefix this embedder's properties are named under. */
  private static final String PROPERTY_PREFIX = "gemini.";

  private static final Logger log = LoggerFactory.getLogger(GeminiEmbedderConfig.class);

  private String apiKey;
  private String baseUrl;
  private String model = DEFAULT_MODEL;
  private OptionalInt dimension = OptionalInt.empty();
  private String taskType;
  private Client client;
  private boolean useEnv;

  private final Map<String, String> properties = new LinkedHashMap<>();

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

  /**
   * A vendor property for this embedder's requests, named under its prefix. No embedder property is
   * supported yet: one under this adapter's prefix is ignored, with a warning naming it, when the
   * provider is built. Repeatable; the last value given for a name wins. A name under another
   * prefix fails at build.
   */
  public GeminiEmbedderConfig property(String name, String value) {
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
  public <T> GeminiEmbedderConfig property(VendorProperty<T> property, T value) {
    return property(property.name(), property.format(value));
  }

  /** {@link #property(String, String)} for each entry. */
  public GeminiEmbedderConfig properties(Map<String, String> properties) {
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
          "NESSY EMBEDDING: property '{}{}' is not supported by gemini and is ignored; supported: []",
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

  GeminiEmbeddingProvider build() {
    requireOwnProperties();
    warnUnsupported(properties);
    return new GeminiEmbeddingProvider(resolveClient(), taskType, model, dimension);
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
