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
package org.jwcarman.nessy.spi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Context;

/**
 * Pins the validating constructors on the public records.
 *
 * <p>These guards are API: relaxing one after 1.0 is a behavior break, so they need tests that fail
 * when someone deletes them.
 */
class ValidationTest {

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
