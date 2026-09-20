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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.embedding.Embedding;
import org.jwcarman.nessy.spi.embedding.EmbeddingOptions;
import org.jwcarman.nessy.spi.embedding.EmbeddingProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Voyage AI's embeddings, over plain HTTP: the embedding partner Anthropic points to, since
 * Anthropic has no embeddings of its own. No SDK, so the client is the JDK's.
 *
 * <p>One embedder is one model at one dimension, decided where it is built.
 */
public final class VoyageEmbeddingProvider implements EmbeddingProvider, AutoCloseable {

  private static final int BATCH = 128;

  private final HttpClient http;
  private final URI endpoint;
  private final String apiKey;
  private final String inputType;
  private final Duration timeout;
  private final JsonMapper mapper;

  /** The client, endpoint and key are what the config resolved; the rest is read as configured. */
  VoyageEmbeddingProvider(
      HttpClient http, URI endpoint, String apiKey, VoyageEmbedderConfig config) {
    this.http = Objects.requireNonNull(http, "http must not be null");
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
    Objects.requireNonNull(config, "config must not be null");
    this.inputType = config.inputType();
    this.timeout = Objects.requireNonNull(config.timeout(), "timeout must not be null");
    this.mapper = Objects.requireNonNull(config.mapper(), "mapper must not be null");
  }

  public static VoyageEmbeddingProvider create(VoyageEmbedderCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    VoyageEmbedderConfig config = new VoyageEmbedderConfig();
    customizer.customize(config);
    return config.build();
  }

  /** {@code VOYAGE_API_KEY} and the default model. */
  public static VoyageEmbeddingProvider fromEnv() {
    return create(VoyageEmbedderConfig::fromEnv);
  }

  /** Semconv names no value for Voyage AI, so this one is ours. */
  @Override
  public String providerName() {
    return "voyage";
  }

  /** Batches of up to {@value #BATCH}, each one request; the reply is indexed and put in order. */
  @Override
  public List<Embedding> embedDocuments(List<String> texts, EmbeddingOptions options) {
    return embed(texts, roleOr("document"), options);
  }

  /** A question, told to Voyage as one. Retrieval is asymmetric and this is the asking half. */
  @Override
  public Embedding embedQuery(String query, EmbeddingOptions options) {
    Objects.requireNonNull(query, "query must not be null");
    return embed(List.of(query), roleOr("query"), options).getFirst();
  }

  /** An explicit input type is an override: somebody who named one meant it. */
  private String roleOr(String role) {
    return inputType == null ? role : inputType;
  }

  private List<Embedding> embed(List<String> texts, String role, EmbeddingOptions options) {
    Objects.requireNonNull(texts, "texts must not be null");
    if (texts.isEmpty()) {
      return List.of();
    }
    Embedding[] ordered = new Embedding[texts.size()];
    for (int from = 0; from < texts.size(); from += BATCH) {
      List<String> batch = texts.subList(from, Math.min(texts.size(), from + BATCH));
      JsonNode reply = post(batch, role, options);
      for (JsonNode item : reply.path("data")) {
        int index = item.path("index").asInt(-1);
        if (index < 0 || index >= batch.size()) {
          throw new IllegalStateException("the vendor returned an embedding for index " + index);
        }
        ordered[from + index] = vector(item.path("embedding"), options);
      }
    }
    for (Embedding embedding : ordered) {
      if (embedding == null) {
        throw new IllegalStateException("the vendor returned fewer embeddings than texts");
      }
    }
    return List.of(ordered);
  }

  private JsonNode post(List<String> batch, String role, EmbeddingOptions options) {
    ObjectNode body = mapper.createObjectNode().put("model", options.modelName());
    ArrayNode input = body.putArray("input");
    batch.forEach(input::add);
    options.dimension().ifPresent(d -> body.put("output_dimension", d));
    body.put("input_type", role);
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted asking " + endpoint, e);
    } catch (IOException e) {
      throw new IllegalStateException("could not reach " + endpoint, e);
    }
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "Voyage answered " + response.statusCode() + ": " + response.body());
    }
    return mapper.readTree(response.body());
  }

  private static Embedding vector(JsonNode values, EmbeddingOptions options) {
    if (!values.isArray() || values.isEmpty()) {
      throw new IllegalStateException("the vendor returned no embedding");
    }
    float[] vector = new float[values.size()];
    for (int i = 0; i < vector.length; i++) {
      vector[i] = (float) values.get(i).asDouble();
    }
    return new Embedding(options.modelName(), vector);
  }

  /**
   * Nothing to release: the JDK client is shared and unowned. Here so a try-with-resources reads.
   */
  @Override
  public void close() {
    // The HttpClient is not this class's to close.
  }
}
