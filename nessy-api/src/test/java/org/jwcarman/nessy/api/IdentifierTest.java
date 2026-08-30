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

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.model.ModelId;

class IdentifierTest {

  @Test
  void an_agent_id_carries_the_name_the_world_knows() {
    assertThat(AgentId.of("house-12").value()).isEqualTo("house-12");
  }

  @Test
  void a_blank_agent_id_is_refused() {
    assertThatThrownBy(() -> AgentId.of("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
  }

  @Test
  void a_blank_agent_type_is_refused() {
    assertThatThrownBy(() -> AgentType.of(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
  }

  @Test
  void a_blank_model_id_is_refused() {
    assertThatThrownBy(() -> ModelId.of(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
  }

  @Test
  void the_same_id_under_two_types_is_two_different_agents() {
    AgentType watchman = AgentType.of("watchman");
    AgentType support = AgentType.of("support");

    assertThat(watchman).isNotEqualTo(support);
    assertThat(AgentId.of("house-12")).isEqualTo(AgentId.of("house-12"));
  }
}
