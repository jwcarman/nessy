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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Vendor properties, as every adapter reads them")
class VendorPropertiesTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

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
  class ALiteral {

    @Test
    void a_whole_number_is_a_number() {
      assertThat(VendorProperties.literal("12000", MAPPER)).isEqualTo(12000);
    }

    @Test
    void a_decimal_is_a_number() {
      assertThat(VendorProperties.literal("0.2", MAPPER)).isEqualTo(0.2);
    }

    @Test
    void true_is_a_boolean() {
      assertThat(VendorProperties.literal("true", MAPPER)).isEqualTo(true);
    }

    @Test
    void null_is_null() {
      assertThat(VendorProperties.literal("null", MAPPER)).isNull();
    }

    @Test
    void braces_are_an_object() {
      assertThat(VendorProperties.literal("{\"type\":\"enabled\",\"budget_tokens\":4096}", MAPPER))
          .isEqualTo(Map.of("type", "enabled", "budget_tokens", 4096));
    }

    @Test
    void brackets_are_an_array() {
      assertThat(VendorProperties.literal("[\"\\n\\n\"]", MAPPER)).isEqualTo(List.of("\n\n"));
    }

    @Test
    void a_bare_word_is_a_string() {
      assertThat(VendorProperties.literal("high", MAPPER)).isEqualTo("high");
    }

    @Test
    void a_quoted_number_is_a_string() {
      assertThat(VendorProperties.literal("\"12345\"", MAPPER)).isEqualTo("12345");
    }

    /** Review Focus 1: the front of a value parsing as JSON does not make the value JSON. */
    @Test
    void a_value_that_only_starts_like_json_is_a_string() {
      assertThat(VendorProperties.literal("12abc", MAPPER)).isEqualTo("12abc");
      assertThat(VendorProperties.literal("true story", MAPPER)).isEqualTo("true story");
      assertThat(VendorProperties.literal("{\"a\":1} trailing", MAPPER))
          .isEqualTo("{\"a\":1} trailing");
    }

    /** The mapper is the application's; the whole-value rule is ours, whatever it tolerates. */
    @Test
    void a_tolerant_mapper_does_not_make_a_trailing_fragment_json() {
      JsonMapper tolerant =
          JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

      assertThat(VendorProperties.literal("12abc", tolerant)).isEqualTo("12abc");
      assertThat(VendorProperties.literal("true story", tolerant)).isEqualTo("true story");
      assertThat(VendorProperties.literal("{\"a\":1} trailing", tolerant))
          .isEqualTo("{\"a\":1} trailing");
    }

    @Test
    void an_array_is_a_list_whatever_the_mapper_prefers() {
      JsonMapper arrays =
          JsonMapper.builder().enable(DeserializationFeature.USE_JAVA_ARRAY_FOR_JSON_ARRAY).build();

      assertThat(VendorProperties.literal("[1,2]", arrays)).isEqualTo(List.of(1, 2));
    }
  }

  @Nested
  class TypedReads {

    @Test
    void an_integer_is_read() {
      assertThat(VendorProperties.requireInteger("anthropic.thinking.budget_tokens", "8192"))
          .isEqualTo(8192);
    }

    @Test
    void a_bad_integer_is_refused_naming_the_property_and_the_value() {
      assertThatThrownBy(
              () -> VendorProperties.requireInteger("anthropic.thinking.budget_tokens", "lots"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property 'anthropic.thinking.budget_tokens' must be an integer, was 'lots'");
    }

    @Test
    void a_boolean_is_read() {
      assertThat(VendorProperties.requireBoolean("openai.tools.strict", "false")).isFalse();
    }

    @Test
    void a_bad_boolean_is_refused_naming_the_property_and_the_value() {
      assertThatThrownBy(() -> VendorProperties.requireBoolean("openai.tools.strict", "yes"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("property 'openai.tools.strict' must be true or false, was 'yes'");
    }

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
