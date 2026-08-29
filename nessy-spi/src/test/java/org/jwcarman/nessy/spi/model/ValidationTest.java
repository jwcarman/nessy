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
import static org.assertj.core.api.Assertions.assertThatCode;
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
  void a_model_settings_without_tokens_to_spend_is_rejected() {
    assertThatThrownBy(() -> new ModelSettings(0, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void the_defaults_use_the_published_max_tokens_constant_and_require_nothing() {
    var settings = ModelSettings.defaults();

    assertThat(settings.maxTokens()).isEqualTo(ModelSettings.DEFAULT_MAX_TOKENS);
    assertThat(settings.required()).isEmpty();
  }

  @Test
  void settings_are_rejected_by_a_model_that_lacks_a_required_capability() {
    var settings = new ModelSettings(1024, Set.of(Capability.IMAGE_INPUT));
    var textOnly = new ModelDescription("text-only", "test", 200_000, Set.of());

    assertThatThrownBy(() -> settings.requireSatisfiedBy(textOnly))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("IMAGE_INPUT");
  }

  @Test
  void settings_are_rejected_by_a_model_whose_window_cannot_hold_the_answer() {
    var settings = new ModelSettings(200_000, Set.of());
    var small = new ModelDescription("small", "test", 8_192, Set.of());

    assertThatThrownBy(() -> settings.requireSatisfiedBy(small))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("context window");
  }

  @Test
  void settings_a_model_can_honour_pass_without_complaint() {
    var settings = new ModelSettings(1024, Set.of(Capability.THINKING));
    var capable =
        new ModelDescription(
            "capable", "test", 200_000, Set.of(Capability.THINKING, Capability.IMAGE_INPUT));

    assertThatCode(() -> settings.requireSatisfiedBy(capable)).doesNotThrowAnyException();
  }

  @Test
  void a_model_request_without_tokens_to_spend_is_rejected() {
    Context context = Context.of(List.of());

    assertThatThrownBy(() -> new ModelRequest(context, "system", 0, List.of(), Set.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_model_request_without_a_context_is_rejected() {
    assertThatThrownBy(() -> new ModelRequest(null, "system", 1024, List.of(), Set.of(), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void a_model_request_accepts_a_null_response_schema() {
    var request =
        new ModelRequest(Context.of(List.of()), "system", 1024, List.of(), Set.of(), null);

    assertThat(request.responseSchema()).isNull();
  }

  @Test
  void a_model_request_carries_a_non_null_response_schema() {
    var schema = JsonNodeFactory.instance.objectNode().put("type", "object");

    var request =
        new ModelRequest(Context.of(List.of()), "system", 1024, List.of(), Set.of(), schema);

    assertThat(request.responseSchema()).isSameAs(schema);
  }
}
