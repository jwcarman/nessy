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
package org.jwcarman.nessy.console;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AnsiTest {

  @AfterEach
  void clear_the_override_seam() {
    Ansi.overrideEnabled(null);
  }

  @Nested
  class When_styling_is_enabled {

    @Test
    void bold_wraps_the_text_in_the_bold_sgr_code_and_a_reset() {
      Ansi.overrideEnabled(true);

      assertThat(Ansi.bold("hi")).isEqualTo("[1mhi[0m");
    }

    @Test
    void dim_wraps_the_text_in_the_dim_sgr_code_and_a_reset() {
      Ansi.overrideEnabled(true);

      assertThat(Ansi.dim("hi")).isEqualTo("[2mhi[0m");
    }

    @Test
    void italic_wraps_the_text_in_the_italic_sgr_code_and_a_reset() {
      Ansi.overrideEnabled(true);

      assertThat(Ansi.italic("hi")).isEqualTo("[3mhi[0m");
    }

    @Test
    void cyan_wraps_the_text_in_the_cyan_sgr_code_and_a_reset() {
      Ansi.overrideEnabled(true);

      assertThat(Ansi.cyan("hi")).isEqualTo("[36mhi[0m");
    }

    @Test
    void yellow_wraps_the_text_in_the_yellow_sgr_code_and_a_reset() {
      Ansi.overrideEnabled(true);

      assertThat(Ansi.yellow("hi")).isEqualTo("[33mhi[0m");
    }

    @Test
    void red_wraps_the_text_in_the_red_sgr_code_and_a_reset() {
      Ansi.overrideEnabled(true);

      assertThat(Ansi.red("hi")).isEqualTo("[31mhi[0m");
    }

    @Test
    void green_wraps_the_text_in_the_green_sgr_code_and_a_reset() {
      Ansi.overrideEnabled(true);

      assertThat(Ansi.green("hi")).isEqualTo("[32mhi[0m");
    }

    @Test
    void strikethrough_wraps_the_text_in_the_strikethrough_sgr_code_and_a_reset() {
      Ansi.overrideEnabled(true);

      assertThat(Ansi.strikethrough("hi")).isEqualTo("[9mhi[0m");
    }
  }

  @Nested
  class When_styling_is_disabled {

    @Test
    void every_wrapper_is_an_exact_pass_through() {
      Ansi.overrideEnabled(false);

      assertThat(Ansi.bold("hi")).isEqualTo("hi");
      assertThat(Ansi.dim("hi")).isEqualTo("hi");
      assertThat(Ansi.italic("hi")).isEqualTo("hi");
      assertThat(Ansi.cyan("hi")).isEqualTo("hi");
      assertThat(Ansi.yellow("hi")).isEqualTo("hi");
      assertThat(Ansi.red("hi")).isEqualTo("hi");
      assertThat(Ansi.green("hi")).isEqualTo("hi");
    }

    @Test
    void strikethrough_is_an_exact_pass_through_too() {
      Ansi.overrideEnabled(false);

      assertThat(Ansi.strikethrough("hi")).isEqualTo("hi");
    }
  }

  @Nested
  class The_enabled_check {

    @Test
    void reports_disabled_when_there_is_no_console() {
      Ansi.overrideEnabled(false);

      assertThat(Ansi.enabled()).isFalse();
    }

    @Test
    void reports_enabled_when_the_override_says_so() {
      Ansi.overrideEnabled(true);

      assertThat(Ansi.enabled()).isTrue();
    }
  }
}
