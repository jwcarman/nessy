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
package org.jwcarman.nessy.engine.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.VendorProperty;
import org.jwcarman.nessy.api.embedding.Dimension;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.EmbedderConfig;
import org.jwcarman.nessy.api.embedding.Embedding;
import org.jwcarman.nessy.embedding.EmbeddingOptions;
import org.jwcarman.nessy.embedding.EmbeddingProvider;
import org.jwcarman.nessy.engine.observability.ObservedEmbedder;

/**
 * Which provider a store's embedder is minted over, what it is asked with, and what comes back.
 *
 * <p>A store names a provider and a model, or takes the factory's defaults; either way the provider
 * is resolved once, when the embedder is made, and a width asked for is a width received.
 */
@DisplayName("Embedders over named providers")
class DefaultEmbedderFactoryTest {

  private static final ProviderId OPENAI = ProviderId.of("openai");
  private static final ProviderId VOYAGE = ProviderId.of("voyage");

  /**
   * Remembers what it was asked and validated. Answers with vectors as wide as it was asked for, or
   * three wide when asked for no width -- unless given a fixed width, which it answers with
   * whatever it was asked, as a server that ignores {@code dimensions} does.
   */
  private static final class Asked implements EmbeddingProvider {
    final AtomicReference<EmbeddingOptions> lastAsked = new AtomicReference<>();
    final List<EmbeddingOptions> validated = new ArrayList<>();
    private final String vendor;
    private final int fixedWidth;

    Asked() {
      this("asked", 0);
    }

    Asked(String vendor, int fixedWidth) {
      this.vendor = vendor;
      this.fixedWidth = fixedWidth;
    }

    private Embedding answer(EmbeddingOptions asked) {
      lastAsked.set(asked);
      float[] vector =
          new float
              [fixedWidth > 0 ? fixedWidth : asked.dimension().map(Dimension::value).orElse(3)];
      Arrays.fill(vector, 1f);
      return new Embedding(asked.modelName(), vector);
    }

    @Override
    public List<Embedding> embedDocuments(List<String> texts, EmbeddingOptions options) {
      return texts.stream().map(text -> answer(options)).toList();
    }

    @Override
    public Embedding embedQuery(String query, EmbeddingOptions options) {
      return answer(options);
    }

    @Override
    public String vendor() {
      return vendor;
    }

    @Override
    public void validate(EmbeddingOptions options) {
      if (options.properties().containsKey("test.model")) {
        throw new IllegalArgumentException("property 'test.model' is refused");
      }
      validated.add(options);
    }
  }

  /** Answers a batch of two with a vector two wide, then one three wide: a mixed reply. */
  private static final class Mixed implements EmbeddingProvider {
    @Override
    public List<Embedding> embedDocuments(List<String> texts, EmbeddingOptions options) {
      return List.of(
          new Embedding(options.modelName(), new float[] {1f, 1f}),
          new Embedding(options.modelName(), new float[] {1f, 1f, 1f}));
    }

    @Override
    public Embedding embedQuery(String query, EmbeddingOptions options) {
      return new Embedding(options.modelName(), new float[] {1f, 1f});
    }

    @Override
    public String vendor() {
      return "mixed";
    }
  }

  /** One provider, registered as {@code openai}, and the factory's default on it. */
  private static DefaultEmbedderFactory over(Asked provider, EmbeddingOptions defaults) {
    return DefaultEmbedderFactory.of(f -> f.provider(OPENAI, provider).embedding(OPENAI, defaults));
  }

  // ---- widths ---------------------------------------------------------------------------

  @Test
  void inherit_the_factory_default_width_when_they_ask_for_none() {
    Asked provider = new Asked();
    over(provider, new EmbeddingOptions("a-model", Optional.of(Dimension.of(1024))))
        .create(c -> {})
        .embedDocument("anything");

    assertThat(provider.lastAsked.get().dimension()).hasValue(Dimension.of(1024));
    assertThat(provider.lastAsked.get().modelName()).isEqualTo("a-model");
  }

  @Test
  void keep_their_own_width_when_they_ask_for_one() {
    Asked provider = new Asked();
    over(provider, new EmbeddingOptions("a-model", Optional.of(Dimension.of(1024))))
        .create(c -> c.dimension(256))
        .embedDocument("anything");

    assertThat(provider.lastAsked.get().dimension()).hasValue(Dimension.of(256));
  }

  /** A factory with no opinion leaves the width to the model, which is most of them. */
  @Test
  void ask_for_no_width_when_neither_the_factory_nor_the_embedder_named_one() {
    Asked provider = new Asked();
    over(provider, EmbeddingOptions.of("a-model")).create(c -> {}).embedDocument("anything");

    assertThat(provider.lastAsked.get().dimension()).isEmpty();
  }

  // ---- properties (VP Task 2's two cases, on the new construction) ------------------------

  @Test
  void carry_their_properties_to_the_provider() {
    Asked provider = new Asked();
    over(provider, EmbeddingOptions.of("a-model"))
        .create(c -> c.property("voyage.truncation", "false"))
        .embedDocument("anything");

    assertThat(provider.lastAsked.get().properties())
        .containsExactly(Map.entry("voyage.truncation", "false"));
  }

  @Test
  void carry_a_typed_property_as_the_text_the_string_form_carries() {
    Asked provider = new Asked();
    VendorProperty<Boolean> truncation = VendorProperty.ofBoolean("voyage.truncation");
    over(provider, EmbeddingOptions.of("a-model"))
        .create(c -> c.property(truncation, false))
        .embedDocument("anything");

    assertThat(provider.lastAsked.get().properties())
        .containsExactly(Map.entry("voyage.truncation", "false"));
  }

  @Test
  void are_refused_when_the_provider_refuses_their_terms() {
    DefaultEmbedderFactory factory = over(new Asked(), EmbeddingOptions.of("a-model"));
    Customizer<EmbedderConfig> refused = c -> c.property("test.model", "x");

    assertThatThrownBy(() -> factory.create(refused))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("property 'test.model' is refused");
  }

  @Test
  void the_factory_default_properties_seed_every_embedder() {
    Asked provider = new Asked();
    over(provider, new EmbeddingOptions("a-model", Optional.empty(), Map.of("x.a", "1")))
        .create(c -> c.property("x.b", "2"))
        .embedDocument("anything");

    assertThat(provider.lastAsked.get().properties()).containsOnlyKeys("x.a", "x.b");
  }

  // ---- which provider ---------------------------------------------------------------------

  @Test
  void a_store_that_names_a_registered_provider_gets_that_one() {
    Asked openai = new Asked("openai", 0);
    Asked voyage = new Asked("voyage", 0);
    DefaultEmbedderFactory factory =
        DefaultEmbedderFactory.of(
            f ->
                f.provider(OPENAI, openai)
                    .provider(VOYAGE, voyage)
                    .embedding(OPENAI, EmbeddingOptions.of("text-embedding-3-small")));

    Embedder embedder = factory.create(c -> c.provider("voyage").model("voyage-3.5"));
    embedder.embedDocument("anything");

    assertThat(embedder.vendor()).isEqualTo("voyage");
    assertThat(voyage.lastAsked.get().modelName()).isEqualTo("voyage-3.5");
    assertThat(openai.lastAsked.get()).isNull();
  }

  @Test
  void a_store_that_names_nothing_gets_the_factory_default() {
    Asked openai = new Asked("openai", 0);
    Asked voyage = new Asked("voyage", 0);
    DefaultEmbedderFactory factory =
        DefaultEmbedderFactory.of(
            f ->
                f.provider(OPENAI, openai)
                    .provider(VOYAGE, voyage)
                    .embedding(VOYAGE, EmbeddingOptions.of("voyage-3.5")));

    Embedder embedder = factory.create(c -> {});
    embedder.embedDocument("anything");

    assertThat(embedder.vendor()).isEqualTo("voyage");
    assertThat(embedder.model()).isEqualTo("voyage-3.5");
    assertThat(openai.lastAsked.get()).isNull();
  }

  /** Plan ruling 4: the defaults seed each field on its own, as an agent type's do. */
  @Test
  void a_store_naming_only_a_provider_keeps_the_default_model() {
    Asked openai = new Asked("openai", 0);
    Asked voyage = new Asked("voyage", 0);
    DefaultEmbedderFactory factory =
        DefaultEmbedderFactory.of(
            f ->
                f.provider(OPENAI, openai)
                    .provider(VOYAGE, voyage)
                    .embedding(OPENAI, EmbeddingOptions.of("a-model")));

    factory.create(c -> c.provider(VOYAGE)).embedDocument("anything");

    assertThat(voyage.lastAsked.get().modelName()).isEqualTo("a-model");
  }

  @Test
  void a_store_naming_an_unknown_provider_fails_listing_every_registered_one() {
    DefaultEmbedderFactory factory =
        DefaultEmbedderFactory.of(
            f -> f.provider(OPENAI, new Asked()).provider(VOYAGE, new Asked()));
    Customizer<EmbedderConfig> cohere = c -> c.provider("cohere").model("embed-v4");

    assertThatThrownBy(() -> factory.create(cohere))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "an embedder names provider 'cohere', which is not registered; registered: [openai,"
                + " voyage]");
  }

  @Test
  void
      a_store_naming_no_provider_from_a_factory_with_no_default_fails_listing_what_is_registered() {
    DefaultEmbedderFactory factory =
        DefaultEmbedderFactory.of(
            f -> f.provider(OPENAI, new Asked()).provider(VOYAGE, new Asked()));
    Customizer<EmbedderConfig> modelOnly = c -> c.model("a-model");

    assertThatThrownBy(() -> factory.create(modelOnly))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "an embedder names no provider and the factory has no default; registered: [openai,"
                + " voyage]");
  }

  @Test
  void a_factory_with_nothing_registered_says_so() {
    DefaultEmbedderFactory factory = DefaultEmbedderFactory.of(f -> {});
    Customizer<EmbedderConfig> nothing = c -> {};

    assertThatThrownBy(() -> factory.create(nothing))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("an embedder names no provider and the factory has no default; registered: []");
  }

  @Test
  void a_store_naming_no_model_from_a_factory_with_no_default_fails() {
    DefaultEmbedderFactory factory =
        DefaultEmbedderFactory.of(f -> f.provider(OPENAI, new Asked()));
    Customizer<EmbedderConfig> providerOnly = c -> c.provider(OPENAI);

    assertThatThrownBy(() -> factory.create(providerOnly))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("an embedder needs a model: model(...), or a factory default");
  }

  @Test
  void registering_one_id_twice_fails_at_registration() {
    Asked first = new Asked();
    Asked second = new Asked();
    Customizer<EmbedderFactoryConfig> twice =
        f -> f.provider(OPENAI, first).provider(OPENAI, second);

    assertThatThrownBy(() -> DefaultEmbedderFactory.of(twice))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("embedding provider 'openai' is already registered");
  }

  @Test
  void two_stores_on_one_id_share_the_provider_and_have_their_own_wrappers() {
    Asked provider = new Asked();
    DefaultEmbedderFactory factory = over(provider, EmbeddingOptions.of("a-model"));

    Embedder notes = factory.create(c -> c.model("small"));
    Embedder episodes = factory.create(c -> c.model("large"));
    notes.embedDocument("a");
    String askedFirst = provider.lastAsked.get().modelName();
    episodes.embedDocument("b");

    assertThat(notes).isNotSameAs(episodes).isInstanceOf(ObservedEmbedder.class);
    assertThat(episodes).isInstanceOf(ObservedEmbedder.class);
    assertThat(askedFirst).isEqualTo("small");
    assertThat(provider.lastAsked.get().modelName()).isEqualTo("large");
  }

  @Test
  void a_width_of_zero_is_refused_where_it_is_set() {
    DefaultEmbedderFactory factory = over(new Asked(), EmbeddingOptions.of("a-model"));

    assertThatThrownBy(() -> factory.create(c -> c.dimension(0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("an embedding dimension must be at least 1, was 0");
  }

  @Test
  void a_negative_width_is_refused_where_it_is_set() {
    DefaultEmbedderFactory factory = over(new Asked(), EmbeddingOptions.of("a-model"));

    assertThatThrownBy(() -> factory.create(c -> c.dimension(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("an embedding dimension must be at least 1, was -1");
  }

  // ---- validate, and the wrap -----------------------------------------------------------

  @Test
  void validate_is_asked_with_the_resolved_options_before_anything_is_embedded() {
    Asked provider = new Asked();
    over(provider, EmbeddingOptions.of("a-model")).create(c -> c.dimension(8));

    assertThat(provider.validated)
        .containsExactly(new EmbeddingOptions("a-model", Optional.of(Dimension.of(8)), Map.of()));
    assertThat(provider.lastAsked.get()).isNull();
  }

  @Test
  void with_a_registry_every_embedder_is_observed() {
    DefaultEmbedderFactory factory =
        DefaultEmbedderFactory.of(
            f ->
                f.provider(OPENAI, new Asked())
                    .embedding(OPENAI, EmbeddingOptions.of("a-model"))
                    .observations(ObservationRegistry.create()));

    assertThat(factory.create(c -> {})).isInstanceOf(ObservedEmbedder.class);
  }

  /** Observed either way: with nothing configured the registry is the no-op one. */
  @Test
  void without_one_every_embedder_is_still_observed() {
    assertThat(over(new Asked(), EmbeddingOptions.of("a-model")).create(c -> {}))
        .isInstanceOf(ObservedEmbedder.class);
  }

  // ---- a width asked for is a width received (§5f) ---------------------------------------

  @Test
  void a_width_asked_for_and_not_received_fails_naming_both_on_every_call() {
    Asked server = new Asked("lmstudio", 768);
    Embedder embedder =
        over(server, new EmbeddingOptions("nomic", Optional.of(Dimension.of(256)))).create(c -> {});
    List<String> one = List.of("a lake monster");

    assertThatThrownBy(() -> embedder.embedDocuments(one))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("asked for 256 coordinates, the model returned 768");
    assertThatThrownBy(() -> embedder.embedQuery("where does it live"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("asked for 256 coordinates, the model returned 768");
  }

  @Test
  void an_embedder_that_asked_for_no_width_learns_the_model_s() {
    Embedder embedder =
        over(new Asked("lmstudio", 768), EmbeddingOptions.of("nomic")).create(c -> {});

    assertThat(embedder.dimension()).isEmpty();
    embedder.embedDocument("a lake monster");

    assertThat(embedder.dimension()).hasValue(Dimension.of(768));
  }

  /** Review Focus 4: every vector in a reply is checked, not the first alone. */
  @Test
  void a_batch_with_one_vector_of_the_wrong_width_fails() {
    Embedder embedder =
        DefaultEmbedderFactory.of(
                f ->
                    f.provider(OPENAI, new Mixed())
                        .embedding(OPENAI, new EmbeddingOptions("m", Optional.of(Dimension.of(2)))))
            .create(c -> {});
    List<String> two = List.of("a", "b");

    assertThatThrownBy(() -> embedder.embedDocuments(two))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("asked for 2 coordinates, the model returned 3");
  }
}
