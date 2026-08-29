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
package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolSpec;

/**
 * Pins the validating constructors on the public records.
 *
 * <p>These guards are API: relaxing one after 1.0 is a behavior break, so they need tests that fail
 * when someone deletes them.
 */
class ValidationTest {

  @Test
  void a_tool_call_without_an_id_is_rejected() {
    var arguments = JsonNodeFactory.instance.objectNode();

    assertThatThrownBy(() -> new ToolCall(null, "echo", arguments))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_tool_call_without_a_name_is_rejected() {
    var arguments = JsonNodeFactory.instance.objectNode();

    assertThatThrownBy(() -> new ToolCall("c1", " ", arguments))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_tool_result_block_without_content_is_rejected() {
    assertThatThrownBy(() -> ToolResultBlock.of("c1", null, false))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void a_context_without_a_message_list_is_rejected() {
    assertThatThrownBy(() -> new Context(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void a_tool_spec_without_a_name_is_rejected() {
    var schema = JsonNodeFactory.instance.objectNode();

    assertThatThrownBy(() -> new ToolSpec("", "does things", schema))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void negative_token_counts_are_rejected() {
    assertThatThrownBy(() -> new Usage(-1, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_negative_cache_read_input_token_count_is_rejected() {
    assertThatThrownBy(() -> new Usage(0, 0, -1, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_negative_cache_write_input_token_count_is_rejected() {
    assertThatThrownBy(() -> new Usage(0, 0, 0, -1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void plus_sums_all_four_usage_components() {
    var sum = new Usage(1, 2, 3, 4).plus(new Usage(10, 20, 30, 40));

    assertThat(sum).isEqualTo(new Usage(11, 22, 33, 44));
  }
}
