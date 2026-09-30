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
package org.jwcarman.nessy.embedding.voyage;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.vendor.VendorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link VoyageEmbeddingProvider#of(Customizer)} hands a customizer: a CONFIG, not a builder
 * -- fluent setters, no public {@code build()}.
 */
public final class VoyageEmbedderConfig {

  /** Voyage's current general model: 1024 dimensions unless asked for 256, 512 or 2048. */
  public static final String DEFAULT_MODEL = "voyage-3.5";

  public static final String DEFAULT_BASE_URL = "https://api.voyageai.com/v1";

  private static final String API_KEY_ENV_VAR = "VOYAGE_API_KEY";

  /** The prefix this embedder's properties are named under. */
  private static final String PROPERTY_PREFIX = "voyage.";

  private static final Logger log = LoggerFactory.getLogger(VoyageEmbedderConfig.class);

  private String apiKey;
  private String baseUrl = DEFAULT_BASE_URL;
  private String model = DEFAULT_MODEL;
  private OptionalInt dimension = OptionalInt.empty();
  private String inputType;
  private Duration timeout = Duration.ofSeconds(30);
  private HttpClient http;
  private JsonMapper mapper = JsonMapper.builder().build();
  private boolean useEnv;

  private final Map<String, String> properties = new LinkedHashMap<>();

  VoyageEmbedderConfig() {}

  public VoyageEmbedderConfig apiKey(String apiKey) {
    this.apiKey = apiKey;
    return this;
  }

  /** {@value #API_KEY_ENV_VAR}; an explicit key wins. */
  public VoyageEmbedderConfig fromEnv() {
    this.useEnv = true;
    return this;
  }

  /** The API root, {@value #DEFAULT_BASE_URL} by default; {@code /embeddings} is appended. */
  public VoyageEmbedderConfig baseUrl(String baseUrl) {
    this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
    return this;
  }

  public VoyageEmbedderConfig model(String model) {
    Objects.requireNonNull(model, "model must not be null");
    if (model.isBlank()) {
      throw new IllegalArgumentException("model must not be blank");
    }
    this.model = model;
    return this;
  }

  /** Ask for this many coordinates; the 3.5 models honour 256, 512, 1024 and 2048. */
  public VoyageEmbedderConfig dimension(int dimension) {
    if (dimension <= 0) {
      throw new IllegalArgumentException("dimension must be positive: " + dimension);
    }
    this.dimension = OptionalInt.of(dimension);
    return this;
  }

  /** Voyage's hint: {@code document} or {@code query}. None by default. */
  public VoyageEmbedderConfig inputType(String inputType) {
    this.inputType = inputType;
    return this;
  }

  public VoyageEmbedderConfig timeout(Duration timeout) {
    this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    return this;
  }

  /** The JDK client to send with; one is built if none is given. Never closed by the embedder. */
  public VoyageEmbedderConfig httpClient(HttpClient http) {
    this.http = Objects.requireNonNull(http, "http must not be null");
    return this;
  }

  public VoyageEmbedderConfig mapper(JsonMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    return this;
  }

  /**
   * A vendor property for this embedder's requests, named under its prefix. No embedder property is
   * supported yet: one under this adapter's prefix is ignored, with a warning naming it, when the
   * provider is built. Repeatable; the last value given for a name wins. A name under another
   * prefix fails at build.
   */
  public VoyageEmbedderConfig property(String name, String value) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    properties.put(name, VendorProperties.requireString(name, value));
    return this;
  }

  /** {@link #property(String, String)} for each entry. */
  public VoyageEmbedderConfig properties(Map<String, String> properties) {
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
          "NESSY EMBEDDING: property '{}{}' is not supported by voyage and is ignored; supported: []",
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

  VoyageEmbeddingProvider build() {
    requireOwnProperties();
    warnUnsupported(properties);
    String key = apiKey;
    if (useEnv && key == null) {
      key = System.getenv(API_KEY_ENV_VAR);
      if (key == null) {
        throw new IllegalStateException(
            API_KEY_ENV_VAR + " environment variable is not set; call apiKey(...) instead");
      }
    }
    if (key == null || key.isBlank()) {
      throw new IllegalStateException("an API key is required: call apiKey(...) or fromEnv()");
    }
    String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    return new VoyageEmbeddingProvider(
        http != null ? http : HttpClient.newBuilder().connectTimeout(timeout).build(),
        URI.create(root + "/embeddings"),
        key,
        this);
  }

  String model() {
    return model;
  }

  OptionalInt dimension() {
    return dimension;
  }

  String inputType() {
    return inputType;
  }

  Duration timeout() {
    return timeout;
  }

  JsonMapper mapper() {
    return mapper;
  }
}
