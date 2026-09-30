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
        .containsExactly(
            new ResolvedProvider(
                "openai",
                Wire.OPENAI_CHAT,
                null,
                "openai",
                "k",
                Map.of("openai.tools.strict", "true")));
  }

  @Test
  void an_xai_key_lights_xai_at_its_own_url() {
    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of(), Map.of("xai.api-key", "k")::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "xai",
                Wire.OPENAI_CHAT,
                "https://api.x.ai/v1",
                "x_ai",
                "k",
                Map.of("openai.tools.strict", "true")));
  }

  @Test
  void an_openrouter_key_lights_openrouter_at_its_own_url() {
    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of(), Map.of("openrouter.api-key", "k")::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "openrouter",
                Wire.OPENAI_CHAT,
                "https://openrouter.ai/api/v1",
                "openrouter",
                "k",
                Map.of("openai.tools.strict", "true")));
  }

  @Test
  void an_nvidia_key_lights_nvidia_at_its_own_url() {
    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of(), Map.of("nvidia.api-key", "k")::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "nvidia",
                Wire.OPENAI_CHAT,
                "https://integrate.api.nvidia.com/v1",
                "nvidia",
                "k",
                Map.of("openai.tools.strict", "true")));
  }

  @Test
  void a_groq_key_lights_groq_at_its_own_url() {
    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of(), Map.of("groq.api-key", "k")::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "groq",
                Wire.OPENAI_CHAT,
                "https://api.groq.com/openai/v1",
                "groq",
                "k",
                Map.of("openai.tools.strict", "true")));
  }

  @Test
  void a_cerebras_key_lights_cerebras_at_its_own_url_with_strict_tools() {
    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of(), Map.of("cerebras.api-key", "k")::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "cerebras",
                Wire.OPENAI_CHAT,
                "https://api.cerebras.ai/v1",
                "cerebras",
                "k",
                Map.of("openai.tools.strict", "true")));
  }

  @Test
  void a_mistral_key_lights_mistral_at_its_own_url() {
    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of(), Map.of("mistral.api-key", "k")::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "mistral",
                Wire.OPENAI_CHAT,
                "https://api.mistral.ai/v1",
                "mistral_ai",
                "k",
                Map.of("openai.tools.strict", "true")));
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
            new ResolvedProvider(
                "openai",
                Wire.OPENAI_CHAT,
                null,
                "openai",
                "ok",
                Map.of("openai.tools.strict", "true")),
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
    ProviderSettings xai = new ProviderSettings(null, null, "k", null, null, null);

    List<ResolvedProvider> resolved = ProviderCatalogue.resolve(Map.of("xai", xai), key -> null);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "xai",
                Wire.OPENAI_CHAT,
                "https://api.x.ai/v1",
                "x_ai",
                "k",
                Map.of("openai.tools.strict", "true")));
  }

  @Test
  void the_openai_preset_told_the_responses_wire_resolves_to_it() {
    ProviderSettings openai =
        new ProviderSettings(Wire.OPENAI_RESPONSES, null, null, null, null, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("openai", openai), Map.of("openai.api-key", "k")::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider("openai", Wire.OPENAI_RESPONSES, null, "openai", "k", Map.of()));
  }

  @Test
  void the_openai_preset_told_another_wire_starts_without_the_openai_default() {
    ProviderSettings openai = new ProviderSettings(Wire.ANTHROPIC, null, null, null, null, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("openai", openai), Map.of("openai.api-key", "k")::get);

    assertThat(resolved)
        .singleElement()
        .extracting(ResolvedProvider::properties)
        .isEqualTo(Map.of());
  }

  @Test
  void the_openai_preset_told_the_wire_it_already_has_keeps_its_default() {
    ProviderSettings openai = new ProviderSettings(Wire.OPENAI_CHAT, null, null, null, null, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("openai", openai), Map.of("openai.api-key", "k")::get);

    assertThat(resolved)
        .singleElement()
        .extracting(ResolvedProvider::properties)
        .isEqualTo(Map.of("openai.tools.strict", "true"));
  }

  @Test
  void the_openai_base_url_property_overrides_the_openai_preset() {
    Map<String, String> properties =
        Map.of("openai.api-key", "k", "openai.base-url", "http://localhost:1234/v1");

    List<ResolvedProvider> resolved = ProviderCatalogue.resolve(Map.of(), properties::get);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "openai",
                Wire.OPENAI_CHAT,
                "http://localhost:1234/v1",
                "openai",
                "k",
                Map.of("openai.tools.strict", "true")));
  }

  @Test
  void a_setting_overrides_a_preset_field() {
    ProviderSettings anthropic =
        new ProviderSettings(null, "https://proxy/v1", null, null, null, null);

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
    ProviderSettings mine =
        new ProviderSettings(Wire.OPENAI_CHAT, "https://g/v1", "k", null, null, null);

    List<ResolvedProvider> resolved = ProviderCatalogue.resolve(Map.of("mine", mine), key -> null);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider("mine", Wire.OPENAI_CHAT, "https://g/v1", "openai", "k"));
  }

  @Test
  void a_custom_provider_without_a_wire_fails_naming_it() {
    ProviderSettings mine = new ProviderSettings(null, "https://g/v1", null, null, null, null);
    Map<String, ProviderSettings> settings = Map.of("mine", mine);

    assertThatThrownBy(() -> ProviderCatalogue.resolve(settings, key -> null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("nessy.providers.mine.wire is required: mine is not a preset");
  }

  @Test
  void a_custom_provider_without_a_url_fails_naming_it() {
    ProviderSettings mine = new ProviderSettings(Wire.OPENAI_CHAT, null, null, null, null, null);
    Map<String, ProviderSettings> settings = Map.of("mine", mine);

    assertThatThrownBy(() -> ProviderCatalogue.resolve(settings, key -> null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("nessy.providers.mine.base-url is required: mine is not a preset");
  }

  @Test
  void lmstudio_is_off_until_enabled() {
    ProviderSettings lmstudio = new ProviderSettings(null, null, "custom-key", null, null, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("lmstudio", lmstudio), key -> null);

    assertThat(resolved).isEmpty();
  }

  @Test
  void lmstudio_lights_when_enabled() {
    ProviderSettings lmstudio = new ProviderSettings(null, null, null, true, null, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("lmstudio", lmstudio), key -> null);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "lmstudio", Wire.OPENAI_CHAT, "http://localhost:1234/v1", "lmstudio", "lm-studio"));
  }

  @Test
  void lmstudio_enabled_with_a_key_override_resolves_that_key() {
    ProviderSettings lmstudio = new ProviderSettings(null, null, "custom-key", true, null, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("lmstudio", lmstudio), key -> null);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "lmstudio",
                Wire.OPENAI_CHAT,
                "http://localhost:1234/v1",
                "lmstudio",
                "custom-key"));
  }

  @Test
  void ollama_is_off_until_enabled() {
    ProviderSettings ollama = new ProviderSettings(null, null, "custom-key", null, null, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("ollama", ollama), key -> null);

    assertThat(resolved).isEmpty();
  }

  @Test
  void ollama_lights_when_enabled() {
    ProviderSettings ollama = new ProviderSettings(null, null, null, true, null, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("ollama", ollama), key -> null);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "ollama", Wire.OPENAI_CHAT, "http://localhost:11434/v1", "ollama", "ollama"));
  }

  @Test
  void ollama_enabled_with_a_key_override_resolves_that_key() {
    ProviderSettings ollama = new ProviderSettings(null, null, "custom-key", true, null, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("ollama", ollama), key -> null);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider(
                "ollama", Wire.OPENAI_CHAT, "http://localhost:11434/v1", "ollama", "custom-key"));
  }

  @Test
  void ollama_switched_off_stays_off_even_with_a_key() {
    ProviderSettings ollama = new ProviderSettings(null, null, "custom-key", false, null, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("ollama", ollama), key -> null);

    assertThat(resolved).isEmpty();
  }

  @Test
  void a_hosted_preset_with_its_key_set_and_enabled_false_is_absent() {
    ProviderSettings xai = new ProviderSettings(null, null, null, false, null, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("xai", xai), Map.of("xai.api-key", "k")::get);

    assertThat(resolved).isEmpty();
  }

  @Test
  void a_custom_entry_with_wire_and_base_url_and_enabled_false_is_absent() {
    ProviderSettings mine =
        new ProviderSettings(Wire.OPENAI_CHAT, "https://g/v1", "k", false, null, null);

    List<ResolvedProvider> resolved = ProviderCatalogue.resolve(Map.of("mine", mine), key -> null);

    assertThat(resolved).isEmpty();
  }

  @Test
  void lmstudio_with_enabled_false_is_absent() {
    ProviderSettings lmstudio = new ProviderSettings(null, null, null, false, null, null);

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("lmstudio", lmstudio), key -> null);

    assertThat(resolved).isEmpty();
  }

  @Test
  void a_custom_vendor_defaults_to_the_wires_own() {
    ProviderSettings mine =
        new ProviderSettings(Wire.ANTHROPIC, "https://g/v1", "k", null, null, null);

    List<ResolvedProvider> resolved = ProviderCatalogue.resolve(Map.of("mine", mine), key -> null);

    assertThat(resolved)
        .containsExactly(
            new ResolvedProvider("mine", Wire.ANTHROPIC, "https://g/v1", "anthropic", "k"));
  }

  @Test
  void settings_overlay_a_preset_s_default_properties_name_by_name() {
    ProviderSettings openai =
        new ProviderSettings(
            null,
            null,
            null,
            null,
            null,
            Map.of("openai.tools.strict", "false", "openai.reasoning.effort", "high"));

    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("openai", openai), Map.of("openai.api-key", "k")::get);

    assertThat(resolved)
        .singleElement()
        .extracting(ResolvedProvider::properties)
        .isEqualTo(Map.of("openai.tools.strict", "false", "openai.reasoning.effort", "high"));
  }

  @Test
  void a_preset_without_defaults_carries_no_properties() {
    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of(), Map.of("anthropic.api-key", "k")::get);

    assertThat(resolved)
        .singleElement()
        .extracting(ResolvedProvider::properties)
        .isEqualTo(Map.of());
  }

  @Test
  void a_custom_provider_carries_only_its_own_properties() {
    ProviderSettings mine =
        new ProviderSettings(
            Wire.OPENAI_CHAT, "https://g/v1", "k", null, null, Map.of("openai.temperature", "0.2"));

    List<ResolvedProvider> resolved = ProviderCatalogue.resolve(Map.of("mine", mine), key -> null);

    assertThat(resolved)
        .singleElement()
        .extracting(ResolvedProvider::properties)
        .isEqualTo(Map.of("openai.temperature", "0.2"));
  }
}
