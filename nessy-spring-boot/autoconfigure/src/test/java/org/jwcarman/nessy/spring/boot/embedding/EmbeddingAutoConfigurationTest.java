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

import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.EmbedderFactory;
import org.jwcarman.nessy.api.embedding.Embedding;
import org.jwcarman.nessy.engine.observability.ObservedEmbedder;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Which embedders an application can make, and from what.
 *
 * <p>Adding the module is how an application asks for embeddings at all; a key it already gave for
 * chat is enough to build the factory, and the model is the vendor's default until a store says
 * otherwise. The starter vends no embedder of its own: the model belongs to whichever store holds
 * the vectors, so the store is what names it.
 */
class EmbeddingAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  org.springframework.boot.micrometer.observation.autoconfigure
                      .ObservationAutoConfiguration.class,
                  VoyageEmbeddingAutoConfiguration.class,
                  OpenAiEmbeddingAutoConfiguration.class,
                  GeminiEmbeddingAutoConfiguration.class));

  /** The embedder a store with nothing to say would get: the factory's own default model. */
  private static Embedder defaultEmbedder(AssertableApplicationContext context) {
    return context.getBean(EmbedderFactory.class).create(c -> {});
  }

  @Test
  @DisplayName("with no keys at all, nothing can make an embedder")
  void with_no_keys_at_all_no_embedder_is_made() {
    runner.run(context -> assertThat(context).doesNotHaveBean(EmbedderFactory.class));
  }

  @Nested
  @DisplayName("OpenAI")
  class OpenAi {

    @Test
    @DisplayName("an api key alone is enough, and the model is OpenAI's default")
    void an_api_key_alone_is_enough() {
      runner
          .withPropertyValues("openai.api-key=sk-test")
          .run(
              context -> {
                assertThat(context).hasSingleBean(EmbedderFactory.class);
                assertThat(defaultEmbedder(context).model()).isEqualTo("text-embedding-3-small");
                assertThat(defaultEmbedder(context).providerName()).isEqualTo("openai");
              });
    }

    @Test
    @DisplayName("a named model is the default the factory mints with")
    void a_named_model_is_used_instead() {
      runner
          .withPropertyValues(
              "openai.api-key=sk-test", "nessy.embedding.openai.model=text-embedding-3-large")
          .run(
              context ->
                  assertThat(defaultEmbedder(context).model()).isEqualTo("text-embedding-3-large"));
    }

    /** A store can name its own model, whatever the application's default is. */
    @Test
    @DisplayName("a store that names its own model gets that one")
    void a_store_that_names_its_own_model_gets_that_one() {
      runner
          .withPropertyValues("openai.api-key=sk-test")
          .run(
              context -> {
                Embedder mine =
                    context
                        .getBean(EmbedderFactory.class)
                        .create(c -> c.model("text-embedding-3-large"));
                assertThat(mine.model()).isEqualTo("text-embedding-3-large");
                assertThat(defaultEmbedder(context).model()).isEqualTo("text-embedding-3-small");
              });
    }

    /**
     * An OpenAI-compatible endpoint serves the models it serves. Guessing OpenAI's own would fail
     * at the first call, with a model nobody named.
     */
    @Test
    @DisplayName("with a base url and no model named, nothing is made")
    void with_a_base_url_and_no_model_named_nothing_is_made() {
      runner
          .withPropertyValues(
              "openai.api-key=lm-studio", "openai.base-url=http://localhost:1234/v1")
          .run(context -> assertThat(context).doesNotHaveBean(EmbedderFactory.class));
    }

    @Test
    @DisplayName("with a base url and a model named, that one is made")
    void with_a_base_url_and_a_model_named_that_one_is_made() {
      runner
          .withPropertyValues(
              "openai.api-key=lm-studio",
              "openai.base-url=http://localhost:1234/v1",
              "nessy.embedding.openai.model=text-embedding-nomic-embed-text-v1.5")
          .run(
              context ->
                  assertThat(defaultEmbedder(context).model())
                      .isEqualTo("text-embedding-nomic-embed-text-v1.5"));
    }
  }

  @Nested
  @DisplayName("the others")
  class TheOthers {

    @Test
    @DisplayName("a Gemini key makes a Gemini embedder")
    void a_gemini_key_makes_a_gemini_embedder() {
      runner
          .withPropertyValues("gemini.api-key=g-test")
          .run(
              context -> {
                assertThat(defaultEmbedder(context).model()).isEqualTo("gemini-embedding-001");
                assertThat(defaultEmbedder(context).providerName()).isEqualTo("gcp.gemini");
              });
    }

    @Test
    @DisplayName("Google's other name for the Gemini key does too")
    void googles_other_name_for_the_key_does_too() {
      runner
          .withPropertyValues("google.api-key=g-test")
          .run(
              context ->
                  assertThat(defaultEmbedder(context).providerName()).isEqualTo("gcp.gemini"));
    }

    @Test
    @DisplayName("a Voyage key wins, because a Voyage key exists for embeddings alone")
    void a_voyage_key_wins() {
      runner
          .withPropertyValues(
              "nessy.embedding.voyage.api-key=v-test",
              "openai.api-key=sk-test",
              "gemini.api-key=g-test")
          .run(
              context -> {
                assertThat(context).hasSingleBean(EmbedderFactory.class);
                assertThat(defaultEmbedder(context).providerName()).isEqualTo("voyage");
              });
    }
  }

  @Nested
  @DisplayName("observing")
  class Observing {

    @Test
    @DisplayName("with a registry, every call is a span")
    void with_a_registry_every_call_is_a_span() {
      runner
          .withBean(ObservationRegistry.class, ObservationRegistry::create)
          .withPropertyValues("openai.api-key=sk-test")
          .run(
              context -> assertThat(defaultEmbedder(context)).isInstanceOf(ObservedEmbedder.class));
    }

    /** Observed either way: with nothing configured the registry is the no-op one. */
    @Test
    @DisplayName("without one, it is observed against the no-op registry")
    void without_one_it_is_observed_against_the_no_op_registry() {
      runner
          .withPropertyValues("openai.api-key=sk-test")
          .run(
              context -> assertThat(defaultEmbedder(context)).isInstanceOf(ObservedEmbedder.class));
    }
  }

  @Nested
  @DisplayName("when the application supplies its own EmbedderFactory")
  class WhenTheApplicationSuppliesItsOwn {

    @Test
    @DisplayName("it backs off entirely")
    void it_backs_off_entirely() {
      EmbedderFactory ours = customizer -> OwnEmbedder.INSTANCE;
      runner
          .withPropertyValues("openai.api-key=sk-test", "gemini.api-key=g-test")
          .withBean(EmbedderFactory.class, () -> ours)
          .run(
              context -> {
                assertThat(context).hasSingleBean(EmbedderFactory.class);
                assertThat(context.getBean(EmbedderFactory.class)).isSameAs(ours);
                assertThat(defaultEmbedder(context)).isSameAs(OwnEmbedder.INSTANCE);
              });
    }
  }

  /** An application's own, which the auto-configurations must leave alone. */
  private static final class OwnEmbedder implements Embedder {

    static final Embedder INSTANCE = new OwnEmbedder();

    @Override
    public String model() {
      return "ours";
    }

    @Override
    public int dimension() {
      return 1;
    }

    @Override
    public List<Embedding> embedDocuments(List<String> texts) {
      return texts.stream().map(t -> new Embedding(model(), new float[] {1})).toList();
    }
  }
}
