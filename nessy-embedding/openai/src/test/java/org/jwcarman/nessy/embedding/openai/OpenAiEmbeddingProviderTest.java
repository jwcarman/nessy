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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openai.client.OpenAIClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.services.blocking.EmbeddingService;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.Embedding;
import org.jwcarman.nessy.engine.embedding.DefaultEmbedderFactory;

@DisplayName("The OpenAI embedder")
class OpenAiEmbeddingProviderTest {

  /**
   * An embedder over a provider, which is how one is made: the connection is the provider's, the
   * model is the caller's.
   */
  private static Embedder embedderOver(OpenAiEmbedderCustomizer connection) {
    return new DefaultEmbedderFactory(
            OpenAiEmbeddingProvider.create(connection), OpenAiEmbedderConfig.DEFAULT_MODEL)
        .create(c -> {});
  }

  /**
   * The SDK client is an interface with dozens of resource accessors; a JDK proxy answers the one
   * path {@code embed} takes, {@code embeddings().create(params)}, and refuses everything else.
   */
  private static OpenAIClient fakeClient(
      Function<EmbeddingCreateParams, CreateEmbeddingResponse> answer, AtomicBoolean closed) {
    EmbeddingService embeddings =
        (EmbeddingService)
            Proxy.newProxyInstance(
                EmbeddingService.class.getClassLoader(),
                new Class<?>[] {EmbeddingService.class},
                (proxy, method, args) -> {
                  if ("create".equals(method.getName())) {
                    return answer.apply((EmbeddingCreateParams) args[0]);
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    return (OpenAIClient)
        Proxy.newProxyInstance(
            OpenAIClient.class.getClassLoader(),
            new Class<?>[] {OpenAIClient.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "embeddings" -> {
                  return embeddings;
                }
                case "close" -> {
                  closed.set(true);
                  return null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
              }
            });
  }

  private static CreateEmbeddingResponse.Usage usage() {
    return CreateEmbeddingResponse.Usage.builder().promptTokens(1).totalTokens(1).build();
  }

  private static com.openai.models.embeddings.Embedding item(long index, float... values) {
    com.openai.models.embeddings.Embedding.Builder builder =
        com.openai.models.embeddings.Embedding.builder().index(index);
    List<Float> boxed = new java.util.ArrayList<>();
    for (float value : values) {
      boxed.add(value);
    }
    return builder.embedding(boxed).build();
  }

  private static CreateEmbeddingResponse reply(com.openai.models.embeddings.Embedding... items) {
    return CreateEmbeddingResponse.builder()
        .model("text-embedding-3-small")
        .usage(usage())
        .data(List.of(items))
        .build();
  }

  @Nested
  class Embedding_texts {

    @Test
    void one_text_is_one_request_and_the_dimension_is_learned_from_the_reply() {
      AtomicReference<EmbeddingCreateParams> sent = new AtomicReference<>();
      Embedder embedder =
          embedderOver(
              c ->
                  c.client(
                      fakeClient(
                          params -> {
                            sent.set(params);
                            return reply(item(0, 0.1f, 0.2f, 0.3f));
                          },
                          new AtomicBoolean())));

      assertThat(embedder.dimension()).isZero();
      Embedding embedding = embedder.embedDocument("a lake monster");

      assertThat(embedding.model()).isEqualTo("text-embedding-3-small");
      assertThat(embedding.vector()).containsExactly(0.1f, 0.2f, 0.3f);
      assertThat(embedder.dimension()).isEqualTo(3);
      assertThat(sent.get().model()).hasToString("text-embedding-3-small");
      assertThat(sent.get().input().asArrayOfStrings()).containsExactly("a lake monster");
      assertThat(sent.get().dimensions()).isEmpty();
    }

    @Test
    void a_batch_is_one_request_and_comes_back_in_the_order_asked_whatever_order_it_arrived() {
      Embedder embedder =
          embedderOver(
              c ->
                  c.client(
                      fakeClient(params -> reply(item(1, 2f), item(0, 1f)), new AtomicBoolean())));

      List<Embedding> embeddings = embedder.embedDocuments(List.of("first", "second"));

      assertThat(embeddings).extracting(e -> e.vector()[0]).containsExactly(1f, 2f);
      assertThat(embedder.embedDocuments(List.of())).isEmpty();
    }

    @Test
    void a_model_and_a_dimension_asked_for_are_sent_and_reported() {
      AtomicReference<EmbeddingCreateParams> sent = new AtomicReference<>();
      Embedder embedder =
          new DefaultEmbedderFactory(
                  OpenAiEmbeddingProvider.create(
                      c ->
                          c.client(
                              fakeClient(
                                  params -> {
                                    sent.set(params);
                                    return reply(item(0, 1f));
                                  },
                                  new AtomicBoolean()))))
              .create(c -> c.model("text-embedding-3-large").dimension(256));

      assertThat(embedder.model()).isEqualTo("text-embedding-3-large");
      assertThat(embedder.dimension()).isEqualTo(256);
      embedder.embedDocument("x");
      assertThat(sent.get().dimensions()).contains(256L);
    }

    @Test
    void a_reply_short_of_an_embedding_is_refused_rather_than_padded() {
      Embedder embedder =
          embedderOver(
              c -> c.client(fakeClient(params -> reply(item(0, 1f)), new AtomicBoolean())));
      List<String> two = List.of("a", "b");

      assertThatThrownBy(() -> embedder.embedDocuments(two))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  class Configuration {

    @Test
    void a_handed_in_client_is_never_closed_and_a_built_one_is() {
      AtomicBoolean closed = new AtomicBoolean();
      OpenAiEmbeddingProvider.create(c -> c.client(fakeClient(p -> reply(), closed))).close();
      assertThat(closed).isFalse();

      assertThatCode(
              () ->
                  OpenAiEmbeddingProvider.create(
                          c -> c.apiKey("k").baseUrl("http://127.0.0.1:1/v1").organization("org"))
                      .close())
          .doesNotThrowAnyException();
    }

    @Test
    void what_is_refused_at_configuration() {
      assertThatThrownBy(() -> OpenAiEmbeddingProvider.create(c -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("apiKey");
      assertThatThrownBy(() -> OpenAiEmbeddingProvider.create(c -> c.model(" ")))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> OpenAiEmbeddingProvider.create(c -> c.dimension(0)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> OpenAiEmbeddingProvider.create(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void from_env_needs_a_key_unless_one_is_given_explicitly() {
      assumeTrue(System.getenv("OPENAI_API_KEY") == null, "a key is set in this environment");
      assertThatThrownBy(OpenAiEmbeddingProvider::fromEnv)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("OPENAI_API_KEY");
      assertThatCode(() -> OpenAiEmbeddingProvider.create(c -> c.fromEnv().apiKey("k")).close())
          .doesNotThrowAnyException();
    }
  }
}
