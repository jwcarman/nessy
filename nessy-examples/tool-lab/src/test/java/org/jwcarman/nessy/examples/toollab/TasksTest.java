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
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.examples.toollab.Task.Expected;
import org.jwcarman.nessy.examples.toollab.Task.Kind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TasksTest {

  private static final Catalog CATALOG = Catalog.of(20);

  @Nested
  class The_task_file {

    @Test
    void holds_about_forty_tasks_with_distinct_ids() {
      List<Task> tasks = Tasks.load();

      assertThat(tasks).hasSizeBetween(36, 44);
      assertThat(new HashSet<>(tasks.stream().map(Task::id).toList())).hasSameSizeAs(tasks);
    }

    @Test
    void opens_with_a_balanced_mix_of_the_three_kinds() {
      List<Task> pilot = Tasks.load().subList(0, 10);

      assertThat(pilot.stream().filter(task -> task.kind() == Kind.SINGLE).count())
          .isBetween(3L, 4L);
      assertThat(pilot.stream().filter(task -> task.kind() == Kind.MULTI).count())
          .isBetween(3L, 4L);
      assertThat(pilot.stream().filter(task -> task.kind() == Kind.AMBIGUOUS).count())
          .isBetween(3L, 4L);
    }

    @Test
    void has_one_expected_call_for_a_single_two_or_three_for_a_multi_and_none_for_an_ambiguous() {
      List<Task> tasks = Tasks.load();

      assertThat(tasks).isNotEmpty();
      for (Task task : tasks) {
        switch (task.kind()) {
          case SINGLE -> assertThat(task.expected()).hasSize(1);
          case MULTI -> assertThat(task.expected()).hasSizeBetween(2, 3);
          case AMBIGUOUS -> assertThat(task.expected()).isEmpty();
        }
      }
    }

    @Test
    void expects_only_tools_the_smallest_catalog_holds_with_arguments_they_declare() {
      List<Task> tasks = Tasks.load();

      assertThat(tasks).isNotEmpty();
      for (Task task : tasks) {
        for (Expected expected : task.expected()) {
          CatalogTool tool = CATALOG.find(expected.tool()).orElseThrow();
          JsonNode properties = Schemas.MAPPER.readTree(tool.schema().json()).path("properties");
          for (String key : expected.args().propertyNames()) {
            assertThat(properties.has(key))
                .as("%s: %s takes %s", task.id(), expected.tool(), key)
                .isTrue();
          }
        }
      }
    }

    @Test
    void asks_for_every_ambiguous_task_something_both_jira_and_linear_or_both_crms_could_do() {
      List<Task> ambiguous = Tasks.load().stream().filter(t -> t.kind() == Kind.AMBIGUOUS).toList();

      assertThat(ambiguous).hasSizeGreaterThanOrEqualTo(8);
      assertThat(ambiguous).allMatch(task -> task.prompt().length() > 10);
    }

    @Test
    void chains_each_value_from_the_prompt_or_from_an_earlier_result() {
      List<Task> tasks = Tasks.load();

      assertThat(tasks).isNotEmpty();
      for (Task task : tasks) {
        StringBuilder known = new StringBuilder(task.prompt().toLowerCase(Locale.ROOT));
        for (Expected expected : task.expected()) {
          for (Map.Entry<String, JsonNode> arg : expected.args().properties()) {
            String value = arg.getValue().asString("").toLowerCase(Locale.ROOT);
            assertThat(known.toString())
                .as(
                    "%s: %s=%s is in the prompt or an earlier result",
                    task.id(), arg.getKey(), value)
                .contains(value);
          }
          CatalogTool tool = CATALOG.find(expected.tool()).orElseThrow();
          ObjectNode arguments = (ObjectNode) expected.args().deepCopy();
          if (!arguments.has("query")
              && Schemas.MAPPER.readTree(tool.schema().json()).path("properties").has("query")) {
            // A search the task does not pin down is run with the user's own words.
            arguments.put("query", task.prompt());
          }
          String result = tool.run(arguments);
          assertThat(result)
              .as("%s: %s answers", task.id(), expected.tool())
              .doesNotContain("not found");
          assertThat(result).doesNotContain("[]");
          known.append(' ').append(result.toLowerCase(Locale.ROOT));
        }
      }
    }
  }

  @Nested
  class Parsing {

    @Test
    void reads_a_task_from_json() {
      List<Task> tasks =
          Tasks.parse(
              """
              [{"id":"x","kind":"single","prompt":"p",
                "expected":[{"tool":"aws_list_regions","args":{"account_id":"1"}}],
                "ordered":true}]""");

      assertThat(tasks).hasSize(1);
      assertThat(tasks.getFirst().kind()).isEqualTo(Kind.SINGLE);
      assertThat(tasks.getFirst().expected().getFirst().tool()).isEqualTo("aws_list_regions");
      assertThat(tasks.getFirst().ordered()).isTrue();
    }

    @Test
    void refuses_a_kind_it_does_not_know() {
      String json =
          "[{\"id\":\"x\",\"kind\":\"odd\",\"prompt\":\"p\",\"expected\":[],\"ordered\":true}]";

      assertThatThrownBy(() -> Tasks.parse(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("odd");
    }
  }
}
