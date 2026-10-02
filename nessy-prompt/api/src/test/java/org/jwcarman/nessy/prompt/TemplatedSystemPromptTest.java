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
package org.jwcarman.nessy.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.SystemPrompt;

@DisplayName("A templated system prompt")
class TemplatedSystemPromptTest {

  /** A one-hole engine, so the framework is tested without any real one. */
  private static final PromptTemplateFactory ENGINE =
      source ->
          variables ->
              variables
                  .variable("who")
                  .map(who -> source.replace("<who>", who))
                  .orElseThrow(() -> new IllegalArgumentException("nothing fills <who>"));

  @Test
  void renders_once_into_a_system_prompt() {
    SystemPrompt prompt =
        TemplatedSystemPrompt.render(
            ENGINE, "You serve <who>.", PromptVariables.of(Map.of("who", "James")));

    assertThat(prompt.value()).isEqualTo("You serve James.");
  }

  @Test
  void renders_a_template_already_compiled() {
    PromptTemplate template = ENGINE.compile("You serve <who>.");

    SystemPrompt prompt =
        TemplatedSystemPrompt.render(template, PromptVariables.of(Map.of("who", "Ada")));

    assertThat(prompt.value()).isEqualTo("You serve Ada.");
  }

  @Test
  @DisplayName("variables are asked in order and the first answer wins")
  void variables_compose_in_order() {
    SystemPrompt prompt =
        TemplatedSystemPrompt.render(
            ENGINE,
            "You serve <who>.",
            PromptVariables.firstOf(
                List.of(
                    PromptVariables.of(Map.of("other", "x")),
                    PromptVariables.of(Map.of("who", "the first")),
                    PromptVariables.of(Map.of("who", "the second")))));

    assertThat(prompt.value()).isEqualTo("You serve the first.");
  }

  @Test
  @DisplayName("a supplied variable is read once, however often the prompt is used")
  void a_supplied_variable_is_read_once() {
    AtomicInteger calls = new AtomicInteger();
    SystemPrompt prompt =
        TemplatedSystemPrompt.render(
            ENGINE,
            "Call <who>.",
            PromptVariables.supplied("who", () -> "number " + calls.incrementAndGet()));

    assertThat(prompt.value()).isEqualTo("Call number 1.");
    assertThat(prompt.value()).isEqualTo("Call number 1.");
    assertThat(calls).hasValue(1);
  }

  @Test
  @DisplayName("a hole nothing fills is the engine's to refuse, and the refusal is not swallowed")
  void an_unfilled_hole_throws() {
    PromptVariables none = PromptVariables.none();

    assertThatThrownBy(() -> TemplatedSystemPrompt.render(ENGINE, "You serve <who>.", none))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("who");
  }

  @Test
  void firstOf_with_no_variables_answers_nothing() {
    assertThat(PromptVariables.firstOf(List.of()).variable("anything")).isEmpty();
  }

  @Test
  void supplied_answers_only_its_own_name() {
    PromptVariables supplied = PromptVariables.supplied("today", () -> "Tuesday");

    assertThat(supplied.variable("today")).contains("Tuesday");
    assertThat(supplied.variable("tomorrow")).isEmpty();
  }
}
