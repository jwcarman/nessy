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
package org.jwcarman.nessy.spring.boot.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.embedding.Dimension;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.EmbedderFactory;
import org.jwcarman.nessy.api.embedding.Embedding;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * A custom embedder, {@code local}, on the {@code openai} wire at LM Studio running on this machine
 * with {@code text-embedding-nomic-embed-text-v1.5} loaded, through the whole starter. It proves
 * the custom-embedder route the providers guide documents for local servers, and the width check
 * against a server measured to ignore the width it is asked for. It promotes nothing to a preset.
 *
 * <pre>{@code
 * ./mvnw -q -pl :nessy-spring-boot-autoconfigure -am test -Dnessy.excludedGroups= -Dtest=LocalEmbedderLiveTest -Dsurefire.failIfNoSpecifiedTests=false
 * }</pre>
 */
@Tag("live")
@DisplayName("A custom embedder at LM Studio, through the starter")
class LocalEmbedderLiveTest {

  private static final String MODEL = "text-embedding-nomic-embed-text-v1.5";

  @BeforeEach
  void skipWithoutLmStudio() {
    assumeTrue(lmStudioListens(), "nothing is listening on localhost:1234");
  }

  private static boolean lmStudioListens() {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("localhost", 1234), 500);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(EmbeddingProvidersAutoConfiguration.class))
          .withBean(ObservationRegistry.class, ObservationRegistry::create)
          .withPropertyValues(
              "nessy.embedders.local.wire=openai",
              "nessy.embedders.local.base-url=http://localhost:1234/v1",
              "nessy.embedders.local.api-key=lm-studio",
              "nessy.embedders.local.vendor=lmstudio",
              "nessy.embedder=local",
              "nessy.embedding-model=" + MODEL);

  @Test
  void the_default_embedder_learns_the_model_s_width_and_records_the_model_it_asked_for() {
    runner.run(
        context -> {
          Embedder embedder = context.getBean(EmbedderFactory.class).create(c -> {});
          assertThat(embedder.dimension()).isEmpty();

          Embedding document =
              embedder.embedDocument("The Loch Ness monster is said to live in a Scottish lake.");
          Embedding query = embedder.embedQuery("Where does Nessie live?");

          assertThat(document.dimension()).isEqualTo(768);
          assertThat(query.dimension()).isEqualTo(768);
          assertThat(embedder.dimension()).hasValue(Dimension.of(768));
          assertThat(document.model()).isEqualTo(MODEL);
          assertThat(embedder.vendor()).isEqualTo("lmstudio");
        });
  }

  /**
   * A width the server ignores is measured on the wire: LM Studio answers 768 whatever is asked.
   */
  @Test
  void a_width_the_server_ignores_fails_naming_both() {
    runner.run(
        context -> {
          Embedder narrow = context.getBean(EmbedderFactory.class).create(c -> c.dimension(256));

          assertThatThrownBy(() -> narrow.embedDocument("a lake monster"))
              .isInstanceOf(IllegalStateException.class)
              .hasMessage("asked for 256 coordinates, the model returned 768");
        });
  }
}
