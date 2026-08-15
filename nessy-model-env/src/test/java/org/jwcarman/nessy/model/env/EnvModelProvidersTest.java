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
import java.util.function.Supplier;
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

  /** The provider {@link #capturingStderr} returned, paired with whatever it wrote to stderr. */
  private record Captured(ModelProvider provider, String stderr) {}

  /**
   * Runs {@code call} with {@link System#err} redirected, so both halves of "the explicit choice is
   * silent, only the default notices" can be asserted the same deliberate way: emptiness proven by
   * inspecting the channel, not merely by an assertion that never happened to check it.
   */
  private static Captured capturingStderr(Supplier<ModelProvider> call) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    PrintStream originalErr = System.err;
    System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
    try {
      return new Captured(call.get(), buffer.toString(StandardCharsets.UTF_8));
    } finally {
      System.setErr(originalErr);
    }
  }

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
    void an_explicit_openai_choice_is_silent_even_with_mixed_case() {
      Map<String, String> env =
          Map.of(
              "ANTHROPIC_API_KEY", "fake-anthropic-key",
              "OPENAI_API_KEY", "fake-openai-key",
              // Mixed case, on purpose: the tiebreak reads NESSY_PROVIDER case-insensitively.
              "NESSY_PROVIDER", "OpenAI");

      Captured captured = capturingStderr(() -> EnvModelProviders.fromEnv(env));

      assertThat(captured.provider()).isInstanceOf(OpenAiModelProvider.class);
      assertThat(captured.stderr()).isEmpty();
    }

    @Test
    void an_explicit_anthropic_choice_is_silent() {
      Map<String, String> env =
          Map.of(
              "ANTHROPIC_API_KEY", "fake-anthropic-key",
              "OPENAI_API_KEY", "fake-openai-key",
              "NESSY_PROVIDER", "anthropic");

      Captured captured = capturingStderr(() -> EnvModelProviders.fromEnv(env));

      assertThat(captured.provider()).isInstanceOf(AnthropicModelProvider.class);
      assertThat(captured.stderr()).isEmpty();
    }

    @Test
    void defaults_to_anthropic_and_prints_a_one_line_notice_when_nessy_provider_is_unset() {
      Map<String, String> env =
          Map.of(
              "ANTHROPIC_API_KEY", "fake-anthropic-key",
              "OPENAI_API_KEY", "fake-openai-key");

      Captured captured = capturingStderr(() -> EnvModelProviders.fromEnv(env));

      assertThat(captured.provider()).isInstanceOf(AnthropicModelProvider.class);
      assertThat(captured.stderr()).isNotEmpty();
      assertThat(captured.stderr().lines().count()).isEqualTo(1);
      assertThat(captured.stderr()).containsIgnoringCase("anthropic");
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
