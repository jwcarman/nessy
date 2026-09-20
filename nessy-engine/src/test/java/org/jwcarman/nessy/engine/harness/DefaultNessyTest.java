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
package org.jwcarman.nessy.engine.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Nessy;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.EmbedderFactory;
import org.jwcarman.nessy.api.embedding.Embedding;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.store.Schemas;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One set of model connections, and the three ways of using them.
 *
 * <p>What is worth asserting is not that a door returns something -- it is that the doors are the
 * same wiring. An application says which model once; the agents and the readers of documents both
 * talk through it.
 */
class DefaultNessyTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static final InferenceProvider MODEL =
      (_, _) -> new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("done"));

  private static HikariDataSource dataSource() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    HikariDataSource dataSource = new HikariDataSource(config);
    Schemas.initialize(dataSource);
    return dataSource;
  }

  private static Nessy nessy(java.util.function.Consumer<NessyConfig> extra) {
    return DefaultNessy.of(
        c -> {
          c.dataSource(dataSource()).inference(MODEL, InferenceOptions.of("a-model"));
          extra.accept(c);
        });
  }

  @Test
  void the_three_doors_are_all_open() {
    try (Nessy nessy = nessy(_ -> {})) {
      assertThat(nessy.harnesses()).isNotNull();
      assertThat(nessy.extractors()).isNotNull();
    }
  }

  /**
   * An extractor built from the same wiring, with no database on its path.
   *
   * <p>It reads through the provider this was configured with, which is the reason the door exists:
   * saying the model twice is how the two halves of an application drift apart.
   */
  @Test
  void an_extractor_reads_with_the_model_nessy_was_given() {
    record Shape(String said) {}

    try (Nessy nessy = nessy(_ -> {})) {
      var extraction =
          nessy.extractors().create(c -> c.model("a-model")).extract(Shape.class, "a document");

      assertThat(extraction)
          .as("the stand-in model answers rather than recording, and that is Talked")
          .isInstanceOf(org.jwcarman.nessy.api.extraction.Extraction.Talked.class);
    }
  }

  /**
   * Asking for what was never wired is a wiring mistake, and says so.
   *
   * <p>Said while an application is starting rather than found later, when retrieval would have
   * been quietly ranking by recency. A store that works either way does not ask.
   */
  @Test
  void asking_for_embedders_that_were_never_wired_is_refused() {
    try (Nessy nessy = nessy(_ -> {})) {
      assertThatThrownBy(nessy::embedders)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no embedding provider is configured");
    }
  }

  /**
   * What was wired is what is handed back.
   *
   * <p>Nessy owns no embedding of its own: which model a store is keyed on is that store's
   * decision, and this door only says where embedders come from. What a factory does with a model
   * is its own test, in the module that implements one.
   */
  @Test
  void the_embedder_factory_that_was_configured_is_the_one_handed_back() {
    Embedder stub =
        new Embedder() {
          @Override
          public String model() {
            return "a-model";
          }

          @Override
          public int dimension() {
            return 1;
          }

          @Override
          public List<Embedding> embedDocuments(List<String> texts) {
            return texts.stream().map(t -> new Embedding(model(), new float[] {1})).toList();
          }
        };
    EmbedderFactory embedders = c -> stub;

    try (Nessy nessy = nessy(c -> c.embedders(embedders))) {
      assertThat(nessy.embedders()).isSameAs(embedders);
      assertThat(nessy.embedders().create(c -> {})).isSameAs(stub);
    }
  }

  /** Found on the way up rather than when the first turn runs. */
  @Test
  void a_model_is_required() {
    HikariDataSource dataSource = dataSource();

    assertThatThrownBy(() -> DefaultNessy.of(c -> c.dataSource(dataSource)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("inference(provider, options)");
  }

  /** A schema generator and a mapper are wiring with a right answer, so they have one. */
  @Test
  void the_schema_generator_and_mapper_are_defaulted() {
    record Shape(String said) {}

    try (Nessy nessy = nessy(_ -> {})) {
      assertThat(nessy.extractors().create(c -> c.model("a-model")))
          .as("built without either being named")
          .isNotNull();
    }
  }
}
