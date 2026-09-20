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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.Embedding;
import org.jwcarman.nessy.engine.embedding.DefaultEmbedderFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Against a local HTTP server standing in for Voyage: what is sent, and what is made of replies.
 */
@DisplayName("The Voyage embedder")
class VoyageEmbedderTest {

  /** An embedder over a provider: the connection is the provider's, the model the caller's. */
  private static Embedder embedderOver(VoyageEmbeddingProvider provider, String model) {
    return new DefaultEmbedderFactory(provider, model).create(c -> {});
  }

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private HttpServer server;
  private final List<JsonNode> received = new ArrayList<>();
  private final List<String> authorizations = new ArrayList<>();
  private Function<JsonNode, String> answer = body -> "{\"data\":[]}";
  private int status = 200;

  @BeforeEach
  void serve() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
          received.add(body);
          authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] reply = answer.apply(body).getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, reply.length);
          exchange.getResponseBody().write(reply);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private VoyageEmbeddingProvider provider(VoyageEmbedderCustomizer more) {
    return VoyageEmbeddingProvider.create(
        c -> {
          c.apiKey("test-key")
              .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
          more.customize(c);
        });
  }

  private static String reply(float[]... vectors) {
    StringBuilder out = new StringBuilder("{\"object\":\"list\",\"data\":[");
    // Deliberately out of order, to prove the index is what places them.
    for (int i = vectors.length - 1; i >= 0; i--) {
      out.append("{\"index\":").append(i).append(",\"embedding\":[");
      for (int j = 0; j < vectors[i].length; j++) {
        out.append(j == 0 ? "" : ",").append(vectors[i][j]);
      }
      out.append("]}").append(i == 0 ? "" : ",");
    }
    return out.append("],\"model\":\"voyage-3.5\"}").toString();
  }

  @Nested
  class EmbeddingTexts {

    @Test
    void a_batch_is_one_authorised_request_and_comes_back_in_the_order_asked() {
      answer = body -> reply(new float[] {1, 0}, new float[] {0, 1});
      try (VoyageEmbeddingProvider connection = provider(c -> {})) {
        Embedder embedder = embedderOver(connection, VoyageEmbedderConfig.DEFAULT_MODEL);
        List<Embedding> embeddings = embedder.embedDocuments(List.of("first", "second"));

        assertThat(embeddings)
            .extracting(Embedding::vector)
            .containsExactly(new float[] {1, 0}, new float[] {0, 1});
        assertThat(embedder.dimension()).isEqualTo(2);
        assertThat(embedder.model()).isEqualTo("voyage-3.5");
        assertThat(received).hasSize(1);
        assertThat(received.getFirst().path("model").asString()).isEqualTo("voyage-3.5");
        assertThat(received.getFirst().path("input")).hasSize(2);
        assertThat(received.getFirst().has("output_dimension")).isFalse();
        assertThat(authorizations).containsExactly("Bearer test-key");
        assertThat(embedder.embedDocuments(List.of())).isEmpty();
      }
    }

    @Test
    void a_dimension_and_an_input_type_asked_for_are_sent() {
      answer = body -> reply(new float[] {1});
      try (VoyageEmbeddingProvider connection = provider(c -> c.inputType("query"))) {
        Embedder embedder =
            new DefaultEmbedderFactory(connection, "voyage-3.5-lite").create(c -> c.dimension(512));

        assertThat(embedder.dimension()).isEqualTo(512);
        embedder.embedDocument("x");

        assertThat(received.getFirst().path("output_dimension").asInt()).isEqualTo(512);
        assertThat(received.getFirst().path("input_type").asString()).isEqualTo("query");
        assertThat(received.getFirst().path("model").asString()).isEqualTo("voyage-3.5-lite");
      }
    }

    @Test
    void more_than_a_batch_goes_in_several_requests() {
      answer =
          body -> {
            float[][] vectors = new float[body.path("input").size()][];
            java.util.Arrays.fill(vectors, new float[] {1});
            return reply(vectors);
          };
      try (VoyageEmbeddingProvider connection = provider(c -> {})) {
        Embedder embedder = embedderOver(connection, VoyageEmbedderConfig.DEFAULT_MODEL);
        assertThat(embedder.embedDocuments(java.util.Collections.nCopies(130, "x"))).hasSize(130);
        assertThat(received).hasSize(2);
      }
    }

    @Test
    void a_short_reply_and_a_stray_index_are_refused() {
      answer = body -> reply(new float[] {1});
      try (VoyageEmbeddingProvider connection = provider(c -> {})) {
        Embedder embedder = embedderOver(connection, VoyageEmbedderConfig.DEFAULT_MODEL);
        List<String> two = List.of("a", "b");
        assertThatThrownBy(() -> embedder.embedDocuments(two))
            .isInstanceOf(IllegalStateException.class);
      }
      answer = body -> "{\"data\":[{\"index\":7,\"embedding\":[1]}]}";
      try (VoyageEmbeddingProvider connection = provider(c -> {})) {
        Embedder embedder = embedderOver(connection, VoyageEmbedderConfig.DEFAULT_MODEL);
        assertThatThrownBy(() -> embedder.embedDocument("a"))
            .isInstanceOf(IllegalStateException.class);
      }
    }

    @Test
    void an_error_status_is_reported_with_the_vendors_words() {
      status = 401;
      answer = body -> "{\"detail\":\"bad key\"}";
      try (VoyageEmbeddingProvider connection = provider(c -> {})) {
        Embedder embedder = embedderOver(connection, VoyageEmbedderConfig.DEFAULT_MODEL);
        assertThatThrownBy(() -> embedder.embedDocument("a"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("401")
            .hasMessageContaining("bad key");
      }
    }

    @Test
    void an_unreachable_endpoint_is_reported() {
      Embedder unreachable =
          embedderOver(
              VoyageEmbeddingProvider.create(
                  c ->
                      c.apiKey("k")
                          .baseUrl("http://127.0.0.1:1/v1")
                          .timeout(Duration.ofSeconds(2))),
              VoyageEmbedderConfig.DEFAULT_MODEL);

      assertThatThrownBy(() -> unreachable.embedDocument("a"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("could not reach");
    }
  }

  @Nested
  class Configuration {

    @Test
    void what_is_refused_at_configuration() {
      assertThatThrownBy(() -> VoyageEmbeddingProvider.create(c -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("apiKey");
      assertThatThrownBy(() -> VoyageEmbeddingProvider.create(c -> c.model(" ")))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> VoyageEmbeddingProvider.create(c -> c.dimension(0)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> VoyageEmbeddingProvider.create(c -> c.mapper(null)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void from_env_needs_a_key_unless_one_is_given_explicitly() {
      assumeTrue(System.getenv("VOYAGE_API_KEY") == null, "a key is set in this environment");
      assertThatThrownBy(VoyageEmbeddingProvider::fromEnv)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("VOYAGE_API_KEY");
      assertThat(
              VoyageEmbeddingProvider.create(
                      c ->
                          c.fromEnv()
                              .apiKey("k")
                              .httpClient(java.net.http.HttpClient.newHttpClient()))
                  .providerName())
          .isEqualTo("voyage");
    }
  }
}
