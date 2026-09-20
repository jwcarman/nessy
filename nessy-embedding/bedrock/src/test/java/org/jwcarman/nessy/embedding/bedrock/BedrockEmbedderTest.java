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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.Embedding;
import org.jwcarman.nessy.engine.embedding.DefaultEmbedderFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("The Bedrock embedder")
class BedrockEmbedderTest {

  /** An embedder over a provider: the connection is the provider's, the model the caller's. */
  private static Embedder embedderOver(BedrockEmbeddingProvider provider, String model) {
    return new DefaultEmbedderFactory(provider, model).create(c -> {});
  }

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** A client that answers each request body with a reply body, and remembers what it was sent. */
  private static final class Scripted implements BedrockEmbeddingClient {
    final List<JsonNode> sent = new ArrayList<>();
    final List<String> modelIds = new ArrayList<>();
    final AtomicBoolean closed = new AtomicBoolean();
    private final Function<JsonNode, String> answer;

    Scripted(Function<JsonNode, String> answer) {
      this.answer = answer;
    }

    @Override
    public InvokeModelResponse invoke(InvokeModelRequest request) {
      JsonNode body = MAPPER.readTree(request.body().asUtf8String());
      sent.add(body);
      modelIds.add(request.modelId());
      assertThat(request.contentType()).isEqualTo("application/json");
      return InvokeModelResponse.builder()
          .body(SdkBytes.fromUtf8String(answer.apply(body)))
          .build();
    }

    @Override
    public void close() {
      closed.set(true);
    }
  }

  /** An embedder over a provider: the connection is the provider's, the model the caller's. */
  private static Embedder embedder(Scripted client, String model, OptionalInt dimension) {
    return new DefaultEmbedderFactory(
            new BedrockEmbeddingProvider(client, "search_document", MAPPER), model)
        .create(c -> dimension.ifPresent(c::dimension));
  }

  @Nested
  class Titan {

    @Test
    void one_text_per_call_and_the_dimension_is_learned() {
      Scripted client =
          new Scripted(body -> "{\"embedding\":[0.5,0.25],\"inputTextTokenCount\":3}");
      Embedder embedder = embedder(client, "amazon.titan-embed-text-v2:0", OptionalInt.empty());

      List<Embedding> embeddings = embedder.embedDocuments(List.of("a", "b"));

      assertThat(embeddings).hasSize(2);
      assertThat(embeddings.getFirst().vector()).containsExactly(0.5f, 0.25f);
      assertThat(embedder.dimension()).isEqualTo(2);
      assertThat(client.sent).hasSize(2);
      assertThat(client.sent.getFirst().path("inputText").asString()).isEqualTo("a");
      assertThat(client.sent.getFirst().has("dimensions")).isFalse();
      assertThat(client.modelIds).containsOnly("amazon.titan-embed-text-v2:0");
    }

    @Test
    void a_dimension_asked_for_is_sent_with_normalisation() {
      Scripted client = new Scripted(body -> "{\"embedding\":[1]}");
      Embedder embedder = embedder(client, "amazon.titan-embed-text-v2:0", OptionalInt.of(256));

      embedder.embedDocument("x");

      assertThat(client.sent.getFirst().path("dimensions").asInt()).isEqualTo(256);
      assertThat(client.sent.getFirst().path("normalize").asBoolean()).isTrue();
      assertThat(embedder.dimension()).isEqualTo(256);
    }

    @Test
    void a_reply_without_an_embedding_is_refused() {
      Embedder embedder =
          embedder(
              new Scripted(body -> "{\"message\":\"nope\"}"),
              "amazon.titan-embed-text-v1",
              OptionalInt.empty());

      assertThatThrownBy(() -> embedder.embedDocument("x"))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  class Cohere {

    @Test
    void a_batch_per_call_with_the_input_type() {
      Scripted client = new Scripted(body -> "{\"embeddings\":[[1,0],[0,1]]}");
      Embedder embedder = embedder(client, "cohere.embed-english-v3", OptionalInt.empty());

      List<Embedding> embeddings = embedder.embedDocuments(List.of("a", "b"));

      assertThat(embeddings)
          .extracting(Embedding::vector)
          .containsExactly(new float[] {1, 0}, new float[] {0, 1});
      assertThat(client.sent).hasSize(1);
      assertThat(client.sent.getFirst().path("texts")).hasSize(2);
      assertThat(client.sent.getFirst().path("input_type").asString()).isEqualTo("search_document");
      assertThat(embedder.embedDocuments(List.of())).isEmpty();
    }

    @Test
    void more_than_ninety_six_texts_go_in_several_calls() {
      Scripted client =
          new Scripted(
              body -> {
                StringBuilder reply = new StringBuilder("{\"embeddings\":[");
                for (int i = 0; i < body.path("texts").size(); i++) {
                  reply.append(i == 0 ? "" : ",").append("[1]");
                }
                return reply.append("]}").toString();
              });
      Embedder embedder = embedder(client, "cohere.embed-multilingual-v3", OptionalInt.empty());
      List<String> texts = java.util.Collections.nCopies(100, "x");

      assertThat(embedder.embedDocuments(texts)).hasSize(100);
      assertThat(client.sent).hasSize(2);
    }

    @Test
    void a_short_reply_is_refused() {
      Embedder embedder =
          embedder(
              new Scripted(body -> "{\"embeddings\":[[1]]}"),
              "cohere.embed-english-v3",
              OptionalInt.empty());
      List<String> two = List.of("a", "b");

      assertThatThrownBy(() -> embedder.embedDocuments(two))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  class Configuration {

    /**
     * Refused when it is used, which is when the model is known.
     *
     * <p>It used to be refused when the embedder was built, because the model was fixed then. A
     * provider holds a connection and is told the model per call, so this is the first moment
     * anything can say the family is not one Bedrock embeds with. Later than it was, and still
     * before anything reaches the wire.
     */
    @Test
    void a_model_of_an_unknown_family_is_refused_when_it_is_used() {
      Scripted client = new Scripted(body -> "{}");
      Embedder embedder = embedder(client, "meta.llama3-8b", OptionalInt.empty());
      List<String> texts = List.of("a");

      assertThatThrownBy(() -> embedder.embedDocuments(texts))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Titan")
          .hasMessageContaining("Cohere");
    }

    @Test
    void closing_closes_the_client_it_was_given() {
      Scripted client = new Scripted(body -> "{}");
      new BedrockEmbeddingProvider(client, "search_document", MAPPER).close();
      assertThat(client.closed).isTrue();
    }

    @Test
    void a_region_and_credentials_build_an_embedder_and_a_handed_in_client_is_not_closed() {
      StaticCredentialsProvider credentials =
          StaticCredentialsProvider.create(AwsBasicCredentials.create("akid", "secret"));
      assertThatCode(
              () ->
                  BedrockEmbeddingProvider.create(
                          c ->
                              c.region(Region.US_EAST_1)
                                  .credentialsProvider(credentials)
                                  .model("cohere.embed-english-v3")
                                  .cohereInputType("search_query")
                                  .dimension(512)
                                  .mapper(MAPPER))
                      .close())
          .doesNotThrowAnyException();

      AtomicBoolean closed = new AtomicBoolean();
      var theirs =
          (software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient)
              java.lang.reflect.Proxy.newProxyInstance(
                  BedrockEmbedderTest.class.getClassLoader(),
                  new Class<?>[] {
                    software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient.class
                  },
                  (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                      closed.set(true);
                    }
                    return null;
                  });
      BedrockEmbeddingProvider.create(c -> c.client(theirs)).close();
      assertThat(closed).isFalse();
    }

    @Test
    void what_is_refused_at_configuration() {
      assertThatThrownBy(() -> BedrockEmbeddingProvider.create(c -> c.model(" ")))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> BedrockEmbeddingProvider.create(c -> c.dimension(0)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> BedrockEmbeddingProvider.create(c -> c.mapper(null)))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> BedrockEmbeddingProvider.create(c -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("region");
    }

    @Test
    void from_env_needs_a_region() {
      assumeTrue(
          System.getenv("AWS_REGION") == null && System.getenv("AWS_DEFAULT_REGION") == null,
          "a region is set in this environment");
      assertThatThrownBy(BedrockEmbeddingProvider::fromEnv)
          .isInstanceOf(IllegalStateException.class);
    }
  }
}
