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
package org.jwcarman.nessy.engine.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Stringifier;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InputLabelsTest {

  private static final AgentType TYPE = new AgentType("desk");

  @Nested
  class With_no_label_configured {

    @Test
    void an_input_is_called_by_its_simple_class_name() {
      InputLabels<Object> labels = new InputLabels<>(TYPE, Optional.empty());

      assertThat(labels.of("hello")).isEqualTo("String");
    }

    @Test
    void an_anonymous_class_is_called_by_its_full_class_name_since_its_simple_name_is_empty() {
      Object anonymous = new Object() {};
      InputLabels<Object> labels = new InputLabels<>(TYPE, Optional.empty());

      assertThat(labels.of(anonymous)).isNotBlank().isEqualTo(anonymous.getClass().getName());
    }
  }

  @Nested
  class With_a_label_configured {

    @Test
    void its_text_is_the_label() {
      InputLabels<String> labels = new InputLabels<>(TYPE, Optional.of(said -> "Greeting"));

      assertThat(labels.of("hello")).isEqualTo("Greeting");
    }

    @Test
    void a_multi_line_label_is_stored_as_one_line() {
      InputLabels<String> labels =
          new InputLabels<>(TYPE, Optional.of(said -> "Invoice\n  4711\r\nfor   Acme"));

      assertThat(labels.of("hello")).isEqualTo("Invoice 4711 for Acme");
    }

    @Test
    void an_over_long_label_is_cut_to_256_characters() {
      Stringifier<String> endless = said -> "x".repeat(1000);
      InputLabels<String> labels = new InputLabels<>(TYPE, Optional.of(endless));

      assertThat(labels.of("hello")).hasSize(256).isEqualTo("x".repeat(253) + "...");
    }

    @Test
    void a_label_that_throws_falls_back_to_the_class_name() {
      InputLabels<String> labels =
          new InputLabels<>(
              TYPE,
              Optional.of(
                  said -> {
                    throw new IllegalStateException("no");
                  }));

      assertThat(labels.of("hello")).isEqualTo("String");
    }

    @Test
    void a_null_or_blank_label_falls_back_to_the_class_name() {
      InputLabels<String> nothing = new InputLabels<>(TYPE, Optional.of(said -> null));
      InputLabels<String> blank = new InputLabels<>(TYPE, Optional.of(said -> " \n "));

      assertThat(nothing.of("hello")).isEqualTo("String");
      assertThat(blank.of("hello")).isEqualTo("String");
    }
  }
}
