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
package org.jwcarman.nessy.api.block;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.tool.ToolResult;

/** The one place a {@link ToolResult} becomes wire content. */
@DisplayName("Attaching identity to a tool's result")
class ToolResultBlockTest {

  @Test
  @DisplayName("a success carries its content and no error flag")
  void a_success_carries_its_content() {
    CallId callId = CallId.of("call-1");

    ToolResultBlock block = ToolResultBlock.of(callId, ToolResult.ok("done"));

    assertThat(block.toolUseId()).isEqualTo(callId);
    assertThat(block.content()).containsExactly(new TextBlock("done"));
    assertThat(block.isError()).isFalse();
  }

  @Test
  @DisplayName(
      "a failure flattens to the isError flag the providers model, carrying the message as text")
  void a_failure_flattens_to_the_error_flag() {
    CallId callId = CallId.of("call-1");

    ToolResultBlock block =
        ToolResultBlock.of(callId, ToolResult.error("could not reach the host"));

    assertThat(block.toolUseId()).isEqualTo(callId);
    assertThat(block.content()).containsExactly(new TextBlock("could not reach the host"));
    assertThat(block.isError()).isTrue();
  }
}
