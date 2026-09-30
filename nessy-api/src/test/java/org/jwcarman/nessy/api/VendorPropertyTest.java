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
package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A vendor property is a name and a type. These are the seven things one promises: it reads what it
 * is given, writes what it reads, and says what it accepted when it refuses.
 */
class VendorPropertyTest {

  enum Color {
    RED("red"),
    DARK_BLUE("dark-blue");

    private final String spelling;

    Color(String spelling) {
      this.spelling = spelling;
    }

    String spelling() {
      return spelling;
    }
  }

  @Nested
  class An_integer {

    private final VendorProperty<Integer> property = VendorProperty.ofInteger("acme.count");

    @Test
    void round_trips_through_its_text() {
      assertThat(property.format(1024)).isEqualTo("1024");
      assertThat(property.in(Map.of("acme.count", "1024"))).contains(1024);
    }

    @Test
    void is_refused_when_it_is_not_a_whole_number() {
      Map<String, String> properties = Map.of("acme.count", "12abc");

      assertThatThrownBy(() -> property.in(properties))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property 'acme.count' must be an integer, was '12abc'");
    }
  }

  @Nested
  class A_boolean {

    private final VendorProperty<Boolean> property = VendorProperty.ofBoolean("acme.flag");

    @Test
    void round_trips_through_its_text() {
      assertThat(property.format(true)).isEqualTo("true");
      assertThat(property.in(Map.of("acme.flag", "true"))).contains(true);
      assertThat(property.in(Map.of("acme.flag", "false"))).contains(false);
    }

    @Test
    void is_case_sensitive() {
      Map<String, String> properties = Map.of("acme.flag", "TRUE");

      assertThatThrownBy(() -> property.in(properties))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property 'acme.flag' must be true or false, was 'TRUE'");
    }
  }

  @Nested
  class A_float {

    private final VendorProperty<Float> property = VendorProperty.ofFloat("acme.ratio");

    @Test
    void round_trips_through_its_text() {
      assertThat(property.format(0.5f)).isEqualTo("0.5");
      assertThat(property.in(Map.of("acme.ratio", "0.5"))).contains(0.5f);
    }

    @Test
    void is_refused_when_it_is_not_a_number() {
      Map<String, String> properties = Map.of("acme.ratio", "warm");

      assertThatThrownBy(() -> property.in(properties))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property 'acme.ratio' must be a number, was 'warm'");
    }

    @Test
    void is_refused_when_it_is_not_finite() {
      Map<String, String> properties = Map.of("acme.ratio", "NaN");

      assertThatThrownBy(() -> property.in(properties))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property 'acme.ratio' must be a number, was 'NaN'");
    }
  }

  @Nested
  class An_enum {

    private final VendorProperty<Color> property =
        VendorProperty.ofEnum("acme.color", Color.class, Color::spelling);

    @Test
    void round_trips_through_its_spelling() {
      assertThat(property.format(Color.DARK_BLUE)).isEqualTo("dark-blue");
      assertThat(property.in(Map.of("acme.color", "dark-blue"))).contains(Color.DARK_BLUE);
    }

    @Test
    void lists_its_spellings_in_declaration_order_when_refusing() {
      Map<String, String> properties = Map.of("acme.color", "green");

      assertThatThrownBy(() -> property.in(properties))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property 'acme.color' must be one of [red, dark-blue], was 'green'");
    }

    @Test
    void matches_the_spelling_exactly() {
      Map<String, String> properties = Map.of("acme.color", "RED");

      assertThatThrownBy(() -> property.in(properties))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("was 'RED'");
    }
  }

  @Nested
  class Any_property {

    @Test
    void is_empty_when_the_map_does_not_carry_it() {
      VendorProperty<Integer> property = VendorProperty.ofInteger("acme.count");

      assertThat(property.in(Map.of("acme.other", "1"))).isEmpty();
    }

    @Test
    void carries_its_full_name() {
      assertThat(VendorProperty.ofInteger("acme.count").name()).isEqualTo("acme.count");
    }

    @Test
    void parses_text_directly_and_says_what_it_accepted_when_refusing() {
      VendorProperty<Integer> property = VendorProperty.ofInteger("acme.count");

      assertThat(property.parse("7")).isEqualTo(7);
      assertThatThrownBy(() -> property.parse("seven"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("must be an integer, was 'seven'");
    }

    @Test
    void refuses_to_format_nothing() {
      VendorProperty<Integer> property = VendorProperty.ofInteger("acme.count");

      assertThatThrownBy(() -> property.format(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void is_named_for_its_adapter() {
      assertThatThrownBy(() -> VendorProperty.ofInteger("count"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("was 'count'");
      assertThatThrownBy(() -> VendorProperty.ofInteger(".count"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> VendorProperty.ofInteger(" "))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
