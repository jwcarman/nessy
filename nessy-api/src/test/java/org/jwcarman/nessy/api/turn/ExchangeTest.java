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
package org.jwcarman.nessy.api.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ExchangeTest {

  private static final CallId FIRST = new CallId("call-1");
  private static final CallId SECOND = new CallId("call-2");

  private static final List<Block.ActionRequestContent> REQUEST =
      List.of(
          new Block.ToolCall("call-1", "refund", "{}"),
          new Block.Commentary("one moment"),
          new Block.ToolCall("call-2", "audit", "{}"));

  private static Exchange exchange(Map<CallId, String> actions, Map<CallId, String> results) {
    return new Exchange(new Seq(2), REQUEST, List.of(), actions, results);
  }

  private static Map<CallId, String> bothActions() {
    return Map.of(FIRST, "refund forty dollars", SECOND, "audit the buyer");
  }

  @Test
  void an_exchange_refuses_a_call_with_no_action() {
    Map<CallId, String> actions = Map.of(FIRST, "refund forty dollars");
    Map<CallId, String> results = Map.of();

    assertThatThrownBy(() -> exchange(actions, results))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("call-2");
  }

  @Test
  void an_action_for_an_id_that_is_not_one_of_its_calls_is_refused() {
    Map<CallId, String> actions = new HashMap<>(bothActions());
    actions.put(new CallId("call-9"), "something nobody asked for");
    Map<CallId, String> results = Map.of();

    assertThatThrownBy(() -> exchange(actions, results))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("call-9");
  }

  @Test
  void action_of_gives_the_calls_action() {
    Exchange exchange = exchange(bothActions(), Map.of());

    assertThat(exchange.actionOf(SECOND)).isEqualTo("audit the buyer");
  }

  @Test
  void action_of_refuses_an_id_that_is_not_one_of_its_calls() {
    Exchange exchange = exchange(bothActions(), Map.of());
    CallId stranger = new CallId("call-9");

    assertThatThrownBy(() -> exchange.actionOf(stranger))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("call-9");
  }

  @Test
  void result_of_is_empty_for_a_call_that_has_not_succeeded() {
    Exchange exchange = exchange(bothActions(), Map.of(FIRST, "refunded"));

    assertThat(exchange.resultOf(SECOND)).isEmpty();
  }

  @Test
  void result_of_gives_the_line_of_a_call_that_succeeded() {
    Exchange exchange = exchange(bothActions(), Map.of(FIRST, "refunded"));

    assertThat(exchange.resultOf(FIRST)).isEqualTo(Optional.of("refunded"));
  }

  @Test
  void a_result_line_may_be_the_empty_string() {
    Exchange exchange = exchange(bothActions(), Map.of(FIRST, ""));

    assertThat(exchange.resultOf(FIRST)).isEqualTo(Optional.of(""));
  }

  @Test
  void result_of_refuses_an_id_that_is_not_one_of_its_calls() {
    Exchange exchange = exchange(bothActions(), Map.of());
    CallId stranger = new CallId("call-9");

    assertThatThrownBy(() -> exchange.resultOf(stranger))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("call-9");
  }

  @Test
  void the_maps_cannot_be_changed_afterwards() {
    Map<CallId, String> actions = new HashMap<>(bothActions());
    Map<CallId, String> results = new HashMap<>(Map.of(FIRST, "refunded"));
    Exchange exchange = exchange(actions, results);

    actions.put(FIRST, "changed");
    results.put(SECOND, "changed");

    assertThat(exchange.actionOf(FIRST)).isEqualTo("refund forty dollars");
    assertThat(exchange.resultOf(SECOND)).isEmpty();
    assertThatThrownBy(() -> exchange.actions().put(FIRST, "changed"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> exchange.results().put(SECOND, "changed"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void a_result_for_an_id_that_is_not_one_of_its_calls_is_refused() {
    Map<CallId, String> results = Map.of(new CallId("call-9"), "from nowhere");
    Map<CallId, String> actions = bothActions();

    assertThatThrownBy(() -> exchange(actions, results))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("call-9");
  }
}
