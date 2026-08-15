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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.model.anthropic.AnthropicModelProvider;
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
  }
}
