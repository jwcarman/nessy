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

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Tokens;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.examples.toollab.Task.Expected;
import org.jwcarman.nessy.examples.toollab.Task.Kind;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RunnerTest {

  private static final Catalog CATALOG = Catalog.of(100);
  private static final InferenceOptions OPTIONS = new InferenceOptions("fake-model", 1000);

  private static Task lookup() {
    return new Task(
        "t",
        Kind.SINGLE,
        "What is the status of Jira issue AUTH-87?",
        List.of(
            new Expected("jira_get_issue", Schemas.MAPPER.readTree("{\"issue_key\":\"AUTH-87\"}"))),
        true);
  }

  private static Task ambiguousTask() {
    return new Task("a", Kind.AMBIGUOUS, "Create a task to rotate the TLS cert.", List.of(), false);
  }

  private static Runner runner(ScriptedProvider provider) {
    return new Runner(provider, OPTIONS, CATALOG, Duration.ZERO);
  }

  private static List<String> offered(InferenceRequest request) {
    return request.toolset().offers().stream().map(offer -> offer.name().value()).toList();
  }

  private static Turn active(InferenceRequest request) {
    return request.context().activeTurn();
  }

  @Nested
  class A_search_load_call_answer_run {

    private ScriptedProvider provider() {
      return new ScriptedProvider()
          .then(ScriptedProvider.calling("c1", "search_tools", "{\"query\":\"jira issue status\"}"))
          .then(ScriptedProvider.calling("c2", "load_tools", "{\"names\":[\"jira_get_issue\"]}"))
          .then(ScriptedProvider.calling("c3", "jira_get_issue", "{\"issue_key\":\"AUTH-87\"}"))
          .then(ScriptedProvider.saying("AUTH-87 is open."));
    }

    @Test
    void grades_as_a_success() {
      RunRecord run = runner(provider()).run(lookup(), Variant.V2, 0);

      assertThat(run.success()).isTrue();
      assertThat(run.ending()).isEqualTo(Ending.ANSWER);
      assertThat(run.answer()).isEqualTo("AUTH-87 is open.");
      assertThat(run.rounds()).isEqualTo(4);
      assertThat(run.metaCalls()).isEqualTo(2);
      assertThat(run.catalogCalls()).isEqualTo(1);
      assertThat(run.wrongCalls()).isZero();
      assertThat(run.unloadedCalls()).isZero();
      assertThat(run.unknownCalls()).isZero();
    }

    @Test
    void offers_the_loaded_tool_only_from_the_request_after_the_load() {
      ScriptedProvider provider = provider();

      runner(provider).run(lookup(), Variant.V2, 0);

      List<InferenceRequest> requests = provider.requests();
      assertThat(requests).hasSize(4);
      assertThat(offered(requests.get(0))).containsExactly("search_tools", "load_tools");
      assertThat(offered(requests.get(1))).containsExactly("search_tools", "load_tools");
      assertThat(offered(requests.get(2)))
          .containsExactly("search_tools", "load_tools", "jira_get_issue");
      assertThat(offered(requests.get(3)))
          .containsExactly("search_tools", "load_tools", "jira_get_issue");
    }

    @Test
    void answers_the_search_with_ranked_tools_and_the_load_with_the_tools_loaded() {
      ScriptedProvider provider = provider();

      runner(provider).run(lookup(), Variant.V2, 0);

      Exchange search = active(provider.requests().get(2)).exchanges().get(0);
      Exchange load = active(provider.requests().get(2)).exchanges().get(1);
      assertThat(text(search.outcomes().getFirst())).contains("\"name\":\"jira_get_issue\"");
      assertThat(text(load.outcomes().getFirst()))
          .contains("\"name\":\"jira_get_issue\"")
          .contains("\"loaded\":true");
    }

    @Test
    void sends_one_turn_whose_input_is_the_prompt_and_grows_it_one_exchange_per_round() {
      ScriptedProvider provider = provider();

      runner(provider).run(lookup(), Variant.V2, 0);

      List<InferenceRequest> requests = provider.requests();
      assertThat(active(requests.get(0)).input().blocks())
          .containsExactly(new Block.Text("What is the status of Jira issue AUTH-87?"));
      for (int i = 0; i < requests.size(); i++) {
        assertThat(active(requests.get(i)).exchanges()).hasSize(i);
      }
    }

    @Test
    void does_not_list_the_catalog_in_the_prompt_of_v2() {
      ScriptedProvider provider = provider();

      runner(provider).run(lookup(), Variant.V2, 0);

      String prompt = provider.requests().getFirst().systemPrompt().value();
      assertThat(prompt).contains("search_tools").doesNotContain("jira_get_issue");
    }
  }

  @Nested
  class A_call_to_a_tool_that_is_not_loaded {

    @Test
    void is_recorded_as_failed_and_counted() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(ScriptedProvider.calling("c1", "jira_get_issue", "{\"issue_key\":\"AUTH-87\"}"))
              .then(ScriptedProvider.saying("sorry"));

      RunRecord run = runner(provider).run(lookup(), Variant.V1, 0);

      Exchange exchange = active(provider.requests().get(1)).exchanges().getFirst();
      assertThat(exchange.outcomes())
          .containsExactly(
              new ToolOutcome.Failed(
                  exchange.calls().getFirst().id(),
                  "jira_get_issue is not loaded; load it with load_tools first"));
      assertThat(run.unloadedCalls()).isEqualTo(1);
      assertThat(run.catalogCalls()).isEqualTo(1);
    }

    @Test
    void does_not_satisfy_the_task_because_it_never_ran() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(ScriptedProvider.calling("c1", "jira_get_issue", "{\"issue_key\":\"AUTH-87\"}"))
              .then(ScriptedProvider.saying("sorry"));

      RunRecord run = runner(provider).run(lookup(), Variant.V1, 0);

      assertThat(run.success()).isFalse();
    }

    @Test
    void makes_the_tool_offered_on_the_next_request_because_the_history_names_it() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(ScriptedProvider.calling("c1", "jira_get_issue", "{\"issue_key\":\"AUTH-87\"}"))
              .then(ScriptedProvider.calling("c2", "jira_get_issue", "{\"issue_key\":\"AUTH-87\"}"))
              .then(ScriptedProvider.saying("open"));

      RunRecord run = runner(provider).run(lookup(), Variant.V1, 0);

      assertThat(offered(provider.requests().get(1)))
          .containsExactly("load_tools", "jira_get_issue");
      assertThat(run.success()).isTrue();
      assertThat(run.unloadedCalls()).isEqualTo(1);
    }

    @Test
    void cannot_happen_in_v0_where_every_tool_is_offered() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(ScriptedProvider.calling("c1", "jira_get_issue", "{\"issue_key\":\"AUTH-87\"}"))
              .then(ScriptedProvider.saying("open"));

      RunRecord run = runner(provider).run(lookup(), Variant.V0, 0);

      assertThat(run.success()).isTrue();
      assertThat(run.unloadedCalls()).isZero();
      assertThat(run.maxOffered()).isEqualTo(CATALOG.tools().size());
    }
  }

  @Nested
  class A_call_to_a_name_that_is_not_in_the_catalog {

    @Test
    void is_recorded_as_failed_with_no_such_tool_and_counted() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(ScriptedProvider.calling("c1", "jira_fetch_everything", "{}"))
              .then(ScriptedProvider.saying("sorry"));

      RunRecord run = runner(provider).run(lookup(), Variant.V2, 0);

      Exchange exchange = active(provider.requests().get(1)).exchanges().getFirst();
      assertThat(exchange.outcomes())
          .containsExactly(
              new ToolOutcome.Failed(exchange.calls().getFirst().id(), "no such tool"));
      assertThat(run.unknownCalls()).isEqualTo(1);
      assertThat(run.catalogCalls()).isZero();
    }

    @Test
    void includes_a_meta_tool_the_variant_does_not_offer() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(ScriptedProvider.calling("c1", "search_tools", "{\"query\":\"jira\"}"))
              .then(ScriptedProvider.saying("sorry"));

      RunRecord run = runner(provider).run(lookup(), Variant.V1, 0);

      assertThat(run.unknownCalls()).isEqualTo(1);
      assertThat(run.metaCalls()).isZero();
    }
  }

  @Nested
  class The_meta_tools {

    @Test
    void load_tools_reports_an_unknown_name_as_an_error_and_a_known_one_as_loaded() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(
                  ScriptedProvider.calling(
                      "c1", "load_tools", "{\"names\":[\"jira_get_issue\",\"nope\"]}"))
              .then(ScriptedProvider.saying("done"));

      runner(provider).run(lookup(), Variant.V1, 0);

      Exchange exchange = active(provider.requests().get(1)).exchanges().getFirst();
      assertThat(text(exchange.outcomes().getFirst()))
          .contains("{\"name\":\"nope\",\"error\":\"no such tool\"}")
          .contains("\"name\":\"jira_get_issue\"")
          .contains("\"loaded\":true");
      assertThat(offered(provider.requests().get(1)))
          .containsExactly("load_tools", "jira_get_issue");
    }

    @Test
    void load_tools_with_no_names_fails() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(ScriptedProvider.calling("c1", "load_tools", "{}"))
              .then(ScriptedProvider.saying("done"));

      runner(provider).run(lookup(), Variant.V1, 0);

      Exchange exchange = active(provider.requests().get(1)).exchanges().getFirst();
      assertThat(exchange.outcomes().getFirst()).isInstanceOf(ToolOutcome.Failed.class);
      assertThat(offered(provider.requests().get(1))).containsExactly("load_tools");
    }

    @Test
    void search_tools_limits_its_answer_to_the_limit_given() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(
                  ScriptedProvider.calling(
                      "c1", "search_tools", "{\"query\":\"list\",\"limit\":3}"))
              .then(ScriptedProvider.saying("done"));

      runner(provider).run(lookup(), Variant.V2, 0);

      Exchange exchange = active(provider.requests().get(1)).exchanges().getFirst();
      assertThat(Schemas.MAPPER.readTree(text(exchange.outcomes().getFirst()))).hasSize(3);
    }

    @Test
    void search_tools_never_returns_more_than_twenty_five() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(
                  ScriptedProvider.calling(
                      "c1", "search_tools", "{\"query\":\"list\",\"limit\":99}"))
              .then(ScriptedProvider.saying("done"));

      runner(provider).run(lookup(), Variant.V2, 0);

      Exchange exchange = active(provider.requests().get(1)).exchanges().getFirst();
      assertThat(Schemas.MAPPER.readTree(text(exchange.outcomes().getFirst()))).hasSize(25);
    }
  }

  @Nested
  class The_prompt_of_v1 {

    @Test
    void lists_every_catalog_tool_as_a_name_and_a_description() {
      ScriptedProvider provider = new ScriptedProvider().then(ScriptedProvider.saying("hi"));

      runner(provider).run(lookup(), Variant.V1, 0);

      String prompt = provider.requests().getFirst().systemPrompt().value();
      assertThat(CATALOG.tools()).isNotEmpty();
      for (CatalogTool tool : CATALOG.tools()) {
        assertThat(prompt).contains(tool.name() + " — " + tool.description());
      }
    }
  }

  @Nested
  class An_ambiguous_task {

    @Test
    void succeeds_when_the_model_asks_which_system_and_changes_nothing() {
      ScriptedProvider provider =
          new ScriptedProvider().then(ScriptedProvider.saying("Jira or Linear?"));

      RunRecord run = runner(provider).run(ambiguousTask(), Variant.V0, 0);

      assertThat(run.success()).isTrue();
    }

    @Test
    void fails_and_counts_the_write_when_the_model_guesses() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(
                  ScriptedProvider.calling(
                      "c1", "jira_create_issue", "{\"project\":\"OPS\",\"summary\":\"x\"}"))
              .then(ScriptedProvider.saying("Created."));

      RunRecord run = runner(provider).run(ambiguousTask(), Variant.V0, 0);

      assertThat(run.success()).isFalse();
      assertThat(run.wrongCalls()).isEqualTo(1);
    }
  }

  @Nested
  class How_a_run_ends {

    @Test
    void with_a_refusal() {
      ScriptedProvider provider =
          new ScriptedProvider().then(new InferenceResult.Refusal("safety"));

      RunRecord run = runner(provider).run(lookup(), Variant.V0, 0);

      assertThat(run.ending()).isEqualTo(Ending.REFUSAL);
      assertThat(run.success()).isFalse();
    }

    @Test
    void with_a_fault() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(new InferenceResult.Fault(new Failure.Rejected("bad request")));

      RunRecord run = runner(provider).run(lookup(), Variant.V0, 0);

      assertThat(run.ending()).isEqualTo(Ending.FAULT);
      assertThat(run.note()).contains("bad request");
    }

    @Test
    void with_a_reply_cut_off_at_the_output_limit() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(new InferenceResult.Truncated(List.of(new Block.Text("The sta"))));

      RunRecord run = runner(provider).run(lookup(), Variant.V0, 0);

      assertThat(run.ending()).isEqualTo(Ending.TRUNCATED);
    }

    @Test
    void exhausted_after_twelve_rounds_that_all_asked_for_more() {
      ScriptedProvider provider = new ScriptedProvider();
      for (int i = 0; i < 13; i++) {
        provider.then(
            ScriptedProvider.calling("c" + i, "jira_get_issue", "{\"issue_key\":\"X-1\"}"));
      }

      RunRecord run = runner(provider).run(lookup(), Variant.V0, 0);

      assertThat(run.ending()).isEqualTo(Ending.EXHAUSTED);
      assertThat(run.rounds()).isEqualTo(12);
      assertThat(provider.requests()).hasSize(12);
    }

    @Test
    void a_call_that_throws_is_tried_again_and_the_run_goes_on() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .thenThrow(new IllegalStateException("rate limited"))
              .thenThrow(new IllegalStateException("rate limited"))
              .then(ScriptedProvider.saying("open"));

      RunRecord run = runner(provider).run(lookup(), Variant.V0, 0);

      assertThat(run.ending()).isEqualTo(Ending.ANSWER);
      assertThat(run.rounds()).isEqualTo(1);
      assertThat(provider.requests()).hasSize(3);
    }

    @Test
    void a_call_that_keeps_throwing_ends_the_run_as_a_fault_naming_the_last_failure() {
      ScriptedProvider provider = new ScriptedProvider();
      for (int i = 0; i < Models.ATTEMPTS; i++) {
        provider.thenThrow(new IllegalStateException("overloaded"));
      }

      RunRecord run = runner(provider).run(lookup(), Variant.V0, 0);

      assertThat(run.ending()).isEqualTo(Ending.FAULT);
      assertThat(run.note()).contains("overloaded");
    }

    @Test
    void a_fault_result_is_not_tried_again() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(new InferenceResult.Fault(new Failure.Transient("busy")))
              .then(ScriptedProvider.saying("never reached"));

      runner(provider).run(lookup(), Variant.V0, 0);

      assertThat(provider.requests()).hasSize(1);
    }
  }

  @Nested
  class Usage_across_rounds {

    @Test
    void is_summed_count_by_count() {
      Usage first = new Usage("fake-model", 100, 10, 40, 5, null);
      Usage second = new Usage("fake-model", 150, 20, 60, 0, null);
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(
                  ScriptedProvider.calling(
                      "c1", "jira_get_issue", "{\"issue_key\":\"AUTH-87\"}", first))
              .then(ScriptedProvider.saying("open", second));

      RunRecord run = runner(provider).run(lookup(), Variant.V0, 0);

      assertThat(run.usage().input()).isEqualTo(Tokens.of(250));
      assertThat(run.usage().output()).isEqualTo(Tokens.of(30));
      assertThat(run.usage().cacheRead()).isEqualTo(Tokens.of(100));
      assertThat(run.usage().cacheWrite()).isEqualTo(Tokens.of(5));
    }

    @Test
    void stays_unreported_when_the_vendor_never_reported_it() {
      ScriptedProvider provider = new ScriptedProvider().then(ScriptedProvider.saying("open"));

      RunRecord run = runner(provider).run(lookup(), Variant.V0, 0);

      assertThat(run.usage().input()).isEqualTo(Tokens.none());
      assertThat(run.usage().reasoning()).isEqualTo(Tokens.none());
      assertThat(Tally.show(run.usage().input())).isEqualTo("unreported");
    }
  }

  @Nested
  class The_exchanges_recorded {

    @Test
    void name_each_call_and_its_arguments_in_a_line_cut_to_two_hundred_characters() {
      String longArgument = "x".repeat(400);
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(
                  ScriptedProvider.calling(
                      "c1", "jira_get_issue", "{\"issue_key\":\"" + longArgument + "\"}"))
              .then(ScriptedProvider.saying("none"));

      runner(provider).run(lookup(), Variant.V0, 0);

      Exchange exchange = active(provider.requests().get(1)).exchanges().getFirst();
      String line = exchange.actionOf(exchange.calls().getFirst().id());
      assertThat(line).startsWith("jira_get_issue(").hasSize(200);
    }
  }

  private static String text(ToolOutcome outcome) {
    return ((Block.Text) ((ToolOutcome.Succeeded) outcome).blocks().getFirst()).text();
  }
}
