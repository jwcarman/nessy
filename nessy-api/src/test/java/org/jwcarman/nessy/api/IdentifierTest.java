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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
  @DisplayName("an id is bounded, because it is a primary-key column")
  void an_over_long_id_is_refused_where_it_is_written() {
    String tooLong = "a".repeat(257);

    assertThatThrownBy(() -> AgentId.of(tooLong))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at most 256 characters")
        .hasMessageContaining("257");
  }

  @Test
  void an_id_of_exactly_the_limit_is_allowed() {
    assertThat(AgentId.of("a".repeat(256)).value()).hasSize(256);
  }

  @ParameterizedTest
  @ValueSource(strings = {"a|b", "a/b", "a b", "a#b", "ünïcode", "tab\there"})
  @DisplayName("characters something downstream would read as structure are refused")
  void an_id_carrying_structure_is_refused(String illegal) {
    assertThatThrownBy(() -> AgentId.of(illegal))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must contain only letters, digits or");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "house-12",
        "0195f0a1-7c3e-7000-8000-2b9a5f8c1d44",
        "PROJ-123",
        "acme:user-7",
        "someone@example.com",
        "call_abc123",
        "toolu_01A09q90qw90lq917835lq9"
      })
  @DisplayName("the identifiers people and vendors actually use are allowed")
  void a_real_identifier_is_allowed(String legal) {
    assertThat(AgentId.of(legal).value()).isEqualTo(legal);
  }

  @Test
  @DisplayName("a call id is a provider's string, and is checked on the way in")
  void an_unusable_call_id_fails_at_the_parser_not_at_the_index() {
    assertThatThrownBy(() -> CallId.of("call_" + "x".repeat(300)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("call id");
  }

  @Test
  void a_turn_id_names_its_own_kind_when_it_is_wrong() {
    assertThatThrownBy(() -> TurnId.of(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("turn id");
  }

  @Test
  @DisplayName("a turn and a call cannot be transposed, which is the point of the types")
  void a_turn_id_is_not_a_call_id() {
    assertThat((Object) TurnId.of("same")).isNotEqualTo(CallId.of("same"));
  }

  @Test
  @DisplayName("an id serializes as its own string, so a policy reads input.callId")
  void an_identifier_is_json_transparent() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    String json = mapper.writeValueAsString(new Envelope(AgentId.of("house-12"), CallId.of("c1")));

    assertThat(json).isEqualTo("{\"agentId\":\"house-12\",\"callId\":\"c1\"}");
    assertThat(mapper.readValue(json, Envelope.class).callId()).isEqualTo(CallId.of("c1"));
  }

  record Envelope(AgentId agentId, CallId callId) {}

  @Test
  void the_same_id_under_two_types_is_two_different_agents() {
    AgentType watchman = AgentType.of("watchman");
    AgentType support = AgentType.of("support");

    assertThat(watchman).isNotEqualTo(support);
    assertThat(AgentId.of("house-12")).isEqualTo(AgentId.of("house-12"));
  }
}
