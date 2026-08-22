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
package org.jwcarman.nessy.api.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class SealedInputsTest {

  sealed interface Vocabulary permits Restart, Shutdown {}

  record Restart(String host) implements Vocabulary {}

  record Shutdown(String reason) implements Vocabulary {}

  record NotSealed(String value) {}

  sealed interface CollidingVocabulary permits CollidingType {}

  record CollidingType(String type) implements CollidingVocabulary {}

  sealed interface NestedVocabulary permits Leaf, Nested {}

  record Leaf(String value) implements NestedVocabulary {}

  sealed interface Nested extends NestedVocabulary permits NestedLeaf {}

  record NestedLeaf(String value) implements Nested {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void a_sealed_interface_is_a_sealed_input() {
    assertThat(SealedInputs.isSealedInput(Vocabulary.class)).isTrue();
  }

  @Test
  void an_ordinary_record_is_not_a_sealed_input() {
    assertThat(SealedInputs.isSealedInput(NotSealed.class)).isFalse();
  }

  @Test
  void bind_matches_the_declared_type_and_populates_its_fields() {
    ObjectNode arguments =
        JsonNodeFactory.instance.objectNode().put("type", "Restart").put("host", "prod-eu");

    Vocabulary bound = SealedInputs.bind(Vocabulary.class, arguments, MAPPER);

    assertThat(bound).isEqualTo(new Restart("prod-eu"));
  }

  @Test
  void bind_matches_a_different_permitted_record_by_its_own_type_name() {
    ObjectNode arguments =
        JsonNodeFactory.instance.objectNode().put("type", "Shutdown").put("reason", "maintenance");

    Vocabulary bound = SealedInputs.bind(Vocabulary.class, arguments, MAPPER);

    assertThat(bound).isEqualTo(new Shutdown("maintenance"));
  }

  @Test
  void bind_rejects_an_unknown_type_naming_all_legal_types() {
    ObjectNode arguments =
        JsonNodeFactory.instance.objectNode().put("type", "Reboot").put("host", "prod-eu");

    assertThatThrownBy(() -> SealedInputs.bind(Vocabulary.class, arguments, MAPPER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Restart")
        .hasMessageContaining("Shutdown");
  }

  @Test
  void bind_rejects_a_missing_type_naming_all_legal_types() {
    ObjectNode arguments = JsonNodeFactory.instance.objectNode().put("host", "prod-eu");

    assertThatThrownBy(() -> SealedInputs.bind(Vocabulary.class, arguments, MAPPER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Restart")
        .hasMessageContaining("Shutdown");
  }

  @Test
  void bind_matches_the_type_name_case_sensitively() {
    ObjectNode arguments =
        JsonNodeFactory.instance.objectNode().put("type", "restart").put("host", "prod-eu");

    assertThatThrownBy(() -> SealedInputs.bind(Vocabulary.class, arguments, MAPPER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("restart")
        .hasMessageContaining("Restart")
        .hasMessageContaining("Shutdown");
  }

  @Test
  void bind_surfaces_the_mappers_error_when_the_body_cannot_bind_into_the_matched_record() {
    ObjectNode arguments =
        JsonNodeFactory.instance
            .objectNode()
            .put("type", "Restart")
            .set("host", JsonNodeFactory.instance.objectNode().put("nested", "not-a-string"));

    assertThatThrownBy(() -> SealedInputs.bind(Vocabulary.class, arguments, MAPPER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("host");
  }

  @Test
  void bind_refuses_a_matched_record_that_declares_its_own_type_component() {
    ObjectNode arguments = JsonNodeFactory.instance.objectNode().put("type", "CollidingType");

    assertThatThrownBy(() -> SealedInputs.bind(CollidingVocabulary.class, arguments, MAPPER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CollidingType")
        .hasMessageContaining("type");
  }

  /**
   * Final review round: {@code getRecordComponents()} returns {@code null} (not throws) for a
   * matched permit that is itself not a record — here a nested sealed interface permitted directly.
   * Before the fix this reached a bare NPE through {@code Stream.of(null)}, violating {@code
   * Codec}'s IllegalArgumentException javadoc contract.
   */
  @Test
  void bindRejectsAMatchedPermitThatIsNotARecordNamingItRatherThanNpe() {
    ObjectNode arguments = JsonNodeFactory.instance.objectNode().put("type", "Nested");

    assertThatThrownBy(() -> SealedInputs.bind(NestedVocabulary.class, arguments, MAPPER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Nested");
  }
}
