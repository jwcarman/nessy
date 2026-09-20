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
package org.jwcarman.nessy.spi.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.SystemPrompt;
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

  private static InferenceRequest with(ToolChoice choice) {
    return new InferenceRequest(
        SYSTEM, InferenceContext.of(List.of()), List.of(), choice, InferenceOptions.of("a-model"));
  }

  /** The four-argument constructor is what every caller written before this used. */
  @Test
  void a_request_that_does_not_mention_choosing_leaves_it_to_the_model() {
    InferenceRequest request =
        new InferenceRequest(
            SYSTEM, InferenceContext.of(List.of()), List.of(), InferenceOptions.of("a-model"));

    assertThat(request.toolChoice()).isEqualTo(new ToolChoice.Auto());
  }

  /**
   * A row written before the field existed decodes with nothing there. It is read as auto rather
   * than refused, because refusing would make the record of what a model was shown unreadable.
   */
  @Test
  void a_stored_request_from_before_this_field_existed_reads_as_auto() {
    assertThat(with(null).toolChoice()).isEqualTo(new ToolChoice.Auto());
  }

  @Test
  void a_named_choice_keeps_the_name() {
    assertThat(with(new ToolChoice.Named(new ToolName("lookup"))).toolChoice())
        .isEqualTo(new ToolChoice.Named(new ToolName("lookup")));
  }

  /** A name is the whole of what Named says, so there is nothing to be nameless about. */
  @Test
  void naming_no_tool_is_refused() {
    assertThatThrownBy(() -> new ToolChoice.Named(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("name");
  }

  /** The four are values, so two of the same choice are the same choice. */
  @Test
  void the_choices_are_compared_by_what_they_say() {
    assertThat(new ToolChoice.Auto()).isEqualTo(ToolChoice.auto());
    assertThat(new ToolChoice.Any()).isNotEqualTo(new ToolChoice.None());
    assertThat(new ToolChoice.Named(new ToolName("a")))
        .isNotEqualTo(new ToolChoice.Named(new ToolName("b")));
  }
}
