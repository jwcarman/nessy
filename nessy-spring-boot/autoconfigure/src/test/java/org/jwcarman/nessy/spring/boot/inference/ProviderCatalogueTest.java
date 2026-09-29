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
package org.jwcarman.nessy.spring.boot.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a set of {@code nessy.providers.<id>} settings, plus the vendor environment properties that
 * light a hosted preset, resolve to -- pure, with no Spring context.
 */
@DisplayName("The provider catalogue's resolution")
class ProviderCatalogueTest {

  @Test
  void nothing_set_lights_nothing() {
    List<ResolvedProvider> resolved = ProviderCatalogue.resolve(Map.of(), key -> null);

    assertThat(resolved).isEmpty();
  }

  @Test
  void an_openai_key_lights_openai() {
    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of(), Map.of("openai.api-key", "k")::get);

    assertThat(resolved)
        .containsExactly(new ResolvedProvider("openai", Wire.OPENAI, null, "openai", "k"));
  }

  @Test
  void an_xai_key_lights_xai_at_its_own_url() {
    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of(), Map.of("xai.api-key", "k")::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider("xai", Wire.OPENAI, "https://api.x.ai/v1", "x_ai", "k"));
  }

  @Test
  void an_openrouter_key_lights_openrouter_at_its_own_url() {
    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of(), Map.of("openrouter.api-key", "k")::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "openrouter", Wire.OPENAI, "https://openrouter.ai/api/v1", "openrouter", "k"));
  }

  @Test
  void an_nvidia_key_lights_nvidia_at_its_own_url() {
    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of(), Map.of("nvidia.api-key", "k")::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "nvidia", Wire.OPENAI, "https://integrate.api.nvidia.com/v1", "nvidia", "k"));
  }

  @Test
  void every_key_lights_its_own_preset() {
    Map<String, String> properties =
        Map.of(
            "openai.api-key", "ok",
            "anthropic.api-key", "ak",
            "gemini.api-key", "gk");

    List<ResolvedProvider> resolved = ProviderCatalogue.resolve(Map.of(), properties::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider("openai", Wire.OPENAI, null, "openai", "ok"),
            new ResolvedProvider("anthropic", Wire.ANTHROPIC, null, "anthropic", "ak"),
            new ResolvedProvider("gemini", Wire.GEMINI, null, "gcp.gemini", "gk"));
  }

  @Test
  void either_gemini_key_lights_one_gemini() {
    Map<String, String> properties = Map.of("gemini.api-key", "a", "google.api-key", "b");

    List<ResolvedProvider> resolved = ProviderCatalogue.resolve(Map.of(), properties::get);

    assertThat(resolved)
        .containsExactly(new ResolvedProvider("gemini", Wire.GEMINI, null, "gcp.gemini", "a"));
  }

  @Test
  void a_blank_key_does_not_light_a_preset() {
    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of(), Map.of("openai.api-key", " ")::get);

    assertThat(resolved).isEmpty();
  }

  @Test
  void a_prefixed_key_lights_the_preset() {
    ProviderSettings xai = new ProviderSettings(null, null, "k", null, null);

    List<ResolvedProvider> resolved = ProviderCatalogue.resolve(Map.of("xai", xai), key -> null);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider("xai", Wire.OPENAI, "https://api.x.ai/v1", "x_ai", "k"));
  }

  @Test
  void the_openai_base_url_property_overrides_the_openai_preset() {
    Map<String, String> properties =
        Map.of("openai.api-key", "k", "openai.base-url", "http://localhost:1234/v1");

    List<ResolvedProvider> resolved = ProviderCatalogue.resolve(Map.of(), properties::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider("openai", Wire.OPENAI, "http://localhost:1234/v1", "openai", "k"));
  }

  @Test
  void a_setting_overrides_a_preset_field() {
    ProviderSettings anthropic = new ProviderSettings(null, "https://proxy/v1", null, null, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(
            Map.of("anthropic", anthropic), Map.of("anthropic.api-key", "k")::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "anthropic", Wire.ANTHROPIC, "https://proxy/v1", "anthropic", "k"));
  }

  @Test
  void a_custom_provider_needs_a_wire_and_a_url() {
    ProviderSettings mine = new ProviderSettings(Wire.OPENAI, "https://g/v1", "k", null, null);

    List<ResolvedProvider> resolved = ProviderCatalogue.resolve(Map.of("mine", mine), key -> null);

    assertThat(resolved)
        .containsExactly(new ResolvedProvider("mine", Wire.OPENAI, "https://g/v1", "openai", "k"));
  }

  @Test
  void a_custom_provider_without_a_wire_fails_naming_it() {
    ProviderSettings mine = new ProviderSettings(null, "https://g/v1", null, null, null);
    Map<String, ProviderSettings> settings = Map.of("mine", mine);

    assertThatThrownBy(() -> ProviderCatalogue.resolve(settings, key -> null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("nessy.providers.mine.wire is required: mine is not a preset");
  }

  @Test
  void a_custom_provider_without_a_url_fails_naming_it() {
    ProviderSettings mine = new ProviderSettings(Wire.OPENAI, null, null, null, null);
    Map<String, ProviderSettings> settings = Map.of("mine", mine);

    assertThatThrownBy(() -> ProviderCatalogue.resolve(settings, key -> null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("nessy.providers.mine.base-url is required: mine is not a preset");
  }

  @Test
  void lmstudio_is_off_until_enabled() {
    List<ResolvedProvider> resolved = ProviderCatalogue.resolve(Map.of(), key -> null);

    assertThat(resolved).isEmpty();
  }

  @Test
  void lmstudio_lights_when_enabled() {
    ProviderSettings lmstudio = new ProviderSettings(null, null, null, true, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("lmstudio", lmstudio), key -> null);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "lmstudio", Wire.OPENAI, "http://localhost:1234/v1", "lmstudio", "lm-studio"));
  }

  @Test
  void lmstudio_enabled_with_a_key_override_resolves_that_key() {
    ProviderSettings lmstudio = new ProviderSettings(null, null, "custom-key", true, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("lmstudio", lmstudio), key -> null);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "lmstudio", Wire.OPENAI, "http://localhost:1234/v1", "lmstudio", "custom-key"));
  }

  @Test
  void ollama_is_off_until_enabled() {
    List<ResolvedProvider> resolved = ProviderCatalogue.resolve(Map.of(), key -> null);

    assertThat(resolved).isEmpty();
  }

  @Test
  void ollama_lights_when_enabled() {
    ProviderSettings ollama = new ProviderSettings(null, null, null, true, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("ollama", ollama), key -> null);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "ollama", Wire.OPENAI, "http://localhost:11434/v1", "ollama", "ollama"));
  }

  @Test
  void ollama_enabled_with_a_key_override_resolves_that_key() {
    ProviderSettings ollama = new ProviderSettings(null, null, "custom-key", true, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("ollama", ollama), key -> null);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "ollama", Wire.OPENAI, "http://localhost:11434/v1", "ollama", "custom-key"));
  }

  @Test
  void ollama_switched_off_stays_off_even_with_a_key() {
    ProviderSettings ollama = new ProviderSettings(null, null, "custom-key", false, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("ollama", ollama), key -> null);

    assertThat(resolved).isEmpty();
  }

  @Test
  void a_hosted_preset_with_its_key_set_and_enabled_false_is_absent() {
    ProviderSettings xai = new ProviderSettings(null, null, null, false, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("xai", xai), Map.of("xai.api-key", "k")::get);

    assertThat(resolved).isEmpty();
  }

  @Test
  void a_custom_entry_with_wire_and_base_url_and_enabled_false_is_absent() {
    ProviderSettings mine = new ProviderSettings(Wire.OPENAI, "https://g/v1", "k", false, null);

    List<ResolvedProvider> resolved = ProviderCatalogue.resolve(Map.of("mine", mine), key -> null);

    assertThat(resolved).isEmpty();
  }

  @Test
  void lmstudio_with_enabled_false_is_absent() {
    ProviderSettings lmstudio = new ProviderSettings(null, null, null, false, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("lmstudio", lmstudio), key -> null);

    assertThat(resolved).isEmpty();
  }

  @Test
  void a_custom_vendor_defaults_to_the_wires_own() {
    ProviderSettings mine = new ProviderSettings(Wire.ANTHROPIC, "https://g/v1", "k", null, null);

    List<ResolvedProvider> resolved = ProviderCatalogue.resolve(Map.of("mine", mine), key -> null);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider("mine", Wire.ANTHROPIC, "https://g/v1", "anthropic", "k"));
  }
}
