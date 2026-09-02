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
package org.jwcarman.nessy.api.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WHICH model, by the name its provider knows it by")
class ModelIdTest {

  @Test
  @DisplayName("the of factory wraps the provider's own name")
  void of_wraps_the_name() {
    ModelId id = ModelId.of("claude-opus-5");

    assertThat(id.value()).isEqualTo("claude-opus-5");
  }

  @Test
  @DisplayName("a blank name is deployment misconfiguration, not a valid model choice")
  void refuses_a_blank_name() {
    assertThatThrownBy(() -> ModelId.of(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
  }
}
