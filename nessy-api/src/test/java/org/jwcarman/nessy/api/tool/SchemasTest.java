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

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.SchemaVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SchemasTest {

  record ReadFile(
      @JsonPropertyDescription("Path relative to the workspace root") String path,
      Optional<Integer> maxLines) {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Restart.class, name = "Restart"),
    @JsonSubTypes.Type(value = Shutdown.class, name = "Shutdown")
  })
  sealed interface Vocabulary permits Restart, Shutdown {}

  record Restart(@JsonPropertyDescription("Target host") String host) implements Vocabulary {}

  record Shutdown(Optional<String> reason) implements Vocabulary {}

  sealed interface UnannotatedVocabulary permits UnannotatedMember {}

  record UnannotatedMember(String value) implements UnannotatedVocabulary {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({@JsonSubTypes.Type(value = CollidingType.class, name = "CollidingType")})
  sealed interface CollidingVocabulary permits CollidingType {}

  record CollidingType(String type) implements CollidingVocabulary {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Clean.class, name = "Clean"),
    @JsonSubTypes.Type(value = CollidingSecondBranch.class, name = "CollidingSecondBranch")
  })
  sealed interface TwoBranchCollidingVocabulary permits Clean, CollidingSecondBranch {}

  record Clean(String value) implements TwoBranchCollidingVocabulary {}

  record CollidingSecondBranch(String type) implements TwoBranchCollidingVocabulary {}

  record NestedTarget(String name) {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = RestartTarget.class, name = "Restart"),
    @JsonSubTypes.Type(value = ShutdownTarget.class, name = "Shutdown")
  })
  sealed interface VocabularyWithSharedNestedRecord permits RestartTarget, ShutdownTarget {}

  record RestartTarget(NestedTarget target) implements VocabularyWithSharedNestedRecord {}

  record ShutdownTarget(NestedTarget target) implements VocabularyWithSharedNestedRecord {}

  @Test
  void components_become_properties() {
    ObjectNode schema = Schemas.of(ReadFile.class);

    assertThat(schema.get("properties").has("path")).isTrue();
    assertThat(schema.get("properties").has("maxLines")).isTrue();
  }

  @Test
  void descriptions_survive_into_the_schema() {
    ObjectNode schema = Schemas.of(ReadFile.class);

    assertThat(schema.get("properties").get("path").get("description").asText())
        .isEqualTo("Path relative to the workspace root");
  }

  @Test
  void everything_is_required_except_optionals() {
    ObjectNode schema = Schemas.of(ReadFile.class);

    assertThat(schema.get("required")).hasSize(1);
    assertThat(schema.get("required").get(0).asText()).isEqualTo("path");
  }

  @Test
  void the_schema_describes_an_object() {
    ObjectNode schema = Schemas.of(ReadFile.class);

    assertThat(schema.get("type").asText()).isEqualTo("object");
  }

  @Nested
  class ASealedInterfaceInputType {

    @Test
    void the_schema_is_a_oneOf_with_one_branch_per_permitted_record() {
      ObjectNode schema = Schemas.of(Vocabulary.class);

      assertThat(schema.get("oneOf")).isNotNull();
      assertThat(schema.get("oneOf")).hasSize(2);
    }

    @Test
    void each_branch_carries_a_required_const_discriminator_named_type() {
      ObjectNode schema = Schemas.of(Vocabulary.class);

      ObjectNode restartBranch = branchNamed(schema, "Restart");
      ObjectNode shutdownBranch = branchNamed(schema, "Shutdown");

      assertThat(restartBranch.get("properties").get("type").get("const").asText())
          .isEqualTo("Restart");
      assertThat(requiredNames(restartBranch)).contains("type");

      assertThat(shutdownBranch.get("properties").get("type").get("const").asText())
          .isEqualTo("Shutdown");
      assertThat(requiredNames(shutdownBranch)).contains("type");
    }

    @Test
    void each_branch_still_carries_its_own_records_properties() {
      ObjectNode schema = Schemas.of(Vocabulary.class);

      ObjectNode restartBranch = branchNamed(schema, "Restart");

      assertThat(restartBranch.get("properties").has("host")).isTrue();
      assertThat(requiredNames(restartBranch)).contains("host");
    }

    @Test
    void an_optional_component_is_not_required_on_its_branch() {
      ObjectNode schema = Schemas.of(Vocabulary.class);

      ObjectNode shutdownBranch = branchNamed(schema, "Shutdown");

      assertThat(requiredNames(shutdownBranch)).doesNotContain("reason");
    }

    @Test
    void the_root_carries_schema_and_no_branch_does() {
      ObjectNode schema = Schemas.of(Vocabulary.class);

      assertThat(schema.get("$schema").asText())
          .isEqualTo(SchemaVersion.DRAFT_2020_12.getIdentifier());
      assertThat(schema.get("oneOf")).hasSize(2);
      for (JsonNode branch : schema.get("oneOf")) {
        assertThat(branch.has("$schema")).isFalse();
      }
    }

    @Test
    void an_unannotated_sealed_interface_is_rejected_with_a_message_naming_what_to_add() {
      assertThatThrownBy(() -> Schemas.of(UnannotatedVocabulary.class))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("UnannotatedVocabulary")
          .hasMessageContaining("@JsonTypeInfo")
          .hasMessageContaining("@JsonSubTypes");
    }

    @Test
    void a_record_declaring_its_own_type_component_fails_loudly_at_schema_time() {
      assertThatThrownBy(() -> Schemas.of(CollidingVocabulary.class))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("CollidingType")
          .hasMessageContaining("type");
    }

    @Test
    void a_two_branch_vocabularys_colliding_second_branch_fails_loudly_naming_it() {
      assertThatThrownBy(() -> Schemas.of(TwoBranchCollidingVocabulary.class))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("CollidingSecondBranch")
          .hasMessageContaining("type");
    }

    @Test
    void two_branches_sharing_a_nested_record_carry_one_shared_defs_entry_at_the_root() {
      ObjectNode schema = Schemas.of(VocabularyWithSharedNestedRecord.class);

      assertThat(schema.has("$defs")).isTrue();
      assertThat(schema.get("$defs").has("NestedTarget")).isTrue();
      for (JsonNode branch : schema.get("oneOf")) {
        assertThat(branch.has("$defs")).isFalse();
      }
    }

    @Test
    void every_ref_in_a_shared_nested_record_schema_resolves_against_the_root() {
      ObjectNode schema = Schemas.of(VocabularyWithSharedNestedRecord.class);

      List<String> refs = new ArrayList<>();
      collectRefs(schema, refs);

      assertThat(refs).isNotEmpty();
      for (String ref : refs) {
        assertThat(ref).startsWith("#/");
        assertThat(schema.at(ref.substring(1)).isMissingNode()).isFalse();
      }
    }

    private void collectRefs(JsonNode node, List<String> refs) {
      if (node.isObject()) {
        JsonNode ref = node.get("$ref");
        if (ref != null) {
          refs.add(ref.asText());
        }
        node.fields().forEachRemaining(entry -> collectRefs(entry.getValue(), refs));
      } else if (node.isArray()) {
        node.forEach(child -> collectRefs(child, refs));
      }
    }

    private ObjectNode branchNamed(ObjectNode schema, String typeName) {
      for (JsonNode branch : schema.get("oneOf")) {
        JsonNode constNode = branch.at("/properties/type/const");
        if (constNode.asText().equals(typeName)) {
          return (ObjectNode) branch;
        }
      }
      throw new NoSuchElementException("no branch named " + typeName);
    }

    private List<String> requiredNames(ObjectNode branch) {
      var required = branch.get("required");
      assertThat(required).isNotEmpty();
      var names = new ArrayList<String>();
      required.forEach(node -> names.add(node.asText()));
      return names;
    }
  }
}
