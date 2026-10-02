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
package org.jwcarman.nessy.backend.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ActionRequestTest {

  private static final CallId CALL = new CallId("c1");
  private static final ToolName TOOL = new ToolName("refund");

  @Test
  void a_tool_call_refuses_a_null_or_blank_action() {
    assertThatThrownBy(() -> new ActionRequest.ToolCall(CALL, TOOL, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("action must not be null");
    assertThatThrownBy(() -> new ActionRequest.ToolCall(CALL, TOOL, "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("action must not be blank");
  }

  @Test
  void a_tool_call_keeps_the_action_it_was_given() {
    assertThat(new ActionRequest.ToolCall(CALL, TOOL, "refund order 88").action())
        .isEqualTo("refund order 88");
  }
}
