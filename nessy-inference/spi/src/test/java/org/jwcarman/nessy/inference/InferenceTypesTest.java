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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Summary;

@DisplayName("The inference vocabulary")
class InferenceTypesTest {

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
    InferenceContext bare = InferenceContext.of(List.of());
    InferenceContext summarised =
        new InferenceContext(
            List.of(Summary.text(new TurnId(1), new TurnId(3), "a")), List.of(), List.of());

    assertThat(bare.hasSummaries()).isFalse();
    assertThat(summarised.hasSummaries()).isTrue();
    assertThat(new Failure.Rejected("too long").reason()).isEqualTo("too long");
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
