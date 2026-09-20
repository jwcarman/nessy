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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import org.jwcarman.nessy.api.Embedder;
import org.jwcarman.nessy.api.Embedding;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Embedding models on Amazon Bedrock, through {@code InvokeModel}.
 *
 * <p>Converse, which the inference adapter uses, has no embeddings, so this is the per-model JSON
 * body the Converse adapter deliberately avoids. Two families are spoken: Amazon Titan Text
 * Embeddings (one text per call, an optional dimension on v2) and Cohere Embed (a batch per call,
 * with an input type). The family is read off the model id when the embedder is built.
 */
public final class BedrockEmbedder implements Embedder, AutoCloseable {

  /** The request and response shapes this adapter knows. */
  enum Family {
    TITAN,
    COHERE;

    static Family of(String modelId) {
      if (modelId.startsWith("amazon.titan-embed")) {
        return TITAN;
      }
      if (modelId.startsWith("cohere.embed")) {
        return COHERE;
      }
      throw new IllegalArgumentException(
          "model "
              + modelId
              + " is neither a Titan nor a Cohere embedding model; those are the two shapes this"
              + " adapter speaks");
    }
  }

  private static final String JSON = "application/json";
  private static final int COHERE_BATCH = 96;

  private final BedrockEmbeddingClient client;
  private final String model;
  private final Family family;
  private final OptionalInt requestedDimension;
  private final String cohereInputType;
  private final JsonMapper mapper;
  private volatile int dimension;

  BedrockEmbedder(
      BedrockEmbeddingClient client,
      String model,
      OptionalInt requestedDimension,
      String cohereInputType,
      JsonMapper mapper) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.model = Objects.requireNonNull(model, "model must not be null");
    this.family = Family.of(model);
    this.requestedDimension = Objects.requireNonNull(requestedDimension, "dimension");
    this.cohereInputType = Objects.requireNonNull(cohereInputType, "inputType must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    this.dimension = requestedDimension.orElse(0);
  }

  public static BedrockEmbedder create(BedrockEmbedderCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    BedrockEmbedderConfig config = new BedrockEmbedderConfig();
    customizer.customize(config);
    return config.build();
  }

  /** The AWS default credentials chain, the region from the environment, and the default model. */
  public static BedrockEmbedder fromEnv() {
    return create(BedrockEmbedderConfig::fromEnv);
  }

  @Override
  public String providerName() {
    return "aws.bedrock";
  }

  @Override
  public String model() {
    return model;
  }

  @Override
  public int dimension() {
    return dimension;
  }

  @Override
  public List<Embedding> embed(List<String> texts) {
    Objects.requireNonNull(texts, "texts must not be null");
    if (texts.isEmpty()) {
      return List.of();
    }
    List<Embedding> embeddings =
        switch (family) {
          case TITAN -> texts.stream().map(this::titan).toList();
          case COHERE -> cohere(texts);
        };
    if (dimension == 0) {
      dimension = embeddings.getFirst().dimension();
    }
    return embeddings;
  }

  /** Titan takes one text and answers with one vector. */
  private Embedding titan(String text) {
    ObjectNode body = mapper.createObjectNode().put("inputText", text);
    requestedDimension.ifPresent(d -> body.put("dimensions", d).put("normalize", true));
    JsonNode reply = invoke(body);
    return vector(reply.path("embedding"));
  }

  /** Cohere takes a batch of up to {@value #COHERE_BATCH} and answers in order. */
  private List<Embedding> cohere(List<String> texts) {
    List<Embedding> all = new ArrayList<>(texts.size());
    for (int from = 0; from < texts.size(); from += COHERE_BATCH) {
      List<String> batch = texts.subList(from, Math.min(texts.size(), from + COHERE_BATCH));
      ObjectNode body = mapper.createObjectNode();
      ArrayNode input = body.putArray("texts");
      batch.forEach(input::add);
      body.put("input_type", cohereInputType).put("truncate", "END");
      JsonNode reply = invoke(body);
      JsonNode returned = reply.path("embeddings");
      if (returned.size() != batch.size()) {
        throw new IllegalStateException(
            "the model returned %d embeddings for %d texts"
                .formatted(returned.size(), batch.size()));
      }
      returned.forEach(node -> all.add(vector(node)));
    }
    return List.copyOf(all);
  }

  private JsonNode invoke(ObjectNode body) {
    InvokeModelRequest request =
        InvokeModelRequest.builder()
            .modelId(model)
            .contentType(JSON)
            .accept(JSON)
            .body(SdkBytes.fromUtf8String(mapper.writeValueAsString(body)))
            .build();
    return mapper.readTree(client.invoke(request).body().asUtf8String());
  }

  private Embedding vector(JsonNode values) {
    if (!values.isArray() || values.isEmpty()) {
      throw new IllegalStateException("the model returned no embedding");
    }
    float[] vector = new float[values.size()];
    for (int i = 0; i < vector.length; i++) {
      vector[i] = (float) values.get(i).asDouble();
    }
    return new Embedding(model, vector);
  }

  /**
   * Closes the client this embedder BUILT; one handed in through the config is never closed here.
   */
  @Override
  public void close() {
    client.close();
  }
}
