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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CatalogTest {

  private static final Pattern VENDOR_SAFE = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Nested
  class A_catalog_of_a_given_size {

    @Test
    void has_exactly_that_many_tools() {
      for (int size : List.of(20, 100, 500, 2000)) {
        assertThat(Catalog.of(size).tools()).hasSize(size);
      }
    }

    @Test
    void is_identical_every_time_it_is_built() {
      List<String> first = Catalog.of(500).tools().stream().map(CatalogTool::name).toList();
      List<String> second = Catalog.of(500).tools().stream().map(CatalogTool::name).toList();

      assertThat(first).isNotEmpty().isEqualTo(second);
    }

    @Test
    void is_a_prefix_of_every_larger_catalog() {
      List<String> small = Catalog.of(100).tools().stream().map(CatalogTool::name).toList();
      List<String> large = Catalog.of(500).tools().stream().map(CatalogTool::name).toList();

      assertThat(large.subList(0, 100)).isEqualTo(small);
    }

    @Test
    void holds_no_name_twice() {
      List<String> names = Catalog.of(2000).tools().stream().map(CatalogTool::name).toList();

      assertThat(new HashSet<>(names)).hasSameSizeAs(names);
    }

    @Test
    void names_every_tool_in_a_way_every_vendor_accepts() {
      List<String> names = Catalog.of(2000).tools().stream().map(CatalogTool::name).toList();

      assertThat(names).isNotEmpty();
      assertThat(names).allMatch(name -> VENDOR_SAFE.matcher(name).matches());
    }

    @Test
    void describes_every_tool_in_one_line() {
      List<CatalogTool> tools = Catalog.of(500).tools();

      assertThat(tools).isNotEmpty();
      assertThat(tools)
          .allMatch(tool -> !tool.description().isBlank() && !tool.description().contains("\n"));
    }

    @Test
    void gives_every_tool_an_object_schema_of_one_to_four_described_typed_properties() {
      List<CatalogTool> tools = Catalog.of(2000).tools();

      assertThat(tools).isNotEmpty();
      for (CatalogTool tool : tools) {
        JsonNode schema = MAPPER.readTree(tool.schema().json());
        assertThat(schema.path("type").asString()).isEqualTo("object");
        assertThat(schema.path("properties").size()).isBetween(1, 4);
        for (JsonNode property : schema.path("properties").values()) {
          assertThat(property.path("type").asString()).isNotBlank();
          assertThat(property.path("description").asString()).isNotBlank();
        }
        for (JsonNode required : schema.path("required")) {
          assertThat(schema.path("properties").has(required.asString())).isTrue();
        }
      }
    }

    @Test
    void refuses_a_size_smaller_than_the_hand_written_tools() {
      int tooSmall = HandWritten.tools().size() - 1;

      assertThatThrownBy(() -> Catalog.of(tooSmall))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("hand-written");
    }
  }

  @Nested
  class The_hand_written_tools {

    @Test
    void come_first_and_are_in_every_catalog() {
      List<String> hand = HandWritten.tools().stream().map(CatalogTool::name).toList();

      assertThat(hand).isNotEmpty();
      for (int size : List.of(20, 100, 2000)) {
        List<String> names = Catalog.of(size).tools().stream().map(CatalogTool::name).toList();
        assertThat(names.subList(0, hand.size())).isEqualTo(hand);
      }
    }

    @Test
    void number_no_more_than_the_smallest_catalog() {
      assertThat(HandWritten.tools()).hasSizeLessThanOrEqualTo(20);
    }

    @Test
    void include_near_duplicates_across_systems() {
      Set<String> names = Catalog.of(20).names();

      assertThat(names)
          .contains(
              "jira_create_issue",
              "linear_create_issue",
              "salesforce_search_accounts",
              "hubspot_search_companies");
    }

    @Test
    void return_results_that_chain() {
      CatalogTool search = Catalog.of(20).find("salesforce_search_accounts").orElseThrow();

      String result = search.run(MAPPER.readTree("{\"name\":\"Acme\"}"));

      assertThat(result).contains("001ACME").contains("Acme Corp");
    }

    @Test
    void mark_the_tools_that_change_things() {
      Catalog catalog = Catalog.of(20);

      assertThat(catalog.find("jira_create_issue").orElseThrow().write()).isTrue();
      assertThat(catalog.find("pagerduty_acknowledge_incident").orElseThrow().write()).isTrue();
      assertThat(catalog.find("jira_get_issue").orElseThrow().write()).isFalse();
    }
  }

  @Nested
  class The_generated_tools {

    @Test
    void answer_with_a_small_deterministic_result() {
      CatalogTool generated = Catalog.of(100).tools().get(HandWritten.tools().size());

      String first = generated.run(MAPPER.readTree("{}"));
      String second = generated.run(MAPPER.readTree("{}"));

      assertThat(first).startsWith("{\"ok\":true").isEqualTo(second);
    }

    @Test
    void mark_a_create_update_delete_as_a_write_and_a_list_as_a_read() {
      List<CatalogTool> tools = Catalog.of(2000).tools();

      assertThat(tools).anyMatch(tool -> tool.name().contains("_create_") && tool.write());
      assertThat(tools).anyMatch(tool -> tool.name().contains("_delete_") && tool.write());
      assertThat(tools).anyMatch(tool -> tool.name().contains("_list_") && !tool.write());
    }
  }
}
