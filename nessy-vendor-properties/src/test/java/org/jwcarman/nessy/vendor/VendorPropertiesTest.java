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
package org.jwcarman.nessy.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Vendor properties, as every adapter reads them")
class VendorPropertiesTest {

  /** Insertion order, which a Map.of would not keep. */
  private static Map<String, String> ordered(String... pairs) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put(pairs[i], pairs[i + 1]);
    }
    return map;
  }

  @Nested
  class UnderAPrefix {

    @Test
    void entries_come_back_with_the_prefix_stripped_and_other_prefixes_left_out() {
      Map<String, String> merged =
          ordered(
              "openai.reasoning.effort", "high",
              "anthropic.thinking.budget_tokens", "8192",
              "openai.seed", "42");

      assertThat(VendorProperties.under(merged, "openai."))
          .containsExactly(Map.entry("reasoning.effort", "high"), Map.entry("seed", "42"));
    }

    @Test
    void a_longer_first_segment_is_another_prefix_not_this_one() {
      Map<String, String> merged = ordered("openaix.seed", "1", "openai.seed", "2");

      assertThat(VendorProperties.under(merged, "openai.")).containsExactly(Map.entry("seed", "2"));
    }

    @Test
    void the_map_returned_cannot_be_changed() {
      Map<String, String> under = VendorProperties.under(Map.of("openai.seed", "1"), "openai.");

      assertThatThrownBy(() -> under.put("x", "y"))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void a_name_with_no_prefix_is_refused() {
      Map<String, String> merged = Map.of("temperature", "0.2");

      assertThatThrownBy(() -> VendorProperties.under(merged, "openai."))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'temperature' has no prefix");
    }

    @Test
    void a_name_starting_with_a_dot_is_refused() {
      Map<String, String> merged = Map.of(".seed", "1");

      assertThatThrownBy(() -> VendorProperties.under(merged, "openai."))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'.seed' has no prefix");
    }

    @Test
    void a_prefix_must_be_one_segment_ending_in_a_dot() {
      Map<String, String> merged = Map.of();

      assertThatThrownBy(() -> VendorProperties.under(merged, "gcp.gemini"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("gcp.gemini");
    }
  }

  @Test
  void agent_type_entries_override_provider_entries_by_name() {
    Map<String, String> provider = ordered("openai.seed", "1", "openai.store", "false");
    Map<String, String> agentType = Map.of("openai.seed", "2");

    assertThat(VendorProperties.merge(provider, agentType))
        .containsExactly(Map.entry("openai.seed", "2"), Map.entry("openai.store", "false"));
  }

  @Test
  void the_merged_map_cannot_be_changed() {
    Map<String, String> merged = VendorProperties.merge(Map.of("a.b", "1"), Map.of("a.c", "2"));

    assertThatThrownBy(() -> merged.put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Nested
  class TypedReads {

    @Test
    void a_string_is_read() {
      assertThat(VendorProperties.requireString("openai.reasoning.effort", "xhigh"))
          .isEqualTo("xhigh");
    }

    @Test
    void a_blank_string_is_refused_naming_the_property() {
      assertThatThrownBy(() -> VendorProperties.requireString("openai.reasoning.effort", " "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property 'openai.reasoning.effort' must be a non-blank string, was ' '");
    }
  }
}
