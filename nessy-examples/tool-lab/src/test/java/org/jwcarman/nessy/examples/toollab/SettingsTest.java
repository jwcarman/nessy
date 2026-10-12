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
package org.jwcarman.nessy.examples.toollab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SettingsTest {

  private static Settings parse(String... args) {
    return Settings.parse(args);
  }

  @Nested
  class Reading_the_command_line {

    @Test
    void needs_only_a_provider_and_a_model_and_defaults_the_rest() {
      Settings settings = parse("--provider", "anthropic", "--model", "claude-sonnet-5-5");

      assertThat(settings.provider()).isEqualTo("anthropic");
      assertThat(settings.model()).isEqualTo("claude-sonnet-5-5");
      assertThat(settings.variants()).containsExactly(Variant.V0, Variant.V1, Variant.V2);
      assertThat(settings.size()).isEqualTo(100);
      assertThat(settings.tasks()).isEqualTo(10);
      assertThat(settings.runs()).isEqualTo(1);
      assertThat(settings.out()).isEqualTo(Path.of("."));
      assertThat(settings.maxTokens()).isEqualTo(2048);
      assertThat(settings.thinking()).isNull();
      assertThat(settings.probeUnoffered()).isFalse();
    }

    @Test
    void takes_every_option() {
      Settings settings =
          parse(
              "--provider",
              "lmstudio",
              "--model",
              "qwen/qwen3-coder-30b",
              "--variants",
              "v2,v0",
              "--size",
              "500",
              "--tasks",
              "20",
              "--runs",
              "3",
              "--out",
              "/tmp/results",
              "--base-url",
              "http://host:1234/v1",
              "--max-tokens",
              "4096");

      assertThat(settings.variants()).containsExactly(Variant.V2, Variant.V0);
      assertThat(settings.size()).isEqualTo(500);
      assertThat(settings.tasks()).isEqualTo(20);
      assertThat(settings.runs()).isEqualTo(3);
      assertThat(settings.out()).isEqualTo(Path.of("/tmp/results"));
      assertThat(settings.baseUrl()).isEqualTo("http://host:1234/v1");
      assertThat(settings.maxTokens()).isEqualTo(4096);
    }

    @Test
    void takes_the_probe_flag_without_a_value() {
      Settings settings = parse("--provider", "openai", "--model", "gpt-5", "--probe-unoffered");

      assertThat(settings.probeUnoffered()).isTrue();
    }

    @Test
    void takes_thinking_for_anthropic() {
      Settings settings =
          parse("--provider", "anthropic", "--model", "m", "--thinking", "between_tools");

      assertThat(settings.thinking()).isEqualTo("between_tools");
    }
  }

  @Nested
  class Refusing_what_it_cannot_run {

    @Test
    void a_missing_provider() {
      assertThatThrownBy(() -> parse("--model", "m"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--provider");
    }

    @Test
    void an_unknown_provider_naming_the_choices() {
      assertThatThrownBy(() -> parse("--provider", "bedrock", "--model", "m"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("bedrock")
          .hasMessageContaining("anthropic");
    }

    @Test
    void an_unknown_variant() {
      assertThatThrownBy(() -> parse("--provider", "openai", "--model", "m", "--variants", "v9"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("v9");
    }

    @Test
    void an_unknown_option() {
      assertThatThrownBy(() -> parse("--provider", "openai", "--model", "m", "--colour", "red"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--colour");
    }

    @Test
    void an_option_with_no_value() {
      assertThatThrownBy(() -> parse("--provider", "openai", "--model"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--model needs a value");
    }

    @Test
    void a_number_that_is_not_a_positive_integer() {
      assertThatThrownBy(() -> parse("--provider", "openai", "--model", "m", "--runs", "0"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--runs");
      assertThatThrownBy(() -> parse("--provider", "openai", "--model", "m", "--size", "many"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--size");
    }

    @Test
    void a_base_url_for_a_provider_that_is_not_lmstudio() {
      assertThatThrownBy(
              () -> parse("--provider", "openai", "--model", "m", "--base-url", "http://x"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--base-url applies only to --provider lmstudio");
    }

    @Test
    void thinking_for_a_provider_that_is_not_anthropic() {
      assertThatThrownBy(
              () -> parse("--provider", "openai", "--model", "m", "--thinking", "adaptive"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--thinking applies only to --provider anthropic");
    }

    @Test
    void more_tasks_than_the_file_holds() {
      int tooMany = Tasks.load().size() + 1;

      assertThatThrownBy(
              () -> parse("--provider", "openai", "--model", "m", "--tasks", "" + tooMany))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--tasks");
    }

    @Test
    void a_catalog_smaller_than_the_hand_written_tools() {
      assertThatThrownBy(() -> parse("--provider", "openai", "--model", "m", "--size", "3"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--size");
    }
  }

  @Test
  void the_providers_it_names_are_the_ones_the_lab_can_build() {
    assertThat(ToolLab.PROVIDERS)
        .isEqualTo(List.of("anthropic", "openai", "xai", "gemini", "lmstudio"));
  }
}
