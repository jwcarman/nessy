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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolSpec;
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
    assertThatThrownBy(() -> new ConversationId("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_blank_park_token_is_rejected() {
    assertThatThrownBy(() -> new ParkToken(" ")).isInstanceOf(IllegalArgumentException.class);
  }

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
    assertThatThrownBy(() -> new ToolResultBlock("c1", null, false))
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
  void a_model_settings_without_a_model_is_rejected() {
    assertThatThrownBy(() -> new ModelSettings(null, "", 1024, Set.of(), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void a_model_settings_without_tokens_to_spend_is_rejected() {
    assertThatThrownBy(() -> new ModelSettings("fake-model", "", 0, Set.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_model_settings_context_window_at_or_below_max_tokens_is_rejected() {
    assertThatThrownBy(() -> new ModelSettings("fake-model", "", 1024, Set.of(), 1024L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_model_settings_accepts_a_null_context_window() {
    var settings = new ModelSettings("fake-model", "", 1024, Set.of(), null);

    assertThat(settings.contextWindow()).isNull();
  }

  @Test
  void a_model_settings_context_window_above_max_tokens_is_accepted() {
    var settings = new ModelSettings("fake-model", "", 1024, Set.of(), 200_000L);

    assertThat(settings.contextWindow()).isEqualTo(200_000L);
  }

  @Test
  void a_model_request_without_a_model_is_rejected() {
    Context context = Context.of(List.of());

    assertThatThrownBy(
            () -> new ModelRequest(context, "system", " ", 1024, List.of(), Set.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_model_request_without_tokens_to_spend_is_rejected() {
    Context context = Context.of(List.of());

    assertThatThrownBy(
            () -> new ModelRequest(context, "system", "fake-model", 0, List.of(), Set.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_model_request_without_a_context_is_rejected() {
    assertThatThrownBy(
            () -> new ModelRequest(null, "system", "fake-model", 1024, List.of(), Set.of(), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void negative_token_counts_are_rejected() {
    assertThatThrownBy(() -> new Usage(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_negative_cached_input_token_count_is_rejected() {
    assertThatThrownBy(() -> new Usage(0, 0, -1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void plus_sums_all_three_usage_components() {
    var sum = new Usage(1, 2, 3).plus(new Usage(10, 20, 30));

    assertThat(sum).isEqualTo(new Usage(11, 22, 33));
  }

  @Test
  void a_model_request_accepts_a_null_response_schema() {
    var request =
        new ModelRequest(
            Context.of(List.of()), "system", "fake-model", 1024, List.of(), Set.of(), null);

    assertThat(request.responseSchema()).isNull();
  }

  @Test
  void a_model_request_carries_a_non_null_response_schema() {
    var schema = JsonNodeFactory.instance.objectNode().put("type", "object");

    var request =
        new ModelRequest(
            Context.of(List.of()), "system", "fake-model", 1024, List.of(), Set.of(), schema);

    assertThat(request.responseSchema()).isSameAs(schema);
  }
}
