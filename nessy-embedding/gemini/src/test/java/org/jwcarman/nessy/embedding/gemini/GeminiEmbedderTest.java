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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.genai.Client;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.Embedding;
import org.jwcarman.nessy.engine.embedding.DefaultEmbedderFactory;

@DisplayName("The Gemini embedder")
class GeminiEmbedderTest {

  /** An embedder over a provider: the connection is the provider's, the model the caller's. */
  private static Embedder embedderOver(GeminiEmbeddingProvider provider, String model) {
    return new DefaultEmbedderFactory(provider, model).create(c -> {});
  }

  private record Sent(String model, List<String> texts, EmbedContentConfig config) {}

  private static EmbedContentResponse reply(float[]... vectors) {
    List<ContentEmbedding> embeddings =
        java.util.Arrays.stream(vectors)
            .map(
                vector -> {
                  List<Float> values = new java.util.ArrayList<>();
                  for (float v : vector) {
                    values.add(v);
                  }
                  return ContentEmbedding.builder().values(values).build();
                })
            .toList();
    return EmbedContentResponse.builder().embeddings(embeddings).build();
  }

  private static GeminiEmbeddingClient scripted(
      EmbedContentResponse response, AtomicReference<Sent> sent, AtomicBoolean closed) {
    return new GeminiEmbeddingClient() {
      @Override
      public EmbedContentResponse embed(
          String model, List<String> texts, EmbedContentConfig config) {
        sent.set(new Sent(model, texts, config));
        return response;
      }

      @Override
      public void close() {
        closed.set(true);
      }
    };
  }

  @Nested
  class EmbeddingTexts {

    @Test
    void a_batch_is_one_request_and_the_dimension_is_learned_from_the_reply() {
      AtomicReference<Sent> sent = new AtomicReference<>();
      Embedder embedder =
          embedderOver(
              new GeminiEmbeddingProvider(
                  scripted(
                      reply(new float[] {1, 0}, new float[] {0, 1}), sent, new AtomicBoolean()),
                  null),
              GeminiEmbedderConfig.DEFAULT_MODEL);

      assertThat(embedder.dimension()).isZero();
      List<Embedding> embeddings = embedder.embedDocuments(List.of("a", "b"));

      assertThat(embeddings)
          .extracting(Embedding::vector)
          .containsExactly(new float[] {1, 0}, new float[] {0, 1});
      assertThat(embedder.dimension()).isEqualTo(2);
      assertThat(sent.get().model()).isEqualTo("gemini-embedding-001");
      assertThat(sent.get().texts()).containsExactly("a", "b");
      assertThat(sent.get().config().outputDimensionality()).isEmpty();
      assertThat(embedder.embedDocuments(List.of())).isEmpty();
    }

    @Test
    void a_dimension_and_a_task_type_asked_for_are_sent() {
      AtomicReference<Sent> sent = new AtomicReference<>();
      Embedder embedder =
          new DefaultEmbedderFactory(
                  new GeminiEmbeddingProvider(
                      scripted(reply(new float[] {1}), sent, new AtomicBoolean()),
                      "RETRIEVAL_QUERY"),
                  "m")
              .create(c -> c.dimension(256));

      assertThat(embedder.dimension()).isEqualTo(256);
      embedder.embedDocument("x");

      assertThat(sent.get().config().outputDimensionality()).contains(256);
      assertThat(sent.get().config().taskType()).contains("RETRIEVAL_QUERY");
    }

    @Test
    void a_reply_short_of_an_embedding_is_refused() {
      Embedder embedder =
          embedderOver(
              new GeminiEmbeddingProvider(
                  scripted(reply(new float[] {1}), new AtomicReference<>(), new AtomicBoolean()),
                  null),
              GeminiEmbedderConfig.DEFAULT_MODEL);
      List<String> two = List.of("a", "b");

      assertThatThrownBy(() -> embedder.embedDocuments(two))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  class Configuration {

    @Test
    void closing_closes_the_client_it_was_given_and_not_one_the_application_handed_in() {
      AtomicBoolean closed = new AtomicBoolean();
      new GeminiEmbeddingProvider(scripted(reply(), new AtomicReference<>(), closed), null).close();
      assertThat(closed).isTrue();

      Client theirs = Client.builder().apiKey("theirs").build();
      assertThatCode(() -> GeminiEmbeddingProvider.create(c -> c.client(theirs)).close())
          .doesNotThrowAnyException();
      assertThat(theirs.models).isNotNull();
    }

    @Test
    void a_key_a_base_url_a_model_and_a_dimension_build_an_embedder() {
      assertThatCode(
              () ->
                  GeminiEmbeddingProvider.create(
                          c ->
                              c.apiKey("k")
                                  .baseUrl("http://127.0.0.1:1")
                                  .model("gemini-embedding-001")
                                  .dimension(768)
                                  .taskType("RETRIEVAL_DOCUMENT"))
                      .close())
          .doesNotThrowAnyException();
    }

    @Test
    void what_is_refused_at_configuration() {
      assertThatThrownBy(() -> GeminiEmbeddingProvider.create(c -> {}))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> GeminiEmbeddingProvider.create(c -> c.model(" ")))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> GeminiEmbeddingProvider.create(c -> c.dimension(0)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void from_env_needs_a_key_unless_one_is_given_explicitly() {
      assumeTrue(
          System.getenv("GEMINI_API_KEY") == null && System.getenv("GOOGLE_API_KEY") == null,
          "a key is set in this environment");
      assertThatThrownBy(GeminiEmbeddingProvider::fromEnv)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("GEMINI_API_KEY");
      assertThatCode(() -> GeminiEmbeddingProvider.create(c -> c.fromEnv().apiKey("k")).close())
          .doesNotThrowAnyException();
    }
  }
}
