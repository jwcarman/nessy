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
package org.jwcarman.nessy.model.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.nessy.model.anthropic.AnthropicModelProvider;
import org.jwcarman.nessy.model.gemini.GeminiModelProvider;
import org.jwcarman.nessy.model.openai.OpenAiModelProvider;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.slf4j.LoggerFactory;

/**
 * Drives {@link EnvModelProviders#fromEnv(Map)} entirely through the env-map seam — no real
 * environment variable, no network — the same offline shape {@code ConsoleReplTest} drives {@code
 * ConsoleRepl} through its reader/writer seam. The default-provider notice is asserted through a
 * capturing {@link ListAppender} on {@link EnvModelProviders}'s own logger — the same house pattern
 * {@code AgentBuilderTest}'s {@code Memory_downgrade_warning} uses — rather than by redirecting
 * {@link System#err}, since the notice moved off that raw stream and onto a logger (java:S106).
 */
class EnvModelProvidersTest {

  @Nested
  class Only_the_anthropic_key_set {

    @Test
    void chooses_the_anthropic_provider() {
      ModelProvider provider =
          EnvModelProviders.fromEnv(Map.of("ANTHROPIC_API_KEY", "fake-anthropic-key"));

      assertThat(provider).isInstanceOf(AnthropicModelProvider.class);
    }
  }

  @Nested
  class Only_the_openai_key_set {

    @Test
    void chooses_the_openai_provider() {
      ModelProvider provider =
          EnvModelProviders.fromEnv(Map.of("OPENAI_API_KEY", "fake-openai-key"));

      assertThat(provider).isInstanceOf(OpenAiModelProvider.class);
    }
  }

  @Nested
  class Both_keys_set {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private Level originalLevel;

    @BeforeEach
    void wires_a_capturing_appender_onto_the_env_model_providers_logger() {
      logger = (Logger) LoggerFactory.getLogger(EnvModelProviders.class);
      originalLevel = logger.getLevel();
      logger.setLevel(Level.WARN);
      appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
    }

    @AfterEach
    void unwires_the_appender_and_restores_the_loggers_level() {
      logger.detachAppender(appender);
      logger.setLevel(originalLevel);
    }

    @Test
    void an_explicit_openai_choice_is_silent_even_with_mixed_case() {
      Map<String, String> env =
          Map.of(
              "ANTHROPIC_API_KEY", "fake-anthropic-key",
              "OPENAI_API_KEY", "fake-openai-key",
              // Mixed case, on purpose: the tiebreak reads NESSY_PROVIDER case-insensitively.
              "NESSY_PROVIDER", "OpenAI");

      ModelProvider provider = EnvModelProviders.fromEnv(env);

      assertThat(provider).isInstanceOf(OpenAiModelProvider.class);
      assertThat(appender.list).isEmpty();
    }

    @Test
    void an_explicit_anthropic_choice_is_silent() {
      Map<String, String> env =
          Map.of(
              "ANTHROPIC_API_KEY", "fake-anthropic-key",
              "OPENAI_API_KEY", "fake-openai-key",
              "NESSY_PROVIDER", "anthropic");

      ModelProvider provider = EnvModelProviders.fromEnv(env);

      assertThat(provider).isInstanceOf(AnthropicModelProvider.class);
      assertThat(appender.list).isEmpty();
    }

    @Test
    void defaults_to_anthropic_and_warns_once_when_nessy_provider_is_unset() {
      Map<String, String> env =
          Map.of(
              "ANTHROPIC_API_KEY", "fake-anthropic-key",
              "OPENAI_API_KEY", "fake-openai-key");

      ModelProvider provider = EnvModelProviders.fromEnv(env);

      assertThat(provider).isInstanceOf(AnthropicModelProvider.class);
      assertThat(appender.list).hasSize(1);
      ILoggingEvent event = appender.list.getFirst();
      assertThat(event.getLevel()).isEqualTo(Level.WARN);
      assertThat(event.getFormattedMessage()).containsIgnoringCase("anthropic");
    }

    @Test
    void defaults_to_anthropic_and_warns_once_when_nessy_provider_is_unrecognized() {
      Map<String, String> env =
          Map.of(
              "ANTHROPIC_API_KEY", "fake-anthropic-key",
              "OPENAI_API_KEY", "fake-openai-key",
              // Neither "anthropic" nor "openai" — the tiebreak's fallback arm, same as unset.
              "NESSY_PROVIDER", "gemini");

      ModelProvider provider = EnvModelProviders.fromEnv(env);

      assertThat(provider).isInstanceOf(AnthropicModelProvider.class);
      assertThat(appender.list).hasSize(1);
      ILoggingEvent event = appender.list.getFirst();
      assertThat(event.getLevel()).isEqualTo(Level.WARN);
      assertThat(event.getFormattedMessage()).containsIgnoringCase("anthropic");
    }
  }

  @Nested
  class Neither_key_set {

    @Test
    void fails_noisy_naming_every_variable_it_checked() {
      assertThatThrownBy(() -> EnvModelProviders.fromEnv(Map.of()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ANTHROPIC_API_KEY")
          .hasMessageContaining("OPENAI_API_KEY")
          .hasMessageContaining("NESSY_PROVIDER");
    }

    @Test
    void fails_noisy_naming_the_gemini_and_xai_variables_too() {
      assertThatThrownBy(() -> EnvModelProviders.fromEnv(Map.of()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("GEMINI_API_KEY")
          .hasMessageContaining("GOOGLE_API_KEY")
          .hasMessageContaining("XAI_API_KEY");
    }
  }

  @Nested
  class Only_the_gemini_key_set {

    @Test
    void chooses_the_gemini_provider_via_gemini_api_key() {
      ModelProvider provider =
          EnvModelProviders.fromEnv(Map.of("GEMINI_API_KEY", "fake-gemini-key"));

      assertThat(provider).isInstanceOf(GeminiModelProvider.class);
    }

    @Test
    void chooses_the_gemini_provider_via_google_api_key() {
      ModelProvider provider =
          EnvModelProviders.fromEnv(Map.of("GOOGLE_API_KEY", "fake-google-key"));

      assertThat(provider).isInstanceOf(GeminiModelProvider.class);
    }

    @Test
    void a_google_api_key_alongside_a_gemini_api_key_still_builds_the_gemini_provider() {
      // GEMINI_API_KEY wins internally (GeminiModelProvider.Builder#fromEnv's own documented
      // pair order), but that resolved key is never exposed by an accessor to assert on directly
      // — the same offline limit OpenAiModelProviderTest documents for its own base URL/org
      // fields. What's verified here is the observable half: both variables set together still
      // resolves to exactly one Gemini provider, not an ambiguity.
      Map<String, String> env =
          Map.of(
              "GEMINI_API_KEY", "fake-gemini-key",
              "GOOGLE_API_KEY", "fake-google-key");

      ModelProvider provider = EnvModelProviders.fromEnv(env);

      assertThat(provider).isInstanceOf(GeminiModelProvider.class);
    }
  }

  @Nested
  class Only_the_xai_key_set {

    @Test
    void chooses_an_openai_provider_built_against_the_xai_base_url() {
      // XAI_API_KEY builds an OpenAiModelProvider pointed at https://api.x.ai/v1 (design §3):
      // Grok as a first-class env citizen with zero new provider code. The resolved base URL has
      // no accessor to assert against offline (OpenAiModelProviderTest documents the same limit
      // for its own baseUrl(...) builder calls) — what's verified here is that the single-key
      // path resolves to exactly one OpenAI-shaped provider without error.
      ModelProvider provider = EnvModelProviders.fromEnv(Map.of("XAI_API_KEY", "fake-xai-key"));

      assertThat(provider).isInstanceOf(OpenAiModelProvider.class);
    }
  }

  @Nested
  class The_openai_base_url_variable {

    @Test
    void is_honored_when_the_openai_key_is_the_only_key_present() {
      // §7 amendment: OPENAI_BASE_URL layers onto the OpenAI provider whenever OPENAI_API_KEY is
      // the chosen path — local runtimes (LM Studio, Ollama) and gateways (OpenRouter, Gemini's
      // OpenAI-compat endpoint) become zero-code env citizens. Same offline limit as above: no
      // accessor to assert the resolved base URL against, so this proves the value is accepted
      // without error rather than that it was actually applied.
      Map<String, String> env =
          Map.of(
              "OPENAI_API_KEY", "lm-studio",
              "OPENAI_BASE_URL", "http://127.0.0.1:1234/v1");

      ModelProvider provider = EnvModelProviders.fromEnv(env);

      assertThat(provider).isInstanceOf(OpenAiModelProvider.class);
    }
  }

  /**
   * Every ambiguity/tiebreak combination the provider-expansion design adds beyond the original
   * anthropic/openai pair ({@link Both_keys_set} keeps covering that original pair unchanged) —
   * parameterized per the house rule banked from an earlier task: a third same-shaped test bundles
   * rather than repeats. Two providers building the same {@link OpenAiModelProvider} class (openai
   * and xai) can't be told apart by type, so the tiebreak's own observable branch — silent
   * (explicit match found) vs. one WARN logged (fell through to the default order) — is what these
   * assertions key on; the resolved type is asserted too wherever the pairing makes it
   * distinguishable.
   */
  @Nested
  class Multiple_keys_set {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private Level originalLevel;

    @BeforeEach
    void wires_a_capturing_appender_onto_the_env_model_providers_logger() {
      logger = (Logger) LoggerFactory.getLogger(EnvModelProviders.class);
      originalLevel = logger.getLevel();
      logger.setLevel(Level.WARN);
      appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
    }

    @AfterEach
    void unwires_the_appender_and_restores_the_loggers_level() {
      logger.detachAppender(appender);
      logger.setLevel(originalLevel);
    }

    static Stream<Arguments> default_order_cases() {
      return Stream.of(
          Arguments.of(
              Map.of("ANTHROPIC_API_KEY", "k1", "GEMINI_API_KEY", "k2"),
              AnthropicModelProvider.class),
          Arguments.of(
              Map.of("ANTHROPIC_API_KEY", "k1", "XAI_API_KEY", "k2"), AnthropicModelProvider.class),
          Arguments.of(
              Map.of("OPENAI_API_KEY", "k1", "GEMINI_API_KEY", "k2"), OpenAiModelProvider.class),
          Arguments.of(
              Map.of("OPENAI_API_KEY", "k1", "XAI_API_KEY", "k2"), OpenAiModelProvider.class),
          Arguments.of(
              Map.of("GEMINI_API_KEY", "k1", "XAI_API_KEY", "k2"), GeminiModelProvider.class),
          Arguments.of(
              Map.of(
                  "ANTHROPIC_API_KEY", "k1",
                  "OPENAI_API_KEY", "k2",
                  "GEMINI_API_KEY", "k3",
                  "XAI_API_KEY", "k4"),
              AnthropicModelProvider.class));
    }

    @ParameterizedTest
    @MethodSource("default_order_cases")
    void with_no_nessy_provider_set_the_first_key_in_precedence_order_wins_and_warns_once(
        Map<String, String> env, Class<? extends ModelProvider> expectedType) {
      ModelProvider provider = EnvModelProviders.fromEnv(env);

      assertThat(provider).isInstanceOf(expectedType);
      assertThat(appender.list).hasSize(1);
      assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);
    }

    static Stream<Arguments> explicit_choice_cases() {
      return Stream.of(
          // gemini explicitly beats the anthropic default.
          Arguments.of(
              Map.of("ANTHROPIC_API_KEY", "k1", "GEMINI_API_KEY", "k2"),
              "gemini",
              GeminiModelProvider.class),
          // gemini explicitly beats the openai default.
          Arguments.of(
              Map.of("OPENAI_API_KEY", "k1", "GEMINI_API_KEY", "k2"),
              "GEMINI",
              GeminiModelProvider.class),
          // the "grok" alias explicitly beats the gemini default.
          Arguments.of(
              Map.of("GEMINI_API_KEY", "k1", "XAI_API_KEY", "k2"),
              "grok",
              OpenAiModelProvider.class),
          // xai explicitly beats the anthropic default (still OpenAiModelProvider-typed, but
          // distinguishable from the would-be anthropic default by type all the same).
          Arguments.of(
              Map.of("ANTHROPIC_API_KEY", "k1", "XAI_API_KEY", "k2"),
              "xai",
              OpenAiModelProvider.class));
    }

    @ParameterizedTest
    @MethodSource("explicit_choice_cases")
    void naming_a_present_key_explicitly_chooses_it_silently(
        Map<String, String> env, String preference, Class<? extends ModelProvider> expectedType) {
      var envWithPreference = new HashMap<>(env);
      envWithPreference.put("NESSY_PROVIDER", preference);

      ModelProvider provider = EnvModelProviders.fromEnv(envWithPreference);

      assertThat(provider).isInstanceOf(expectedType);
      assertThat(appender.list).isEmpty();
    }

    @Test
    void openai_versus_xai_is_only_distinguishable_by_the_tiebreak_s_own_silence() {
      // Both keys build an OpenAiModelProvider, so type can't prove which key was actually
      // embedded (no accessor — see Only_the_xai_key_set's note). What IS observable: naming
      // "xai" explicitly takes the silent explicit-match branch rather than the
      // warn-and-fall-back-to-openai default branch.
      Map<String, String> env =
          Map.of(
              "OPENAI_API_KEY", "k1",
              "XAI_API_KEY", "k2",
              "NESSY_PROVIDER", "xai");

      ModelProvider provider = EnvModelProviders.fromEnv(env);

      assertThat(provider).isInstanceOf(OpenAiModelProvider.class);
      assertThat(appender.list).isEmpty();
    }
  }

  /**
   * {@link EnvModelProviders#select(Map)} — the {@link EnvModelProviders.Selection}-returning
   * overload demos use for their banners — layered on top of the same {@code fromEnv(Map)}
   * machinery: same provider choice, plus the provider name and the model that goes with it.
   */
  @Nested
  class Selecting {

    @Test
    void names_and_defaults_the_anthropic_model_when_only_its_key_is_set() {
      EnvModelProviders.Selection selection =
          EnvModelProviders.select(Map.of("ANTHROPIC_API_KEY", "fake-anthropic-key"));

      assertThat(selection.provider()).isInstanceOf(AnthropicModelProvider.class);
      assertThat(selection.providerName()).isEqualTo("anthropic");
      assertThat(selection.model()).isEqualTo("claude-haiku-4-5-20251001");
    }

    @Test
    void names_and_defaults_the_openai_model_when_only_its_key_is_set() {
      EnvModelProviders.Selection selection =
          EnvModelProviders.select(Map.of("OPENAI_API_KEY", "fake-openai-key"));

      assertThat(selection.provider()).isInstanceOf(OpenAiModelProvider.class);
      assertThat(selection.providerName()).isEqualTo("openai");
      assertThat(selection.model()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void names_and_defaults_the_gemini_model_when_only_its_key_is_set() {
      EnvModelProviders.Selection selection =
          EnvModelProviders.select(Map.of("GEMINI_API_KEY", "fake-gemini-key"));

      assertThat(selection.provider()).isInstanceOf(GeminiModelProvider.class);
      assertThat(selection.providerName()).isEqualTo("gemini");
      assertThat(selection.model()).isEqualTo("gemini-3.6-flash");
    }

    @Test
    void names_and_defaults_the_xai_model_when_only_its_key_is_set() {
      EnvModelProviders.Selection selection =
          EnvModelProviders.select(Map.of("XAI_API_KEY", "fake-xai-key"));

      assertThat(selection.provider()).isInstanceOf(OpenAiModelProvider.class);
      assertThat(selection.providerName()).isEqualTo("xai");
      assertThat(selection.model()).isEqualTo("grok-4.6");
    }

    @Test
    void a_non_blank_nessy_model_wins_over_the_provider_default() {
      Map<String, String> env =
          Map.of(
              "ANTHROPIC_API_KEY", "fake-anthropic-key",
              "NESSY_MODEL", "claude-opus-4-1-20260805");

      EnvModelProviders.Selection selection = EnvModelProviders.select(env);

      assertThat(selection.providerName()).isEqualTo("anthropic");
      assertThat(selection.model()).isEqualTo("claude-opus-4-1-20260805");
    }

    @Test
    void a_blank_nessy_model_is_ignored_in_favor_of_the_provider_default() {
      Map<String, String> env =
          Map.of(
              "OPENAI_API_KEY", "fake-openai-key",
              "NESSY_MODEL", "   ");

      EnvModelProviders.Selection selection = EnvModelProviders.select(env);

      assertThat(selection.model()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void nessy_model_wins_even_when_multiple_keys_are_set_and_a_tiebreak_runs() {
      Map<String, String> env =
          Map.of(
              "ANTHROPIC_API_KEY", "fake-anthropic-key",
              "OPENAI_API_KEY", "fake-openai-key",
              "NESSY_PROVIDER", "openai",
              "NESSY_MODEL", "gpt-5-nano");

      EnvModelProviders.Selection selection = EnvModelProviders.select(env);

      assertThat(selection.providerName()).isEqualTo("openai");
      assertThat(selection.model()).isEqualTo("gpt-5-nano");
    }

    @Test
    void rejects_a_null_provider() {
      assertThatThrownBy(() -> new EnvModelProviders.Selection(null, "anthropic", "a-model"))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("provider must not be null");
    }

    @Test
    void rejects_a_null_provider_name() {
      ModelProvider provider =
          AnthropicModelProvider.builder().apiKey("fake-anthropic-key").build();

      assertThatThrownBy(() -> new EnvModelProviders.Selection(provider, null, "a-model"))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("providerName must not be null");
    }

    @Test
    void rejects_a_null_model() {
      ModelProvider provider =
          AnthropicModelProvider.builder().apiKey("fake-anthropic-key").build();

      assertThatThrownBy(() -> new EnvModelProviders.Selection(provider, "anthropic", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("model must not be null");
    }
  }
}
