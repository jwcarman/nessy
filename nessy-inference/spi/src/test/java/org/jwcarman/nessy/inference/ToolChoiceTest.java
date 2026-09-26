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

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.tool.InputSchema;
import org.jwcarman.nessy.api.tool.ToolName;

/**
 * What a request means when it says nothing about choosing a tool.
 *
 * <p>A rendered request is stored, so this is not only about a caller who left the argument out: it
 * is about every row written before the field existed, read back to show what a model was shown. An
 * absent choice has to keep meaning what it meant then, which is that the model decided.
 */
class ToolChoiceTest {

  private static final SystemPrompt SYSTEM = new SystemPrompt("you are a helpful assistant");

  private static final ToolOffer LOOKUP =
      new ToolOffer(new ToolName("lookup"), "looks something up", new InputSchema("{}"));

  private static Toolset with(ToolChoice choice) {
    return new Toolset(List.of(LOOKUP), choice);
  }

  /** What a toolset assembled without an opinion about choosing means. */
  @Test
  void a_toolset_that_does_not_mention_choosing_leaves_it_to_the_model() {
    InferenceRequest request =
        new InferenceRequest(
            SYSTEM, InferenceContext.of(List.of()), Toolset.none(), InferenceOptions.of("a-model"));

    assertThat(request.toolset().choice()).isEqualTo(new ToolChoice.Auto());
    assertThat(Toolset.of(List.of(LOOKUP)).choice()).isEqualTo(new ToolChoice.Auto());
  }

  /** Nothing on offer is a state a wire cares about, not an empty list to be sent. */
  @Test
  void a_toolset_says_whether_anything_is_on_offer() {
    assertThat(Toolset.none().any()).isFalse();
    assertThat(Toolset.none().offers()).isEmpty();
    assertThat(Toolset.of(List.of(LOOKUP)).any()).isTrue();
  }

  /**
   * Incoherent rather than merely unusual, so it is refused once here instead of four times on four
   * wires. Note what is NOT refused: a choice that is absent, which defaults, because a stored row
   * written before there was anything to say about choosing has to stay readable.
   */
  @Test
  void a_required_call_with_nothing_to_call_is_refused() {
    List<ToolOffer> nothing = List.of();
    ToolChoice any = new ToolChoice.Any();
    ToolChoice named = new ToolChoice.Named(new ToolName("absent"));

    assertThatThrownBy(() -> new Toolset(nothing, any))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("needs a tool on offer");
    assertThatThrownBy(() -> new Toolset(List.of(LOOKUP), named))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("absent");
  }

  /** Neither of these obliges a call, so neither needs anything on offer. */
  @Test
  void leaving_it_open_or_forbidding_it_is_coherent_with_nothing_on_offer() {
    assertThat(new Toolset(List.of(), ToolChoice.auto()).choice()).isEqualTo(new ToolChoice.Auto());
    assertThat(new Toolset(List.of(), new ToolChoice.None()).choice())
        .isEqualTo(new ToolChoice.None());
  }

  /**
   * A row written before the field existed decodes with nothing there. It is read as auto rather
   * than refused, because refusing would make the record of what a model was shown unreadable.
   */
  @Test
  void a_stored_request_from_before_this_field_existed_reads_as_auto() {
    assertThat(with(null).choice()).isEqualTo(new ToolChoice.Auto());
  }

  @Test
  void a_named_choice_keeps_the_name() {
    assertThat(with(new ToolChoice.Named(new ToolName("lookup"))).choice())
        .isEqualTo(new ToolChoice.Named(new ToolName("lookup")));
  }

  /** A name is the whole of what Named says, so there is nothing to be nameless about. */
  @Test
  void naming_no_tool_is_refused() {
    assertThatThrownBy(() -> new ToolChoice.Named(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("name");
  }

  /**
   * The four are values, so two of the same choice are the same choice.
   *
   * <p>Same-type comparisons only. Asserting that an {@code Any} differs from a {@code None} tests
   * the language rather than this file -- two different record classes are never equal, so it would
   * pass however these were written. What is worth pinning is that each stays a value: a record
   * turned into a class with identity equality fails these and would pass that.
   */
  @Test
  void the_choices_are_compared_by_what_they_say() {
    assertThat(new ToolChoice.Auto()).isEqualTo(ToolChoice.auto());
    assertThat(new ToolChoice.Any()).isEqualTo(new ToolChoice.Any());
    assertThat(new ToolChoice.None()).isEqualTo(new ToolChoice.None());
    assertThat(new ToolChoice.Named(new ToolName("a")))
        .isNotEqualTo(new ToolChoice.Named(new ToolName("b")));
  }
}
