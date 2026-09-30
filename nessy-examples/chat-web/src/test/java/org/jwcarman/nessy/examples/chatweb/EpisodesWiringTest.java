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
package org.jwcarman.nessy.examples.chatweb;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.EmbedderFactory;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.memory.episodic.JdbcEpisodes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Naming a default embedder makes the store rank by relevance: chat-web's own custom embedder,
 * {@code local}, at the chat model's endpoint, with the pair naming it. The store is built either
 * way.
 */
@SpringBootTest(
    properties = {
      "nessy.embedder=local",
      "nessy.embedding-model=text-embedding-nomic-embed-text-v1.5",
      "nessy.provider=scriptedModels"
    })
@Import(PostgresBacked.class)
@DisplayName("Episodes in the chat example")
class EpisodesWiringTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class ScriptedModelConfiguration {
    @Bean
    InferenceProvider scriptedModels() {
      return ScriptedProvider.alwaysSaying("Noted.");
    }
  }

  @Autowired private JdbcEpisodes episodes;
  @Autowired private EmbedderFactory embedders;

  @Test
  void the_store_ranks_with_the_configured_embedding_model() {
    assertThat(embedders.create(c -> {}).model()).isEqualTo("text-embedding-nomic-embed-text-v1.5");
    // The store minted its own from the same factory, so it is keyed on that same model.
    assertThat(episodes.embedder())
        .map(Embedder::model)
        .contains("text-embedding-nomic-embed-text-v1.5");
    assertThat(episodes.embedder()).map(Embedder::vendor).contains("lmstudio");
  }
}
