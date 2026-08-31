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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SchemasTest {

  @Nested
  @DisplayName("A tool that takes no arguments")
  class ANoArgumentInputType {

    record Nothing() {}

    /**
     * Victools generates {@code {"type":"object"}} for a record with no components, which is valid
     * JSON Schema and is REJECTED on the wire: the OpenAI function-calling shape requires {@code
     * parameters.properties} to be present. Measured against LM Studio 2026-08-31, which answers
     * {@code invalid_type ... path: function.parameters.properties}.
     *
     * <p>A no-argument tool is an ordinary thing to want — "what time is it", "list the containers"
     * — so a schema that cannot be sent is a bug here, not in every such tool.
     */
    @Test
    @DisplayName("still carries a properties object, because the wire requires one")
    void has_an_empty_properties_object() {
      ObjectNode schema = Schemas.of(Nothing.class);

      assertThat(schema.get("type").asText()).isEqualTo("object");
      assertThat(schema.has("properties")).isTrue();
      assertThat(schema.get("properties").isObject()).isTrue();
      assertThat(schema.get("properties")).isEmpty();
    }
  }

  @Nested
  @DisplayName("An ordinary input type")
  class APlainRecord {

    record Dated(@JsonPropertyDescription("ISO-8601, e.g. 2026-12-25") String date) {}

    @Test
    void keeps_the_properties_it_generated() {
      ObjectNode schema = Schemas.of(Dated.class);

      assertThat(schema.get("properties").get("date").get("type").asText()).isEqualTo("string");
    }

    /** The one thing a generator cannot infer, and the part a model actually reads. */
    @Test
    void carries_the_description_written_on_the_component() {
      ObjectNode schema = Schemas.of(Dated.class);

      assertThat(schema.get("properties").get("date").get("description").asText())
          .isEqualTo("ISO-8601, e.g. 2026-12-25");
    }
  }

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

  record NestedTarget(String name) {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = RestartTarget.class, name = "Restart"),
    @JsonSubTypes.Type(value = ShutdownTarget.class, name = "Shutdown")
  })
  sealed interface VocabularyWithSharedNestedRecord permits RestartTarget, ShutdownTarget {}

  record RestartTarget(NestedTarget target) implements VocabularyWithSharedNestedRecord {}

  record ShutdownTarget(NestedTarget target) implements VocabularyWithSharedNestedRecord {}

  /**
   * A sealed ABSTRACT CLASS (not an interface) carrying the same polymorphism annotations: victools
   * still derives {@code anyOf} for it via the Jackson module (annotation-driven, not {@code
   * isInterface()}-gated), but {@link Schemas#of} only routes through the dedicated {@code
   * isInterface() && isSealed()} path for interfaces — this type instead exercises the plain {@code
   * GENERATOR.generateSchema} branch, proving {@link Schemas#of}'s {@code anyOf}→{@code oneOf}
   * normalization applies there too, not only on the sealed-interface path.
   */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = ClassRestart.class, name = "ClassRestart"),
    @JsonSubTypes.Type(value = ClassShutdown.class, name = "ClassShutdown")
  })
  abstract static sealed class ClassVocabulary permits ClassRestart, ClassShutdown {}

  static final class ClassRestart extends ClassVocabulary {
    private String host;

    public String getHost() {
      return host;
    }

    public void setHost(String host) {
      this.host = host;
    }
  }

  static final class ClassShutdown extends ClassVocabulary {
    private String reason;

    public String getReason() {
      return reason;
    }

    public void setReason(String reason) {
      this.reason = reason;
    }
  }

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

  @Nested
  class ASealedAbstractClassInputType {

    @Test
    void the_schema_is_a_oneOf_not_the_raw_victools_anyOf() {
      ObjectNode schema = Schemas.of(ClassVocabulary.class);

      assertThat(schema.has("anyOf")).isFalse();
      assertThat(schema.get("oneOf")).isNotNull();
      assertThat(schema.get("oneOf")).hasSize(2);
    }

    @Test
    void each_branch_still_carries_its_own_discriminator_const() {
      ObjectNode schema = Schemas.of(ClassVocabulary.class);

      var discriminators = new ArrayList<String>();
      for (JsonNode branch : schema.get("oneOf")) {
        discriminators.add(branch.at("/properties/type/const").asText());
      }

      assertThat(discriminators).containsExactlyInAnyOrder("ClassRestart", "ClassShutdown");
    }
  }
}
