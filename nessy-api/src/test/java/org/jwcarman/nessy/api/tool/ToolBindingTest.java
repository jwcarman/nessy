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
package org.jwcarman.nessy.api.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;

/**
 * A binding is the application's statements ABOUT a tool — who may say no, how to explain a call —
 * kept apart from the tool's own statements about itself.
 */
@DisplayName("One tool, as a particular agent may use it")
class ToolBindingTest {

  record Args(String value) {}

  private static final Tool<Args> TOOL =
      new Tool<>() {
        @Override
        public Class<Args> inputType() {
          return Args.class;
        }

        @Override
        public String name() {
          return "noop";
        }

        @Override
        public String description() {
          return "does nothing";
        }

        @Override
        public Awaited<ToolResult> execute(ToolCallRequest<Args> call) {
          return Awaited.ready(ToolResult.ok("done"));
        }
      };

  private static final ActionRenderer<Args> RENDERER = ActionRenderer.byToString();

  @Test
  @DisplayName("carries the tool, the approver, and the renderer it was built with")
  void carries_its_three_fields() {
    Approver approver = Approver.always();

    ToolBinding<Args> binding = new ToolBinding<>(TOOL, approver, RENDERER);

    assertThat(binding.tool()).isSameAs(TOOL);
    assertThat(binding.approver()).isSameAs(approver);
    assertThat(binding.renderer()).isSameAs(RENDERER);
  }

  @Test
  @DisplayName("refuses a null tool")
  void refuses_a_null_tool() {
    assertThatThrownBy(() -> new ToolBinding<>(null, Approver.always(), RENDERER))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("tool must not be null");
  }

  @Test
  @DisplayName(
      "refuses a null approver, since every binding must carry one — even Approver.always()")
  void refuses_a_null_approver() {
    assertThatThrownBy(() -> new ToolBinding<>(TOOL, null, RENDERER))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("approver must not be null");
  }

  @Test
  @DisplayName("refuses a null renderer")
  void refuses_a_null_renderer() {
    assertThatThrownBy(() -> new ToolBinding<>(TOOL, Approver.always(), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("renderer must not be null");
  }
}
