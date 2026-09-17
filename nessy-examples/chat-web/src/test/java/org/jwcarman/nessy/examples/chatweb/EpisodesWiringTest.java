package org.jwcarman.nessy.examples.chatweb;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.embedding.Embedder;
import org.jwcarman.nessy.memory.episodic.JdbcEpisodes;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Naming an embedding model makes an embedder at the chat model's endpoint -- the starter's, from
 * the key and base URL this application already configured -- and the store is handed it. The store
 * is built either way.
 */
@SpringBootTest(properties = "nessy.embedding.openai.model=text-embedding-nomic-embed-text-v1.5")
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
  @Autowired private Embedder embedder;

  @Test
  void the_store_ranks_with_the_configured_embedding_model() {
    assertThat(embedder.model()).isEqualTo("text-embedding-nomic-embed-text-v1.5");
    // The store observes the embedder it is given, so it holds a wrapper around that model.
    assertThat(episodes.embedder()).map(Embedder::model).contains(embedder.model());
  }
}
