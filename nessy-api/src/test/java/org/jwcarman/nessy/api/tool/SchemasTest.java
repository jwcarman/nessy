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

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

  sealed interface Vocabulary permits Restart, Shutdown {}

  record Restart(@JsonPropertyDescription("Target host") String host) implements Vocabulary {}

  record Shutdown(Optional<String> reason) implements Vocabulary {}

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
