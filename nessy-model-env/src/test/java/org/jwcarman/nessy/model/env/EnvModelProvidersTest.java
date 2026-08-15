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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.model.anthropic.AnthropicModelProvider;
import org.jwcarman.nessy.model.openai.OpenAiModelProvider;
import org.jwcarman.nessy.spi.model.ModelProvider;

/**
 * Drives {@link EnvModelProviders#fromEnv(Map)} entirely through the env-map seam — no real
 * environment variable, no network — the same offline shape {@code ConsoleReplTest} drives {@code
 * ConsoleRepl} through its reader/writer seam.
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

    @Test
    void breaks_the_tie_toward_openai_when_nessy_provider_says_so() {
      ModelProvider provider =
          EnvModelProviders.fromEnv(
              Map.of(
                  "ANTHROPIC_API_KEY", "fake-anthropic-key",
                  "OPENAI_API_KEY", "fake-openai-key",
                  "NESSY_PROVIDER", "openai"));

      assertThat(provider).isInstanceOf(OpenAiModelProvider.class);
    }

    @Test
    void defaults_to_anthropic_and_prints_a_one_line_notice_when_nessy_provider_is_unset() {
      ByteArrayOutputStream captured = new ByteArrayOutputStream();
      PrintStream originalErr = System.err;
      System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
      ModelProvider provider;
      try {
        provider =
            EnvModelProviders.fromEnv(
                Map.of(
                    "ANTHROPIC_API_KEY",
                    "fake-anthropic-key",
                    "OPENAI_API_KEY",
                    "fake-openai-key"));
      } finally {
        System.setErr(originalErr);
      }

      assertThat(provider).isInstanceOf(AnthropicModelProvider.class);
      String notice = captured.toString(StandardCharsets.UTF_8);
      assertThat(notice).isNotEmpty();
      assertThat(notice.lines().count()).isEqualTo(1);
      assertThat(notice).containsIgnoringCase("anthropic");
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
