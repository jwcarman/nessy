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

import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class Bm25Test {

  @Nested
  class Searching_a_catalog {

    @Test
    void ranks_the_tool_that_names_the_system_the_verb_and_the_entity_first() {
      Bm25 index = new Bm25(Catalog.of(500).tools());

      List<CatalogTool> found = index.search("create jira issue", 10);

      assertThat(found).isNotEmpty();
      assertThat(found.getFirst().name()).isEqualTo("jira_create_issue");
    }

    @Test
    void splits_names_on_underscores_so_a_word_in_the_name_matches() {
      Bm25 index = new Bm25(Catalog.of(100).tools());

      List<CatalogTool> found = index.search("pagerduty incidents", 5);

      assertThat(found).isNotEmpty();
      assertThat(found.stream().map(CatalogTool::name)).contains("pagerduty_list_incidents");
    }

    @Test
    void returns_no_more_than_the_limit() {
      Bm25 index = new Bm25(Catalog.of(500).tools());

      assertThat(index.search("list", 3)).hasSize(3);
    }

    @Test
    void returns_nothing_for_words_no_tool_mentions() {
      Bm25 index = new Bm25(Catalog.of(100).tools());

      assertThat(index.search("zzzxqv", 10)).isEmpty();
    }

    @Test
    void ignores_case_and_punctuation() {
      Bm25 index = new Bm25(Catalog.of(500).tools());

      List<CatalogTool> found = index.search("Create, JIRA: issue!", 10);

      assertThat(found).isNotEmpty();
      assertThat(found.getFirst().name()).isEqualTo("jira_create_issue");
    }
  }
}
