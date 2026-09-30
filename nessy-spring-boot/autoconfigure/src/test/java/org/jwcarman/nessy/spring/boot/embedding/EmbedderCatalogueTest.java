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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a set of {@code nessy.embedders.<id>} settings, plus the vendor environment properties that
 * light a hosted preset, resolve to -- pure, with no Spring context.
 */
@DisplayName("The embedder catalogue's resolution")
class EmbedderCatalogueTest {

  private static final String VOYAGE_URL = "https://api.voyageai.com/v1";

  private static EmbedderSettings settings(
      EmbeddingWire wire, String baseUrl, String apiKey, Boolean enabled, String vendor) {
    return new EmbedderSettings(wire, baseUrl, apiKey, enabled, vendor, null);
  }

  @Test
  void the_catalogue_is_the_three_measured_rows_and_none_is_keyless() {
    assertThat(EmbedderPreset.CATALOGUE)
        .extracting(EmbedderPreset::id)
        .containsExactly("openai", "gemini", "voyage");
    assertThat(EmbedderPreset.CATALOGUE).isNotEmpty().noneMatch(EmbedderPreset::keyless);
  }

  @Test
  void the_wire_values_are_the_vendors_names() {
    assertThat(EmbeddingWire.values())
        .extracting(EmbeddingWire::propertyValue)
        .containsExactly("openai", "gemini", "voyage");
    assertThat(EmbeddingWire.values())
        .extracting(EmbeddingWire::defaultVendor)
        .containsExactly("openai", "gcp.gemini", "voyage");
  }

  @Test
  void nothing_set_lights_nothing() {
    assertThat(EmbedderCatalogue.resolve(Map.of(), key -> null)).isEmpty();
  }

  @Test
  void an_openai_key_lights_openai() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(Map.of(), Map.of("openai.api-key", "k")::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedEmbedder("openai", EmbeddingWire.OPENAI, null, "openai", "k", Map.of()));
  }

  @Test
  void a_voyage_key_lights_voyage_at_its_own_url() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(Map.of(), Map.of("voyage.api-key", "k")::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedEmbedder(
                "voyage", EmbeddingWire.VOYAGE, VOYAGE_URL, "voyage", "k", Map.of()));
  }

  @Test
  void either_gemini_key_lights_one_gemini_and_the_first_spelling_wins() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(
            Map.of(), Map.of("gemini.api-key", "g", "google.api-key", "o")::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedEmbedder(
                "gemini", EmbeddingWire.GEMINI, null, "gcp.gemini", "g", Map.of()));
  }

  @Test
  void every_key_lights_its_own_preset_and_none_is_chosen() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(
            Map.of(),
            Map.of("openai.api-key", "a", "gemini.api-key", "b", "voyage.api-key", "c")::get);

    assertThat(resolved)
        .extracting(ResolvedEmbedder::id)
        .containsExactly("openai", "gemini", "voyage");
  }

  @Test
  void a_blank_key_does_not_light_a_preset() {
    assertThat(EmbedderCatalogue.resolve(Map.of(), Map.of("voyage.api-key", " ")::get)).isEmpty();
  }

  @Test
  void a_prefixed_key_lights_the_preset() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(
            Map.of("voyage", settings(null, null, "k", null, null)), key -> null);

    assertThat(resolved)
        .containsExactly(
            new ResolvedEmbedder(
                "voyage", EmbeddingWire.VOYAGE, VOYAGE_URL, "voyage", "k", Map.of()));
  }

  @Test
  void the_openai_base_url_property_overrides_the_openai_preset_and_no_other() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(
            Map.of(),
            Map.of(
                    "openai.api-key", "k",
                    "openai.base-url", "http://localhost:1234/v1",
                    "voyage.api-key", "v")
                ::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedEmbedder(
                "openai",
                EmbeddingWire.OPENAI,
                "http://localhost:1234/v1",
                "openai",
                "k",
                Map.of()),
            new ResolvedEmbedder(
                "voyage", EmbeddingWire.VOYAGE, VOYAGE_URL, "voyage", "v", Map.of()));
  }

  @Test
  void a_setting_overrides_a_preset_field() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(
            Map.of("voyage", settings(null, "https://proxy/v1", null, null, null)),
            Map.of("voyage.api-key", "k")::get);

    assertThat(resolved).extracting(ResolvedEmbedder::baseUrl).containsExactly("https://proxy/v1");
  }

  @Test
  void a_hosted_preset_with_its_key_set_and_enabled_false_is_absent() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(
            Map.of("gemini", settings(null, null, null, false, null)),
            Map.of("gemini.api-key", "k")::get);

    assertThat(resolved).isEmpty();
  }

  @Test
  void a_custom_embedder_with_a_wire_a_url_and_a_key_is_resolved_with_the_wire_s_vendor() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(
            Map.of("mine", settings(EmbeddingWire.OPENAI, "https://g/v1", "k", null, null)),
            key -> null);

    assertThat(resolved)
        .containsExactly(
            new ResolvedEmbedder(
                "mine", EmbeddingWire.OPENAI, "https://g/v1", "openai", "k", Map.of()));
  }

  @Test
  void a_custom_embedder_keeps_the_vendor_it_names() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(
            Map.of(
                "local",
                settings(
                    EmbeddingWire.OPENAI,
                    "http://localhost:1234/v1",
                    "lm-studio",
                    null,
                    "lmstudio")),
            key -> null);

    assertThat(resolved).extracting(ResolvedEmbedder::vendor).containsExactly("lmstudio");
  }

  @Test
  void a_custom_embedder_without_a_wire_fails_naming_it() {
    Map<String, EmbedderSettings> mine =
        Map.of("mine", settings(null, "https://g/v1", "k", null, null));

    assertThatThrownBy(() -> EmbedderCatalogue.resolve(mine, key -> null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("nessy.embedders.mine.wire is required: mine is not a preset");
  }

  @Test
  void a_custom_embedder_without_a_url_fails_naming_it() {
    Map<String, EmbedderSettings> mine =
        Map.of("mine", settings(EmbeddingWire.OPENAI, " ", "k", null, null));

    assertThatThrownBy(() -> EmbedderCatalogue.resolve(mine, key -> null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("nessy.embedders.mine.base-url is required: mine is not a preset");
  }

  /** Plan ruling 7: every embedding wire needs a key. */
  @Test
  void a_custom_embedder_without_a_key_fails_naming_it() {
    Map<String, EmbedderSettings> mine =
        Map.of("mine", settings(EmbeddingWire.VOYAGE, "https://g/v1", null, null, null));

    assertThatThrownBy(() -> EmbedderCatalogue.resolve(mine, key -> null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("nessy.embedders.mine.api-key is required: the voyage wire needs a key");
  }

  /** §6c made checkable: lmstudio is not a preset, so it is a custom embedder with no wire. */
  @Test
  void an_id_that_is_not_a_preset_turned_on_alone_fails_naming_the_wire() {
    Map<String, EmbedderSettings> lmstudio =
        Map.of("lmstudio", settings(null, null, null, true, null));

    assertThatThrownBy(() -> EmbedderCatalogue.resolve(lmstudio, key -> null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("nessy.embedders.lmstudio.wire is required: lmstudio is not a preset");
  }

  @Test
  void a_custom_entry_with_enabled_false_is_absent() {
    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(
            Map.of("mine", settings(EmbeddingWire.OPENAI, "https://g/v1", "k", false, null)),
            key -> null);

    assertThat(resolved).isEmpty();
  }

  @Test
  void a_preset_carries_the_properties_its_settings_give() {
    EmbedderSettings voyage =
        new EmbedderSettings(null, null, null, null, null, Map.of("voyage.truncation", "false"));

    List<ResolvedEmbedder> resolved =
        EmbedderCatalogue.resolve(Map.of("voyage", voyage), Map.of("voyage.api-key", "k")::get);

    assertThat(resolved)
        .singleElement()
        .extracting(ResolvedEmbedder::properties)
        .isEqualTo(Map.of("voyage.truncation", "false"));
  }

  @Test
  void a_custom_embedder_carries_its_own_properties() {
    EmbedderSettings mine =
        new EmbedderSettings(
            EmbeddingWire.OPENAI, "https://g/v1", "k", null, null, Map.of("openai.user", "t"));

    List<ResolvedEmbedder> resolved = EmbedderCatalogue.resolve(Map.of("mine", mine), key -> null);

    assertThat(resolved)
        .singleElement()
        .extracting(ResolvedEmbedder::properties)
        .isEqualTo(Map.of("openai.user", "t"));
  }
}
