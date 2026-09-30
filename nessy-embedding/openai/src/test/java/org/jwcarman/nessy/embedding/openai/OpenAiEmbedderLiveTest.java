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
 * Against OpenAI itself, through the SDK's own reading of the environment, so a local runtime is
 * reached the way it is everywhere else here: {@code OPENAI_BASE_URL} beside {@code
 * OPENAI_API_KEY}, and {@code NESSY_LIVE_EMBEDDING_MODEL} naming what that endpoint serves. Tagged
 * {@code live}, so CI skips it; run it with {@code -Dnessy.excludedGroups=}.
 */
@Tag("live")
@DisplayName("The OpenAI embedder, live")
class OpenAiEmbedderLiveTest {

  private static final String MODEL =
      System.getenv()
          .getOrDefault("NESSY_LIVE_EMBEDDING_MODEL", OpenAiEmbedderConfig.DEFAULT_MODEL);

  private static final ProviderId OPENAI = ProviderId.of("openai");

  /** An embedder over a provider: the connection is the provider's, the model the caller's. */
  private static Embedder embedderOver(OpenAiEmbeddingProvider provider, String model) {
    return DefaultEmbedderFactory.of(
            f -> f.provider(OPENAI, provider).embedding(OPENAI, EmbeddingOptions.of(model)))
        .create(c -> {});
  }

  @Test
  void near_texts_are_nearer_than_far_ones() {
    assumeTrue(System.getenv("OPENAI_API_KEY") != null, "OPENAI_API_KEY is not set");
    try (OpenAiEmbeddingProvider provider =
        OpenAiEmbeddingProvider.of(OpenAiEmbedderConfig::fromEnv)) {
      Embedder embedder = embedderOver(provider, MODEL);

      List<Embedding> embeddings =
          embedder.embedDocuments(
              List.of(
                  "The Loch Ness monster is a creature said to live in a Scottish lake.",
                  "Nessie is a legendary animal reported in a loch in the Highlands.",
                  "The quarterly invoice for the office printer toner is overdue."));

      Embedding nessie = embeddings.get(0);
      Embedding alsoNessie = embeddings.get(1);
      Embedding toner = embeddings.get(2);

      assertThat(embedder.model()).isEqualTo(MODEL);
      assertThat(embedder.dimension().orElseThrow().value())
          .isEqualTo(nessie.dimension())
          .isPositive();
      assertThat(embeddings)
          .allSatisfy(e -> assertThat(e.dimension()).isEqualTo(nessie.dimension()));

      double near = nessie.similarity(alsoNessie);
      double far = nessie.similarity(toner);
      System.out.printf(
          "%s: %d dimensions; nessie~nessie %.3f, nessie~toner %.3f%n",
          MODEL, nessie.dimension(), near, far);
      assertThat(near).isGreaterThan(far);
      assertThat(nessie.similarity(nessie))
          .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }
  }

  /**
   * OpenAI's embeddings endpoint has no query/document distinction on the wire, so this proves only
   * that both paths yield the model's width and record the model asked for; it cannot show the two
   * vectors differ.
   */
  @Test
  void the_query_and_document_paths_return_the_same_width_and_the_model_asked_for() {
    assumeTrue(System.getenv("OPENAI_API_KEY") != null, "OPENAI_API_KEY is not set");
    try (OpenAiEmbeddingProvider provider =
        OpenAiEmbeddingProvider.of(OpenAiEmbedderConfig::fromEnv)) {
      Embedder embedder = embedderOver(provider, MODEL);

      Embedding document =
          embedder.embedDocument("The Loch Ness monster is said to live in a Scottish lake.");
      Embedding query = embedder.embedQuery("Where does Nessie live?");

      assertThat(document.dimension())
          .isPositive()
          .isEqualTo(embedder.dimension().orElseThrow().value());
      assertThat(query.dimension()).isEqualTo(document.dimension());
      assertThat(document.model()).isEqualTo(MODEL);
      assertThat(query.model()).isEqualTo(MODEL);
    }
  }
}
