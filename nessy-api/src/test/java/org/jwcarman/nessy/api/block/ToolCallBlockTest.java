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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.tool.ToolCall;

@DisplayName("The model asking for a tool")
class ToolCallBlockTest {

  @Test
  @DisplayName("id() is the id the answer must come back under")
  void id_is_the_wrapped_calls_id() {
    CallId callId = CallId.of("call-1");
    ToolCallBlock block =
        new ToolCallBlock(new ToolCall(callId, "read_file", JsonNodeFactory.instance.objectNode()));

    assertThat(block.id()).isEqualTo(callId);
  }
}
