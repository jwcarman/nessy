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

import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.engine.observability.ObservedInferenceProvider;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.anthropic.AnthropicInferenceProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * What {@code nessy.providers.*} -- a vendor key, a prefixed key, an explicit {@code enabled}, or a
 * custom id -- registers as {@code InferenceProvider} beans, re-run as {@code
 * ApplicationContextRunner} rows against spec §12a of the named-providers design record.
 */
@DisplayName("The presets, and what lights them")
class InferenceProvidersAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(InferenceProvidersAutoConfiguration.class))
          .withBean(ObservationRegistry.class, ObservationRegistry::create);

  @Test
  void nothing_configured_registers_nothing() {
    runner.run(context -> assertThat(context.getBeansOfType(InferenceProvider.class)).isEmpty());
  }

  @Test
  void an_openai_key_registers_one_provider() {
    runner
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context -> {
              Map<String, InferenceProvider> providers =
                  context.getBeansOfType(InferenceProvider.class);
              assertThat(providers).containsOnlyKeys("openai");
              assertThat(providers.values()).isNotEmpty().allSatisfy(this::isObserved);
              assertThat(providers.get("openai").vendor()).isEqualTo("openai");
            });
  }

  /**
   * Observed whether or not anything is listening: with no registry bean at all in the context, the
   * registrar's own {@code getIfAvailable(() -> ObservationRegistry.NOOP)} fallback is what wraps
   * the provider, which costs a check per call and nothing else.
   */
  @Test
  void without_an_observation_registry_a_lit_preset_is_still_observed() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(InferenceProvidersAutoConfiguration.class))
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context ->
                assertThat(context.getBean(InferenceProvider.class))
                    .isInstanceOf(ObservedInferenceProvider.class));
  }

  @Test
  void three_keys_register_three_providers_none_chosen() {
    runner
        .withPropertyValues(
            "openai.api-key=sk-test", "anthropic.api-key=sk-test", "gemini.api-key=sk-test")
        .run(
            context ->
                assertThat(context.getBeansOfType(InferenceProvider.class))
                    .containsOnlyKeys("openai", "anthropic", "gemini"));
  }

  @Test
  void openai_and_gemini_keys_register_two_providers() {
    runner
        .withPropertyValues("openai.api-key=sk-test", "gemini.api-key=sk-test")
        .run(
            context ->
                assertThat(context.getBeansOfType(InferenceProvider.class))
                    .containsOnlyKeys("openai", "gemini"));
  }

  @Test
  void openai_and_xai_keys_register_two_chat_completions_providers_with_their_own_vendors() {
    runner
        .withPropertyValues("openai.api-key=sk-test", "xai.api-key=xai-test")
        .run(
            context -> {
              Map<String, InferenceProvider> providers =
                  context.getBeansOfType(InferenceProvider.class);
              assertThat(providers).containsOnlyKeys("openai", "xai");
              assertThat(providers.get("openai").vendor()).isEqualTo("openai");
              assertThat(providers.get("xai").vendor()).isEqualTo("x_ai");
            });
  }

  @Test
  void gemini_and_xai_keys_register_two_providers() {
    runner
        .withPropertyValues("gemini.api-key=sk-test", "xai.api-key=xai-test")
        .run(
            context ->
                assertThat(context.getBeansOfType(InferenceProvider.class))
                    .containsOnlyKeys("gemini", "xai"));
  }

  @Test
  void either_gemini_key_registers_one_gemini_provider() {
    runner
        .withPropertyValues("gemini.api-key=a", "google.api-key=b")
        .run(
            context ->
                assertThat(context.getBeansOfType(InferenceProvider.class))
                    .containsOnlyKeys("gemini"));
  }

  @Test
  void the_prefixed_form_of_a_key_registers_the_preset() {
    runner
        .withPropertyValues("nessy.providers.xai.api-key=xai-test")
        .run(
            context ->
                assertThat(context.getBeansOfType(InferenceProvider.class))
                    .containsOnlyKeys("xai"));
  }

  @Test
  void the_openai_base_url_property_overrides_the_openai_preset_endpoint() {
    runner
        .withPropertyValues("openai.api-key=sk-test", "openai.base-url=http://localhost:1234/v1")
        .run(context -> assertThat(context.getBeansOfType(InferenceProvider.class)).hasSize(1));
  }

  @Test
  void a_custom_provider_with_a_wire_url_and_key_is_registered_with_the_wires_own_vendor() {
    runner
        .withPropertyValues(
            "nessy.providers.mine.wire=chat-completions",
            "nessy.providers.mine.base-url=https://g/v1",
            "nessy.providers.mine.api-key=k")
        .run(
            context -> {
              Map<String, InferenceProvider> providers =
                  context.getBeansOfType(InferenceProvider.class);
              assertThat(providers).containsOnlyKeys("mine");
              assertThat(providers.get("mine").vendor()).isEqualTo("openai");
            });
  }

  @Test
  void a_custom_provider_with_no_wire_fails_to_start_naming_it() {
    runner
        .withPropertyValues("nessy.providers.mine.base-url=https://g/v1")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void a_bogus_wire_value_fails_to_start_naming_the_allowed_values() {
    runner
        .withPropertyValues("nessy.providers.xai.wire=bogus")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void the_lmstudio_preset_registers_only_when_turned_on() {
    runner
        .withPropertyValues("nessy.providers.lmstudio.enabled=true")
        .run(
            context ->
                assertThat(context.getBeansOfType(InferenceProvider.class))
                    .containsOnlyKeys("lmstudio"));
  }

  @Test
  void an_application_bean_is_not_backed_off_beside_a_preset() {
    runner
        .withUserConfiguration(AScriptedProvider.class)
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context -> {
              Map<String, InferenceProvider> providers =
                  context.getBeansOfType(InferenceProvider.class);
              assertThat(providers).containsOnlyKeys("scripted", "openai");
              assertThat(providers.get("scripted")).isSameAs(AScriptedProvider.INSTANCE);
            });
  }

  // ---- Review Focus -------------------------------------------------------------------------

  @Test
  void a_blank_key_does_not_light_a_preset() {
    runner
        .withPropertyValues("openai.api-key=")
        .run(context -> assertThat(context.getBeansOfType(InferenceProvider.class)).isEmpty());
  }

  @Test
  void a_lit_preset_whose_adapter_is_absent_is_skipped() {
    runner
        .withClassLoader(new FilteredClassLoader(AnthropicInferenceProvider.class))
        .withPropertyValues("anthropic.api-key=sk-test")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansOfType(InferenceProvider.class))
                  .doesNotContainKey("anthropic");
            });
  }

  @Test
  void the_environment_variable_form_of_a_prefixed_key_lights_the_preset() {
    runner
        .withInitializer(
            context ->
                context
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(
                        new SystemEnvironmentPropertySource(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of("NESSY_PROVIDERS_XAI_APIKEY", "xai-test"))))
        .run(
            context ->
                assertThat(context.getBeansOfType(InferenceProvider.class))
                    .containsOnlyKeys("xai"));
  }

  @Test
  void a_bean_named_like_a_lit_preset_fails() {
    runner
        .withUserConfiguration(AnOpenAiNamedProvider.class)
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasMessageContaining("'openai'");
            });
  }

  private void isObserved(InferenceProvider provider) {
    assertThat(provider).isInstanceOf(ObservedInferenceProvider.class);
  }

  @Configuration(proxyBeanMethods = false)
  static class AScriptedProvider {

    static final InferenceProvider INSTANCE =
        (request, narrator) -> new InferenceResult.Refusal("scripted provider answers nothing");

    @Bean
    InferenceProvider scripted() {
      return INSTANCE;
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AnOpenAiNamedProvider {

    @Bean
    InferenceProvider openai() {
      return (request, narrator) -> new InferenceResult.Refusal("never called");
    }
  }
}
