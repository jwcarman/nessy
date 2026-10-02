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
package org.jwcarman.nessy.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.Memory;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.State;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Chapter;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.Turn;

@DisplayName("The inference vocabulary")
class InferenceTypesTest {

  private static Chapter chapter(long from, long through) {
    return new Chapter(
        new AgentType("chat"), AgentId.random(), new TurnId(from), new TurnId(through));
  }

  private static Turn turn(long id) {
    return new Turn(
        new TurnId(id),
        new Input(new Seq(id), List.of(new Block.Text("q" + id))),
        List.of(),
        null,
        10);
  }

  private static final List<Block.ActionRequestContent> ONLY_PROSE =
      List.of(new Block.Commentary("thinking"));

  @Test
  void what_is_refused_where_it_is_written() {
    assertThatThrownBy(() -> new InferenceResult.Actions(ONLY_PROSE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one call");
    ToolName name = new ToolName("t");
    JsonSchema schema = new JsonSchema("{}");
    assertThatThrownBy(() -> new ToolOffer(name, " ", schema))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InferenceOptions(" ", 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_context_knows_whether_it_carries_summaries() {
    InferenceContext bare = InferenceContext.of(List.of(turn(4)));
    InferenceContext summarised =
        new InferenceContext(List.of(new Summary(chapter(1, 3), "a")), List.of(turn(4)), List.of());

    assertThat(bare.hasSummaries()).isFalse();
    assertThat(summarised.hasSummaries()).isTrue();
    assertThat(new Failure.Rejected("too long").reason()).isEqualTo("too long");
  }

  @Nested
  class A_context_of_turns {

    @Test
    void the_last_turn_is_split_off_as_the_active_one() {
      InferenceContext context = InferenceContext.of(List.of(turn(1), turn(2), turn(3)));

      assertThat(context.tail()).containsExactly(turn(1), turn(2));
      assertThat(context.activeTurn()).isEqualTo(turn(3));
      assertThat(context.summaries()).isEmpty();
      assertThat(context.memory()).isEmpty();
      assertThat(context.state()).isEmpty();
      assertThat(context.ambient()).isEmpty();
    }

    @Test
    void a_single_turn_is_the_active_one_with_nothing_before_it() {
      InferenceContext context = InferenceContext.of(List.of(turn(1)));

      assertThat(context.tail()).isEmpty();
      assertThat(context.activeTurn()).isEqualTo(turn(1));
    }

    @Test
    void no_turns_at_all_is_refused() {
      List<Turn> none = List.of();

      assertThatThrownBy(() -> InferenceContext.of(none))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("active turn");
    }

    @Test
    void turns_are_the_tail_and_then_the_active_turn() {
      InferenceContext context =
          new InferenceContext(
              List.of(), List.of(turn(1), turn(2)), List.of(), List.of(), turn(3), List.of());

      assertThat(context.turns()).containsExactly(turn(1), turn(2), turn(3));
    }

    @Test
    void the_turns_given_with_ambient_have_the_last_as_the_active_turn() {
      InferenceContext context =
          new InferenceContext(
              List.of(turn(1), turn(2)), List.of(Ambient.text("clock", "Tuesday")));

      assertThat(context.tail()).containsExactly(turn(1));
      assertThat(context.activeTurn()).isEqualTo(turn(2));
      assertThat(context.ambient()).containsExactly(Ambient.text("clock", "Tuesday"));
    }
  }

  @Nested
  class A_context_is_built_from_its_strata {

    @Test
    void every_list_is_copied() {
      List<Turn> tail = new ArrayList<>(List.of(turn(1)));
      List<Memory> memory = new ArrayList<>(List.of(Memory.text("episodes", "m")));
      List<State> state = new ArrayList<>(List.of(State.text("plan", "s")));
      InferenceContext context =
          new InferenceContext(List.of(), tail, memory, state, turn(2), List.of());

      tail.add(turn(9));
      memory.clear();
      state.clear();

      assertThat(context.tail()).containsExactly(turn(1));
      assertThat(context.memory()).containsExactly(Memory.text("episodes", "m"));
      assertThat(context.state()).containsExactly(State.text("plan", "s"));
    }

    @Test
    void an_active_turn_is_required() {
      assertThatThrownBy(
              () ->
                  new InferenceContext(List.of(), List.of(), List.of(), List.of(), null, List.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("activeTurn");
    }
  }

  @Test
  void options_carry_properties_in_the_order_given_and_two_arguments_carry_none() {
    Map<String, String> given = new LinkedHashMap<>();
    given.put("openai.seed", "1");
    given.put("anthropic.top_k", "5");

    InferenceOptions options = new InferenceOptions("m", 10, given);
    given.put("openai.store", "false");

    assertThat(options.properties())
        .containsExactly(Map.entry("openai.seed", "1"), Map.entry("anthropic.top_k", "5"));
    assertThat(new InferenceOptions("m", 10).properties()).isEmpty();
    assertThat(InferenceOptions.of("m").properties()).isEmpty();
    assertThat(new InferenceOptions("m", 10)).isEqualTo(new InferenceOptions("m", 10, Map.of()));
  }

  @Test
  void options_refuse_a_null_property_value_and_never_print_one() {
    Map<String, String> nullValue = new LinkedHashMap<>();
    nullValue.put("openai.user", null);
    InferenceOptions options = new InferenceOptions("m", 10, Map.of("openai.user", "tenant-42"));

    assertThatThrownBy(() -> new InferenceOptions("m", 10, nullValue))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("openai.user");
    assertThat(options.toString()).contains("openai.user").doesNotContain("tenant-42");
  }

  @Test
  void the_properties_of_options_cannot_be_changed() {
    Map<String, String> properties = new InferenceOptions("m", 10, Map.of("a.b", "1")).properties();

    assertThatThrownBy(() -> properties.put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
