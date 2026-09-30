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
package org.jwcarman.nessy.embedding.bedrock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.vendor.VendorProperties;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link BedrockEmbeddingProvider#of(Customizer)} hands a customizer: a CONFIG, not a builder
 * -- fluent setters, no public {@code build()}. No {@code apiKey}: Bedrock is reached with AWS
 * credentials.
 */
public final class BedrockEmbedderConfig {

  /** Amazon's current embedding model: 1024 dimensions unless asked for 512 or 256. */
  public static final String DEFAULT_MODEL = "amazon.titan-embed-text-v2:0";

  /** What Cohere is told the texts are for; {@code search_query} is the other common value. */
  public static final String DEFAULT_COHERE_INPUT_TYPE = "search_document";

  private static final String AWS_REGION_ENV_VAR = "AWS_REGION";
  private static final String AWS_DEFAULT_REGION_ENV_VAR = "AWS_DEFAULT_REGION";

  /** The prefix this embedder's properties are named under. */
  private static final String PROPERTY_PREFIX = "bedrock.";

  private Region region;
  private AwsCredentialsProvider credentialsProvider;
  private BedrockRuntimeClient client;
  private boolean useEnv;
  private String model = DEFAULT_MODEL;
  private OptionalInt dimension = OptionalInt.empty();
  private String cohereInputType = DEFAULT_COHERE_INPUT_TYPE;
  private JsonMapper mapper = JsonMapper.builder().build();

  private final Map<String, String> properties = new LinkedHashMap<>();

  BedrockEmbedderConfig() {}

  public BedrockEmbedderConfig region(Region region) {
    this.region = region;
    return this;
  }

  public BedrockEmbedderConfig credentialsProvider(AwsCredentialsProvider credentialsProvider) {
    this.credentialsProvider = credentialsProvider;
    return this;
  }

  /** The default credentials chain, and the region from {@value #AWS_REGION_ENV_VAR}. */
  public BedrockEmbedderConfig fromEnv() {
    this.useEnv = true;
    return this;
  }

  /** A Titan ({@code amazon.titan-embed-…}) or Cohere ({@code cohere.embed-…}) model id. */
  public BedrockEmbedderConfig model(String model) {
    Objects.requireNonNull(model, "model must not be null");
    if (model.isBlank()) {
      throw new IllegalArgumentException("model must not be blank");
    }
    this.model = model;
    return this;
  }

  /** Titan v2 honours 256, 512 or 1024; Cohere's width is fixed and this is ignored. */
  public BedrockEmbedderConfig dimension(int dimension) {
    if (dimension <= 0) {
      throw new IllegalArgumentException("dimension must be positive: " + dimension);
    }
    this.dimension = OptionalInt.of(dimension);
    return this;
  }

  public BedrockEmbedderConfig cohereInputType(String inputType) {
    this.cohereInputType = Objects.requireNonNull(inputType, "inputType must not be null");
    return this;
  }

  /** Escape hatch: a fully preconfigured SDK client. <b>Ownership stays with the caller.</b> */
  public BedrockEmbedderConfig client(BedrockRuntimeClient client) {
    this.client = client;
    return this;
  }

  public BedrockEmbedderConfig mapper(JsonMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    return this;
  }

  /**
   * A vendor property for this embedder's requests, named under its prefix. Carried and not yet
   * read: the embedding adapters read their properties from the named-embedders item on (spec §9f).
   * Repeatable; the last value given for a name wins. A name under another prefix fails at build.
   */
  public BedrockEmbedderConfig property(String name, String value) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    properties.put(name, VendorProperties.requireString(name, value));
    return this;
  }

  /** {@link #property(String, String)} for each entry. */
  public BedrockEmbedderConfig properties(Map<String, String> properties) {
    Objects.requireNonNull(properties, "properties must not be null");
    properties.forEach(this::property);
    return this;
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

  BedrockEmbeddingProvider build() {
    requireOwnProperties();
    return new BedrockEmbeddingProvider(resolveClient(), cohereInputType, mapper, model, dimension);
  }

  private BedrockEmbeddingClient resolveClient() {
    if (client != null) {
      return BedrockEmbeddingClient.over(client, false);
    }
    BedrockRuntimeClient built =
        BedrockRuntimeClient.builder()
            .region(resolveRegion())
            .credentialsProvider(
                credentialsProvider != null
                    ? credentialsProvider
                    : DefaultCredentialsProvider.builder().build())
            .build();
    return BedrockEmbeddingClient.over(built, true);
  }

  private Region resolveRegion() {
    if (region != null) {
      return region;
    }
    // Said before reading anything, because the message below is about environment variables and
    // this caller never asked for them. It used to fall through to that message, so a caller who
    // simply forgot region(...) was told AWS_REGION was unset -- advice about a mechanism it had
    // not opted into.
    if (!useEnv) {
      throw new IllegalStateException(
          "a region is required: call region(...) or fromEnv(), or provide a preconfigured client"
              + " via client(...)");
    }
    String value = System.getenv(AWS_REGION_ENV_VAR);
    if (value == null) {
      value = System.getenv(AWS_DEFAULT_REGION_ENV_VAR);
    }
    if (value != null) {
      return Region.of(value);
    }
    throw new IllegalStateException(
        AWS_REGION_ENV_VAR
            + " (or "
            + AWS_DEFAULT_REGION_ENV_VAR
            + ") environment variable is not set; call region(...) or fromEnv(), or provide a"
            + " preconfigured client via client(...)");
  }
}
