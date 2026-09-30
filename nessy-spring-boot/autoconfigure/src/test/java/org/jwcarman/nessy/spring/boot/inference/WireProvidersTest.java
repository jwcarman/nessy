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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.anthropic.AnthropicInferenceProvider;
import org.jwcarman.nessy.inference.openai.OpenAiChatInferenceProvider;
import org.jwcarman.nessy.inference.openai.OpenAiResponsesInferenceProvider;

/** Which adapter class each OpenAI wire builds -- asked before the observation wrapper goes on. */
class WireProvidersTest {

  private static final ClassLoader LOADER = WireProvidersTest.class.getClassLoader();

  @Test
  void the_openai_responses_wire_builds_the_responses_adapter() {
    ResolvedProvider resolved =
        new ResolvedProvider("mine", Wire.OPENAI_RESPONSES, "https://g/v1", "openai", "k");

    Optional<InferenceProvider> built = WireProviders.build(resolved, null, LOADER);

    assertThat(built)
        .get()
        .isInstanceOfSatisfying(
            OpenAiResponsesInferenceProvider.class, OpenAiResponsesInferenceProvider::close);
  }

  @Test
  void the_openai_preset_told_the_responses_wire_builds_without_a_wrong_prefix_failure() {
    ProviderSettings settings =
        new ProviderSettings(Wire.OPENAI_RESPONSES, null, null, null, null, null);
    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("openai", settings), Map.of("openai.api-key", "k")::get);
    assertThat(resolved).hasSize(1);

    Optional<InferenceProvider> built = WireProviders.build(resolved.get(0), null, LOADER);

    assertThat(built)
        .get()
        .isInstanceOfSatisfying(
            OpenAiResponsesInferenceProvider.class, OpenAiResponsesInferenceProvider::close);
  }

  @Test
  void the_openai_preset_told_the_anthropic_wire_builds_without_a_wrong_prefix_failure() {
    ProviderSettings settings = new ProviderSettings(Wire.ANTHROPIC, null, null, null, null, null);
    List<ResolvedProvider> resolved =
        ProviderCatalogue.resolve(Map.of("openai", settings), Map.of("openai.api-key", "k")::get);
    assertThat(resolved).hasSize(1);

    Optional<InferenceProvider> built = WireProviders.build(resolved.get(0), null, LOADER);

    assertThat(built)
        .get()
        .isInstanceOfSatisfying(
            AnthropicInferenceProvider.class, AnthropicInferenceProvider::close);
  }

  @Test
  void the_openai_chat_wire_builds_the_chat_adapter() {
    ResolvedProvider resolved =
        new ResolvedProvider("openai", Wire.OPENAI_CHAT, null, "openai", "k");

    Optional<InferenceProvider> built = WireProviders.build(resolved, null, LOADER);

    assertThat(built)
        .get()
        .isInstanceOfSatisfying(
            OpenAiChatInferenceProvider.class, OpenAiChatInferenceProvider::close);
  }

  @Test
  void both_openai_wires_need_the_one_openai_module() {
    assertThat(WireProviders.artifactId(Wire.OPENAI_RESPONSES)).isEqualTo("nessy-inference-openai");
    assertThat(WireProviders.artifactId(Wire.OPENAI_CHAT)).isEqualTo("nessy-inference-openai");
  }

  @Test
  void the_responses_wire_s_vendor_is_the_resolved_one() {
    ResolvedProvider resolved =
        new ResolvedProvider("pplx", Wire.OPENAI_RESPONSES, "https://g/v1", "perplexity", "k");

    InferenceProvider built = WireProviders.build(resolved, null, LOADER).orElseThrow();

    assertThat(built.vendor()).isEqualTo("perplexity");
    ((OpenAiResponsesInferenceProvider) built).close();
  }

  @Test
  void the_chat_wire_hands_its_properties_to_the_adapter() {
    ResolvedProvider resolved =
        new ResolvedProvider(
            "openai", Wire.OPENAI_CHAT, null, "openai", "k", Map.of("openai.tools.strict", "yes"));

    assertThatThrownBy(() -> WireProviders.build(resolved, null, LOADER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.tools.strict'");
  }

  @Test
  void the_responses_wire_hands_its_properties_to_the_adapter() {
    ResolvedProvider resolved =
        new ResolvedProvider(
            "mine",
            Wire.OPENAI_RESPONSES,
            "https://g/v1",
            "openai",
            "k",
            Map.of("openai.tools.strict", "false"));

    assertThatThrownBy(() -> WireProviders.build(resolved, null, LOADER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.tools.strict'");
  }

  @Test
  void the_anthropic_wire_hands_its_properties_to_the_adapter() {
    ResolvedProvider resolved =
        new ResolvedProvider(
            "anthropic",
            Wire.ANTHROPIC,
            null,
            "anthropic",
            "k",
            Map.of("anthropic.thinking.budget_tokens", "lots"));

    assertThatThrownBy(() -> WireProviders.build(resolved, null, LOADER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'anthropic.thinking.budget_tokens'");
  }

  @Test
  void the_gemini_wire_hands_its_properties_to_the_adapter() {
    ResolvedProvider resolved =
        new ResolvedProvider(
            "gemini",
            Wire.GEMINI,
            null,
            "gcp.gemini",
            "k",
            Map.of("gemini.generationConfig.thinkingConfig.thinkingBudget", "lots"));

    assertThatThrownBy(() -> WireProviders.build(resolved, null, LOADER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'gemini.generationConfig.thinkingConfig.thinkingBudget'");
  }
}
