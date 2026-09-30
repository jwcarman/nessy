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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.Embedding;
import org.jwcarman.nessy.embedding.EmbeddingOptions;
import org.jwcarman.nessy.engine.embedding.DefaultEmbedderFactory;

/**
 * Against Amazon Bedrock. Tagged {@code live}; {@code AWS_ACCESS_KEY_ID} opts it in, as the
 * inference live test's gate does, and {@code NESSY_EMBEDDING_MODEL} may name another model. The
 * query-versus-document case needs a model with an input type, so it runs only when {@code
 * NESSY_LIVE_BEDROCK_COHERE_MODEL} names a Cohere embed model (Titan, the default, has none).
 */
@Tag("live")
@DisplayName("The Bedrock embedder, live")
class BedrockEmbedderLiveTest {

  private static final ProviderId BEDROCK = ProviderId.of("bedrock");

  /** An embedder over a provider: the connection is the provider's, the model the caller's. */
  private static Embedder embedderOver(BedrockEmbeddingProvider provider, String model) {
    return DefaultEmbedderFactory.of(
            f -> f.provider(BEDROCK, provider).embedding(BEDROCK, EmbeddingOptions.of(model)))
        .create(c -> {});
  }

  @Test
  void near_texts_are_nearer_than_far_ones() {
    assumeTrue(
        System.getenv("AWS_BEARER_TOKEN_BEDROCK") != null
            || System.getenv("AWS_ACCESS_KEY_ID") != null,
        "neither AWS_BEARER_TOKEN_BEDROCK nor AWS_ACCESS_KEY_ID is set");
    String model =
        System.getenv().getOrDefault("NESSY_EMBEDDING_MODEL", BedrockEmbedderConfig.DEFAULT_MODEL);
    try (BedrockEmbeddingProvider provider =
        BedrockEmbeddingProvider.of(BedrockEmbedderConfig::fromEnv)) {
      Embedder embedder = embedderOver(provider, model);
      List<Embedding> embeddings =
          embedder.embedDocuments(
              List.of(
                  "The Loch Ness monster is a creature said to live in a Scottish lake.",
                  "Nessie is a legendary animal reported in a loch in the Highlands.",
                  "The quarterly invoice for the office printer toner is overdue."));

      double near = embeddings.get(0).similarity(embeddings.get(1));
      double far = embeddings.get(0).similarity(embeddings.get(2));
      System.out.printf(
          "%s: %d dimensions; nessie~nessie %.3f, nessie~toner %.3f%n",
          embedder.model(), embedder.dimension(), near, far);
      assertThat(near).isGreaterThan(far);
    }
  }

  @Test
  void a_query_and_a_document_are_the_model_s_width_and_carry_the_model_asked_for() {
    assumeTrue(
        System.getenv("AWS_BEARER_TOKEN_BEDROCK") != null
            || System.getenv("AWS_ACCESS_KEY_ID") != null,
        "neither AWS_BEARER_TOKEN_BEDROCK nor AWS_ACCESS_KEY_ID is set");
    String model =
        System.getenv().getOrDefault("NESSY_EMBEDDING_MODEL", BedrockEmbedderConfig.DEFAULT_MODEL);
    try (BedrockEmbeddingProvider provider =
        BedrockEmbeddingProvider.of(BedrockEmbedderConfig::fromEnv)) {
      Embedder embedder = embedderOver(provider, model);

      Embedding document =
          embedder.embedDocument("The Loch Ness monster is said to live in a Scottish lake.");
      Embedding query = embedder.embedQuery("Where does Nessie live?");

      assertThat(document.dimension()).isPositive().isEqualTo(embedder.dimension());
      assertThat(query.dimension()).isEqualTo(document.dimension());
      assertThat(document.model()).isEqualTo(model);
      assertThat(query.model()).isEqualTo(model);
    }
  }

  @Test
  void a_query_is_not_the_document_s_vector_and_lands_nearer_its_answer() {
    assumeTrue(
        System.getenv("AWS_BEARER_TOKEN_BEDROCK") != null
            || System.getenv("AWS_ACCESS_KEY_ID") != null,
        "neither AWS_BEARER_TOKEN_BEDROCK nor AWS_ACCESS_KEY_ID is set");
    String model = System.getenv("NESSY_LIVE_BEDROCK_COHERE_MODEL");
    assumeTrue(
        model != null, "NESSY_LIVE_BEDROCK_COHERE_MODEL is not set: Titan has no input type");
    try (BedrockEmbeddingProvider provider =
        BedrockEmbeddingProvider.of(BedrockEmbedderConfig::fromEnv)) {
      Embedder embedder = embedderOver(provider, model);
      String text = "Where does the Loch Ness monster live?";

      Embedding asQuery = embedder.embedQuery(text);
      Embedding asDocument = embedder.embedDocuments(List.of(text)).get(0);
      List<Embedding> answers =
          embedder.embedDocuments(
              List.of(
                  "The Loch Ness monster is said to live in a Scottish lake.",
                  "The quarterly invoice for the office printer toner is overdue."));

      double sameText = asQuery.similarity(asDocument);
      double toAnswer = asQuery.similarity(answers.get(0));
      double toUnrelated = asQuery.similarity(answers.get(1));
      System.out.printf(
          "%s: query~document(same text) %.4f, query~answer %.3f, query~unrelated %.3f%n",
          embedder.model(), sameText, toAnswer, toUnrelated);
      assertThat(sameText).isLessThan(0.9999);
      assertThat(toAnswer).isGreaterThan(toUnrelated);
    }
  }
}
