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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.spi.Reducer;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;

/**
 * Pins the validating constructors on the public records.
 *
 * <p>These guards are API: relaxing one after 1.0 is a behavior break, so they need tests that fail
 * when someone deletes them.
 */
class ValidationTest {

  @Test
  void a_blank_session_id_is_rejected() {
    assertThatThrownBy(() -> new SessionId("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_blank_park_token_is_rejected() {
    assertThatThrownBy(() -> new ParkToken(" ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_null_termination_policy_is_rejected() {
    assertThatThrownBy(() -> new Reducer(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void a_tool_call_without_an_id_is_rejected() {
    assertThatThrownBy(() -> new ToolCall(null, "echo", JsonNodeFactory.instance.objectNode()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_tool_call_without_a_name_is_rejected() {
    assertThatThrownBy(() -> new ToolCall("c1", " ", JsonNodeFactory.instance.objectNode()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_tool_result_block_without_content_is_rejected() {
    assertThatThrownBy(() -> new ToolResultBlock("c1", null, false))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void a_tool_spec_without_a_name_is_rejected() {
    assertThatThrownBy(() -> new ToolSpec("", "does things", JsonNodeFactory.instance.objectNode()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_model_settings_without_a_model_is_rejected() {
    assertThatThrownBy(() -> new ModelSettings(null, "", 1024, Set.of()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void a_model_settings_without_tokens_to_spend_is_rejected() {
    assertThatThrownBy(() -> new ModelSettings("fake-model", "", 0, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_model_request_without_a_model_is_rejected() {
    assertThatThrownBy(() -> new ModelRequest(List.of(), "system", " ", 1024, List.of(), Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
