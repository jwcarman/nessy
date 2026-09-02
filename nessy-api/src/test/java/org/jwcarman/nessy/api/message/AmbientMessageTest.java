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
package org.jwcarman.nessy.api.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.block.AmbientContentBlock;
import org.jwcarman.nessy.api.block.TextBlock;

/**
 * Background an agent is shown but never remembers.
 *
 * <p>The kind rule is a SAFETY rule rather than a style one: an adapter may interpolate a kind into
 * markup, so an unconstrained one could write structure into a prompt. Checking it here is what
 * lets every adapter skip escaping it.
 */
@DisplayName("Background assembled at recall")
class AmbientMessageTest {

  private static List<AmbientContentBlock> something() {
    return List.of(new TextBlock("the plan"));
  }

  @Nested
  class Kinds {

    @Test
    void a_lowercase_kebab_case_kind_is_accepted() {
      assertThat(new AmbientMessage("plan", something()).kind()).isEqualTo("plan");
      assertThat(new AmbientMessage("saved-notes", something()).kind()).isEqualTo("saved-notes");
      assertThat(new AmbientMessage("plan2", something()).kind()).isEqualTo("plan2");
    }

    @Test
    @DisplayName("anything an adapter would have to escape is refused")
    void a_kind_that_could_write_markup_is_refused() {
      List<AmbientContentBlock> content = something();

      assertThatThrownBy(() -> new AmbientMessage("<script>", content))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("kebab-case");
    }

    @Test
    void an_uppercase_kind_is_refused() {
      List<AmbientContentBlock> content = something();

      assertThatThrownBy(() -> new AmbientMessage("Plan", content))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_kind_starting_with_a_digit_is_refused() {
      List<AmbientContentBlock> content = something();

      assertThatThrownBy(() -> new AmbientMessage("2plans", content))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void an_empty_kind_is_refused() {
      List<AmbientContentBlock> content = something();

      assertThatThrownBy(() -> new AmbientMessage("", content))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class Content {

    @Test
    @DisplayName("say nothing by adding nothing, not by adding an empty message")
    void empty_content_is_refused() {
      List<AmbientContentBlock> nothing = List.of();

      assertThatThrownBy(() -> new AmbientMessage("plan", nothing))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be empty");
    }

    @Test
    void the_content_is_defensively_copied() {
      var mutable = new java.util.ArrayList<AmbientContentBlock>(something());
      var ambient = new AmbientMessage("plan", mutable);

      mutable.clear();

      assertThat(ambient.content()).hasSize(1);
    }
  }
}
