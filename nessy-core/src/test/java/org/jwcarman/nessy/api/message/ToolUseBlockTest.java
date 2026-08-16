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
package org.jwcarman.nessy.api.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolCall;

/** The tool-use block: a call, plus the optional continuity token some providers issue. */
class ToolUseBlockTest {

  private static ToolCall call() {
    return new ToolCall("call-1", "search", JsonNodeFactory.instance.objectNode());
  }

  @Test
  void the_convenience_constructor_yields_a_null_signature() {
    ToolUseBlock block = new ToolUseBlock(call());

    assertThat(block.signature()).isNull();
  }

  @Test
  void the_canonical_constructor_round_trips_the_signature() {
    ToolUseBlock block = new ToolUseBlock(call(), "sig-123");

    assertThat(block.signature()).isEqualTo("sig-123");
    assertThat(block.call()).isEqualTo(call());
  }

  @Test
  void a_null_call_is_rejected() {
    assertThatThrownBy(() -> new ToolUseBlock(null, "sig-123"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("call");
  }

  @Test
  void two_blocks_differing_only_in_signature_are_not_equal_since_replay_reuses_the_stored_value() {
    ToolUseBlock signed = new ToolUseBlock(call(), "sig-123");
    ToolUseBlock unsigned = new ToolUseBlock(call(), null);

    assertThat(signed).isNotEqualTo(unsigned);
  }
}
