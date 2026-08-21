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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SchemasTest {

  record ReadFile(
      @JsonPropertyDescription("Path relative to the workspace root") String path,
      Optional<Integer> maxLines) {}

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
}
