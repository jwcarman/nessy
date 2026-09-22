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

import java.util.Objects;
import java.util.OptionalInt;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link BedrockEmbeddingProvider#create(BedrockEmbedderCustomizer)} hands a customizer: a
 * CONFIG, not a builder -- fluent setters, no public {@code build()}. No {@code apiKey}: Bedrock is
 * reached with AWS credentials.
 */
public final class BedrockEmbedderConfig {

  /** Amazon's current embedding model: 1024 dimensions unless asked for 512 or 256. */
  public static final String DEFAULT_MODEL = "amazon.titan-embed-text-v2:0";

  /** What Cohere is told the texts are for; {@code search_query} is the other common value. */
  public static final String DEFAULT_COHERE_INPUT_TYPE = "search_document";

  private static final String AWS_REGION_ENV_VAR = "AWS_REGION";
  private static final String AWS_DEFAULT_REGION_ENV_VAR = "AWS_DEFAULT_REGION";

  private Region region;
  private AwsCredentialsProvider credentialsProvider;
  private BedrockRuntimeClient client;
  private boolean useEnv;
  private String model = DEFAULT_MODEL;
  private OptionalInt dimension = OptionalInt.empty();
  private String cohereInputType = DEFAULT_COHERE_INPUT_TYPE;
  private JsonMapper mapper = JsonMapper.builder().build();

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

  /** The model a factory over this connection hands out when an embedder names none. */
  String model() {
    return model;
  }

  BedrockEmbeddingProvider build() {
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
    if (useEnv) {
      String value = System.getenv(AWS_REGION_ENV_VAR);
      if (value == null) {
        value = System.getenv(AWS_DEFAULT_REGION_ENV_VAR);
      }
      if (value != null) {
        return Region.of(value);
      }
    }
    throw new IllegalStateException(
        AWS_REGION_ENV_VAR
            + " (or "
            + AWS_DEFAULT_REGION_ENV_VAR
            + ") environment variable is not set; call region(...) or fromEnv(), or provide a"
            + " preconfigured client via client(...)");
  }
}
