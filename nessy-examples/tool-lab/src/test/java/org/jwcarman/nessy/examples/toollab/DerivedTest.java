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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.inference.ToolOffer;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DerivedTest {

  private static final Catalog CATALOG = Catalog.of(100);

  private static Turn asking() {
    return new Turn(
        new TurnId(1), new Input(new Seq(1), List.of(new Block.Text("hello"))), List.of(), null);
  }

  /** The turn after one exchange in which the model made these calls, with these outcomes. */
  private static Turn after(Turn turn, List<Block.ToolCall> calls, List<ToolOutcome> outcomes) {
    List<Exchange> exchanges = new ArrayList<>(turn.exchanges());
    Map<CallId, String> actions = new LinkedHashMap<>();
    for (Block.ToolCall call : calls) {
      actions.put(call.id(), call.name() + "(" + call.arguments() + ")");
    }
    exchanges.add(
        new Exchange(
            new Seq(exchanges.size() + 2), List.copyOf(calls), outcomes, actions, Map.of()));
    return new Turn(turn.id(), turn.input(), exchanges, null);
  }

  private static ToolOutcome succeeded(String id) {
    return new ToolOutcome.Succeeded(new CallId(id), List.of(new Block.Text("ok")));
  }

  private static ToolOutcome failed(String id) {
    return new ToolOutcome.Failed(new CallId(id), "no");
  }

  private static List<String> names(Variant variant, Turn... turns) {
    return Derived.offers(variant, CATALOG, List.of(turns)).stream()
        .map(offer -> offer.name().value())
        .toList();
  }

  @Nested
  class With_nothing_in_the_context {

    @Test
    void v2_offers_the_search_and_load_meta_tools_only() {
      assertThat(names(Variant.V2, asking())).containsExactly("search_tools", "load_tools");
    }

    @Test
    void v1_offers_the_load_meta_tool_only() {
      assertThat(names(Variant.V1, asking())).containsExactly("load_tools");
    }

    @Test
    void v0_offers_every_catalog_tool_and_no_meta_tool() {
      List<String> offered = names(Variant.V0, asking());

      assertThat(offered).hasSize(CATALOG.tools().size());
      assertThat(offered).doesNotContain("search_tools", "load_tools");
    }
  }

  @Nested
  class With_a_call_in_the_context {

    @Test
    void the_tool_called_is_offered_after_the_meta_tools() {
      Turn turn =
          after(
              asking(),
              List.of(new Block.ToolCall("c1", "jira_get_issue", "{}")),
              List.of(succeeded("c1")));

      assertThat(names(Variant.V2, turn))
          .containsExactly("search_tools", "load_tools", "jira_get_issue");
    }

    @Test
    void a_call_whose_outcome_failed_still_counts_because_the_history_names_the_tool() {
      Turn turn =
          after(
              asking(),
              List.of(new Block.ToolCall("c1", "jira_get_issue", "{}")),
              List.of(failed("c1")));

      assertThat(names(Variant.V1, turn)).containsExactly("load_tools", "jira_get_issue");
    }

    @Test
    void a_call_to_a_name_the_catalog_lacks_offers_nothing() {
      Turn turn =
          after(
              asking(),
              List.of(new Block.ToolCall("c1", "made_up_tool", "{}")),
              List.of(failed("c1")));

      assertThat(names(Variant.V1, turn)).containsExactly("load_tools");
    }

    @Test
    void a_tool_called_twice_is_offered_once() {
      Turn once =
          after(
              asking(),
              List.of(new Block.ToolCall("c1", "jira_get_issue", "{}")),
              List.of(succeeded("c1")));
      Turn twice =
          after(
              once,
              List.of(new Block.ToolCall("c2", "jira_get_issue", "{}")),
              List.of(succeeded("c2")));

      assertThat(names(Variant.V1, twice)).containsExactly("load_tools", "jira_get_issue");
    }

    @Test
    void a_call_to_a_meta_tool_adds_no_catalog_tool() {
      Turn turn =
          after(
              asking(),
              List.of(new Block.ToolCall("c1", "search_tools", "{\"query\":\"jira\"}")),
              List.of(succeeded("c1")));

      assertThat(names(Variant.V2, turn)).containsExactly("search_tools", "load_tools");
    }
  }

  @Nested
  class With_load_tools_in_the_context {

    @Test
    void the_names_of_a_succeeded_load_are_offered_in_the_order_given() {
      Turn turn =
          after(
              asking(),
              List.of(
                  new Block.ToolCall(
                      "c1",
                      "load_tools",
                      "{\"names\":[\"stripe_list_invoices\",\"jira_get_issue\"]}")),
              List.of(succeeded("c1")));

      assertThat(names(Variant.V2, turn))
          .containsExactly("search_tools", "load_tools", "stripe_list_invoices", "jira_get_issue");
    }

    @Test
    void the_names_of_a_failed_load_are_not_offered() {
      Turn turn =
          after(
              asking(),
              List.of(new Block.ToolCall("c1", "load_tools", "{\"names\":[\"jira_get_issue\"]}")),
              List.of(failed("c1")));

      assertThat(names(Variant.V2, turn)).containsExactly("search_tools", "load_tools");
    }

    @Test
    void a_load_with_no_outcome_yet_offers_nothing() {
      Turn turn =
          after(
              asking(),
              List.of(new Block.ToolCall("c1", "load_tools", "{\"names\":[\"jira_get_issue\"]}")),
              List.of());

      assertThat(names(Variant.V2, turn)).containsExactly("search_tools", "load_tools");
    }

    @Test
    void names_the_catalog_lacks_are_left_out() {
      Turn turn =
          after(
              asking(),
              List.of(
                  new Block.ToolCall(
                      "c1", "load_tools", "{\"names\":[\"no_such_tool\",\"jira_get_issue\"]}")),
              List.of(succeeded("c1")));

      assertThat(names(Variant.V2, turn))
          .containsExactly("search_tools", "load_tools", "jira_get_issue");
    }

    @Test
    void arguments_that_are_not_json_offer_nothing() {
      Turn turn =
          after(
              asking(),
              List.of(new Block.ToolCall("c1", "load_tools", "not json")),
              List.of(succeeded("c1")));

      assertThat(names(Variant.V2, turn)).containsExactly("search_tools", "load_tools");
    }

    @Test
    void loads_and_calls_from_earlier_exchanges_and_earlier_turns_all_count() {
      Turn earlier =
          after(
              asking(),
              List.of(new Block.ToolCall("c1", "load_tools", "{\"names\":[\"jira_get_issue\"]}")),
              List.of(succeeded("c1")));
      Turn later =
          after(
              asking(),
              List.of(new Block.ToolCall("c2", "stripe_list_invoices", "{}")),
              List.of(succeeded("c2")));

      assertThat(names(Variant.V2, earlier, later))
          .containsExactly("search_tools", "load_tools", "jira_get_issue", "stripe_list_invoices");
    }
  }

  @Nested
  class Offers {

    @Test
    void carry_the_description_and_schema_of_the_tool() {
      Turn turn =
          after(
              asking(),
              List.of(new Block.ToolCall("c1", "jira_get_issue", "{}")),
              List.of(succeeded("c1")));

      ToolOffer offer =
          Derived.offers(Variant.V1, CATALOG, List.of(turn)).stream()
              .filter(candidate -> candidate.name().value().equals("jira_get_issue"))
              .findFirst()
              .orElseThrow();

      assertThat(offer.description()).isEqualTo("Get one Jira issue by its key.");
      assertThat(offer.schema().json()).contains("issue_key");
    }

    @Test
    void are_the_same_every_time_for_the_same_context() {
      Turn turn =
          after(
              asking(),
              List.of(new Block.ToolCall("c1", "load_tools", "{\"names\":[\"jira_get_issue\"]}")),
              List.of(succeeded("c1")));

      assertThat(Derived.offers(Variant.V2, CATALOG, List.of(turn)))
          .isEqualTo(Derived.offers(Variant.V2, CATALOG, List.of(turn)));
    }
  }
}
