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
import org.jwcarman.nessy.api.embedding.Embedding;
import org.jwcarman.nessy.spi.embedding.EmbeddingOptions;
import org.jwcarman.nessy.spi.embedding.EmbeddingProvider;
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
 * with an input type). The family is read off the model id of each call, because which model is
 * asked for belongs to the caller and can differ between one embedder over this and the next.
 */
public final class BedrockEmbeddingProvider implements EmbeddingProvider, AutoCloseable {

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
  private final String cohereInputType;
  private final JsonMapper mapper;

  private final String defaultModel;
  private final OptionalInt defaultDimension;

  BedrockEmbeddingProvider(
      BedrockEmbeddingClient client,
      String cohereInputType,
      JsonMapper mapper,
      String defaultModel,
      OptionalInt defaultDimension) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.cohereInputType = Objects.requireNonNull(cohereInputType, "inputType must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    this.defaultModel = defaultModel;
    this.defaultDimension = defaultDimension;
  }

  /** A provider built directly rather than from a config: no connection defaults to inherit. */
  BedrockEmbeddingProvider(
      BedrockEmbeddingClient client, String cohereInputType, JsonMapper mapper) {
    this(client, cohereInputType, mapper, null, OptionalInt.empty());
  }

  /** The model this connection hands an embedder that names none. */
  public String defaultModel() {
    return defaultModel;
  }

  /** How wide this connection's vectors are unless an embedder asks otherwise. */
  public OptionalInt defaultDimension() {
    return defaultDimension;
  }

  public static BedrockEmbeddingProvider create(BedrockEmbedderCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    BedrockEmbedderConfig config = new BedrockEmbedderConfig();
    customizer.customize(config);
    return config.build();
  }

  /** The AWS default credentials chain, the region from the environment, and the default model. */
  public static BedrockEmbeddingProvider fromEnv() {
    return create(BedrockEmbedderConfig::fromEnv);
  }

  @Override
  public String providerName() {
    return "aws.bedrock";
  }

  @Override
  public List<Embedding> embedDocuments(List<String> texts, EmbeddingOptions options) {
    Objects.requireNonNull(texts, "texts must not be null");
    if (texts.isEmpty()) {
      return List.of();
    }
    return switch (Family.of(options.modelName())) {
      case TITAN -> texts.stream().map(text -> titan(text, options)).toList();
      case COHERE -> cohere(texts, options);
    };
  }

  /**
   * The same call, because Bedrock's own embedding models say nothing about what a text is for.
   *
   * <p>Cohere on Bedrock does take an input type, and it is fixed where this connection is
   * configured rather than chosen per call: it is a deployment's decision about what this is for.
   */
  @Override
  public Embedding embedQuery(String query, EmbeddingOptions options) {
    Objects.requireNonNull(query, "query must not be null");
    return embedDocuments(List.of(query), options).getFirst();
  }

  /** Titan takes one text and answers with one vector. */
  private Embedding titan(String text, EmbeddingOptions options) {
    ObjectNode body = mapper.createObjectNode().put("inputText", text);
    options.dimension().ifPresent(d -> body.put("dimensions", d).put("normalize", true));
    JsonNode reply = invoke(body, options);
    return vector(reply.path("embedding"), options);
  }

  /** Cohere takes a batch of up to {@value #COHERE_BATCH} and answers in order. */
  private List<Embedding> cohere(List<String> texts, EmbeddingOptions options) {
    List<Embedding> all = new ArrayList<>(texts.size());
    for (int from = 0; from < texts.size(); from += COHERE_BATCH) {
      List<String> batch = texts.subList(from, Math.min(texts.size(), from + COHERE_BATCH));
      ObjectNode body = mapper.createObjectNode();
      ArrayNode input = body.putArray("texts");
      batch.forEach(input::add);
      body.put("input_type", cohereInputType).put("truncate", "END");
      JsonNode reply = invoke(body, options);
      JsonNode returned = reply.path("embeddings");
      if (returned.size() != batch.size()) {
        throw new IllegalStateException(
            "the model returned %d embeddings for %d texts"
                .formatted(returned.size(), batch.size()));
      }
      returned.forEach(node -> all.add(vector(node, options)));
    }
    return List.copyOf(all);
  }

  private JsonNode invoke(ObjectNode body, EmbeddingOptions options) {
    InvokeModelRequest request =
        InvokeModelRequest.builder()
            .modelId(options.modelName())
            .contentType(JSON)
            .accept(JSON)
            .body(SdkBytes.fromUtf8String(mapper.writeValueAsString(body)))
            .build();
    return mapper.readTree(client.invoke(request).body().asUtf8String());
  }

  private static Embedding vector(JsonNode values, EmbeddingOptions options) {
    if (!values.isArray() || values.isEmpty()) {
      throw new IllegalStateException("the model returned no embedding");
    }
    float[] vector = new float[values.size()];
    for (int i = 0; i < vector.length; i++) {
      vector[i] = (float) values.get(i).asDouble();
    }
    return new Embedding(options.modelName(), vector);
  }

  /**
   * Closes the client this embedder BUILT; one handed in through the config is never closed here.
   */
  @Override
  public void close() {
    client.close();
  }
}
