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
import org.jwcarman.nessy.examples.toollab.Task.Expected;
import org.jwcarman.nessy.examples.toollab.Task.Kind;
import tools.jackson.databind.JsonNode;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GraderTest {

  private static JsonNode json(String text) {
    return Schemas.MAPPER.readTree(text);
  }

  private static Task.Called called(String tool, String arguments) {
    return new Task.Called(tool, json(arguments), true);
  }

  private static Task chain(boolean ordered) {
    return new Task(
        "t",
        Kind.MULTI,
        "find Acme's opportunities",
        List.of(
            new Expected("salesforce_search_accounts", json("{\"name\":\"Acme\"}")),
            new Expected("salesforce_list_opportunities", json("{\"account_id\":\"001ACME\"}"))),
        ordered);
  }

  private static Task ambiguous() {
    return new Task("a", Kind.AMBIGUOUS, "create a task", List.of(), false);
  }

  private static final List<Task.Called> THE_CHAIN =
      List.of(
          called("salesforce_search_accounts", "{\"name\":\"Acme\"}"),
          called("salesforce_list_opportunities", "{\"account_id\":\"001ACME\"}"));

  @Nested
  class A_single_or_multi_step_task {

    @Test
    void succeeds_when_every_expected_call_was_made_in_order_and_the_run_answered() {
      Grade grade = Grader.grade(chain(true), THE_CHAIN, Ending.ANSWER, catalog());

      assertThat(grade.success()).isTrue();
      assertThat(grade.wrongCalls()).isZero();
    }

    @Test
    void fails_when_the_run_did_not_end_with_an_answer() {
      for (Ending ending :
          List.of(Ending.REFUSAL, Ending.FAULT, Ending.TRUNCATED, Ending.EXHAUSTED)) {
        assertThat(Grader.grade(chain(true), THE_CHAIN, ending, catalog()).success()).isFalse();
      }
    }

    @Test
    void fails_when_an_expected_call_is_missing() {
      Grade grade = Grader.grade(chain(true), THE_CHAIN.subList(0, 1), Ending.ANSWER, catalog());

      assertThat(grade.success()).isFalse();
    }

    @Test
    void fails_when_ordered_and_the_calls_came_the_other_way_round() {
      Grade grade =
          Grader.grade(
              chain(true), List.of(THE_CHAIN.get(1), THE_CHAIN.get(0)), Ending.ANSWER, catalog());

      assertThat(grade.success()).isFalse();
    }

    @Test
    void succeeds_when_not_ordered_and_the_calls_came_the_other_way_round() {
      Grade grade =
          Grader.grade(
              chain(false), List.of(THE_CHAIN.get(1), THE_CHAIN.get(0)), Ending.ANSWER, catalog());

      assertThat(grade.success()).isTrue();
    }

    @Test
    void accepts_other_calls_between_the_expected_ones_as_a_subsequence() {
      List<Task.Called> calls =
          List.of(
              THE_CHAIN.get(0),
              called("jira_get_issue", "{\"issue_key\":\"X-1\"}"),
              THE_CHAIN.get(1));

      Grade grade = Grader.grade(chain(true), calls, Ending.ANSWER, catalog());

      assertThat(grade.success()).isTrue();
      assertThat(grade.wrongCalls()).isEqualTo(1);
    }

    @Test
    void ignores_extra_arguments_the_expected_call_does_not_list() {
      List<Task.Called> calls =
          List.of(
              called("salesforce_search_accounts", "{\"name\":\"Acme\",\"limit\":5}"),
              THE_CHAIN.get(1));

      assertThat(Grader.grade(chain(true), calls, Ending.ANSWER, catalog()).success()).isTrue();
    }

    @Test
    void fails_when_a_listed_argument_has_another_value() {
      List<Task.Called> calls =
          List.of(
              THE_CHAIN.get(0),
              called("salesforce_list_opportunities", "{\"account_id\":\"001X\"}"));

      Grade grade = Grader.grade(chain(true), calls, Ending.ANSWER, catalog());

      assertThat(grade.success()).isFalse();
      assertThat(grade.wrongCalls()).isEqualTo(1);
    }

    @Test
    void fails_when_a_listed_argument_is_missing() {
      List<Task.Called> calls =
          List.of(THE_CHAIN.get(0), called("salesforce_list_opportunities", "{}"));

      assertThat(Grader.grade(chain(true), calls, Ending.ANSWER, catalog()).success()).isFalse();
    }

    @Test
    void compares_numbers_numerically_and_everything_else_as_text() {
      Task task =
          new Task(
              "n",
              Kind.SINGLE,
              "get pr 77",
              List.of(
                  new Expected(
                      "github_get_pull_request", json("{\"repo\":\"acme/web\",\"number\":77}"))),
              true);

      for (String number : List.of("77", "77.0", "\"77\"")) {
        List<Task.Called> calls =
            List.of(
                called(
                    "github_get_pull_request",
                    "{\"repo\":\"acme/web\",\"number\":" + number + "}"));
        assertThat(Grader.grade(task, calls, Ending.ANSWER, catalog()).success()).isTrue();
      }
      List<Task.Called> other =
          List.of(called("github_get_pull_request", "{\"repo\":\"acme/web\",\"number\":78}"));
      assertThat(Grader.grade(task, other, Ending.ANSWER, catalog()).success()).isFalse();
    }

    @Test
    void does_not_count_a_call_that_failed_as_having_been_made() {
      List<Task.Called> calls =
          List.of(
              THE_CHAIN.get(0),
              new Task.Called(
                  "salesforce_list_opportunities", json("{\"account_id\":\"001ACME\"}"), false));

      Grade grade = Grader.grade(chain(true), calls, Ending.ANSWER, catalog());

      assertThat(grade.success()).isFalse();
      assertThat(grade.wrongCalls()).isZero();
    }

    @Test
    void counts_a_repeat_of_an_expected_call_as_right_not_wrong() {
      List<Task.Called> calls = List.of(THE_CHAIN.get(0), THE_CHAIN.get(0), THE_CHAIN.get(1));

      assertThat(Grader.grade(chain(true), calls, Ending.ANSWER, catalog()).wrongCalls()).isZero();
    }
  }

  @Nested
  class An_ambiguous_task {

    @Test
    void succeeds_when_nothing_was_changed_and_the_run_answered() {
      Grade grade = Grader.grade(ambiguous(), List.of(), Ending.ANSWER, catalog());

      assertThat(grade.success()).isTrue();
      assertThat(grade.wrongCalls()).isZero();
    }

    @Test
    void fails_when_a_create_call_was_made() {
      List<Task.Called> calls = List.of(called("jira_create_issue", "{\"project\":\"OPS\"}"));

      Grade grade = Grader.grade(ambiguous(), calls, Ending.ANSWER, catalog());

      assertThat(grade.success()).isFalse();
      assertThat(grade.wrongCalls()).isEqualTo(1);
    }

    @Test
    void fails_when_a_system_specific_write_was_made() {
      List<Task.Called> calls = List.of(called("slack_post_message", "{\"channel\":\"C1\"}"));

      assertThat(Grader.grade(ambiguous(), calls, Ending.ANSWER, catalog()).success()).isFalse();
    }

    @Test
    void counts_a_write_that_failed_to_run_because_the_model_tried_it() {
      List<Task.Called> calls = List.of(new Task.Called("linear_create_issue", json("{}"), false));

      assertThat(Grader.grade(ambiguous(), calls, Ending.ANSWER, catalog()).success()).isFalse();
    }

    @Test
    void allows_reads_while_working_out_what_the_user_means() {
      List<Task.Called> calls = List.of(called("jira_get_issue", "{\"issue_key\":\"X-1\"}"));

      Grade grade = Grader.grade(ambiguous(), calls, Ending.ANSWER, catalog());

      assertThat(grade.success()).isTrue();
      assertThat(grade.wrongCalls()).isZero();
    }

    @Test
    void fails_when_the_run_did_not_end_with_an_answer() {
      assertThat(Grader.grade(ambiguous(), List.of(), Ending.REFUSAL, catalog()).success())
          .isFalse();
    }
  }

  private static Catalog catalog() {
    return Catalog.of(20);
  }
}
